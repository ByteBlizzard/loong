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
 * 除工具面外提供语言导览面（见 GuideContent）：initialize 的 instructions（常驻精简版）、
 * prompts（write-function / new-module / fix-diagnostics 任务手册）、
 * resources（文法 / AST 节点表 / 诊断码 / 示例参考文档）——教 agent 写 loong，
 * 而不只是看到工具列表。工具本身携带 inputSchema 与用法要点描述。
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

    // 超时兜底：daemon 无响应（测试死循环挂线程 / 进程僵死）时调用方拿到明确错误，
    // 而不是永久挂起——run_tests 的进程内执行没有任何服务端超时。
    private val http = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()

    private fun request(path: String, method: String, body: String? = null): HttpRequest =
        HttpRequest.newBuilder(URI(baseUrl + path))
            .header("X-Loong-Client", "mcp")
            .timeout(java.time.Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .apply {
                if (body == null) {
                    method(method, HttpRequest.BodyPublishers.noBody())
                } else {
                    header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()

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
                    "capabilities" to mapOf(
                        "tools" to emptyMap<String, Any?>(),
                        "prompts" to mapOf("listChanged" to false),
                        "resources" to mapOf("listChanged" to false),
                    ),
                    "serverInfo" to mapOf("name" to "loong-mcp", "version" to McpServer.VERSION),
                    // 语言与工作法导览：客户端会把 instructions 注入模型上下文（MCP 规范）；
                    // 完整参考走 prompts（任务手册）与 resources（文法/节点表/诊断码）按需拉取。
                    "instructions" to GuideContent.INSTRUCTIONS,
                ),
            )

            "ping" -> Ok(emptyMap<String, Any?>())

            // ---------- 语言导览面（prompts / resources） ----------

            "prompts/list" -> Ok(mapOf("prompts" to GuideContent.PROMPTS.map { it.descriptor }))

            "prompts/get" -> {
                val name = (params?.map?.get("name") as? Json.Str)?.v
                    ?: return Err(-32602, "missing prompt name")
                val prompt = GuideContent.PROMPTS.firstOrNull { it.name == name }
                    ?: return Err(-32602, "unknown prompt '$name'")
                val args = (params.map["arguments"] as? Json.Obj)?.map
                    ?.mapValues { (_, v) -> (v as? Json.Str)?.v ?: v.toString() }
                    ?: emptyMap()
                Ok(
                    mapOf(
                        "description" to prompt.description,
                        "messages" to listOf(
                            mapOf("role" to "user", "content" to mapOf("type" to "text", "text" to prompt.render(args))),
                        ),
                    ),
                )
            }

            "resources/list" -> Ok(mapOf("resources" to GuideContent.RESOURCES.map { it.descriptor }))

            "resources/read" -> {
                val uri = (params?.map?.get("uri") as? Json.Str)?.v
                    ?: return Err(-32602, "missing resource uri")
                val res = GuideContent.RESOURCES.firstOrNull { it.uri == uri }
                    ?: return Err(-32602, "unknown resource '$uri'")
                Ok(
                    mapOf(
                        "contents" to listOf(
                            mapOf("uri" to uri, "mimeType" to "text/markdown", "text" to res.text),
                        ),
                    ),
                )
            }

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
        http.send(request(path, "GET"), HttpResponse.BodyHandlers.ofString())
            .let { if (it.statusCode() >= 400) error(it.body()) else it.body() }

    private fun post(path: String, body: String): String =
        http.send(request(path, "POST", body), HttpResponse.BodyHandlers.ofString())
            .let { if (it.statusCode() >= 400) error(it.body()) else it.body() }

    private fun delete(path: String): String =
        http.send(request(path, "DELETE"), HttpResponse.BodyHandlers.ofString())
            .let { if (it.statusCode() >= 400) error(it.body()) else it.body() }

    private fun argString(args: Json.Obj, key: String): String? =
        (args.map[key] as? Json.Str)?.v

    private fun argLong(args: Json.Obj, key: String): Long? = when (val v = args.map[key]) {
        is Json.Num -> v.v
        is Json.Str -> v.v.toLongOrNull()
        else -> null
    }

    // ---------- 工具面（design §8.3） ----------

    /** JSON Schema 片段（properties + required）：随工具描述教 agent 正确调用。 */
    private fun prop(name: String, type: String, desc: String): Pair<String, Any?> =
        name to mapOf("type" to type, "description" to desc)

    private fun schema(vararg props: Pair<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> =
        buildMap {
            put("properties", props.toMap())
            if (required.isNotEmpty()) put("required", required)
        }

    private class Tool(
        val name: String,
        val description: String,
        val schema: Map<String, Any?> = emptyMap(),
        val handler: (Json.Obj) -> String,
    ) {
        val descriptor: Map<String, Any?> = mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to mapOf("type" to "object") + schema,
        )
    }

    private val TOOLS: List<Tool> = listOf(
        // ---------- 模块级管理（整模块装卸） ----------
        Tool(
            "list_modules",
            "列出工作区全部模块名。新建模块前先看一眼，避免与既有模块重名。",
        ) { _ ->
            get("/modules")
        },
        Tool(
            "list_ast_kinds",
            "AST 节点种类总表：每个 kind 的 kids 形态约束（结构不变量——违反会被 " +
                "insert_tree/replace_tree/create_module 整批拒绝，或报 E_INVARIANT_VIOLATION）" +
                "与类别（declaration/statement/expression）。写树前查这张表，" +
                "不要用 get_node 逆向工程。关键陷阱：else 只认 If 的第 3 槽（Block 或 If）；" +
                "While/For 的第 2 kid 必须是 Block（循环语句挂 Block 里）；Binary/Assign 恰两个 kid；" +
                "MatchArm 体恰一个 kid（Return 等语句须包在 Block 里）。" +
                "配套人类可读版：resource loong://reference/ast-kinds。",
        ) { _ ->
            Json.write(
                mapOf(
                    "kinds" to dev.loong.engine.rules.StructuralInvariants.KIND_SPEC.map { (k, kids, cat) ->
                        mapOf("kind" to k.name, "category" to cat, "kids" to kids)
                    },
                ),
            )
        },
        Tool(
            "create_module",
            "创建（或整体替换同名）模块。结构化形态 {module, trees} 是 agent 主路径：" +
                "module 为全名（如 demo.app/v1），trees 为顶层声明树数组（与 insert_tree " +
                "同构）。source 源码文本形态保留给人类/导入（Web UI、CLI 文件），" +
                "agent 不要用它拼代码。命名规范见 prompt new-module。" +
                "注意：新项目已预置 main 与 example 模块，多数任务直接在其上加声明即可。",
            schema(
                prop("module", "string", "模块全名（结构化形态）"),
                prop("trees", "array", "顶层声明树数组（结构化形态）"),
                prop("source", "string", "完整模块源码文本（人类/导入兼容形态）"),
            ),
        ) { a ->
            val hasTrees = a.map.containsKey("trees")
            val body = linkedMapOf<String, Json.Value>()
            a.map.forEach { (k, v) -> if (hasTrees || k != "module") body[k] = v }
            if (body.isEmpty()) error("provide {module, trees} or {source}")
            post("/modules", Json.write(Json.Obj(body)))
        },
        Tool(
            "delete_module",
            "删除整个模块（AST 与持久化一并移除，不可恢复）。删除前确认已 commit 或确实不再需要。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a ->
            delete("/modules/${enc(req(a))}")
        },
        Tool(
            "get_diagnostics",
            "模块当前诊断列表（code/message/node）。写操作响应也自带诊断，本工具用于主动复查。",
            schema(prop("module", "string", "模块全名，如 demo.app/v1"), required = listOf("module")),
        ) { a ->
            get("/modules/${enc(req(a))}/diagnostics")
        },
        Tool(
            "get_node",
            "按节点 ID 读 AST 节点（kind/fields/kids/parent）。节点形态见 resource loong://reference/ast-kinds。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "节点 ID（Module 根恒为 1）"),
                required = listOf("module", "id"),
            ),
        ) { a ->
            get("/modules/${enc(req(a))}/node/${argLong(a, "id") ?: error("id required")}")
        },
        Tool(
            "find_nodes",
            "列出节点 ID（可按 kind 过滤，如 Function/Test/DocComment）。" +
                "broken=true 列出全部结构异常节点（kids 数量/槽位违例，附 problem 说明，" +
                "配 delete_node 修复）——审计异常写入改了什么的第一入口。",
            schema(
                prop("module", "string", "模块全名"),
                prop("kind", "string", "可选：Kind 名，缺省列出全部"),
                prop("broken", "boolean", "可选：true = 只列结构异常节点（附 problem）"),
                required = listOf("module"),
            ),
        ) { a ->
            val qs = listOfNotNull(
                argString(a, "kind")?.let { "kind=$it" },
                (a.map["broken"] as? Json.Bool)?.takeIf { it.v }?.let { "broken=true" },
            ).joinToString("&")
            get("/modules/${enc(req(a))}/nodes" + if (qs.isEmpty()) "" else "?$qs")
        },
        Tool(
            "get_module_interface",
            "模块的渲染文本视图（AST → loong 源码形态）。改代码前先读它定位结构与插入位置。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a ->
            get("/modules/${enc(req(a))}/render")
        },
        Tool(
            "get_dependencies",
            "模块的 depends 声明列表。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a ->
            get("/modules/${enc(req(a))}/deps")
        },
        // ---------- 粗粒度写入（嵌套节点树：一次提交整棵/多棵声明子树） ----------
        Tool(
            "insert_tree",
            "一次写入一棵或多棵嵌套节点树——日常写代码的主路径：整个函数（DocComment + " +
                "Param* + Block + 表达式）、配套 Test、struct/impl/external 块，一次调用全部入库。" +
                "树形态与 get_node 输出同构：{kind, fields, kids}；fields 值 string/number/boolean/" +
                "{\"r\":节点id}。应用前逐节点校验，任何形态错误整批拒绝并给出节点路径" +
                "（如 trees[0].kids[1]），零副作用。写函数的完整树模板见 prompt write-function。",
            schema(
                prop("module", "string", "模块全名"),
                prop("tree", "object", "单棵树 {kind,fields,kids}（与 trees 二选一）"),
                prop("trees", "array", "多棵兄弟树（与 tree 二选一，如 [函数树, 测试树]）"),
                prop("parent", "integer", "挂接父节点 ID（缺省 = 模块根 1）"),
                prop("index", "integer", "插入位置（缺省 = 末尾）"),
                required = listOf("module"),
            ),
        ) { a ->
            val body = linkedMapOf<String, Json.Value>()
            a.map.forEach { (k, v) -> if (k != "module") body[k] = v }
            post("/modules/${enc(req(a))}/tree", Json.write(Json.Obj(body)))
        },
        Tool(
            "replace_tree",
            "整棵子树原位重写（重写整个函数＝它的树版本）：目标节点变为 tree 根的形态" +
                "（kind/fields 换新、子树整体重建），根节点 ID 保持不变（引用不漂移）。" +
                "校验与树形态同 insert_tree。改签名、换实现、补 doc 都可用它一步完成。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "目标节点 ID"),
                prop("tree", "object", "新子树 {kind,fields,kids}"),
                required = listOf("module", "id", "tree"),
            ),
        ) { a ->
            val body = linkedMapOf<String, Json.Value>()
            a.map.forEach { (k, v) -> if (k != "module") body[k] = v }
            post("/modules/${enc(req(a))}/tree", Json.write(Json.Obj(body)))
        },
        Tool(
            "batch_edit",
            "一次提交多个操作（多点改动的粗粒度路径）：ops 数组逐项执行逐项报告，" +
                "单项失败不影响其余项（看每项 ok/error，修复后重发失败项）。" +
                "op 形态：{\"op\":\"insert_tree\",\"trees\":[...],\"parent\":1}、" +
                "{\"op\":\"replace_tree\",\"id\":9,\"tree\":{...}}、" +
                "{\"op\":\"move\",\"id\":9,\"newParent\":1,\"index\":0}、" +
                "{\"op\":\"delete\",\"id\":9}、{\"op\":\"replace\",\"id\":9,\"fields\":{\"name\":\"新名\"}}" +
                "（字段级合并语义）。常用组合见 prompt edit-in-bulk。",
            schema(
                prop("module", "string", "模块全名"),
                prop("ops", "array", "操作数组（顺序执行）"),
                required = listOf("module", "ops"),
            ),
        ) { a ->
            post(
                "/modules/${enc(req(a))}/batch",
                Json.write(mapOf("ops" to (a.map["ops"] ?: Json.Arr(emptyList())))),
            )
        },
        Tool(
            "insert_node",
            "创建单个节点并挂接为 parent 第 index 个子节点，返回新节点 ID。点状微调专用" +
                "（补一个 Param、插一条语句）；成片产出用 insert_tree（一次整棵树），" +
                "多点改动用 batch_edit——不要用它逐节点搭声明。" +
                "注意：DocComment 必须是声明的第一个子节点（index 0）；" +
                "fields 形态见 resource loong://reference/ast-kinds。",
            schema(
                prop("module", "string", "模块全名"),
                prop("parent", "integer", "父节点 ID（模块级声明挂 Module 根 = 1）"),
                prop("index", "integer", "插入位置（0 起）"),
                prop("kind", "string", "节点 kind，如 Function/DocComment/Param/Block/Test"),
                prop("fields", "object", "节点字段，如 {\"name\":\"f\",\"visibility\":\"public\",\"return_type\":\"Int\"}"),
                required = listOf("module", "parent", "kind"),
            ),
        ) { a -> edit(withOp(a, "insert")) },
        Tool(
            "replace_node",
            "原地改节点字段；给 kids 则整组子节点替换（旧子树删除）。响应自带重算诊断。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "目标节点 ID"),
                prop("fields", "object", "新字段（可只给要改的键）"),
                prop("kids", "array", "新子节点 ID 列表（须为游离节点；不给则保留子树）"),
                required = listOf("module", "id"),
            ),
        ) { a -> edit(withOp(a, "replace")) },
        Tool(
            "move_node",
            "把节点连同子树移到 newParent 的 index 位置（禁止移入自身子树）。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "节点 ID"),
                prop("newParent", "integer", "新父节点 ID"),
                prop("index", "integer", "新位置（0 起）"),
                required = listOf("module", "id", "newParent"),
            ),
        ) { a -> edit(withOp(a, "move")) },
        Tool(
            "delete_node",
            "删除节点及整棵子树（ID 永不复用）。响应自带重算诊断。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "待删节点 ID"),
                required = listOf("module", "id"),
            ),
        ) { a -> edit(withOp(a, "delete")) },
        Tool(
            "check",
            "显式触发检查并返回诊断（写操作已自动触发重算，此为复查入口，同 get_diagnostics）。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a ->
            get("/modules/${enc(req(a))}/diagnostics")
        },
        Tool(
            "run_tests",
            "运行模块全部内联 test，结果按 Test 节点 ID 索引。诊断清零后才可运行。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a ->
            post("/modules/${enc(req(a))}/test", "{}")
        },
        Tool(
            "build_project",
            "构建整个项目为自包含可运行 jar（要求全部模块诊断清零）。" +
                "产物落项目目录 build/：build/<项目名>.jar 与 build/classes/（class 中间产物）；" +
                "返回 jar 路径与 Main-Class。与人类在 Web UI「构建」页触发的是同一构建。",
        ) { _ ->
            post("/build", "{}")
        },
        // ---------- JVM 生态：三方依赖管理 + 类查询（写 external 声明前先查） ----------
        Tool(
            "list_jvm_deps",
            "查看 JVM 三方依赖：jvm-deps.json 坐标清单 + 当前生效 jar（libs/ 手放 + 解析产物）+ 最近解析错误。",
        ) { _ ->
            get("/jvmdeps")
        },
        Tool(
            "add_jvm_dep",
            "添加 JVM 三方依赖（Maven 坐标 group:artifact:version）。daemon 委托本机 gradle/mvn " +
                "解析 classpath；成功后 java_class_info、external 声明校验、测试与构建即可使用该库。" +
                "解析失败不阻塞——结果里带错误与兜底建议（手动放 jar 到项目 libs/ 目录）。",
            schema(
                prop("coordinate", "string", "Maven 坐标，如 com.google.code.gson:gson:2.11.0"),
                required = listOf("coordinate"),
            ),
        ) { a ->
            post(
                "/jvmdeps",
                Json.write(
                    mapOf("coordinate" to (argString(a, "coordinate") ?: error("coordinate required"))),
                ),
            )
        },
        Tool(
            "remove_jvm_dep",
            "移除一条 JVM 三方依赖并重解析 classpath。",
            schema(prop("coordinate", "string", "Maven 坐标"), required = listOf("coordinate")),
        ) { a ->
            delete("/jvmdeps/${enc(argString(a, "coordinate") ?: error("coordinate required"))}")
        },
        Tool(
            "resolve_jvm_deps",
            "手动重解析 JVM 三方依赖（直接改了 jvm-deps.json 或 libs/ 之后，或上次解析失败重试）。",
        ) { _ ->
            post("/jvmdeps/resolve", "{}")
        },
        Tool(
            "java_class_info",
            "反射查询 JVM 类的 public API：构造器 / 静态方法 / 实例方法（参数与返回的 JVM 类型）" +
                "+ loong 类型映射提示（int→Int32、long→Int、double→Float…）。" +
                "写 external java 声明前先查真实签名，不要凭记忆猜——JDK 类开箱即查，" +
                "三方类需先 add_jvm_dep 或放入 libs/。写法手册见 resource loong://guide/interop。",
            schema(
                prop("fqn", "string", "类全限定名，如 java.nio.file.Files"),
                required = listOf("fqn"),
            ),
        ) { a ->
            get("/jvm/class?fqn=${enc(argString(a, "fqn") ?: error("fqn required"))}")
        },
        Tool(
            "add_dependency",
            "添加 depends 声明（registry 源）。跨模块引用前先加依赖，否则 E_UNKNOWN_NAME。",
            schema(
                prop("module", "string", "模块全名"),
                prop("name", "string", "依赖模块全名，如 org.example.core/v1"),
                required = listOf("module", "name"),
            ),
        ) { a ->
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
        Tool(
            "remove_dependency",
            "删除 depends 声明（按依赖模块名）。",
            schema(
                prop("module", "string", "模块全名"),
                prop("name", "string", "依赖模块全名"),
                required = listOf("module", "name"),
            ),
        ) { a ->
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
            "挂起请求人工审批；人类经 daemon（Web UI）响应后本调用返回结果。" +
                "用于 commit 前把关或高风险变更（接口/依赖改动）。",
            schema(
                prop("module", "string", "模块全名"),
                prop("reason", "string", "请求审批的原因（展示给人类）"),
                prop("nodeIds", "array", "涉及的节点 ID 列表（可选）"),
                required = listOf("module", "reason"),
            ),
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
        Tool(
            "commit",
            "提交当前模块快照到版本历史（不可变）。测试全绿后再提交。",
            schema(
                prop("module", "string", "模块全名"),
                prop("message", "string", "提交说明（可选）"),
                required = listOf("module"),
            ),
        ) { a ->
            post(
                "/modules/${enc(req(a))}/commit",
                Json.write(mapOf("message" to (argString(a, "message") ?: ""))),
            )
        },
        Tool(
            "log",
            "模块提交历史（id + message）。",
            schema(prop("module", "string", "模块全名"), required = listOf("module")),
        ) { a -> get("/modules/${enc(req(a))}/log") },
        Tool(
            "diff",
            "行级 diff：给 id = 该提交相对前一提交；不给 id = 当前未提交改动" +
                "（当前渲染 vs 最近一次 commit；无 commit 则 vs 空基线）——" +
                "审计「刚才那批写入到底改了什么」用后者。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "可选：提交 ID（缺省 = 未提交改动）"),
                required = listOf("module"),
            ),
        ) { a ->
            val id = argLong(a, "id")
            get("/modules/${enc(req(a))}/diff" + (id?.let { "/$it" } ?: "/uncommitted"))
        },
        Tool(
            "checkout",
            "检出历史提交（模块回到该快照，用于回滚）。",
            schema(
                prop("module", "string", "模块全名"),
                prop("id", "integer", "提交 ID"),
                required = listOf("module", "id"),
            ),
        ) { a ->
            post(
                "/modules/${enc(req(a))}/checkout",
                Json.write(mapOf("id" to (argLong(a, "id") ?: error("id required")))),
            )
        },
        Tool(
            "publish",
            "发布模块到本地注册表（要求诊断清零；版本须匹配主版本段，如模块 a.b/v2 配 2.y.z）。",
            schema(
                prop("module", "string", "模块全名"),
                prop("version", "string", "语义版本 x.y.z"),
                required = listOf("module", "version"),
            ),
        ) { a ->
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

        /** 单请求超时（秒）：略高于 request_human_review 的等待上限，正常调用远低于此。 */
        private const val REQUEST_TIMEOUT_S = 120L
    }

    private sealed class Result
    private class Ok(val value: Any?) : Result()
    private class Err(val code: Int, val message: String) : Result()
}
