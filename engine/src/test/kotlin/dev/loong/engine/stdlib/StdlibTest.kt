package dev.loong.engine.stdlib

import dev.loong.engine.pkg.LocalRegistry
import dev.loong.engine.pkg.Packages
import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * loong.core 狗粮：标准库以真实 Loong 代码编写，走完整流水线后发布到注册表，
 * 消费方模块 depends loong.core 并在 java 子进程中运行。
 */
class StdlibTest {

    private fun coreSource(): String =
        Path.of("../stdlib/loong/core.loong").toFile().readText().replace("\r\n", "\n")

    @Test
    fun `loong core compiles clean and its tests pass`() {
        val ws = Workspace()
        ws.load(coreSource())
        val m = ws.module("loong.core/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        val results = dev.loong.engine.testrunner.TestRunner(m.table, m.hostClassName).runTests(m.classes)
        assertEquals(44, results.size)
        assertTrue(results.all { it.passed }, results.joinToString { "${it.name}: ${it.error}" })
    }

    @Test
    fun `consumer depends on published loong core and runs`() {
        // 发布 1.0.0
        val lib = Workspace()
        lib.load(coreSource())
        val libModule = lib.module("loong.core/v1")
        assertTrue(libModule.diagnostics.isEmpty(), libModule.diagnostics.joinToString { it.message })
        val registry = Files.createTempDirectory("loong-core-reg")
        Packages.publish(registry, lib.tableOf("loong.core/v1"), lib.rootOf("loong.core/v1"), "1.0.0", libModule.classes)

        // 消费方
        val consumer = """
            module demo.use

            depends loong.core/v1 >= 1.0.0

            /// Entry.
            function main() -> Unit {
                println(str(loong.core.clamp(a: 42, lo: 0, hi: 10)))
                println(str(loong.core.abs(-7)))
                println(concat(concat(str(loong.core.sign(-5)), str(loong.core.sign(0))), str(loong.core.sign(9))))
                println(str(loong.core.sqr(6)))
                println(str(loong.core.unwrapOr(Option.Some(5), 0)))
                println(str(loong.core.unwrapOr(Option.None, 9)))
                if loong.core.isSome(Option.Some(1)) {
                    println("some")
                } else {
                    println("none")
                }
                println(loong.core.unwrapOr(Option.Some("bo"), "x"))
                println(loong.core.unwrapOr(Option.None, "nx"))
                println(str(loong.core.unwrapOrOk(Result.Err("e"), 7)))
                let mk: Result<Int, String> = loong.core.mapOk(Result.Ok(3), twice)
                println(str(loong.core.unwrapOrOk(mk, 0)))
                let src: Array<Int> = [1, 2, 3, 4, 5]
                let evens: Array<Int> = loong.core.filter(src, isEven)
                println(str(len(evens)))
                let doubled: Array<Int> = loong.core.map(evens, twice)
                println(str(loong.core.fold(doubled, 0, addUp)))
                println(str(loong.core.sumInt([1, 2, 3, 4])))
                if loong.core.containsInt([5, 6], 6) {
                    println("yes")
                } else {
                    println("no")
                }
                if loong.core.fsqrt(9.0) == 3.0 {
                    println("sqrt-ok")
                } else {
                    println("sqrt-bad")
                }
                let mo: Option<Int> = loong.core.mapOpt(Option.Some(5), twice)
                println(str(loong.core.unwrapOr(mo, 0)))
                println(loong.core.sub("hello world", 0, 5))
                println(loong.core.upper("ab"))
                println(str(loong.core.find("hello", "ll")))
                if loong.core.at("hi", 0) == 'h' {
                    println("c-ok")
                } else {
                    println("c-bad")
                }
            }

            /// True for even integers.
            function isEven(x: Int) -> Bool {
                return x % 2 == 0
            }

            /// Doubles an integer.
            function twice(x: Int) -> Int {
                return x * 2
            }

            /// Adds two integers.
            function addUp(a: Int, b: Int) -> Int {
                return a + b
            }
        """.trimIndent()
        val ws = Workspace()
        ws.load(consumer)
        ws.linkModules(LocalRegistry(registry))
        val m = ws.module("demo.use/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })

        val out = Files.createTempDirectory("loong-core-out")
        m.classes.forEach { (internal, bytes) ->
            val f = out.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = (listOf(out.toString()) + ws.classpathDirs("demo.use/v1").map { it.toString() } +
            listOf(System.getProperty("java.class.path"))).joinToString(java.io.File.pathSeparator)
        val proc = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java.exe", "-cp", cp, m.hostClassName,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), output)
        assertEquals("10\n7\n-101\n36\n5\n9\nsome\nbo\nnx\n7\n6\n2\n12\n10\nyes\nsqrt-ok\n10\nhello\nAB\n2\nc-ok\n", output)
    }
}
