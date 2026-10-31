package dev.loong.engine.types

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity

/**
 * 类型检查器（plan.md M1 第 6 步）。
 *
 * 契约：在 [dev.loong.engine.resolve.Resolver] 之后运行，Path 的 resolved_id 已填充。
 *
 * 策略：
 * - 声明侧：验证全部类型文本（已知名字 + 泛型元数）、where 约束是接口、禁止裸类型参数；
 * - 表达式侧：自底向上计算类型并与期望比对。源语言无类型推断——推断只发生在
 *   泛型调用点的类型实参统一与集合字面量元素统一（「显式」原则的检查器内边界）；
 * - 泛型：擦除式签名；调用点统一类型参数，统一后检查 where 约束；
 * - match：枚举穷尽性；字面量匹配必须含通配臂。
 *
 * M1 简化（记录在案）：where 约束按接口基名比较；Map 索引返回 V（缺席键语义在代码生成阶段定案）；
 * 用户泛型 struct 的构造从期望类型绑定参数；参数默认不可变。
 */
class TypeChecker(
    private val table: NodeTable,
    /** 链接进来的依赖接口 AST 的 Module 根（M4 跨模块类型）。 */
    private val extraRoots: List<NodeId> = emptyList(),
) {

    private class StructInfo(
        val name: String,
        val params: List<String>,
        val fields: List<Pair<String, String>>,
        val node: NodeId,
    )

    private class EnumInfo(
        val name: String,
        val params: List<String>,
        val variants: Map<String, String?>,
        val node: NodeId,
    )

    private class Local(val type: LoongType, val mutable: Boolean)

    private val diags = ArrayList<Diagnostic>()
    private val structs = HashMap<String, StructInfo>()
    private val enums = HashMap<String, EnumInfo>()
    private val impls = ArrayList<Pair<String, String>>()

    private val UNIT = LoongType.Named("Unit")
    private val INT = LoongType.Named("Int")
    private val INT32 = LoongType.Named("Int32")
    private val FLOAT = LoongType.Named("Float")
    private val BOOL = LoongType.Named("Bool")
    private val STRING = LoongType.Named("String")
    private val CHAR = LoongType.Named("Char")

    private val builtinArity = mapOf(
        "Int" to 0, "Float" to 0, "Bool" to 0, "String" to 0, "Char" to 0, "Unit" to 0,
        "Object" to 0, // 互操作：擦除后的 Java 引用形参/返回（design §6.2 精确 JVM 描述符）
        "Any" to 0,   // 标记接口：一切类型隐式实现；`where T: Any` 表达无约束泛型（2026-10 决策 A）,
        "Int32" to 0, // 互操作：JVM int 宽度（I）——JDK int 形参/返回的精确对接（2026-10 决策）
        "Array" to 1, "Map" to 2, "Option" to 1, "Result" to 2,
        "Handle" to 1, "Mutex" to 1, "channel" to 1, "Iterable" to 1,
    )

    /** 外部类类型：短名 → FQN（design §6.2 外部声明的精确 JVM 类型）。 */
    private val externalTypes = HashMap<String, String>()

    private val locals = HashMap<NodeId, Local>()
    private var fnRet: LoongType = UNIT
    private var currentFn: NodeId = NodeId.NONE
    private var currentTypeParams: Set<String> = emptySet()
    private var currentFnTypeParams: Set<String> = emptySet()

    /** 表达式类型侧表：代码生成消费（键 = 表达式节点，值 = 检查期计算的类型）。 */
    val exprTypes = HashMap<NodeId, LoongType>()

    /** 泛型调用的类型实参替换表：键 = Call 节点。 */
    val callSubst = HashMap<NodeId, Map<String, LoongType>>()

    fun localTypeOf(id: NodeId): LoongType? = locals[id]?.type

    // ---------- 入口 ----------

    fun check(root: NodeId): List<Diagnostic> {
        registerBuiltinEnums()
        gatherPass1(root)
        gatherPass2(root)
        checkConsts(root)
        table.find(Kind.Function).forEach { checkFunction(it) }
        table.find(Kind.Test).forEach { checkTest(it) }
        return diags
    }

    private fun registerBuiltinEnums() {
        enums["Option"] = EnumInfo("Option", listOf("T"), mapOf("Some" to "T", "None" to null), NodeId.NONE)
        enums["Result"] = EnumInfo("Result", listOf("T", "E"), mapOf("Ok" to "T", "Err" to "E"), NodeId.NONE)
    }

    // ---------- 声明收集与验证 ----------

    private fun allDecls(root: NodeId): List<NodeId> =
        table[root].kids + extraRoots.flatMap { table[it].kids }

    private fun gatherPass1(root: NodeId) {
        for (decl in allDecls(root)) {
            when (table[decl].kind) {
                Kind.Struct -> {
                    val name = fs(decl, "name")
                    val params = typeParamsOf(decl)
                    val fields = table[decl].kids.filter { table[it].kind == Kind.Field }
                        .map { fs(it, "name") to fs(it, "type") }
                    structs[name] = StructInfo(name, params, fields, decl)
                }
                Kind.Enum -> {
                    val name = fs(decl, "name")
                    val params = typeParamsOf(decl)
                    val variants = HashMap<String, String?>()
                    for (v in table[decl].kids.filter { table[it].kind == Kind.Variant }) {
                        variants[fs(v, "name")] =
                            table[v].fields["payload_type"]?.let { (it as FieldValue.Str).value }
                    }
                    enums[name] = EnumInfo(name, params, variants, decl)
                }
                Kind.Impl -> impls += fs(decl, "struct").substringBefore('<') to fs(decl, "interface")
                Kind.ExternalClass -> {
                    val fqn = fs(decl, "class_fqn")
                    externalTypes[fqn.substringAfterLast('.')] = fqn
                }
                else -> Unit
            }
        }
    }

    private fun gatherPass2(root: NodeId) {
        for (decl in allDecls(root)) {
            when (table[decl].kind) {
                Kind.Struct -> {
                    val params = typeParamsOf(decl).toSet()
                    table[decl].kids.filter { table[it].kind == Kind.Field }
                        .forEach { validateTypeText(fs(it, "type"), params, it) }
                    checkTypeParamsHaveConstraints(decl)
                }
                Kind.Enum -> {
                    val params = typeParamsOf(decl).toSet()
                    table[decl].kids.filter { table[it].kind == Kind.Variant }.forEach { v ->
                        (table[v].fields["payload_type"] as? FieldValue.Str)?.let {
                            validateTypeText(it.value, params, v)
                        }
                    }
                    checkTypeParamsHaveConstraints(decl)
                }
                Kind.Interface -> {
                    val params = typeParamsOf(decl).toSet()
                    table[decl].kids.filter { table[it].kind == Kind.MethodSig }.forEach { m ->
                        mParamsOf(m).forEach { validateTypeText(it.typeText, params, m) }
                        validateTypeText(fs(m, "return_type"), params, m)
                    }
                    checkTypeParamsHaveConstraints(decl)
                }
                else -> Unit
            }
        }
    }

    private fun checkConsts(root: NodeId) {
        for (decl in table[root].kids.filter { table[it].kind == Kind.Const }) {
            val declared = validateTypeText(fs(decl, "type"), emptySet(), decl) ?: continue
            val value = table[decl].kids.firstOrNull { table[it].kind == Kind.Literal } ?: continue
            val actual = literalType(value)
            if (actual != declared) mismatch(decl, declared, actual)
        }
    }

    private fun typeParamsOf(decl: NodeId): List<String> =
        table[decl].kids.filter { table[it].kind == Kind.TypeParam }.map { fs(it, "name") }

    /** 禁止裸类型参数：TypeParam 必须有 where 约束，且约束基名必须是接口。
     * 例外：接口的首个类型参数是接收者占位（grammar §4.3），由 impl 显式绑定。 */
    private fun checkTypeParamsHaveConstraints(decl: NodeId) {
        val tps = table[decl].kids.filter { table[it].kind == Kind.TypeParam }
        val isInterface = table[decl].kind == Kind.Interface
        tps.forEachIndexed { idx, tp ->
            if (isInterface && idx == 0) return@forEachIndexed
            val constraint = table[tp].fields["constraint"] as? FieldValue.Str
            if (constraint == null) {
                diags += Diagnostic(
                    tp, Severity.Error, DiagCodes.E_MISSING_TYPE_CONSTRAINT,
                    "type parameter '${fs(tp, "name")}' has no where constraint (bare type parameters are forbidden)",
                )
            } else {
                val base = constraint.value.substringBefore('<')
                if (base != "Iterable" && base != "Any" &&
                    table.find(Kind.Interface).none { fs(it, "name") == base }
                ) {
                    diags += Diagnostic(
                        tp, Severity.Error, DiagCodes.E_UNKNOWN_TYPE,
                        "constraint '${constraint.value}' is not an interface",
                    )
                }
            }
        }
    }

    /** 限定名归一：loong.core.Step → Step（链接模块的声明以短名入表）。 */
    private fun normalize(t: LoongType): LoongType = when (t) {
        is LoongType.Named -> {
            val short = t.name.substringAfterLast('.')
            if (short != t.name && (structs.containsKey(short) || enums.containsKey(short))) {
                LoongType.Named(short, t.args.map { normalize(it) })
            } else {
                LoongType.Named(t.name, t.args.map { normalize(it) })
            }
        }
        is LoongType.Func -> LoongType.Func(t.params.map { normalize(it) }, normalize(t.ret))
    }

    /** 验证类型文本：名字已知（模块类型 ∪ 内建 ∪ 作用域类型参数）且元数正确。 */
    /** Object = 互操作擦除形态：任何类型可显式收敛给它（调用点由 codegen 装箱）。 */
    private fun isObject(t: LoongType): Boolean = t is LoongType.Named && t.name == "Object" && t.args.isEmpty()

    private fun validateTypeText(text: String, scopeParams: Set<String>, anchor: NodeId): LoongType? {
        var ok = true
        fun walk(t: LoongType) {
            when (t) {
                is LoongType.Named -> {
                    val arity = when {
                        t.name in scopeParams -> 0
                        structs.containsKey(t.name) -> structs[t.name]!!.params.size
                        enums.containsKey(t.name) -> enums[t.name]!!.params.size
                        externalTypes.containsKey(t.name) -> 0
                        builtinArity.containsKey(t.name) -> builtinArity[t.name]!!
                        else -> {
                            ok = false
                            diags += Diagnostic(
                                anchor, Severity.Error, DiagCodes.E_UNKNOWN_TYPE,
                                "unknown type '${t.name}' in '$text'",
                            )
                            return
                        }
                    }
                    if (t.args.size != arity) {
                        ok = false
                        diags += Diagnostic(
                            anchor, Severity.Error, DiagCodes.E_UNKNOWN_TYPE,
                            "type '${t.name}' expects $arity type argument(s), got ${t.args.size} in '$text'",
                        )
                    }
                    t.args.forEach { walk(it) }
                }
                is LoongType.Func -> {
                    t.params.forEach { walk(it) }
                    walk(t.ret)
                }
            }
        }
        val parsed = normalize(LoongType.parse(text))
        walk(parsed)
        return if (ok) parsed else null
    }

    // ---------- 函数与测试 ----------

    private fun checkFunction(fn: NodeId) {
        // locals 以绑定节点为键、全工程唯一，无需清空
        currentFn = fn
        checkTypeParamsHaveConstraints(fn)
        val scope = typeParamsOf(fn).toSet()
        currentTypeParams = scope
        currentFnTypeParams = scope
        mParamsOf(fn).forEach { p ->
            val t = validateTypeText(p.typeText, scope, p.node) ?: UNIT
            locals[p.node] = Local(t, mutable = false) // 参数不可变：显式风格，重新绑定需局部 let/var
        }
        fnRet = validateTypeText(fs(fn, "return_type"), scope, fn) ?: UNIT
        val body = table[fn].kids.firstOrNull { table[it].kind == Kind.Block } ?: return
        checkBlock(body, fnRet)
    }

    private fun checkTest(test: NodeId) {
        currentFn = test
        currentTypeParams = emptySet()
        currentFnTypeParams = emptySet()
        fnRet = UNIT
        table[test].kids.firstOrNull { table[it].kind == Kind.Block }?.let { checkBlock(it, UNIT) }
    }

    private data class Param(val node: NodeId, val name: String, val typeText: String)

    private fun mParamsOf(fn: NodeId): List<Param> =
        table[fn].kids.filter { table[it].kind == Kind.Param }
            .map { Param(it, fs(it, "name"), fs(it, "type")) }

    private fun funcTypeOf(fn: NodeId): LoongType.Func =
        LoongType.Func(
            mParamsOf(fn).map { LoongType.parse(it.typeText) },
            LoongType.parse(fs(fn, "return_type")),
        )

    // ---------- 语句与块 ----------

    /**
     * 期望类型为 Unit 时尾表达式可为任意类型（丢弃）；null 表示纯语句位置（同 Unit）。
     * 尾表达式的类型比对在此完成（期望非 Unit 时）；无尾表达式的块值为 Unit——
     * 函数体以 return 语句返回时不需要尾表达式。
     */
    private fun checkBlock(block: NodeId, expected: LoongType?): LoongType {
        val kids = table[block].kids
        kids.dropLast(1).forEach { checkStmt(it, null) }
        val last = kids.lastOrNull() ?: return UNIT
        if (table[last].kind == Kind.ExprStmt) {
            val expr = table[last].kids.first()
            val t = checkExpr(expr, expected)
            // 尾部控制流表达式类型为 Unit（各分支经 return 退出）时不比对：
            // M1 无流分析，缺席路径的返回缺失检查推迟。
            val controlFlow = table[expr].kind in setOf(Kind.If, Kind.IfLet, Kind.Match)
            if (expected != null && expected != UNIT && t != expected && (!controlFlow || t != UNIT)) {
                mismatch(expr, expected, t)
            }
            return t
        }
        checkStmt(last, null)
        return UNIT
    }

    private fun checkStmt(s: NodeId, expected: LoongType?): LoongType {
        val kids = table[s].kids
        return when (table[s].kind) {
            Kind.Let -> {
                val declared = validateTypeText(fs(s, "type"), currentFnTypeParams, s) ?: UNIT
                val init = kids.firstOrNull()
                val actual = if (init != null) checkExpr(init, declared) else UNIT
                if (actual != declared && !isObject(declared)) mismatch(s, declared, actual)
                if (fb(s, "shared") && (declared as? LoongType.Named)?.name != "Mutex") {
                    diags += Diagnostic(
                        s, Severity.Error, DiagCodes.E_SHARED_REQUIRES_MUTEX,
                        "'shared' binding must have type Mutex<T>, got ${declared.render()}",
                    )
                }
                locals[s] = Local(declared, mutable = fb(s, "mutable"))
                UNIT
            }
            Kind.Assign -> {
                checkAssign(kids[0], kids[1])
                UNIT
            }
            Kind.ExprStmt -> checkExpr(kids.first(), expected)
            Kind.Return -> {
                if (kids.isEmpty()) {
                    if (fnRet != UNIT) mismatch(s, fnRet, UNIT)
                } else {
                    val t = checkExpr(kids[0], fnRet)
                    if (t != fnRet) mismatch(s, fnRet, t)
                }
                UNIT
            }
            Kind.While -> {
                requireBool(kids[0], "while condition")
                checkBlock(kids[1], null)
                UNIT
            }
            Kind.For -> {
                val iterT = checkExpr(kids[0], null)
                val elem = elementTypeOf(iterT)
                if (elem == null) {
                    diags += Diagnostic(
                        s, Severity.Error, DiagCodes.E_NOT_ITERABLE,
                        "for-in target type ${iterT.render()} does not implement Iterable",
                    )
                } else {
                    val varT = validateTypeText(fs(s, "var_type"), currentFnTypeParams, s) ?: UNIT
                    if (varT != elem) mismatch(s, elem, varT)
                }
                locals[s] = Local(elem ?: UNIT, mutable = false)
                checkBlock(kids[1], null)
                UNIT
            }
            Kind.If -> checkIf(s, expected)
            Kind.IfLet -> checkIfLet(s, expected)
            Kind.Match -> checkMatch(s, expected)
            else -> UNIT
        }
    }

    private fun checkAssign(target: NodeId, value: NodeId) {
        when (table[target].kind) {
            Kind.Path -> {
                val rid = refOf(target, "resolved_id")
                val local = rid?.let { locals[it] }
                when {
                    local == null -> diags += Diagnostic(
                        target, Severity.Error, DiagCodes.E_ASSIGN_IMMUTABLE,
                        "assignment target is not a mutable local binding",
                    )
                    !local.mutable -> diags += Diagnostic(
                        target, Severity.Error, DiagCodes.E_ASSIGN_IMMUTABLE,
                        "cannot assign to immutable binding",
                    )
                }
                checkExpr(value, local?.type)
            }
            Kind.FieldAccess -> {
                val recv = table[target].kids[0]
                val recvT = checkExpr(recv, null)
                val fieldT = fieldTypeOf(target, recvT, fs(target, "field"))
                if (table[recv].kind == Kind.Path) {
                    val rid = refOf(recv, "resolved_id")
                    val local = rid?.let { locals[it] }
                    if (local != null && !local.mutable) {
                        diags += Diagnostic(
                            target, Severity.Error, DiagCodes.E_ASSIGN_IMMUTABLE,
                            "cannot mutate a field of an immutable binding",
                        )
                    }
                }
                checkExpr(value, fieldT)
            }
            Kind.Index -> {
                val t = checkExpr(table[target].kids[0], null)
                checkExpr(table[target].kids[1], null)
                val elemT = when ((t as? LoongType.Named)?.name) {
                    "Array" -> t.args[0]
                    "Map" -> t.args[1]
                    else -> {
                        diags += Diagnostic(
                            target, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                            "indexed assignment requires Array or Map, got ${t.render()}",
                        )
                        UNIT
                    }
                }
                checkExpr(value, elemT)
            }
            else -> diags += Diagnostic(
                target, Severity.Error, DiagCodes.E_ASSIGN_IMMUTABLE,
                "invalid assignment target",
            )
        }
    }

    // ---------- 控制流 ----------

    private fun checkIf(id: NodeId, expected: LoongType?): LoongType {
        val kids = table[id].kids
        requireBool(kids[0], "if condition")
        val thenT = checkBlock(kids[1], expected)
        val elseT = kids.getOrNull(2)?.let {
            if (table[it].kind == Kind.Block) checkBlock(it, expected) else checkIf(it, expected)
        } ?: UNIT
        if (thenT != elseT) {
            diags += Diagnostic(
                id, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                "if branches have different types: ${thenT.render()} vs ${elseT.render()}",
            )
        }
        return thenT
    }

    private fun checkIfLet(id: NodeId, expected: LoongType?): LoongType {
        val kids = table[id].kids
        val scrutT = checkExpr(kids[0], null)
        bindPattern(id, fs(id, "path"), scrutT, hasBinding = table[id].fields.containsKey("binding"))
        patternPayloadType(id, scrutT)?.let { locals[id] = Local(it, mutable = false) }
        val thenT = checkBlock(kids[1], expected)
        val elseT = kids.getOrNull(2)?.let { checkBlock(it, expected) } ?: UNIT
        if (thenT != elseT) {
            diags += Diagnostic(
                id, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                "if-let branches have different types: ${thenT.render()} vs ${elseT.render()}",
            )
        }
        return thenT
    }

    private fun checkMatch(id: NodeId, expected: LoongType?): LoongType {
        val kids = table[id].kids
        val scrutT = checkExpr(kids[0], null)
        val arms = kids.drop(1)
        var result: LoongType? = null
        val covered = HashSet<String>()
        var hasWildcard = false
        var literalScrutinee = false
        for (arm in arms) {
            when (fs(arm, "pattern_kind")) {
                "wildcard" -> hasWildcard = true
                "literal" -> {
                    literalScrutinee = true
                    val litT = literalPatternType(fs(arm, "literal"))
                    if (litT != scrutT) {
                        diags += Diagnostic(
                            arm, Severity.Error, DiagCodes.E_PATTERN_TYPE_MISMATCH,
                            "literal pattern does not match scrutinee type ${scrutT.render()}",
                        )
                    }
                }
                "enum_ctor" -> {
                    val variant = bindPattern(
                        arm, fs(arm, "path"), scrutT,
                        hasBinding = table[arm].fields.containsKey("binding"),
                    )
                    if (variant != null) covered += variant
                    patternPayloadType(arm, scrutT)?.let {
                        locals[arm] = Local(it, mutable = false)
                    }
                }
            }
            val body = table[arm].kids.firstOrNull() ?: continue
            val bodyT = if (table[body].kind == Kind.Block) {
                checkBlock(body, expected)
            } else {
                checkExpr(body, expected)
            }
            if (result == null) {
                result = bodyT
            } else if (result != bodyT) {
                diags += Diagnostic(
                    arm, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                    "match arms have different types: ${result!!.render()} vs ${bodyT.render()}",
                )
            }
        }
        val scrutName = (scrutT as? LoongType.Named)?.name
        val enumInfo = scrutName?.let { enums[it] }
        if (enumInfo != null) {
            val missing = enumInfo.variants.keys - covered
            if (!hasWildcard && missing.isNotEmpty()) {
                diags += Diagnostic(
                    id, Severity.Error, DiagCodes.E_NOT_EXHAUSTIVE,
                    "match is not exhaustive: missing ${missing.joinToString { "$scrutName.$it" }}",
                )
            }
        } else if (literalScrutinee && !hasWildcard) {
            diags += Diagnostic(
                id, Severity.Error, DiagCodes.E_NOT_EXHAUSTIVE,
                "match on ${scrutT.render()} requires a wildcard arm",
            )
        }
        return result ?: UNIT
    }

    /** 校验 Enum.Variant 模式；返回变体名（null = 已报诊断）。同时返回载荷类型。 */
    private fun bindPattern(
        anchor: NodeId,
        path: String,
        scrutT: LoongType,
        hasBinding: Boolean,
    ): String? {
        val segs = path.split('.')
        if (segs.size < 2) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_PATTERN_TYPE_MISMATCH,
                "enum constructor pattern must look like Enum.Variant, got '$path'",
            )
            return null
        }
        val enumName = segs[segs.size - 2]
        val variantName = segs.last()
        val scrutName = (scrutT as? LoongType.Named)?.name
        val info = enums[enumName]
        if (scrutName == null || info == null || scrutName != enumName) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_PATTERN_TYPE_MISMATCH,
                "pattern '$path' does not match scrutinee type ${scrutT.render()}",
            )
            return null
        }
        if (!info.variants.containsKey(variantName)) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_PATTERN_TYPE_MISMATCH,
                "enum '$enumName' has no variant '$variantName'",
            )
            return null
        }
        if (hasBinding && info.variants[variantName] == null) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_PATTERN_TYPE_MISMATCH,
                "variant '$enumName.$variantName' has no payload to bind",
            )
        }
        return variantName
    }

    private fun patternPayloadType(arm: NodeId, scrutT: LoongType): LoongType? {
        val segs = fs(arm, "path").split('.')
        val enumName = segs.getOrNull(segs.size - 2) ?: return null
        val info = enums[enumName] ?: return null
        val variant = segs.last()
        if (!info.variants.containsKey(variant)) return null
        val text = info.variants[variant] ?: return null
        val args = (scrutT as? LoongType.Named)?.args ?: emptyList()
        val subst = HashMap<String, LoongType>()
        info.params.zip(args).forEach { (p, a) -> subst[p] = a }
        return applySubst(LoongType.parse(text), subst)
    }

    // ---------- 表达式 ----------

    /** 记录类型后返回（代码生成读取 exprTypes）。 */
    private fun checkExpr(e: NodeId, expected: LoongType?): LoongType {
        val t = checkExprInner(e, expected)
        exprTypes[e] = t
        return t
    }

    private fun checkExprInner(e: NodeId, expected: LoongType?): LoongType {
        val kids = table[e].kids
        return when (table[e].kind) {
            Kind.Literal -> literalType(e, expected)
            Kind.Path -> pathType(e, expected)
            Kind.FieldAccess -> {
                val recvT = checkExpr(kids[0], null)
                fieldTypeOf(e, recvT, fs(e, "field"))
            }
            Kind.Index -> {
                val t = checkExpr(kids[0], null)
                val idx = checkExpr(kids[1], null)
                when {
                    t is LoongType.Named && t.name == "Array" && idx == INT -> t.args[0]
                    // Map 索引缺席键显式处理：返回 Option<V>（无 null 哲学，2026 决策）
                    t is LoongType.Named && t.name == "Map" && idx == t.args[0] ->
                        LoongType.Named("Option", listOf(t.args[1]))
                    t is LoongType.Named && t.name == "Map" -> {
                        mismatch(e, t.args[0], idx)
                        LoongType.Named("Option", listOf(t.args[1]))
                    }
                    else -> {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                            "indexing requires Array or Map, got ${t.render()}",
                        )
                        UNIT
                    }
                }
            }
            Kind.Call -> checkCall(e, expected)
            Kind.Binary -> checkBinary(e)
            Kind.Unary -> checkUnary(e)
            Kind.Join -> {
                val t = checkExpr(kids[0], null)
                if (t is LoongType.Named && t.name == "Handle") t.args[0]
                else {
                    diags += Diagnostic(
                        e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                        "join requires Handle<T>, got ${t.render()}",
                    )
                    UNIT
                }
            }
            Kind.Spawn -> LoongType.Named("Handle", listOf(checkExpr(kids[0], null)))
            Kind.If -> checkIf(e, expected)
            Kind.IfLet -> checkIfLet(e, expected)
            Kind.Match -> checkMatch(e, expected)
            else -> UNIT
        }
    }

    private fun literalType(e: NodeId, expected: LoongType? = null): LoongType = when (fs(e, "lit_kind")) {
        "int" -> INT
        "float" -> FLOAT
        "bool" -> BOOL
        "string" -> STRING
        "char" -> CHAR
        "array" -> {
            val elemExpected = (expected as? LoongType.Named)?.takeIf { it.name == "Array" }?.args?.getOrNull(0)
            var elem: LoongType? = elemExpected
            for (k in table[e].kids) {
                val t = checkExpr(k, elem)
                if (elem == null) elem = t
                else if (t != elem) mismatch(k, elem!!, t)
            }
            if (elem == null) {
                diags += Diagnostic(
                    e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                    "cannot determine element type of empty array literal; an annotation is required",
                )
                LoongType.Named("Array", listOf(UNIT))
            } else {
                LoongType.Named("Array", listOf(elem!!))
            }
        }
        "map" -> {
            val mExpected = (expected as? LoongType.Named)?.takeIf { it.name == "Map" }?.args
            val kExpected = mExpected?.getOrNull(0)
            val vExpected = mExpected?.getOrNull(1)
            var k: LoongType? = kExpected
            var v: LoongType? = vExpected
            for (entry in table[e].kids) {
                val kt = checkExpr(table[entry].kids[0], kExpected)
                val vt = checkExpr(table[entry].kids[1], vExpected)
                if (k == null) k = kt
                else if (kt != k) mismatch(entry, k!!, kt)
                if (v == null) v = vt
                else if (vt != v) mismatch(entry, v!!, vt)
            }
            if (k == null || v == null) {
                diags += Diagnostic(
                    e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                    "cannot determine element types of empty map literal; an annotation is required",
                )
                LoongType.Named("Map", listOf(UNIT, UNIT))
            } else {
                LoongType.Named("Map", listOf(k!!, v!!))
            }
        }
        else -> UNIT
    }

    private fun literalPatternType(text: String): LoongType = when {
        text == "true" || text == "false" -> BOOL
        text.startsWith("\"") -> STRING
        text.startsWith("'") -> CHAR
        text.contains('.') -> FLOAT
        else -> INT
    }

    private fun pathType(e: NodeId, expected: LoongType?): LoongType {
        val rid = refOf(e, "resolved_id")
        if (rid != null) {
            locals[rid]?.let { return it.type }
            // 目标节点已被删除（防御：解析器重算时会清引用，此处兜底避免崩溃）
            val target = table.getOrNull(rid) ?: return UNIT
            return when (target.kind) {
                Kind.Function, Kind.ExternalMethod -> funcTypeOf(rid)
                Kind.Const -> LoongType.parse(fs(rid, "type"))
                Kind.Variant -> {
                    val parentNode = table.getOrNull(table[rid].parent)
                    val info = parentNode?.let { enums[fs(it.id, "name")] }
                    val variant = fs(rid, "name")
                    if (info != null && info.variants[variant] != null) {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_CALL_NOT_FUNCTION,
                            "variant '${info.name}.$variant' has a payload and must be constructed by a call",
                        )
                    }
                    LoongType.Named(info?.name ?: "Unit")
                }
                else -> UNIT
            }
        }
        return when (fs(e, "full_name")) {
            "loong.core.Option.None" -> {
                val exp = expected as? LoongType.Named
                if (exp?.name == "Option") exp
                else {
                    diags += Diagnostic(
                        e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                        "cannot infer type argument of Option.None; an annotation is required",
                    )
                    LoongType.Named("Option", listOf(UNIT))
                }
            }
            else -> UNIT
        }
    }

    private fun fieldTypeOf(anchor: NodeId, recvT: LoongType, field: String): LoongType {
        val named = recvT as? LoongType.Named
        val info = named?.let { structs[it.name] }
        if (info == null) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                "field access requires a struct type, got ${recvT.render()}",
            )
            return UNIT
        }
        val fieldText = info.fields.firstOrNull { it.first == field }?.second
        if (fieldText == null) {
            diags += Diagnostic(
                anchor, Severity.Error, DiagCodes.E_UNKNOWN_FIELD,
                "struct '${info.name}' has no field '$field'",
            )
            return UNIT
        }
        val subst = HashMap<String, LoongType>()
        info.params.zip(named.args).forEach { (p, a) -> subst[p] = a }
        return applySubst(LoongType.parse(fieldText), subst)
    }

    private fun checkBinary(e: NodeId): LoongType {
        val op = fs(e, "op")
        val l = checkExpr(table[e].kids[0], null)
        val r = checkExpr(table[e].kids[1], null)
        fun bad(): LoongType {
            diags += Diagnostic(
                e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                "operator '$op' cannot be applied to ${l.render()} and ${r.render()}",
            )
            return UNIT
        }
        return when (op) {
            "&&", "||" -> if (l == BOOL && r == BOOL) BOOL else bad()
            "==", "!=", "<", "<=", ">", ">=" -> if (l == r && isComparable(l)) BOOL else bad()
            "<<", ">>", "&", "|" -> if (l == INT && r == INT) INT else bad()
            "+", "-", "*", "/", "%" -> if ((l == INT || l == FLOAT) && l == r) l else bad()
            else -> bad()
        }
    }

    private fun isComparable(t: LoongType): Boolean = when {
        t == INT || t == FLOAT || t == BOOL || t == STRING || t == CHAR -> true
        t is LoongType.Named && enums.containsKey(t.name) -> true
        else -> false
    }

    private fun checkUnary(e: NodeId): LoongType {
        val op = fs(e, "op")
        val t = checkExpr(table[e].kids[0], null)
        return when {
            op == "!" && t == BOOL -> BOOL
            op == "-" && (t == INT || t == FLOAT) -> t
            else -> {
                diags += Diagnostic(
                    e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                    "operator '$op' cannot be applied to ${t.render()}",
                )
                UNIT
            }
        }
    }

    // ---------- 调用 ----------

    private fun argExpr(arg: NodeId): NodeId = table[arg].kids.first()

    private fun checkCall(e: NodeId, expected: LoongType?): LoongType {
        val callee = table[e].kids.firstOrNull() ?: return UNIT
        val args = table[e].kids.drop(1)
        val rid = if (table[callee].kind == Kind.Path) refOf(callee, "resolved_id") else null

        // 局部函数值调用
        if (rid != null && locals.containsKey(rid)) {
            val t = locals[rid]!!.type
            if (t is LoongType.Func) {
                return checkApply(
                    e,
                    t.params.mapIndexed { i, p -> "#$i" to p },
                    emptySet(), t.ret, null, args, expected,
                )
            }
        }

        if (rid != null) {
            if (table.getOrNull(rid) == null) return UNIT // 目标已删除；解析器重算会报告 unknown
            return when (table[rid].kind) {
                Kind.Function, Kind.ExternalMethod -> {
                    val sig = mParamsOf(rid).map { it.name to LoongType.parse(it.typeText) }
                    checkApply(
                        e, sig, typeParamsOf(rid).toSet(),
                        LoongType.parse(fs(rid, "return_type")),
                        rid, args, expected,
                    )
                }
                Kind.Variant -> {
                    val parentNode = table.getOrNull(table[rid].parent) ?: return UNIT
                    val info = enums[fs(parentNode.id, "name")] ?: return UNIT
                    val variant = fs(rid, "name")
                    val payload = info.variants[variant]
                    if (payload == null) {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_CALL_NOT_FUNCTION,
                            "variant '${info.name}.$variant' has no payload and is not callable",
                        )
                        return LoongType.Named(info.name)
                    }
                    // 用户枚举 v1 非泛型：载荷类型即声明文本
                    checkArgsExact(e, listOf("value" to LoongType.parse(payload)), args)
                    LoongType.Named(info.name)
                }
                Kind.Struct -> {
                    val info = structs[fs(rid, "name")] ?: return UNIT
                    checkConstruct(e, info, args, expected)
                }
                Kind.ExternalClass -> {
                    // 外部构造：new + invokespecial（design §6.2 constructor → invokespecial）
                    val ctor = table[rid].kids.firstOrNull {
                        table[it].kind == Kind.ExternalMethod && fs(it, "call_kind") == "constructor"
                    } ?: run {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_EXTERNAL_METHOD_NOT_FOUND,
                            "external class '${fs(rid, "class_fqn")}' declares no constructor",
                        )
                        return UNIT
                    }
                    val sig = mParamsOf(ctor).map { it.name to LoongType.parse(it.typeText) }
                    checkApply(
                        e, sig, emptySet(),
                        LoongType.Named(fs(rid, "class_fqn").substringAfterLast('.')),
                        ctor, args, expected,
                    )
                }
                else -> {
                    diags += Diagnostic(e, Severity.Error, DiagCodes.E_CALL_NOT_FUNCTION, "callee is not a function")
                    UNIT
                }
            }
        }

        return when (fs(callee, "full_name")) {
            "loong.core.println" -> checkApply(
                e, listOf("s" to STRING), emptySet(), UNIT, null, args, expected,
            )
            "loong.core.print" -> checkApply(
                e, listOf("s" to STRING), emptySet(), UNIT, null, args, expected,
            )
            "loong.core.concat" -> checkApply(
                e, listOf("a" to STRING, "b" to STRING), emptySet(), STRING, null, args, expected,
            )
            "loong.core.str" -> checkApply(
                e, listOf("v" to INT), emptySet(), STRING, null, args, expected,
            )
            "loong.core.tof" -> checkApply(e, listOf("v" to INT), emptySet(), FLOAT, null, args, expected)
            "loong.core.to32" -> checkApply(e, listOf("v" to INT), emptySet(), INT32, null, args, expected)
            "loong.core.to64" -> checkApply(e, listOf("v" to INT32), emptySet(), INT, null, args, expected)
            "loong.core.len" -> {
                if (args.size != 1) {
                    arity(e, 1, args.size)
                    UNIT
                } else {
                    when (val t = checkExpr(argExpr(args[0]), null)) {
                        is LoongType.Named -> when (t.name) {
                            "Array" -> INT
                            "String" -> INT
                            else -> {
                                diags += Diagnostic(
                                    e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                                    "len requires Array or String, got ${t.render()}",
                                )
                                UNIT
                            }
                        }
                        else -> {
                            diags += Diagnostic(
                                e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                                "len requires Array or String, got ${t.render()}",
                            )
                            UNIT
                        }
                    }
                }
            }
            "loong.core.newArray" -> {
                val exp = expected as? LoongType.Named
                if (exp?.name != "Array") {
                    diags += Diagnostic(
                        e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                        "newArray requires an expected type Array<T> (annotation)",
                    )
                    UNIT
                } else {
                    if (args.size != 1) arity(e, 1, args.size)
                    else checkExpr(argExpr(args[0]), INT)
                    exp
                }
            }
            "loong.core.Option.Some" -> {
                if (args.size != 1) {
                    arity(e, 1, args.size)
                    UNIT
                } else {
                    // 期望类型透传给载荷：构造嵌套泛型（Option<Step<...>>）依赖它实例化
                    val want = (expected as? LoongType.Named)?.takeIf { it.name == "Option" }?.args?.getOrNull(0)
                    LoongType.Named("Option", listOf(checkExpr(argExpr(args[0]), want)))
                }
            }
            "loong.core.Result.Ok", "loong.core.Result.Err" -> {
                val ok = fs(callee, "full_name").endsWith("Ok")
                val exp = expected as? LoongType.Named
                if (exp?.name != "Result") {
                    diags += Diagnostic(
                        e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                        "cannot infer type arguments of ${if (ok) "Result.Ok" else "Result.Err"}; an annotation is required",
                    )
                    UNIT
                } else {
                    val want = if (ok) exp.args[0] else exp.args[1]
                    if (args.size != 1) arity(e, 1, args.size)
                    else checkExpr(argExpr(args[0]), want)
                    exp
                }
            }
            "loong.core.send" -> {
                if (args.size != 2) arity(e, 2, args.size)
                else {
                    val ch = checkExpr(argExpr(args[0]), null)
                    if (ch is LoongType.Named && ch.name == "channel") {
                        checkExpr(argExpr(args[1]), ch.args[0])
                    } else {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                            "send requires a channel, got ${ch.render()}",
                        )
                    }
                }
                UNIT
            }
            "loong.core.recv" -> {
                if (args.size != 1) {
                    arity(e, 1, args.size)
                    UNIT
                } else {
                    val ch = checkExpr(argExpr(args[0]), null)
                    if (ch is LoongType.Named && ch.name == "channel") ch.args[0]
                    else {
                        diags += Diagnostic(
                            e, Severity.Error, DiagCodes.E_BAD_OPERAND_TYPE,
                            "recv requires a channel, got ${ch.render()}",
                        )
                        UNIT
                    }
                }
            }
            else -> {
                // callee 无 resolved_id 且无 full_name：名字解析已报告 E_UNKNOWN_NAME；
                // 返回期望类型（若有）避免级联误报
                if (fs(callee, "full_name").isNotEmpty()) {
                    diags += Diagnostic(e, Severity.Error, DiagCodes.E_CALL_NOT_FUNCTION, "callee is not a function")
                }
                expected ?: UNIT
            }
        }
    }

    /**
     * 泛型调用：期望类型先尝试统一 → 实参分配与统一 → 类型参数完备 → where 约束 → 返回实例化。
     * calleeFn 供约束检查与 impl 接口推导（函数值调用传 null）。
     */
    private fun checkApply(
        call: NodeId,
        sig: List<Pair<String, LoongType>>,
        typeParams: Set<String>,
        retType: LoongType,
        calleeFn: NodeId?,
        args: List<NodeId>,
        expected: LoongType?,
    ): LoongType {
        val subst = HashMap<String, LoongType>()
        if (expected != null) unify(retType, expected, subst, typeParams)
        // 被调方若是 impl 函数，记录其接口基名（供泛型体内的接口方法调用豁免）
        val implBase = calleeFn
            ?.let { table.getOrNull(table[it].parent) }
            ?.let { node -> if (node.kind == Kind.Impl) fs(node.id, "interface").substringBefore('<') else null }
        val assigned = assignArgs(call, sig, args) ?: return UNIT
        for ((_, paramT, argNode) in assigned) {
            val actual = checkExpr(argExpr(argNode), applySubst(paramT, subst))
            if (isObject(paramT)) continue // 擦除形参：任意实参可收敛（调用点装箱）
            if (!unify(paramT, actual, subst, typeParams)) {
                if (!isOpaqueInterfaceCall(actual, implBase)) {
                    mismatch(argNode, applySubst(paramT, subst), actual)
                }
            }
        }
        val missing = typeParams.filter { !subst.containsKey(it) }
        if (missing.isNotEmpty()) {
            diags += Diagnostic(
                call, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                "cannot infer type parameter(s) ${missing.joinToString()} from the call",
            )
        }
        if (calleeFn == null) return applySubst(retType, subst)
        callSubst[call] = HashMap(subst)
        for (tp in table[calleeFn].kids.filter { table[it].kind == Kind.TypeParam }) {
            val name = fs(tp, "name")
            val bound = subst[name] ?: continue
            val constraint = (table[tp].fields["constraint"] as? FieldValue.Str)?.value ?: continue
            // 绑定仍是当前函数的不透明类型参数时不检查（本函数的约束已在声明处验证语义）
            if (bound is LoongType.Named && bound.args.isEmpty() && bound.name in currentTypeParams) continue
            if (!implementsInterface(bound, constraint.substringBefore('<'))) {
                diags += Diagnostic(
                    call, Severity.Error, DiagCodes.E_CONSTRAINT_NOT_SATISFIED,
                    "type argument ${bound.render()} for '$name' does not satisfy constraint '$constraint'",
                )
            }
        }
        return applySubst(retType, subst)
    }

    /**
     * 泛型函数体内以不透明类型参数调用接口方法（如 where T: Show 内的 show(self: v)）：
     * 实参是当前函数的类型参数，且该参数的 where 约束覆盖 impl 的接口基名时，放行。
     */
    private fun isOpaqueInterfaceCall(actual: LoongType, implBase: String?): Boolean {
        if (implBase == null) return false
        if (actual !is LoongType.Named || actual.args.isNotEmpty()) return false
        if (actual.name !in currentTypeParams) return false
        val currentConstraints = HashMap<String, String>()
        if (currentFn != NodeId.NONE) {
            for (tp in table[currentFn].kids.filter { table[it].kind == Kind.TypeParam }) {
                (table[tp].fields["constraint"] as? FieldValue.Str)?.let {
                    currentConstraints[fs(tp, "name")] = it.value.substringBefore('<')
                }
            }
        }
        return currentConstraints[actual.name] == implBase
    }

    private fun checkArgsExact(call: NodeId, sig: List<Pair<String, LoongType>>, args: List<NodeId>) {
        val assigned = assignArgs(call, sig, args) ?: return
        for ((_, paramT, argNode) in assigned) {
            val actual = checkExpr(argExpr(argNode), paramT)
            if (actual != paramT) mismatch(argNode, paramT, actual)
        }
    }

    /** 位置实参按序、具名实参按名分配；报告元数/未知名字/重复，失败返回 null。 */
    private fun assignArgs(
        call: NodeId,
        sig: List<Pair<String, LoongType>>,
        args: List<NodeId>,
    ): List<Triple<String, LoongType, NodeId>>? {
        val result = ArrayList<Triple<String, LoongType, NodeId>>()
        val remaining = LinkedHashMap(sig.toMap())
        var positionalIndex = 0
        for (arg in args) {
            val name = (table[arg].fields["name"] as? FieldValue.Str)?.value
            if (name == null) {
                val used = sig.getOrNull(positionalIndex)
                if (used == null) {
                    arity(call, sig.size, args.size)
                    return null
                }
                if (!remaining.containsKey(used.first)) {
                    diags += Diagnostic(
                        arg, Severity.Error, DiagCodes.E_NAMED_ARG_UNKNOWN,
                        "duplicate argument for parameter '${used.first}'",
                    )
                    return null
                }
                remaining.remove(used.first)
                result += Triple(used.first, used.second, arg)
                positionalIndex++
            } else {
                val paramT = remaining.remove(name)
                if (paramT == null) {
                    val known = sig.map { it.first }
                    diags += Diagnostic(
                        arg, Severity.Error, DiagCodes.E_NAMED_ARG_UNKNOWN,
                        if (name in known) "duplicate argument for parameter '$name'"
                        else "no parameter named '$name' (parameters: ${known.joinToString()})",
                    )
                    return null
                }
                result += Triple(name, paramT, arg)
            }
        }
        if (remaining.isNotEmpty()) {
            arity(call, sig.size, args.size)
            return null
        }
        return result
    }

    /** struct 构造：具名实参对应字段；泛型 struct 从期望类型绑定参数。 */
    private fun checkConstruct(
        call: NodeId,
        info: StructInfo,
        args: List<NodeId>,
        expected: LoongType?,
    ): LoongType {
        val subst = HashMap<String, LoongType>()
        if (info.params.isNotEmpty()) {
            val exp = expected as? LoongType.Named
            if (exp != null && exp.name == info.name && exp.args.size == info.params.size) {
                info.params.zip(exp.args).forEach { (p, a) -> subst[p] = a }
            } else {
                diags += Diagnostic(
                    call, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                    "cannot infer type arguments of generic struct '${info.name}'; an annotation is required",
                )
            }
        }
        val sig = info.fields.map { (n, t) -> n to applySubst(LoongType.parse(t), subst) }
        checkArgsExact(call, sig, args)
        return LoongType.Named(info.name, info.params.map { subst[it] ?: UNIT })
    }

    // ---------- 统一与替换 ----------

    private fun unify(
        param: LoongType,
        actual: LoongType,
        subst: MutableMap<String, LoongType>,
        typeParams: Set<String>,
    ): Boolean {
        if (param is LoongType.Named && param.args.isEmpty() && param.name in typeParams) {
            val bound = subst[param.name]
            // bound == param：期望驱动路径把未绑定的 T 原样回传造成的自引用假绑定，视为未绑定
            if (bound == null || bound == param || bound == actual) {
                subst[param.name] = actual
                return true
            }
            return false
        }
        if (param is LoongType.Named && actual is LoongType.Named &&
            param.name == actual.name && param.args.size == actual.args.size
        ) {
            return param.args.zip(actual.args).all { (p, a) -> unify(p, a, subst, typeParams) }
        }
        if (param is LoongType.Func && actual is LoongType.Func &&
            param.params.size == actual.params.size
        ) {
            param.params.zip(actual.params).forEach { (p, a) -> if (!unify(p, a, subst, typeParams)) return false }
            return unify(param.ret, actual.ret, subst, typeParams)
        }
        return param == actual
    }

    private fun applySubst(t: LoongType, subst: Map<String, LoongType>): LoongType = when (t) {
        is LoongType.Named ->
            if (t.args.isEmpty() && subst.containsKey(t.name)) subst[t.name]!!
            else LoongType.Named(t.name, t.args.map { applySubst(it, subst) })
        is LoongType.Func ->
            LoongType.Func(t.params.map { applySubst(it, subst) }, applySubst(t.ret, subst))
    }

    // ---------- 接口实现表 ----------

    private fun implementsInterface(type: LoongType, interfaceBase: String): Boolean {
        if (interfaceBase == "Any") return true // 标记接口：一切类型隐式实现
        val n = type as? LoongType.Named ?: return false
        return when (n.name) {
            "Array", "String" -> interfaceBase == "Iterable"
            else -> impls.any { it.first == n.name && it.second.substringBefore('<') == interfaceBase }
        }
    }

    private fun elementTypeOf(t: LoongType): LoongType? {
        val n = t as? LoongType.Named ?: return null
        return when (n.name) {
            "Array" -> n.args[0]
            "String" -> CHAR
            else -> {
                val itf = impls.firstOrNull {
                    it.first == n.name && it.second.substringBefore('<') == "Iterable"
                } ?: return null
                val args = (LoongType.parse(itf.second) as? LoongType.Named)?.args ?: emptyList()
                val subst = HashMap<String, LoongType>()
                structs[n.name]?.params?.zip(n.args)?.forEach { (p, a) -> subst[p] = a }
                args.getOrNull(0)?.let { applySubst(it, subst) }
            }
        }
    }

    // ---------- 工具 ----------

    private fun requireBool(e: NodeId, what: String) {
        val t = checkExpr(e, null)
        if (t != BOOL) {
            diags += Diagnostic(
                e, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
                "$what must be Bool, got ${t.render()}",
            )
        }
    }

    private fun mismatch(anchor: NodeId, expected: LoongType, actual: LoongType) {
        diags += Diagnostic(
            anchor, Severity.Error, DiagCodes.E_TYPE_MISMATCH,
            "expected ${expected.render()} but found ${actual.render()}",
        )
    }

    private fun arity(anchor: NodeId, expected: Int, actual: Int) {
        diags += Diagnostic(
            anchor, Severity.Error, DiagCodes.E_ARITY_MISMATCH,
            "expected $expected argument(s) but got $actual",
        )
    }

    private fun fb(id: NodeId, key: String): Boolean =
        (table[id].fields[key] as? FieldValue.Bool)?.value == true

    private fun refOf(id: NodeId, key: String): NodeId? =
        (table[id].fields[key] as? FieldValue.Ref)?.id

    private fun fs(id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
