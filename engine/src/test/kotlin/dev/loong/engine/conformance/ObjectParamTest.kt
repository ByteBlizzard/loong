package dev.loong.engine.conformance

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** JVM 深化 ⑤：擦除 Object 形参——ArrayList 混合元素 + Objects.toString 往返。 */
class ObjectParamTest {

    private val source = """
        module demo.obj

        external java "java.util.LinkedList" {
            constructor function new() -> LinkedList
            instance function offer(self: LinkedList, x: Object) -> Bool
            instance function poll(self: LinkedList) -> Object
            instance function isEmpty(self: LinkedList) -> Bool
        }

        external java "java.util.Objects" {
            static function toString(o: Object) -> String
            static function equals(a: Object, b: Object) -> Bool
        }

        /// Entry.
        function main() -> Unit {
            let q: LinkedList = LinkedList()
            offer(self: q, x: 41)
            offer(self: q, x: str(1))
            if isEmpty(self: q) {
                println("bad")
            } else {
                println("ok")
            }
            let first: Object = poll(self: q)
            println(Objects.toString(first))
            let second: Object = poll(self: q)
            println(Objects.toString(second))
            let eq: Bool = Objects.equals(a: first, b: first)
            println(str(if eq { 1 } else { 0 }))
        }
    """.trimIndent()

    @Test
    fun `erased object params accept mixed values`() {
        val ws = Workspace()
        ws.load(source)
        val m = ws.module("demo.obj/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        val dir = Files.createTempDirectory("loong-obj")
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
        // Object 形参收 Int（装箱）与 String（引用透传）；poll 回读经 toString；equals 比较
        assertEquals("ok\n41\n1\n1\n", out)
    }
}
