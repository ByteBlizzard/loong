package dev.loong.engine.ast

/**
 * AST 节点稳定 ID（ADR-0004）。
 *
 * u64；0 保留为 [NONE]，不指向任何节点；有效 ID 从 1 起工作区内单调递增，永不复用。
 */
@JvmInline
value class NodeId(val value: Long) : Comparable<NodeId> {

    override fun compareTo(other: NodeId): Int = value.compareTo(other.value)

    override fun toString(): String = "NodeId($value)"

    companion object {
        val NONE = NodeId(0L)
    }
}

/**
 * ID 分配器：节点表内唯一签发点（ADR-0004）。
 *
 * 水位随节点表落盘持久化；恢复时以 max(已存最大 ID, 水位) 之后的值继续，
 * 防止崩溃窗口内复用。
 */
class IdAllocator(startWatermark: Long = 0L) {

    init {
        require(startWatermark >= 0L) { "watermark must be >= 0: $startWatermark" }
    }

    private var next: Long = startWatermark + 1

    /** 已分配的最大 ID（含已删除节点）。 */
    val watermark: Long get() = next - 1

    fun allocate(): NodeId = NodeId(next++)
}
