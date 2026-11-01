package dev.loong.engine.rules

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * external java 声明的编译期校验（design §6.2：静态可确定的外部声明）。
 *
 * 用真实 JVM 类做反射核对，把「签名靠猜」的运行期炸裂（NoSuchMethodError）
 * 提前到编辑响应里：类不存在 → E_EXTERNAL_CLASS_NOT_FOUND；方法名存在但参数/
 * 返回对不上 → E_EXTERNAL_METHOD_NOT_FOUND（附可用重载）；描述符不符 →
 * E_EXTERNAL_DESCRIPTOR_MISMATCH。
 *
 * 纯函数、ClassLoader 由调用方注入（daemon 用 jvm-deps/libs 感知的 loader），
 * engine 核心编译路径不依赖类加载。类型映射只覆盖可静态判定的子集
 * （内建类型、String、外部类、Option/Result/Array/Map/Handle）；用户 struct
 * 等复杂类型不做误报校验（返回 null = 跳过该方法核对）。
 */
object ExternalVerifier {

    fun verify(table: NodeTable, root: NodeId, loader: ClassLoader): List<Diagnostic> {
        val diags = ArrayList<Diagnostic>()
        // 只校验本模块根下的 ExternalClass（依赖接口 AST 合并进来的声明由依赖方自己校验）
        val externals = HashMap<String, String>() // 短名 → fqn
        for (decl in table[root].kids) {
            if (table[decl].kind != Kind.ExternalClass) continue
            externals[fs(table, decl, "class_fqn").substringAfterLast('.')] = fs(table, decl, "class_fqn")
        }
        for (decl in table[root].kids) {
            if (table[decl].kind != Kind.ExternalClass) continue
            val fqn = fs(table, decl, "class_fqn")
            val cls = try {
                Class.forName(fqn, false, loader)
            } catch (e: Throwable) {
                diags += Diagnostic(
                    decl, Severity.Error, DiagCodes.E_EXTERNAL_CLASS_NOT_FOUND,
                    "external class '$fqn' not found on the classpath " +
                        "(JDK classes work out of the box; third-party jars need jvm-deps.json or libs/)",
                )
                continue
            }
            // 进程终结类黑名单：测试在 daemon 进程内反射执行（TestRunner），声明并调用
            // exit/halt 会直接杀死 daemon（MCP 连接「无字节返回」的元凶之一）。
            val lethal = LETHAL_METHODS[cls.name]
            if (lethal != null) {
                diags += Diagnostic(
                    decl, Severity.Error, DiagCodes.E_EXTERNAL_METHOD_NOT_FOUND,
                    "declaring '${cls.name}' is not allowed: ${lethal} terminates the loongd process; " +
                        "end a test by failing an assertion instead of exiting",
                )
                continue
            }
            for (m in table[decl].kids.filter { table[it].kind == Kind.ExternalMethod }) {
                verifyMethod(table, m, cls, externals, loader, diags)
            }
        }
        return diags
    }

    /** 会终结进程的类 → 触发原因（external 声明黑名单）。 */
    private val LETHAL_METHODS = mapOf(
        "java.lang.System" to "System.exit / load / loadLibrary",
        "java.lang.Runtime" to "Runtime.exit / halt",
    )

    private fun verifyMethod(
        table: NodeTable,
        m: NodeId,
        cls: Class<*>,
        externals: Map<String, String>,
        loader: ClassLoader,
        diags: ArrayList<Diagnostic>,
    ) {
        val kind = fs(table, m, "call_kind")
        val loongName = fs(table, m, "name")
        val jvmName = table[m].fields["jvm_name"]?.let { (it as FieldValue.Str).value }?.takeIf { it.isNotEmpty() }
            ?: loongName
        val params = table[m].kids.filter { table[it].kind == Kind.Param }
            .map { fs(table, it, "type") }
        // instance 的首参是 self（接收者），JVM 签名不含它
        val sigParams = if (kind == "instance" && params.isNotEmpty()) params.drop(1) else params
        val ret = fs(table, m, "return_type")

        val types = (sigParams + ret).map { jvmClassOf(it, externals, loader) }
        if (types.any { it == null }) return // 含不可静态判定类型（用户 struct 等）：不误报，跳过
        val ps = types.dropLast(1).map { it!! }
        val r = types.last()!!

        val what = "$jvmName(${ps.joinToString(",") { it.simpleName }}) -> ${r.simpleName}"
        fun reportOverloads(candidates: List<String>) {
            diags += Diagnostic(
                m, Severity.Error, DiagCodes.E_EXTERNAL_METHOD_NOT_FOUND,
                "no matching JVM method for '$what' on ${cls.name}" +
                    if (candidates.isEmpty()) "" else "; available: ${candidates.joinToString(" | ")}",
            )
        }

        when (kind) {
            "constructor" -> {
                val ctors = cls.constructors.filter { it.parameterTypes.contentEquals(ps.toTypedArray()) }
                if (ctors.isEmpty()) {
                    diags += Diagnostic(
                        m, Severity.Error, DiagCodes.E_EXTERNAL_METHOD_NOT_FOUND,
                        "no matching constructor for '$what' on ${cls.name}; available: " +
                            cls.constructors.joinToString(" | ") {
                                "<init>(" + it.parameterTypes.joinToString(",") { t -> t.simpleName } + ")"
                            },
                    )
                }
            }
            "static" -> {
                val named = cls.methods.filter {
                    Modifier.isStatic(it.modifiers) && it.name == jvmName && paramsCompatible(it, ps)
                }
                if (named.isEmpty()) {
                    reportOverloads(
                        cls.methods.filter { Modifier.isStatic(it.modifiers) && it.name == jvmName }
                            .map { render(it.name, it) },
                    )
                    return
                }
                if (named.none { returnsCompatible(it, r) }) {
                    diags += Diagnostic(
                        m, Severity.Error, DiagCodes.E_EXTERNAL_DESCRIPTOR_MISMATCH,
                        "'$what' found but returns ${named.first().returnType.simpleName}",
                    )
                }
            }
            else -> {
                val named = cls.methods.filter {
                    !Modifier.isStatic(it.modifiers) && it.name == jvmName && paramsCompatible(it, ps)
                }
                if (named.isEmpty()) {
                    reportOverloads(
                        cls.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == jvmName }
                            .map { render(it.name, it) },
                    )
                    return
                }
                if (named.none { returnsCompatible(it, r) }) {
                    diags += Diagnostic(
                        m, Severity.Error, DiagCodes.E_EXTERNAL_DESCRIPTOR_MISMATCH,
                        "'$what' found but returns ${named.first().returnType.simpleName}",
                    )
                }
            }
        }
    }

    /** 参数核对：精确匹配；Object[] 期望接受任何协变数组（CodeGenerator 的擦除表示）。 */
    private fun paramsCompatible(m: Method, ps: List<Class<*>>): Boolean {
        if (m.parameterCount != ps.size) return false
        return m.parameterTypes.zip(ps).all { (actual, expected) -> assignable(actual, expected) }
    }

    private fun returnsCompatible(m: Method, r: Class<*>): Boolean = assignable(m.returnType, r)

    private fun assignable(actual: Class<*>, expected: Class<*>): Boolean {
        if (actual == expected) return true
        // 期望 Object[]（Array<T> 擦除）时，实际任何数组都可（元素协变使用）
        if (expected == Array<Any>::class.java && actual.isArray) return true
        return false
    }

    private fun render(name: String, m: Method): String =
        "$name(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}"

    /** loong 类型文本 → JVM Class；不可静态判定返回 null（调用方跳过该方法）。 */
    private fun jvmClassOf(text: String, externals: Map<String, String>, loader: ClassLoader): Class<*>? {
        val base = text.substringBefore('<').trim()
        return when (base) {
            "Int" -> java.lang.Long.TYPE
            "Int32" -> java.lang.Integer.TYPE
            "Float" -> java.lang.Double.TYPE
            "Bool" -> java.lang.Boolean.TYPE
            "Char" -> java.lang.Character.TYPE
            "String" -> java.lang.String::class.java
            "Unit" -> java.lang.Void.TYPE
            "Array" -> Array<Any>::class.java
            "Map" -> java.util.HashMap::class.java
            "Handle" -> java.util.concurrent.FutureTask::class.java
            // Variant 经 loader 解析（engine 主代码不编译期依赖 runtime 模块）
            "Option", "Result" ->
                runCatching { Class.forName("dev.loong.runtime.Variant", false, loader) }.getOrNull()
            else -> externals[base]?.let { runCatching { Class.forName(it, false, loader) }.getOrNull() }
        }
    }

    private fun fs(table: NodeTable, id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
