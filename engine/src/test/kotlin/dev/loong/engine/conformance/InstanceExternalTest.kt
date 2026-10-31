package dev.loong.engine.conformance

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** JVM 后端深化 ①：实例方法与构造器外部调用（StringBuilder 链式操作，真实 Java 对象）。 */
class InstanceExternalTest {

    private val source = """
        module demo.sbx

        external java "java.lang.StringBuilder" {
            constructor function new() -> StringBuilder
            instance function append(self: StringBuilder, s: String) -> StringBuilder
            instance function toString(self: StringBuilder) -> String
        }

        /// Entry.
        function main() -> Unit {
            let b: StringBuilder = StringBuilder()
            let b2: StringBuilder = append(self: b, s: "hello ")
            let b3: StringBuilder = append(self: b2, s: str(42))
            println(toString(self: b3))
            let c: StringBuilder = StringBuilder()
            append(self: c, s: "x")
            println(toString(self: c))
        }
    """.trimIndent()

    @Test
    fun `constructor and instance methods on real java objects`() {
        val ws = Workspace()
        ws.load(source)
        val m = ws.module("demo.sbx/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        val dir = Files.createTempDirectory("loong-sbx")
        m.classes.forEach { (internal, bytes) ->
            val f = dir.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = dir.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java.exe", "-cp", cp, m.hostClassName,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), out)
        assertEquals("hello 42\nx\n", out)
    }
}
