package dev.loong.cli

import dev.loong.engine.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * daemon 控制面（ADR-0006）：端口/发现/生命周期。
 *
 * - **动态端口**：daemon 绑定随机空闲端口，端点写入 `<项目目录>/loongd.json`
 *   （{port, pid, version}）。多项目 = 多 daemon，端口互不冲突；
 * - **ensure 语义**：任何入口（loong-up / loong-mcp）先探活复用，未运行才拉起——
 *   用户与 agent 谁先谁后都不会起出第二个 daemon；
 * - **生命周期**：daemon 常驻（agent 断开不退出），`loong stop` 经 /shutdown
 *   优雅停止；残留端点文件由下次探活判定失效并覆盖。
 */
object DaemonCtl {

    data class Endpoint(val port: Int, val pid: Long) {
        val baseUrl: String get() = "http://127.0.0.1:$port"
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build()
    }

    fun endpointFile(projectDir: Path): Path = projectDir.resolve("loongd.json")

    fun daemonLog(projectDir: Path): Path = projectDir.resolve("daemon.log")

    /** 读端点文件（不探活）。文件缺失、JSON 损坏返回 null。 */
    fun readEndpoint(projectDir: Path): Endpoint? {
        val f = endpointFile(projectDir)
        if (!Files.isRegularFile(f)) return null
        return runCatching {
            val obj = Json.parse(Files.readString(f, StandardCharsets.UTF_8)) as? Json.Obj ?: return null
            val port = (obj.map["port"] as? Json.Num)?.v?.toInt() ?: return null
            val pid = (obj.map["pid"] as? Json.Num)?.v?.toLong() ?: return null
            Endpoint(port, pid)
        }.getOrNull()
    }

    /** 读端点文件并探活（/health 200）。文件缺失、JSON 损坏、进程已死都返回 null。 */
    fun probe(projectDir: Path): Endpoint? = readEndpoint(projectDir)?.let { alive(it) }

    fun alive(ep: Endpoint): Endpoint? = runCatching {
        val r = http.send(
            HttpRequest.newBuilder(URI("${ep.baseUrl}/health"))
                .timeout(Duration.ofMillis(800)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() == 200) ep else null
    }.getOrNull()

    fun writeEndpoint(projectDir: Path, ep: Endpoint) {
        Files.createDirectories(projectDir)
        Files.writeString(
            endpointFile(projectDir),
            Json.write(
                mapOf(
                    "port" to ep.port,
                    "pid" to ep.pid,
                    "version" to LoongCliVersion.VERSION,
                ),
            ),
            StandardCharsets.UTF_8,
        )
    }

    // ---------- 本机 daemon 索引（loong list 用） ----------

    /** ~/.loong/daemons/：跨项目的 daemon 运行索引（主目录仅存跨项目信息，ADR-0006）。 */
    private fun daemonsDir(): Path =
        Path.of(System.getProperty("user.home"), ".loong", "daemons")

    /** daemon 启动时登记本机索引；文件名即 pid，天然无冲突。 */
    fun register(projectDir: Path, ep: Endpoint) {
        val dir = daemonsDir().also { Files.createDirectories(it) }
        Files.writeString(
            dir.resolve("${ep.pid}.json"),
            Json.write(
                mapOf(
                    "projectDir" to projectDir.toAbsolutePath().toString(),
                    "port" to ep.port,
                    "pid" to ep.pid,
                ),
            ),
            StandardCharsets.UTF_8,
        )
    }

    /** daemon 退出时（shutdown hook / stop）除名。 */
    fun unregister(pid: Long) {
        runCatching { Files.deleteIfExists(daemonsDir().resolve("$pid.json")) }
    }

    data class DaemonInfo(val pid: Long, val port: Int, val projectDir: String)

    /**
     * 列出本机运行中的 daemon：读索引逐个探活，顺带清理已死进程的残留登记
     * （强杀/崩溃不会走 hook，这里兜底）。
     */
    fun listRunning(): List<DaemonInfo> {
        val dir = daemonsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        val result = ArrayList<DaemonInfo>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.forEach { f ->
                val info = runCatching {
                    val obj = Json.parse(Files.readString(f, StandardCharsets.UTF_8)) as? Json.Obj
                        ?: return@forEach
                    DaemonInfo(
                        pid = (obj.map["pid"] as? Json.Num)?.v?.toLong() ?: return@forEach,
                        port = (obj.map["port"] as? Json.Num)?.v?.toInt() ?: return@forEach,
                        projectDir = (obj.map["projectDir"] as? Json.Str)?.v ?: return@forEach,
                    )
                }.getOrNull()
                when {
                    info == null -> runCatching { Files.deleteIfExists(f) } // 损坏登记：清理
                    alive(Endpoint(info.port, info.pid)) != null -> result.add(info)
                    else -> runCatching { Files.deleteIfExists(f) } // 进程已死：清理
                }
            }
        }
        return result
    }

    /**
     * 确保 daemon 在运行；未运行则以分离进程拉起（`Launch up` 路径，
     * 输出追加到 <项目目录>/daemon.log），等待 /health 就绪后返回端点。
     * 拉起的 daemon 不随本进程退出——生命周期由 loong stop 管理。
     */
    fun ensureDetached(projectDir: Path): Endpoint {
        probe(projectDir)?.let { return it }

        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val cp = System.getProperty("java.class.path")
        val dir = projectDir.toAbsolutePath().toString()
        // java -jar 时 classpath 就是 jar 本身；classpath 模式（gradle run/IDE）用 -cp
        val cmd = if (cp.contains(File.pathSeparator)) {
            arrayOf(javaBin, "-cp", cp, "dev.loong.cli.LaunchKt", "up", "--project", dir)
        } else {
            arrayOf(javaBin, "-jar", cp, "up", "--project", dir)
        }
        Files.createDirectories(projectDir)
        ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(daemonLog(projectDir).toFile()))
            .start()

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            probe(projectDir)?.let { return it }
            Thread.sleep(200)
        }
        error("daemon 未能在 20 秒内就绪，日志见 ${daemonLog(projectDir).toAbsolutePath()}")
    }

    /** 优雅停止：POST /shutdown → 等待退出 → 兜底强杀 → 清理端点与索引。返回是否曾在运行。 */
    fun stop(projectDir: Path): Boolean {
        val ep = readEndpoint(projectDir)
        if (ep == null || alive(ep) == null) {
            // 不在运行：清掉可能残留的端点文件与索引登记即可
            runCatching { Files.deleteIfExists(endpointFile(projectDir)) }
            ep?.let { unregister(it.pid) }
            return false
        }
        runCatching {
            http.send(
                HttpRequest.newBuilder(URI("${ep.baseUrl}/shutdown"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        // 等优雅退出（shutdown hook 落盘需要一点时间）
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (alive(ep) == null) break
            Thread.sleep(200)
        }
        // 兜底：仍存活则强杀（正常不应走到这里）
        if (alive(ep) != null) {
            ProcessHandle.of(ep.pid).ifPresent { it.destroyForcibly() }
            Thread.sleep(300)
        }
        runCatching { Files.deleteIfExists(endpointFile(projectDir)) }
        return true
    }
}

/** CLI 侧统一版本号（端点文件记录用）。 */
object LoongCliVersion {
    const val VERSION = "0.1.0"
}
