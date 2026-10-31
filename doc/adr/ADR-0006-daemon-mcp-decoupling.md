# ADR-0006：daemon 与 MCP 端点解耦（动态端口 + 端点文件 + 桥接进程）

- 状态：Accepted（2026-10-30；同日修订：入口收敛为单一命令 loong 的子命令）
- 关联：design §8.1/§8.3/§8.7；ADR-0003（H2 持久化）；plan.md「全栈入口与发布包」

## 背景

v0.1 发布包形态里，`loong-up` 单进程同时扮演 daemon（HTTP/WebUI/H2）与 MCP stdio 端点，
端口固定 8765。该形态的问题：

1. **stdio 天生 1:1**——一个进程只能服务一个 agent，同一项目无法挂多个 agent；
2. **单机多项目冲突**——第二个 daemon 绑 8765 直接 BindException；
3. **启动顺序被绑死**——agent 把 loong-up 拉起为子进程，若用户已先起 daemon，
   agent 侧再拉一个必然失败（反之亦然）；
4. **生命周期耦合**——stdin EOF 即整个进程退出（含 daemon），或 daemon 常驻则 agent
   断开后进程泄漏。

决策者要求：用户先起 loong、agent 先起 loong、任意顺序都不出错；多项目并存不冲突；
随后进一步要求：所有能力收敛为单一命令 `loong`（loong up / stop / mcp / list），
项目目录缺省 = 当前工作目录（建目录、cd 进去、敲 loong 即用）。

## 决策

**把「daemon 进程」与「agent 接入端点」拆成两个角色，接入传输保持 stdio：**

1. **动态端口 + 项目内端点文件**：daemon 绑定随机空闲端口，端点写入
   `<项目目录>/loongd.json`（`{port, pid, version}`）。多项目 = 多 daemon 各持各端口；
   所有入口凭该文件发现并复用 daemon。项目目录缺省 = **当前工作目录**，
   `--project`/`LOONG_PROJECT` 覆盖；产物（workspace.db、registry/、cache/、daemon.log）
   就地落盘。用户主目录只存 ~/.loong（跨项目个人配置 + daemon 运行索引）。
2. **ensure 语义统一**：`loong up`（用户，也是无参数默认命令）与 `loong mcp`
   （agent）启动时都先探活（读端点文件 → GET /health）；已在运行则复用，未运行才
   拉起——两种启动顺序因此都成立。双启动竞态由「绑定后复查端点文件，发现他人
   已就位则让位」兜底。
3. **`loong mcp` 桥**：agent 配置的 command（args 首参 `mcp`）。ensure daemon
   （未运行则以分离进程拉起，输出汇入 `<项目目录>/daemon.log`，等待 /health 就绪）
   后，把 stdio JSON-RPC 桥接到 daemon HTTP。桥零状态、可并存多个——同一项目多
   agent 自然支持。daemon 进程本身不再承载 MCP stdio。
4. **daemon 常驻 + `loong stop`**：daemon 不随桥/agent 退出（决策者选定）；
   `loong stop` 经 daemon 的 `POST /shutdown` 优雅停止（先响应再退出，shutdown hook
   落盘关库）——不用进程强杀，因为 Windows TerminateProcess 不走 hook，有损坏
   MVStore 的风险。残留端点文件由下次探活判定失效并覆盖，`stop` 顺带清理。
5. **`loong list`**：daemon 启动时登记本机索引 `~/.loong/daemons/<pid>.json`
   （projectDir/port/pid），退出除名；list 读索引逐个探活展示，顺带清理强杀/崩溃
   残留的登记。detached daemon 复用同一 jar 的 `Launch up` 路径启动（`-jar` 与
   classpath 双形态均支持）。

## 后果

- 正面：顺序无关、多项目隔离、同项目多 agent、agent 断开不丢 daemon（Web UI 随时可看）；
  单命令 + CWD 缺省使工作流收敛为「建目录 → cd → loong」；
- 代价：每项目常驻一个 JVM（约百余 MB）；Windows 下运行中的 daemon 锁定 loong.jar，
  升级发布目录前需先 stop（README 已注明）；
- CLI 的 daemon 客户端模式仍经 `LOONGD_URL` 显式指定（端口现为动态值，从 loongd.json
  或启动输出获取）——后续可让 CLI 自动读取项目端点文件；
- Web UI 端口不再固定 8765，首页含 loong 命令详细用法，MCP 页配置模板为
  `command: loong, args: ["mcp", "--project", …]`。
