package dev.loong.engine.diagnostics

import dev.loong.engine.ast.NodeId

/** 诊断严重级。v1 全部强制规则为 Error；Warning 留给未来 lint。 */
enum class Severity { Error, Warning }

/**
 * 结构化诊断（design.md §4.5）。
 * 注册表：doc/spec/diagnostics-registry.md；code 只增不改义。
 */
data class Diagnostic(
    val nodeId: NodeId,
    val severity: Severity,
    val code: String,
    val message: String,
    val suggestedFix: String? = null,
)

/** 已注册诊断码（与注册表文档一一对应；按阶段逐步启用）。 */
object DiagCodes {
    // 词法 / 语法
    const val E_UNEXPECTED_TOKEN = "E_UNEXPECTED_TOKEN"
    const val E_UNEXPECTED_EOF = "E_UNEXPECTED_EOF"
    const val E_UNTERMINATED_STRING = "E_UNTERMINATED_STRING"
    const val E_INVALID_ESCAPE = "E_INVALID_ESCAPE"
    const val E_INVALID_NUMBER = "E_INVALID_NUMBER"
    const val E_MISSING_MODULE_DECL = "E_MISSING_MODULE_DECL"
    const val E_BAD_MODULE_NAME = "E_BAD_MODULE_NAME"

    // 名字解析
    const val E_UNKNOWN_NAME = "E_UNKNOWN_NAME"
    const val E_UNKNOWN_TYPE = "E_UNKNOWN_TYPE"
    const val E_AMBIGUOUS_NAME = "E_AMBIGUOUS_NAME"
    const val E_DUPLICATE_DECL = "E_DUPLICATE_DECL"
    const val E_UNKNOWN_MODULE = "E_UNKNOWN_MODULE"
    const val E_CYCLE_DEPENDS = "E_CYCLE_DEPENDS"
    const val E_UNRESOLVED_TARGET = "E_UNRESOLVED_TARGET"

    // 类型检查
    const val E_TYPE_MISMATCH = "E_TYPE_MISMATCH"
    const val E_ARITY_MISMATCH = "E_ARITY_MISMATCH"
    const val E_NAMED_ARG_UNKNOWN = "E_NAMED_ARG_UNKNOWN"
    const val E_BAD_OPERAND_TYPE = "E_BAD_OPERAND_TYPE"
    const val E_CALL_NOT_FUNCTION = "E_CALL_NOT_FUNCTION"
    const val E_NOT_EXHAUSTIVE = "E_NOT_EXHAUSTIVE"
    const val E_PATTERN_TYPE_MISMATCH = "E_PATTERN_TYPE_MISMATCH"
    const val E_NOT_ITERABLE = "E_NOT_ITERABLE"
    const val E_MISSING_TYPE_CONSTRAINT = "E_MISSING_TYPE_CONSTRAINT"
    const val E_CONSTRAINT_NOT_SATISFIED = "E_CONSTRAINT_NOT_SATISFIED"
    const val E_ASSIGN_IMMUTABLE = "E_ASSIGN_IMMUTABLE"
    const val E_SHARED_REQUIRES_MUTEX = "E_SHARED_REQUIRES_MUTEX"
    const val E_UNKNOWN_FIELD = "E_UNKNOWN_FIELD"

    // JVM 互操作
    const val E_EXTERNAL_CLASS_NOT_FOUND = "E_EXTERNAL_CLASS_NOT_FOUND"
    const val E_EXTERNAL_METHOD_NOT_FOUND = "E_EXTERNAL_METHOD_NOT_FOUND"
    const val E_EXTERNAL_DESCRIPTOR_MISMATCH = "E_EXTERNAL_DESCRIPTOR_MISMATCH"

    // 强制规则（design.md §2.3）
    const val E_MISSING_DOC = "E_MISSING_DOC"
    const val E_NO_TEST = "E_NO_TEST"
    const val E_TOO_LONG = "E_TOO_LONG"
    const val E_PRIVATE_ACCESS = "E_PRIVATE_ACCESS"
    const val E_IMPL_SIGNATURE_MISMATCH = "E_IMPL_SIGNATURE_MISMATCH"
    const val E_PUBLIC_NOT_IN_INTERFACE = "E_PUBLIC_NOT_IN_INTERFACE"
}
