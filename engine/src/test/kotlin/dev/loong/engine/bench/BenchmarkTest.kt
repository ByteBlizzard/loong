package dev.loong.engine.bench

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 性能基准：Loong 生成代码 vs 手写 Java 基线（同 JVM 进程模型）。
 * 三类代表路径：整数算术（纯原始）、装箱数组求和（Object[]/Long[]）、枚举 match（Variant vs enum switch）。
 */
class BenchmarkTest {

    private fun runCmd(cmd: List<String>, cwd: Path? = null): String {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).apply { cwd?.let { directory(it.toFile()) } }.start()
        val out = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), out)
        return out
    }

    @Test
    fun `loong vs java baseline`() {
        val dir = Files.createTempDirectory("loong-bench")
        // Loong 侧
        val src = Path.of("src/test/resources/bench/Bench.loong").toFile().readText().replace("\r\n", "\n")
        val ws = Workspace()
        ws.load(src)
        val m = ws.module("demo.bench/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { it.message })
        m.classes.forEach { (internal, bytes) ->
            val f = dir.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = dir.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val javaBin = System.getProperty("java.home") + "/bin/java.exe"
        val javac = System.getProperty("java.home") + "/bin/javac.exe"

        // Java 侧基线
        Files.copy(Path.of("src/test/resources/bench/Bench.java"), dir.resolve("Bench.java"))
        runCmd(listOf(javac, "Bench.java"), dir)

        // 预热各一次（JIT）后正式测量
        runCmd(listOf(javaBin, "-cp", cp, "demo.bench"))
        val loongOut = runCmd(listOf(javaBin, "-cp", cp, "demo.bench"))
        runCmd(listOf(javaBin, "-cp", dir.toString(), "Bench"))
        val javaOut = runCmd(listOf(javaBin, "-cp", dir.toString(), "Bench"))

        val loong = loongOut.lines().filter { it.contains(" ") }
            .associate { it.substringBefore(" ") to it.substringAfter(" ").toLong() }
        val java = javaOut.lines().filter { it.contains(" ") }
            .associate { it.substringBefore(" ") to it.substringAfter(" ").toLong() }

        println("== 性能基准（ns，同机同 JVM）==")
        println("Loong arith     : ${loong["arith"]}   Java arith     : ${java["arith"]}")
        println("Loong arrSum    : ${loong["arrSum"]}   Java arrSumBox : ${java["arrSumBox"]}   Java arrSumPrim : ${java["arrSumPrim"]}")
        println("Loong match     : ${loong["match"]}   Java matchEnum : ${java["matchEnum"]}")
        val ratioA = loong.getValue("arith").toDouble() / java.getValue("arith")
        val ratioB = loong.getValue("arrSum").toDouble() / java.getValue("arrSumBox")
        val ratioC = loong.getValue("match").toDouble() / java.getValue("matchEnum")
        println(String.format("比值：arith=%.2fx  arrSum(vs boxed)=%.2fx  match=%.2fx", ratioA, ratioB, ratioC))
        // 宽松合理性断言：生成代码不应比解释执行还慢三个数量级
        assertTrue(ratioA < 50, "arith ratio $ratioA")
        assertTrue(ratioC < 100, "match ratio $ratioC")
    }
}
