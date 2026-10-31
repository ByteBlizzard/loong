package dev.loong.engine.conformance

import dev.loong.engine.pkg.LocalRegistry
import dev.loong.engine.pkg.Packages
import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 跨模块 struct：字段访问/构造/泛型实例化/迭代协议 Step 来自依赖 loong.core。 */
class CrossModuleStructTest {

    @Test
    fun `cross module struct fields construction and iteration step`() {
        // 发布 loong.core（含 Step<T,S>）
        val lib = Workspace()
        lib.load(java.nio.file.Path.of("../stdlib/loong/core.loong").toFile().readText().replace("\r\n", "\n"))
        val libM = lib.module("loong.core/v1")
        assertTrue(libM.diagnostics.isEmpty(), libM.diagnostics.joinToString { it.message })
        val registry = Files.createTempDirectory("loong-x-reg")
        Packages.publish(registry, lib.tableOf("loong.core/v1"), lib.rootOf("loong.core/v1"), "1.0.0", libM.classes)

        // 消费方：Range 的迭代协议使用 loong.core.Step（跨模块泛型 struct）
        val consumer = """
            module demo.xiter

            depends loong.core/v1 >= 1.0.0

            /// A half-open numeric range.
            struct Range {
                /// lower bound
                lo: Int
                /// upper bound
                hi: Int
            }

            impl Iterable<Int> for Range {
                /// Initial state.
                function start(self: Range) -> Int {
                    return self.lo
                }

                /// Advances the state machine using loong.core.Step.
                function next(self: Range, state: Int) -> Option<loong.core.Step<Int, Int>> {
                    if state < self.hi {
                        return Option.Some(loong.core.Step(elem: state, state: state + 1))
                    }
                    return Option.None
                }
            }

            /// Entry.
            function main() -> Unit {
                let r: Range = Range(lo: 1, hi: 6)
                var sum: Int = 0
                for v: Int in r {
                    sum = sum + v
                }
                println(str(sum))
                let s: loong.core.Step<Int, Int> = loong.core.Step(elem: 41, state: 42)
                println(str(s.elem + s.state))
                var m: loong.core.Step<Int, Int> = loong.core.Step(elem: 5, state: 9)
                m.elem = 7
                println(str(m.elem + m.state))
            }
        """.trimIndent()

        val ws = Workspace()
        ws.load(consumer)
        ws.linkModules(LocalRegistry(registry))
        val m = ws.module("demo.xiter/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })

        val out = Files.createTempDirectory("loong-x-out")
        m.classes.forEach { (internal, bytes) ->
            val f = out.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = (listOf(out.toString()) + ws.classpathDirs("demo.xiter/v1").map { it.toString() } +
            listOf(System.getProperty("java.class.path"))).joinToString(java.io.File.pathSeparator)
        val proc = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java.exe", "-cp", cp, m.hostClassName,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), output)
        // 1+2+3+4+5=15；跨模块字段读 41+42=83；跨模块字段写后 7+9=16
        assertEquals("15\n83\n16\n", output)
    }
}
