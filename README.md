# Loong

面向 AI 编程场景的显式结构语言，编译到 JVM。设计文档见 [doc/design.md](doc/design.md)，实施计划见 [doc/plan.md](doc/plan.md)。


## 仓库结构

| 模块 | 产物 | 职责 |
|---|---|---|
| `engine` | loong-engine | 纯库：AST 节点表、解析/渲染、名字解析、类型检查、强制规则、字节码生成、增量编译 |
| `runtime` | loong-runtime | 编译器隐式链接的运行时 shim（Option/Result/并发原语） |
| `daemon` | loongd | 语言守护进程：状态持有、操作队列、持久化（H2） |
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
`loong mcp`（agent 侧 stdio 桥）、`loong list`（本机 daemon 概览）、
`loong check/build/run/test`（编译器）。项目目录缺省 = 当前工作目录——
建个目录、cd 进去、敲 loong 即可，产物就地落盘；每个项目一个常驻 daemon，
端口随机分配互不冲突，用户与 agent 谁先启动都成立，同项目可挂多个 agent。
脚本模板见 `packaging/`。

## 规范与决策记录

- `doc/spec/grammar-v1.md` — EBNF 文法 v1
- `doc/spec/ast-schema-v1.md` — AST 节点 Schema v1
- `doc/spec/diagnostics-registry.md` — 诊断码注册表
- `doc/adr/` — 架构决策记录（ADR-0001~0004）
