package dev.loong.cli

import dev.loong.daemon.server.LoongdServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `loong up`：daemon 的 ensure 入口（ADR-0006）。
 *
 * - 已在运行 → 打印地址复用并退出（不重复起、不报错）；
 * - 未运行 → 前台启动（Ctrl+C 停止），端口随机分配并写入 loongd.json，
 *   同时登记本机索引（~/.loong/daemons/，`loong list` 据此展示）；
 * - MCP stdio 不在本进程（一对一会限制 agent 数量）：agent 侧统一经
 *   `loong mcp` 桥接入，桥同样 ensure daemon 后挂接——用户与 agent 谁先谁后都成立。
 *
 * 项目目录：所有项目内产物都在其中——workspace.db、loongd.json（端点）、
 * registry/、cache/、daemon.log。缺省 = 当前工作目录（建目录、cd 进去、敲 loong 即可）；
 * --project/LOONG_PROJECT 可覆盖。用户主目录只存 ~/.loong（跨项目个人配置与 daemon 索引）。
 */
fun main(args: Array<String>) = startFullStack(args)

fun startFullStack(args: Array<String>) {
    val projectDir = projectDirOf(args)

    DaemonCtl.probe(projectDir)?.let { ep ->
        System.err.println()
        System.err.println("  Loong daemon 已在运行（复用，不重复启动）")
        System.err.println("  ├─ 项目目录 : ${projectDir.toAbsolutePath()}")
        System.err.println("  |- Web UI  : ${ep.baseUrl}/")
        System.err.println("  └─ 停止    : loong stop")
        exitProcess(0)
    }

    val db = projectDir.resolve("workspace.db").also { Files.createDirectories(it.parent) }
    val server = LoongdServer(0, db, projectDir.resolve("registry"))
    val ep = DaemonCtl.Endpoint(server.port(), ProcessHandle.current().pid())

    // 双启动竞态兜底：绑定后再探一次端点文件，发现别的 daemon 已就位则让位
    DaemonCtl.probe(projectDir)?.let { winner ->
        runCatching { server.close() }
        System.err.println("  Loong daemon 已在运行（复用 ${winner.baseUrl}）")
        exitProcess(0)
    }
    DaemonCtl.writeEndpoint(projectDir, ep)
    DaemonCtl.register(projectDir, ep)

    val endpointFile = DaemonCtl.endpointFile(projectDir)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(endpointFile) }
        DaemonCtl.unregister(ep.pid)
    })

    // 日志走 stderr（detached 模式汇入 <项目目录>/daemon.log）
    System.err.println()
    System.err.println("  Loong daemon 已启动（停止：Ctrl+C 或 loong stop）")
    System.err.println("  ├─ 项目目录     : ${projectDir.toAbsolutePath()}")
    System.err.println("  |- Web UI      : ${ep.baseUrl}/")
    System.err.println("  |- daemon API  : ${ep.baseUrl}/health")
    System.err.println("  ├─ 持久化      : ${db.toAbsolutePath()}")
    System.err.println("  ├─ 发布注册表  : ${projectDir.resolve("registry").toAbsolutePath()}")
    System.err.println("  ├─ 个人配置    : ${userConfigDir()}（跨项目）")
    System.err.println("  └─ MCP        : agent 配置 loong mcp；本机概览：loong list")
    System.err.println()

    server.awaitTermination()
}

/**
 * 项目目录解析优先级：--project <dir> 参数 > LOONG_PROJECT 环境变量 > 当前工作目录。
 */
fun projectDirOf(args: Array<String>): Path {
    val flagIdx = args.indexOf("--project")
    val byFlag = if (flagIdx >= 0) args.getOrNull(flagIdx + 1) else null
    val raw = byFlag ?: System.getenv("LOONG_PROJECT") ?: ""
    return Path.of(raw).toAbsolutePath().normalize()
}

/** 用户级跨项目配置目录（主目录仅此用途）。 */
fun userConfigDir(): Path =
    Path.of(System.getProperty("user.home"), ".loong").also { Files.createDirectories(it) }
