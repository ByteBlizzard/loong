# ADR-0003 daemon 持久化采用 H2

**状态**：Accepted（决策者已决，2026-10-29）
**关联**：design.md §8.2、§11 待决 #5；plan.md §3.1

## 决策

daemon 持久化层采用 **H2 2.x（MVStore 模式）**，不采用 SQLite / Dolt。

## 理由

1. **需求匹配**：负载为「单写者 daemon + 节点表按 ID 存取 + 不可变快照追加 + 周期性落盘」，无跨进程并发访问数据库的场景；H2 相对 SQLite 的并发短板不构成约束。
2. **全 JVM 生态**：纯 Java 单 Maven 依赖；sqlite-jdbc 需按平台捆绑本地二进制，复杂化构建、分发与 GraalVM native-image。与「本项目以 JVM 实现、架构尽量简单」的总体目标一致。
3. **锁定风险低**：可移植事实源是 `.loongast` 规范序列化文本（ADR-0002），数据库仅为内部持久化实现。

## 约束（实施必须遵守）

1. 数据库访问统一隔离在 `engine/storage` 的 `StoragePort` 接口之后；daemon 与 engine 其余部分不得直接依赖 H2 API。
2. **CLI standalone 模式不得打开 daemon 的数据库文件**（H2 文件锁为单进程）；standalone 使用内存态。
3. 备份使用 H2 官方备份 API，不得在运行中复制数据文件。
4. 周期性导出 `.loongast` 快照是天然恢复点：数据库损坏可从快照重建。
