Loong 编程语言 —— 发布包（版本见 zip 文件名 / loong --version）
================================================================

一个自包含目录：解压（或保持展开）到任何位置即可使用，仅要求 Java 21+ 在 PATH，
或设置 JAVA_HOME 指向 Java 21+ 安装目录。全部能力收敛为单一命令 loong
（执行同目录下唯一的 loong.jar，所有模块与依赖已合并其中）。

快速开始（最典型的工作流）
--------------------------
1. 建一个项目目录并进入：
       mkdir myproj
       cd myproj
2. 启动（无参数即 loong up）：
       loong                      （Unix；Windows 同名 loong.cmd）
   未运行则前台启动 daemon（Ctrl+C 停止）；已在运行则显示地址直接复用。
   横幅会给出 Web UI 地址（端口随机分配，互不冲突）。
3. 浏览器打开 Web UI 地址——查看代码、诊断、测试、AI 活动流、人工审批。
4. AI agent 接入：把 loong 配置为 agent 的 MCP server——
       command: <本目录>/loong（Windows 为 loong.cmd）
       args:     ["mcp", "--project", "<项目目录>"]
   桥启动时自动确保 daemon 在运行（未运行则拉起，agent 先起、用户先起都成立）；
   agent 断开后 daemon 常驻；同一项目可挂多个 agent；多项目各自独立 daemon。
   Web UI 的「MCP」页有现成配置模板与连接状态。
5. 停止：
       loong stop
6. 查看本机所有运行中的 daemon：
       loong list

命令一览
--------
loong                    启动/复用当前目录的 daemon（默认命令，等同 loong up）
loong up                 同上（显式写法）
loong stop               停止当前目录的 daemon
loong mcp                MCP stdio 桥（agent 接入用，由 agent 拉起，一般不手敲）
loong list               列出本机所有运行中的 Loong daemon
loong check <file>       编译检查（有诊断则退出码 1）
loong build <file> [-d <dir>]   编译 class 到目录（缺省 loong-out）
loong run <file>         编译并在新 JVM 上运行 main
loong test <file>        编译并运行内联测试（有失败则退出码 2）
loong help               完整用法        loong --version  版本

项目目录
--------
缺省 = 当前工作目录：建个目录、cd 进去、敲 loong 即可，全部产物就地落盘——
workspace.db（AST 持久化）、loongd.json（daemon 端点：端口/pid）、registry/
（发布注册表）、cache/（依赖缓存）、daemon.log（daemon 日志）。
用 --project <目录> 或环境变量 LOONG_PROJECT 覆盖。
用户主目录只存 ~/.loong（跨项目个人配置与 daemon 运行索引，loong list 用）。

架构一图流
----------
  编程 agent ── stdio JSON-RPC ── loong mcp（桥，可多个）──┐
  浏览器 ────── Web UI ─────────────────────────────────├── Loong daemon（常驻）
  loong check ── LOONGD_URL 或独立编译 ─────────────────┘     ├─ 每项目一个
                                                              └─ 端口随机分配
daemon 是唯一事实源（AST、诊断、测试、版本历史、发布注册表）；Web UI、MCP 桥、
CLI 都是它的客户端，凭项目目录里的 loongd.json 发现并复用 daemon。

提示
----
- 升级/替换发布目录前先 loong stop：Windows 下运行中的 daemon 会锁定 loong.jar；
- 编译器默认 standalone；设 LOONGD_URL=<daemon 地址> 后自动转 daemon 客户端模式
  （地址见 loongd.json 或启动输出）。

退出码
------
0 正常；1 编译诊断（check/build）；2 测试失败（test）；5 用法/内部错误。

许可证
------
见源码仓库 LICENSE。
