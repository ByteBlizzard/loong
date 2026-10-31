package dev.loong.engine.ast

/**
 * fields 的取值类型（ast-schema-v1.md §2）。类型标注类字段一律以
 * 规范类型文本（Str）表示，不引入独立类型节点。
 */
sealed class FieldValue {

    data class Str(val value: String) : FieldValue()

    data class Int(val value: Long) : FieldValue()

    data class Bool(val value: Boolean) : FieldValue()

    /** 指向另一节点的字段（如 Test.target_id、Path.resolved_id）。 */
    data class Ref(val id: NodeId) : FieldValue()
}
