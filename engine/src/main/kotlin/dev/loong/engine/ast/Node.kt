package dev.loong.engine.ast

/**
 * 通用节点结构（design.md §4.2）。不可变值对象：编辑操作以替换条目的方式更新节点表。
 */
data class Node(
    val id: NodeId,
    val kind: Kind,
    val fields: Map<String, FieldValue> = emptyMap(),
    val kids: List<NodeId> = emptyList(),
    val parent: NodeId = NodeId.NONE,
)

/** 字段构造糖。 */
fun str(value: String): FieldValue.Str = FieldValue.Str(value)

fun int(value: Long): FieldValue.Int = FieldValue.Int(value)

fun bool(value: Boolean): FieldValue.Bool = FieldValue.Bool(value)

fun ref(id: NodeId): FieldValue.Ref = FieldValue.Ref(id)
