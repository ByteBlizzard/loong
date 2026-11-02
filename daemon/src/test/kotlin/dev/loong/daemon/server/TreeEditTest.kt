package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 嵌套节点树（粗粒度写入）与批量编辑的端到端验证：
 * - insert_tree：一次调用写入整函数树 + 配套 Test 树（读写与 get_node 同构）；
 * - replace_tree：整函数原位重写，根节点 ID 保留；
 * - 结构校验：坏 kind / 坏字段形态 → 整批拒绝零副作用（revision 与节点数不变）；
 * - batch：树/节点操作混合一次提交，单项失败不影响其余项；
 * - create_module {module, trees}：结构化建模块（无源码文本）。
 */
class TreeEditTest {

    @TempDir
    lateinit var project: Path

    private val client = HttpClient.newHttpClient()

    /** incr(x) = x + 1 的完整函数树（DocComment + Param + Block + Return + Binary）。 */
    private fun fnTree(name: String = "incr", doc: String = "Adds one to x.", rhs: String = "1") = """
        {"kind":"Function","fields":{"name":"$name","visibility":"public","return_type":"Int"},"kids":[
          {"kind":"DocComment","fields":{"text":"$doc"}},
          {"kind":"Param","fields":{"name":"x","type":"Int"}},
          {"kind":"Block","kids":[
            {"kind":"Return","kids":[
              {"kind":"Binary","fields":{"op":"+"},"kids":[
                {"kind":"Path","fields":{"written":"x"}},
                {"kind":"Literal","fields":{"lit_kind":"int","value":"$rhs"}}
              ]}
            ]}
          ]}
        ]}
    """.trimIndent().replace("\n", "")

    /** 配套 Test 树：let r = name(x: 41)。 */
    private fun testTree(module: String, fn: String = "incr") = """
        {"kind":"Test","fields":{"name":"$fn","target_name":"$module.$fn"},"kids":[
          {"kind":"Block","kids":[
            {"kind":"Let","fields":{"name":"r","type":"Int","mutable":false,"shared":false},"kids":[
              {"kind":"Call","kids":[
                {"kind":"Path","fields":{"written":"$fn"}},
                {"kind":"Arg","kids":[{"kind":"Literal","fields":{"lit_kind":"int","value":"41"}}]}
              ]}
            ]}
          ]}
        ]}
    """.trimIndent().replace("\n", "")

    @Test
    fun `insert tree writes function and test in one call`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")

            // 一次调用：两棵树（函数 + 配套 test）
            val (s, b) = post(
                port, "/modules/demo.tree%2Fv1/tree",
                """{"trees":[${fnTree()},${testTree("demo.tree")}]}""",
            )
            assertEquals(200, s, b)
            assertTrue("\"diagnostics\":[]" in b, b)

            // 测试真的跑得通（树结构合法且语义正确）
            val (ts, tb) = post(port, "/modules/demo.tree%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":1" in tb, tb)
        }
    }

    @Test
    fun `replace tree rewrites a function keeping its id`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")
            val (_, b1) = post(
                port, "/modules/demo.tree%2Fv1/tree",
                """{"trees":[${fnTree()},${testTree("demo.tree")}]}""",
            )
            val fnId = Regex("\"ids\":\\[(\\d+)").find(b1)?.groupValues?.get(1)
                ?: error("no ids in response: $b1")

            // 整函数重写：x + 1 → x + 2（doc 也换），根 ID 不变
            val (s, b2) = post(
                port, "/modules/demo.tree%2Fv1/tree",
                """{"id":$fnId,"tree":${fnTree(doc = "Adds two to x.", rhs = "2")}}""",
            )
            assertEquals(200, s, b2)
            assertTrue("\"ids\":$fnId" in b2, b2)
            assertTrue("\"diagnostics\":[]" in b2, b2)

            // 渲染视图反映新函数体；测试仍绿
            val (_, render) = get(port, "/modules/demo.tree%2Fv1/render")
            assertTrue("Adds two to x." in render && "x + 2" in render, render)
            val (ts, tb) = post(port, "/modules/demo.tree%2Fv1/test", "{}")
            assertTrue("\"passed\":1" in tb, tb)
        }
    }

    @Test
    fun `invalid tree is rejected atomically with node path`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")
            val before = get(port, "/modules/demo.tree%2Fv1/nodes?kind=Function")
            val diag0 = get(port, "/modules/demo.tree%2Fv1/diagnostics")

            // 第二棵树 kind 非法：整批拒绝（第一棵好树也不入库），错误带节点路径
            val bad = """{"trees":[${fnTree()},{"kind":"Nope","fields":{}}]}"""
            val (s, b) = post(port, "/modules/demo.tree%2Fv1/tree", bad)
            assertEquals(400, s, b)
            assertTrue("trees[1]" in b, b)

            // 零副作用：函数节点数与 revision（含于诊断响应）不变
            assertEquals(before, get(port, "/modules/demo.tree%2Fv1/nodes?kind=Function"))
            assertEquals(diag0, get(port, "/modules/demo.tree%2Fv1/diagnostics"))
        }
    }

    @Test
    fun `batch mixes tree and node ops with per-item results`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")
            val (_, b1) = post(
                port, "/modules/demo.tree%2Fv1/tree",
                """{"trees":[${fnTree()},${testTree("demo.tree")}]}""",
            )
            val fnId = Regex("\"ids\":\\[(\\d+)").find(b1)!!.groupValues[1]
            val testId = Regex("\"ids\":\\[\\d+,(\\d+)").find(b1)!!.groupValues[1]

            // 混合批：补第二个函数+test、字段级改 test 名、移动节点、一个坏项
            val bump = fnTree("bump", "Adds one to x.")
            val bumpTest = testTree("demo.tree", "bump")
            val batch = """
                {"ops":[
                  {"op":"insert_tree","trees":[$bump,$bumpTest]},
                  {"op":"replace","id":$testId,"fields":{"name":"incr renamed"}},
                  {"op":"move","id":$testId,"newParent":1,"index":0},
                  {"op":"delete","id":999999}
                ]}
            """.trimIndent().replace("\n", "")
            val (s, b2) = post(port, "/modules/demo.tree%2Fv1/batch", batch)
            assertEquals(200, s, b2)
            assertTrue("\"applied\":3" in b2 && "\"failed\":1" in b2, b2)
            assertTrue("\"diagnostics\":[]" in b2, b2)

            // 两个函数两个测试全部在位且通过
            val (ts, tb) = post(port, "/modules/demo.tree%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":2" in tb, tb)
        }
    }

    @Test
    fun `create module accepts structured trees without source text`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            val (s, b) = post(
                port, "/modules",
                """{"module":"demo.fresh","trees":[${fnTree()},${testTree("demo.fresh")}]}""",
            )
            assertEquals(200, s, b)
            assertTrue("\"module\":\"demo.fresh/v1\"" in b, b)
            assertTrue("\"diagnostics\":[]" in b, b)

            val (ts, tb) = post(port, "/modules/demo.fresh%2Fv1/test", "{}")
            assertTrue("\"passed\":1" in tb, tb)

            // 坏模块名直接拒绝
            val (es, eb) = post(port, "/modules", """{"module":"bad name!","trees":[]}""")
            assertEquals(400, es, eb)

            // 系统模块不可结构化覆盖
            val (ss, sb) = post(port, "/modules", """{"module":"loong.io","trees":[]}""")
            assertEquals(400, ss, sb)
        }
    }

    // ---------- 字段 schema：名称 / 必填 / 类型转换（agent 实战踩过的错位） ----------

    private val pathX = """{"kind":"Path","fields":{"written":"x"}}"""

    @Test
    fun `unknown or missing fields are rejected naming the accepted ones`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")
            val before = get(port, "/modules/demo.tree%2Fv1/nodes")

            // Path 写 name 而非 written：以前被静默存下、解析报 "unknown name ''"；现在点名拒绝并列出可用字段
            val badName = fnTree().replace(pathX, """{"kind":"Path","fields":{"name":"x"}}""")
            val (s1, b1) = post(port, "/modules/demo.tree%2Fv1/tree", """{"trees":[$badName]}""")
            assertEquals(400, s1, b1)
            assertTrue("unknown field 'name' for Path" in b1 && "written" in b1, b1)
            assertTrue("trees[0].kids[2]" in b1, b1) // 节点路径直达出错的 Path

            // 只给派生字段 full_name：缺必填 written（以前被解析器清空后同样报 "unknown name ''"）
            val noWritten = fnTree().replace(pathX, """{"kind":"Path","fields":{"full_name":"x"}}""")
            val (s2, b2) = post(port, "/modules/demo.tree%2Fv1/tree", """{"trees":[$noWritten]}""")
            assertEquals(400, s2, b2)
            assertTrue("requires field 'written'" in b2, b2)

            // Test 写 target 而非 target_name
            val badTest = testTree("demo.tree").replace("\"target_name\":", "\"target\":")
            val (s3, b3) = post(port, "/modules/demo.tree%2Fv1/tree", """{"trees":[${fnTree()},$badTest]}""")
            assertEquals(400, s3, b3)
            assertTrue("unknown field 'target' for Test" in b3 && "target_name" in b3, b3)

            // 单节点写入走同一套校验
            val (s4, b4) = post(
                port, "/modules/demo.tree%2Fv1/edit",
                """{"op":"insert","parent":1,"index":0,"kind":"Function","fields":{"name":"f","visibility":"public"}}""",
            )
            assertEquals(400, s4, b4)
            assertTrue("requires field 'return_type'" in b4, b4)

            // 全部零副作用
            assertEquals(before, get(port, "/modules/demo.tree%2Fv1/nodes"))
        }
    }

    @Test
    fun `numeric literal value is coerced to text and get_node output round-trips`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")

            // value 传 JSON 数字（以前存成 Int：类型检查放行、渲染成空、codegen 在模块根报 E_INTERNAL）
            val numeric = fnTree().replace("\"value\":\"1\"", "\"value\":1")
            val (s, b) = post(port, "/modules/demo.tree%2Fv1/tree", """{"trees":[$numeric,${testTree("demo.tree")}]}""")
            assertEquals(200, s, b)
            assertTrue("\"diagnostics\":[]" in b, b)
            val (_, render) = get(port, "/modules/demo.tree%2Fv1/render")
            assertTrue("x + 1" in render, render)
            val (_, tb) = post(port, "/modules/demo.tree%2Fv1/test", "{}")
            assertTrue("\"passed\":1" in tb, tb)

            // get_node 输出 JSON 原生值：字符串/引用 {"r":id}，可原样回写
            val litId = findNode(port, "demo.tree%2Fv1", "Literal") { "\"value\":\"1\"" in it }
            val (_, lit) = get(port, "/modules/demo.tree%2Fv1/node/$litId")
            assertTrue("\"lit_kind\":\"int\"" in lit, lit)

            val pathId = findNode(port, "demo.tree%2Fv1", "Path") { "\"written\":\"x\"" in it }
            val (_, path) = get(port, "/modules/demo.tree%2Fv1/node/$pathId")
            assertTrue("\"resolved_id\":{\"r\":" in path, path)
            assertTrue("Str(value=" !in path && "Ref(id=" !in path, path)

            // 把 get_node 读出的 fields（含解析填充字段）原样喂回 replace_tree：接受，且仍绿
            val fields = Regex("\"fields\":(\\{[^}]*\\{[^}]*\\}[^}]*\\}|\\{[^}]*\\})").find(path)!!.groupValues[1]
            val (rs, rb) = post(
                port, "/modules/demo.tree%2Fv1/tree",
                """{"id":$pathId,"tree":{"kind":"Path","fields":$fields}}""",
            )
            assertEquals(200, rs, rb)
            assertTrue("\"diagnostics\":[]" in rb, rb)
        }
    }

    @Test
    fun `replace op validates merged fields against the target kind`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.tree\n"}""")
            val (_, b1) = post(port, "/modules/demo.tree%2Fv1/tree", """{"trees":[${fnTree()},${testTree("demo.tree")}]}""")
            val testId = Regex("\"ids\":\\[\\d+,(\\d+)").find(b1)!!.groupValues[1]
            val litId = findNode(port, "demo.tree%2Fv1", "Literal") { "\"value\":\"1\"" in it }

            // 未知字段按目标节点 kind 拒绝
            val (s1, r1) = post(port, "/modules/demo.tree%2Fv1/edit", """{"op":"replace","id":$testId,"fields":{"target":"demo.tree.incr"}}""")
            assertEquals(400, s1, r1)
            assertTrue("unknown field 'target' for Test" in r1, r1)

            // 合法合并：Literal.value 给数字也转成文本，渲染与测试都反映新值
            val (s2, r2) = post(port, "/modules/demo.tree%2Fv1/edit", """{"op":"replace","id":$litId,"fields":{"value":2}}""")
            assertEquals(200, s2, r2)
            assertTrue("\"diagnostics\":[]" in r2, r2)
            val (_, render) = get(port, "/modules/demo.tree%2Fv1/render")
            assertTrue("x + 2" in render, render)
        }
    }

    // ---------- HTTP 小件 ----------

    /** 按 kind 列出节点，挑出 node JSON 满足 [pick] 的第一个 id。 */
    private fun findNode(port: Int, module: String, kind: String, pick: (String) -> Boolean): String {
        val (_, list) = get(port, "/modules/$module/nodes?kind=$kind")
        return Regex("\"id\":(\\d+)").findAll(list).map { it.groupValues[1] }
            .first { id -> pick(get(port, "/modules/$module/node/$id").second) }
    }

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
}
