package dev.loong.cli

import kotlin.system.exitProcess

/**
 * 发布包统一入口（`java -jar loong.jar …` / `loong <命令>`，见根目录 `dist` 任务与 packaging/）。
 *
 * 所有能力收敛到单一命令 loong 的子命令上：
 *  - 无参数 = `up`（默认命令）：ensure daemon——未运行则前台启动，已在运行则复用；
 *  - `up` / `stop` / `mcp` / `list`：daemon 生命周期与 agent 接入（ADR-0006）；
 *  - `check` / `build` / `run` / `test`：编译器 CLI（standalone；LOONGD_URL 时走 daemon）；
 *  - `help` / `--version`。
 *
 * 项目目录解析（up/stop/mcp/list 共用）：`--project <dir>` > LOONG_PROJECT > 当前工作目录
 * ——建个目录、cd 进去、敲 loong 即可，全部产物就地落盘。
 */
private val CLI_COMMANDS = setOf("check", "build", "run", "test", "--version")

fun main(args: Array<String>) {
    when {
        args.isEmpty() -> startFullStack(emptyArray())
        args[0] == "up" -> startFullStack(args.drop(1).toTypedArray())
        args[0] == "stop" -> stopDaemon(args.drop(1).toTypedArray())
        args[0] == "mcp" -> runMcpBridge(args.drop(1).toTypedArray())
        args[0] == "list" -> listDaemons()
        args[0] == "help" || args[0] == "--help" || args[0] == "-h" -> {
            printUsage()
            exitProcess(0)
        }
        args[0] in CLI_COMMANDS -> runCli(args)
        args[0].startsWith("-") -> startFullStack(args) // 例如 loong --project <dir> = loong up
        else -> {
            System.err.println("未知命令 '${args[0]}'")
            printUsage()
            exitProcess(5)
        }
    }
}

/** `loong help`：完整用法（与 Web UI 首页一致）。 */
fun printUsage() {
    println(
        """
        Loong ${LoongCliVersion.VERSION} —— 用法：loong <命令> [参数]（无参数 = loong up）

          loong                  启动/复用当前目录的 daemon（默认命令，等同 loong up）
          loong up               未运行则前台启动（Ctrl+C 停止）；已在运行则显示地址复用
          loong stop             停止当前目录的 daemon
          loong mcp              MCP stdio 桥（agent 接入用，由 agent 拉起，一般不手敲）
          loong list             列出本机所有运行中的 Loong daemon
          loong check <file>     编译检查；build <file> [-d <dir>]；run <file>；test <file>
          loong help             本帮助      loong --version  版本

        项目目录：--project <目录> 覆盖，缺省 = 当前工作目录（LOONG_PROJECT 环境变量同义）。
        产物就地落在项目目录：workspace.db、loongd.json、registry/、cache/、daemon.log。
        """.trimIndent(),
    )
}
