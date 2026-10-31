package dev.loong.mcp

import dev.loong.daemon.server.LoongdServer
import dev.loong.engine.json.Json
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * M3 验收：design §8.6 九步循环的压缩版，全部经 MCP 工具面驱动（stdio JSON-RPC）：
 * 装载 → 插入函数骨架（响应自带 E_MISSING_DOC）→ 补 doc/参数/函数体 → 创建 test →
 * 诊断干净 → run_tests 通过 → request_human_review 挂起等待人类批准 → 继续。
 */
class McpLoopTest {

    private val source = """
        module demo.loop

        /// Doubles.
        function dbl(x: Int) -> Int {
            return x * 2
        }
    """.trimIndent()

    private fun quoted(s: String): String {
        val sb = StringBuilder()
        Json.writeString(sb, s)
        return sb.toString()
    }

    @Test
    fun `nine-step loop over mcp`() {
        val db = Files.createTempDirectory("loong-m3").resolve("ws.db")
        val server = LoongdServer(0, db)
        val port = server.port()
        val base = "http://127.0.0.1:$port"
        val http = HttpClient.newHttpClient()

        // 1. 装载模块（经 daemon HTTP，等价于人类/AI 的初始装载）
        val loadBody = """{"source":${quoted(source)}"""
        val loadResp = http.send(
            HttpRequest.newBuilder(URI("$base/modules"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("$loadBody}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, loadResp.statusCode(), loadResp.body())

        // MCP stdio 会话（单写者管道 + 单例读取器）
        val toServer = PipedOutputStream()
        val fromServer = PipedInputStream()
        val session = McpSession(
            PipedInputStream(toServer).bufferedReader(),
            PipedOutputStream(fromServer).writer(Charsets.UTF_8),
            base,
        )
        Thread { session.run() }.apply { isDaemon = true }.start()
        val writer = PrintWriter(toServer, true)
        val reader = fromServer.bufferedReader()
        var nextId = 1L

        fun send(json: String) {
            writer.println(json)
        }

        fun read(): Json.Obj = Json.parse(reader.readLine() ?: error("session closed")) as Json.Obj

        fun request(method: String, params: String = "{}"): Json.Obj {
            val id = nextId++
            send("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            return read()
        }

        fun callText(name: String, args: String): String {
            val resp = request("tools/call", """{"name":"$name","arguments":$args}""")
            val result = resp.map["result"] as Json.Obj
            val content = result.map["content"] as Json.Arr
            return ((content.items[0] as Json.Obj).map["text"] as Json.Str).v
        }

        fun insert(args: String): Long =
            Regex("\"value\":(\\d+)").find(callText("insert_node", args))!!
                .groupValues[1].toLong()

        // 2. 握手 + 工具面
        val init = request("initialize")
        val serverInfo = init.map["result"].let { it as Json.Obj }.map["serverInfo"] as Json.Obj
        assertEquals("loong-mcp", (serverInfo.map["name"] as Json.Str).v)
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val tools = request("tools/list").map["result"].let { it as Json.Obj }.map["tools"] as Json.Arr
        val toolNames = tools.items.map { ((it as Json.Obj).map["name"] as Json.Str).v }
        assertTrue("insert_node" in toolNames && "run_tests" in toolNames && "request_human_review" in toolNames)

        val m = "{\"module\":\"demo.loop/v1\""
        fun args(body: String) = "$m,$body}"

        // 3. 插入函数骨架（无 doc）→ 响应自带 E_MISSING_DOC
        val skeleton = callText(
            "insert_node",
            args("\"parent\":1,\"index\":1,\"kind\":\"Function\",\"fields\":{\"name\":\"inc\",\"visibility\":\"public\",\"return_type\":\"Int\"}"),
        )
        assertTrue("E_MISSING_DOC" in skeleton, skeleton)
        val fnId = Regex("\"value\":(\\d+)").find(skeleton)!!.groupValues[1].toLong()

        // 4. 补全函数：doc / param / block / return / 字面量
        insert(args("\"parent\":$fnId,\"index\":0,\"kind\":\"DocComment\",\"fields\":{\"text\":\"Returns seven.\"}"))
        insert(args("\"parent\":$fnId,\"index\":1,\"kind\":\"Param\",\"fields\":{\"name\":\"x\",\"type\":\"Int\"}"))
        val block = insert(args("\"parent\":$fnId,\"index\":2,\"kind\":\"Block\",\"fields\":{}"))
        val ret = insert(args("\"parent\":$block,\"index\":0,\"kind\":\"Return\",\"fields\":{}"))
        insert(args("\"parent\":$ret,\"index\":0,\"kind\":\"Literal\",\"fields\":{\"lit_kind\":\"int\",\"value\":\"7\"}"))

        // 此时唯一遗留诊断应为 public 无测试
        val midDiag = callText("get_diagnostics", "$m}")
        assertTrue("E_NO_TEST" in midDiag, midDiag)
        assertTrue("E_MISSING_DOC" !in midDiag, midDiag)

        // 5. 创建 test → 诊断干净
        val test = insert(args("\"parent\":1,\"index\":2,\"kind\":\"Test\",\"fields\":{\"name\":\"inc basic\",\"target_name\":\"demo.loop.inc\"}"))
        insert(args("\"parent\":$test,\"index\":0,\"kind\":\"Block\",\"fields\":{}"))
        val afterTest = callText("get_diagnostics", "$m}")
        assertTrue("E_NO_TEST" !in afterTest, afterTest)

        // 6. run_tests 通过
        val testsOut = callText("run_tests", "$m}")
        assertTrue("\"passed\":1" in testsOut, testsOut)

        // 7. request_human_review 挂起 → 人类经 daemon 批准
        Thread {
            runCatching {
                Thread.sleep(200)
                val pending = http.send(
                    HttpRequest.newBuilder(URI("$base/review/pending")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
                val id = Regex("\"id\":(\\d+)").find(pending)!!.groupValues[1]
                http.send(
                    HttpRequest.newBuilder(URI("$base/review/$id/respond"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"approved":true}""")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
        }.apply { isDaemon = true }.start()
        val review = callText(
            "request_human_review",
            args("\"reason\":\"ready to commit\",\"nodeIds\":[$fnId]"),
        )
        assertTrue("approved" in review, review)

        // 8. 继续：文本视图包含新函数；未排期工具显式报不可用
        val view = callText("get_module_interface", "$m}")
        assertTrue("function inc" in view, view)
        val commit = callText("commit", args("\"message\":\"approved state\""))
        assertTrue("\"commit\":1" in commit, commit)
        val log = callText("log", "$m}")
        assertTrue("approved state" in log, log)
        val diff = callText("diff", args("\"id\":1"))
        assertTrue("+ function dbl" in diff, diff)  // 首提交相对空基线的全量 + 行

        server.close()
    }
}
