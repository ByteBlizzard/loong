package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Loong Console 冒烟测试：daemon 分发 UI，页面与既有 API 对接的端点全部在位。 */
class ConsoleUiTest {

    @Test
    fun `console page is served with wired endpoints`() {
        val db = Files.createTempDirectory("loong-ui").resolve("ws.db")
        LoongdServer(0, db).use { server ->
            val port = server.port()
            val http = HttpClient.newHttpClient()

            val page = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, page.statusCode())
            assertEquals("text/html", page.headers().firstValue("Content-Type").get().substringBefore(';'))
            // 四个面板 + SSE + 审批操作的存在性
            assertTrue("Loong Console" in page.body())
            for (marker in listOf("/render", "/ast", "/build", "/diagnostics", "/test", "/deps", "/events", "/review/")) {
                assertTrue(marker in page.body(), "missing wiring for $marker")
            }

            // /ui 别名同样可用
            assertEquals(
                200,
                http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode(),
            )
        }
    }
}
