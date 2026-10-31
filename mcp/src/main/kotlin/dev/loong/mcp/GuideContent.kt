package dev.loong.mcp

/**
 * MCP 语言导览内容：initialize.instructions（常驻精简版）+ prompts（任务手册）+
 * resources（参考文档）。
 *
 * 与工具面互补——工具面是操作入口，这里教 agent「怎么写 loong、怎么经节点操作改 AST」。
 * 内容与以下文档保持同步（各文档头部标注来源，修改 spec 时同步更新）：
 * - doc/spec/grammar-v1.md
 * - doc/spec/ast-schema-v1.md
 * - doc/spec/diagnostics-registry.md
 * 示例与 daemon 缺省模块（daemon/server/DefaultModules.kt）同源。
 */
internal object GuideContent {

    // ---------- initialize.instructions（常驻上下文，保持精简；详篇走 prompts/resources） ----------

    val INSTRUCTIONS = """
        Loong 是面向 AI 结对的显式结构语言，编译到 JVM。代码不是文件，而是常驻 daemon 的
        AST 节点表：你不能写文件，只能用本 server 的节点工具（insert_node / replace_node /
        move_node / delete_node）修改 AST；每次写操作的响应都自带重算后的诊断，无需单独 check。
        模块级管理：list_modules 看全部模块，create_module 用整段源码文本新建模块，
        delete_module 整体卸载；build_project 把项目打包成可运行 jar（产物在项目目录 build/）。

        语言要点（写代码前必读）：
        - 模块以 `module org.example.foo` 声明（反向域名，省略版本段 = /v1）；Module 根节点 id 恒为 1。
        - 类型处处显式：`let x: Int = 1`、参数 `n: Int`、返回 `-> Int`；无分号。
        - 无 null、无异常：缺席与失败用 Option/Result 表达，match 分支处理。
        - 声明必须带 /// 文档注释（DocComment 必须是声明的第一个子节点，否则 E_MISSING_DOC）；
          模块级 public 函数必须被至少一个 test 引用（否则 E_NO_TEST）。
        - if / match 是表达式，块尾表达式即块值；调用支持具名实参 `f(x: 1)`。
        - 无 obj.method() 语法糖：impl 函数以首参 self 约定，调用写 `area(self: p)`。
        - Int/String/Bool/Option 等是 loong.core 库类型而非关键字；
          println/print/concat/str/len/newArray/to32/to64 是内建函数。

        标准工作流：get_module_interface（读渲染视图）→ insert_node / edit（响应自带诊断）
        → 诊断清零 → run_tests → request_human_review（需要人类把关时）→ commit。

        深入阅读：prompts（write-function / new-module / fix-diagnostics 任务手册）与
        resources（loong://guide/grammar 文法、loong://reference/ast-kinds 节点表、
        loong://reference/diagnostics 诊断对照、loong://examples/hello 示例）。
    """.trimIndent()

    // ---------- prompts（任务手册，按需拉取） ----------

    internal class PromptArg(val name: String, val description: String, val required: Boolean = false)

    internal class Prompt(
        val name: String,
        val description: String,
        val args: List<PromptArg>,
        private val body: (Map<String, String>) -> String,
    ) {
        val descriptor: Map<String, Any?> = mapOf(
            "name" to name,
            "description" to description,
            "arguments" to args.map { mapOf("name" to it.name, "description" to it.description, "required" to it.required) },
        )

        fun render(args: Map<String, String>): String = body(args)
    }

    /** 手册参数缺省占位：<key> 形态，渲染结果可读且能自解释。 */
    private fun arg(args: Map<String, String>, key: String): String =
        args[key]?.takeIf { it.isNotBlank() } ?: "<$key>"

    val PROMPTS = listOf(
        Prompt(
            "write-function",
            "在既有模块中新增一个函数并让测试通过——逐步节点操作手册（design §8.6 九步循环的函数版）",
            listOf(
                PromptArg("module", "模块全名，如 demo.app/v1", true),
                PromptArg("name", "函数名", true),
                PromptArg("params", "参数清单文本，如 x: Int, y: Int（可留空）"),
                PromptArg("returns", "返回类型，如 Int / String / Unit", true),
            ),
        ) { a ->
            val module = arg(a, "module")
            val name = arg(a, "name")
            val params = a["params"]?.takeIf { it.isNotBlank() } ?: ""
            val returns = arg(a, "returns")
            """
            在模块 $module 中新增函数 $name 并让测试通过。按步骤逐个调用工具；
            每步写操作的响应都自带最新诊断——跟着诊断走比一次写完更稳。

            目标签名：function $name($params) -> $returns

            1. 读当前结构
               get_module_interface {"module":"$module"}
               函数是 Module 根（id=1）的直接子节点，选好插入位置 index。

            2. 插入函数骨架（此时报 E_MISSING_DOC 属预期）
               insert_node {"module":"$module","parent":1,"index":<n>,"kind":"Function",
                 "fields":{"name":"$name","visibility":"public","return_type":"$returns"}}
               响应的 value 即新函数节点 id，下称 FID。

            3. 补文档注释——必须是 FID 的第一个子节点（index 0）
               insert_node {"module":"$module","parent":<FID>,"index":0,"kind":"DocComment",
                 "fields":{"text":"<一句话英文描述>"}}

            4. 逐个补参数（Param，index 依次 1, 2, ...）
               insert_node {"module":"$module","parent":<FID>,"index":1,"kind":"Param",
                 "fields":{"name":"x","type":"Int"}}

            5. 补函数体
               insert_node {"module":"$module","parent":<FID>,"index":<末位>,"kind":"Block","fields":{}}  → 记 BID
               insert_node {"module":"$module","parent":<BID>,"index":0,"kind":"Return","fields":{}}      → 记 RID
               insert_node {"module":"$module","parent":<RID>,"index":0,"kind":"Literal",
                 "fields":{"lit_kind":"int","value":"7"}}
               返回表达式也可以是 Call / Binary / Path：Call 的 kids = 被调 Path + Arg*；
               具名实参在 Arg 的 fields 加 {"name":"x"}；函数体末位的 ExprStmt 即块值。

            6. public 函数必须配 test（否则 E_NO_TEST）
               insert_node {"module":"$module","parent":1,"index":<n>,"kind":"Test",
                 "fields":{"name":"$name basic","target_name":"$module.$name"}}
               target_name = 模块全名.函数名；再给 Test 挂一个 Block 子节点（可为空块）。

            7. run_tests {"module":"$module"} 全绿后 commit {"module":"$module","message":"add $name"}。

            诊断速查：E_MISSING_DOC=补 DocComment 首子节点；E_NO_TEST=补 Test；
            E_UNKNOWN_NAME=名字拼错或缺依赖；E_NOT_EXHAUSTIVE=match 补全分支或加 `_` 臂。
            完整对照见 fix-diagnostics 手册与 loong://reference/diagnostics。
            """.trimIndent()
        },
        Prompt(
            "new-module",
            "规划一个新模块：命名规范（ADR-0001）+ 最小可用脚手架 + 已预置模块说明",
            listOf(PromptArg("name", "拟采用的模块全名，如 org.example.http", true)),
        ) { a ->
            val name = arg(a, "name")
            """
            新模块 $name 的规划要点：

            命名规范（ADR-0001）：反向域名 org.example.foo，全小写；
            主版本作路径段（org.example.http/v2），省略 = /v1；loong.* 为官方保留前缀，第三方不得使用。

            装载路径：调用 create_module 工具提交整段模块文本（整模块装卸的唯一工具面入口）；
            装载后你再用节点工具增量修改。注意：新项目已预置 main（hello 入口）与
            example（语言能力样板）两个模块——多数任务直接在既有模块上加函数即可
            （见 write-function 手册）；不再需要的模块用 delete_module 卸载（不可恢复）。

            最小可用模块文本（与渲染视图同构，直接作 create_module 的 source）：

            module $name

            /// Greets the caller by name.
            public function greet(who: String) -> String {
                return who
            }

            test "greet" target: $name.greet {
                let r: String = greet(who: "loong")
            }

            纪律提醒：每个声明前有 ///；public 函数必有 test；struct 的每个字段、
            interface 的每个方法签名也各需 ///（E_MISSING_DOC 一视同仁）。
            """.trimIndent()
        },
        Prompt(
            "fix-diagnostics",
            "把模块诊断清零：诊断码 → 含义 → 修复动作对照表",
            listOf(PromptArg("module", "模块全名，如 demo.app/v1", true)),
        ) { a ->
            val module = arg(a, "module")
            """
            修复模块 $module 的诊断：

            1. get_diagnostics {"module":"$module"} 得到 [{code, message, node}] 列表；
               node 即节点 id，可用 get_node 查看字段与子节点细节。
            2. 按下表修复；每次写操作响应自带新诊断，迭代到清零后 run_tests。

            | 诊断码 | 含义 | 修复 |
            |---|---|---|
            | E_MISSING_DOC | 声明无非空 doc | 在声明节点 index 0 插入 DocComment {"text":"..."} |
            | E_NO_TEST | public 函数无测试 | 加 Test（target_name=模块全名.函数名）并挂 Block |
            | E_TOO_LONG | 函数体超 50 语句 | 拆分为小函数（语言强制，无开关） |
            | E_UNKNOWN_NAME | 引用的名字不存在 | 修 Path 的 written；跨模块符号先 add_dependency |
            | E_UNKNOWN_TYPE | 类型名无法解析 | 修类型文本（支持全限定名 org.example.Point） |
            | E_DUPLICATE_DECL | 同作用域重名 | 改名或删除旧声明 |
            | E_TYPE_MISMATCH | 期望/实际类型不一致 | 修表达式或注解；if/match 各分支须同型；返回值对齐 return_type |
            | E_ARITY_MISMATCH | 实参数与签名不符 | 增删 Call 下的 Arg 节点 |
            | E_NAMED_ARG_UNKNOWN | 具名实参不在签名中 | 核对 Param 名（Arg.fields.name） |
            | E_BAD_OPERAND_TYPE | 操作数不支持该运算符 | 无运算符重载——换类型或改用函数 |
            | E_NOT_EXHAUSTIVE | match 未穷尽枚举 | 补全全部构造分支或加 `_` 臂 |
            | E_NOT_ITERABLE | for 对象不可迭代 | 为其类型 impl Iterable（start/next 显式状态机） |
            | E_ASSIGN_IMMUTABLE | 给 let/const 赋值 | Let 的 mutable 置 true（或改用新绑定） |
            | E_UNKNOWN_FIELD | struct 无该字段 | 核对字段名（无访问器，纯数据字段） |
            | E_IMPL_SIGNATURE_MISMATCH | impl 与接口签名不一致 | 参数名/类型/返回逐一对齐；接收者型参按 impl 实参绑定 |
            | E_CYCLE_DEPENDS | depends 依赖成环 | 删除自依赖（remove_dependency） |
            | E_MISSING_TYPE_CONSTRAINT | 裸类型参数 | where 子句补约束（无约束泛型用 where T: Any） |

            完整注册表与 JVM 互操作类诊断：loong://reference/diagnostics。
            """.trimIndent()
        },
    )

    // ---------- resources（参考文档，URI 寻址） ----------

    internal class Resource(val uri: String, val name: String, val description: String, val text: String) {
        val descriptor: Map<String, Any?> = mapOf(
            "uri" to uri,
            "name" to name,
            "description" to description,
            "mimeType" to "text/markdown",
        )
    }

    val RESOURCES = listOf(
        Resource(
            "loong://guide/quickstart",
            "快速上手",
            "十分钟学会在 loong 里写并运行一个函数：语法最小集 + 工作流",
            """
            # loong 快速上手

            代码以 AST 常驻 daemon；人类用 Web UI / CLI，你（agent）用本 MCP 工具面。
            新项目已预置 `main`（打印 Hello 的入口）与 `example`（能力样板）模块。

            ## 第一个函数

            经工具面在 main/v1 上加一个函数（节点操作序列见 prompts 的 write-function 手册），
            渲染视图（get_module_interface）里它长这样：

            ```
            module main/v1

            /// Doubles an integer.
            public function dbl(x: Int) -> Int {
                return x * 2
            }

            test "dbl" target: main.dbl {
                let r: Int = dbl(x: 21)
            }
            ```

            ## 语法最小集

            - 声明：`public function f(a: Int) -> Int { ... }`；struct/interface/impl/enum/const/test 同级。
            - 变量：`let n: Int = 1`（不可变）、`var i: Int = 0`（可变）；类型必须显式。
            - 控制流：`if c { } else { }`、`while c { }`、`for v: Int in xs { }`、
              `match o { Option.Some(v) => v Option.None => 0 }`。
            - 调用：`f(x: 1)` 具名实参优先；数组 `[1, 2, 3]`；下标 `xs[0]`。
            - 输出：`println("text")`（内建，参数须为 String，数字用 `str(n)` 转）。

            ## 工作流（每一步响应自带诊断）

            list_modules（看现状）→ create_module（新模块；既有模块跳过）
            → get_module_interface → insert_node/replace_node/delete_node（增量改 AST）
            → 诊断清零 → run_tests → request_human_review（按需）→ commit
            → build_project（按需：打包成可运行 jar）。

            更多：loong://guide/grammar（文法）、loong://reference/ast-kinds（节点表）、
            loong://examples/hello（完整示例）。
            """.trimIndent(),
        ),
        Resource(
            "loong://guide/grammar",
            "文法要点",
            "loong v1 文法精选：声明、类型、语句、表达式、优先级、约定与禁用特性",
            """
            # loong 文法要点（精选自 doc/spec/grammar-v1.md，完整版以该文件为准）

            ## 模块与依赖

            ```
            module org.example.http          // 反向域名；省略版本段 = /v1
            depends org.example.core >= 1.2.0
            depends workspace: org.example.util   // 工作区协议：直接用本工作区模块
            ```

            每个模块隐式 depends loong.core/v1（唯一隐式项；Int/String/Option/Result 皆出自它）。

            ## 声明

            ```
            /// 文档注释（/// 开头；声明必需，struct 字段与 interface 方法同样必需）
            public function clamp(a: Int, lo: Int, hi: Int) -> Int { ... }
            public struct Point { x: Int  y: Int }              // 字段各需 ///
            public interface Shape<S> { function area(self: S) -> Int }   // 方法签名以分号结尾
            impl Shape<Point> for Point { function area(self: Point) -> Int { ... } }
            public enum Color { Red  Green  Blue(Int) }
            public const MAX: Int = 100
            test "clamp basics" target: org.example.mod.clamp { ... }
            external java "java.lang.Math" { static function pow(x: Float, y: Float) -> Float }
            ```

            - 可见性只有 public（省略 = internal）；public 函数必须被 test 引用。
            - 泛型约束只写在 where 子句：`function f<T>(v: T) -> T where T: Any`；
              无约束泛型用 `where T: Any`（Any 是标记接口，一切类型隐式实现）。
            - impl 函数全名挂接口名下；调用是普通函数名 + 具名 self 实参：`area(self: p)`。
            - external 的调用类别：static / instance / constructor；
              符号名与 JVM 方法名可分离（`as "jvmName"`）；Int32 为 JVM int 互操作通道。

            ## 类型

            - 名义类型 + 显式实参：`Int`、`Array<Int>`、`Map<String, Point>`、`Option<T>`、`Mutex<T>`。
            - 函数类型：`(Int, Int) -> Bool`；类型文本存于字段的 type/return_type。

            ## 语句与表达式

            - 语句：let/var、赋值（仅 var 与字段/下标可赋值）、while、for、return、break、continue。
            - for 迭代协议（显式状态机，无可变迭代器对象）：类型 impl `Iterable<E>` 须提供
              `start(self: X) -> S` 与 `next(self: X, state: S) -> Option<Step>`，
              Step 为含 `elem: E`、`state: S` 字段的 struct。
            - 表达式：if / match / if-let 是表达式（块值 = 末位 ExprStmt 的表达式）；
              模式四种：`_`、字面量、枚举构造 `Color.Red`、带载荷绑定 `Result.Ok(v)`。
            - map 字面量：`map { 1: "one" }`（前缀关键字防歧义）；Map 索引 `m[k]` 返回 `Option<V>`。
            - 并发：`spawn f(args)` 返回 `Handle<T>`，`join h` 等待取值；
              channel 经 `send(ch, v)` / `recv(ch)`（普通函数，非关键字）。
            - 优先级（低→高）：`||` < `&&` < `== !=` < 关系 < 移位 < `|` < `&` < `+ -` < `* / %` < 一元 < join < 后缀。

            ## 禁用特性（语法上无法表达）

            继承/重写、默认方法、抽象类、运算符与函数重载、宏/注解/反射、字符串插值、
            属性访问器、解构赋值、多返回值、匿名函数、try/catch、async/await、`?` 传播、
            默认参数、可变参数、三元运算符、null 字面量。
            """.trimIndent(),
        ),
        Resource(
            "loong://reference/ast-kinds",
            "AST 节点参考",
            "全部节点 kind 的 fields 与 kids 合法形态 + 编辑操作契约（做节点编辑的权威参考）",
            """
            # loong AST 节点参考（源：doc/spec/ast-schema-v1.md §4/§6，改 spec 时同步）

            节点 = { id, kind, fields, kids, parent }。字段值四种：Str / Int / Bool / Ref(节点id)。
            类型标注（type/return_type/constraint/payload_type）存规范类型文本，如 "Array<Int>"。
            Module 根节点 id 恒为 1。

            ## 声明类

            | kind | fields | kids |
            |---|---|---|
            | Module | name(含 /vN)、host_class? | Depends*, 声明* |
            | Depends | module_name、constraint?(如 ">=1.2.0")、source(registry/workspace) | — |
            | Function | name、visibility(public/internal)、return_type | DocComment?, TypeParam*, Param*, Block |
            | Param | name、type | — |
            | TypeParam | name、constraint? | — |
            | Struct | name、visibility | DocComment?, TypeParam*, Field* |
            | Field | name、type | DocComment? |
            | Interface | name、visibility | DocComment?, TypeParam*, MethodSig* |
            | MethodSig | name、return_type | DocComment?, TypeParam*, Param* |
            | Impl | interface(全名,如 Shape<Point>)、struct(全名) | Function* |
            | Enum | name、visibility | DocComment?, TypeParam*, Variant* |
            | Variant | name、payload_type? | DocComment? |
            | Const | name、visibility、type | DocComment?, Literal |
            | Test | name、target_name(源文本全名) | DocComment?, Block |
            | DocComment | text | — |
            | ExternalClass | class_fqn | ExternalMethod* |
            | ExternalMethod | name、call_kind(static/instance/constructor)、return_type | DocComment?, TypeParam*, Param* |

            ## 语句类

            | kind | fields | kids |
            |---|---|---|
            | Block | — | stmt*（末位允许尾表达式，即块值） |
            | Let | name、type、mutable(bool)、shared(bool) | 初始化表达式 |
            | Assign | — | 目标(Path/FieldAccess/Index)、值表达式 |
            | If | — | 条件、then-Block、(else-Block 或 else-If)? |
            | IfLet | path(枚举构造全名)、binding | 被检表达式、then-Block、else-Block? |
            | While | — | 条件、Block |
            | For | var_name、var_type | 迭代对象表达式、Block |
            | Match | — | 被检表达式、MatchArm+ |
            | MatchArm | pattern_kind(wildcard/literal/enum_ctor)、literal?、path?、binding? | 体(Block 或表达式) |
            | Return | — | 表达式?（缺省 = Unit） |
            | Break / Continue | — | — |
            | ExprStmt | — | 表达式 |

            ## 表达式类

            | kind | fields | kids |
            |---|---|---|
            | Call | — | 被调 Path、Arg* |
            | Arg | name?(具名实参) | 实参表达式 |
            | Binary | op | lhs、rhs |
            | Unary | op(- 或 !) | 操作数 |
            | Literal | lit_kind(int/float/bool/string/char/array/map)、value?(源文本; array/map 无) | array: 元素*; map: MapEntry* |
            | MapEntry | — | key、value |
            | Path | written(源文本)、full_name?、resolved_id?(Ref,解析填充) | — |
            | FieldAccess | field | 接收者 |
            | Index | — | 目标、下标 |
            | Spawn | — | Call |
            | Join | — | Handle 表达式 |

            ## 编辑操作（insert_node / replace_node / move_node / delete_node 的底层语义）

            | 操作 | 语义 |
            |---|---|
            | insert(parent, index, kind, fields) | 创建并挂接为 parent 第 index 个子节点，返回新 id |
            | replace(id, fields?, kids?) | 原地改字段；给 kids 则旧子树整体删除、新 kids 替换 |
            | move(id, newParent, index) | 换位（禁止移入自身子树） |
            | delete(id) | 整棵子树移出，id 永不复用 |

            ## 关键不变量（违反 = E_INVARIANT_VIOLATION）

            - DocComment 若存在必须是声明的**第一个子节点**（E_MISSING_DOC 检查的就是它）。
            - 点链 `a.b.c` 是单个 Path（written="a.b.c"）；FieldAccess 仅当接收者不是 Path。
            - 块值 = 末位子节点恰为 ExprStmt 时的其表达式，无尾表达式则块值为 Unit。
            - Test.target_id 由名字解析填充，编辑时只写 target_name。
            """.trimIndent(),
        ),
        Resource(
            "loong://reference/diagnostics",
            "诊断码注册表",
            "全部诊断码：触发条件与修复建议（码永不改名改义，只能新增）",
            """
            # loong 诊断码注册表（源：doc/spec/diagnostics-registry.md；修复建议为编辑视角）

            诊断随每次写操作响应返回；v1 全部为 Error（阻断 codegen）。

            ## 词法 / 语法

            | 代码 | 触发 | 修复 |
            |---|---|---|
            | E_UNEXPECTED_TOKEN / E_UNEXPECTED_EOF | token 不合产生式 / 输入提前结束 | 检查节点结构与必需子节点 |
            | E_UNTERMINATED_STRING / E_INVALID_ESCAPE / E_INVALID_NUMBER | 字面量非法 | 修 Literal.value 源文本（转义 \n \t \" \\ \uXXXX） |
            | E_MISSING_MODULE_DECL | 文件不以 module 开头 | Module 根必须在首位 |
            | E_BAD_MODULE_NAME | 模块名不符 ADR-0001 | 反向域名全小写；版本段 /vN |

            ## 名字解析

            | 代码 | 触发 | 修复 |
            |---|---|---|
            | E_UNKNOWN_NAME | 引用的符号不存在 | 修 Path.written；跨模块先 add_dependency |
            | E_UNKNOWN_TYPE | 类型名无法解析 | 修类型文本；可用全限定名 |
            | E_AMBIGUOUS_NAME | 短名映射到多个全名 | 改用全限定名 |
            | E_DUPLICATE_DECL | 同作用域重名 | 改名或删除 |
            | E_UNKNOWN_MODULE | 依赖目标不存在（含幻觉依赖） | 核对模块名与版本；workspace: 协议用本工作区模块 |
            | E_CYCLE_DEPENDS | depends 成环 | 删除环上依赖 |
            | E_UNRESOLVED_TARGET | Test.target_name 无对应函数 | 核对目标函数全名 |

            ## 类型检查

            | 代码 | 触发 | 修复 |
            |---|---|---|
            | E_TYPE_MISMATCH | 期望/实际不一致（含分支不同型、return 不符、实参不符） | 对齐表达式与注解 |
            | E_ARITY_MISMATCH | 实参数不符 | 增删 Arg |
            | E_NAMED_ARG_UNKNOWN | 具名实参不在签名 | 核对 Param 名 |
            | E_BAD_OPERAND_TYPE | 操作数类型不支持运算符 | 无重载——换类型或改函数调用 |
            | E_CALL_NOT_FUNCTION | 被调路径不是函数 | 修 Call 的被调 Path |
            | E_NOT_EXHAUSTIVE | match 未穷尽 | 补全构造或 `_` 臂 |
            | E_PATTERN_TYPE_MISMATCH | 模式与被检类型不符 | 修 MatchArm.pattern |
            | E_NOT_ITERABLE | for 对象未实现 Iterable | 补 start/next 状态机 impl |
            | E_MISSING_TYPE_CONSTRAINT | 裸类型参数 | where 补约束；无约束用 T: Any |
            | E_CONSTRAINT_NOT_SATISFIED | 泛型实参不满足约束 | 换实参或补 impl |
            | E_ASSIGN_IMMUTABLE | 给 let/const 赋值 | mutable 置 true 或用新绑定 |
            | E_SHARED_REQUIRES_MUTEX | shared 绑定非 Mutex<T> | shared 仅限 var + Mutex<T> |
            | E_UNKNOWN_FIELD | struct 无该字段 | 核对字段名（纯数据字段，无访问器） |

            ## 强制规则（纪律层，编译器强制）

            | 代码 | 触发 | 修复 |
            |---|---|---|
            | E_MISSING_DOC | Function/Struct/Interface（含 Field/MethodSig）无非空 doc | index 0 插 DocComment |
            | E_NO_TEST | 模块级 public 函数无 test 引用 | 加 Test（target_name=全名.函数名）|
            | E_TOO_LONG | 函数体 > 50 直接语句 | 拆函数 |
            | E_IMPL_SIGNATURE_MISMATCH | impl 与 MethodSig 不完全一致 | 名/类型/返回逐项对齐（接收者按 impl 实参绑定） |

            ## JVM 互操作 / 内部

            | 代码 | 触发 |
            |---|---|
            | E_EXTERNAL_CLASS_NOT_FOUND / E_EXTERNAL_METHOD_NOT_FOUND / E_EXTERNAL_DESCRIPTOR_MISMATCH | external 声明与目标类/方法/描述符不符 |
            | E_INVARIANT_VIOLATION | 节点表不变量被破坏（工具链缺陷，非你的错误） |
            | E_INTERNAL | 引擎内部错误（附追踪信息） |
            """.trimIndent(),
        ),
        Resource(
            "loong://examples/hello",
            "可运行示例",
            "从 hello 到 struct/interface/impl/迭代/控制流的完整示例（与空项目预置模块同源）",
            """
            # loong 可运行示例（与空项目预置的 main / example 模块同源）

            ## main：最小入口（println 为内建；函数名 main 时生成 JVM 入口桥）

            ```
            module main

            /// Entry point of a fresh loong project: greet the console.
            function main() -> Unit {
                println("Hello , loong!")
            }
            ```

            ## example：语言能力样板

            struct 与字段（各需 ///）、接口与实现（接收者用型参 S 表达，impl 绑定后调用具名 self）：

            ```
            /// A point in the plane.
            struct Point {
                /// x coordinate
                x: Int
                /// y coordinate
                y: Int
            }

            /// Shapes with a computable area.
            interface Shape<S> {
                /// Area of the shape.
                function area(self: S) -> Int
            }

            impl Shape<Point> for Point {
                /// Rectangle area: width times height.
                function area(self: Point) -> Int {
                    return self.x * self.y
                }
            }
            ```

            枚举 + match（分支是表达式；模式支持枚举构造与 `_`）：

            ```
            /// Classifies v against bound: smaller, equal or bigger.
            public function classify(v: Int, bound: Int) -> Cmp {
                if v < bound {
                    return Cmp.Lt
                }
                if v == bound {
                    return Cmp.Eq
                }
                return Cmp.Gt
            }

            /// Doubles the value when present, def otherwise.
            public function twiceOr(o: Option<Int>, def: Int) -> Int {
                match o {
                    Option.Some(v) => v * 2
                    Option.None => def
                }
            }
            ```

            泛型 + where 约束（无约束泛型用 T: Any）：

            ```
            /// First element of the array, def when empty.
            public function firstOr<T>(xs: Array<T>, def: T) -> T where T: Any {
                if len(xs) == 0 {
                    return def
                }
                return xs[0]
            }
            ```

            for 迭代 = 显式状态机（impl Iterable 提供 start/next；next 返回 None 结束）：

            ```
            /// Step of a range iteration: yielded element plus next state.
            struct IterStep {
                /// yielded element
                elem: Int
                /// next state
                state: Int
            }

            /// A half-open numeric range [lo, hi).
            struct Range {
                /// lower bound
                lo: Int
                /// upper bound
                hi: Int
            }

            impl Iterable<Int> for Range {
                /// Initial state of the iteration.
                function start(self: Range) -> Int {
                    return self.lo
                }

                /// Advances the state machine: None ends, Some(step) yields and continues.
                function next(self: Range, state: Int) -> Option<IterStep> {
                    if state < self.hi {
                        return Option.Some(IterStep(elem: state, state: state + 1))
                    }
                    return Option.None
                }
            }
            ```

            变量、循环与调用（let/var 各类型 + while + for + 具名实参 + 内建 concat/str）：

            ```
            /// Entry point exercising the declarations above.
            function main() -> Unit {
                let p: Point = Point(x: 3, y: 4)
                let a: Int = area(self: p)
                var total: Int = 0
                let r: Range = Range(lo: 1, hi: 5)
                for v: Int in r {
                    total = total + v
                }
                var i: Int = 0
                while i < 3 {
                    total = total + i
                    i = i + 1
                }
                let label: String = "example"
                let ratio: Float = 0.5
                let ok: Bool = true
                let first: Char = 'e'
                println(concat("area: ", str(a)))
            }
            ```

            每个模块级 public 函数配一个 test（target_name = 模块全名.函数名）：

            ```
            test "classify" target: example.classify {
                let a: Cmp = classify(2, 5)
            }
            ```
            """.trimIndent(),
        ),
    )
}
