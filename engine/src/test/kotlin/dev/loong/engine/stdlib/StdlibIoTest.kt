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

    @Test
    fun `loong io compiles clean and its tests pass`() {
        val ws = Workspace()
        ws.load(ioSource())
        val m = ws.module("loong.io/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        val results = dev.loong.engine.testrunner.TestRunner(m.table, m.hostClassName).runTests(m.classes)
        assertEquals(12, results.size)
        assertTrue(results.all { it.passed }, results.joinToString { "${it.name}: ${it.error}" })
    }

    @Test
    fun `loong io survives a full reload`() {
        // 整体重载（daemon 快照恢复路径的等价物）：重新 parse + recompute 仍零诊断
        val ws = Workspace()
        ws.load(ioSource())
        ws.load(ioSource())
        assertTrue(ws.module("loong.io/v1").diagnostics.isEmpty())
    }

    @Test
    fun `scratch dir stays clean`() {
        // io.loong 的测试都在系统临时目录下留痕；这里仅确认装载与运行不触碰项目目录
        val before = Files.list(Path.of(".")).use { s -> s.count() }
        val ws = Workspace()
        ws.load(ioSource())
        assertEquals(before, Files.list(Path.of(".")).use { s -> s.count() })
    }
}
