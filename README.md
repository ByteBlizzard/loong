# Loong

面向 AI 编程场景的显式结构语言，编译到 JVM。设计文档见 [doc/design.md](doc/design.md)，实施计划见 [doc/plan.md](doc/plan.md)。


## 仓库结构

| 模块 | 产物 | 职责 |
|---|---|---|
| `engine` | loong-engine | 纯库：AST 节点表、解析/渲染、名字解析、类型检查、强制规则、字节码生成、增量编译 |
| `runtime` | loong-runtime | 编译器隐式链接的运行时 shim（Option/Result/并发原语、FileIo/HttpIo/Tcp 异常收口） |
| `stdlib` | — | 标准库源（loong.core 工具函数；loong.io 文件 I/O；loong.net HTTP/TCP），daemon 内置为系统模块 |
| `daemon` | loongd | 语言守护进程：状态持有、操作队列、持久化（H2）、JVM 依赖解析 |
| `mcp` | loong-mcp | AI agent 操作面（MCP server） |
| `cli` | loong-cli | 命令行（daemon 客户端 / standalone 双模式） |
| `doc` | — | 设计、计划、规范（EBNF/AST schema/诊断码）、ADR |

## 构建

要求 JDK 21+；Gradle 9.5.1 经仓库自带的 wrapper 使用（首次运行自动下载发行版，无需单独安装）。

```bash
./gradlew build        # Linux / macOS / Git Bash
gradlew.bat build      # Windows cmd / PowerShell
```

## 发布包

```bash
./gradlew dist
```

一次产出两份：展开发布目录 `build/dist/loong/`（进入即可直接使用）与
`build/distributions/loong-<版本>.zip`（解压到任何目录即可用，仅需目标机有 Java 21+）。
包根目录只有一对脚本 `loong`（sh）/ `loong.cmd`；全部 jar 合并为唯一的 `loong.jar`。

单一命令 + 子命令（ADR-0006）：`loong`（= `loong up`）ensure daemon、`loong stop`、
`loong mcp`（agent 侧 stdio 桥，随握手下发语言导览 instructions，
并提供 write-function / new-module / fix-diagnostics 任务手册 prompts 与
文法 / AST 节点表 / 诊断码 / 标准库清单 / JVM 互操作手册 / 示例 resources——
教 agent 写 loong，而非只给工具清单）、
`loong list`（本机 daemon 概览）、
`loong check/build/run/test`（编译器）。项目目录缺省 = 当前工作目录——
建个目录、cd 进去、敲 loong 即可，产物就地落盘；每个项目一个常驻 daemon，
端口随机分配互不冲突，用户与 agent 谁先启动都成立，同项目可挂多个 agent。
脚本模板见 `packaging/`。

**标准库与 JVM 生态**：daemon 内置三个系统模块（`list_modules` 可见、不可删）——
`loong.core`（数值/字符串/Option/Result 工具）、`loong.io`（文件读写，全部返回
`Result<T, String>`）、`loong.net`（HTTP 与 TCP，阻塞式配虚拟线程）；使用前显式
`depends loong.io` 即可。调 JVM 类用 `external java` 声明：agent 先经
`java_class_info` 反射查询真实签名再写声明，装载时编译器对声明做反射核对
（`E_EXTERNAL_*` 诊断）；三方 jar 经 `add_jvm_dep`（Maven 坐标，daemon 委托本机
gradle/mvn 解析 classpath）或手动放入项目 `libs/` 引入，测试与构建自动携带
（构建 jar 复制到 `build/libs/` 并以 manifest Class-Path 引用）；人类侧等价操作在
Web UI「JVM 依赖」页。注意：loong 无异常，会抛异常的 JDK API 一律走 loong.io/
loong.net 包装（runtime shim 内收口为 Result），不要 external 直调。

## 规范与决策记录

- `doc/spec/grammar-v1.md` — EBNF 文法 v1
- `doc/spec/ast-schema-v1.md` — AST 节点 Schema v1
- `doc/spec/diagnostics-registry.md` — 诊断码注册表
- `doc/adr/` — 架构决策记录（ADR-0001~0004）
