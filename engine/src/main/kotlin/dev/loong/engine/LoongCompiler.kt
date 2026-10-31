package dev.loong.engine

import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.codegen.CodeGenerator
import dev.loong.engine.codegen.CodegenException
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity
import dev.loong.engine.resolve.Resolver
import dev.loong.engine.rules.RulesChecker
import dev.loong.engine.text.Parser
import dev.loong.engine.types.TypeChecker

/** 检查结果：节点表 + 根 + 全阶段聚合诊断（按阶段顺序排列）。 */
class CheckResult(
    val table: NodeTable,
    val root: NodeId,
    val diagnostics: List<Diagnostic>,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }
}

/** 编译结果：检查 + 生成的字节码（internal name → class 字节）。 */
class CompileResult(
    val table: NodeTable,
    val root: NodeId,
    val diagnostics: List<Diagnostic>,
    val classes: Map<String, ByteArray>,
    val hostClassName: String,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.Error }
}

private class FrontEnd(
    val table: NodeTable,
    val root: NodeId,
    val diagnostics: MutableList<Diagnostic>,
    val checker: TypeChecker,
)

/**
 * 编译器门面：单模块完整流水线。
 * 阶段顺序 = 诊断顺序：解析 → 名字解析 → 类型检查 → 强制规则 →（无错误时）字节码生成。
 * M2 的增量编译与 CLI 复用同一阶段组件。
 */
object LoongCompiler {

    fun check(source: String): CheckResult {
        val fe = frontEnd(source)
        return CheckResult(fe.table, fe.root, fe.diagnostics)
    }

    fun compile(source: String): CompileResult {
        val fe = frontEnd(source)
        var classes = emptyMap<String, ByteArray>()
        var hostClass = ""
        if (fe.diagnostics.none { it.severity == Severity.Error }) {
            try {
                val gen = CodeGenerator(fe.table, fe.checker)
                classes = gen.generate(fe.root)
                hostClass = gen.hostClassOf()
            } catch (e: CodegenException) {
                fe.diagnostics += Diagnostic(
                    fe.root, Severity.Error, "E_INTERNAL", "codegen failure: ${e.message}",
                )
            }
        }
        return CompileResult(fe.table, fe.root, fe.diagnostics, classes, hostClass)
    }

    private fun frontEnd(source: String): FrontEnd {
        val parsed = Parser(source).parse()
        val diags = parsed.diagnostics.toMutableList()
        diags += Resolver(parsed.table).resolve(parsed.root)
        val checker = TypeChecker(parsed.table)
        diags += checker.check(parsed.root)
        diags += RulesChecker(parsed.table).check(parsed.root)
        return FrontEnd(parsed.table, parsed.root, diags, checker)
    }
}
