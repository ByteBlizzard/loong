# Loong 文法 v1（EBNF）

**状态**：Accepted（M0 决策门通过，2026-10-29）
**依据**：design.md §2（语法特性与边界）、§3（模块与依赖）
**配套**：`doc/spec/ast-schema-v1.md`（产生式与节点 kind 的映射见 §6）

记法约定：`"…" ` 为字面终结符；`*` 零或多；`+` 一或多；`?` 可选；`|` 选择；`( )` 分组。产生式名用 `snake_case`。

---

## 1. 词法

### 1.1 保留关键字

```
module depends workspace public function struct interface impl enum const test target
let var shared if else while for in match return break continue spawn join external
true false
```

上下文关键字（仅特定产生式中作为关键字，其他位置可作标识符）：`map`、`java`、`where`、`static`、`instance`、`constructor`。

### 1.2 词法单元

| 单元 | 定义 |
|---|---|
| 标识符 | `[a-zA-Z_][a-zA-Z0-9_]*` |
| 整数字面量 | 十进制 `[0-9]+` 或十六进制 `0x[0-9a-fA-F]+` |
| 浮点字面量 | `[0-9]+\.[0-9]+`（`[eE][+-]?[0-9]+` 可选指数） |
| 字符串字面量 | `"…"`，转义 `\n \t \r \" \\ \uXXXX`；**无插值**（design §2.4） |
| 字符字面量 | `'x'`，同上转义 |
| 布尔字面量 | `true` / `false` |
| 行注释 | `// …` 到行尾（丢弃） |
| 文档注释 | `/// …`（连续行合并为一个 DocComment 节点，必须紧邻被注解声明） |
| 分隔符 | `( ) { } [ ] < > , : ; . => = ->` 及二元运算符 |

### 1.3 换行与分号

语句不强制分号，**不依赖换行消歧**（表达式与语句可在文法层区分，无需 JS 式 ASI）。分号 `;` 允许出现且被忽略。

---

## 2. 产生式

### 2.1 编译单元与模块头

```ebnf
compilation_unit  := module_decl depends_decl* decl* ;

module_decl       := "module" module_fullname ;
module_fullname   := dotted_name ( "/v" INT )? ;          (* 主版本进路径；省略 = /v1 *)
dotted_name       := ident ("." ident)* ;

depends_decl      := "depends" depends_item ("," depends_item)* ;
depends_item      := module_fullname version_constraint?
                   | "workspace:" dotted_name ;             (* 工作区协议，见 design §7.5 *)
version_constraint:= ">=" version ;                        (* v1 仅最小版本，配合 MVS *)
version           := INT "." INT "." INT ;
```

### 2.2 声明

```ebnf
decl := doc_comment?
        ( function_decl | struct_decl | interface_decl | impl_decl
        | enum_decl | const_decl | test_decl | external_decl ) ;

visibility        := "public" ;                             (* 省略 = internal *)

function_decl     := visibility? "function" type_params? ident
                     "(" param_list? ")" "->" type where_clause? block ;
param             := ident ":" type ;
type_params       := "<" ident ("," ident)* ">" ;
where_clause      := "where" type_constraint ("," type_constraint)* ;
type_constraint   := ident ":" type_name ;                  (* 约束必须显式给出 *)

struct_decl       := visibility? "struct" type_params? ident where_clause? "{" field* "}" ;
field             := doc_comment? ident ":" type ;

interface_decl    := visibility? "interface" type_params? ident where_clause? "{" method_sig* "}" ;
method_sig        := doc_comment? "function" type_params? ident
                     "(" param_list? ")" "->" type where_clause? ";" ;

impl_decl         := "impl" type_name "for" type_name where_clause? "{" function_decl* "}" ;

enum_decl         := visibility? "enum" type_params? ident where_clause? "{" variant* "}" ;
variant           := doc_comment? ident payload? ;
payload           := "(" type ")" ;

const_decl        := visibility? "const" ident ":" type "=" literal ;

test_decl         := doc_comment? "test" string "target" ":" dotted_name block ;
                    (* dotted_name 为被测函数全名；名字解析填充 Test.target_id *)

external_decl     := "external" "java" string "{" external_method* "}" ;
                    (* string 为目标类全限定名，如 "java.lang.Math" *)
external_method   := doc_comment? ext_modifier "function" ident
                     "(" param_list? ")" "->" type ( "as" string )? ;
                    (* as "jvmName"：符号名与 JVM 方法名分离（2026-10 决策），解决同名外部方法冲突 *)
ext_modifier      := "static" | "instance" | "constructor" ;  (* 对应 design §6.2 四类 invoke *)
```

### 2.3 类型

```ebnf
type              := type_name ("<" type_list ">")?         (* 名义类型 + 显式实参 *)
                   | "(" type_list? ")" "->" type ;         (* 函数类型 *)
type_name         := dotted_name ;                          (* 短名或全限定名，见 §4.2 *)
type_list         := type ("," type)* ;
```

### 2.4 语句与表达式

```ebnf
block             := "{" stmt* trailing_expr? "}" ;         (* 尾表达式给出块的值，见 §4.1 *)
trailing_expr     := expr ;
stmt              := let_stmt | assign_stmt | while_stmt | for_stmt
                   | return_stmt | "break" | "continue" | expr_stmt ;

let_stmt          := "shared"? ("let" | "var") ident ":" type "=" expr ;
                    (* shared 仅允许修饰 var，且类型必须为 Mutex<T> *)
assign_stmt       := lvalue "=" expr ;
lvalue            := path | field_access | index ;

while_stmt        := "while" expr block ;
for_stmt          := "for" ident ":" type "in" expr block ; (* 循环变量类型必须显式 *)
return_stmt       := "return" expr? ;
expr_stmt         := expr ;                                  (* 值为 Unit 的求值，或块值丢弃 *)
```

### 2.5 表达式（优先级由低到高）

```ebnf
expr              := or_expr ;
or_expr           := and_expr ("||" and_expr)* ;
and_expr          := eq_expr ("&&" eq_expr)* ;
eq_expr           := rel_expr (("==" | "!=") rel_expr)* ;
rel_expr          := shift_expr (("<" | "<=" | ">" | ">=") shift_expr)* ;
shift_expr        := bitor_expr (("<<" | ">>") bitor_expr)* ;
bitor_expr        := bitand_expr ("|" bitand_expr)* ;
bitand_expr       := add_expr ("&" add_expr)* ;
add_expr          := mul_expr (("+" | "-") mul_expr)* ;
mul_expr          := unary (("*" | "/" | "%") unary)* ;
unary             := ("-" | "!") unary | join_expr ;
join_expr         := "join" postfix ;                        (* join 绑定紧于二元运算 *)
postfix           := primary (("." ident) | ("[" expr "]") | call_suffix)* ;
call_suffix       := "(" arg_list? ")" ;
primary           := literal | path | spawn_expr
                   | if_expr | iflet_expr | match_expr | "(" expr ")" ;

spawn_expr        := "spawn" primary call_suffix ;           (* spawn f(args)：被 spawn 的必须是调用 *)
if_expr           := "if" expr block ("else" (if_expr | block))? ;
iflet_expr        := "if" "let" enum_ctor_pattern "=" expr block ("else" block)? ;
match_expr        := "match" expr "{" match_arm+ "}" ;
match_arm         := pattern "=>" (block | expr) ;
pattern           := "_" | literal | enum_ctor_pattern ;
enum_ctor_pattern := type_name ("(" ident ")")? ;            (* 可选单变量绑定载荷，见 §4.4 *)

arg               := ident ":" expr | expr ;                 (* 具名/位置实参，可混用 *)
literal           := INT | FLOAT | string | char | "true" | "false"
                   | "[" expr_list? "]"                      (* 数组字面量 *)
                   | "map" "{" (expr ":" expr)*? "}" ;       (* map 字面量，map 为上下文关键字 *)
field_access      := postfix "." ident ;                     (* 仅数据字段，无访问器 *)
index             := postfix "[" expr "]" ;
path              := dotted_name ;                           (* 值引用；见 §4.2 *)
expr_list         := expr ("," expr)* ;
```

完整优先级链：`|| < && < ==,!= < 关系 < 移位 < | < & < +,- < *,/,% < 一元 < join < 后缀`。

---

## 3. 运算符优先级总表

| 级 | 运算符 | 结合 |
|---|---|---|
| 1（最低） | `\|\|` | 左 |
| 2 | `&&` | 左 |
| 3 | `== !=` | 不结合要求 |
| 4 | `< <= > >=` | 左 |
| 5 | `<< >>` | 左 |
| 6 | `\|` | 左 |
| 7 | `&` | 左 |
| 8 | `+ -` | 左 |
| 9 | `* / %` | 左 |
| 10 | `- !`（一元） | 右 |
| 11 | `join` | 前缀 |
| 12 | `. [] f(...)` | 后缀 |

无运算符重载、无自定义优先级（design §2.4）。

---

## 4. 文法层设计说明（需决策门确认的推论）

### 4.1 块值语义

`if` / `match` / `if-let` 是**表达式**；其分支块以**最后一个表达式**作为块的值（Kotlin 语义）。语句位置出现的 `if c { … }` 是 ExprStmt，块值被丢弃。该规则是「if 分支类型必须一致」（design §2.2）的检查基础。

### 4.2 名字写法与解析职责

- 文本中允许写短名（`Point`）或全限定名（`org.example.geom.Point`），与渲染器输出一致。
- 解析器把**书写形态**存入 `Path.written`；短名到全名的还原（**渲染逆映射**）与 `resolved_id` 填充由名字解析阶段完成（依赖 depends 图与接口 AST，解析器单文件信息不足）。
- **点链最大吞噬**：表达式中的 `a.b.c` 一律解析为**单个** `Path`（written = `"a.b.c"`）。它究竟是符号引用还是「局部变量 + 字段访问链」，单文件信息不足，拆分为 `Path` + `FieldAccess` 链是**名字解析阶段**的职责。仅当点链的接收者不是 Path（如 `arr[0].x`、`f(c).x`）时，解析器才直接产生 `FieldAccess`。

### 4.3 调用与 `self` 约定（无点号方法糖，已确认 2026-10-29）

v1 **没有** `obj.method()` 形态：点号仅用于数据字段访问。`c.area()` 的本质是「隐式插入 self 实参 + 按接收者类型选择函数」，前者违背参数显式，后者是隐式重载（判定调用目标需要整个依赖闭包的信息）。`impl` 函数以首个参数名 `self` 为约定（类型 = 所属 struct）；**接口侧的接收者用普通类型参数表达，不引入 `Self` 关键字**：

```
interface Shape<S> {
    function area(self: S) -> Int;
}

impl Shape<Circle> for Circle {
    function area(self: Circle) -> Int { … }
}

// 调用：area 是普通函数名，走普通名字解析；self 是普通参数，具名实参让角色显式
let a: Int = area(self: c);
```

**符号命名规则**：`impl` 函数在符号表中的全名挂在**接口全名**下（如 `org.example.geom.Shape.area`），不挂在 struct 名下——否则一个 struct 实现多个接口时的同名方法又构成重载。

（`c.` 式的方法补全留给工具层按「接收者类型的 impl」提供，不进语言语义。）

### 4.4 模式

v1 模式仅四种：通配 `_`、字面量、枚举构造（`Option.Some`）、带单变量载荷绑定的枚举构造（`Result.Ok(v)`）。无元组/结构解构（design §2.4 禁解构赋值）。载荷通过绑定变量访问。

### 4.5 其余约定

1. **预置依赖**：每个模块隐式 `depends loong.core/v1`——全语言唯一的隐式项，由编译器注入并在依赖图中显式可见（`Int`/`String`/`Option`/`Result` 等皆为核心库名义类型，非关键字）。
2. **send/recv 不是关键字**：`channel<T>` 的 `send(ch, v)` / `recv(ch)` 是 loong.core 函数，经普通调用产生。`spawn`/`join` 因绑定语义特殊（返回 `Handle<T>`）保留为关键字。
3. **map 字面量**用 `map { k: v }` 前缀关键字，避免与块 `{ }` 歧义；数组用 `[ ]`。
   **Map 索引语义**：`m[k]` 返回 `Option<V>`——缺席键显式处理（与无 null 哲学一致；数组越界仍为运行时错误，因数组长度是局部可知的）。
   **Object 互操作类型**：外部声明的形参/返回可为 `Object`（擦除后的 Java 引用），任意类型实参可显式收敛给它（调用点自动装箱原始类型）。**Int32 互操作类型**（2026-10 决策，解除前述限制）：`Int32` 映射 JVM `I`，用于外部声明的 int 宽度形参/返回（如 `size() -> Int32`、`get(i: Int32)`）；与 `Int` 的衔接一律经显式转换 `to32(Int) -> Int32` / `to64(Int32) -> Int`（调用点与返回处自动插入 L2I/I2L）。`Int32` 不参与运算符与字面量——纯互操作通道。
4. **泛型约束只写在 where 子句**（design §2.2 的 `where T: Interface` 形态），类型参数列表本身不带内联约束，禁止裸类型参数。**例外**：接口的首个类型参数是接收者占位（§4.3），由 impl 显式绑定，无需 where 约束。**`Any` 标记接口**（2026-10 决策 A）：一切类型隐式实现，`where T: Any` 表达无约束泛型（如 `unwrapOr<T>(o: Option<T>, def: T) -> T where T: Any`），保持「约束必须显式」纪律的同时不限制组合子。泛型为擦除式：类型参数映射 JVM Object，调用点按具体类型装箱/解箱/CHECKCAST。
5. **版本约束 v1 只有 `>=`**：MVS 只需要最小版本（design §7.4）。
6. **Unit**：无返回值的函数写 `-> Unit`；`Unit` 是核心库类型。
   **用户自定义迭代协议**（2026-10-30 决策：显式状态机，不允许 self 变 var）：
   `impl Iterable<E> for X` 须提供两个纯函数——`start(self: X) -> S`（初始状态）与
   `next(self: X, state: S) -> Option<Y>`（`Y` 为含 `elem: E`、`state: S` 字段的本模块 struct）。
   `for v in x` 降级为状态循环：`st = start(x); loop { next(x, st) 为 None 则结束；v = .elem; st = .state }`。
   状态推进完全显式且不可变——无隐藏可变迭代器对象。Step 可来自依赖模块（如 loong.core.Step）。
7. **结构体构造 = 调用**：`Point(x: 1, y: 2)`——以 struct 名为被调方的调用，具名实参一一对应字段；与枚举构造 `Color.Blue(v)` 形态统一。泛型 struct 的构造从期望类型绑定类型参数（赋值注释或形参类型）。

---

## 5. 禁用特性的文法层体现（design §2.4 清单）

以下特性**没有对应产生式**，语法上无法表达：继承与重写、默认方法、抽象类、运算符/函数重载、宏与注解与反射、字符串插值、属性访问器、解构赋值、多返回值、尾随闭包、匿名函数、try/catch/finally、async/await、`?` 错误传播、默认参数、可变参数、通配符导入（无 import 产生式）、标签 break/continue、goto、null 字面量、三元运算符。

---

## 6. 产生式 → AST kind 映射（摘要）

| 产生式 | kind | 备注 |
|---|---|---|
| module_decl | `Module` | |
| depends_decl / depends_item | `Depends` × N | |
| function_decl / external_method | `Function` / `ExternalMethod` | |
| param | `Param` | |
| type_params + where_clause | `TypeParam` × N | 约束存字段 |
| struct_decl / field | `Struct` / `Field` | |
| interface_decl / method_sig | `Interface` / `MethodSig` | |
| impl_decl | `Impl` | interface/struct 全名存字段 |
| enum_decl / variant | `Enum` / `Variant` | |
| const_decl | `Const` | 值为 Literal 子节点 |
| test_decl | `Test` | target_name 存字段，target_id 由解析填充 |
| external_decl | `ExternalClass` | |
| doc_comment | `DocComment` | 被注解声明的首子节点 |
| block | `Block` | |
| let_stmt | `Let` | mutable/shared 存字段 |
| assign_stmt | `Assign` | |
| if_expr / iflet_expr | `If` / `IfLet` | |
| while_stmt / for_stmt | `While` / `For` | |
| match_expr / match_arm | `Match` / `MatchArm` | 模式存 MatchArm 字段 |
| return_stmt / break / continue | `Return` / `Break` / `Continue` | |
| expr_stmt | `ExprStmt` | |
| call（callee + arg） | `Call` + `Arg` × N | |
| 二元 / 一元 | `Binary` / `Unary` | 运算符存字段 |
| literal | `Literal` | 数组/map 经子节点表达 |
| path / field_access / index | `Path` / `FieldAccess` / `Index` | |
| spawn / join | `Spawn` / `Join` | |
| map 字面量条目 | `MapEntry` | |

字段与子节点的完整定义见 `ast-schema-v1.md`。
