package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 版本历史：commit → 编辑 → diff → checkout 恢复。 */
class HistoryTest {

    @Test
    fun `commit diff checkout roundtrip`() {
        val db = Files.createTempDirectory("loong-h").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()
            val http = HttpClient.newHttpClient()

            fun post(path: String, body: String): Pair<Int, String> {
                val r = http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return r.statusCode() to r.body()
            }

            fun get(path: String): Pair<Int, String> {
                val r = http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return r.statusCode() to r.body()
            }

            val mod = "demo.hist%2Fv1"
            val v1 = """
                module demo.hist

                /// Adds.
                public function add(x: Int, y: Int) -> Int {
                    return x + y
                }

                test "t" target: demo.hist.add {
                    let r: Int = add(x: 1, y: 2)
                }
            """.trimIndent().replace("\"", "\\\"").replace("\n", "\\n")

            assertEquals(200, post("/modules", """{"source":"$v1"}""").first)
            assertEquals(200, post("/modules/$mod/commit", """{"message":"first"}""").first)

            // 改动：替换 return 表达式为 x * y
            val v2 = v1.replace("return x + y", "return x * y")
            assertEquals(200, post("/modules", """{"source":"$v2"}""").first)
            assertEquals(200, post("/modules/$mod/commit", """{"message":"second"}""").first)

            val (dc, diffBody) = get("/modules/$mod/diff/2")
            assertEquals(200, dc)
            assertTrue("- " in diffBody && "+ " in diffBody, diffBody)
            assertTrue("x * y" in diffBody, diffBody)

            val (lc, logBody) = get("/modules/$mod/log")
            assertEquals(200, lc)
            assertTrue("\"first\"" in logBody && "\"second\"" in logBody, logBody)

            // checkout 回第一版：渲染文本恢复 x + y
            assertEquals(200, post("/modules/$mod/checkout", """{"id":1}""").first)
            val (rc, render) = get("/modules/$mod/render")
            assertEquals(200, rc)
            assertTrue("return x + y" in render, render)
            assertTrue("x * y" !in render, render)
        }
    }
}
