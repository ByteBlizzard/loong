package dev.loong.engine.conformance

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 迭代协议（显式状态机）：impl Iterable for X 提供 start/next，for-in 降级为状态循环。 */
class IterateTest {

    private val source = """
        module demo.iter

        /// Step of a range iteration: yielded element plus next state.
        struct IterStep {
            /// yielded element
            elem: Int
            /// next state
            state: Int
        }

        /// A half-open numeric range [lo, hi).
        struct Range {
            /// lower bound
            lo: Int
            /// upper bound
            hi: Int
        }

        impl Iterable<Int> for Range {
            /// Initial state of the iteration.
            function start(self: Range) -> Int {
                return self.lo
            }

            /// Advances the state machine: None ends, Some(step) yields and continues.
            function next(self: Range, state: Int) -> Option<IterStep> {
                if state < self.hi {
                    return Option.Some(IterStep(elem: state, state: state + 1))
                }
                return Option.None
            }
        }

        /// Entry.
        function main() -> Unit {
            let r: Range = Range(lo: 1, hi: 5)
            var sum: Int = 0
            for v: Int in r {
                sum = sum + v
            }
            println(str(sum))
            let early: Range = Range(lo: 10, hi: 20)
            var touched: Int = 0
            for v: Int in early {
                touched = touched + 1
                if touched >= 3 {
                    break
                }
            }
            println(str(touched))
        }
    """.trimIndent()

    @Test
    fun `user-defined iteration via explicit state machine`() {
        val ws = Workspace()
        ws.load(source)
        val m = ws.module("demo.iter/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        val dir = Files.createTempDirectory("loong-iter")
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
        // 1+2+3+4=10；break 在第 3 次迭代生效
        assertEquals("10\n3\n", out)
    }
}
