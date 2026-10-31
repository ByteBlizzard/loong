package dev.loong.engine.conformance

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Int32 互操作：JDK int 宽度方法（ArrayList.size/get、String.length）经显式转换对接。 */
class Int32InteropTest {

    private val source = """
        module demo.i32

        external java "java.util.ArrayList" {
            constructor function new() -> ArrayList
            instance function add(self: ArrayList, x: Object) -> Bool
            instance function size(self: ArrayList) -> Int32
            instance function get(self: ArrayList, i: Int32) -> Object
        }

        external java "java.lang.String" {
            instance function length(self: String) -> Int32
        }

        external java "java.util.Objects" {
            static function toString(o: Object) -> String
        }

        /// Entry.
        function main() -> Unit {
            let l: ArrayList = ArrayList()
            add(self: l, x: 41)
            add(self: l, x: "x")
            let n: Int = to64(size(self: l))
            println(str(n))
            let v: Object = get(self: l, i: to32(0))
            println(Objects.toString(v))
            println(str(to64(length(self: "hello"))))
        }
    """.trimIndent()

    @Test
    fun `int width jdk methods via Int32 and explicit conversions`() {
        val ws = Workspace()
        ws.load(source)
        val m = ws.module("demo.i32/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        val dir = Files.createTempDirectory("loong-i32")
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
        // size()I→2；get(I)→41；length()I→5
        assertEquals("2\n41\n5\n", out)
    }
}
