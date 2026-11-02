package dev.loong.engine.rules

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind

/**
 * AST 字段 schema（ast-schema-v1.md §4 字段列的可执行形态，与 Parser 产出严格一致）。
 *
 * 与 [StructuralInvariants]（kids 形态）互补：这里约束 fields——名称、值类型、必填，
 * 以及「由后端填充」的派生字段（Path.full_name/resolved_id、Test.target_id：输入可带，
 * 只作回灌，名字解析阶段一律重算）。
 *
 * 三个消费方共用同一张表：
 * 1. daemon 写入（树/单节点）：未知字段、缺必填、类型不符 → 整批拒绝并列出可用字段，
 *    杜绝「写了 name 而非 written、字段被静默忽略」这类错位；
 * 2. Workspace.recompute 门禁（[StructuralInvariants.check]）：缺必填/类型不符 →
 *    E_INVARIANT_VIOLATION 点名节点（不拒未知字段——容忍持久化/链接产物的额外键）；
 * 3. MCP list_ast_kinds / reference 文档：自描述。
 */
object FieldSchema {

    enum class Type { Str, Bool, Ref }

    /**
     * 单字段规格。[required] 缺失即违例；[derived] 由名字解析阶段填充，输入可带但不要求。
     * Bool 字段缺失一律按 false 读（Renderer/TypeChecker 的 fb 语义），故不设必填。
     */
    class Spec(
        val name: String,
        val type: Type,
        val required: Boolean,
        val derived: Boolean,
        val doc: String,
    ) {
        /** 文档/工具输出用的一行描述：`written(str, 必填; 源文本路径)`。 */
        fun describe(): String {
            val tag = when {
                derived -> "解析填充"
                required -> "必填"
                else -> "可选"
            }
            val t = type.name.lowercase()
            return if (doc.isEmpty()) "$name($t, $tag)" else "$name($t, $tag; $doc)"
        }
    }

    private fun req(name: String, doc: String = "") = Spec(name, Type.Str, required = true, derived = false, doc = doc)

    private fun opt(name: String, doc: String = "") = Spec(name, Type.Str, required = false, derived = false, doc = doc)

    private fun optBool(name: String, doc: String = "") = Spec(name, Type.Bool, required = false, derived = false, doc = doc)

    private fun derivedStr(name: String, doc: String = "") = Spec(name, Type.Str, required = false, derived = true, doc = doc)

    private fun derivedRef(name: String, doc: String = "") = Spec(name, Type.Ref, required = false, derived = true, doc = doc)

    private val NONE: List<Spec> = emptyList()

    /** kind → 字段规格（顺序即文档展示序）。 */
    val SPECS: Map<Kind, List<Spec>> = mapOf(
        // 声明类
        Kind.Module to listOf(req("name", "模块全名，含 /vN"), opt("host_class", "JVM 宿主类名")),
        Kind.Depends to listOf(
            req("module_name", "依赖模块全名"),
            opt("constraint", "版本约束，如 \">=1.2.0\""),
            req("source", "registry|workspace|path"),
        ),
        Kind.Function to listOf(req("name"), req("visibility", "public|internal"), req("return_type", "规范类型文本")),
        Kind.Param to listOf(req("name"), req("type", "规范类型文本")),
        Kind.TypeParam to listOf(req("name"), opt("constraint", "约束接口名；缺失 = 违反显式约束规则")),
        Kind.Struct to listOf(req("name"), req("visibility", "public|internal")),
        Kind.Field to listOf(req("name"), req("type", "规范类型文本")),
        Kind.Interface to listOf(req("name"), req("visibility", "public|internal")),
        Kind.MethodSig to listOf(req("name"), req("return_type", "规范类型文本")),
        Kind.Impl to listOf(req("interface", "接口全名"), req("struct", "结构体全名")),
        Kind.Enum to listOf(req("name"), req("visibility", "public|internal")),
        Kind.Variant to listOf(req("name"), opt("payload_type", "载荷类型文本")),
        Kind.Const to listOf(req("name"), req("visibility", "public|internal"), req("type", "规范类型文本")),
        Kind.Test to listOf(
            req("name", "测试名"),
            req("target_name", "被测函数全名，如 demo.app.add"),
            derivedRef("target_id", "指向被测 Function"),
        ),
        Kind.DocComment to listOf(req("text", "连续 /// 行合并")),
        Kind.ExternalClass to listOf(req("class_fqn", "JVM 类全限定名")),
        Kind.ExternalMethod to listOf(
            req("name"),
            req("call_kind", "static|instance|constructor"),
            req("return_type", "规范类型文本"),
            opt("jvm_name", "JVM 方法名别名（符号名与 JVM 名分离）"),
        ),

        // 语句类
        Kind.Block to NONE,
        Kind.Let to listOf(
            req("name"),
            req("type", "规范类型文本"),
            optBool("mutable", "缺省 false；true 即 var"),
            optBool("shared", "缺省 false"),
        ),
        Kind.Assign to NONE,
        Kind.If to NONE,
        Kind.IfLet to listOf(req("path", "枚举构造全名"), opt("binding", "绑定变量名")),
        Kind.While to NONE,
        Kind.For to listOf(req("var_name"), req("var_type", "规范类型文本")),
        Kind.Match to NONE,
        Kind.MatchArm to listOf(
            req("pattern_kind", "wildcard|literal|enum_ctor"),
            opt("literal", "literal 模式的源文本"),
            opt("path", "enum_ctor 模式的构造全名"),
            opt("binding", "enum_ctor 模式的绑定变量名"),
        ),
        Kind.Return to NONE,
        Kind.Break to NONE,
        Kind.Continue to NONE,
        Kind.ExprStmt to NONE,

        // 表达式类
        Kind.Call to NONE,
        Kind.Arg to listOf(opt("name", "具名实参的形参名")),
        Kind.Binary to listOf(req("op", "运算符文本")),
        Kind.Unary to listOf(req("op", "- 或 !")),
        Kind.Literal to listOf(
            req("lit_kind", "int|float|bool|string|char|array|map"),
            opt("value", "源文本，恒为字符串——整数也写 \"7\"（标量必填；array/map 无）"),
        ),
        Kind.MapEntry to NONE,
        Kind.Path to listOf(
            req("written", "源文本路径，如 \"x\" 或 \"demo.app.f\"（这是唯一要写的字段）"),
            derivedStr("full_name", "解析后的符号全名"),
            derivedRef("resolved_id", "指向被引用的声明"),
        ),
        Kind.FieldAccess to listOf(req("field", "字段名")),
        Kind.Index to NONE,
        Kind.Spawn to NONE,
        Kind.Join to NONE,
    )

    fun specsOf(kind: Kind): List<Spec> = SPECS[kind] ?: NONE

    fun specOf(kind: Kind, field: String): Spec? = specsOf(kind).firstOrNull { it.name == field }

    /** `written(str, 必填; …)、full_name(str, 解析填充; …)` 形态的一行描述（空 = "—"）。 */
    fun describe(kind: Kind): String =
        specsOf(kind).takeIf { it.isNotEmpty() }?.joinToString("、") { it.describe() } ?: "—"

    /** 该 kind 不认识的字段名（写入路径用：拒绝并提示可用字段）。 */
    fun unknownFields(kind: Kind, names: Collection<String>): List<String> {
        val known = specsOf(kind).map { it.name }.toSet()
        return names.filter { it !in known }
    }

    /**
     * 已落表字段的类型/必填违例（门禁 + 写入路径共用）：返回可直接进诊断的描述，空 = 合法。
     * 不报未知字段——那是写入路径的职责（门禁容忍额外键）。
     * Literal.value 仅标量必填（array/map 无 value）。
     */
    fun problemsOf(kind: Kind, fields: Map<String, FieldValue>): List<String> {
        val out = ArrayList<String>()
        for (spec in specsOf(kind)) {
            val v = fields[spec.name]
            if (v == null) {
                if (spec.required) {
                    out += "$kind requires field '${spec.name}'" + hintFor(kind, spec)
                }
                continue
            }
            val ok = when (spec.type) {
                Type.Str -> v is FieldValue.Str
                Type.Bool -> v is FieldValue.Bool
                Type.Ref -> v is FieldValue.Ref
            }
            if (!ok) {
                out += "$kind.${spec.name} must be ${spec.type.name.lowercase()} " +
                    "(found ${v::class.simpleName?.lowercase()})" + hintFor(kind, spec)
            }
        }
        if (kind == Kind.Literal && fields["value"] == null) {
            val lk = (fields["lit_kind"] as? FieldValue.Str)?.value
            if (lk != null && lk != "array" && lk != "map") {
                out += "Literal requires field 'value' for lit_kind '$lk' (源文本字符串，如 \"7\")"
            }
        }
        return out
    }

    /** 针对高频错位的定向提示。 */
    private fun hintFor(kind: Kind, spec: Spec): String = when {
        kind == Kind.Literal && spec.name == "value" -> "——字面量值恒为源文本字符串，整数也写 \"7\" 而非 7"
        kind == Kind.Path && spec.name == "written" -> "——Path 只写 written（源文本路径）；full_name/resolved_id 由解析器填充"
        kind == Kind.Test && spec.name == "target_name" -> "——被测函数全名，如 demo.app.add；target_id 由解析器填充"
        else -> ""
    }
}
