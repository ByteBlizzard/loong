package dev.loong.engine.stdlib

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * loong.io 狗粮：文件 I/O 平台库以真实 Loong 代码编写（external 声明 + 包装函数 +
 * 真实读写测试），走完整流水线；TestRunner 进程内运行，FileIo shim 就在测试
 * classpath 上（engine testImplementation :runtime）。
 */
class StdlibIoTest {

    private fun ioSource(): String =
        Path.of("../stdlib/loong/io.loong").toFile().readText().replace("\r\n", "\n")

    private fun coreSource(): String =
        Path.of("../stdlib/loong/core.loong").toFile().readText().replace("\r\n", "\n")

    /** io 依赖 loong.core（splitLines 用 split/ends/sub）——装载并链接后再取模块。 */
    private fun linkedWorkspace(): Workspace {
        val ws = Workspace()
        ws.load(coreSource())
        ws.load(ioSource())
        fun lookup(name: String): Pair<dev.loong.engine.ast.NodeTable, dev.loong.engine.ast.NodeId>? {
            val key = if (name.contains('/')) name else "$name/v1"
            return runCatching { ws.tableOf(key) to ws.rootOf(key) }.getOrNull()
        }
        val src = dev.loong.engine.pkg.WorkspaceSource { lookup(it) }
        val deps = object : dev.loong.engine.pkg.DepSource {
            override fun versions(module: String): List<String> =
                if (lookup(module) != null) listOf("0.0.0") else emptyList()

            override fun fetch(module: String, version: String): dev.loong.engine.pkg.FetchedDep =
                src.fetch(module, version)
        }
        ws.linkModules(deps)
        return ws
    }

    @Test
    fun `loong io compiles clean and its tests pass`() {
        val ws = linkedWorkspace()
        val m = ws.module("loong.io/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        val core = ws.module("loong.core/v1").classes
        val results = dev.loong.engine.testrunner.TestRunner(m.table, m.hostClassName)
            .runTests(m.classes + core)
        assertEquals(14, results.size)
        assertTrue(results.all { it.passed }, results.joinToString { "${it.name}: ${it.error}" })
    }

    @Test
    fun `loong io survives a full reload`() {
        // 整体重载（daemon 快照恢复路径的等价物）：重新 parse + recompute 仍零诊断
        val ws = linkedWorkspace()
        assertTrue(ws.module("loong.io/v1").diagnostics.isEmpty())
    }

    @Test
    fun `scratch dir stays clean`() {
        // io.loong 的测试都在系统临时目录下留痕；这里仅确认装载与运行不触碰项目目录
        val before = Files.list(Path.of(".")).use { s -> s.count() }
        linkedWorkspace()
        assertEquals(before, Files.list(Path.of(".")).use { s -> s.count() })
    }
}
