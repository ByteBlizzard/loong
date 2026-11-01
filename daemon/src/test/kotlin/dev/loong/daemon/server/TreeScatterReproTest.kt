package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 复现外部 agent 报告的 P0-1「AST 写入拆散树 / 毒节点」断言（调查用）：
 * - a) 同批 trees 提交两棵复杂树（含 While/If 函数体）→ 第二棵是否被嵌进第一棵？
 * - b) While 体尾部 Assign、前一条 If → Assign 是否被挂成 If 的第三 kid（else）？
 * - c) replace_tree 以 While 结尾的函数体 → 尾部 Return 是否丢失？
 * - d) 残缺节点（缺操作数 Binary）→ 写入是否被接受？之后 render/edit/test 是否全炸、诊断是否陈旧？
 * - e) 递归函数自引用（短名/全名）→ 是否 E_UNKNOWN_NAME？
 */
class TreeScatterReproTest {

    @TempDir
    lateinit var project: Path

    private val client = HttpClient.newHttpClient()

    // 计数循环 + 尾部 Return：i=0; while i<n { if i>0 { i = i + 1 } }; return i
    private val loopFn = """
        {"kind":"Function","fields":{"name":"loop","visibility":"internal","return_type":"Int"},"kids":[
          {"kind":"DocComment","fields":{"text":"Counts up."}},
          {"kind":"Param","fields":{"name":"n","type":"Int"}},
          {"kind":"Block","kids":[
            {"kind":"Let","fields":{"name":"i","type":"Int","mutable":true,"shared":false},"kids":[
              {"kind":"Literal","fields":{"lit_kind":"int","value":"0"}}
            ]},
            {"kind":"While","kids":[
              {"kind":"Binary","fields":{"op":"<"},"kids":[
                {"kind":"Path","fields":{"written":"i"}},
                {"kind":"Path","fields":{"written":"n"}}
              ]},
              {"kind":"Block","kids":[
                {"kind":"If","kids":[
                  {"kind":"Binary","fields":{"op":">"},"kids":[
                    {"kind":"Path","fields":{"written":"i"}},
                    {"kind":"Literal","fields":{"lit_kind":"int","value":"0"}}
                  ]},
                  {"kind":"Block","kids":[
                    {"kind":"Assign","kids":[
                      {"kind":"Path","fields":{"written":"i"}},
                      {"kind":"Binary","fields":{"op":"+"},"kids":[
                        {"kind":"Path","fields":{"written":"i"}},
                        {"kind":"Literal","fields":{"lit_kind":"int","value":"1"}}
                      ]}
                    ]}
                  ]}
                ]}
              ]}
            ]},
            {"kind":"Return","kids":[{"kind":"Path","fields":{"written":"i"}}]}
          ]}
        ]}
    """.trimIndent().replace("\n", "")

    // 第二棵复杂树：sum(xs) = for 循环求和
    private val sumFn = """
        {"kind":"Function","fields":{"name":"sum","visibility":"internal","return_type":"Int"},"kids":[
          {"kind":"DocComment","fields":{"text":"Sums."}},
          {"kind":"Param","fields":{"name":"xs","type":"Array<Int>"}},
          {"kind":"Block","kids":[
            {"kind":"Let","fields":{"name":"acc","type":"Int","mutable":true,"shared":false},"kids":[
              {"kind":"Literal","fields":{"lit_kind":"int","value":"0"}}
            ]},
            {"kind":"For","fields":{"var_name":"x","var_type":"Int"},"kids":[
              {"kind":"Path","fields":{"written":"xs"}},
              {"kind":"Block","kids":[
                {"kind":"Assign","kids":[
                  {"kind":"Path","fields":{"written":"acc"}},
                  {"kind":"Binary","fields":{"op":"+"},"kids":[
                    {"kind":"Path","fields":{"written":"acc"}},
                    {"kind":"Path","fields":{"written":"x"}}
                  ]}
                ]}
              ]}
            ]},
            {"kind":"Return","kids":[{"kind":"Path","fields":{"written":"acc"}}]}
          ]}
        ]}
    """.trimIndent().replace("\n", "")

    @Test
    fun `a - two complex trees in one call stay siblings`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.a\n"}""")
            val (s, b) = post(
                port, "/modules/demo.a%2Fv1/tree",
                """{"trees":[$loopFn,$sumFn]}""",
            )
            println("a) insert status=$s body=$b")
            assertEquals(200, s, b)
            val ids = Regex("\"ids\":\\[(\\d+),(\\d+)").find(b)!!.groupValues
            // 两个树根的 parent 都应是 Module 根（=1），互不嵌套
            for (id in ids.drop(1)) {
                val node = get(port, "/modules/demo.a%2Fv1/node/$id").second
                println("a) tree root $id -> $node")
                assertTrue("\"parent\":1" in node, "tree root $id not a module kid: $node")
            }
            val render = get(port, "/modules/demo.a%2Fv1/render").second
            println("a) render:\n$render")
            assertTrue("function loop" in render && "function sum" in render)
        }
    }

    @Test
    fun `b - assign after if inside while body lands in the block`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.b\n"}""")
            // 单独提交一棵 While 体尾部是 Assign、前一条是 If 的树（While 直接子节点）
            val whileTailAssign = """
                {"kind":"Function","fields":{"name":"w","visibility":"internal","return_type":"Unit"},"kids":[
                  {"kind":"DocComment","fields":{"text":"While with trailing assign."}},
                  {"kind":"Block","kids":[
                    {"kind":"Let","fields":{"name":"i","type":"Int","mutable":true,"shared":false},"kids":[
                      {"kind":"Literal","fields":{"lit_kind":"int","value":"0"}}
                    ]},
                    {"kind":"While","kids":[
                      {"kind":"Binary","fields":{"op":"<"},"kids":[
                        {"kind":"Path","fields":{"written":"i"}},
                        {"kind":"Literal","fields":{"lit_kind":"int","value":"3"}}
                      ]},
                      {"kind":"Block","kids":[
                        {"kind":"If","kids":[
                          {"kind":"Binary","fields":{"op":">"},"kids":[
                            {"kind":"Path","fields":{"written":"i"}},
                            {"kind":"Literal","fields":{"lit_kind":"int","value":"0"}}
                          ]},
                          {"kind":"Block","kids":[
                            {"kind":"Assign","kids":[
                              {"kind":"Path","fields":{"written":"i"}},
                              {"kind":"Binary","fields":{"op":"-"},"kids":[
                                {"kind":"Path","fields":{"written":"i"}},
                                {"kind":"Literal","fields":{"lit_kind":"int","value":"1"}}
                              ]}
                            ]}
                          ]}
                        ]},
                        {"kind":"Assign","kids":[
                          {"kind":"Path","fields":{"written":"i"}},
                          {"kind":"Binary","fields":{"op":"+"},"kids":[
                            {"kind":"Path","fields":{"written":"i"}},
                            {"kind":"Literal","fields":{"lit_kind":"int","value":"2"}}
                          ]}
                        ]}
                      ]}
                    ]}
                  ]}
                ]}
            """.trimIndent().replace("\n", "")
            val (s, b) = post(port, "/modules/demo.b%2Fv1/tree", """{"trees":[$whileTailAssign]}""")
            println("b) insert status=$s body=$b")
            assertEquals(200, s, b)
            assertTrue("\"diagnostics\":[]" in b, "expected green diagnostics: $b")
            val render = get(port, "/modules/demo.b%2Fv1/render").second
            println("b) render:\n$render")
            // If 若吃掉第三个 kid 会把 Assign 渲染成 else（出现第二组 {}）；记录观察
            assertTrue("i = i + 2" in render, "trailing assign missing from render: $render")
        }
    }

    @Test
    fun `c - replace tree keeps trailing return after while`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.c\n"}""")
            val (_, b1) = post(port, "/modules/demo.c%2Fv1/tree", """{"trees":[$loopFn]}""")
            val fnId = Regex("\"ids\":\\[(\\d+)").find(b1)!!.groupValues[1]
            // 以 While 结尾的函数体（尾部 Return 在 While 之后）整树重写
            val (s, b2) = post(
                port, "/modules/demo.c%2Fv1/tree",
                """{"id":$fnId,"tree":$loopFn}""",
            )
            println("c) replace status=$s body=$b2")
            assertEquals(200, s, b2)
            val render = get(port, "/modules/demo.c%2Fv1/render").second
            println("c) render:\n$render")
            assertTrue("return i" in render, "trailing return lost: $render")
        }
    }

    @Test
    fun `d - malformed tree write is rejected atomically with structural message`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.d\n"}""")
            post(port, "/modules/demo.d%2Fv1/tree", """{"trees":[$loopFn]}""")
            val before = get(port, "/modules/demo.d%2Fv1/diagnostics").second
            val nodesBefore = get(port, "/modules/demo.d%2Fv1/nodes").second

            // 缺操作数的 Binary（kids 只有 lhs）——结构不变量在写入入口整批拒绝
            val poison = """
                {"kind":"ExprStmt","kids":[
                  {"kind":"Binary","fields":{"op":"+"},"kids":[
                    {"kind":"Path","fields":{"written":"n"}}
                  ]}
                ]}
            """.trimIndent().replace("\n", "")
            val target = bodyBlockId(port, "demo.d/v1")
            val (es, eb) = post(port, "/modules/demo.d%2Fv1/tree", """{"tree":$poison,"parent":$target}""")
            assertEquals(400, es, eb)
            assertTrue("binary op takes lhs + rhs kids" in eb, "structural message expected: $eb")
            assertTrue("trees[0].kids[0]" in eb, "node path expected: $eb")

            // 零副作用：诊断（含 revision）与节点集完全不变——「崩溃写入也是写入」不复存在
            assertEquals(before, get(port, "/modules/demo.d%2Fv1/diagnostics").second)
            assertEquals(nodesBefore, get(port, "/modules/demo.d%2Fv1/nodes").second)
        }
    }

    @Test
    fun `d2 - fine-grained half-built node yields E_INVARIANT_VIOLATION not a crash`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.d2\n"}""")
            post(port, "/modules/demo.d2%2Fv1/tree", """{"trees":[$loopFn]}""")
            val target = bodyBlockId(port, "demo.d2/v1")

            // 细粒度 insert_node 半成品 If（无 kids）——写入保留，诊断点名（诊断驱动工作流）
            val (ws, wb) = post(
                port, "/modules/demo.d2%2Fv1/edit",
                """{"op":"insert","parent":$target,"kind":"If"}""",
            )
            assertEquals(200, ws, wb)
            assertTrue("E_INVARIANT_VIOLATION" in wb, "expected structural diagnostic: $wb")

            // 渲染降级不炸（防御式渲染：残缺节点出 <incomplete> 标记）
            val (rs, rb) = get(port, "/modules/demo.d2%2Fv1/render")
            assertEquals(200, rs, rb)
            assertTrue("<incomplete>" in rb, "defensive marker expected: $rb")

            // 结构审计：broken=true 点名残缺节点
            val broken = get(port, "/modules/demo.d2%2Fv1/nodes?broken=true").second
            assertTrue("\"problem\"" in broken, "broken filter expected: $broken")

            // 修复路径永远可用：delete 掉残缺节点 → 诊断清零
            val badId = Regex("\"id\":(\\d+),\"kind\":\"If\",\"problem\"").find(broken)
                ?.groupValues?.get(1)
                ?: Regex("\"id\":(\\d+)").find(broken)!!.groupValues[1]
            val (ds, db) = post(
                port, "/modules/demo.d2%2Fv1/edit",
                """{"op":"delete","id":$badId}""",
            )
            assertEquals(200, ds, db)
            assertTrue("\"diagnostics\":[]" in db, "diagnostics should clear after delete: $db")
        }
    }

    @Test
    fun `f - uncommitted diff reports pending changes`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.f\n"}""")
            post(port, "/modules/demo.f%2Fv1/tree", """{"trees":[$loopFn]}""")

            // 无 commit 基线：全部内容都是新增行
            val (s0, b0) = get(port, "/modules/demo.f%2Fv1/diff/uncommitted")
            assertEquals(200, s0, b0)
            assertTrue("\"base\":\"initial\"" in b0, b0)

            // commit 后未再改：diff 为空
            post(port, "/modules/demo.f%2Fv1/commit", """{"message":"add loop"}""")
            val (s1, b1) = get(port, "/modules/demo.f%2Fv1/diff/uncommitted")
            assertEquals(200, s1, b1)
            assertTrue("\"base\":\"commit 1\"" in b1, b1)
            assertTrue("\"diff\":[]" in b1, "no pending changes after commit: $b1")

            // 再改一笔：diff 出现变更行
            post(port, "/modules/demo.f%2Fv1/tree", """{"trees":[$sumFn]}""")
            val (s2, b2) = get(port, "/modules/demo.f%2Fv1/diff/uncommitted")
            assertEquals(200, s2, b2)
            assertTrue("\"+ function sum" in b2.replace("\\\\n", "\n"), "pending change expected: $b2")
        }
    }

    /** 函数体 Block ID：函数节点 kids = [DocComment?, Param*, Block] 的末位。 */
    private fun bodyBlockId(port: Int, module: String): String {
        val mod = module.replace("/", "%2F")
        val fns = get(port, "/modules/$mod/nodes?kind=Function").second
        val fnId = Regex("\"id\":(\\d+)").find(fns)!!.groupValues[1]
        val fn = get(port, "/modules/$mod/node/$fnId").second
        return Regex("\"kids\":\\[([\\d,]+)]").find(fn)!!.groupValues[1].split(',').last().trim()
    }

    @Test
    fun `e - recursive self reference resolves`() {
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()
            post(port, "/modules", """{"source":"module demo.e\n"}""")
            val fib = """
                {"kind":"Function","fields":{"name":"fib","visibility":"internal","return_type":"Int"},"kids":[
                  {"kind":"DocComment","fields":{"text":"Recursive fib."}},
                  {"kind":"Param","fields":{"name":"n","type":"Int"}},
                  {"kind":"Block","kids":[
                    {"kind":"If","kids":[
                      {"kind":"Binary","fields":{"op":"<"},"kids":[
                        {"kind":"Path","fields":{"written":"n"}},
                        {"kind":"Literal","fields":{"lit_kind":"int","value":"2"}}
                      ]},
                      {"kind":"Block","kids":[{"kind":"Return","kids":[{"kind":"Path","fields":{"written":"n"}}]}]}
                    ]},
                    {"kind":"Return","kids":[
                      {"kind":"Binary","fields":{"op":"+"},"kids":[
                        {"kind":"Call","kids":[
                          {"kind":"Path","fields":{"written":"fib"}},
                          {"kind":"Arg","kids":[
                            {"kind":"Binary","fields":{"op":"-"},"kids":[
                              {"kind":"Path","fields":{"written":"n"}},
                              {"kind":"Literal","fields":{"lit_kind":"int","value":"1"}}
                            ]}
                          ]}
                        ]},
                        {"kind":"Call","kids":[
                          {"kind":"Path","fields":{"written":"fib"}},
                          {"kind":"Arg","kids":[
                            {"kind":"Binary","fields":{"op":"-"},"kids":[
                              {"kind":"Path","fields":{"written":"n"}},
                              {"kind":"Literal","fields":{"lit_kind":"int","value":"2"}}
                            ]}
                          ]}
                        ]}
                      ]}
                    ]}
                  ]}
                ]}
            """.trimIndent().replace("\n", "")
            val (s, b) = post(port, "/modules/demo.e%2Fv1/tree", """{"trees":[$fib]}""")
            println("e) short-name self-ref insert: status=$s body=$b")

            // 全名自引用变体
            post(port, "/modules", """{"source":"module demo.e2\n"}""")
            val fibFull = fib.replace("\"written\":\"fib\"", "\"written\":\"demo.e2.fib\"")
            val (s2, b2) = post(port, "/modules/demo.e2%2Fv1/tree", """{"trees":[$fibFull]}""")
            println("e) full-name self-ref insert: status=$s2 body=$b2")
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
