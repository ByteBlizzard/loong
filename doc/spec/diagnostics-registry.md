# Loong 诊断码注册表

**状态**：Proposed（M0 决策门待批）
**依据**：design.md §2.3、§4.4、§4.5

诊断结构（design §4.5）：

```
Diagnostic { node_id, severity, code, message, suggested_fix? }
```

**注册纪律**：诊断码一经分配**永不改名、永不改义、永不回收**；只能新增。severity 分 `Error`（阻断编译）/ `Warning`（不阻断）。v1 全部为 Error，Warning 留给未来 lint 类规则。

---

## 1. 词法 / 语法（parse 阶段）

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_UNEXPECTED_TOKEN` | 出错 token 能定位到的最近节点 | token 不符合当前产生式 |
| `E_UNEXPECTED_EOF` | 文件级 | 输入提前结束（未闭合块/括号/字符串） |
| `E_UNTERMINATED_STRING` | 字面量节点 | 字符串/字符未闭合 |
| `E_INVALID_ESCAPE` | 字面量节点 | 非法转义序列 |
| `E_INVALID_NUMBER` | 字面量节点 | 非法数字字面量 |
| `E_MISSING_MODULE_DECL` | Module | 文件首条声明不是 `module` |
| `E_BAD_MODULE_NAME` | Module | 模块全名不符合命名规范（ADR-0001） |

## 2. 名字解析（resolve 阶段）

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_UNKNOWN_NAME` | `Path` | 引用的符号在可见作用域内不存在 |
| `E_UNKNOWN_TYPE` | 类型字段所在节点 | 类型名无法解析 |
| `E_AMBIGUOUS_NAME` | `Path` | 短名经渲染逆映射可还原到多个全名 |
| `E_DUPLICATE_DECL` | 后声明节点 | 同一作用域内声明重名 |
| `E_UNKNOWN_MODULE` | `Depends` | 依赖目标在注册表/工作区中不存在（含 AI 幻觉依赖，design §7.6） |
| `E_CYCLE_DEPENDS` | `Depends` | depends 图有环（design §2.3） |
| `E_UNRESOLVED_TARGET` | `Test` | `target_name` 找不到对应函数 |

## 3. 类型检查（type 阶段）

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_TYPE_MISMATCH` | 表达式节点 | 期望类型与实际类型不一致（含 if/match 分支不一致、return 类型不一致、实参类型不符） |
| `E_ARITY_MISMATCH` | `Call` | 实参数与签名不符 |
| `E_NAMED_ARG_UNKNOWN` | `Arg` | 具名实参不在目标签名中 |
| `E_BAD_OPERAND_TYPE` | `Binary`/`Unary` | 操作数类型不支持该运算符（无重载，内建语义唯一） |
| `E_CALL_NOT_FUNCTION` | `Call` | 被调路径的类型不是函数类型 |
| `E_NOT_EXHAUSTIVE` | `Match` | match 未穷尽枚举构造（design §2.2） |
| `E_PATTERN_TYPE_MISMATCH` | `MatchArm`/`IfLet` | 模式与被检类型不符 |
| `E_NOT_ITERABLE` | `For` | 迭代对象未实现 `Iterable` 接口 |
| `E_MISSING_TYPE_CONSTRAINT` | `TypeParam` | 类型参数无 where 约束（禁止裸类型参数） |
| `E_CONSTRAINT_NOT_SATISFIED` | 类型实参使用点 | 泛型实参不满足 where 约束 |
| `E_ASSIGN_IMMUTABLE` | `Assign` | 对 `let` 绑定或 `const` 赋值 |
| `E_SHARED_REQUIRES_MUTEX` | `Let` | `shared` 修饰的绑定类型不是 `Mutex<T>` |
| `E_UNKNOWN_FIELD` | `FieldAccess` | struct 上不存在该字段（2026-10-29 注册） |

## 4. 语言强制规则（rule 阶段，design §2.3）

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_MISSING_DOC` | `Function`/`Struct`/`Interface`（及其 `Field`/`MethodSig`） | 无非空 DocComment 首子节点 |
| `E_NO_TEST` | public `Function` | 未被任何 `Test.target_id` 引用 |
| `E_TOO_LONG` | `Block` | 直接子语句数超过阈值（v1 定 50，编译器常量，可配置项后续进 daemon 配置） |
| `E_PRIVATE_ACCESS` | `Path` | 跨模块 `resolved_id` 指向非 interface 暴露成员（design §4.4） |
| `E_IMPL_SIGNATURE_MISMATCH` | `Impl` 下 `Function` | 与 `MethodSig` 签名不完全一致（名义匹配，design §2.2） |
| `E_PUBLIC_NOT_IN_INTERFACE` | public 声明 | 跨模块可见成员未在模块 interface 中声明（design §2.3 第 5 条） |

## 5. JVM 互操作（interop 阶段）

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_EXTERNAL_CLASS_NOT_FOUND` | `ExternalClass` | 目标类在 classpath 不存在 |
| `E_EXTERNAL_METHOD_NOT_FOUND` | `ExternalMethod` | 目标方法不存在 |
| `E_EXTERNAL_DESCRIPTOR_MISMATCH` | `ExternalMethod` | 声明签名与目标方法 JVM 描述符不符（design §6.2） |

## 6. 工具链内部

| 代码 | 锚点 | 触发条件 |
|---|---|---|
| `E_INVARIANT_VIOLATION` | 违例节点 | 节点表不变量（ast-schema §3）被破坏，属工具链缺陷，不是用户错误 |
| `E_INTERNAL` | 尽力定位 | 引擎内部错误（附追踪信息） |

---

## 分配规则

新增诊断码：`E_<范畴>_<含义>`，大写下划线；在本文档登记后方可使用；诊断输出同时携带 `code` 与人类可读 `message`（message 可本地化，code 不可变）。
