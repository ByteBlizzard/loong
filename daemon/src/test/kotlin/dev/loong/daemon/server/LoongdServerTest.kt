package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** loongd 集成测试：HTTP API + 编辑重算 + 测试运行 + SSE 广播 + 崩溃恢复。 */
class LoongdServerTest {

    private val source = """
        module demo.daemon

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            return x + y
        }

        test "add" target: demo.daemon.add {
            let r: Int = add(x: 1, y: 2)
        }
    """.trimIndent()

    private fun client() = HttpClient.newHttpClient()

    private fun post(c: HttpClient, port: Int, path: String, body: String): Pair<Int, String> {
        val resp = c.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return resp.statusCode() to resp.body()
    }

    private fun get(c: HttpClient, port: Int, path: String): Pair<Int, String> {
        val resp = c.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return resp.statusCode() to resp.body()
    }

    @Test
    fun `full daemon loop load edit test sse and recovery`() {
        val dbDir = Files.createTempDirectory("loong-daemon")
        val db = dbDir.resolve("ws.db")

        val server = LoongdServer(0, db)
        val port = server.port()
        val c = client()

        // 健康检查与装载
        assertEquals(200, get(c, port, "/health").first)
        val (s1, b1) = post(c, port, "/modules", """{"source":${jsonStr(source)}}""")
        assertEquals(200, s1, b1)
        assertTrue("\"module\":\"demo.daemon/v1\"" in b1, b1)
        assertTrue("\"diagnostics\":[]" in b1, b1)

        // 渲染视图
        val (_, rendered) = get(c, port, "/modules/demo.daemon%2Fv1/render")
        assertTrue("return x + y" in rendered, rendered)

        // SSE 订阅（先连接，等一拍再触发变更）
        val gotMutation = CountDownLatch(1)
        val sseBody = StringBuilder()
        val sseThread = Thread {
            runCatching {
                c.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/events")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                ).body().use { ins ->
                    val buf = ByteArray(1024)
                    while (gotMutation.count == 1L) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        sseBody.append(String(buf, 0, n, Charsets.UTF_8))
                        if ("\"type\":\"mutation\"" in sseBody) {
                            gotMutation.countDown()
                            break
                        }
                    }
                }
            }
        }.apply { isDaemon = true }
        sseThread.start()
        Thread.sleep(300) // 等订阅建立

        // 编辑：向模块插入一个缺 doc 的函数 → 诊断应自动返回（E_MISSING_DOC + E_NO_TEST）
        val (s2, b2) = post(
            c, port, "/modules/demo.daemon%2Fv1/edit",
            """{"op":"insert","parent":1,"index":2,"kind":"Function","fields":{"name":"g","visibility":"public","return_type":"Int"}}""",
        )
        assertEquals(200, s2, b2)
        assertTrue("\"revision\":2" in b2, b2)
        assertTrue("E_MISSING_DOC" in b2, b2)
        assertTrue("E_NO_TEST" in b2, b2)

        // SSE 收到变更事件
        assertTrue(gotMutation.await(5, TimeUnit.SECONDS), "no SSE mutation event; got: $sseBody")
        assertTrue("\"module\":\"demo.daemon/v1\"" in sseBody.toString(), sseBody.toString())

        // 模块有错误时测试运行返回 409
        assertEquals(409, post(c, port, "/modules/demo.daemon%2Fv1/test", "{}").first)

        // 重新装载干净版本 → 测试可运行且通过
        post(c, port, "/modules", """{"source":${jsonStr(source)}}""")
        val (s4, b4) = post(c, port, "/modules/demo.daemon%2Fv1/test", "{}")
        assertEquals(200, s4, b4)
        assertTrue("\"passed\":1" in b4, b4)

        // 节点查询
        val (s6, b6) = get(c, port, "/modules/demo.daemon%2Fv1/node/1")
        assertEquals(200, s6)
        assertTrue("\"kind\":\"Module\"" in b6, b6)

        server.close()

        // 崩溃恢复：新进程同一数据库
        val server2 = LoongdServer(0, db)
        val port2 = server2.port()
        val (s5, b5) = get(client(), port2, "/modules")
        assertEquals(200, s5)
        assertTrue("demo.daemon/v1" in b5, b5)
        val (_, render2) = get(client(), port2, "/modules/demo.daemon%2Fv1/render")
        assertTrue("function add" in render2, render2)
        server2.close()
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
