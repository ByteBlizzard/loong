# Loong 实施计划

**版本**：0.3（v1.0 完成，进入 JVM 后端深化阶段）

## 当前状态（2026-11 交接快照，换机续作从这里开始）

**v1.0（M0–M6）全部完成**，101 个测试全绿。提交序列：

| 提交 | 内容 |
|---|---|
| `32cf1d5` | M0+M1：规范/ADR + 编译器全链路（.loong → class → java 运行） |
| `dde6ae7` | M2 引擎层：增量重算、差分测试门禁、.loongast 序列化、H2 持久化 |
| `57e1fd6` | M2：loongd 守护进程（HTTP/SSE/操作队列/崩溃恢复）+ 测试运行器 |
| `77e7687` | M3：MCP server，design §8.6 AI 九步闭环验收通过 |
| `1d9e446` | M4：包管理与多模块（MVS、注册表、接口 AST 合并、跨模块编译运行） |
| `55550ed` | M5：Loong Console Web UI（零依赖，随 daemon 分发） |
| `19cad1e` | M6：互操作硬化（空值边界断言、host: 命名）+ 供应链（Ed25519 签名/SBOM） |
| `9a40a6a` | 决策落地：形式化验证整体移除（功能未成熟，需要时再引入） |
| `0cd0847` | JVM 深化 ①：实例方法/构造器外部调用（invokevirtual/invokespecial） |
| `1fb1aec` | JVM 深化 ②：枚举匹配 switch 化（lookupswitch + 字面量 acmp + 冲突回退） |
| `293e728` | JVM 深化 ③ 地基：方法发射规范序 + ADR-0005 增量 codegen 设计 |
| `4a420ac` | JVM 深化 ④：Map 索引语义定案 `m[k] -> Option<V>` |

**决策状态**：形式化验证已移除（§9 横幅）；WASM 后端已决暂不做；待决 #3（闭包）等
loong.core 狗粮数据；其余待决事项全部归零。

**下一步选项**（按优先级）：
- ~~别名机制 / publish 接线 / CLI 实体化~~ **均已完成（2026-10-30）**：
  ① `as "jvmName"` 别名（parser/renderer/codegen，解决 String/Character/Math.round 同名冲突，
  round/toUpper/toLower 补全，stdlib 44 个内联测试）；② daemon `publish` 端点 + MCP 工具
  （发布到 db 同级 registry 目录，脏模块拒绝发布）；③ `loong` CLI 实体化
  （check/build/run/test/--version，application 分发，稳定退出码，stdlib 44/44 全过，
  fac.loong 实跑 3628800）。design §8.7 双模式中的 standalone 已交付，daemon 客户端模式待接。
1. loong.core 标准库扩充（狗粮进行中：stdlib/loong/core.loong 已含 min/max/abs/clamp/sign/sqr
   + Option 具体组合子，8 个内联测试，发布-消费闭环已验收）；
2. ~~③ 增量 codegen 全量实施~~ **已完成（2026-10-30，按 ADR-0005）**：函数内容哈希失效 +
   ClassReader 未变方法逐字节拷贝 + Workspace 缓存接线；差分门禁升级为方法集语义比较；
   行为级验证：连续编辑后拷贝/重发射混合类真实运行输出正确；
3. JVM 深化 ⑤ 已完成（Object 形参 + 装箱收敛）。
4. 跨模块 struct（进行中，2026-10-30）：checker 限定名归一（loong.core.Step→Step）、
   Option.Some 期望透传、codegen 链接 struct 字段表/desc 映射/字段读写适配已落地
   （类型层全部通过）；**遗留一处 codegen 槽位错配**（VerifyError: locals long vs aload，
   见 CrossModuleStructTest @Disabled）。
   进展（同日续作）：双重求值已修复（compileFor 头部与状态机重复编译迭代对象，javap 证实）；
   现卡在 main 的 visitMaxs/COMPUTE_FRAMES 抛 NegativeArraySizeException(-1)。
   排查法：注释二分 main 三段（for-in 状态机 / Step 字段读 / Step 字段写）定位出错帧边。

**性能基准（2026-10-30，BenchmarkTest，JIT 预热后同机同 JVM 测量）**：
- 整数算术循环 5e8 次：Loong 476ms vs Java 469ms（**1.02x**）——原始类型路径零开销；
- 装箱数组求和 1e7 元素：Loong(Object[]) 10.1ms vs Java(Long[]) 9.9ms（**1.02x**）——
  擦除数组成本与手写 boxed Java 完全同级（vs 原始数组 10.5ms 亦持平，JIT 逃逸分析功劳）；
- 枚举 match 5e7 次：Loong(Variant+lookupswitch) 21.5ms vs Java(enum switch) 15.3ms（**1.40x**）——
  Variant 构造+getTag 为主导开销，int 标签直接量编码是远期优化点。
结论：语言抽象在热路径上近乎免费；唯一显著开销点是 Variant 表示（1.4x，绝对量 21ms/5e7 次）。

**后置深化项进展（2026-10-30 续）**：
- ~~CLI daemon 客户端模式~~ 已完成：`LOONGD_URL` 指向 + `/health` 探测，check/test 经 daemon
  执行（复用热状态），不可达自动回退 standalone；三模式验证通过（含 44/44 daemon 转发测试）。
- ~~枚举 int 直接量编码~~ 已完成（形态为**无载荷变体静态单例**）：本模块枚举的无载荷变体
  编译为宿主 `<clinit>` 初始化的 `public static final` 单例，构造点变 GETSTATIC（零分配）；
  结构相等（tag+payload）使跨枚举同名 tag 复用单例在语义上安全。基准：match 1.40x→1.29x
  （构造开销移除后剩余为 match 分派本身）。int 标签通道实验（tagId）此前已证不划算并回退。
- ~~Sigstore cosign 对接~~ 已完成（桥接形态）：`CosignBridge` 检测本机 cosign 二进制，
  存在则委托 `cosign verify-blob`，缺席返回 null 回退内建 Ed25519——同一验签合同双实现，
  测试覆盖回退语义；真实 cosign 环境的端到端验证待有网络/密钥的环境执行。

**狗粮阶段数据**（2026-10-30，驱动待决事项）：
- ~~迭代协议状态推进设计~~ 已决（2026-10-30）：显式状态机（start/next + Step{elem,state}），
  不允许 self 变 var；for-in 结构体代码生成落地，Range 迭代 + break 实跑验证；
- 实现者本能写 `String + String` 被类型系统拒绝（设计意图验证通过：拼接必须显式 concat）；
- ~~裸类型参数禁令过严~~ **已决（决策 A，2026-10-30）**：引入 `Any` 标记接口（一切类型隐式实现），
  `where T: Any` 表达无约束泛型；泛型 codegen 落地（擦除式 + 调用点装箱/解箱/CHECKCAST），
  unwrapOr<T>/isSome<T> 已泛型化并经消费方运行验证；
- ~~int 宽度互操作缺口~~ 已决（2026-10-30）：`Int32` 互操作类型 + `to32/to64` 显式转换（ArrayList.size/get、String.length 实跑验证）；
- ~~无闭包代价（待决 #3）~~ **已决（2026-10-30）：不引入闭包**。高阶函数 = 具名函数值 +
  显式参数穿引：Fn0-3 擦除接口 + 适配类物化 + invokeinterface 间接调用落地；
  map<T,U>/filter<T>/fold<T,A> 已进 loong.core，消费方管道（filter→map→fold）实跑验证。
  连带决策：Array<T> 统一为 Object[]（元素装箱），消除 JVM 数组不协变与擦除泛型的冲突；
  顺带修复 Let/For 类型标注不在函数类型参数作用域的缺陷。

**全栈入口与发布包（2026-10-30 续）**：
- ~~`gradle run` 一键起全栈~~ 已完成：daemon + Web UI + MCP（stdio）单进程；
  `-Pproject=<dir>` 指定项目目录（`--project` > `LOONG_PROJECT` > `./loong-project`），
  所有项目产物（workspace.db/registry/cache）落项目目录，主目录仅存跨项目个人配置；
- ~~daemon 与 MCP 端点解耦~~ 已完成（同日，ADR-0006）：原单进程形态有 1:1、固定端口
  8765、启动顺序绑死三问题。现拆为：daemon（动态端口 + `<项目目录>/loongd.json` 端点
  文件）+ `loong mcp`（agent 侧 stdio 桥，ensure 后挂接，可多桥并存）+ `loong stop`
  （POST /shutdown 优雅停止，daemon 常驻）。
  **同日修订（决策者要求）**：入口收敛为单一命令——`loong [up|stop|mcp|list|check|
  build|run|test|help]`，无参数 = loong up；项目目录缺省 = **当前工作目录**（原
  ./loong-project 子目录废除；gradle run 缺省固定 ./loong-project 防产物散进仓库根）；
  新增 `loong list`（daemon 启动登记 ~/.loong/daemons/<pid>.json，list 探活展示并清理
  强杀残留）；Web UI 首页含命令详细用法，MCP 模板 = command loong + args ["mcp",
  "--project", …]；loong-up/loong-mcp/loongc 独立脚本删除。
  全矩阵实测：agent 先起/用户先起/双项目并行/同项目双 agent/stop 清理/强杀后索引
  自清理/cmd 路径/编译器子命令（check+run 实跑）全过。
  附带修复：MCP stdio 输出显式 UTF-8（原 GBK 乱码）、启动横幅走 stderr（stdout 协议
  独占）。CLI 的 LOONGD_URL 发现保持显式；后续可让它自动读端点文件。
- ~~发布包（dist 任务）~~ 已完成（同日）：`gradle dist` 产出展开发布目录
  `build/dist/loong/` + `build/distributions/loong-<版本>.zip`。结构：脚本在根目录
  （loong / loong-up / loongc 的 sh+cmd 成对，sh 带可执行位）、全部 jar 合一为
  `loong.jar`（fat jar，Main-Class=LaunchKt 统一分发：无参=全栈，编译器命令走 CLI；
  剔除签名与 module-info）。版本号集中到 gradle.properties。脚本模板在 packaging/
  （.cmd 必须纯 ASCII——cmd 的 OEM 代码页下 UTF-8 中文注释会吞换行导致注释被当命令执行）。
  验证：展开目录与 zip 解压到任意目录均可直接使用（loongc run/test/build 冒烟、
  全栈 /health + Web UI + MCP、cmd/sh 双路径、退出码）。

**构建环境**：JDK 21+（开发机 JDK 25）、Gradle 9.5.1，详见 README。测试：`gradle test`
（含 conformance 子进程运行、差分门禁）；发布：`gradle dist`。仓库
github.com/ByteBlizzard/loong，main 分支。

---

**执行模式**：AI 实施 + 人类决策者。决策点经 ADR 落档并回写 design.md/plan.md。
**目标**：给出从零到 v1.0 的可执行路线：总体策略、技术选型、阶段化实施步骤、验收标准、风险对策与启动清单。
**执行模式**：本计划不含人力与日历工期估算。AI 作为实施者按阶段推进，每一步以自动化测试自证；决策者是唯一拍板人，在「决策门」处审核放行。每个阶段结束输出阶段报告（做了什么、测试证据、遗留问题、下一步建议）。

---

## 1. 项目理解（一句话版）

Loong = **一门语言 + 一个引擎 + 一个守护进程 + 三个客户端**：

- **语言**：显式结构、名义类型、无隐式机制、编译器强制纪律（doc/测试/长度/模块边界）；
- **引擎（loong-engine）**：AST 节点表（稳定 ID）+ 前端（解析/名字解析/类型检查/强制规则）+ JVM 字节码后端 + 增量编译 + 测试运行器 + MVS 依赖解析 + 快照版本历史；
- **守护进程（loongd）**：持有全部状态的单一事实源，操作队列串行化，广播变更；
- **客户端**：MCP server（AI）、CLI（脚本/CI）、Loong Console Web UI（人类监督）。

**v1.0 的验收即 design.md §8.6 的九步循环端到端跑通**，外加依赖消费与 JVM 互操作基础。

---

## 2. 总体实施策略

1. **引擎优先，客户端皆薄壳**。所有逻辑进 `loong-engine` 纯库（无 I/O 副作用、可嵌入），daemon/MCP/CLI/Web UI 只做协议适配。这直接兑现 design.md §8.7 的"三端语义一致"承诺，也让每个阶段都有可独立测试的库级产物。
2. **节点表第一天就按 daemon 形态建**。AST 存储、ID 分配器、编辑操作（insert/replace/move/delete）是所有组件的地基，不从编译器内部数据结构"以后再改"。
3. **纵向切片优先于横向铺功能**。先打通"文本 → AST → 检查 → 字节码 → JVM 运行"最小闭环（哪怕只支持 1/5 语法），再逐步扩语法面。语言项目的最大风险是"每个组件都半成品"。
4. **文本解析器定位为"输入桥"，不是事实源**。规范存储是 `.loongast` 序列化（design.md §4.6），但解析器必须建：① 早期人类输入；② AI 经常产出文本代码，"粘贴文本 → AST"是高价值入口；③ conformance 测试需要文本形式。注意一个设计推论：渲染器把全限定名缩写为末段，解析器就必须实现**渲染逆映射**（把短名确定性地还原为全限定名），这是双向变换，需与渲染器一起实现和测试。
5. **尽早吃狗粮**。`loong.core` 用 Loong 自身编写，越早开始越早暴露"无闭包"等激进取舍的真实成本（待决 #3 的数据来源）。
6. **形式化验证与 WASM 明确后置**（形式验证功能后按决策整体移除，需要时再引入）。
7. **增量编译的正确性用差分测试兜底**：任何输入下"增量结果 == 全量结果"（诊断与字节码逐位一致），不一致即 P0。这是整个"编译快"承诺的生命线。
8. **每一步可自动验收**。AI 实施依赖客观判据：所有验收标准必须能表达为测试/脚本判定，不依赖主观评审（规范文档定稿除外，那是决策门）。

---

## 3. 技术选型

| 决策点 | 结论 | 状态与理由 |
|---|---|---|
| 工具链实现语言 | **Kotlin（JVM）** | 建议。与字节码生成同生态（ASM 成熟）；`loong-engine` 天然发布为 `dev.loong` Maven 构件；daemon 长驻进程不在乎 JVM 冷启动；调试生成的字节码同平台最方便 |
| 字节码生成 | ASM 9.x | 建议。事实标准 |
| 目标 JVM 基线 | **JVM 21+** | 虚拟线程（Loom）是 `spawn` 语义的前提 |
| daemon 持久化 | **H2 2.x（MVStore）** | **已决（决策者，2026-10-29）**。理由见 §3.1 |
| 运行时 shim | 先用 Java/Kotlin 写 `loong-runtime` | `Option`/`Result`/`Mutex`/`Channel`/`Handle` 在编译器完备前就得存在；后续逐步用 Loong 重写（自举） |
| 模块全名规范 | 反向域名，`loong/` 官方保留前缀 | 建议，M0 决策（待决 #1） |
| `.loongast` 序列化 | 节点按 ID 升序 + 键排序的确定性 JSON + sha256 | 建议，M0 出草案、M4 定稿（待决 #2） |
| Web UI | 浏览器 SPA + WebSocket | 瘦客户端，只消费 daemon 广播 |
| 构建/仓库 | Gradle (Kotlin DSL) monorepo | engine/daemon/mcp/cli/web/stdlib 统一版本推进 |

### 3.1 为什么 H2 而不是 SQLite（已决）

- **需求匹配**：持久化负载是「单写者 daemon + 节点表按 ID 存取 + 不可变快照追加 + 周期性落盘」，无跨进程并发访问数据库的场景——多进程并发恰是 H2 相对 SQLite 唯一的实质短板，在本架构中不构成约束。
- **全 JVM 生态**：纯 Java 单 Maven 依赖；sqlite-jdbc 需按平台捆绑本地二进制，增加构建、分发与 GraalVM native-image 的复杂度。
- **锁定风险低**：可移植事实源是 `.loongast` 规范序列化文本（diff、签名、发布物都用它），数据库只是内部持久化实现。**数据库访问统一隔离在存储接口（`StoragePort`）之后**，将来替换（甚至换纯文件方案）成本接近零。
- **注意事项**：使用 H2 2.x；H2 文件锁为单进程，**CLI standalone 模式不得打开 daemon 的数据库文件**（standalone 用内存态或独立临时存储）；备份走 H2 官方 API，不在运行中复制数据文件。

**仓库结构**：

```
loong/
├── engine/            # loong-engine（Kotlin 纯库，无 I/O）
│   ├── ast/           #   节点模型、节点表、ID 分配器、编辑操作
│   ├── text/          #   .loong 词法/语法解析 + 渲染器（含渲染逆映射）
│   ├── resolve/       #   名字解析、resolved_id、depends 图
│   ├── types/         #   类型检查
│   ├── rules/         #   强制规则（E_MISSING_DOC / E_NO_TEST / E_TOO_LONG / …）
│   ├── codegen/       #   JVM 字节码生成
│   ├── interop/       #   外部声明、类型映射、空安全边界
│   ├── diagnostics/   #   结构化诊断与诊断码注册表
│   ├── deps/          #   MVS 解析、.loongast 快照加载
│   ├── testrunner/    #   test.target 关联与增量测试
│   ├── vcs/           #   快照提交图、节点级 diff
│   └── storage/       #   StoragePort 接口 + H2 实现
├── runtime/           # loong-runtime（Java/Kotlin shim，编译器隐式链接）
├── daemon/            # loongd：操作队列、广播、H2 持久化
├── mcp/               # loong-mcp
├── cli/               # loong（双模式，薄壳）
├── web/               # Loong Console
└── stdlib/            # loong.core / loong.platform.jvm（用 Loong 写）
```

---

## 4. 协作与决策模式

- **AI（实施者）**：按阶段推进；每个步骤以单元/集成/差分测试自证；测试不过不算完成；阶段结束提交阶段报告。
- **决策者（唯一拍板人）**：在以下决策点介入，其余时间不阻塞实施：
  1. M0 的规范与 ADR 定稿（文法、节点 schema、诊断码、四项 ADR）；
  2. 每个阶段的验收审核（看阶段报告 + 跑验收脚本）；
  3. 待决 #3（闭包）的触发评估：AI 在 M1 后用 Loong 写 `loong.core` 积累体验数据，若 map/filter 体验严重受阻，提交评估报告请决策；
  4. 任何偏离 design.md 的设计变更（须先提 ADR）。
- **纪律**：所有决策落 ADR 文件；决议一经确定回写 design.md 与本计划。

---

## 5. 阶段计划

> 阶段严格按序（M4 与 M5 可交错），每阶段末尾是决策门。

### M0 — 规范冻结与工程骨架

**实施步骤**：

1. 起草 **EBNF 文法 v1**（覆盖 design.md §2 全部特性与禁用清单的边界），提交决策者评审定稿。
2. 起草 **AST 节点 schema v1**：kind 清单、各 kind 的 fields/kids/parent 约定。
3. 建立**诊断码注册表**（`E_*` 全清单；此后只增不改义）。
4. 写 ADR-0001～0004：模块全名规范（建议反向域名）、`.loongast` 序列化草案（ID 升序、键排序 JSON、sha256）、持久化 H2（把 §3.1 理由落档）、NodeId 分配规则（工作区内单调递增、水位持久化、删除永不复用）。
5. 搭 monorepo + Gradle 多模块骨架 + CI（含差分测试框架骨架与 ADR 目录）。

**交付物**：三份规范、四份 ADR、可构建的空工程。

**验收（决策门）**：已通过（2026-10-29）——四份 ADR Accepted，文法/Schema 争议点（无方法糖、块尾表达式、接口接收者 `Shape<S>`）经决策者确认。

### M1 — 编译器最小闭环（关键路径）

**实施步骤**：

1. `engine/ast`：Node/Kind/NodeId/NodeTable 实现 + ID 分配器 + 单元测试（含「删除不复用、ID 单调」性质测试）。
2. `engine/text` 词法器：token 定义与文法 v1 对齐。
3. 语法解析器（文法子集：`module`/`depends`/`function`/`struct`/`interface`/`impl`/`let`/`var`/`if`/`while`/`match`/`for-in`/`return`/调用/字面量），产物入节点表；非法输入产生结构化诊断。
4. 渲染器：AST → 类 Rust 文本；实现**渲染逆映射**；建立往返测试（parse → render → parse 幂等）。
5. `engine/resolve`：模块内名字解析、`Path.resolved_id` 填充、depends 图构建。
6. `engine/types`：名义类型、显式约束泛型（**v1 采用擦除式签名 + 约束检查，不做单态化**）、`match` 穷尽性、`Option`/`Result`、无 null。
7. `engine/rules`：`E_MISSING_DOC`、`E_TOO_LONG`、`E_PRIVATE_ACCESS`、循环依赖检测（`E_NO_TEST` 留 M2）。
8. `engine/codegen` + `runtime` shim 第一批（Option/Result/基本容器）：子集语法 → class 文件，`java` 直接运行。
9. 建立 conformance 测试目录与 golden 基线机制：≥30 个程序（正例运行输出正确 + 每个强制规则有「违规 → 精确诊断码」反例）。

**交付物**：可独立运行检查与编译的 engine 库；conformance 套件。

**验收（决策门）**：conformance 全绿；`fac.loong` → class → `java` 运行输出正确（零号里程碑）。

### M2 — 引擎化、daemon 与增量编译（风险最高段）

**实施步骤**：

1. 编辑操作 API：`insert/replace/move/delete/get/find`，返回 `affected_ids`（纯内存态先行，独立测试）。
2. 增量编译调度：以**模块为失效单元**（自身节点变更或依赖的 interface AST 变更 → 模块重算），depends 图驱动。
3. **差分测试门禁**：随机编辑序列生成器 + 「增量诊断/字节码 == 全量结果」逐位比较，纳入 CI。
4. `engine/storage`：定义 `StoragePort` 接口，实现 H2/MVStore 版本（节点表持久化、ID 水位、周期落盘、启动恢复）。
5. `daemon`：操作队列串行化、WebSocket 广播、经 `StoragePort` 持久化、崩溃恢复；standalone 模式不触碰 daemon 数据库文件。
6. `engine/testrunner`：`test.target` 关联、结果按节点 ID 索引、增量测试（被测函数未变则跳过）、`E_NO_TEST` 生效。
7. `engine/interop` 第一批：外部声明（静态方法/构造器/实例方法/接口方法）→ `invokestatic`/`invokespecial`/`invokevirtual`/`invokeinterface`。

**交付物**：常驻 daemon + 增量引擎 + 测试运行器。

**验收（决策门）**：daemon 常驻下连续 1000 步随机编辑序列诊断始终与全量一致；中等模块单步编辑 → 诊断返回平均 < 100ms；M1 conformance 程序的内联 `test` 块可运行并按节点 ID 报告结果。

### M3 — MCP 与 AI 闭环

**实施步骤**：

1. MCP server 全量工具（design.md §8.3 清单：读/写/构建测试/依赖发布/版本/协作）。
2. `request_human_review` 挂起-恢复语义。
3. 审批门做成 daemon 配置（写操作策略、interface 变更强制 review 等），非 agent 自觉。
4. 固化契约：写操作自动触发增量编译、诊断随响应返回。

**交付物**：loong-mcp。

**验收（决策门）**：**§8.6 九步循环由真实 AI agent 经 MCP 走通**（`add_dependency` 可先用本地路径依赖模拟）。这是项目对外演示的第一个完整故事。

### M4 — 包管理与发布

**实施步骤**：

1. `.loongast` 规范序列化定稿（哈希与签名覆盖范围），与 ADR-0002 对齐。
2. MVS 解析器；`workspace:` 协议与 monorepo 拓扑序发布；本地路径覆盖。
3. 发布物三形态打包：字节码 + 接口 AST + 可选实现 AST。
4. 最小注册表：静态文件服务起步（目录即注册表），命名空间"已验证"检查（防 AI 幻觉依赖）。
5. 消费侧验证：哈希锁定先行；签名（cosign）、SBOM/SLSA 后置到 M6。

**交付物**：deps 解析器 + 发布/消费工具链。

**验收（决策门）**：工作区内两个模块经快照互依编译通过；`publish` → 新项目 `depends` 消费全流程走通；MVS 确定性性质测试（同输入必同输出）通过。

### M5 — Loong Console Web UI（与 M4 可交错）

**实施步骤**：

1. 代码视图（AST 渲染文本，悬停显示节点 ID/诊断）；
2. 诊断池（按节点跳转）；3. 测试面板（按被测函数分组）；4. 依赖图（模块有向图 + MVS 版本 + 冲突高亮）；5. AI 活动流 + `request_human_review` 弹窗；6. 发布面板；7. 版本历史（提交图 + 节点级 diff）。

**交付物**：Loong Console。

**验收（决策门）**：人类通过 UI 完成一次 review 批准 + 一次节点级 revert，全程不读源码文本也能掌握项目状态。

### M6 — 互操作与供应链硬化

**实施步骤**：

1. 空安全边界断言（方法入口显式非空检查）；原始类型/装箱确定性选择；模块函数宿主类命名（AST 显式属性）。
2. 真实第三方 Java 库调用标定（建议以 `java.net.http` 为用例）。
3. SBOM（CycloneDX）、SLSA provenance、Sigstone→Sigstore cosign 签名接入消费验证链。

**交付物**：完整互操作与供应链验证链。

**验收（决策门）**：Loong 程序调用真实 Java 库完成一次 HTTP 请求；未签名发布物被解析器拒绝。

### M7+ — 后置能力（v1.0 之后）

- ~~形式化验证~~ **已决（决策者）：移除**——功能未成熟；若未来需要，再按新决策引入（合约语法可同时驱动属性测试与证明）。
- 带显式捕获列表的闭包字面量（待决 #3，依狗粮数据决策）。
- ~~WASM 后端评估~~ **已决（决策者）：暂不做**——资源集中于把 JVM 后端做深做透；
- 自举：编译器前端逐步用 Loong 重写（终极狗粮项目）。
- JVM 后端深化：① 实例/构造器外部调用（已完成）② 枚举匹配 switch 化（已完成）
  ③ 增量 codegen（设计定稿于 ADR-0005，规范序地基已落地，实施待专门周期）
  ④ Map 缺席键语义（已定：`m[k] -> Option<V>`）⑤ 擦除泛型 Object 参数（已完成）⑥ Int32 互操作（已完成：Int32↔JVM I + 显式转换）。
- 可选：JUnit Platform 适配器，让 IDE 直接跑 Loong 测试。

**v1.0 定义 = M1–M6 全部验收通过。**

---

## 6. 待决事项决议状态（design.md §11）

| # | 事项 | 状态 | 决策时点 |
|---|---|---|---|
| 1 | 模块全名规范 | 建议：反向域名 `org.example.http`，主版本进路径；`loong/` 官方保留 | **M0**（阻塞 daemon/CLI/registry 接口） |
| 2 | `.loongast` 规范格式 | 建议：ID 升序、键排序确定性 JSON、sha256 | M0 草案，**M4 定稿** |
| 3 | 匿名闭包 | 推迟；M1 后狗粮 `loong.core` 积累数据，受阻则提交评估报告 | M2–M4 之间 |
| 4 | proof 形态 | **已决：形式化验证整体移除**（功能未成熟），不再预留 kind | 已决 |
| 5 | 持久化选型 | **已决（2026-10-29）：H2 2.x**，见 §3.1 | 已决 |
| 6 | CLI 短别名 | 建议：官方提供 `lg` 薄包装 | M0 |
| 7 | WASM 后端 | v1 后评估，不作承诺 | M7+ |

---

## 7. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 增量编译失效错误（该重算没重算） | **P0** | 差分测试作为 CI 门禁（增量==全量，逐位比较）；任何不一致视为发布阻断；提供"强制全量"开关兜底 |
| 稳定 ID 被持久化/快照合并破坏 | 高 | ID 分配器单点负责、水位持久化、快照合并时 ID 冲突显式报错而非静默重分配 |
| 无闭包导致标准库/用户代码难写 | 高 | M1 后立刻狗粮 `loong.core`；预留显式捕获列表闭包作为退路（待决 #3），不回到隐式闭包 |
| 鸡生蛋：编译器与标准库互相等待 | 中 | `loong-runtime` 用 Java 先行兜底，标准库逐步替换 |
| JVM 代码生成复杂度（泛型擦除、函数值、穷尽 match） | 中 | v1 泛型收敛为擦除式 + 显式约束检查；函数值映射到固定函数式接口；conformance 用例持续累积 |
| H2 数据文件损坏/备份不当 | 中 | H2 2.x 官方备份 API；周期性全量 `.loongast` 导出天然是恢复点（事实源可重建数据库） |
| 范围蔓延（形式化验证/WASM/多后端） | 中 | 明确后置；schema 预留即可，不写实现 |
| MCP 协议生态变动 | 低 | MCP server 做成纯适配层，工具面依赖 engine API 而非相反 |

---

## 8. 启动步骤（M0 立即可执行）

1. 起草 EBNF 文法 v1 与 AST 节点 schema v1，连同诊断码注册表提交评审。
2. 写 ADR-0001～0004。
3. 搭 monorepo + Gradle 多模块 + CI 骨架。
4. `engine/ast`：节点表、ID 分配器、单元测试（M1 第 1 步可提前到此）。
5. 约定阶段报告模板（做了什么/测试证据/遗留问题/下一步）。

---

*本计划与 design.md v0.1 配套；待决事项决议一经确定，回写 design.md 并更新本计划 §6 状态。*
