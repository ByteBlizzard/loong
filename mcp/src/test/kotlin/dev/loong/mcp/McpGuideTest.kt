package dev.loong.mcp

import dev.loong.engine.json.Json
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 语言导览面验收：initialize 的 instructions、prompts（任务手册）、resources（参考文档）
 * 与工具 inputSchema。纯本地方法——不依赖 daemon（baseUrl 指向不可达地址也不影响）。
 */
class McpGuideTest {

    /** 管道会话（同 McpLoopTest 的模式，但不挂 daemon）。 */
    private class Session {
        private val toServer = PipedOutputStream()
        private val fromServer = PipedInputStream()
        private val writer: PrintWriter
        private val reader = fromServer.bufferedReader()
        private var nextId = 1L

        init {
            val session = McpSession(
                PipedInputStream(toServer).bufferedReader(),
                PipedOutputStream(fromServer).writer(Charsets.UTF_8),
                "http://127.0.0.1:1", // 导览面不触网；误触网即失败（连接拒绝）
            )
            Thread { session.run() }.apply { isDaemon = true }.start()
            writer = PrintWriter(toServer, true)
        }

        fun request(method: String, params: String = "{}"): Json.Obj {
            writer.println("""{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")
            return Json.parse(reader.readLine() ?: error("session closed")) as Json.Obj
        }

        fun notify(json: String) {
            writer.println(json)
        }
    }

    @Test
    fun `initialize advertises instructions and guide capabilities`() {
        val s = Session()
        val init = s.request("initialize")
        val result = init.map["result"] as Json.Obj

        // instructions 注入模型上下文：讲语言要点与工作法，而不只是工具名单
        val instructions = (result.map["instructions"] as Json.Str).v
        for (marker in listOf("AST", "E_MISSING_DOC", "E_NO_TEST", "insert_node", "run_tests", "Option")) {
            assertTrue(marker in instructions, "instructions missing: $marker")
        }

        val caps = result.map["capabilities"] as Json.Obj
        assertTrue("tools" in caps.map)
        assertTrue("prompts" in caps.map)
        assertTrue("resources" in caps.map)
    }

    @Test
    fun `prompts list and get render task playbooks`() {
        val s = Session()
        val listed = s.request("prompts/list").map["result"].let { it as Json.Obj }
            .map["prompts"] as Json.Arr
        val names = listed.items.map { ((it as Json.Obj).map["name"] as Json.Str).v }
        assertTrue(names.containsAll(listOf("write-function", "new-module", "fix-diagnostics")), names.toString())

        // write-function 带参数渲染：逐步手册，含节点 kind/fields 示例与强制规则提示
        val got = s.request(
            "prompts/get",
            """{"name":"write-function","arguments":{"module":"demo.app/v1","name":"inc","returns":"Int"}}""",
        ).map["result"].let { it as Json.Obj }
        val text = (((got.map["messages"] as Json.Arr).items[0] as Json.Obj)
            .map["content"] as Json.Obj).let { (it.map["text"] as Json.Str).v }
        for (marker in listOf("demo.app/v1", "inc", "kind\":\"Function", "DocComment", "E_NO_TEST", "run_tests")) {
            assertTrue(marker in text, "write-function playbook missing: $marker")
        }
    }

    @Test
    fun `resources list and read serve reference docs`() {
        val s = Session()
        val listed = s.request("resources/list").map["result"].let { it as Json.Obj }
            .map["resources"] as Json.Arr
        val uris = listed.items.map { ((it as Json.Obj).map["uri"] as Json.Str).v }
        assertTrue(
            uris.containsAll(
                listOf(
                    "loong://guide/quickstart",
                    "loong://guide/grammar",
                    "loong://reference/ast-kinds",
                    "loong://reference/diagnostics",
                    "loong://examples/hello",
                ),
            ),
            uris.toString(),
        )

        fun read(uri: String): String {
            val res = s.request("resources/read", """{"uri":"$uri"}""").map["result"].let { it as Json.Obj }
            val content = (res.map["contents"] as Json.Arr).items[0] as Json.Obj
            assertTrue("text/markdown" == (content.map["mimeType"] as Json.Str).v)
            return (content.map["text"] as Json.Str).v
        }

        val kinds = read("loong://reference/ast-kinds")
        for (marker in listOf("| Function |", "| MatchArm |", "| Literal |", "DocComment", "第一个子节点")) {
            assertTrue(marker in kinds, "ast-kinds missing: $marker")
        }
        val diags = read("loong://reference/diagnostics")
        assertTrue("E_MISSING_DOC" in diags && "E_NOT_EXHAUSTIVE" in diags, diags.take(200))
        val hello = read("loong://examples/hello")
        assertTrue("Hello , loong!" in hello && "impl Iterable<Int> for Range" in hello, hello.take(200))

        // 未知 URI 回方法级错误
        val err = s.request("resources/read", """{"uri":"loong://nope"}""")
        assertTrue(err.map.containsKey("error"), err.toString())
    }

    @Test
    fun `tools carry input schemas`() {
        val s = Session()
        s.request("initialize")
        s.notify("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val tools = s.request("tools/list").map["result"].let { it as Json.Obj }.map["tools"] as Json.Arr
        assertTrue(tools.items.size >= 19, "expected full tool surface")
        val insert = tools.items.map { it as Json.Obj }
            .first { (it.map["name"] as Json.Str).v == "insert_node" }
        val schema = insert.map["inputSchema"] as Json.Obj
        val props = schema.map["properties"] as Json.Obj
        for (key in listOf("module", "parent", "kind", "fields")) {
            assertTrue(key in props.map, "insert_node schema missing property: $key")
        }
        assertTrue((schema.map["required"] as Json.Arr).items
            .map { (it as Json.Str).v }.containsAll(listOf("module", "kind")))
        // 描述里带用法要点（教怎么用，不只是是什么）
        val desc = (insert.map["description"] as Json.Str).v
        assertTrue("DocComment" in desc && "诊断" in desc, desc)
    }
}
