package dev.loong.daemon.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.loong.daemon.Loongd
import dev.loong.daemon.storage.H2Storage
import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.json.Json
import dev.loong.engine.storage.WorkspaceSnapshot
import dev.loong.engine.testrunner.TestRunner
import dev.loong.engine.text.Renderer
import dev.loong.engine.workspace.Workspace
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * loongd：语言守护进程（design §8.1/§8.2）。
 *
 * - 状态在 daemon 里：Workspace 内存热状态 + H2 持久化（经 StoragePort，ADR-0003）；
 * - 所有变更经单写者操作队列串行化（人类与 AI 不会同时改同一节点）；
 * - 每次变更后向 SSE 订阅者广播事件（M5 Web UI 换 WebSocket，MCP 用通知）；
 * - 启动时从数据库恢复（崩溃恢复路径）；
 * - HTTP + JSON，M3 的 MCP server 作为瘦客户端复用本 API。
 */
class LoongdServer(
    port: Int,
    dbPath: Path,
    /** 发布注册表根目录：由入口（Up/Loongd）注入的项目目录布局；缺省为 db 同级 registry/。 */
    registryRoot: Path? = null,
) : AutoCloseable {

    private val dbRoot: Path = dbPath
    private val regRoot: Path = registryRoot ?: dbPath.resolveSibling("registry")

    private val workspace = Workspace()
    private val storage = H2Storage(dbPath)
    private val mutations = Executors.newSingleThreadExecutor { r ->
        Thread(r, "loongd-mutations").apply { isDaemon = false }
    }
    private val http: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val sseClients = CopyOnWriteArrayList<HttpExchange>()
    private val sseLock = Any()
    private val closed = CountDownLatch(1)

    @Volatile
    private var mutationsSincePersist = 0

    /** 最近一次来自 MCP 客户端的请求时间戳（X-Loong-Client 头标识），供 UI 显示 agent 连接状态。 */
    @Volatile
    private var lastMcpActivity = 0L

    /** 版本历史（design §8.3 commit/diff/checkout）：模块 → 提交列表（渲染文本快照）。 */
    private val history = HashMap<String, MutableList<Pair<String, String>>>()

    private val reviewLock = Object()
    private val reviews = LinkedHashMap<Long, Review>()
    private var nextReviewId = 1L

    init {
        // 崩溃恢复：启动时装载上次快照并重算
        runCatching { storage.load() }.getOrNull()?.let {
            runCatching { workspace.restore(it.modules) }
        }
        http.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "loongd-http").apply { isDaemon = true }
        }
        http.createContext("/") { ex -> route(ex) }
        http.start()
    }

    fun port(): Int = http.address.port

    fun awaitTermination() {
        closed.await()
    }

    override fun close() {
        persist()
        http.stop(0)
        storage.close()
        mutations.shutdownNow()
        closed.countDown()
    }

    // ---------- 路由 ----------

    private fun route(ex: HttpExchange) {
        // rawPath 保留 %2F：模块名（demo.daemon/v1）在路径中以编码形式出现
        val path = ex.requestURI.rawPath.trimEnd('/')
        val method = ex.requestMethod
        if (ex.requestHeaders.getFirst("X-Loong-Client") == "mcp") {
            lastMcpActivity = System.currentTimeMillis()
        }
        try {
            when {
                method == "GET" && (path == "" || path == "/ui" || path == "/index.html") ->
                    serveConsole(ex)

                method == "GET" && path == "/health" ->
                    respondJson(ex, 200, mapOf("ok" to true, "version" to Loongd.VERSION))

                method == "GET" && path == "/mcp/status" -> {
                    val idleMs = System.currentTimeMillis() - lastMcpActivity
                    respondJson(
                        ex, 200,
                        mapOf(
                            "mcpServer" to true,
                            "agentConnected" to (lastMcpActivity > 0L && idleMs < 30_000L),
                            "lastActivityAgoMs" to if (lastMcpActivity == 0L) null else idleMs,
                        ),
                    )
                }

                method == "GET" && path == "/modules" ->
                    respondJson(ex, 200, mapOf("modules" to workspace.moduleNames()))

                method == "POST" && path == "/modules" -> mutate(ex) { body ->
                    val src = bodyString(body, "source")
                    val m = workspace.load(src)
                    noteMutation()
                    mapOf(
                        "module" to m.name,
                        "revision" to m.revision,
                        "diagnostics" to diagnosticsJson(m.diagnostics),
                    )
                }

                method == "GET" && path == "/events" -> {
                    sse(ex)
                    return
                }

                method == "POST" && path == "/persist" -> mutate(ex) { _ ->
                    persist()
                    mapOf("ok" to true, "watermark" to workspace.watermark())
                }

                // 优雅停止（loong stop）：先把响应发回，再退出触发 shutdown hook 落盘关库。
                // 不能靠进程强杀——Windows TerminateProcess 不走 hook，可能损坏 MVStore。
                method == "POST" && path == "/shutdown" -> {
                    respondJson(ex, 200, mapOf("ok" to true, "stopping" to true))
                    Thread {
                        Thread.sleep(150) // 留出响应 flush 的时间
                        System.exit(0)
                    }.start()
                }

                path.startsWith("/review") -> reviewRoute(ex, path, method)

                path.startsWith("/modules/") -> moduleRoute(ex, path, method)

                else -> respondJson(ex, 404, mapOf("error" to "not found: $method $path"))
            }
        } catch (e: Exception) {
            runCatching { respondJson(ex, 400, mapOf("error" to (e.message ?: e.toString()))) }
        } finally {
            if (path != "/events") ex.close()
        }
    }

    /** 人工审批队列（design §8.3 request_human_review）：AI 挂起等待人类响应。 */
    private fun reviewRoute(ex: HttpExchange, path: String, method: String) {
        val rest = path.removePrefix("/review").trim('/')
        when {
            method == "POST" && rest == "request" -> {
                val body = Json.parse(ex.requestBody.readBytes().decodeToString()) as? Json.Obj
                    ?: error("request body must be a JSON object")
                val review = synchronized(reviewLock) {
                    val id = nextReviewId++
                    Review(
                        id,
                        bodyString(body, "module"),
                        (body.map["reason"] as? Json.Str)?.v ?: "",
                        (body.map["nodeIds"] as? Json.Arr)?.items?.map { (it as Json.Num).v } ?: emptyList(),
                    ).also { reviews[id] = it }
                }
                broadcast(Json.write(mapOf("type" to "review_requested", "review" to review.id)))
                respondJson(ex, 200, mapOf("id" to review.id))
            }

            method == "GET" && rest == "pending" -> {
                val pending = synchronized(reviewLock) {
                    reviews.values.filter { it.approved == null }
                        .map { mapOf("id" to it.id, "module" to it.module, "reason" to it.reason) }
                }
                respondJson(ex, 200, mapOf("pending" to pending))
            }

            method == "GET" && rest.toLongOrNull() != null -> {
                val r = synchronized(reviewLock) { reviews[rest.toLong()] }
                if (r == null) respondJson(ex, 404, mapOf("error" to "no such review"))
                else respondJson(ex, 200, mapOf("id" to r.id, "approved" to r.approved))
            }

            method == "POST" && rest.substringAfterLast('/') == "respond" -> {
                val id = rest.substringBefore('/').toLongOrNull() ?: error("bad review id")
                val body = Json.parse(ex.requestBody.readBytes().decodeToString()) as? Json.Obj
                    ?: error("request body must be a JSON object")
                val approved = (body.map["approved"] as? Json.Bool)?.v ?: error("approved required")
                val r = synchronized(reviewLock) {
                    reviews[id]?.also { it.approved = approved }
                } ?: return respondJson(ex, 404, mapOf("error" to "no such review"))
                broadcast(Json.write(mapOf("type" to "review_responded", "review" to r.id, "approved" to approved)))
                respondJson(ex, 200, mapOf("id" to r.id, "approved" to approved))
            }

            else -> respondJson(ex, 404, mapOf("error" to "not found: $method $path"))
        }
    }

    private fun moduleRoute(ex: HttpExchange, path: String, method: String) {
        val rest = path.removePrefix("/modules/")
        val segments = rest.split('/')
        // 模块名含 '/'（如 demo.daemon/v1）：路径中以 %2F 编码，仅解码首段
        val name = java.net.URLDecoder.decode(segments[0], Charsets.UTF_8)
        when {
            method == "GET" && segments.size == 2 && segments[1] == "nodes" -> {
                val kind = ex.requestURI.rawQuery?.let { q ->
                    q.split('&').firstOrNull { it.startsWith("kind=") }?.removePrefix("kind=")
                }
                val t = workspace.tableOf(name)
                val nodes = t.find(kind?.let { k -> Kind.entries.firstOrNull { it.name == k } })
                    .map { mapOf("id" to it.value, "kind" to t[it].kind.name) }
                respondJson(ex, 200, mapOf("nodes" to nodes))
            }

            method == "GET" && segments.size == 2 && segments[1] == "deps" -> {
                val t = workspace.tableOf(name)
                val deps = t.find(Kind.Depends).map {
                    mapOf(
                        "id" to it.value,
                        "module" to ((t[it].fields["module_name"] as? FieldValue.Str)?.value ?: ""),
                        "constraint" to ((t[it].fields["constraint"] as? FieldValue.Str)?.value),
                    )
                }
                respondJson(ex, 200, mapOf("dependencies" to deps))
            }

            method == "GET" && segments.size == 2 && segments[1] == "diagnostics" -> {
                val m = workspace.module(name)
                respondJson(ex, 200, mapOf("revision" to m.revision, "diagnostics" to diagnosticsJson(m.diagnostics)))
            }

            method == "GET" && segments.size == 2 && segments[1] == "render" -> {
                val text = Renderer(workspace.tableOf(name)).render(workspace.rootOf(name))
                respondText(ex, 200, text)
            }

            method == "GET" && segments.size == 3 && segments[1] == "node" -> {
                val id = segments[2].toLongOrNull() ?: error("bad node id")
                respondJson(ex, 200, nodeJson(workspace.tableOf(name), NodeId(id)))
            }

            method == "POST" && segments.size == 2 && segments[1] == "edit" -> mutate(ex, mapOf("module" to name)) { body ->
                val outcome = workspace.edit(name) { t -> applyEdit(t, body) }
                noteMutation()
                mapOf(
                    "value" to outcome.value,
                    "revision" to outcome.revision,
                    "diagnostics" to diagnosticsJson(outcome.diagnostics),
                )
            }

            method == "POST" && segments.size == 2 && segments[1] == "publish" -> mutate(ex, mapOf("module" to name)) { body ->
                val version = ((body.map["version"] as? Json.Str)?.v)
                    ?: error("version required (x.y.z)")
                val m = workspace.module(name)
                if (m.diagnostics.any { it.severity == dev.loong.engine.diagnostics.Severity.Error }) {
                    error("module has errors; fix diagnostics before publishing")
                }
                val regRoot = this.regRoot
                val manifest = dev.loong.engine.pkg.Packages.publish(
                    regRoot, workspace.tableOf(name), workspace.rootOf(name), version, m.classes,
                )
                mapOf(
                    "module" to manifest.module,
                    "version" to manifest.version,
                    "ast_sha256" to manifest.astSha256,
                    "registry" to regRoot.toString(),
                )
            }

            method == "POST" && segments.size == 2 && segments[1] == "commit" -> mutate(ex, mapOf("module" to name)) { body ->
                val text = dev.loong.engine.text.Renderer(workspace.tableOf(name)).render(workspace.rootOf(name))
                val log = history.getOrPut(name) { mutableListOf() }
                val label = (body.map["message"] as? Json.Str)?.v?.takeIf { it.isNotEmpty() }
                    ?: "rev ${log.size + 1}"
                log += label to text
                mapOf("commit" to log.size, "message" to label)
            }

            method == "GET" && segments.size == 2 && segments[1] == "log" -> {
                val log = history[name] ?: emptyList()
                respondJson(
                    ex, 200,
                    mapOf(
                        "commits" to log.mapIndexed { i, (label, _) -> mapOf("id" to (i + 1), "message" to label) },
                    ),
                )
            }

            method == "GET" && segments.size == 3 && segments[1] == "diff" -> {
                val id = segments[2].toIntOrNull() ?: error("bad commit id")
                val log = history[name] ?: error("no history for $name")
                if (id < 1 || id > log.size) error("no commit $id")
                val prev = if (id == 1) "" else log[id - 2].second
                respondJson(
                    ex, 200,
                    mapOf(
                        "commit" to id,
                        "diff" to textDiff(prev, log[id - 1].second),
                    ),
                )
            }

            method == "POST" && segments.size == 2 && segments[1] == "checkout" -> mutate(ex, mapOf("module" to name)) { body ->
                val id = ((body.map["id"] as? Json.Num)?.v ?: -1L).toInt()
                val log = history[name] ?: error("no history for $name")
                if (id < 1 || id > log.size) error("no commit $id")
                val m = workspace.load(log[id - 1].second)
                mapOf("checkedOut" to id, "revision" to m.revision)
            }

            method == "POST" && segments.size == 2 && segments[1] == "test" -> {
                val m = workspace.module(name)
                if (m.classes.isEmpty()) {
                    respondJson(ex, 409, mapOf("error" to "module has errors; fix diagnostics before running tests"))
                } else {
                    val runner = TestRunner(workspace.tableOf(name), m.hostClassName)
                    val results = runner.runTests(m.classes)
                    respondJson(
                        ex, 200,
                        mapOf(
                            "total" to results.size,
                            "passed" to results.count { it.passed },
                            "results" to results.map {
                                mapOf(
                                    "test" to it.testNodeId.value,
                                    "name" to it.name,
                                    "passed" to it.passed,
                                    "error" to it.error,
                                )
                            },
                        ),
                    )
                }
            }

            else -> respondJson(ex, 404, mapOf("error" to "not found: $method $path"))
        }
    }

    // ---------- 编辑操作分发（design §4.5） ----------

    private fun applyEdit(t: NodeTable, body: Json.Obj): Any? {
        val op = (body.map["op"] as? Json.Str)?.v ?: error("edit requires 'op'")
        fun id(key: String): NodeId = NodeId(long(body, key))
        fun index(): Int = body.map["index"]?.let { (it as Json.Num).v.toInt() } ?: 0
        fun kind(): Kind = Kind.entries.firstOrNull {
            it.name == (body.map["kind"] as? Json.Str)?.v
        } ?: error("unknown kind ${(body.map["kind"] as? Json.Str)?.v}")
        fun fields(): Map<String, FieldValue> {
            val obj = body.map["fields"] as? Json.Obj ?: return emptyMap()
            val out = HashMap<String, FieldValue>()
            obj.map.forEach { (k, v) ->
                out[k] = when (v) {
                    is Json.Str -> FieldValue.Str(v.v)
                    is Json.Bool -> FieldValue.Bool(v.v)
                    is Json.Num -> FieldValue.Int(v.v)
                    is Json.Obj -> (v.map["r"] as? Json.Str)?.let { FieldValue.Ref(NodeId(it.v.toLong())) }
                        ?: error("field object must be {\"r\": id}")
                    else -> error("unsupported field value")
                }
            }
            return out
        }
        return when (op) {
            "create" -> t.create(kind(), fields()).value
            "insert" -> t.insert(id("parent"), index(), kind(), fields()).value
            "attach" -> t.attach(id("id"), id("parent"), index()).map { it.value }
            "replace" -> {
                val kids = (body.map["kids"] as? Json.Arr)?.items
                    ?.map { NodeId((it as Json.Str).v.toLong()) }
                t.replace(id("id"), fields().ifEmpty { null }, kids).map { it.value }
            }
            "move" -> t.move(id("id"), id("newParent"), index()).map { it.value }
            "delete" -> t.delete(id("id")).map { it.value }
            else -> error("unknown edit op '$op'")
        }
    }

    // ---------- SSE ----------

    private fun sse(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        sseClients += ex
        broadcast("""{"type":"hello","version":"${Loongd.VERSION}"}""")
    }

    private fun broadcast(payload: String) {
        val dead = ArrayList<HttpExchange>()
        synchronized(sseLock) {
            for (client in sseClients) {
                try {
                    val out = client.responseBody
                    out.write("data: $payload\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                } catch (e: Exception) {
                    dead += client
                }
            }
            sseClients.removeAll(dead.toSet())
        }
    }

    // ---------- 变更与持久化 ----------

    /** 变更操作进单写者队列串行执行；完成后广播（extra 用于补充事件元数据如模块名）。 */
    private fun mutate(
        ex: HttpExchange,
        extra: Map<String, Any?> = emptyMap(),
        fn: (Json.Obj) -> Map<String, Any?>,
    ) {
        val body = Json.parse(ex.requestBody.readBytes().decodeToString()) as? Json.Obj
            ?: error("request body must be a JSON object")
        val future = mutations.submit<Map<String, Any?>> { fn(body) }
        val result = future.get()
        broadcast(Json.write(result + extra + ("type" to "mutation")))
        respondJson(ex, 200, result)
    }

    private fun noteMutation() {
        mutationsSincePersist++
        if (mutationsSincePersist >= PERSIST_EVERY) {
            mutationsSincePersist = 0
            persist()
        }
    }

    private fun persist() {
        runCatching {
            storage.save(WorkspaceSnapshot(workspace.snapshotModules(), workspace.watermark()))
        }
    }

    // ---------- 序列化辅助 ----------

    private fun diagnosticsJson(diags: List<dev.loong.engine.diagnostics.Diagnostic>) =
        diags.map { mapOf("code" to it.code, "message" to it.message, "node" to it.nodeId.value) }

    private fun nodeJson(t: NodeTable, id: NodeId): Map<String, Any?> {
        val n = t[id]
        return mapOf(
            "id" to n.id.value,
            "kind" to n.kind.name,
            "fields" to n.fields.entries.sortedBy { it.key }.associate { (k, v) -> k to v.toString() },
            "kids" to n.kids.map { it.value },
            "parent" to n.parent.value,
        )
    }

    private fun bodyString(body: Json.Obj, key: String): String =
        (body.map[key] as? Json.Str)?.v ?: error("missing string field '$key'")

    private fun long(body: Json.Obj, key: String): Long =
        when (val v = body.map[key]) {
            is Json.Num -> v.v
            is Json.Str -> v.v.toLongOrNull() ?: error("bad id '$key'")
            else -> error("missing numeric field '$key'")
        }

    /** Loong Console：零依赖单页应用，随 daemon 分发。 */
    private fun serveConsole(ex: HttpExchange) {
        val bytes = javaClass.getResourceAsStream("/console/index.html")?.readBytes()
            ?: return respondJson(ex, 404, mapOf("error" to "console ui not packaged"))
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 行级文本 diff（+/- 形态），供节点级审查的轻量 v1。 */
    private fun textDiff(a: String, b: String): List<String> {
        val al = a.split(CharArray(1) { 10.toChar() }.concatToString())
        val bl = b.split(CharArray(1) { 10.toChar() }.concatToString())
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < al.size || j < bl.size) {
            when {
                i < al.size && j < bl.size && al[i] == bl[j] -> {
                    i++; j++
                }
                j < bl.size && (i >= al.size || !al.contains(bl[j])) -> {
                    out += "+ " + bl[j]
                    j++
                }
                else -> {
                    out += "- " + al[i]
                    i++
                }
            }
        }
        return out
    }

    private fun respondJson(ex: HttpExchange, status: Int, payload: Any?) {
        val bytes = Json.write(payload).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondText(ex: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    class Review(val id: Long, val module: String, val reason: String, val nodeIds: List<Long>) {
        @Volatile
        var approved: Boolean? = null
    }

    companion object {
        private const val PERSIST_EVERY = 10
    }
}
