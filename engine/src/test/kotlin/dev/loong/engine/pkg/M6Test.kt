package dev.loong.engine.pkg

import dev.loong.engine.workspace.Workspace
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** M6 验收：宿主类命名、真实 JDK 库调用、空值边界断言、供应链签名与拒绝。 */
class M6Test {

    private fun runMain(source: String, extraCp: List<String> = emptyList(), expectCode: Int = 0): Pair<Int, String> {
        val ws = Workspace()
        ws.load(source)
        val m = ws.module(ws.moduleNames().first())
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        val dir = Files.createTempDirectory("loong-m6")
        m.classes.forEach { (internal, bytes) ->
            val f = dir.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = (listOf(dir.toString()) + extraCp + System.getProperty("java.class.path"))
            .joinToString(java.io.File.pathSeparator)
        val proc = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java.exe", "-cp", cp, m.hostClassName,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        val code = proc.waitFor()
        if (expectCode == 0) assertEquals(0, code, out)
        return code to out
    }

    @Test
    fun `explicit host class naming`() {
        val (_, out) = runMain(
            """
            module demo.custom host: demo.custom.Host

            /// Entry.
            function main() -> Unit {
                println("from custom host")
            }
            """.trimIndent(),
        )
        assertEquals("from custom host\n", out)
    }

    @Test
    fun `real jdk library call and null boundary assertion`() {
        // 真实 JDK 库：Arrays.toString(long[])
        val (_, out) = runMain(
            """
            module demo.jdk

            external java "java.util.Arrays" {
                static function toString(a: Array<Int>) -> String
            }

            /// Entry.
            function main() -> Unit {
                println(Arrays.toString([1, 2, 3]))
            }
            """.trimIndent(),
        )
        assertEquals("[1, 2, 3]\n", out)

        // 空值边界断言：getenv 对不存在的键返回 null → 边界立即失败（design §6.4）
        val (code, out2) = runMain(
            """
            module demo.nullb

            external java "java.lang.System" {
                static function getenv(k: String) -> String
            }

            /// Entry.
            function main() -> Unit {
                println(System.getenv("LOONG_DEFINITELY_MISSING_KEY_9f3"))
            }
            """.trimIndent(),
            expectCode = -1,
        )
        assertTrue(code != 0, "expected nonzero exit, got $code: $out2")
        assertTrue("NullPointerException" in out2, out2)
    }

    @Test
    fun `signed publishing enforced on consume`() {
        val lib = """
            module a.sec

            /// Adds.
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }

            test "t" target: a.sec.add {
                let r: Int = add(x: 1, y: 2)
            }
        """.trimIndent()
        val ws = Workspace()
        ws.load(lib)
        val reg = Files.createTempDirectory("loong-reg6")
        val key = Signing.generate()

        // 签名发布 → 验签注册表可消费
        Packages.publish(reg, ws.tableOf("a.sec/v1"), ws.rootOf("a.sec/v1"), "1.0.0",
            ws.module("a.sec/v1").classes, signing = key.private)
        assertTrue(Files.exists(reg.resolve("a.sec.v1").resolve("1.0.0").resolve("sbom.json")))
        assertTrue(Files.exists(reg.resolve("a.sec.v1").resolve("1.0.0").resolve("provenance.json")))
        LocalRegistry(reg, key.public).fetch("a.sec/v1", "1.0.0")

        // 篡改 manifest → 验签失败
        val mfile = reg.resolve("a.sec.v1").resolve("1.0.0").resolve("manifest.json")
        Files.write(mfile, Files.readAllBytes(mfile).let { String(it).replace("1.0.0", "9.9.9").toByteArray() })
        val tampered = runCatching { LocalRegistry(reg, key.public).fetch("a.sec/v1", "1.0.0") }
        assertTrue(tampered.isFailure, "tampered manifest must be rejected")

        // 未签名发布物 → 验签注册表直接拒绝（M6 验收）
        Files.deleteIfExists(reg.resolve("a.sec.v1").resolve("1.0.0").resolve("manifest.sig"))
        Files.write(mfile, Files.readAllBytes(mfile).let { String(it).replace("9.9.9", "1.0.0").toByteArray() })
        val unsigned = runCatching { LocalRegistry(reg, key.public).fetch("a.sec/v1", "1.0.0") }
        assertTrue(unsigned.isFailure && unsigned.exceptionOrNull()!!.message!!.contains("unsigned"),
            unsigned.exceptionOrNull()?.message)
        // 不验签的注册表仍可拉取（渐进采用）
        assertTrue(runCatching { LocalRegistry(reg).fetch("a.sec/v1", "1.0.0") }.isSuccess)
    }
}
