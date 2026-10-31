package dev.loong.engine.storage

/**
 * 持久化端口（ADR-0003）：daemon 的全部落盘经此接口，engine 其余部分不得依赖具体存储。
 *
 * 快照内容 = 各模块的 `.loongast` 规范文本 + 工作区 ID 水位（ADR-0004）。
 * 保存应原子提交；加载在 daemon 启动恢复时调用一次。
 */
interface StoragePort : AutoCloseable {

    fun save(snapshot: WorkspaceSnapshot)

    fun load(): WorkspaceSnapshot?
}

/** 工作区快照：模块名 → loongast 文本；watermark = 全部模块已分配的最大节点 ID。 */
class WorkspaceSnapshot(
    val modules: Map<String, String>,
    val watermark: Long,
)
