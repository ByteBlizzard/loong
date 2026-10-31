package dev.loong.cli

import dev.loong.mcp.McpSession
import java.io.OutputStreamWriter
import kotlin.system.exitProcess

/** `loong mcp`：agent 侧 stdio 桥（ADR-0006）。 */
fun runMcpBridge(args: Array<String>) {
    val projectDir = projectDirOf(args)
    val ep = DaemonCtl.ensureDetached(projectDir)
    System.err.println("loong mcp: daemon ${ep.baseUrl} (pid ${ep.pid}) 已挂接，项目 ${projectDir.toAbsolutePath()}")

    McpSession(
        System.`in`.bufferedReader(),
        OutputStreamWriter(System.out, Charsets.UTF_8),
        ep.baseUrl,
    ).run()
    // stdin EOF = agent 断开：桥退出，daemon 常驻（loong stop 停止）
    exitProcess(0)
}

/** `loong stop`：优雅停止项目对应的 daemon。 */
fun stopDaemon(args: Array<String>) {
    val projectDir = projectDirOf(args)
    if (DaemonCtl.stop(projectDir)) {
        println("已停止 ${projectDir.toAbsolutePath()} 的 Loong daemon")
        exitProcess(0)
    }
    println("未发现运行中的 Loong daemon（项目 ${projectDir.toAbsolutePath()}）")
    exitProcess(0)
}

/** `loong list`：列出本机所有运行中的 Loong daemon（读 ~/.loong/daemons/ 索引并探活）。 */
fun listDaemons() {
    val running = DaemonCtl.listRunning()
    if (running.isEmpty()) {
        println("没有运行中的 Loong daemon")
        exitProcess(0)
    }
    println("%-8s %-7s %s".format("PID", "端口", "项目目录"))
    running.forEach {
        println("%-8d %-7d %s".format(it.pid, it.port, it.projectDir))
    }
    println("(共 ${running.size} 个；停止：在对应项目目录执行 loong stop)")
    exitProcess(0)
}
