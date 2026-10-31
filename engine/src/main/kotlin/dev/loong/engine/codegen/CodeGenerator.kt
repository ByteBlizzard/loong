package dev.loong.engine.codegen

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.types.LoongType
import dev.loong.engine.types.TypeChecker
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CodegenException(message: String) : RuntimeException(message)

/**
 * JVM 字节码生成器（plan.md M1 第 8 步；design §5.1/§6）。
 *
 * 类布局（design §6.3：模块级函数归入显式宿主类，类名来自模块名）：
 * - 宿主类 = 模块全名（点分隔即包名）；模块函数/impl 函数/常量 → 静态成员；
 *   impl 函数名 = 接口$结构$函数（避免多 impl 擦除冲突）；
 * - struct → 宿主类嵌套类，字段类型化，构造器按字段序；
 * - 枚举/Option/Result → dev.loong.runtime.Variant（tag + 装箱载荷）；
 * - spawn → 每个调用点一个合成 Callable 类 + FutureTask + 虚拟线程；
 * - main() -> Unit 存在时生成 JVM main 桥。
 *
 * 类型映射（M1）：Int→long Float→double Bool→boolean Char→char String→String
 * Array<T>→JVM 数组（按元素类型）Map→HashMap Handle→FutureTask。
 * 前置条件：全部诊断为空（仅编译已通过类型检查的程序）。
 */
class CodeGenerator(
    private val table: NodeTable,
    private val checker: TypeChecker,
    private val externals: dev.loong.engine.pkg.Externals = dev.loong.engine.pkg.Externals.EMPTY,
    /** 上次生成的类字节（增量路径；null = 全量发射）。 */
    private val previousClasses: Map<String, ByteArray>? = null,
    /** 上次生成时的函数内容签名（与 previousClasses 成对）。 */
    private val previousSignatures: Map<NodeId, Long>? = null,
) {

    private lateinit var hostInternal: String
    private lateinit var hostClassName: String

    private val structFields = HashMap<String, List<Pair<String, String>>>() // name -> (字段名, 类型文本)
    private val methodKey = HashMap<NodeId, String>() // Function/ExternalMethod 节点 → 方法名
    private val externalOwner = HashMap<NodeId, String>() // ExternalMethod 节点 → 目标类 internal name
    private val externalTypes = HashMap<String, String>() // 外部类短名 → internal name
    private val fnRefs = LinkedHashSet<NodeId>() // 被引用为值的函数节点
    /** 无载荷变体单例：tag → 宿主静态字段名（GETSTATIC 零分配；结构相等使跨枚举 tag 复用安全）。 */
    private val variantSingletons = LinkedHashMap<String, String>()
    /** 本次各函数的内容签名（generate 后供 Workspace 缓存）。 */
    private var fnSignatures: Map<NodeId, Long> = emptyMap()
    private val spawnSites = ArrayList<NodeId>()

    private val out = LinkedHashMap<String, ByteArray>()

    private val INT = LoongType.Named("Int")
    private val FLOAT = LoongType.Named("Float")
    private val BOOL = LoongType.Named("Bool")
    private val CHAR = LoongType.Named("Char")
    private val STRING = LoongType.Named("String")
    private val UNIT = LoongType.Named("Unit")

    private val VARIANT = "dev/loong/runtime/Variant"
    private val CONSOLE = "dev/loong/runtime/Console"

    fun generate(root: NodeId): Map<String, ByteArray> {
        val moduleName = fs(root, "name").substringBefore('/')
        val hostClass = fs(root, "host_class").ifEmpty { moduleName }
        hostClassName = hostClass
        hostInternal = hostClass.replace('.', '/')
        gather(root)
        collectFnRefs()
        collectVariantSingletons(root)

        // ---- 增量路径（ADR-0005）：内容哈希未变的函数方法从上次类字节逐字节拷贝 ----
        val currentFns = table.find(Kind.Function).filter { !externals.ownerOfNode.containsKey(it) }
        fnSignatures = currentFns.associateWith { fnContentHash(it) }
        fun nameOf(fn: NodeId) = methodKey[fn] ?: fs(fn, "name")
        fun erasedDesc(fn: NodeId): String {
            val ps = paramTypes(fn, emptyMap())
            return "(" + ps.joinToString("") { desc(it) } + ")" +
                desc(LoongType.parse(fs(fn, "return_type")))
        }
        // name -> desc 的未变集：拷贝谓词按 (name, desc) 精确匹配，天然排除
        // <clinit>/main 桥/$test$ 等同名异描述符方法
        val keep = HashMap<String, String>()
        if (previousClasses != null && previousSignatures != null) {
            for (fn in currentFns) {
                if (previousSignatures[fn] == fnSignatures[fn]) keep[nameOf(fn)] = erasedDesc(fn)
            }
        }
        val prevHost = previousClasses?.get(hostInternal)

        val host = newWriter(hostInternal, "java/lang/Object", null)
        var copied = 0
        if (prevHost != null && keep.isNotEmpty()) {
            org.objectweb.asm.ClassReader(prevHost).accept(
                object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int, access: Int, name: String, signature: String?,
                        superName: String, interfaces: Array<out String>?,
                    ) = host.visit(version, access, name, signature, superName, interfaces)

                    // 常量字段与 <clinit> 不拷贝：常量集可能变化，走全新发射
                    override fun visitField(
                        access: Int, name: String, desc: String, signature: String?, value: Any?,
                    ) = null

                    override fun visitMethod(
                        access: Int, name: String, desc: String, signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? =
                        if (keep[name] == desc) {
                            copied++
                            host.visitMethod(access, name, desc, signature, exceptions)
                        } else {
                            null
                        }
                },
                0,
            )
        }
        emitConstants(host, root)
        // 规范序：按方法名排序发射变更/新增函数（拷贝与发射的方法集互补）
        currentFns
            .filter { keep[nameOf(it)] != erasedDesc(it) }
            .sortedBy { nameOf(it) }
            .forEach { fn -> emitFunction(host, fn, nameOf(fn)) }
        emitMainBridge(host, root)
        table.find(Kind.Test).forEach { emitTestFunction(host, it) }
        out[hostInternal] = host.toByteArray()

        emitStructClasses()
        emitSpawnClasses()
        emitFnAdapters()
        return out
    }

    fun hostClassOf(): String = hostClassName

    // ---------- 收集 ----------

    private fun gather(root: NodeId) {
        // 链接的依赖接口 AST 根（M4 合并，其它 Module 节点）：其 struct 不在本模块发射，
        // 但字段表与描述符映射必须可见（跨模块字段访问/构造）
        val linkedRoots = table.find(Kind.Module).filter { it != root }
        val declNodes = table[root].kids + linkedRoots.flatMap { table[it].kids }
        for (decl in declNodes) {
            when (table[decl].kind) {
                Kind.Struct -> structFields[fs(decl, "name")] =
                    table[decl].kids.filter { table[it].kind == Kind.Field }
                        .map { fs(it, "name") to fs(it, "type") }
                Kind.Function -> methodKey[decl] = fs(decl, "name")
                Kind.Impl -> {
                    val itf = fs(decl, "interface").substringBefore('<')
                    val st = fs(decl, "struct").substringBefore('<')
                    for (fn in table[decl].kids.filter { table[it].kind == Kind.Function }) {
                        methodKey[fn] = "$itf$$st$${fs(fn, "name")}"
                    }
                }
                Kind.ExternalClass -> {
                    val fqn = fs(decl, "class_fqn")
                    val owner = fqn.replace('.', '/')
                    externalTypes[fqn.substringAfterLast('.')] = owner
                    for (m in table[decl].kids.filter { table[it].kind == Kind.ExternalMethod }) {
                        externalOwner[m] = owner
                        methodKey[m] = fs(m, "name")
                    }
                }
                else -> Unit
            }
        }
    }

    // ---------- 类型与描述符 ----------

    private fun structInternal(name: String): String {
        val host = externals.structHost[name] ?: return hostInternal + "$" + name
        return host.replace('.', '/') + "$" + name
    }

    private val OBJECT_T = LoongType.Named("Object")
    private val INT32_T = LoongType.Named("Int32")

    /** 目标描述符为 Object（互操作或类型参数擦除）且源为原始类型时装箱。 */
    /** Object（数组元素/泛型返回）→ 具体描述符：原始解箱 / 引用 CHECKCAST。 */
    private fun adaptFromObject(mv: MethodVisitor, d: String) {
        when {
            d == "J" || d == "D" || d == "Z" || d == "C" -> unbox(mv, d)
            d.startsWith("L") && d != "Ljava/lang/Object;" ->
                mv.visitTypeInsn(Opcodes.CHECKCAST, d.substring(1, d.length - 1))
            d.startsWith("[") -> mv.visitTypeInsn(Opcodes.CHECKCAST, d)
        }
    }

    private fun boxIfObject(mv: MethodVisitor, erasedDesc: String, actualT: LoongType) {
        if (erasedDesc != "Ljava/lang/Object;") return
        when (desc(actualT)) {
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)
        }
    }

    private fun norm(t: LoongType): LoongType = when (t) {
        is LoongType.Named -> {
            val short = t.name.substringAfterLast('.')
            if (short != t.name && (structFields.containsKey(short) || externalTypes.containsKey(short) ||
                    externals.structHost.containsKey(short))
            ) {
                LoongType.Named(short, t.args.map { norm(it) })
            } else {
                LoongType.Named(t.name, t.args.map { norm(it) })
            }
        }
        is LoongType.Func -> LoongType.Func(t.params.map { norm(it) }, norm(t.ret))
    }

    /** 宽度适配：Int(J)↔Int32(I) 在调用点与返回处显式衔接。 */
    private fun coerceWidth(mv: MethodVisitor, targetDesc: String, actualDesc: String) {
        if (targetDesc == "I" && actualDesc == "J") mv.visitInsn(Opcodes.L2I)
        if (targetDesc == "J" && actualDesc == "I") mv.visitInsn(Opcodes.I2L)
    }

    private fun desc(t0: LoongType): String {
        val t = norm(t0)
        return when {
        t == INT32_T -> "I"
        t == OBJECT_T -> "Ljava/lang/Object;"
        t == INT -> "J"
        t == FLOAT -> "D"
        t == BOOL -> "Z"
        t == CHAR -> "C"
        t == STRING -> "Ljava/lang/String;"
        t == UNIT -> "V"
        t is LoongType.Named && t.name == "Array" ->
            "[Ljava/lang/Object;" // 统一 Object[]：擦除一致性优先，原始特化后置
        t is LoongType.Named && t.name == "Map" -> "Ljava/util/HashMap;"
        t is LoongType.Named && t.name == "Handle" -> "Ljava/util/concurrent/FutureTask;"
        t is LoongType.Named && structFields.containsKey(t.name) -> "L" + structInternal(t.name) + ";"
        t is LoongType.Named && externalTypes.containsKey(t.name) ->
            "L" + externalTypes[t.name] + ";"
        t is LoongType.Named && externals.structHost.containsKey(t.name) ->
            "L" + externals.structHost[t.name]!!.replace('.', '/') + "$" + t.name + ";"
        t is LoongType.Named && (t.name == "Option" || t.name == "Result" || isUserEnum(t.name)) ->
            "L$VARIANT;"
        // 函数值：按元数映射 Fn 接口（具名函数物化为适配类，无闭包）
        t is LoongType.Func -> when (t.params.size) {
            0 -> "Ldev/loong/runtime/Fn0;"
            1 -> "Ldev/loong/runtime/Fn1;"
            2 -> "Ldev/loong/runtime/Fn2;"
            3 -> "Ldev/loong/runtime/Fn3;"
            else -> throw CodegenException("function values support arity 0..3")
        }
        // 擦除式泛型：裸类型参数映射 Object（声明与调用点共用，保证描述符一致）
        t is LoongType.Named && t.args.isEmpty() -> "Ljava/lang/Object;"
        else -> throw CodegenException("unsupported type in codegen: ${t.render()}")
    }
    }

    private fun isUserEnum(name: String): Boolean =
        table.find(Kind.Enum).any { fs(it, "name") == name }

    private fun tOf(e: NodeId): LoongType = checker.exprTypes[e] ?: UNIT

    // ---------- 类生成 ----------

    private fun newWriter(internal: String, superName: String, interfaces: Array<String>?): ClassWriter =
        object : ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(t1: String, t2: String): String =
                if (t1 == t2) t1 else "java/lang/Object"
        }.apply {
            visit(
                Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, superName, interfaces,
            )
        }

    private fun paramTypes(fn: NodeId, subst: Map<String, LoongType>): List<LoongType> =
        table[fn].kids.filter { table[it].kind == Kind.Param }
            .map { applySub(LoongType.parse(fs(it, "type")), subst) }

    private fun applySub(t: LoongType, subst: Map<String, LoongType>): LoongType = when (t) {
        is LoongType.Named ->
            if (t.args.isEmpty() && subst.containsKey(t.name)) subst[t.name]!!
            else LoongType.Named(t.name, t.args.map { applySub(it, subst) })
        is LoongType.Func -> LoongType.Func(t.params.map { applySub(it, subst) }, applySub(t.ret, subst))
    }

    private fun emitFunction(host: ClassWriter, fn: NodeId, key: String) {
        val params = paramTypes(fn, emptyMap())
        val ret = LoongType.parse(fs(fn, "return_type"))
        val mdesc = "(" + params.joinToString("") { desc(it) } + ")" + desc(ret)
        val mv = host.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, key, mdesc, null, null)
        mv.visitCode()
        val ctx = FnCtx(mv)
        // 参数即局部槽
        table[fn].kids.filter { table[it].kind == Kind.Param }.forEachIndexed { i, p ->
            ctx.bind(p, desc(params[i]))
        }
        val body = table[fn].kids.firstOrNull { table[it].kind == Kind.Block }
        val retDesc = desc(ret)
        if (body != null) compileBlock(body, ctx, leaveValue = retDesc != "V")
        // 保证返回（return 语句之后的兜底：尾表达式已在栈上）
        mv.visitInsn(returnOpcode(retDesc))
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Test 块 → 静态方法 `$test$<节点ID>`；TestRunner 反射调用，结果按节点 ID 索引。 */
    private fun emitTestFunction(host: ClassWriter, test: NodeId) {
        val mv = host.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "\$test\$${test.value}", "()V", null, null,
        )
        mv.visitCode()
        val ctx = FnCtx(mv)
        val body = table[test].kids.firstOrNull { table[it].kind == Kind.Block }
        if (body != null) compileBlock(body, ctx, leaveValue = false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitMainBridge(host: ClassWriter, root: NodeId) {        val main = table[root].kids.firstOrNull {
            table[it].kind == Kind.Function && fs(it, "name") == "main"
        } ?: return
        if (paramTypes(main, emptyMap()).isNotEmpty()) return
        val mv = host.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null,
        )
        mv.visitCode()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, hostInternal, "main", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitConstants(host: ClassWriter, root: NodeId) {
        val consts = table[root].kids.filter { table[it].kind == Kind.Const }
        if (consts.isEmpty() && variantSingletons.isEmpty()) return
        for (c in consts) {
            host.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                fs(c, "name"), desc(LoongType.parse(fs(c, "type"))), null, null,
            ).visitEnd()
        }
        variantSingletons.values.forEach { field ->
            host.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                field, "L$VARIANT;", null, null,
            ).visitEnd()
        }
        val mv = host.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        val ctx = FnCtx(mv)
        for (c in consts) {
            compileExpr(table[c].kids.first { table[it].kind == Kind.Literal }, ctx)
            mv.visitFieldInsn(
                Opcodes.PUTSTATIC, hostInternal, fs(c, "name"), desc(LoongType.parse(fs(c, "type"))),
            )
        }
        variantSingletons.forEach { (tag, field) ->
            mv.visitTypeInsn(Opcodes.NEW, VARIANT)
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(tag)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, VARIANT, "<init>", "(Ljava/lang/String;Ljava/lang/Object;)V", false,
            )
            mv.visitFieldInsn(Opcodes.PUTSTATIC, hostInternal, field, "L$VARIANT;")
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitStructClasses() {
        for ((name, fields) in structFields) {
            if (externals.structHost.containsKey(name)) continue // 外部 struct 由依赖方发布
            val internal = structInternal(name)
            val cw = newWriter(internal, "java/lang/Object", null)
            val descs = fields.map { desc(LoongType.parse(it.second)) }
            fields.forEachIndexed { i, (fname, _) ->
                cw.visitField(Opcodes.ACC_PUBLIC, fname, descs[i], null, null).visitEnd()
            }
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + descs.joinToString("") + ")V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            fields.forEachIndexed { i, (fname, _) ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(loadOp(descs[i]), 1 + descs.subList(0, i).sumOf { sizeOf(it) })
                mv.visitFieldInsn(Opcodes.PUTFIELD, internal, fname, descs[i])
            }
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            out[internal] = cw.toByteArray()
        }
    }

    private fun emitSpawnClasses() {
        var n = 0
        for (call in spawnSites) {
            val callee = table[call].kids.first()
            val target = refOf(callee, "resolved_id") ?: continue
            val subst = checker.callSubst[call] ?: emptyMap()
            val params = paramTypes(target, subst)
            val args = table[call].kids.drop(1)
            val ordered = orderedArgs(paramNames(target), args)
            val ret = applySub(LoongType.parse(fs(target, "return_type")), subst)
            val retDesc = desc(ret)

            val internal = hostInternal + "\$Spawn" + (n++)
            val cw = newWriter(internal, "java/lang/Object", arrayOf("java/util/concurrent/Callable"))
            val descs = params.map { desc(it) }
            descs.forEachIndexed { i, d -> cw.visitField(Opcodes.ACC_PUBLIC, "a$i", d, null, null).visitEnd() }
            // 构造器
            val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + descs.joinToString("") + ")V", null, null)
            ctor.visitCode()
            ctor.visitVarInsn(Opcodes.ALOAD, 0)
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            descs.forEachIndexed { i, d ->
                ctor.visitVarInsn(Opcodes.ALOAD, 0)
                ctor.visitVarInsn(loadOp(d), 1 + descs.subList(0, i).sumOf { sizeOf(it) })
                ctor.visitFieldInsn(Opcodes.PUTFIELD, internal, "a$i", d)
            }
            ctor.visitInsn(Opcodes.RETURN)
            ctor.visitMaxs(0, 0)
            ctor.visitEnd()
            // call()
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/Object;", null, null)
            mv.visitCode()
            descs.forEachIndexed { i, d ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(Opcodes.GETFIELD, internal, "a$i", d)
            }
            invokeStatic(target, subst, mv)
            if (retDesc == "V") mv.visitInsn(Opcodes.ACONST_NULL) else box(mv, retDesc)
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            out[internal] = cw.toByteArray()
        }
    }

    /** 函数子树内容指纹（节点/字段/子序；不含 ID 本身）——增量失效依据。 */
    private fun fnContentHash(fn: NodeId): Long {
        var h = 1125899906842597L
        fun walk(id: NodeId) {
            val n = table[id]
            h = 31 * h + n.kind.ordinal.toLong()
            h = 31 * h + n.fields.entries.sortedBy { it.key }
                .joinToString("|") { it.key + "=" + it.value.toString() }.hashCode()
            n.kids.forEach { walk(it) }
        }
        walk(fn)
        return h
    }

    fun currentFnSignatures(): Map<NodeId, Long> = fnSignatures

    /** 本模块声明的枚举的无载荷变体 → 静态单例（直接量编码：构造即 GETSTATIC）。 */
    private fun collectVariantSingletons(root: NodeId) {
        for (enumDecl in table.find(Kind.Enum)) {
            if (table[enumDecl].parent != root) continue // 仅本模块声明
            for (v in table[enumDecl].kids.filter { table[it].kind == Kind.Variant }) {
                if (!table[v].fields.containsKey("payload_type")) {
                    val tag = fs(v, "name")
                    variantSingletons[tag] = "$" + "e$" + tag
                }
            }
        }
    }

    /** 值位置引用的具名函数（非直接调用 callee）→ 需生成 Fn 适配类。 */
    private fun collectFnRefs() {
        for (path in table.find(Kind.Path)) {
            val r = refOf(path, "resolved_id") ?: continue
            if (table.getOrNull(r)?.kind != Kind.Function) continue
            if (externals.ownerOfNode.containsKey(r)) continue
            val parent = table[path].parent
            if (parent != NodeId.NONE && table[parent].kind == Kind.Call &&
                table[parent].kids.firstOrNull() == path
            ) {
                continue // 直接调用 callee：invokestatic，无需物化
            }
            fnRefs += r
        }
    }

    /** Fn 适配类：apply 装参解箱 → invokestatic 本体 → 装箱返回。 */
    private fun emitFnAdapters() {
        for (fn in fnRefs) {
            val key = methodKey[fn] ?: continue
            val internal = hostInternal + "\$Fn\$" + key
            val params = paramTypes(fn, emptyMap())
            val iface = "dev/loong/runtime/Fn${params.size}"
            val cw = newWriter(internal, "java/lang/Object", arrayOf(iface))
            val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            ctor.visitCode()
            ctor.visitVarInsn(Opcodes.ALOAD, 0)
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            ctor.visitInsn(Opcodes.RETURN)
            ctor.visitMaxs(0, 0)
            ctor.visitEnd()
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "apply",
                "(" + "Ljava/lang/Object;".repeat(params.size) + ")Ljava/lang/Object;", null, null,
            )
            mv.visitCode()
            params.forEachIndexed { i, p ->
                mv.visitVarInsn(Opcodes.ALOAD, 1 + i)
                val pd = desc(p)
                when {
                    pd == "J" || pd == "D" || pd == "Z" || pd == "C" -> unbox(mv, pd)
                    pd.startsWith("L") && pd != "Ljava/lang/Object;" ->
                        mv.visitTypeInsn(Opcodes.CHECKCAST, pd.substring(1, pd.length - 1))
                }
            }
            invokeStatic(fn, emptyMap(), mv)
            val rd = desc(LoongType.parse(fs(fn, "return_type")))
            when (rd) {
                "V" -> mv.visitInsn(Opcodes.ACONST_NULL)
                "J", "D", "Z", "C" -> box(mv, rd)
            }
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            out[internal] = cw.toByteArray()
        }
    }

    private fun paramNames(fn: NodeId): List<String> =
        table[fn].kids.filter { table[it].kind == Kind.Param }.map { fs(it, "name") }

    private fun invokeStatic(target: NodeId, subst: Map<String, LoongType>, mv: MethodVisitor) {
        // 描述符取声明（擦除）形态：泛型参数为 Object，与 emitFunction 一致
        val params = paramTypes(target, emptyMap())
        // invokevirtual 的接收者不进描述符：实例方法描述符去掉 self 首参
        val descParams =
            if (table[target].kind == Kind.ExternalMethod && fs(target, "call_kind") == "instance") {
                params.drop(1)
            } else {
                params
            }
        // 返回描述符同样取声明（擦除）形态；代入后的具体类型仅用于调用点解箱
        val ret = LoongType.parse(fs(target, "return_type"))
        val retConcrete = applySub(ret, subst)
        val mdesc = "(" + descParams.joinToString("") { desc(it) } + ")" + desc(ret)
        when (table[target].kind) {
            Kind.ExternalMethod -> {
                // self 首参即接收者：实例方法描述符天然正确（design §6.2 instance → invokevirtual）
                val opcode = if (fs(target, "call_kind") == "instance") Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC
                val jvmName = (table[target].fields["jvm_name"] as? FieldValue.Str)?.value
                    ?: fs(target, "name")
                mv.visitMethodInsn(opcode, externalOwner[target]!!, jvmName, mdesc, false)
                val rd = mdesc.substringAfter(')')
                if (rd.startsWith("L") || rd.startsWith("[")) {
                    // Null-safety boundary assertion (design §6.4): a null returned from Java fails at the boundary immediately
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false,
                    )
                    mv.visitTypeInsn(Opcodes.CHECKCAST, rd.substring(1, rd.length - 1))
                }
            }
            else -> {
                val extOwner = externals.ownerOfNode[target]
                val owner = extOwner?.replace('.', '/') ?: hostInternal
                val name = if (extOwner != null) fs(target, "name") else methodKey[target]!!
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, mdesc, false)
            }
        }
        // 泛型调用点适配：擦除返回 Object 时按具体类型解箱（原始）或 CHECKCAST（引用）
        val rd = mdesc.substringAfter(')')
        val concrete = desc(retConcrete)
        if (rd == "I" && concrete == "J") mv.visitInsn(Opcodes.I2L)
        if (rd == "Ljava/lang/Object;") {
            when (concrete) {
                "J", "D", "Z", "C" -> unbox(mv, concrete)
                else -> if (concrete.startsWith("L") || concrete.startsWith("[")) {
                    mv.visitTypeInsn(
                        Opcodes.CHECKCAST,
                        if (concrete.startsWith("[")) concrete else concrete.substring(1, concrete.length - 1),
                    )
                }
            }
        }
    }

    // ---------- 语句 ----------

    private inner class FnCtx(val mv: MethodVisitor) {
        val slots = HashMap<NodeId, Int>()
        val descs = HashMap<Int, String>()
        var nextSlot = 0
        val breakLabels = ArrayDeque<Label>()
        val continueLabels = ArrayDeque<Label>()

        fun bind(node: NodeId, d: String): Int {
            val slot = nextSlot
            slots[node] = slot
            descs[slot] = d
            nextSlot += sizeOf(d)
            return slot
        }

        fun slotOf(node: NodeId): Int? = slots[node]
        fun descOf(node: NodeId): String? = slots[node]?.let { descs[it] }
        fun temp(d: String): Int {
            val slot = nextSlot
            descs[slot] = d
            nextSlot += sizeOf(d)
            return slot
        }
    }

    /** 镜像检查器的块值语义：末位 ExprStmt 的表达式即块值；leaveValue=false 时按描述符弹栈。 */
    private fun compileBlock(block: NodeId, ctx: FnCtx, leaveValue: Boolean = false) {
        val kids = table[block].kids
        kids.dropLast(1).forEach { compileStmt(it, ctx) }
        val last = kids.lastOrNull() ?: return
        if (table[last].kind == Kind.ExprStmt) {
            val expr = table[last].kids.first()
            compileExpr(expr, ctx)
            if (!leaveValue) popValue(ctx.mv, desc(tOf(expr)))
        } else {
            compileStmt(last, ctx)
        }
    }

    private fun popValue(mv: MethodVisitor, d: String) {
        if (d != "V") mv.visitInsn(if (d == "J" || d == "D") Opcodes.POP2 else Opcodes.POP)
    }

    private fun compileStmt(s: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[s].kids
        when (table[s].kind) {
            Kind.Let -> {
                val declared = LoongType.parse(fs(s, "type"))
                val d = desc(declared)
                compileExpr(kids.first(), ctx)
                boxIfObject(mv, d, tOf(kids.first()))
                mv.visitVarInsn(storeOp(d), ctx.bind(s, d))
            }
            Kind.Assign -> compileAssign(kids[0], kids[1], ctx)
            Kind.ExprStmt -> {
                compileExpr(kids.first(), ctx)
                val d = desc(tOf(kids.first()))
                if (d != "V") mv.visitInsn(if (d == "J" || d == "D") Opcodes.POP2 else Opcodes.POP)
            }
            Kind.Return -> {
                if (kids.isEmpty()) {
                    mv.visitInsn(Opcodes.RETURN)
                } else {
                    compileExpr(kids[0], ctx)
                    mv.visitInsn(returnOpcode(desc(tOf(kids[0]))))
                }
            }
            Kind.While -> {
                val start = Label()
                val end = Label()
                mv.visitLabel(start)
                compileExpr(kids[0], ctx)
                mv.visitJumpInsn(Opcodes.IFEQ, end)
                ctx.breakLabels.addLast(end)
                ctx.continueLabels.addLast(start)
                compileBlock(kids[1], ctx)
                ctx.breakLabels.removeLast()
                ctx.continueLabels.removeLast()
                mv.visitJumpInsn(Opcodes.GOTO, start)
                mv.visitLabel(end)
            }
            Kind.For -> compileFor(s, ctx)
            Kind.If -> compileIf(s, ctx)
            Kind.IfLet -> compileIfLet(s, ctx)
            Kind.Match -> compileMatch(s, ctx)
            Kind.Break -> mv.visitJumpInsn(Opcodes.GOTO, ctx.breakLabels.lastOrNull() ?: throw CodegenException("break outside loop"))
            Kind.Continue -> mv.visitJumpInsn(Opcodes.GOTO, ctx.continueLabels.lastOrNull() ?: throw CodegenException("continue outside loop"))
            else -> Unit
        }
    }

    private fun compileAssign(target: NodeId, value: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        when (table[target].kind) {
            Kind.Path -> {
                val slot = ctx.slotOf(
                    refOf(target, "resolved_id") ?: throw CodegenException("assign to unresolvable path"),
                ) ?: throw CodegenException("assignment target was not bound to a slot")
                val d = ctx.descs[slot] ?: "V"
                compileExpr(value, ctx)
                mv.visitVarInsn(storeOp(d), slot)
            }
            Kind.FieldAccess -> {
                val structT = tOf(table[target].kids[0]) as LoongType.Named
                val internal = structInternal(structT.name)
                val d = desc(LoongType.parse(structFields[structT.name]!!.first { it.first == fs(target, "field") }.second))
                compileExpr(table[target].kids[0], ctx)
                compileExpr(value, ctx)
                if (d == "Ljava/lang/Object;") boxIfObject(mv, "Ljava/lang/Object;", tOf(value))
                mv.visitFieldInsn(Opcodes.PUTFIELD, internal, fs(target, "field"), d)
            }
            Kind.Index -> {
                val t = tOf(table[target].kids[0]) as LoongType.Named
                compileExpr(table[target].kids[0], ctx)
                compileExpr(table[target].kids[1], ctx)
                if (t.name == "Array") {
                    mv.visitInsn(Opcodes.L2I)
                    compileExpr(value, ctx)
                    boxIfObject(mv, "Ljava/lang/Object;", tOf(value))
                    mv.visitInsn(Opcodes.AASTORE)
                } else {
                    compileExpr(value, ctx)
                    box(mv, desc(t.args[0]))
                    box(mv, desc(t.args[1]))
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false,
                    )
                    mv.visitInsn(Opcodes.POP)
                }
            }
            else -> throw CodegenException("unsupported assignment target")
        }
    }

    private fun compileFor(s: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[s].kids
        val iterT = tOf(kids[0]) as LoongType.Named
        val elemT = checker.localTypeOf(s) ?: UNIT
        val elemDesc = desc(elemT)
        compileExpr(kids[0], ctx)
        when (iterT.name) {
            "Array" -> {
                val arr = ctx.temp(desc(iterT))
                mv.visitVarInsn(Opcodes.ASTORE, arr)
                val len = ctx.temp("I")
                val idx = ctx.temp("I")
                mv.visitVarInsn(Opcodes.ALOAD, arr)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitVarInsn(Opcodes.ISTORE, len)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, idx)
                val start = Label()
                val cont = Label()
                val end = Label()
                mv.visitLabel(start)
                mv.visitVarInsn(Opcodes.ILOAD, idx)
                mv.visitVarInsn(Opcodes.ILOAD, len)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, end)
                mv.visitVarInsn(Opcodes.ALOAD, arr)
                mv.visitVarInsn(Opcodes.ILOAD, idx)
                mv.visitInsn(Opcodes.AALOAD)
                adaptFromObject(mv, elemDesc)
                mv.visitVarInsn(storeOp(elemDesc), ctx.bind(s, elemDesc))
                ctx.breakLabels.addLast(end)
                ctx.continueLabels.addLast(cont)
                compileBlock(kids[1], ctx)
                ctx.breakLabels.removeLast()
                ctx.continueLabels.removeLast()
                mv.visitLabel(cont)
                mv.visitIincInsn(idx, 1)
                mv.visitJumpInsn(Opcodes.GOTO, start)
                mv.visitLabel(end)
            }
            "String" -> {
                val str = ctx.temp("Ljava/lang/String;")
                mv.visitVarInsn(Opcodes.ASTORE, str)
                val len = ctx.temp("I")
                val idx = ctx.temp("I")
                mv.visitVarInsn(Opcodes.ALOAD, str)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                mv.visitVarInsn(Opcodes.ISTORE, len)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, idx)
                val start = Label()
                val cont = Label()
                val end = Label()
                mv.visitLabel(start)
                mv.visitVarInsn(Opcodes.ILOAD, idx)
                mv.visitVarInsn(Opcodes.ILOAD, len)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, end)
                mv.visitVarInsn(Opcodes.ALOAD, str)
                mv.visitVarInsn(Opcodes.ILOAD, idx)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
                mv.visitVarInsn(storeOp(elemDesc), ctx.bind(s, elemDesc))
                ctx.breakLabels.addLast(end)
                ctx.continueLabels.addLast(cont)
                compileBlock(kids[1], ctx)
                ctx.breakLabels.removeLast()
                ctx.continueLabels.removeLast()
                mv.visitLabel(cont)
                mv.visitIincInsn(idx, 1)
                mv.visitJumpInsn(Opcodes.GOTO, start)
                mv.visitLabel(end)
            }
            else -> compileForStateMachine(s, iterT, ctx)
        }
    }

    /**
     * 用户结构体的 for-in：显式状态机协议（2026-10-30 决策：不允许 self 变 var）。
     * impl Iterable<E> for X 须提供：
     *   start(self: X) -> S
     *   next(self: X, state: S) -> Option<Y>，Y 为含 elem/state 字段的本模块 struct。
     * 循环降级：st=start(o); loop { o? = next(o, st); None→end; v=o?.elem; st=o?.state; body }
     */
    private fun compileForStateMachine(s: NodeId, iterT: LoongType.Named, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[s].kids
        val implNode = table.find(Kind.Impl).firstOrNull {
            fs(it, "struct").substringBefore('<') == iterT.name &&
                fs(it, "interface").substringBefore('<') == "Iterable"
        } ?: throw CodegenException("no Iterable implementation for ${iterT.name}")
        fun protocolFn(name: String): NodeId = table[implNode].kids.firstOrNull {
            table[it].kind == Kind.Function && fs(it, "name") == name
        } ?: throw CodegenException("Iterable implementation of ${iterT.name} misses '$name'")

        val startFn = protocolFn("start")
        val nextFn = protocolFn("next")
        val nextRet = LoongType.parse(fs(nextFn, "return_type")) as? LoongType.Named
            ?: throw CodegenException("'next' must return Option<Step>")
        val stepName = (nextRet.args.getOrNull(0) as? LoongType.Named)?.name?.substringAfterLast('.')
            ?: throw CodegenException("'next' must return Option<Step>")
        val fields = structFields[stepName]
            ?: throw CodegenException("iteration step struct '$stepName' is unknown")
        val elemFieldDesc = desc(LoongType.parse(fields.first { it.first == "elem" }.second))
        val stateFieldDesc = desc(LoongType.parse(fields.first { it.first == "state" }.second))
        val stepInternalName = structInternal(stepName)
        val elemDesc = desc(checker.localTypeOf(s) ?: LoongType.Named("Unit"))

        // state 的权威类型 = next 第二形参的具体类型（协议约定与 start 返回一致）
        val stateDesc = desc(
            paramTypes(nextFn, emptyMap()).getOrNull(1)
                ?: throw CodegenException("'next' must take (self, state)"),
        )
        // compileFor 头部已将迭代对象求值入栈，这里直接消费（勿重复求值）
        val objSlot = ctx.temp(desc(iterT))
        mv.visitVarInsn(storeOp(desc(iterT)), objSlot)
        mv.visitVarInsn(loadOp(desc(iterT)), objSlot)
        invokeStatic(startFn, emptyMap(), mv)
        val stSlot = ctx.temp(stateDesc)
        mv.visitVarInsn(storeOp(stateDesc), stSlot)

        val loop = Label()
        val cont = Label()
        val someL = Label()
        val end = Label()
        mv.visitLabel(loop)
        mv.visitVarInsn(loadOp(desc(iterT)), objSlot)
        mv.visitVarInsn(loadOp(stateDesc), stSlot)
        invokeStatic(nextFn, emptyMap(), mv)
        val optSlot = ctx.temp("L$VARIANT;")
        mv.visitVarInsn(Opcodes.ASTORE, optSlot)
        mv.visitVarInsn(Opcodes.ALOAD, optSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getTag", "()Ljava/lang/String;", false)
        mv.visitLdcInsn("None")
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, end)
        mv.visitLabel(someL)
        mv.visitVarInsn(Opcodes.ALOAD, optSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getPayload", "()Ljava/lang/Object;", false)
        mv.visitTypeInsn(Opcodes.CHECKCAST, stepInternalName)
        val stepSlot = ctx.temp("L$stepInternalName;")
        mv.visitVarInsn(Opcodes.ASTORE, stepSlot)
        mv.visitVarInsn(Opcodes.ALOAD, stepSlot)
        mv.visitFieldInsn(Opcodes.GETFIELD, stepInternalName, "elem", elemFieldDesc)
        if (elemFieldDesc != elemDesc) {
            if (elemDesc == "J" || elemDesc == "D" || elemDesc == "Z" || elemDesc == "C") {
                unbox(mv, elemDesc)
            }
        }
        mv.visitVarInsn(storeOp(elemDesc), ctx.bind(s, elemDesc))
        mv.visitVarInsn(Opcodes.ALOAD, stepSlot)
        mv.visitFieldInsn(Opcodes.GETFIELD, stepInternalName, "state", stateFieldDesc)
        if (stateFieldDesc != stateDesc) adaptFromObject(mv, stateDesc)
        mv.visitVarInsn(storeOp(stateDesc), stSlot)

        ctx.breakLabels.addLast(end)
        ctx.continueLabels.addLast(cont)
        compileBlock(kids[1], ctx)
        ctx.breakLabels.removeLast()
        ctx.continueLabels.removeLast()
        mv.visitLabel(cont)
        mv.visitJumpInsn(Opcodes.GOTO, loop)
        mv.visitLabel(end)
    }

    private fun compileIf(id: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[id].kids
        val wantValue = desc(tOf(id)) != "V"
        val elseL = Label()
        val end = Label()
        compileExpr(kids[0], ctx)
        mv.visitJumpInsn(Opcodes.IFEQ, elseL)
        compileBlock(kids[1], ctx, wantValue)
        if (kids.size > 2) {
            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitLabel(elseL)
            val els = kids[2]
            if (table[els].kind == Kind.Block) compileBlock(els, ctx, wantValue) else compileIf(els, ctx)
            mv.visitLabel(end)
        } else {
            mv.visitLabel(elseL)
        }
    }

    private fun compileIfLet(id: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[id].kids
        val hasBinding = table[id].fields.containsKey("binding")
        compileExpr(kids[0], ctx)
        val temp = ctx.temp("L$VARIANT;")
        mv.visitVarInsn(Opcodes.ASTORE, temp)
        val elseL = Label()
        val end = Label()
        mv.visitVarInsn(Opcodes.ALOAD, temp)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getTag", "()Ljava/lang/String;", false)
        mv.visitLdcInsn(patternTag(fs(id, "path")))
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, elseL)
        if (hasBinding) {
            mv.visitVarInsn(Opcodes.ALOAD, temp)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getPayload", "()Ljava/lang/Object;", false)
            val payloadDesc = desc(checker.localTypeOf(id) ?: UNIT)
            unbox(mv, payloadDesc)
            mv.visitVarInsn(storeOp(payloadDesc), ctx.bind(id, payloadDesc))
        }
        val wantValue = desc(tOf(id)) != "V"
        compileBlock(kids[1], ctx, wantValue)
        if (kids.size > 2) {
            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitLabel(elseL)
            compileBlock(kids[2], ctx, wantValue)
            mv.visitLabel(end)
        } else {
            mv.visitLabel(elseL)
        }
    }

    private fun compileMatch(id: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[id].kids
        val scrutT = tOf(kids[0]) as LoongType.Named
        val end = Label()
        val wantValue = desc(tOf(id)) != "V"
        if (scrutT.name == "Int") {
            // 字面量匹配（M1：Int）；通配臂为兜底
            compileExpr(kids[0], ctx)
            val temp = ctx.temp("J")
            mv.visitVarInsn(Opcodes.LSTORE, temp)
            val arms = kids.drop(1)
            val wildcard = arms.firstOrNull { fs(it, "pattern_kind") == "wildcard" }
                ?: throw CodegenException("literal match requires wildcard")
            for (arm in arms) {
                if (fs(arm, "pattern_kind") == "wildcard") continue
                val next = Label()
                mv.visitVarInsn(Opcodes.LLOAD, temp)
                mv.visitLdcInsn(fs(arm, "literal").toLong())
                mv.visitInsn(Opcodes.LCMP)
                mv.visitJumpInsn(Opcodes.IFNE, next)
                compileArmBody(arm, ctx, wantValue)
                mv.visitJumpInsn(Opcodes.GOTO, end)
                mv.visitLabel(next)
            }
            compileArmBody(wildcard, ctx, wantValue)
            mv.visitLabel(end)
            return
        }
        // 枚举匹配：hashCode lookupswitch 快路径 + 字面量引用比对
        //（JVM 字符串字面量全局驻留，acmp 可靠）；哈希冲突回退完整顺序链
        compileExpr(kids[0], ctx)
        val temp = ctx.temp("L$VARIANT;")
        mv.visitVarInsn(Opcodes.ASTORE, temp)
        val arms = kids.drop(1)
        val wildcard = arms.firstOrNull { fs(it, "pattern_kind") == "wildcard" }
        val enumArms = arms.filter { fs(it, "pattern_kind") != "wildcard" }
        val fallback = wildcard ?: enumArms.lastOrNull() ?: throw CodegenException("match has no arms")
        val tested = enumArms.filter { it != fallback }
        if (tested.isEmpty()) {
            emitArmBinding(fallback, temp, ctx)
            compileArmBody(fallback, ctx, wantValue)
            mv.visitLabel(end)
            return
        }
        val tagSlot = ctx.temp("Ljava/lang/String;")
        mv.visitVarInsn(Opcodes.ALOAD, temp)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getTag", "()Ljava/lang/String;", false)
        mv.visitVarInsn(Opcodes.ASTORE, tagSlot)

        val seqL = Label()
        val fbL = Label()
        val byHash = LinkedHashMap<Int, MutableList<NodeId>>()
        for (arm in tested) {
            byHash.getOrPut(patternTag(fs(arm, "path")).hashCode()) { mutableListOf() }.add(arm)
        }
        val fastCases = byHash.entries.map { (h, list) -> h to if (list.size == 1) Label() else null }
        mv.visitVarInsn(Opcodes.ALOAD, tagSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false)
        mv.visitLookupSwitchInsn(
            fbL,
            fastCases.map { it.first }.toIntArray(),
            fastCases.map { it.second ?: seqL }.toTypedArray(),
        )
        for ((i, entry) in byHash.entries.withIndex()) {
            val label = fastCases[i].second ?: continue
            mv.visitLabel(label)
            val arm = entry.value[0]
            mv.visitVarInsn(Opcodes.ALOAD, tagSlot)
            mv.visitLdcInsn(patternTag(fs(arm, "path")))
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, seqL)
            emitArmBinding(arm, temp, ctx)
            compileArmBody(arm, ctx, wantValue)
            mv.visitJumpInsn(Opcodes.GOTO, end)
        }
        mv.visitLabel(fbL)
        emitArmBinding(fallback, temp, ctx)
        compileArmBody(fallback, ctx, wantValue)
        mv.visitJumpInsn(Opcodes.GOTO, end)
        // 慢路径：哈希冲突/未命中哈希的完整顺序链（正确性网）
        mv.visitLabel(seqL)
        for (arm in tested) {
            val next = Label()
            mv.visitVarInsn(Opcodes.ALOAD, tagSlot)
            mv.visitLdcInsn(patternTag(fs(arm, "path")))
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, next)
            emitArmBinding(arm, temp, ctx)
            compileArmBody(arm, ctx, wantValue)
            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitLabel(next)
        }
        emitArmBinding(fallback, temp, ctx)
        compileArmBody(fallback, ctx, wantValue)
        mv.visitLabel(end)
    }

    /** 绑定臂的载荷提取：Variant.getPayload → 解箱 → 存入臂绑定槽。 */
    private fun emitArmBinding(arm: NodeId, temp: Int, ctx: FnCtx) {
        if (!table[arm].fields.containsKey("binding")) return
        val mv = ctx.mv
        mv.visitVarInsn(Opcodes.ALOAD, temp)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VARIANT, "getPayload", "()Ljava/lang/Object;", false)
        val payloadDesc = desc(checker.localTypeOf(arm) ?: UNIT)
        unbox(mv, payloadDesc)
        mv.visitVarInsn(storeOp(payloadDesc), ctx.bind(arm, payloadDesc))
    }

    private fun compileArmBody(arm: NodeId, ctx: FnCtx, wantValue: Boolean) {
        val body = table[arm].kids.firstOrNull() ?: return
        if (table[body].kind == Kind.Block) compileBlock(body, ctx, wantValue) else compileExpr(body, ctx)
    }

    /** Color.Red → "Red"；org.example.Color.Red → "Red"。 */
    private fun patternTag(path: String): String = path.substringAfterLast('.')

    // ---------- 表达式 ----------

    private fun compileExpr(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val kids = table[e].kids
        when (table[e].kind) {
            Kind.Literal -> compileLiteral(e, ctx)
            Kind.Path -> compilePath(e, ctx)
            Kind.FieldAccess -> {
                val structT = tOf(kids[0]) as LoongType.Named
                val d = desc(LoongType.parse(structFields[structT.name]!!.first { it.first == fs(e, "field") }.second))
                compileExpr(kids[0], ctx)
                mv.visitFieldInsn(Opcodes.GETFIELD, structInternal(structT.name), fs(e, "field"), d)
                if (d == "Ljava/lang/Object;") adaptFromObject(mv, desc(tOf(e)))
            }
            Kind.Index -> {
                val t = tOf(kids[0]) as LoongType.Named
                compileExpr(kids[0], ctx)
                compileExpr(kids[1], ctx)
                if (t.name == "Array") {
                    mv.visitInsn(Opcodes.L2I)
                    mv.visitInsn(Opcodes.AALOAD)
                    adaptFromObject(mv, desc(t.args[0]))
                } else {
                    box(mv, desc(t.args[0]))
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false,
                    )
                    // 缺席键显式化：null → Option.None，命中 → Option.Some(V)
                    mv.visitInsn(Opcodes.DUP)
                    val someL = Label()
                    val wrapEnd = Label()
                    mv.visitJumpInsn(Opcodes.IFNONNULL, someL)
                    mv.visitInsn(Opcodes.POP)
                    mv.visitTypeInsn(Opcodes.NEW, VARIANT)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitLdcInsn("None")
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VARIANT, "<init>", "(Ljava/lang/String;Ljava/lang/Object;)V", false)
                    mv.visitJumpInsn(Opcodes.GOTO, wrapEnd)
                    mv.visitLabel(someL)
                    val refSlot = ctx.temp("Ljava/lang/Object;")
                    mv.visitVarInsn(Opcodes.ASTORE, refSlot)
                    mv.visitTypeInsn(Opcodes.NEW, VARIANT)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitLdcInsn("Some")
                    mv.visitVarInsn(Opcodes.ALOAD, refSlot)
                    when (desc(t.args[1])) {
                        "J" -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long")
                        "D" -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                        "Z" -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                        "C" -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character")
                        else -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VARIANT, "<init>", "(Ljava/lang/String;Ljava/lang/Object;)V", false)
                    mv.visitLabel(wrapEnd)
                }
            }
            Kind.Call -> compileCall(e, ctx)
            Kind.Binary -> compileBinary(e, ctx)
            Kind.Unary -> {
                compileExpr(kids[0], ctx)
                when {
                    fs(e, "op") == "-" && tOf(kids[0]) == INT -> mv.visitInsn(Opcodes.LNEG)
                    fs(e, "op") == "-" && tOf(kids[0]) == FLOAT -> mv.visitInsn(Opcodes.DNEG)
                    fs(e, "op") == "!" -> { mv.visitInsn(Opcodes.ICONST_1); mv.visitInsn(Opcodes.IXOR) }
                    else -> throw CodegenException("unsupported unary op")
                }
            }
            Kind.Join -> {
                compileExpr(kids[0], ctx)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/util/concurrent/FutureTask", "get",
                    "()Ljava/lang/Object;", false,
                )
                unbox(mv, desc(tOf(e)))
            }
            Kind.Spawn -> compileSpawn(e, ctx)
            Kind.If -> compileIf(e, ctx)
            Kind.IfLet -> compileIfLet(e, ctx)
            Kind.Match -> compileMatch(e, ctx)
            else -> throw CodegenException("unsupported expression kind ${table[e].kind}")
        }
    }

    private fun compileLiteral(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        when (fs(e, "lit_kind")) {
            "int" -> mv.visitLdcInsn(parseLong(fs(e, "value")))
            "float" -> mv.visitLdcInsn(fs(e, "value").toDouble())
            "bool" -> mv.visitInsn(if (fs(e, "value") == "true") Opcodes.ICONST_1 else Opcodes.ICONST_0)
            "string" -> mv.visitLdcInsn(fs(e, "value"))
            "char" -> pushInt(mv, cookChar(fs(e, "value")).code)
            "array" -> {
                val elemDesc = desc((tOf(e) as LoongType.Named).args[0])
                pushInt(mv, table[e].kids.size)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                table[e].kids.forEachIndexed { i, el ->
                    mv.visitInsn(Opcodes.DUP)
                    pushInt(mv, i)
                    compileExpr(el, ctx)
                    boxIfObject(mv, "Ljava/lang/Object;", tOf(el))
                    mv.visitInsn(Opcodes.AASTORE)
                }
            }
            "map" -> {
                mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap")
                mv.visitInsn(Opcodes.DUP)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false)
                val mapT = tOf(e) as LoongType.Named
                for (entry in table[e].kids) {
                    mv.visitInsn(Opcodes.DUP)
                    compileExpr(table[entry].kids[0], ctx)
                    box(mv, desc(mapT.args[0]))
                    compileExpr(table[entry].kids[1], ctx)
                    box(mv, desc(mapT.args[1]))
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false,
                    )
                    mv.visitInsn(Opcodes.POP)
                }
            }
            else -> throw CodegenException("unsupported literal ${fs(e, "lit_kind")}")
        }
    }

    private fun parseLong(text: String): Long =
        if (text.startsWith("0x") || text.startsWith("0X")) java.lang.Long.parseLong(text.substring(2), 16)
        else text.toLong()

    private fun cookChar(raw: String): Char = when {
        raw.length == 1 -> raw[0]
        raw == "\\n" -> '\n'
        raw == "\\t" -> '\t'
        raw == "\\r" -> '\r'
        raw == "\\\\" -> '\\'
        raw == "\\'" -> '\''
        raw.startsWith("\\u") -> raw.substring(2).toInt(16).toChar()
        else -> throw CodegenException("bad char literal '$raw'")
    }

    private fun compilePath(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val rid = refOf(e, "resolved_id")
        if (rid != null) {
            ctx.slotOf(rid)?.let { slot ->
                mv.visitVarInsn(loadOp(ctx.descs[slot] ?: "V"), slot)
                return
            }
            when (table[rid].kind) {
                Kind.Const -> mv.visitFieldInsn(
                    Opcodes.GETSTATIC, hostInternal, fs(rid, "name"), desc(LoongType.parse(fs(rid, "type"))),
                )
                Kind.Variant -> emitVariant(mv, fs(rid, "name"), null, ctx)
                Kind.Function -> {
                    // 具名函数值 → Fn 适配实例（无闭包，捕获即参数穿引）
                    val adapter = hostInternal + "\$Fn\$" + (methodKey[rid] ?: fs(rid, "name"))
                    mv.visitTypeInsn(Opcodes.NEW, adapter)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, adapter, "<init>", "()V", false)
                }
                else -> throw CodegenException("unsupported path target ${table[rid].kind}")
            }
            return
        }
        when (fs(e, "full_name")) {
            "loong.core.Option.None" -> emitVariant(mv, "None", null, ctx)
            else -> throw CodegenException("unsupported builtin value ${fs(e, "full_name")}")
        }
    }

    private fun emitVariant(mv: MethodVisitor, tag: String, payload: NodeId?, ctx: FnCtx) {
        if (payload == null) {
            variantSingletons[tag]?.let { field ->
                mv.visitFieldInsn(Opcodes.GETSTATIC, hostInternal, field, "L$VARIANT;")
                return
            }
        }
        mv.visitTypeInsn(Opcodes.NEW, VARIANT)
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn(tag)
        if (payload == null) {
            mv.visitInsn(Opcodes.ACONST_NULL)
        } else {
            compileExpr(payload, ctx)
            box(mv, desc(tOf(payload)))
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VARIANT, "<init>", "(Ljava/lang/String;Ljava/lang/Object;)V", false)
    }

    private fun compileCall(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val callee = table[e].kids.first()
        val args = table[e].kids.drop(1)
        val rid = if (table[callee].kind == Kind.Path) refOf(callee, "resolved_id") else null
        val subst = checker.callSubst[e] ?: emptyMap()

        // 间接调用：callee 解析到持有函数值的局部绑定 → invokeinterface FnN.apply
        if (rid != null && ctx.slotOf(rid) != null) {
            val ft = LoongType.parse(fs(rid, "type")) as? LoongType.Func
                ?: throw CodegenException("callee binding is not a function value")
            val n = ft.params.size
            compileExpr(callee, ctx)
            for (arg in args) {
                val ex = table[arg].kids.first()
                compileExpr(ex, ctx)
                boxIfObject(mv, "Ljava/lang/Object;", tOf(ex))
            }
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, "dev/loong/runtime/Fn$n", "apply",
                "(" + "Ljava/lang/Object;".repeat(n) + ")Ljava/lang/Object;", true,
            )
            when (val rd = desc(ft.ret)) {
                "V" -> mv.visitInsn(Opcodes.POP)
                "J", "D", "Z", "C" -> unbox(mv, rd)
                "Ljava/lang/Object;" -> Unit
                else -> mv.visitTypeInsn(Opcodes.CHECKCAST, rd.substring(1, rd.length - 1))
            }
            return
        }

        if (rid != null) {
            when (table[rid].kind) {
                Kind.Function, Kind.ExternalMethod -> {
                    val ordered = orderedArgs(paramNames(rid), args)
                    // 装箱判断按擦除形态（与 invokeStatic 的描述符一致）
                    val params = paramTypes(rid, emptyMap())
                    ordered.forEachIndexed { i, arg ->
                        val expr = table[arg].kids.first()
                        compileExpr(expr, ctx)
                        val pd = desc(params.getOrNull(i) ?: OBJECT_T)
                        boxIfObject(mv, pd, tOf(expr))
                        coerceWidth(mv, pd, desc(tOf(expr)))
                    }
                    invokeStatic(rid, subst, mv)
                }
                Kind.Variant -> {
                    val ordered = orderedArgs(listOf("value"), args)
                    emitVariant(mv, fs(rid, "name"), ordered.firstOrNull()?.let { table[it].kids.first() }, ctx)
                }
                Kind.ExternalClass -> {
                    val owner = fs(rid, "class_fqn").replace('.', '/')
                    val ctor = table[rid].kids.first {
                        table[it].kind == Kind.ExternalMethod && fs(it, "call_kind") == "constructor"
                    }
                    val params = paramTypes(ctor, checker.callSubst[e] ?: emptyMap())
                    val mdesc = "(" + params.joinToString("") { desc(it) } + ")V"
                    mv.visitTypeInsn(Opcodes.NEW, owner)
                    mv.visitInsn(Opcodes.DUP)
                    val ordered = orderedArgs(paramNames(ctor), args)
                    ordered.forEachIndexed { i, arg ->
                        val expr = table[arg].kids.first()
                        compileExpr(expr, ctx)
                        boxIfObject(mv, desc(params.getOrNull(i) ?: OBJECT_T), tOf(expr))
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", mdesc, false)
                }
                Kind.Struct -> {
                    val name = fs(rid, "name")
                    val fields = structFields[name]!!
                    val internal = structInternal(name)
                    mv.visitTypeInsn(Opcodes.NEW, internal)
                    mv.visitInsn(Opcodes.DUP)
                    val ordered = orderedArgs(fields.map { it.first }, args)
                    ordered.forEachIndexed { i, arg ->
                        val expr = table[arg].kids.first()
                        compileExpr(expr, ctx)
                        // 泛型字段擦除为 Object：原始实参装箱
                        boxIfObject(mv, desc(LoongType.parse(fields[i].second)), tOf(expr))
                    }
                    mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, internal, "<init>",
                        "(" + fields.joinToString("") { desc(LoongType.parse(it.second)) } + ")V", false,
                    )
                }
                else -> throw CodegenException("unsupported callee kind ${table[rid].kind}")
            }
            return
        }
        when (fs(callee, "full_name")) {
            "loong.core.Option.Some" -> {
                val ordered = orderedArgs(listOf("value"), args)
                emitVariant(mv, "Some", ordered.firstOrNull()?.let { table[it].kids.first() }, ctx)
            }
            "loong.core.Result.Ok", "loong.core.Result.Err" -> {
                val tag = if (fs(callee, "full_name").endsWith("Ok")) "Ok" else "Err"
                val ordered = orderedArgs(listOf("value"), args)
                emitVariant(mv, tag, ordered.firstOrNull()?.let { table[it].kids.first() }, ctx)
            }
            "loong.core.println" -> { compileExpr(table[orderedArgs(listOf("s"), args)[0]].kids.first(), ctx); console(mv, "println", "(Ljava/lang/String;)V") }
            "loong.core.print" -> { compileExpr(table[orderedArgs(listOf("s"), args)[0]].kids.first(), ctx); console(mv, "print", "(Ljava/lang/String;)V") }
            "loong.core.concat" -> {
                val ordered = orderedArgs(listOf("a", "b"), args)
                compileExpr(table[ordered[0]].kids.first(), ctx)
                compileExpr(table[ordered[1]].kids.first(), ctx)
                console(mv, "concat", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
            }
            "loong.core.str" -> {
                compileExpr(table[orderedArgs(listOf("v"), args)[0]].kids.first(), ctx)
                console(mv, "str", "(J)Ljava/lang/String;")
            }
            "loong.core.tof" -> {
                compileExpr(table[args[0]].kids.first(), ctx)
                mv.visitInsn(Opcodes.L2D)
            }
            "loong.core.to32" -> {
                compileExpr(table[args[0]].kids.first(), ctx)
                mv.visitInsn(Opcodes.L2I)
            }
            "loong.core.to64" -> {
                compileExpr(table[args[0]].kids.first(), ctx)
                mv.visitInsn(Opcodes.I2L)
            }
            "loong.core.len" -> {
                val src = table[args[0]].kids.first()
                compileExpr(src, ctx)
                val st = tOf(src)
                if (st is LoongType.Named && st.name == "String") {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                } else {
                    mv.visitInsn(Opcodes.ARRAYLENGTH)
                }
                mv.visitInsn(Opcodes.I2L)
            }
            "loong.core.newArray" -> {
                compileExpr(table[args[0]].kids.first(), ctx)
                mv.visitInsn(Opcodes.L2I)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            }
            else -> throw CodegenException("unsupported builtin call ${fs(callee, "full_name")}")
        }
    }

    private fun console(mv: MethodVisitor, name: String, mdesc: String) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONSOLE, name, mdesc, false)
    }

    private fun compileSpawn(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val call = table[e].kids.first()
        val callee = table[call].kids.first()
        val target = refOf(callee, "resolved_id") ?: throw CodegenException("spawn target must be a resolvable function")
        val index = spawnSites.size
        spawnSites += call
        val internal = hostInternal + "\$Spawn" + index
        val subst = checker.callSubst[call] ?: emptyMap()
        val params = paramTypes(target, subst)
        val descs = params.map { desc(it) }
        // FutureTask 在栈底：new/dup 之后构造 Callable
        mv.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/FutureTask")
        mv.visitInsn(Opcodes.DUP)
        mv.visitTypeInsn(Opcodes.NEW, internal)
        mv.visitInsn(Opcodes.DUP)
        val ordered = orderedArgs(paramNames(target), table[call].kids.drop(1))
        ordered.forEach { compileExpr(table[it].kids.first(), ctx) }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "(" + descs.joinToString("") + ")V", false)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/util/concurrent/FutureTask", "<init>",
            "(Ljava/util/concurrent/Callable;)V", false,
        )
        val temp = ctx.temp("Ljava/util/concurrent/FutureTask;")
        mv.visitVarInsn(Opcodes.ASTORE, temp)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "ofVirtual", "()Ljava/lang/Thread${'$'}Builder${'$'}OfVirtual;", false)
        mv.visitVarInsn(Opcodes.ALOAD, temp)
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE, "java/lang/Thread${'$'}Builder", "start",
            "(Ljava/lang/Runnable;)Ljava/lang/Thread;", true,
        )
        mv.visitInsn(Opcodes.POP)
        mv.visitVarInsn(Opcodes.ALOAD, temp)
    }

    private fun compileBinary(e: NodeId, ctx: FnCtx) {
        val mv = ctx.mv
        val op = fs(e, "op")
        val l = table[e].kids[0]
        val r = table[e].kids[1]
        val lt = tOf(l)
        when (op) {
            "&&" -> {
                val falseL = Label()
                val end = Label()
                compileExpr(l, ctx)
                mv.visitJumpInsn(Opcodes.IFEQ, falseL)
                compileExpr(r, ctx)
                mv.visitJumpInsn(Opcodes.GOTO, end)
                mv.visitLabel(falseL)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitLabel(end)
            }
            "||" -> {
                val trueL = Label()
                val end = Label()
                compileExpr(l, ctx)
                mv.visitJumpInsn(Opcodes.IFNE, trueL)
                compileExpr(r, ctx)
                mv.visitJumpInsn(Opcodes.GOTO, end)
                mv.visitLabel(trueL)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitLabel(end)
            }
            "==", "!=", "<", "<=", ">", ">=" -> {
                val trueL = Label()
                val end = Label()
                compileExpr(l, ctx)
                compileExpr(r, ctx)
                jumpIf(mv, op, lt, trueL)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitJumpInsn(Opcodes.GOTO, end)
                mv.visitLabel(trueL)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitLabel(end)
            }
            else -> {
                compileExpr(l, ctx)
                compileExpr(r, ctx)
                when {
                    lt == INT -> when (op) {
                        "+" -> mv.visitInsn(Opcodes.LADD)
                        "-" -> mv.visitInsn(Opcodes.LSUB)
                        "*" -> mv.visitInsn(Opcodes.LMUL)
                        "/" -> mv.visitInsn(Opcodes.LDIV)
                        "%" -> mv.visitInsn(Opcodes.LREM)
                        "<<" -> { mv.visitInsn(Opcodes.L2I); mv.visitInsn(Opcodes.LSHL) }
                        ">>" -> { mv.visitInsn(Opcodes.L2I); mv.visitInsn(Opcodes.LSHR) }
                        "&" -> mv.visitInsn(Opcodes.LAND)
                        "|" -> mv.visitInsn(Opcodes.LOR)
                        else -> throw CodegenException("unsupported int op $op")
                    }
                    lt == FLOAT -> when (op) {
                        "+" -> mv.visitInsn(Opcodes.DADD)
                        "-" -> mv.visitInsn(Opcodes.DSUB)
                        "*" -> mv.visitInsn(Opcodes.DMUL)
                        "/" -> mv.visitInsn(Opcodes.DDIV)
                        "%" -> mv.visitInsn(Opcodes.DREM)
                        else -> throw CodegenException("unsupported float op $op")
                    }
                    else -> throw CodegenException("unsupported operand type for $op: ${lt.render()}")
                }
            }
        }
    }

    private fun jumpIf(mv: MethodVisitor, op: String, lt: LoongType, target: Label) {
        fun intCmp(opcode: Int) {
            mv.visitInsn(Opcodes.LCMP)
            mv.visitJumpInsn(opcode, target)
        }
        fun dblCmp(opcode: Int) {
            mv.visitInsn(Opcodes.DCMPG)
            mv.visitJumpInsn(opcode, target)
        }
        when {
            lt == INT -> intCmp(
                when (op) {
                    "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE; "<" -> Opcodes.IFLT
                    "<=" -> Opcodes.IFLE; ">" -> Opcodes.IFGT; else -> Opcodes.IFGE
                },
            )
            lt == FLOAT -> dblCmp(
                when (op) {
                    "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE; "<" -> Opcodes.IFLT
                    "<=" -> Opcodes.IFLE; ">" -> Opcodes.IFGT; else -> Opcodes.IFGE
                },
            )
            lt == BOOL || lt == CHAR -> mv.visitJumpInsn(
                if (op == "==") Opcodes.IF_ICMPEQ else Opcodes.IF_ICMPNE,
                target,
            )
            else -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false)
                mv.visitJumpInsn(if (op == "==") Opcodes.IFNE else Opcodes.IFEQ, target)
            }
        }
    }

    // ---------- 装箱 / 操作码 ----------

    private fun box(mv: MethodVisitor, d: String) {
        when (d) {
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)
        }
    }

    private fun unbox(mv: MethodVisitor, d: String) {
        when (d) {
            "J" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
            }
            "D" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
            }
            "Z" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
            }
            "C" -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false)
            }
            "Ljava/lang/String;" -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            "L$VARIANT;" -> mv.visitTypeInsn(Opcodes.CHECKCAST, VARIANT)
            else -> if (d.startsWith("L")) mv.visitTypeInsn(Opcodes.CHECKCAST, d.substring(1, d.length - 1))
        }
    }

    private fun loadOp(d: String): Int = when (d) {
        "J" -> Opcodes.LLOAD
        "D" -> Opcodes.DLOAD
        "Z", "C", "I" -> Opcodes.ILOAD
        else -> Opcodes.ALOAD
    }

    private fun storeOp(d: String): Int = when (d) {
        "J" -> Opcodes.LSTORE
        "D" -> Opcodes.DSTORE
        "Z", "C", "I" -> Opcodes.ISTORE
        else -> Opcodes.ASTORE
    }

    private fun returnOpcode(d: String): Int = when (d) {
        "J" -> Opcodes.LRETURN
        "D" -> Opcodes.DRETURN
        "Z", "C", "I" -> Opcodes.IRETURN
        "V" -> Opcodes.RETURN
        else -> Opcodes.ARETURN
    }

    private fun sizeOf(d: String): Int = if (d == "J" || d == "D") 2 else 1

    private fun arrayLoadOp(elemDesc: String): Int = when (elemDesc) {
        "J" -> Opcodes.LALOAD
        "D" -> Opcodes.DALOAD
        "Z" -> Opcodes.BALOAD
        "C" -> Opcodes.CALOAD
        else -> Opcodes.AALOAD
    }

    private fun arrayStoreOp(elemDesc: String): Int = when (elemDesc) {
        "J" -> Opcodes.LASTORE
        "D" -> Opcodes.DASTORE
        "Z" -> Opcodes.BASTORE
        "C" -> Opcodes.CASTORE
        else -> Opcodes.AASTORE
    }

    private fun pushInt(mv: MethodVisitor, v: Int) {
        when (v) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in -128..127 -> mv.visitIntInsn(Opcodes.BIPUSH, v)
            in -32768..32767 -> mv.visitIntInsn(Opcodes.SIPUSH, v)
            else -> mv.visitLdcInsn(v)
        }
    }

    /** 位置实参按声明序占前缀，具名实参按名落位（与检查器 assignArgs 语义一致）。 */
    private fun orderedArgs(paramNames: List<String>, argNodes: List<NodeId>): List<NodeId> {
        val result = arrayOfNulls<NodeId>(paramNames.size)
        var pi = 0
        for (a in argNodes) {
            val n = (table[a].fields["name"] as? FieldValue.Str)?.value
            if (n == null) {
                result[pi++] = a
            } else {
                val idx = paramNames.indexOf(n)
                if (idx < 0) throw CodegenException("unknown named argument '$n'")
                result[idx] = a
            }
        }
        return result.filterNotNull()
    }

    private fun refOf(id: NodeId, key: String): NodeId? =
        (table[id].fields[key] as? FieldValue.Ref)?.id

    private fun fs(id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
