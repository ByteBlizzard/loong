package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 系统模块 + depends 接线的端到端验证：
 * - 系统模块（loong.core/io/net）启动即装载、零诊断、不可删除/覆盖；
 * - 用户模块 depends loong.io → 跨模块引用解析（此前 depends 在 daemon 是死路）；
 * - run_tests 带依赖闭包字节码（跨模块测试可跑）；
 * - build_project 产物自包含（loong/io 类 + runtime FileIo shim 进 jar）。
 */
class SystemModulesTest {

    private val client = HttpClient.newHttpClient()

    private val filesModule = """
        module demo.files

        depends loong.io

        /// Writes then reads back a scratch file.
        public function roundtrip(p: String) -> Bool {
            let w: Result<Unit, String> = loong.io.writeText(p, "ok")
            let r: Result<String, String> = loong.io.readText(p)
            match r {
                Result.Ok(v) => v == "ok"
                Result.Err(e) => false
            }
        }

        /// Entry point exercising the io roundtrip.
        function main() -> Unit {
            if roundtrip("build-roundtrip.txt") {
                println("io-ok")
            } else {
                println("io-bad")
            }
        }

        test "roundtrip" target: demo.files.roundtrip {
            let p: String = loong.io.joinPath(loong.io.tmpDir(), "loong-daemon-rt.txt")
            let r: Bool = roundtrip(p)
        }
    """.trimIndent()

    @Test
    fun `system modules load clean and are protected`() {
        val db = Files.createTempDirectory("loong-sys").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()

            // 在位 + 清单路由
            val (_, body) = get(port, "/modules")
            assertTrue("\"loong.core/v1\"" in body, body)
            assertTrue("\"loong.io/v1\"" in body, body)
            assertTrue("\"loong.net/v1\"" in body, body)
            val (_, sys) = get(port, "/system-modules")
            assertTrue("\"loong.io/v1\"" in sys, sys)

            // 零诊断
            for (m in listOf("loong.core", "loong.io", "loong.net")) {
                val (_, d) = get(port, "/modules/${m}%2Fv1/diagnostics")
                assertTrue("\"diagnostics\":[]" in d, "$m: $d")
            }

            // 不可删除
            assertEquals(409, delete(port, "/modules/loong.io%2Fv1").first)

            // 不可被 create_module 覆盖
            val (cs, cb) = post(port, "/modules", """{"source":"module loong.io\n"}""")
            assertEquals(400, cs, cb)
            assertTrue("built-in" in cb, cb)

            // 系统模块自身测试可跑（真实文件 IO）
            val (ts, tb) = post(port, "/modules/loong.io%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":14" in tb, tb)
        }
    }

    @Test
    fun `user module depends on loong io and runs its tests`() {
        val db = Files.createTempDirectory("loong-dep").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()

            // 装载即零诊断：loong.io.writeText 等跨模块引用经工作区依赖源解析
            val (s, b) = post(port, "/modules", """{"source":${jsonStr(filesModule)}}""")
            assertEquals(200, s, b)
            assertTrue("\"diagnostics\":[]" in b, b)

            // 跨模块测试：依赖闭包（loong.io 的宿主类）字节码并入测试 classpath
            val (ts, tb) = post(port, "/modules/demo.files%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":1" in tb, tb)
        }
    }

    @Test
    fun `build jar is self-contained with stdlib and shim`() {
        val db = Files.createTempDirectory("loong-build").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()
            val (s, b) = post(port, "/modules", """{"source":${jsonStr(filesModule)}}""")
            assertEquals(200, s, b)
            assertTrue("\"diagnostics\":[]" in b, b)

            val (bs, bb) = post(port, "/build", "{}")
            assertEquals(200, bs, bb)
            val jar = Path.of(bb.substringAfter("\"jar\":\"").substringBefore('"'))
            assertTrue(Files.exists(jar), bb)

            // 自包含：stdlib 宿主类 + runtime IO shim 都打进 jar
            ZipFile(jar.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue("loong/io.class" in names, "missing loong/io host class in $names")
                assertTrue("dev/loong/runtime/FileIo.class" in names, "missing FileIo shim in $names")
                assertTrue("demo/files.class" in names, "missing demo.files in $names")
            }

            // java -cp 跑 demo.files 模块入口：真实文件读写，输出 io-ok
            val javaBin = System.getProperty("java.home") + "/bin/" +
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
            val workdir = Files.createTempDirectory("loong-run")
            val proc = ProcessBuilder(javaBin, "-cp", jar.toString(), "demo.files")
                .directory(workdir.toFile())
                .redirectErrorStream(true).start()
            val out = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
            assertEquals(0, proc.waitFor(), out)
            assertEquals("io-ok\n", out)
        }
    }

    // ---------- HTTP 小件（与 DefaultModulesTest 同风格） ----------

    private fun get(port: Int, path: String): Pair<Int, String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { it.statusCode() to it.body() }

    private fun post(port: Int, path: String, body: String): Pair<Int, String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { it.statusCode() to it.body() }

    private fun delete(port: Int, path: String): Pair<Int, String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { it.statusCode() to it.body() }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
