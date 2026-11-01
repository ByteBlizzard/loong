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

        // instructions 注入模型上下文：讲语言要点与粗粒度工作法，而不只是工具名单
        val instructions = (result.map["instructions"] as Json.Str).v
        for (marker in listOf(
            "AST", "E_MISSING_DOC", "E_NO_TEST", "run_tests", "Option",
            "insert_tree", "replace_tree", "batch_edit", "点状微调", "不要拼源码文本",
        )) {
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
        assertTrue(
            names.containsAll(listOf("write-function", "edit-in-bulk", "new-module", "fix-diagnostics")),
            names.toString(),
        )

        // write-function 带参数渲染：粗粒度手册，含完整树模板与强制规则提示
        val got = s.request(
            "prompts/get",
            """{"name":"write-function","arguments":{"module":"demo.app/v1","name":"inc","returns":"Int"}}""",
        ).map["result"].let { it as Json.Obj }
        val text = (((got.map["messages"] as Json.Arr).items[0] as Json.Obj)
            .map["content"] as Json.Obj).let { (it.map["text"] as Json.Str).v }
        for (marker in listOf(
            "demo.app/v1", "inc", "insert_tree", "kind\":\"Function", "DocComment",
            "kind\":\"Test", "E_NO_TEST", "run_tests", "trees[0]",
        )) {
            assertTrue(marker in text, "write-function playbook missing: $marker")
        }

        // edit-in-bulk：批量组合手册
        val bulk = s.request("prompts/get", """{"name":"edit-in-bulk","arguments":{"module":"demo.app/v1"}}""")
            .map["result"].let { it as Json.Obj }
        val bulkText = (((bulk.map["messages"] as Json.Arr).items[0] as Json.Obj)
            .map["content"] as Json.Obj).let { (it.map["text"] as Json.Str).v }
        for (marker in listOf("batch_edit", "replace_tree", "insert_tree", "失败语义", "字段合并")) {
            assertTrue(marker in bulkText, "edit-in-bulk playbook missing: $marker")
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
                    "loong://guide/interop",
                    "loong://reference/ast-kinds",
                    "loong://reference/diagnostics",
                    "loong://reference/stdlib",
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
        val interop = read("loong://guide/interop")
        assertTrue("java_class_info" in interop && "add_jvm_dep" in interop && "陷阱" in interop, interop.take(200))

        // 未知 URI 回方法级错误
        val err = s.request("resources/read", """{"uri":"loong://nope"}""")
        assertTrue(err.map.containsKey("error"), err.toString())
    }

    /** 标准库清单与 stdlib 源同步：每个 public 函数都必须出现在清单文本里（防漂移）。 */
    @Test
    fun `stdlib reference covers every public function in stdlib sources`() {
        val s = Session()
        val res = s.request("resources/read", """{"uri":"loong://reference/stdlib"}""")
            .map["result"].let { it as Json.Obj }
        val text = ((res.map["contents"] as Json.Arr).items[0] as Json.Obj)
            .let { (it.map["text"] as Json.Str).v }

        val stdlibDir = java.nio.file.Path.of("../stdlib/loong")
        val publicFns = stdlibDir.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".loong") }
            .flatMap { f ->
                Regex("""(?m)^public function (\w+)""").findAll(f.readText().replace("\r\n", "\n"))
            }
            .map { it.groupValues[1] }
            .toList()
        assertTrue(publicFns.size >= 60, "expected a real stdlib surface, got ${publicFns.size}")
        val missing = publicFns.filter { it !in text }
        assertTrue(missing.isEmpty(), "stdlib reference missing functions: $missing")
    }

    @Test
    fun `tools carry input schemas`() {
        val s = Session()
        s.request("initialize")
        s.notify("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val tools = s.request("tools/list").map["result"].let { it as Json.Obj }.map["tools"] as Json.Arr
        assertTrue(tools.items.size >= 22, "expected full tool surface")
        val byName = tools.items.map { it as Json.Obj }
            .associateBy { (it.map["name"] as Json.Str).v }

        // 粗粒度工具在列，schema 齐全
        for (name in listOf("insert_tree", "replace_tree", "batch_edit")) {
            assertTrue(name in byName, "missing tool $name")
        }
        val tree = byName.getValue("insert_tree")
        val treeProps = (tree.map["inputSchema"] as Json.Obj).map["properties"] as Json.Obj
        for (key in listOf("module", "tree", "trees", "parent", "index")) {
            assertTrue(key in treeProps.map, "insert_tree schema missing property: $key")
        }
        val batch = byName.getValue("batch_edit")
        assertTrue(
            ((batch.map["inputSchema"] as Json.Obj).map["required"] as Json.Arr).items
                .map { (it as Json.Str).v }.containsAll(listOf("module", "ops")),
        )
        // 树工具描述教工作法（同构/整批拒绝/路径定位）
        val treeDesc = (tree.map["description"] as Json.Str).v
        assertTrue("同构" in treeDesc && "整批拒绝" in treeDesc, treeDesc)

        val insert = byName.getValue("insert_node")
        val schema = insert.map["inputSchema"] as Json.Obj
        val props = schema.map["properties"] as Json.Obj
        for (key in listOf("module", "parent", "kind", "fields")) {
            assertTrue(key in props.map, "insert_node schema missing property: $key")
        }
        assertTrue((schema.map["required"] as Json.Arr).items
            .map { (it as Json.Str).v }.containsAll(listOf("module", "kind")))
        // 描述里带用法要点与定位（粗粒度优先，单节点仅微调）
        val desc = (insert.map["description"] as Json.Str).v
        assertTrue("DocComment" in desc && "insert_tree" in desc, desc)
    }
}
