package dev.loong.daemon.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.loong.daemon.Loongd
import dev.loong.daemon.storage.H2Storage
import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity
import dev.loong.engine.json.Json
import dev.loong.engine.rules.FieldSchema
import dev.loong.engine.storage.LoongastCodec
import dev.loong.engine.storage.WorkspaceSnapshot
import dev.loong.engine.testrunner.TestRunner
import dev.loong.engine.text.Renderer
import dev.loong.engine.workspace.Workspace
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

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

    private val workspace = Workspace(
        // external java 声明的编译期反射核对：类/方法/描述符对不上当场报 E_EXTERNAL_*，
        // 而不是运行期 NoSuchMethodError（loader 含 jvm-deps/libs 三方 jar）
        extraChecks = { table, root ->
            runCatching {
                dev.loong.engine.rules.ExternalVerifier.verify(table, root, jvmDeps.loader())
            }.getOrDefault(emptyList())
        },
    )
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

    /** JVM 三方依赖（jvm-deps.json + libs/）：java_class_info / 测试 / 构建共用其 classpath。 */
    private val jvmDeps = JvmDeps(dbPath.parent)

    private val reviewLock = Object()
    private val reviews = LinkedHashMap<Long, Review>()
    private var nextReviewId = 1L

    // 链接脏标记：必须先于 init 块声明——Kotlin 按声明序初始化，init 里的
    // relinkAll 会触碰它（曾因声明在后导致启动接线 NPE 被静默吞掉）。
    private val linkDirty = LinkedHashSet<String>()

    init {
        // 崩溃恢复：启动时装载上次快照并重算（系统模块不持久化，恢复后再补装）
        runCatching { storage.load() }.getOrNull()?.let {
            runCatching { workspace.restore(it.modules) }
        }
        // 空项目初始化：恢复后仍无任何模块 → 装载缺省的 main 与 example
        DefaultModules.seedIfEmpty(workspace)
        // 系统模块（loong.core/io/net，daemon 内置资源）装载并接线 depends 解析。
        // 链接失败不能带崩启动，但必须留下痕迹——静默吞掉会把可修的接线问题
        // 变成「模块永远 E_UNKNOWN_NAME」的谜团。
        SystemModules.ensureLoaded(workspace)
        runCatching { relinkAll() }.onFailure { e ->
            System.err.println("loongd: startup relink failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
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
        jvmDeps.close()
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
                    // 带进程标识：本机多 daemon 并存时可定位连的是哪一个
                    respondJson(
                        ex, 200,
                        mapOf(
                            "ok" to true,
                            "version" to Loongd.VERSION,
                            "pid" to ProcessHandle.current().pid(),
                            "port" to port(),
                            "projectDir" to dbRoot.parent.toString(),
                        ),
                    )

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

                // 系统模块清单（内置标准库；DELETE/替换均被拒绝，UI 据此打标）
                method == "GET" && path == "/system-modules" ->
                    respondJson(ex, 200, mapOf("modules" to SystemModules.names()))

                // JVM 三方依赖：配置（jvm-deps.json）+ 解析状态 + 显式重解析
                method == "GET" && path == "/jvmdeps" -> respondJson(ex, 200, jvmDepsJson())

                method == "POST" && path == "/jvmdeps" -> {
                    val body = Json.parse(ex.requestBody.readBytes().decodeToString()) as? Json.Obj
                        ?: error("request body must be a JSON object")
                    val coord = bodyString(body, "coordinate")
                    val r = jvmDeps.add(coord)
                    broadcast(Json.write(mapOf("type" to "jvmdeps") + jvmDepsJson()))
                    respondJson(ex, if (r.ok) 200 else 207, jvmDepsJson(r))
                }

                method == "POST" && path == "/jvmdeps/resolve" -> {
                    val r = jvmDeps.resolve()
                    respondJson(ex, if (r.ok) 200 else 207, jvmDepsJson(r))
                }

                // JVM 类反射查询（external 声明前的「查」）：?fqn=java.nio.file.Files
                method == "GET" && path == "/jvm/class" -> {
                    val fqn = ex.requestURI.rawQuery
                        ?.split("fqn=")?.getOrNull(1)?.substringBefore('&')
                        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                        ?: error("query parameter 'fqn' required")
                    respondJson(ex, 200, jvmClassInfo(fqn))
                }

                path.startsWith("/jvmdeps/") && method == "DELETE" -> {
                    val coord = path.removePrefix("/jvmdeps/").substringBefore('?')
                    val r = jvmDeps.remove(coord)
                    broadcast(Json.write(mapOf("type" to "jvmdeps") + jvmDepsJson()))
                    respondJson(ex, if (r.ok) 200 else 207, jvmDepsJson(r))
                }

                method == "POST" && path == "/modules" -> mutate(ex) { body ->
                    if (body.map.containsKey("trees")) {
                        // 结构化创建（agent 主路径）：{module, trees} —— 无源码文本，
                        // 树先校验后构建，任何形态错误整批拒绝零副作用
                        val moduleName = normalizeModuleName(bodyString(body, "module"))
                        check(!SystemModules.isSystem(moduleName)) {
                            "system module '$moduleName' is built-in and cannot be replaced"
                        }
                        val trees = (body.map["trees"] as? Json.Arr)?.items
                            ?.map { it as? Json.Obj ?: error("each tree must be an object") }
                            ?: emptyList()
                        val m = loadStructured(moduleName, trees)
                        relinkAll()
                        noteMutation()
                        mapOf(
                            "module" to m.name,
                            "revision" to m.revision,
                            "diagnostics" to diagnosticsJson(m.diagnostics),
                        )
                    } else {
                        // 源码文本形态（人类 / Web UI / CLI 文件导入；兼容保留）
                        val src = bodyString(body, "source")
                        val declared = SystemModules.declaredName(src)
                        check(declared == null || !SystemModules.isSystem(declared)) {
                            "system module '$declared' is built-in and cannot be replaced"
                        }
                        val m = workspace.load(src)
                        relinkAll()
                        noteMutation()
                        mapOf(
                            "module" to m.name,
                            "revision" to m.revision,
                            "diagnostics" to diagnosticsJson(m.diagnostics),
                        )
                    }
                }

                method == "GET" && path == "/events" -> {
                    sse(ex)
                    return
                }

                method == "POST" && path == "/persist" -> mutate(ex) { _ ->
                    persist()
                    mapOf("ok" to true, "watermark" to workspace.watermark())
                }

                // 项目构建（design §7 打包）：产物落项目目录 build/（jar + class 中间产物）
                method == "POST" && path == "/build" -> {
                    try {
                        val result = buildProject()
                        respondJson(ex, 200, result)
                        broadcast(Json.write(result + ("type" to "build")))
                    } catch (e: BuildRefused) {
                        respondJson(
                            ex, 409,
                            mapOf(
                                "error" to "modules have errors; fix diagnostics before building",
                                "modules" to e.modules.map { (name, diags) ->
                                    mapOf(
                                        "module" to name,
                                        "errors" to diags.map {
                                            mapOf("code" to it.code, "message" to it.message, "node" to it.nodeId.value)
                                        },
                                    )
                                },
                            ),
                        )
                    }
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
            // 整模块卸载（与 POST /modules 对称）：无请求体，走单写者队列并落盘
            method == "DELETE" && segments.size == 1 -> {
                if (SystemModules.isSystem(name)) {
                    respondJson(
                        ex, 409,
                        mapOf("error" to "system module '$name' is built-in and cannot be deleted"),
                    )
                } else {
                    val result = mutations.submit<Map<String, Any?>> {
                        workspace.remove(name) ?: error("module '$name' is not loaded")
                        relinkAll()
                        noteMutation()
                        mapOf("deleted" to name)
                    }.get()
                    broadcast(Json.write(result + mapOf("module" to name, "type" to "mutation")))
                    respondJson(ex, 200, result)
                }
            }

            // 原始 AST：.loongast 规范导出（ADR-0002，与持久化字节一致）
            method == "GET" && segments.size == 2 && segments[1] == "ast" -> {
                val text = LoongastCodec.serialize(workspace.tableOf(name), workspace.rootOf(name))
                respondText(ex, 200, text, "application/json")
            }

            method == "GET" && segments.size == 2 && segments[1] == "nodes" -> {
                val query = (ex.requestURI.rawQuery ?: "").split('&')
                val kind = query.firstOrNull { it.startsWith("kind=") }?.removePrefix("kind=")
                val brokenOnly = query.any { it == "broken=true" }
                val t = workspace.tableOf(name)
                val nodes = if (brokenOnly) {
                    // 结构异常审计：kids 数量/槽位违例的节点点名（配 delete_node 修复）
                    dev.loong.engine.rules.StructuralInvariants.check(t).map { (id, problem) ->
                        mapOf("id" to id.value, "kind" to t[id].kind.name, "problem" to problem)
                    }
                } else {
                    t.find(kind?.let { k -> Kind.entries.firstOrNull { it.name == k } })
                        .map { mapOf("id" to it.value, "kind" to t[it].kind.name) }
                }
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

            // 节点级渲染（Web UI 原始 AST 树「导出成代码」）：任意节点子树 → 代码文本
            method == "GET" && segments.size == 4 && segments[1] == "node" && segments[3] == "render" -> {
                val id = segments[2].toLongOrNull() ?: error("bad node id")
                val t = workspace.tableOf(name)
                t.getOrNull(NodeId(id)) ?: error("node $id does not exist in module '$name'")
                respondText(ex, 200, Renderer(t).renderNode(NodeId(id)))
            }

            method == "POST" && segments.size == 2 && segments[1] == "edit" -> mutate(ex, mapOf("module" to name)) { body ->
                val depsBefore = dependsNames(name)
                val outcome = workspace.edit(name) { t -> applyEdit(t, body) }
                // depends 声明变化 → 自身重链；被本模块依赖的目标变了 → 消费方重链
                markLinkDirty(name, selfDirty = dependsNames(name) != depsBefore)
                relinkDirty()
                noteMutation()
                // relink 后诊断可能变化（如消费方补 depends 后 E_UNKNOWN_NAME 消除），回传重算结果
                val fresh = workspace.module(name)
                mapOf(
                    "value" to outcome.value,
                    "revision" to fresh.revision,
                    "diagnostics" to diagnosticsJson(fresh.diagnostics),
                )
            }

            // 嵌套节点树一次写入（粗粒度）：给 parent/index = 插入（tree 或 trees 多棵兄弟）；
            // 给 id = 整棵子树原位重写（根 ID 保留）。树形态与 get_node 同构，先校验后构建。
            method == "POST" && segments.size == 2 && segments[1] == "tree" -> mutate(ex, mapOf("module" to name)) { body ->
                val depsBefore = dependsNames(name)
                val op = run {
                    val merged = linkedMapOf<String, Json.Value>()
                    merged["op"] = Json.Str(if (body.map.containsKey("id")) "replace_tree" else "insert_tree")
                    merged.putAll(body.map)
                    Json.Obj(merged)
                }
                val ids = workspace.edit(name) { t -> applyTreeOp(t, op) }.value
                markLinkDirty(name, selfDirty = dependsNames(name) != depsBefore)
                relinkDirty()
                noteMutation()
                val fresh = workspace.module(name)
                mapOf(
                    "ids" to ids,
                    "revision" to fresh.revision,
                    "diagnostics" to diagnosticsJson(fresh.diagnostics),
                )
            }

            // 批量编辑：一次提交多个树/节点操作，逐项执行逐项报告（单项失败不影响其余）。
            method == "POST" && segments.size == 2 && segments[1] == "batch" -> mutate(ex, mapOf("module" to name)) { body ->
                val ops = (body.map["ops"] as? Json.Arr)?.items
                    ?.map { it as? Json.Obj ?: error("each op must be an object") }
                    ?: error("batch requires 'ops' array")
                val depsBefore = dependsNames(name)
                val outcome = workspace.edit(name) { t ->
                    ops.mapIndexed { i, op ->
                        try {
                            mapOf("i" to i, "ok" to true, "value" to applyTreeOp(t, op))
                        } catch (e: Exception) {
                            mapOf("i" to i, "ok" to false, "error" to (e.message ?: e.toString()))
                        }
                    }
                }
                val results = outcome.value
                markLinkDirty(name, selfDirty = dependsNames(name) != depsBefore)
                relinkDirty()
                noteMutation()
                val fresh = workspace.module(name)
                mapOf(
                    "results" to results,
                    "applied" to results.count { it["ok"] == true },
                    "failed" to results.count { it["ok"] != true },
                    "revision" to fresh.revision,
                    "diagnostics" to diagnosticsJson(fresh.diagnostics),
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

            // 未提交改动：当前渲染 vs 最近一次 commit（无 commit 则 vs 空基线）
            method == "GET" && segments.size == 3 && segments[1] == "diff" && segments[2] == "uncommitted" -> {
                val log = history[name] ?: emptyList()
                val prev = log.lastOrNull()?.second ?: ""
                val cur = Renderer(workspace.tableOf(name)).render(workspace.rootOf(name))
                respondJson(
                    ex, 200,
                    mapOf(
                        "base" to (log.size.takeIf { it > 0 }?.let { "commit $it" } ?: "initial"),
                        "diff" to textDiff(prev, cur),
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
                workspace.load(log[id - 1].second)
                relinkAll()
                mapOf("checkedOut" to id, "revision" to workspace.module(name).revision)
            }

            method == "POST" && segments.size == 2 && segments[1] == "test" -> {
                val m = workspace.module(name)
                if (m.classes.isEmpty()) {
                    respondJson(ex, 409, mapOf("error" to "module has errors; fix diagnostics before running tests"))
                } else {
                    // 跨模块测试：依赖闭包（工作区内含系统模块）字节码 + 注册表 classes 目录 + 三方 jar
                    val runner = TestRunner(workspace.tableOf(name), m.hostClassName)
                    val results = runner.runTests(
                        m.classes,
                        deps = depClasses(name),
                        extraClasspath = workspace.classpathDirs(name) + jvmDeps.jars(),
                    )
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
        fun fieldsObj(): Json.Obj? = body.map["fields"] as? Json.Obj
        return when (op) {
            "create" -> t.create(kind(), fieldsOf(t, kind(), fieldsObj(), "fields", requireAll = true)).value
            "insert" -> t.insert(id("parent"), index(), kind(), fieldsOf(t, kind(), fieldsObj(), "fields", requireAll = true)).value
            "attach" -> t.attach(id("id"), id("parent"), index()).map { it.value }
            "replace" -> {
                val kids = (body.map["kids"] as? Json.Arr)?.items
                    ?.map { NodeId((it as Json.Str).v.toLong()) }
                // fields 为合并语义（与 replace_node 工具描述一致：可只给要改的键）；
                // 给 kids 则整组子节点替换（NodeTable.replace 自身语义）。
                // 字段按目标节点的 kind 校验：未知字段拒绝；合并后仍缺必填/类型不符也拒绝。
                val target = id("id")
                val existing = t.getOrNull(target) ?: error("node $target does not exist in this module")
                val f = fieldsOf(t, existing.kind, fieldsObj(), "fields", requireAll = false)
                val merged: Map<String, FieldValue>? = if (f.isEmpty()) null else existing.fields + f
                if (merged != null) {
                    FieldSchema.problemsOf(existing.kind, merged).firstOrNull()?.let { error("fields: $it") }
                }
                t.replace(target, merged, kids).map { it.value }
            }
            "move" -> t.move(id("id"), id("newParent"), index()).map { it.value }
            "delete" -> t.delete(id("id")).map { it.value }
            else -> error("unknown edit op '$op'")
        }
    }

    // ---------- 嵌套节点树（粗粒度写入，读写与 get_node 同构） ----------

    /** 树形态错误：附节点路径（如 trees[0].kids[1].fields.x），整批拒绝零副作用。 */
    private class TreeShapeException(path: String, reason: String) :
        RuntimeException("$path: $reason")

    /**
     * 纯校验（不触碰节点表）：kind 合法、fields（名称/值形态/必填，见 [fieldsOf]）、
     * **结构不变量**（kids 数量与容器槽位，与重算门禁/文档同一张表）、kids 递归。
     * 任何失败抛 [TreeShapeException]。构建期（[buildTree]）在此之后执行，形态上
     * 不可能再失败——两阶段保证「校验失败零副作用」（不会留下半建的游离子树）。
     */
    private fun validateTree(t: NodeTable, node: Json.Obj, path: String) {
        val kindName = (node.map["kind"] as? Json.Str)?.v
            ?: throw TreeShapeException(path, "missing 'kind'")
        val kind = Kind.entries.firstOrNull { it.name == kindName }
            ?: throw TreeShapeException(path, "unknown kind '$kindName'")
        fieldsOf(t, kind, node.map["fields"] as? Json.Obj, path, requireAll = true)
        val kidObjs = (node.map["kids"] as? Json.Arr)?.items
            ?: emptyList()
        kidObjs.forEachIndexed { i, kid ->
            val obj = kid as? Json.Obj
                ?: throw TreeShapeException("$path.kids[$i]", "kid must be a node object")
        }
        val litKind = (node.map["fields"] as? Json.Obj)
            ?.map?.get("lit_kind")?.let { (it as? Json.Str)?.v }
        val kidKinds = kidObjs.map { kid ->
            val kn = ((kid as Json.Obj).map["kind"] as? Json.Str)?.v
                ?: throw TreeShapeException(path, "kid must have a 'kind'")
            Kind.entries.firstOrNull { it.name == kn }
                ?: throw TreeShapeException(path, "unknown kid kind '$kn'")
        }
        dev.loong.engine.rules.StructuralInvariants.problemOf(kind, kidKinds, litKind)
            ?.let { throw TreeShapeException(path, it) }
        kidObjs.forEachIndexed { i, kid ->
            validateTree(t, kid as Json.Obj, "$path.kids[$i]")
        }
    }

    /**
     * JSON fields → FieldValue，按 [FieldSchema] 校验与转换（树写入与单节点写入共用）：
     * - 未知字段 → 拒绝并列出该 kind 的可用字段（根治「写了 name 而非 written、被静默忽略」）；
     * - 值按规格转换：str 规格接受 string/number/boolean 并取其文本——Literal.value 写 7 与
     *   "7" 等价（存成 Int 会让 Renderer/codegen 读到空串）；bool 规格接受 boolean 或
     *   "true"/"false"；ref 规格接受 {"r": id} 或裸 id，且必须指向目标表现存节点；
     * - [requireAll] 时缺必填 → 拒绝（create/insert/树节点）；replace 的合并语义由调用方合并后再查。
     */
    private fun fieldsOf(
        t: NodeTable,
        kind: Kind,
        obj: Json.Obj?,
        path: String,
        requireAll: Boolean,
    ): Map<String, FieldValue> {
        val given = obj?.map ?: emptyMap<String, Json.Value>()
        FieldSchema.unknownFields(kind, given.keys).firstOrNull()?.let { bad ->
            throw TreeShapeException(
                "$path.fields.$bad",
                "unknown field '$bad' for $kind; accepted: ${FieldSchema.describe(kind)}",
            )
        }
        val out = LinkedHashMap<String, FieldValue>()
        for ((k, v) in given) {
            val spec = FieldSchema.specOf(kind, k) ?: continue
            out[k] = when (spec.type) {
                FieldSchema.Type.Str -> when (v) {
                    is Json.Str -> FieldValue.Str(v.v)
                    is Json.Num -> FieldValue.Str(v.v.toString())
                    is Json.Bool -> FieldValue.Str(v.v.toString())
                    else -> throw TreeShapeException("$path.fields.$k", "$kind.$k must be a string")
                }
                FieldSchema.Type.Bool -> when {
                    v is Json.Bool -> FieldValue.Bool(v.v)
                    v is Json.Str && v.v == "true" -> FieldValue.Bool(true)
                    v is Json.Str && v.v == "false" -> FieldValue.Bool(false)
                    else -> throw TreeShapeException("$path.fields.$k", "$kind.$k must be a boolean")
                }
                FieldSchema.Type.Ref -> {
                    val r = when (v) {
                        is Json.Num -> NodeId(v.v)
                        is Json.Obj -> (v.map["r"] as? Json.Num)?.let { NodeId(it.v) }
                            ?: (v.map["r"] as? Json.Str)?.let { NodeId(it.v.toLong()) }
                            ?: throw TreeShapeException("$path.fields.$k", "$kind.$k must be {\"r\": <id>}")
                        else -> throw TreeShapeException("$path.fields.$k", "$kind.$k must be {\"r\": <id>}")
                    }
                    if (t.getOrNull(r) == null) {
                        throw TreeShapeException("$path.fields.$k", "ref ${r.value} does not exist in this module")
                    }
                    FieldValue.Ref(r)
                }
            }
        }
        if (requireAll) {
            FieldSchema.problemsOf(kind, out).firstOrNull()?.let { throw TreeShapeException(path, it) }
        }
        return out
    }

    /** 校验后构建：自底向上 create 游离子树（create 收养游离 kids 的契约逐层成立）。 */
    private fun buildTree(t: NodeTable, node: Json.Obj): NodeId {
        val kids = ((node.map["kids"] as? Json.Arr)?.items ?: emptyList())
            .map { buildTree(t, it as Json.Obj) }
        val kind = kindOf(node)
        return t.create(kind, fieldsOf(t, kind, node.map["fields"] as? Json.Obj, "", requireAll = false), kids)
    }

    private fun kindOf(node: Json.Obj): Kind =
        Kind.entries.first { it.name == (node.map["kind"] as Json.Str).v }

    /** 树（或树数组）统一取为列表。 */
    private fun treeListOf(body: Json.Obj): List<Json.Obj> {
        val single = body.map["tree"] as? Json.Obj
        val many = (body.map["trees"] as? Json.Arr)?.items?.map { it as Json.Obj }
        val list = many ?: single?.let { listOf(it) } ?: error("missing 'tree' or 'trees'")
        if (list.isEmpty()) error("tree list is empty")
        return list
    }

    /** 结构化建模块：新表 + Module 根 + 声明树（先全部校验，后构建挂接）。 */
    private fun loadStructured(moduleName: String, trees: List<Json.Obj>): dev.loong.engine.workspace.ModuleState {
        val table = NodeTable()
        val root = table.create(Kind.Module, mapOf("name" to FieldValue.Str(moduleName)))
        trees.forEachIndexed { i, tr -> validateTree(table, tr, "trees[$i]") }
        trees.forEach { tr ->
            val built = buildTree(table, tr)
            table.attach(built, root, table[root].kids.size)
        }
        return workspace.loadTable(table, root)
    }

    /** 模块名校验与规范化（dotted 反向域名 + 可选 /vN；省略版本补 /v1）。 */
    private fun normalizeModuleName(raw: String): String {
        val seg = "[A-Za-z_][A-Za-z0-9_]*"
        require(Regex("^$seg(\\.$seg)*(\\/v[0-9]+)?$").matches(raw)) {
            "bad module name '$raw' (dotted reverse-domain, optional /vN)"
        }
        return if (raw.contains('/')) raw else "$raw/v1"
    }

    /**
     * 树/节点批量操作分发（/batch 的单项执行；/tree 的整批执行）。
     * insert_tree / replace_tree 为树操作（先整棵校验后构建）；move / delete /
     * replace 复用 [applyEdit]。
     */
    private fun applyTreeOp(t: NodeTable, op: Json.Obj): Any? {
        val kind = (op.map["op"] as? Json.Str)?.v ?: error("op requires 'op'")
        return when (kind) {
            "insert_tree" -> {
                val trees = treeListOf(op)
                trees.forEachIndexed { i, tr -> validateTree(t, tr, "trees[$i]") }
                val parent = op.map["parent"]?.let { NodeId(long(op, "parent")) }
                    ?: t.find(Kind.Module).first()
                val index = op.map["index"]?.let { (it as Json.Num).v.toInt() }
                    ?: t[parent].kids.size
                // index + i：多棵树保持给出顺序（attach 的 coerceIn 兜底越界）
                trees.mapIndexed { i, tr ->
                    val root = buildTree(t, tr)
                    t.attach(root, parent, index + i)
                    root.value
                }
            }
            "replace_tree" -> {
                val target = NodeId(long(op, "id"))
                val tree = op.map["tree"] as? Json.Obj ?: error("replace_tree requires 'tree'")
                validateTree(t, tree, "tree")
                t.getOrNull(target) ?: error("node $target does not exist in this module")
                val kind = kindOf(tree)
                val kids = ((tree.map["kids"] as? Json.Arr)?.items ?: emptyList())
                    .map { buildTree(t, it as Json.Obj) }
                // 整棵换新：kind/fields 以 tree 为准（fields 整组替换，不与旧值合并——
                // 过期的解析填充字段随之清除，由名字解析阶段重算）
                t.replace(target, fieldsOf(t, kind, tree.map["fields"] as? Json.Obj, "tree", requireAll = false), kids, kind)
                target.value
            }
            else -> applyEdit(t, op)
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
            // 系统模块不持久化：daemon 升级后资源版即时生效，库中只存项目自身状态
            val modules = workspace.snapshotModules().filterKeys { !SystemModules.isSystem(it) }
            storage.save(WorkspaceSnapshot(modules, workspace.watermark()))
        }
    }

    // ---------- 依赖解析接线（depends 在 daemon 侧真正生效） ----------

    /**
     * 组合依赖源：工作区模块（含系统模块）优先，本地注册表兜底。
     * depends 写短名（loong.io）或全名（loong.io/v1）都能命中工作区——
     * 无版本约束时最低版本 0.0.0 与工作区占位版本匹配。
     */
    private fun depSource(): dev.loong.engine.pkg.DepSource {
        val registry = dev.loong.engine.pkg.LocalRegistry(regRoot)
        fun lookup(name: String): Pair<NodeTable, NodeId>? {
            val key = if (name.contains('/')) name else "$name/v1"
            return runCatching { workspace.tableOf(key) to workspace.rootOf(key) }.getOrNull()
        }
        val workspaceSource = dev.loong.engine.pkg.WorkspaceSource { lookup(it) }
        return object : dev.loong.engine.pkg.DepSource {
            override fun versions(module: String): List<String> =
                if (lookup(module) != null) listOf("0.0.0") else registry.versions(module)

            override fun fetch(module: String, version: String): dev.loong.engine.pkg.FetchedDep =
                if (lookup(module) != null) workspaceSource.fetch(module, version) else registry.fetch(module, version)
        }
    }

    /** 变更后重链全部模块（模块集合变化时用：load/delete/checkout）。 */
    private fun relinkAll() {
        linkDirty.addAll(workspace.moduleNames())
        relinkDirty()
    }

    /** 只重链脏模块：上次合并的依赖接口节点由 Workspace.linkModules 负责清理。 */
    private fun relinkDirty() {
        if (linkDirty.isEmpty()) return
        val todo = linkDirty.toList()
        linkDirty.clear()
        workspace.linkModules(depSource(), todo)
    }

    /** target 变更后标记：依赖它的模块（传递）需重链；depends 声明变化时自身也重链。 */
    private fun markLinkDirty(target: String, selfDirty: Boolean) {
        if (selfDirty) linkDirty.add(target)
        for (n in workspace.moduleNames()) {
            if (n != target && depClosure(n).contains(target)) linkDirty.add(n)
        }
    }

    /** 模块的依赖闭包（工作区键名，含系统模块；不含自身）。 */
    private fun depClosure(name: String): List<String> {
        val seen = LinkedHashSet<String>()
        seen.add(name)
        val queue = ArrayDeque<String>()
        queue.add(name)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val key = if (cur.contains('/')) cur else "$cur/v1"
            if (key !in workspace.moduleNames()) continue
            val t = workspace.tableOf(key)
            for (dep in t.find(Kind.Depends)) {
                val dn = (t[dep].fields["module_name"] as? FieldValue.Str)?.value ?: continue
                if (seen.add(dn)) queue.add(dn)
            }
        }
        seen.remove(name)
        return seen.map { if (it.contains('/')) it else "$it/v1" }
    }

    /** 模块的 depends 声明签（编辑前后对比，判断自身是否需要重链）。 */
    private fun dependsNames(name: String): List<String> =
        workspace.tableOf(name).find(Kind.Depends)
            .mapNotNull { (workspace.tableOf(name)[it].fields["module_name"] as? FieldValue.Str)?.value }
            .sorted()

    /** 依赖闭包（工作区内模块，含系统模块）的字节码合并：跨模块测试的运行时 classpath。 */
    private fun depClasses(name: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (key in depClosure(name)) {
            if (key !in workspace.moduleNames()) continue
            workspace.module(key).classes.forEach { (internal, bytes) ->
                if (internal !in out) out[internal] = bytes
            }
        }
        return out
    }

    // ---------- 项目构建（design §7 打包形态；产物落项目目录） ----------

    /** 构建门禁失败：模块 → Error 诊断列表（路由层转 409 + 明细，与 run_tests 同风格）。 */
    private class BuildRefused(val modules: List<Pair<String, List<Diagnostic>>>) :
        RuntimeException("modules have errors")

    /**
     * 全项目构建：工作区各模块字节码 + runtime shim 打成自包含可运行 jar。
     * 中间产物 `<项目目录>/build/classes/`，jar `<项目目录>/build/<项目名>.jar`。
     */
    private fun buildProject(): Map<String, Any?> {
        val projectDir = dbRoot.parent
        val names = workspace.moduleNames()

        val broken = names.map { it to workspace.module(it).diagnostics.filter { d -> d.severity == Severity.Error } }
            .filter { it.second.isNotEmpty() }
        if (broken.isNotEmpty()) throw BuildRefused(broken)

        val buildDir = projectDir.resolve("build")
        val classesDir = buildDir.resolve("classes")
        var classCount = 0
        for (n in names) {
            workspace.module(n).classes.forEach { (internal, bytes) ->
                val f = classesDir.resolve("$internal.class")
                Files.createDirectories(f.parent)
                Files.write(f, bytes)
                classCount++
            }
        }

        // Main-Class：短名 main 的模块优先，否则首个有 main 函数的模块
        val mainModule = names.firstOrNull { it.substringBefore('/') == "main" }
            ?: names.firstOrNull(::hasMainFunction)
        val mainClass = mainModule?.let { workspace.module(it).hostClassName }

        val runtime = runtimeShimClasses()
        val jarName = projectDir.fileName?.toString()
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() } ?: "loong-app"
        val jar = buildDir.resolve("$jarName.jar")
        Files.createDirectories(buildDir)
        // 三方 jar 复制进 build/libs/ 并以相对 Class-Path 引用（java -jar 从 jar 同目录解析；
        // 不做 fat-jar 合并——保持产物可审计、不破坏签名 jar）
        val libEntries = JvmDeps.copyIntoBuild(jvmDeps.jars(), buildDir)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (mainClass != null) mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
            if (libEntries.isNotEmpty()) {
                mainAttributes[Attributes.Name.CLASS_PATH] = libEntries.joinToString(" ")
            }
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { jos ->
            fun put(internal: String, bytes: ByteArray) {
                jos.putNextEntry(JarEntry("$internal.class"))
                jos.write(bytes)
                jos.closeEntry()
            }

            for (n in names) workspace.module(n).classes.forEach { (internal, bytes) -> put(internal, bytes) }
            runtime.forEach { (internal, bytes) -> put(internal, bytes) }
        }
        return mapOf(
            "jar" to jar.toString(),
            "mainClass" to mainClass,
            "classes" to classCount,
            "runtimeClasses" to runtime.size,
            "jvmLibs" to libEntries,
            "modules" to names,
        )
    }

    /** /jvmdeps 响应体：坐标清单 + 当前生效 jar + 最近一次解析错误。 */
    private fun jvmDepsJson(last: ResolveResult? = null): Map<String, Any?> =
        mapOf(
            "dependencies" to jvmDeps.list(),
            "jars" to jvmDeps.jars().map { it.fileName.toString() },
            "resolved" to (last?.ok ?: jvmDeps.resolved()),
            "tool" to (last?.tool ?: ""),
            "error" to (last?.error ?: ""),
        )

    /**
     * JVM 类的 public API 反射清单（写 external 声明前先查，不靠猜）：
     * 构造器 / 静态方法 / 实例方法，各带 JVM 参数与返回类型；附 loong 类型映射
     * 提示（int→Int32、long→Int…——external 声明里类型必须按此写）。
     */
    private fun jvmClassInfo(fqn: String): Map<String, Any?> {
        val cls = try {
            Class.forName(fqn, false, jvmDeps.loader())
        } catch (e: Throwable) {
            return mapOf(
                "class" to fqn,
                "found" to false,
                "hint" to "not on the classpath: JDK classes resolve out of the box; " +
                    "third-party needs jvm-deps.json (add_jvm_dep) or a jar in libs/",
            )
        }
        fun methodsOf(static: Boolean): List<Map<String, Any?>> =
            cls.methods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) == static }
                .sortedWith(compareBy({ m -> m.name }, { m -> m.parameterCount }))
                .map { m ->
                    mapOf(
                        "name" to m.name,
                        "params" to m.parameterTypes.map { t -> t.simpleName },
                        "returns" to m.returnType.simpleName,
                    )
                }
        return mapOf(
            "class" to fqn,
            "found" to true,
            "constructors" to cls.constructors.sortedBy { it.parameterCount }.map {
                mapOf("params" to it.parameterTypes.map { t -> t.simpleName })
            },
            "staticMethods" to methodsOf(static = true),
            "instanceMethods" to methodsOf(static = false),
            "loongTypes" to LOONG_TYPE_HINTS,
        )
    }

    /** JVM 原始/常用类型 → loong external 声明类型对照（jvmClassInfo 随带，写声明直接照抄）。 */
    private val LOONG_TYPE_HINTS = mapOf(
        "int" to "Int32",
        "long" to "Int",
        "double" to "Float",
        "boolean" to "Bool",
        "char" to "Char",
        "java.lang.String" to "String",
        "void" to "Unit",
        "java.lang.Object" to "Any（类型参数擦除位）",
    )

    /** 模块是否声明了模块级 main 函数（codegen 会为它生成 JVM 入口桥）。 */
    private fun hasMainFunction(name: String): Boolean {
        val t = workspace.tableOf(name)
        return t[workspace.rootOf(name)].kids.any {
            t[it].kind == Kind.Function && (t[it].fields["name"] as? FieldValue.Str)?.value == "main"
        }
    }

    /**
     * runtime shim：classpath 上 dev/loong/runtime 包的全部类字节。shim 是纯 Java
     * 实现（零第三方依赖，仅 JDK），打进构建 jar 后 `java -jar` 即可自包含运行——
     * 不携带任何宿主工具链（如 kotlin-stdlib）的运行时类。
     */
    private fun runtimeShimClasses(): Map<String, ByteArray> = classpathClassesUnder(RUNTIME_PACKAGE)

    /**
     * 收集 classpath 上位于给定包前缀下的全部类字节。
     * 扫描 java.class.path 各 entry（目录 walk / jar 枚举），并叠加 runtime 类自身
     * codeSource 兜底（Gradle 测试 worker 可能经 pathing jar 装载，class.path 不可靠）。
     */
    private fun classpathClassesUnder(vararg prefixes: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        val roots = ArrayList<Path>()
        System.getProperty("java.class.path")?.split(File.pathSeparator)?.forEach {
            if (it.isNotBlank()) roots.add(Path.of(it))
        }
        for (cn in listOf("dev.loong.runtime.Console", "dev.loong.runtime.Variant")) {
            runCatching {
                Class.forName(cn).protectionDomain.codeSource?.location?.let { roots.add(Path.of(it.toURI())) }
            }
        }
        for (root in roots.distinct()) {
            when {
                Files.isDirectory(root) -> {
                    val pkgs = prefixes.map { root.resolve(it) }.filter { Files.isDirectory(it) }
                    if (pkgs.isEmpty()) continue
                    for (pkg in pkgs) {
                        Files.walk(pkg).use { stream ->
                            stream.filter { it.toString().endsWith(".class") }.forEach { f ->
                                val internal = root.relativize(f).toString().replace('\\', '/').removeSuffix(".class")
                                if (seen.add(internal)) out[internal] = Files.readAllBytes(f)
                            }
                        }
                    }
                }

                Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar") ->
                    runCatching { ZipFile(root.toFile()) }.getOrNull()?.use { zip ->
                        zip.entries().asSequence()
                            .filter { e ->
                                !e.isDirectory && e.name.endsWith(".class") &&
                                    prefixes.any { e.name.startsWith(it) }
                            }
                            .forEach { e ->
                                val internal = e.name.removeSuffix(".class")
                                if (seen.add(internal)) out[internal] = zip.getInputStream(e).readBytes()
                            }
                    }
            }
        }
        return out
    }

    // ---------- 序列化辅助 ----------

    private fun diagnosticsJson(diags: List<dev.loong.engine.diagnostics.Diagnostic>) =
        diags.map { mapOf("code" to it.code, "message" to it.message, "node" to it.nodeId.value) }

    /**
     * 节点 JSON（get_node / find_nodes）。fields 值与写入形态同构：str → 字符串、
     * bool → 布尔、int → 数字、ref → {"r": id}——读出的 fields 可原样喂回
     * insert_tree / replace_tree / replace_node。
     */
    private fun nodeJson(t: NodeTable, id: NodeId): Map<String, Any?> {
        val n = t[id]
        return mapOf(
            "id" to n.id.value,
            "kind" to n.kind.name,
            "fields" to n.fields.entries.sortedBy { it.key }.associate { (k, v) -> k to fieldJson(v) },
            "kids" to n.kids.map { it.value },
            "parent" to n.parent.value,
        )
    }

    private fun fieldJson(v: FieldValue): Any = when (v) {
        is FieldValue.Str -> v.value
        is FieldValue.Bool -> v.value
        is FieldValue.Int -> v.value
        is FieldValue.Ref -> mapOf("r" to v.id.value)
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

    private fun respondText(ex: HttpExchange, status: Int, text: String, mime: String = "text/plain") {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "$mime; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    class Review(val id: Long, val module: String, val reason: String, val nodeIds: List<Long>) {
        @Volatile
        var approved: Boolean? = null
    }

    companion object {
        private const val PERSIST_EVERY = 10
        private const val RUNTIME_PACKAGE = "dev/loong/runtime/"
    }
}
