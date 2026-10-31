package dev.loong.mcp

import dev.loong.daemon.server.LoongdServer
import dev.loong.engine.json.Json
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
 * 模块级管理工具面验收（agent 抱怨「没法创建模块」的补全）：
 * list_modules / create_module / delete_module + build_project，全部经 tools/call 驱动，
 * 并用 daemon HTTP 复核状态确实变化。
 */
class McpModuleToolsTest {

    private val http = HttpClient.newHttpClient()

    @Test
    fun `module lifecycle and build over mcp tools`() {
        val projectDir = Files.createTempDirectory("loong-mcp-mod")
        val server = LoongdServer(0, projectDir.resolve("workspace.db"))
        val base = "http://127.0.0.1:${server.port()}"

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

        fun request(method: String, params: String = "{}"): Json.Obj {
            val id = nextId++
            writer.println("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            return Json.parse(reader.readLine() ?: error("session closed")) as Json.Obj
        }

        fun callText(name: String, args: String = "{}"): String {
            val resp = request("tools/call", """{"name":"$name","arguments":$args}""")
            val result = resp.map["result"] as Json.Obj
            val content = result.map["content"] as Json.Arr
            return ((content.items[0] as Json.Obj).map["text"] as Json.Str).v
        }

        fun quoted(s: String): String {
            val sb = StringBuilder()
            Json.writeString(sb, s)
            return sb.toString()
        }

        // 工具面宣告：四个新工具在列，create_module 带 source schema
        request("initialize")
        writer.println("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val tools = request("tools/list").map["result"].let { it as Json.Obj }.map["tools"] as Json.Arr
        val names = tools.items.map { ((it as Json.Obj).map["name"] as Json.Str).v }
        assertTrue(
            names.containsAll(listOf("list_modules", "create_module", "delete_module", "build_project")),
            names.toString(),
        )
        val create = tools.items.map { it as Json.Obj }.first { (it.map["name"] as Json.Str).v == "create_module" }
        val required = ((create.map["inputSchema"] as Json.Obj).map["required"] as Json.Arr).items
            .map { (it as Json.Str).v }
        assertEquals(listOf("source"), required)

        // list_modules：预置的 main/example 可见
        val listed = callText("list_modules")
        assertTrue("main/v1" in listed && "example/v1" in listed, listed)

        // create_module：整段源文本装载，诊断清零
        val source = """
            module demo.tools

            /// Adds one.
            public function inc(x: Int) -> Int {
                return x + 1
            }

            test "inc" target: demo.tools.inc {
                let r: Int = inc(x: 41)
            }
        """.trimIndent()
        val created = callText("create_module", """{"source":${quoted(source)}}""")
        assertTrue("\"module\":\"demo.tools/v1\"" in created, created)
        assertTrue("\"diagnostics\":[]" in created, created)
        assertTrue("demo.tools/v1" in callText("list_modules"))

        // build_project：产出落项目目录的自包含 jar（默认 main/example 均零诊断）
        val built = callText("build_project")
        assertTrue("\"mainClass\":\"main\"" in built, built)
        val jar = java.nio.file.Path.of(Regex("\"jar\":\"([^\"]+)\"").find(built)!!.groupValues[1])
        assertTrue(Files.isRegularFile(jar), jar.toString())
        assertEquals(projectDir, jar.parent.parent)

        // delete_module：卸载后列表不再包含（HTTP 复核）
        val deleted = callText("delete_module", """{"module":"demo.tools/v1"}""")
        assertTrue("demo.tools/v1" in deleted, deleted)
        val modules = http.send(
            HttpRequest.newBuilder(URI("$base/modules")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("demo.tools/v1" !in modules.body(), modules.body())

        server.close()
    }
}
