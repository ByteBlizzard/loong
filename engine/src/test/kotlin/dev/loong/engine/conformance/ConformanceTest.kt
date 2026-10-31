package dev.loong.engine.conformance

import dev.loong.engine.LoongCompiler
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Conformance 套件（plan.md M1 第 9 步）：
 * 每个程序走完整流水线（解析→名字→类型→规则→字节码），
 * class 写入临时目录后以子进程 `java` 运行，stdout 与期望比较（行尾统一为 LF）。
 */
class ConformanceTest {

    private val programs = listOf(
        "fac", "arith", "fib", "structs", "enums", "optres", "coll", "spawn", "ext", "consts",
    )

    @Test
    fun `all conformance programs compile and run correctly`() {
        val failures = ArrayList<String>()
        for (name in programs) {
            val result = runAndCompare(name)
            if (result != null) failures += result
        }
        assertTrue(failures.isEmpty(), "conformance failures:\n" + failures.joinToString("\n"))
    }

    /** 返回 null = 通过；否则返回失败描述。 */
    private fun runAndCompare(name: String): String? {
        val src = resource("$name.loong").replace("\r\n", "\n")
        val expected = resource("$name.out").replace("\r\n", "\n")
        val compiled = LoongCompiler.compile(src)
        if (compiled.diagnostics.isNotEmpty()) {
            return "$name: unexpected diagnostics: " +
                compiled.diagnostics.joinToString { "${it.code}: ${it.message}" }
        }
        if (compiled.classes.isEmpty()) return "$name: no classes generated"

        val dir = Files.createTempDirectory("loong-conf-$name")
        compiled.classes.forEach { (internal, bytes) ->
            val file = dir.resolve(internal + ".class")
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        val cp = dir.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val javaBin = Paths.get(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val proc = ProcessBuilder(javaBin.toString(), "-cp", cp, compiled.hostClassName)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        val code = proc.waitFor()
        if (code != 0) return "$name: exited with $code:\n$output"
        if (output != expected) {
            return "$name: output mismatch:\n--- expected ---\n$expected--- actual ---\n$output"
        }
        return null
    }

    private fun resource(path: String): String =
        javaClass.getResourceAsStream("/conformance/$path")?.readBytes()?.decodeToString()
            ?: error("missing conformance resource: $path")
}
