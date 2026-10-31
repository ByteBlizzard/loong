package dev.loong.daemon.storage

import dev.loong.engine.storage.StoragePort
import dev.loong.engine.storage.WorkspaceSnapshot
import java.nio.file.Path
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore

/**
 * H2/MVStore 持久化实现（ADR-0003）：
 * - modules 映射：模块名 → .loongast 规范文本；
 * - meta 映射：ID 水位；
 * - 每次保存整体提交（MVStore 事务原子性）；备份走官方 API，不得复制运行中的数据文件。
 */
class H2Storage(path: Path) : StoragePort {

    private val store: MVStore = MVStore.open(path.toString())
    private val modules: MVMap<String, String> = store.openMap("modules")
    private val meta: MVMap<String, Long> = store.openMap("meta")

    override fun save(snapshot: WorkspaceSnapshot) {
        modules.clear()
        snapshot.modules.forEach { (name, json) -> modules[name] = json }
        meta[WatermarkKey] = snapshot.watermark
        store.commit()
    }

    override fun load(): WorkspaceSnapshot? {
        val saved = HashMap<String, String>()
        modules.forEach { (k, v) -> saved[k] = v }
        if (saved.isEmpty()) return null
        return WorkspaceSnapshot(saved, meta[WatermarkKey] ?: 0L)
    }

    override fun close() {
        store.close()
    }

    companion object {
        private const val WatermarkKey = "watermark"
    }
}
