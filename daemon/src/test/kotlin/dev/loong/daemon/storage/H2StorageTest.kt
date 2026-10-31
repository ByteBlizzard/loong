package dev.loong.daemon.storage

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class H2StorageTest {

    private val source = """
        module demo.store

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            let sum: Int = x + y
            return sum
        }

        test "t" target: demo.store.add {
            let r: Int = add(x: 1, y: 2)
        }
    """.trimIndent()

    @Test
    fun `empty storage loads nothing`() {
        val dir = Files.createTempDirectory("loong-h2")
        H2Storage(dir.resolve("ws.db")).use { s ->
            assertNull(s.load())
        }
    }

    @Test
    fun `save and load roundtrips a workspace snapshot`() {
        val dir = Files.createTempDirectory("loong-h2")
        val ws = Workspace()
        ws.load(source)
        val watermarkBefore = ws.watermark()
        assertTrue(watermarkBefore > 0)

        val path = dir.resolve("ws.db")
        H2Storage(path).use { s ->
            s.save(dev.loong.engine.storage.WorkspaceSnapshot(ws.snapshotModules(), watermarkBefore))
        }

        // 恢复：新进程等价路径
        H2Storage(path).use { s ->
            val snapshot = s.load()!!
            assertEquals(watermarkBefore, snapshot.watermark)
            val restored = Workspace()
            restored.restore(snapshot.modules)
            assertEquals(listOf("demo.store/v1"), restored.moduleNames())
            val m = restored.module("demo.store/v1")
            assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { it.message })
            assertTrue(m.classes.isNotEmpty())

            // 水位语义（ADR-0004）：恢复后新节点 ID 严格大于历史水位
            val table = restored.tableOf("demo.store/v1")
            val fresh = table.create(
                dev.loong.engine.ast.Kind.Let,
                mapOf("name" to dev.loong.engine.ast.str("late")),
            )
            assertTrue(fresh.value > watermarkBefore, "fresh=$fresh wm=$watermarkBefore")
            assertEquals(fresh.value, table.watermark)
        }
    }
}
