# Loong AST 节点 Schema v1

**状态**：Accepted（M0 决策门通过，2026-10-29）
**依据**：design.md §4（AST 设计）
**配套**：`grammar-v1.md`（产生式→kind 映射）、ADR-0004（ID 分配规则）、ADR-0002（序列化）

实现载体：`engine/src/main/kotlin/dev/loong/engine/ast`（`Kind` 枚举必须与本文件 kind 清单一致）。

---

## 1. 通用节点结构

```
Node {
  id:     NodeId            // u64，工作区内单调分配，永不复用（ADR-0004）；0 = NONE 保留
  kind:   Kind              // 枚举，决定 fields 的解释与 kids 的合法形态
  fields: Map<str, FieldValue>
  kids:   [NodeId]          // 有序子节点
  parent: NodeId            // 反向引用；根节点（Module）为 NONE
}
```

## 2. 字段值类型

`FieldValue` 只有四种：

| 类型 | 含义 | 例 |
|---|---|---|
| `Str` | 字符串 | `name`、`text`、`op` |
| `Int` | 64 位整数 | （v1 暂无，预留） |
| `Bool` | 布尔 | `mutable`、`shared` |
| `Ref` | 指向另一节点的 NodeId | `target_id`、`resolved_id` |

**类型标注的表示**：所有 `type`、`return_type`、`constraint`、`payload_type` 类字段存**规范类型文本**（grammar §2.3 的序列化形态，如 `"Int"`、`"Array<org.example.Point>"`、`"(Int, Int) -> Bool"`）。类型检查器按需解析该文本，不引入独立类型节点。

**可选字段**：字段缺失即"无值"；序列化时不输出缺失字段（ADR-0002）。

## 3. 不变量（编辑操作与持久化必须共同维护）

1. **双向一致**：`p.kids[i] == c` ⟺ `get(c).parent == p`；kids 无重复。
2. **无环**：parent 链必终止于根（parent = NONE）。
3. **单根**：一个模块的完整 AST 恰有一个 `Module` 根；构建过程中允许存在多个游离子树（parent = NONE 的非 Module 节点），序列化前必须全部挂接。
4. **DocComment 位置语义**：若声明有文档注释，它必须是该声明的**第一个子节点**（替代 design.md §4.3 的 `doc_id` 字段，见 §7 差异清单）。
5. **解析器填充 vs 后端填充**：`Path.written`、`Test.target_name` 由解析器写入；`Path.full_name`、`Path.resolved_id`、`Test.target_id` 由名字解析阶段写入。codegen 前要求引用类字段全部已填充。
6. **删除语义**：删除节点 = 整棵子树移出节点表；ID 永不复用、永不重排。
7. **点链单 Path**：表达式中的 `a.b.c` 解析为单个 `Path`（written = `"a.b.c"`）；拆分为 `FieldAccess` 链是名字解析阶段的职责（grammar §4.2）。仅当点链接收者不是 Path（`arr[0].x`、`f(c).x`）时解析器直接产生 `FieldAccess`。
8. **块尾表达式的表示**：块值 = 末位子节点恰为 `ExprStmt` 时其表达式（无尾表达式的块值为 `Unit`）。不设专门的"尾表达式"节点。
9. **impl 函数符号全名挂接口全名下**（如 `org.example.geom.Shape.area`），供名字解析注册符号表。

## 4. Kind 清单

### 4.1 声明类

| kind | fields | kids |
|---|---|---|
| `Module` | `name`(str, 含 `/vN`)、`host_class`?(str, JVM 宿主类名，见 design §6.3) | `Depends`*, 声明* |
| `Depends` | `module_name`(str)、`constraint`?(str, 如 `">=1.2.0"`)、`source`(str: `registry`\|`workspace`\|`path`) | — |
| `Function` | `name`、`visibility`(str: `public`\|`internal`)、`return_type`(str) | `DocComment`?, `TypeParam`*, `Param`*, `Block` |
| `Param` | `name`、`type` | — |
| `TypeParam` | `name`、`constraint`?(str, 类型名；缺失 = 违反显式约束规则) | — |
| `Struct` | `name`、`visibility` | `DocComment`?, `TypeParam`*, `Field`* |
| `Field` | `name`、`type` | `DocComment`? |
| `Interface` | `name`、`visibility` | `DocComment`?, `TypeParam`*, `MethodSig`* |
| `MethodSig` | `name`、`return_type` | `DocComment`?, `TypeParam`*, `Param`* |
| `Impl` | `interface`(str, 全名)、`struct`(str, 全名) | `Function`* |
| `Enum` | `name`、`visibility` | `DocComment`?, `TypeParam`*, `Variant`* |
| `Variant` | `name`、`payload_type`?(str) | `DocComment`? |
| `Const` | `name`、`visibility`、`type` | `DocComment`?, `Literal` |
| `Test` | `name`、`target_name`(str, 源文本全名)、`target_id`?(Ref→Function, 解析填充) | `DocComment`?, `Block` |
| `DocComment` | `text`(str, 连续 `///` 行合并) | — |
| `ExternalClass` | `class_fqn`(str) | `ExternalMethod`* |
| `ExternalMethod` | `name`、`call_kind`(str: `static`\|`instance`\|`constructor`)、`return_type` | `DocComment`?, `TypeParam`*, `Param`* |

### 4.2 语句类

| kind | fields | kids |
|---|---|---|
| `Block` | — | stmt*（末位允许尾表达式节点，即块值） |
| `Let` | `name`、`type`、`mutable`(bool)、`shared`(bool) | 初始化表达式 |
| `Assign` | — | 目标（`Path`\|`FieldAccess`\|`Index`）、值表达式 |
| `If` | — | 条件、then-`Block`、（else-`Block` \| else-`If`）? |
| `IfLet` | `path`(str, 枚举构造全名)、`binding`(str) | 被检表达式、then-`Block`、else-`Block`? |
| `While` | — | 条件、`Block` |
| `For` | `var_name`、`var_type` | 迭代对象表达式、`Block` |
| `Match` | — | 被检表达式、`MatchArm`+ |
| `MatchArm` | `pattern_kind`(str: `wildcard`\|`literal`\|`enum_ctor`)、`literal`?(str)、`path`?(str)、`binding`?(str) | 体（`Block` 或表达式） |
| `Return` | — | 表达式?（缺省 = Unit） |
| `Break` / `Continue` | — | — |
| `ExprStmt` | — | 表达式 |

### 4.3 表达式类

| kind | fields | kids |
|---|---|---|
| `Call` | — | 被调 `Path`、`Arg`* |
| `Arg` | `name`?(str, 具名实参) | 实参表达式 |
| `Binary` | `op`(str) | lhs、rhs |
| `Unary` | `op`(str: `-`\|`!`) | 操作数 |
| `Literal` | `lit_kind`(str: `int`\|`float`\|`bool`\|`string`\|`char`\|`array`\|`map`)、`value`?(str, 源文本；array/map 无) | array: 元素*；map: `MapEntry`* |
| `MapEntry` | — | key、value |
| `Path` | `written`(str, 源文本形态)、`full_name`?(str, 解析填充)、`resolved_id`?(Ref, 解析填充) | — |
| `FieldAccess` | `field`(str) | 接收者 |
| `Index` | — | 目标、下标 |
| `Spawn` | — | `Call` |
| `Join` | — | Handle 表达式 |

## 5. 已移除的预留 Kind

原为形式化验证预留的 `ProofRequire`/`ProofEnsure` 已按决策移除（功能未成熟，需要时再引入）。

## 6. 编辑操作（design §4.5 的落地契约）

| 操作 | 语义 | 返回 |
|---|---|---|
| `create(kind, fields, kids?)` | 创建游离节点；kids 中的游离节点被收养（parent 改指） | 新 NodeId |
| `insert(parent, index, kind, fields)` | create + 挂接为 parent 第 index 个子节点 | 新 NodeId |
| `attach(id, parent, index)` | 挂接游离子树 | affected |
| `replace(id, fields?, kids?)` | 原地改字段/子节点；旧子树整体删除，新 kids 必须游离 | affected |
| `move(id, newParent, index)` | 换位（禁止移入自身子树） | affected（含新旧祖先链） |
| `delete(id)` | 整棵子树移出节点表，ID 不复用 | affected（幸存祖先 + id） |
| `get` / `find(kind?, predicate?)` | 读取 | Node / NodeId 列表 |

**affected 集合** = 操作节点自身 + 祖先链（至根）。增量编译调度器（M2）据此定位所属模块：模块是失效单元，affected 只需足以回答"哪些模块脏了"。

## 7. 与 design.md §4.3 的差异（需决策门确认）

1. **`doc_id` 字段取消**：design 用字段引用 DocComment；v1 改为「DocComment 是声明首子节点」，避免字段与 kids 双源不一致。`E_MISSING_DOC` 检查首子节点。
2. **补全 design 最小集的缺口**：新增 `Enum`/`Variant`/`Impl`/`Const`/`TypeParam`/`ExternalClass`/`ExternalMethod`/`For`/`Match`/`MatchArm`/`Break`/`Continue`/`IfLet`/`Spawn`/`Join`/`Arg`/`MapEntry`（design §4.3 语句/表达式清单未列全，但 §2.2 特性表要求支持）。
3. **`Module.host_class` 新增**：落实 design §6.3「宿主类名是 AST 显式属性」。
4. **`Path` 三字段**（`written`/`full_name`/`resolved_id`）：解析器只写 `written`，渲染逆映射与名字解析分阶段填充，与 grammar §4.2 一致。
