package dev.loong.mcp

import dev.loong.engine.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * loong-mcp：AI agent 操作面（design §8.3/§8.8）。
 * 瘦客户端——全部状态经 loongd 的 HTTP API 读写，本进程不留状态；
 * stdio 传输（换行分隔的 JSON-RPC 2.0，MCP 规范）。
 *
 * 全部工具已实现（版本历史与发布均接线），
 * 与 plan.md 的 M4/M7 排期一致。
 */
object McpServer {

    const val VERSION = "0.1.0"

    @JvmStatic
    fun main(args: Array<String>) {
        val baseUrl = args.getOrNull(0)
            ?: System.getenv("LOONGD_URL")
            ?: "http://127.0.0.1:8765"
        // MCP stdio 传输必须 UTF-8；System.out.writer() 会跟随平台编码（中文 Windows=GBK）
        McpSession(
            InputStreamReader(System.`in`, Charsets.UTF_8).buffered(),
            OutputStreamWriter(System.out, Charsets.UTF_8),
            baseUrl,
        ).run()
        exitProcess(0) // stdin EOF = 客户端断开，随之退出
    }
}

class McpSession(
    private val input: BufferedReader,
    private val output: Writer,
    private val baseUrl: String,
) {

    private val http = HttpClient.newHttpClient()

    fun run() {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val msg = try {
                Json.parse(line)
            } catch (e: Exception) {
                // 非法消息必须回 JSON-RPC 错误（id=null），沉默丢弃会让调用方永久等待
                reply(
                    mapOf(
                        "jsonrpc" to "2.0", "id" to null,
                        "error" to mapOf("code" to -32700, "message" to "parse error: ${e.message}"),
                    ),
                )
                continue
            }
            val obj = msg as? Json.Obj ?: continue
            val id = obj.map["id"]
            val method = (obj.map["method"] as? Json.Str)?.v ?: continue
            if (id == null) continue // 通知（initialized 等）：不响应
            val result = handle(method, obj.map["params"] as? Json.Obj)
            val response = when (result) {
                is Ok -> mapOf("jsonrpc" to "2.0", "id" to id, "result" to result.value)
                is Err -> mapOf(
                    "jsonrpc" to "2.0", "id" to id,
                    "error" to mapOf("code" to result.code, "message" to result.message),
                )
            }
            reply(response)
        }
    }

    private fun reply(response: Any?) {
        synchronized(output) {
            output.write(Json.write(response) + "\n")
            output.flush()
        }
    }

    // ---------- MCP 方法 ----------

    private fun handle(method: String, params: Json.Obj?): Result {
        return when (method) {
            "initialize" -> Ok(
                mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                    "serverInfo" to mapOf("name" to "loong-mcp", "version" to McpServer.VERSION),
                ),
            )

            "ping" -> Ok(emptyMap<String, Any?>())

            "tools/list" -> Ok(mapOf("tools" to TOOLS.map { it.descriptor }))

            "tools/call" -> {
                val name = (params?.map?.get("name") as? Json.Str)?.v
                    ?: return Err(-32602, "missing tool name")
                val args = params.map["arguments"] as? Json.Obj ?: Json.Obj(LinkedHashMap())
                val tool = TOOLS.firstOrNull { it.name == name }
                    ?: return Err(-32602, "unknown tool '$name'")
                val text = try {
                    tool.handler(args)
                } catch (e: Exception) {
                    return Ok(
                        mapOf(
                            "content" to listOf(mapOf("type" to "text", "text" to (e.message ?: e.toString()))),
                            "isError" to true,
                        ),
                    )
                }
                Ok(mapOf("content" to listOf(mapOf("type" to "text", "text" to text))))
            }

            else -> Err(-32601, "method not found: $method")
        }
    }

    // ---------- daemon HTTP 辅助 ----------

    private fun enc(module: String) = URLEncoder.encode(module, Charsets.UTF_8)

    private fun get(path: String): String =
        http.send(
            HttpRequest.newBuilder(URI(baseUrl + path))
                .header("X-Loong-Client", "mcp").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { if (it.statusCode() >= 400) error(it.body()) else it.body() }

    private fun post(path: String, body: String): String =
        http.send(
            HttpRequest.newBuilder(URI(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-Loong-Client", "mcp")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { if (it.statusCode() >= 400) error(it.body()) else it.body() }

    private fun argString(args: Json.Obj, key: String): String? =
        (args.map[key] as? Json.Str)?.v

    private fun argLong(args: Json.Obj, key: String): Long? = when (val v = args.map[key]) {
        is Json.Num -> v.v
        is Json.Str -> v.v.toLongOrNull()
        else -> null
    }

    // ---------- 工具面（design §8.3） ----------

    private class Tool(val name: String, val description: String, val handler: (Json.Obj) -> String) {
        val descriptor: Map<String, Any?> = mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>()),
        )
    }

    private val TOOLS: List<Tool> = listOf(
        Tool("get_diagnostics", "模块当前诊断（码/消息/节点）") { a ->
            get("/modules/${enc(req(a))}/diagnostics")
        },
        Tool("get_node", "按节点 ID 读取 AST 节点") { a ->
            get("/modules/${enc(req(a))}/node/${argLong(a, "id") ?: error("id required")}")
        },
        Tool("find_nodes", "列出节点（可按 kind 过滤），返回 ID 列表") { a ->
            val kind = argString(a, "kind")?.let { "?kind=$it" } ?: ""
            get("/modules/${enc(req(a))}/nodes$kind")
        },
        Tool("get_module_interface", "模块文本视图（渲染的 AST）") { a ->
            get("/modules/${enc(req(a))}/render")
        },
        Tool("get_dependencies", "模块 depends 声明") { a ->
            get("/modules/${enc(req(a))}/deps")
        },
        Tool("insert_node", "插入节点：{module,parent,index,kind,fields}") { a -> edit(withOp(a, "insert")) },
        Tool("replace_node", "替换节点字段/子节点：{module,id,fields?,kids?}") { a -> edit(withOp(a, "replace")) },
        Tool("move_node", "移动节点：{module,id,newParent,index}") { a -> edit(withOp(a, "move")) },
        Tool("delete_node", "删除节点（整棵子树）：{module,id}") { a -> edit(withOp(a, "delete")) },
        Tool("check", "触发检查并返回诊断（写操作已自动触发，此为显式入口）") { a ->
            get("/modules/${enc(req(a))}/diagnostics")
        },
        Tool("run_tests", "运行模块全部测试，结果按 Test 节点 ID 索引") { a ->
            post("/modules/${enc(req(a))}/test", "{}")
        },
        Tool("add_dependency", "添加依赖声明：{module,name}") { a ->
            edit(
                Json.Obj(
                    linkedMapOf(
                        "module" to a.map["module"]!!,
                        "op" to Json.Str("insert"),
                        "parent" to (a.map["root"] ?: Json.Num(1)),
                        "index" to Json.Num(1),
                        "kind" to Json.Str("Depends"),
                        "fields" to Json.Obj(
                            linkedMapOf(
                                "module_name" to Json.Str(argString(a, "name") ?: error("name required")),
                                "source" to Json.Str("registry"),
                            ),
                        ),
                    ),
                ),
            )
        },
        Tool("remove_dependency", "删除依赖声明：{module,name}") { a ->
            val m = req(a)
            val deps = Json.parse(get("/modules/${enc(m)}/deps")) as Json.Obj
            val target = (deps.map["dependencies"] as Json.Arr).items
                .firstOrNull { ((it as Json.Obj).map["module"] as? Json.Str)?.v == argString(a, "name") }
                ?: error("dependency not found: ${argString(a, "name")}")
            edit(
                Json.Obj(
                    linkedMapOf(
                        "module" to Json.Str(m),
                        "op" to Json.Str("delete"),
                        "id" to (target as Json.Obj).map["id"]!!,
                    ),
                ),
            )
        },
        Tool(
            "request_human_review",
            "挂起请求人工审批；人类经 daemon 响应后本调用返回结果。{module,reason,nodeIds?}",
        ) { a ->
            val body = Json.write(
                mapOf(
                    "module" to req(a),
                    "reason" to (argString(a, "reason") ?: ""),
                    "nodeIds" to ((a.map["nodeIds"] as? Json.Arr)?.items?.map { (it as Json.Num).v }
                        ?: emptyList<Long>()),
                ),
            )
            val resp = Json.parse(post("/review/request", body)) as Json.Obj
            val id = (resp.map["id"] as Json.Num).v
            val deadline = System.currentTimeMillis() + REVIEW_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                val r = Json.parse(get("/review/$id")) as Json.Obj
                val approved = r.map["approved"]
                if (approved is Json.Bool) {
                    return@Tool if (approved.v) "review $id: approved" else "review $id: rejected"
                }
            }
            "review $id: still pending (timed out waiting)"
        },
        Tool("commit", "提交当前模块快照：{module, message?}") { a ->
            post(
                "/modules/${enc(req(a))}/commit",
                Json.write(mapOf("message" to (argString(a, "message") ?: ""))),
            )
        },
        Tool("log", "提交历史：{module}") { a -> get("/modules/${enc(req(a))}/log") },
        Tool("diff", "两次提交间的行级 diff：{module, id}") { a ->
            get("/modules/${enc(req(a))}/diff/${argLong(a, "id") ?: error("id required")}")
        },
        Tool("checkout", "检出历史提交：{module, id}") { a ->
            post(
                "/modules/${enc(req(a))}/checkout",
                Json.write(mapOf("id" to (argLong(a, "id") ?: error("id required")))),
            )
        },
        Tool("publish", "发布模块到本地注册表：{module, version}") { a ->
            post(
                "/modules/${enc(req(a))}/publish",
                Json.write(mapOf("version" to (argString(a, "version") ?: error("version required")))),
            )
        },
    )

    private fun req(a: Json.Obj): String = argString(a, "module") ?: error("module required")

    private fun withOp(a: Json.Obj, op: String): Json.Obj {
        val m = LinkedHashMap(a.map)
        m["op"] = Json.Str(op)
        return Json.Obj(m)
    }

    private fun notYet(name: String): Nothing =
        error("tool '$name' is not yet available (scheduled for M4/M7 per plan.md)")

    private fun edit(a: Json.Obj): String {
        val module = req(a)
        val body = LinkedHashMap<String, Json.Value>()
        body["op"] = a.map["op"] ?: error("op required")
        argLong(a, "id")?.let { body["id"] = Json.Num(it) }
        argLong(a, "parent")?.let { body["parent"] = Json.Num(it) }
        a.map["newParent"]?.let { body["newParent"] = it }
        (a.map["index"] as? Json.Num)?.let { body["index"] = it }
        (a.map["kind"] as? Json.Str)?.let { body["kind"] = it }
        (a.map["fields"] as? Json.Obj)?.let { body["fields"] = it }
        (a.map["kids"] as? Json.Arr)?.let { body["kids"] = it }
        return post("/modules/${enc(module)}/edit", Json.write(Json.Obj(body)))
    }

    companion object {
        private const val REVIEW_TIMEOUT_MS = 60_000L
    }

    private sealed class Result
    private class Ok(val value: Any?) : Result()
    private class Err(val code: Int, val message: String) : Result()
}
