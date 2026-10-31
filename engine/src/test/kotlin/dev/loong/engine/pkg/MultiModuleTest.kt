package dev.loong.engine.pkg

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** M4 验收：注册表发布 → MVS 解析 → 跨模块链接编译 → java 子进程运行。 */
class MultiModuleTest {

    private val libV15 = """
        module a.math

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            return x + y
        }

        /// Multiplies.
        public function mul(x: Int, y: Int) -> Int {
            return x * y
        }

        /// A point.
        public struct Point {
            /// x
            x: Int
            /// y
            y: Int
        }

        test "add" target: a.math.add {
            let r: Int = add(x: 1, y: 2)
        }

        test "mul" target: a.math.mul {
            let r: Int = mul(x: 2, y: 3)
        }
    """.trimIndent()

    private val libV10 = """
        module a.math

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            return x + y
        }

        /// A point.
        public struct Point {
            /// x
            x: Int
            /// y
            y: Int
        }

        test "add" target: a.math.add {
            let r: Int = add(x: 1, y: 2)
        }
    """.trimIndent()

    private fun consumer(mathConstraint: String, useMul: Boolean) = """
        module b.app

        depends a.math/v1 $mathConstraint

        /// Entry.
        function main() -> Unit {
            let sum: Int = a.math.add(x: 20, y: 22)
            let extra: Int = ${if (useMul) "a.math.mul(x: 2, y: 3)" else "0"}
            println(str(sum + extra))
        }
    """.trimIndent()

    @Test
    fun `publish mvs link compile and run`() {
        val registry = Files.createTempDirectory("loong-reg")

        // 发布 1.0.0 与 1.5.0
        for ((src, ver) in listOf(libV10 to "1.0.0", libV15 to "1.5.0")) {
            val ws = Workspace()
            ws.load(src)
            assertTrue(ws.module("a.math/v1").diagnostics.isEmpty(), ws.module("a.math/v1").diagnostics.joinToString { it.message })
            Packages.publish(registry, ws.tableOf("a.math/v1"), ws.rootOf("a.math/v1"), ver, ws.module("a.math/v1").classes)
        }

        // MVS：声明 >=1.4.0 → 选最小满足版本 1.5.0（1.0.0 缺 mul 不可用）
        val consumer = consumer(">= 1.4.0", useMul = true)
        val ws = Workspace()
        ws.load(consumer)
        ws.linkModules(LocalRegistry(registry))
        val m = ws.module("b.app/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        // 运行：消费方字节码 + 注册表依赖 classes + 运行时
        val out = Files.createTempDirectory("loong-out")
        m.classes.forEach { (internal, bytes) ->
            val f = out.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = listOf(out.toString()) + ws.classpathDirs("b.app/v1").map { it.toString() } +
            System.getProperty("java.class.path")
        val javaBin = System.getProperty("java.home") + "/bin/java.exe"
        val proc = ProcessBuilder(javaBin, "-cp", cp.joinToString(java.io.File.pathSeparator), "b.app")
            .redirectErrorStream(true).start()
        val output = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), output)
        assertEquals("48\n", output) // 20+22 + 2*3

        // 幻觉依赖：不存在的版本约束 → E_UNKNOWN_MODULE
        val bad = Workspace()
        bad.load(consumer(">= 9.0.0", useMul = false))
        bad.linkModules(LocalRegistry(registry))
        assertTrue(bad.module("b.app/v1").diagnostics.any { it.code == "E_UNKNOWN_MODULE" })
    }

    @Test
    fun `interface ast strips bodies and non-public decls`() {
        val ws = Workspace()
        ws.load(libV15)
        val text = Packages.interfaceAstText(ws.tableOf("a.math/v1"), ws.rootOf("a.math/v1"))
        assertTrue("\"kind\":\"Function\"" in text)
        assertTrue("\"kind\":\"Block\"" !in text, text.take(400))
        assertTrue("\"kind\":\"Test\"" !in text)
    }
}
