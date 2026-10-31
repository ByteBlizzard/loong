package dev.loong.engine.rules

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity

/**
 * 语言强制规则检查器（design.md §2.3/§4.4）——Loong 的纪律层。
 * 规则由编译器强制，不靠 review 维持。
 *
 * M1 范围内可触发的规则：
 * - E_MISSING_DOC：Function（含 impl 函数）/Struct/Field/Interface/MethodSig 必须有非空 doc 注释；
 * - E_NO_TEST：模块级 public Function 必须被至少一个 Test.target_id 引用；
 * - E_TOO_LONG：函数体直接语句数超过阈值（design 定例 50）；
 * - E_CYCLE_DEPENDS：depends 图有环（单模块 M1 = 自依赖）；
 * - E_IMPL_SIGNATURE_MISMATCH：impl 函数与接口 MethodSig 完全一致（名义匹配）。
 *
 * E_PRIVATE_ACCESS / E_PUBLIC_NOT_IN_INTERFACE 依赖跨模块解析（M2+/M4 的接口 AST 加载），此处不检查。
 */
class RulesChecker(private val table: NodeTable) {

    companion object {
        const val MAX_BLOCK_STATEMENTS = 50
    }

    private val diags = ArrayList<Diagnostic>()

    fun check(root: NodeId): List<Diagnostic> {
        checkDocs()
        checkTests(root)
        checkLength()
        checkCycles(root)
        checkImplSignatures()
        return diags
    }

    // ---------- 强制 doc 注释 ----------

    private fun checkDocs() {
        table.find(Kind.Function).forEach { requireDoc(it) }
        table.find(Kind.Struct).forEach { st ->
            requireDoc(st)
            table[st].kids.filter { table[it].kind == Kind.Field }.forEach { requireDoc(it) }
        }
        table.find(Kind.Interface).forEach { itf ->
            requireDoc(itf)
            table[itf].kids.filter { table[it].kind == Kind.MethodSig }.forEach { requireDoc(it) }
        }
    }

    private fun requireDoc(decl: NodeId) {
        val doc = table[decl].kids.firstOrNull()?.takeIf { table[it].kind == Kind.DocComment }
        if (doc == null || fs(doc, "text").isBlank()) {
            diags += Diagnostic(
                decl, Severity.Error, DiagCodes.E_MISSING_DOC,
                "${table[decl].kind.name.lowercase()} '${fs(decl, "name")}' requires a non-empty doc comment (///)",
            )
        }
    }

    // ---------- 强制单元测试 ----------

    private fun checkTests(root: NodeId) {
        val tested = table.find(Kind.Test).mapNotNull { refOf(it, "target_id") }.toSet()
        for (fn in table[root].kids.filter { table[it].kind == Kind.Function }) {
            if (fs(fn, "visibility") == "public" && fn !in tested) {
                diags += Diagnostic(
                    fn, Severity.Error, DiagCodes.E_NO_TEST,
                    "public function '${fs(fn, "name")}' must be referenced by at least one test",
                )
            }
        }
    }

    // ---------- 函数长度限制 ----------

    private fun checkLength() {
        for (fn in table.find(Kind.Function)) {
            val body = table[fn].kids.firstOrNull { table[it].kind == Kind.Block } ?: continue
            val count = table[body].kids.size
            if (count > MAX_BLOCK_STATEMENTS) {
                diags += Diagnostic(
                    body, Severity.Error, DiagCodes.E_TOO_LONG,
                    "function '${fs(fn, "name")}' has $count statements (limit $MAX_BLOCK_STATEMENTS); extract functions",
                )
            }
        }
    }

    // ---------- 模块循环依赖 ----------

    private fun checkCycles(root: NodeId) {
        val ownName = fs(root, "name").substringBefore('/')
        for (dep in table[root].kids.filter { table[it].kind == Kind.Depends }) {
            if (fs(dep, "module_name").substringBefore('/') == ownName) {
                diags += Diagnostic(
                    dep, Severity.Error, DiagCodes.E_CYCLE_DEPENDS,
                    "module '$ownName' depends on itself",
                )
            }
        }
    }

    // ---------- impl 签名完全匹配 ----------

    private class P(val name: String, val type: String)

    private fun paramsOf(node: NodeId): List<P> =
        table[node].kids.filter { table[it].kind == Kind.Param }
            .map { P(fs(it, "name"), fs(it, "type")) }

    private fun checkImplSignatures() {
        val interfaces = table.find(Kind.Interface).associateBy { fs(it, "name") }
        for (impl in table.find(Kind.Impl)) {
            val itfName = fs(impl, "interface").substringBefore('<')
            val itf = interfaces[itfName] ?: continue // 内建接口（如 Iterable）或跨模块接口，M1 不比对
            // 接口接收者参数（首个类型参数）由 impl 的接口实参绑定，如 Shape<Circle> 的 S := Circle
            val receiverParam = table[itf].kids.firstOrNull { table[it].kind == Kind.TypeParam }
                ?.let { fs(it, "name") }
            val receiverArg = fs(impl, "interface")
                .takeIf { it.contains('<') }
                ?.substringAfter('<', "")?.removeSuffix(">")?.trim()
            val sigs = table[itf].kids.filter { table[it].kind == Kind.MethodSig }
                .associateBy { fs(it, "name") }
            for (fn in table[impl].kids.filter { table[it].kind == Kind.Function }) {
                val sig = sigs[fs(fn, "name")]
                if (sig == null) {
                    diags += Diagnostic(
                        fn, Severity.Error, DiagCodes.E_IMPL_SIGNATURE_MISMATCH,
                        "interface '$itfName' has no method '${fs(fn, "name")}'",
                    )
                    continue
                }
                fun bind(t: String): String {
                    if (receiverParam == null || receiverArg == null) return t
                    return Regex("\\b$receiverParam\\b").replace(t, receiverArg)
                }
                val fnParams = paramsOf(fn)
                val sigParams = paramsOf(sig)
                val mismatch = fnParams.size != sigParams.size ||
                    fnParams.zip(sigParams).any { (a, b) -> a.name != b.name || a.type != bind(b.type) } ||
                    fs(fn, "return_type") != bind(fs(sig, "return_type"))
                if (mismatch) {
                    diags += Diagnostic(
                        fn, Severity.Error, DiagCodes.E_IMPL_SIGNATURE_MISMATCH,
                        "impl of '$itfName.${fs(fn, "name")}' does not match the interface signature",
                    )
                }
            }
        }
    }

    // ---------- 工具 ----------

    private fun refOf(id: NodeId, key: String): NodeId? =
        (table[id].fields[key] as? FieldValue.Ref)?.id

    private fun fs(id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
