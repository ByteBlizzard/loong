package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 空项目初始化：首启装载零诊断的 main/example；非空项目重启不重复装载。 */
class DefaultModulesTest {

    private val client = HttpClient.newHttpClient()

    @Test
    fun `fresh project is seeded with runnable main and example`() {
        val db = Files.createTempDirectory("loong-seed").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()

            // 两个缺省模块在位
            val (s, body) = get(port, "/modules")
            assertEquals(200, s)
            assertTrue("\"main/v1\"" in body, body)
            assertTrue("\"example/v1\"" in body, body)

            // 种子即零诊断（doc/测试等强制规则全部满足）
            val (_, d1) = get(port, "/modules/main%2Fv1/diagnostics")
            assertTrue("\"diagnostics\":[]" in d1, d1)
            val (_, d2) = get(port, "/modules/example%2Fv1/diagnostics")
            assertTrue("\"diagnostics\":[]" in d2, d2)

            // main：默认问候函数；example：能力样板的渲染标记
            val (_, render) = get(port, "/modules/main%2Fv1/render")
            assertTrue("println(\"Hello , loong!\")" in render, render)
            val (_, exRender) = get(port, "/modules/example%2Fv1/render")
            for (marker in listOf(
                "struct Point", "interface Shape", "impl Shape<Point> for Point",
                "enum Cmp", "impl Iterable<Int> for Range",
                "while i < 3", "for v: Int in r", "match o",
            )) {
                assertTrue(marker in exRender, "example render missing: $marker")
            }

            // example 的内联测试全部通过（覆盖类型检查与字节码生成）
            val (ts, tb) = post(port, "/modules/example%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":3" in tb, tb)
        }
    }

    @Test
    fun `existing project is not reseeded on restart`() {
        val db = Files.createTempDirectory("loong-reseed").resolve("ws.db")
        val source = """
            module demo.busy

            /// Adds.
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }

            test "add" target: demo.busy.add {
                let r: Int = add(x: 1, y: 2)
            }
        """.trimIndent()

        // 首启（空项目 → 种子）后加入用户模块
        LoongdServer(0, db).use { server ->
            val (s, _) = post(server.port(), "/modules", """{"source":${jsonStr(source)}}""")
            assertEquals(200, s)
        }

        // 同一数据库重启：恢复全部既有模块，缺省模块不重复注入、用户模块不丢失
        LoongdServer(0, db).use { server ->
            val (_, body) = get(server.port(), "/modules")
            val names = body.substringAfter('[').substringBefore(']')
                .split(',').map { it.trim(' ', '"') }.sorted()
            assertEquals(listOf("demo.busy/v1", "example/v1", "main/v1"), names, body)
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val resp = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return resp.statusCode() to resp.body()
    }

    private fun post(port: Int, path: String, body: String): Pair<Int, String> {
        val resp = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return resp.statusCode() to resp.body()
    }

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
