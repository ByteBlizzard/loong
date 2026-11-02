package dev.loong.engine.rules

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable

/**
 * AST 结构不变量（ast-schema-v1.md §3 的可执行形态，与 Parser 产出严格一致）。
 *
 * 三个消费方共用同一张表，保证「写入被拒的形态」=「重算报 E_INVARIANT_VIOLATION 的形态」
 * =「文档/工具描述的形态」：
 * 1. daemon 树写入校验（LoongdServer.validateTree）：违反 → 整批拒绝零副作用；
 * 2. Workspace.recompute 门禁：违反 → E_INVARIANT_VIOLATION 诊断（跳过名字/类型/
 *    字节码阶段，杜绝下游裸索引崩溃与陈旧绿诊断）；
 * 3. find_nodes?broken=true / MCP list_ast_kinds：审计与自描述。
 *
 * 原则：只约束**结构**（kids 数量与容器槽位），不猜表达式语义——语义交给类型检查。
 */
object StructuralInvariants {

    private val STMT_KINDS = setOf(
        Kind.Let, Kind.Assign, Kind.If, Kind.IfLet, Kind.While, Kind.For,
        Kind.Match, Kind.Return, Kind.Break, Kind.Continue, Kind.ExprStmt,
    )

    private val EXPR_KINDS = setOf(
        Kind.Call, Kind.Arg, Kind.Binary, Kind.Unary, Kind.Literal, Kind.MapEntry,
        Kind.Path, Kind.FieldAccess, Kind.Index, Kind.Spawn, Kind.Join,
        Kind.If, Kind.IfLet, Kind.Match,
    )

    private val MODULE_DECL_KINDS = setOf(
        Kind.Depends, Kind.Function, Kind.Struct, Kind.Interface, Kind.Impl,
        Kind.Enum, Kind.Const, Kind.Test, Kind.ExternalClass,
    )

    /**
     * 单节点结构检查：返回违例描述（可直接进诊断/异常消息），null = 合法。
     * [kidKinds] 为子节点 kind 顺序表；[litKind] 仅 Literal 用。
     */
    fun problemOf(kind: Kind, kidKinds: List<Kind>, litKind: String? = null): String? {
        fun kid(i: Int): Kind? = kidKinds.getOrNull(i)
        fun docOnlyAtHead(): String? {
            val docIdx = kidKinds.indexOf(Kind.DocComment)
            return if (docIdx > 0) "DocComment must be the first kid (found at $docIdx)" else null
        }
        return when (kind) {
            Kind.Module -> kidKinds.filter { it !in MODULE_DECL_KINDS }
                .takeIf { it.isNotEmpty() }
                ?.let { "module kids must be declarations, found ${it.first()}" }

            Kind.Depends, Kind.DocComment, Kind.Param, Kind.TypeParam, Kind.Path,
            Kind.Break, Kind.Continue,
            -> if (kidKinds.isNotEmpty()) "$kind takes no kids (found ${kidKinds.size})" else null

            Kind.Function -> {
                // Block 可缺省：链接进来的依赖接口 AST 是无体签名形态（Packages 接口提取）
                docOnlyAtHead()
                    ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Param, Kind.TypeParam, Kind.Block) }
                        .firstOrNull()?.let { "function kid must be Param/TypeParam/Block, found $it" }
                    ?: (kidKinds.count { it == Kind.Block } atMost 1)?.let { "function takes at most one Block body kid (found $it)" }
            }

            Kind.Struct -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Field, Kind.TypeParam) }
                    .firstOrNull()?.let { "struct kid must be TypeParam/Field, found $it" }

            Kind.Field -> if (kidKinds.any { it != Kind.DocComment }) "field takes no kids besides DocComment" else null

            Kind.Interface -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.MethodSig, Kind.TypeParam) }
                    .firstOrNull()?.let { "interface kid must be TypeParam/MethodSig, found $it" }

            Kind.MethodSig -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.TypeParam, Kind.Param) }
                    .firstOrNull()?.let { "method signature kid must be TypeParam/Param, found $it" }

            Kind.Impl -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Function) }
                    .firstOrNull()?.let { "impl kid must be Function, found $it" }

            Kind.Enum -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Variant, Kind.TypeParam) }
                    .firstOrNull()?.let { "enum kid must be TypeParam/Variant, found $it" }

            Kind.Variant -> if (kidKinds.any { it != Kind.DocComment }) "variant takes no kids besides DocComment" else null

            Kind.Const -> docOnlyAtHead()
                ?: if (kidKinds.none { it != Kind.DocComment }) "const requires a value kid" else null

            Kind.Test -> {
                docOnlyAtHead()
                    ?: (kidKinds.count { it == Kind.Block } atMost 1)?.let { "test takes at most one Block body kid (found $it)" }
                    ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Block) }
                        .firstOrNull()?.let { "test kid must be Block, found $it" }
            }

            Kind.ExternalClass -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.ExternalMethod) }
                    .firstOrNull()?.let { "external class kid must be ExternalMethod, found $it" }

            Kind.ExternalMethod -> docOnlyAtHead()
                ?: kidKinds.filter { it !in setOf(Kind.DocComment, Kind.Param) }
                    .firstOrNull()?.let { "external method kid must be Param, found $it" }

            Kind.Block -> kidKinds.filter { it !in STMT_KINDS }
                .firstOrNull()?.let { "block kid must be a statement, found $it (else-Block/循环体只能装语句)" }

            Kind.Let -> if (kidKinds.size != 1) "let takes exactly one init expression kid (found ${kidKinds.size})" else null

            Kind.Assign -> if (kidKinds.size != 2) "assign takes target + value kids (found ${kidKinds.size})" else null

            Kind.If -> when {
                kidKinds.size !in 2..3 -> "if takes [cond, then-Block, else?]" +
                    " (found ${kidKinds.size} kids——多出的语句不会被渲染/编译，else 只认第 3 槽)"
                kid(1) != Kind.Block -> "if kid[1] must be then-Block, found ${kid(1)}"
                kidKinds.size == 3 && kid(2) !in setOf(Kind.Block, Kind.If) ->
                    "if kid[2] (else) must be Block or If, found ${kid(2)}"
                else -> null
            }

            Kind.IfLet -> when {
                kidKinds.size !in 2..3 -> "if-let takes [scrutinee, then-Block, else-Block?] (found ${kidKinds.size})"
                kid(1) != Kind.Block -> "if-let kid[1] must be then-Block, found ${kid(1)}"
                kidKinds.size == 3 && kid(2) != Kind.Block -> "if-let kid[2] (else) must be Block, found ${kid(2)}"
                else -> null
            }

            Kind.While -> when {
                kidKinds.size != 2 -> "while takes [cond, Block] (found ${kidKinds.size}——循环体语句须挂 Block 内)"
                kid(1) != Kind.Block -> "while kid[1] must be the loop Block, found ${kid(1)}"
                else -> null
            }

            Kind.For -> when {
                kidKinds.size != 2 -> "for takes [iterable, Block] (found ${kidKinds.size})"
                kid(1) != Kind.Block -> "for kid[1] must be the loop Block, found ${kid(1)}"
                else -> null
            }

            Kind.Match -> when {
                kidKinds.size < 2 -> "match takes scrutinee + at least one arm"
                kidKinds.drop(1).any { it != Kind.MatchArm } -> "match arms must be MatchArm kids"
                else -> null
            }

            Kind.MatchArm -> when {
                kidKinds.size != 1 -> "match arm takes exactly one body kid (Block or expression; found ${kidKinds.size})"
                kid(0) !in EXPR_KINDS && kid(0) != Kind.Block ->
                    "match arm body must be Block or expression, found ${kid(0)}（Return 等语句须包在 Block 内）"
                else -> null
            }

            Kind.Return -> if (kidKinds.size > 1) "return takes at most one expression kid" else null

            Kind.ExprStmt -> if (kidKinds.size != 1) "expr-statement takes exactly one expression kid (found ${kidKinds.size})" else null

            Kind.Call -> when {
                kidKinds.isEmpty() -> "call takes a callee kid"
                kidKinds.drop(1).any { it != Kind.Arg } -> "call args must be Arg kids"
                else -> null
            }

            Kind.Arg -> if (kidKinds.size != 1) "argument takes exactly one expression kid (found ${kidKinds.size})" else null

            Kind.Binary -> if (kidKinds.size != 2) "binary op takes lhs + rhs kids (found ${kidKinds.size})" else null

            Kind.Unary, Kind.Join, Kind.FieldAccess, Kind.Spawn ->
                if (kidKinds.size != 1) "$kind takes exactly one kid (found ${kidKinds.size})" else null

            Kind.Index -> if (kidKinds.size != 2) "index takes target + subscript kids (found ${kidKinds.size})" else null

            Kind.MapEntry -> if (kidKinds.size != 2) "map entry takes key + value kids (found ${kidKinds.size})" else null

            Kind.Literal -> when (litKind) {
                "array" -> kidKinds.filter { it !in EXPR_KINDS }
                    .firstOrNull()?.let { "array literal elements must be expressions, found $it" }
                "map" -> kidKinds.filter { it != Kind.MapEntry }
                    .firstOrNull()?.let { "map literal entries must be MapEntry kids, found $it" }
                else -> if (kidKinds.isNotEmpty()) "scalar literal takes no kids (found ${kidKinds.size})" else null
            }
        }
    }

    private infix fun Int.exactly(n: Int): Int? = if (this != n) this else null

    private infix fun Int.atMost(n: Int): Int? = if (this > n) this else null

    /**
     * 全表结构检查（含游离节点）：返回 (节点, 违例描述) 列表，空 = 通过。
     * kids 形态（[problemOf]）与字段类型/必填（[FieldSchema.problemsOf]）一起查——
     * 后者兜住「Literal.value 存成 Int」「Path 缺 written」这类写入错位，
     * 在名字解析/codegen 之前就点名到具体节点，而不是等到 codegen 以
     * E_INTERNAL 报在模块根上。
     */
    fun check(table: NodeTable): List<Pair<NodeId, String>> {
        val problems = ArrayList<Pair<NodeId, String>>()
        for (node in table.ids) {
            val n = table[node]
            val litKind = (n.fields["lit_kind"] as? FieldValue.Str)?.value
            problemOf(n.kind, n.kids.map { table[it].kind }, litKind)?.let { problems += node to it }
            FieldSchema.problemsOf(n.kind, n.fields).forEach { problems += node to it }
        }
        return problems
    }

    /** kind → kids 形态说明（list_ast_kinds 工具 / 文档共用；顺序即 Kind 声明序）。 */
    val KIND_SPEC: List<Triple<Kind, String, String>> = Kind.entries.map { k ->
        Triple(
            k,
            when (k) {
                Kind.Module -> "Depends*、声明*（Function/Struct/Interface/Impl/Enum/Const/Test/ExternalClass）"
                Kind.Depends, Kind.DocComment, Kind.Param, Kind.TypeParam, Kind.Path,
                Kind.Break, Kind.Continue,
                -> "—"
                Kind.Function -> "DocComment?、TypeParam*、Param*、Block?（本模块声明必有函数体；依赖接口 AST 为无体签名）"
                Kind.Struct -> "DocComment?、TypeParam*、Field*"
                Kind.Field -> "DocComment?"
                Kind.Interface -> "DocComment?、TypeParam*、MethodSig*"
                Kind.MethodSig -> "DocComment?、TypeParam*、Param*"
                Kind.Impl -> "DocComment?、Function*"
                Kind.Enum -> "DocComment?、TypeParam*、Variant*"
                Kind.Variant -> "DocComment?"
                Kind.Const -> "DocComment?、值表达式"
                Kind.Test -> "DocComment?、Block?（测试体）"
                Kind.ExternalClass -> "DocComment?、ExternalMethod*"
                Kind.ExternalMethod -> "DocComment?、Param*"
                Kind.Block -> "语句*（末位 ExprStmt 即块值）"
                Kind.Let -> "初始化表达式（恰一个）"
                Kind.Assign -> "目标（Path/FieldAccess/Index）、值表达式"
                Kind.If -> "条件、then-Block、(else-Block 或 else-If)? —— else 只认第 3 槽，多出的 kid 不会编译"
                Kind.IfLet -> "被检表达式、then-Block、else-Block?"
                Kind.While -> "条件、Block（恰两个——循环语句必须挂 Block 里，不能作 While 直接 kid）"
                Kind.For -> "迭代表达式、Block"
                Kind.Match -> "被检表达式、MatchArm+"
                Kind.MatchArm -> "体（Block 或表达式；恰一个——Return 等语句须包在 Block 里）"
                Kind.Return -> "表达式?（缺省 = Unit）"
                Kind.ExprStmt -> "表达式（恰一个）"
                Kind.Call -> "被调 Path、Arg*"
                Kind.Arg -> "实参表达式（恰一个；具名实参加 fields.name）"
                Kind.Binary -> "lhs、rhs（恰两个）"
                Kind.Unary, Kind.Join, Kind.FieldAccess, Kind.Spawn -> "恰一个 kid（Spawn 的 kid 是 Call）"
                Kind.Index -> "目标、下标（恰两个）"
                Kind.MapEntry -> "key、value"
                Kind.Literal -> "标量无 kid；array: 元素*；map: MapEntry*"
            },
            categoryOf(k),
        )
    }

    private fun categoryOf(k: Kind): String = when (k) {
        Kind.Module, Kind.Depends, Kind.Function, Kind.Param, Kind.TypeParam, Kind.Struct,
        Kind.Field, Kind.Interface, Kind.MethodSig, Kind.Impl, Kind.Enum, Kind.Variant,
        Kind.Const, Kind.Test, Kind.DocComment, Kind.ExternalClass, Kind.ExternalMethod,
        -> "declaration"
        Kind.Block, Kind.Let, Kind.Assign, Kind.If, Kind.IfLet, Kind.While, Kind.For,
        Kind.Match, Kind.MatchArm, Kind.Return, Kind.Break, Kind.Continue, Kind.ExprStmt,
        -> "statement"
        Kind.Call, Kind.Arg, Kind.Binary, Kind.Unary, Kind.Literal, Kind.MapEntry,
        Kind.Path, Kind.FieldAccess, Kind.Index, Kind.Spawn, Kind.Join,
        -> "expression"
    }
}
