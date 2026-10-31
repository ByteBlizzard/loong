# ADR-0002 `.loongast` 规范序列化格式

**状态**：Accepted（决策者批准草案，2026-10-29；M4 定稿哈希与签名细节）
**关联**：design.md §11 待决 #2、§4.6、§7.2；ast-schema-v1.md

## 决策

1. 格式为**规范化 JSON**：UTF-8、LF、无填充空白、对象键按 Unicode 码点升序、数字不用指数记法。
2. 顶层结构：

```json
{
  "schema_version": 1,
  "module": "org.example.math/v1",
  "nodes": [ { "id": "1", "kind": "Module", ... }, ... ]
}
```

3. `nodes` 数组**按 id 升序**；每个节点对象含 `id`、`kind`、`fields`（仅存在的字段）、`kids`（id 数组）、`parent`。
4. **NodeId 序列化为十进制字符串**（u64 超出 JSON 安全整数 2^53，JS 消费方会静默丢精度）。
5. **内容哈希**：对整个规范化 JSON 字节流取 sha256，即 `.loongast` 的内容地址。
6. **签名覆盖**：对内容哈希签名（Sigstore cosign，M6 接入），不签部分节点。
7. 版本兼容：`schema_version` 只有 1 和未来的 2、3…；读取方不认识更高版本必须显式失败。

## 确定性要求（重申）

同一 AST 无论何时、何种进程序列化，字节流必须逐位相同——这是节点级 diff、内容寻址缓存、签名验证的共同前提。实现侧禁止在序列化路径上引入 Map 迭代序、时间戳、机器信息。

## 替代方案（否决）

- 二进制格式（CBOR/自定义）：体积小但不可读、工具链多；JSON 先行，若性能成为瓶颈可在 schema_version 2 引入二进制并保持 JSON 为规范形态；
- 按 kids 嵌套的树形 JSON：移动/重排子节点会改写大段文本，违背"diff 显示节点级增删改"（design §4.6）。
