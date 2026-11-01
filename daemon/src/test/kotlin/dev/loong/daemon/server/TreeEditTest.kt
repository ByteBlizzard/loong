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

    // ---------- HTTP 小件 ----------

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
