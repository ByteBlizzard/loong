package dev.loong.engine.workspace

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.codegen.CodeGenerator
import dev.loong.engine.codegen.CodegenException
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity
import dev.loong.engine.resolve.Resolver
import dev.loong.engine.rules.RulesChecker
import dev.loong.engine.storage.LoongastCodec
import dev.loong.engine.pkg.DepSource
import dev.loong.engine.pkg.Externals
import dev.loong.engine.pkg.Linker
import dev.loong.engine.text.Parser
import dev.loong.engine.types.TypeChecker

/** 单模块状态：AST（节点表）+ 最近一次重算的诊断与字节码。 */
class ModuleState internal constructor(
    val name: String,
    internal val table: NodeTable,
    internal val root: NodeId,
) {
    /** 解析期诊断（结构性的，编辑不消除）。 */
    internal var parseDiagnostics: List<Diagnostic> = emptyList()

    /** 最近一次重算后的全量诊断（解析 + 名字 + 类型 + 规则）。 */
    @Volatile
    var diagnostics: List<Diagnostic> = emptyList()
        internal set

    /** 最近一次重算后的字节码（有错误时为空）。 */
    @Volatile
    var classes: Map<String, ByteArray> = emptyMap()
        internal set

    /** 宿主类点分名（design §6.3：由模块名派生或 host_class 覆盖）。 */
    @Volatile
    var hostClassName: String = name.substringBefore('/')
        internal set

    /** 重算次数：每次编辑 +1，客户端据此判断诊断新鲜度。 */
    @Volatile
    var revision: Long = 0
        internal set

    /** 链接结果（M4 多模块）。 */
    @Volatile
    internal var link: dev.loong.engine.pkg.LinkResult? = null

    /** 上次成功生成时的函数内容签名（增量 codegen 缓存，ADR-0005）。 */
    @Volatile
    internal var fnSignatures: Map<NodeId, Long> = emptyMap()
}

/** 编辑结果：操作返回值 + 重算后的诊断 + 修订号。 */
class EditOutcome<T>(val value: T, val diagnostics: List<Diagnostic>, val revision: Long)

/**
 * 内存工作区：daemon 热状态的核心（design §8.2 AST 存储）。
 *
 * 增量模型（M2，plan.md §M2）：以**模块为失效单元**——任何编辑使所属模块脏，
 * 触发该模块的名字解析 → 类型检查 → 强制规则 →（无错误时）字节码生成重算。
 * 多模块依赖图驱动的跨模块失效在 M4 接口 AST 快照加载后接入。
 *
 * 正确性由差分测试保证：编辑后重算 == 渲染文本后全量编译（诊断与字节码一致）。
 */
class Workspace {

    private val modules = LinkedHashMap<String, ModuleState>()

    fun moduleNames(): List<String> = modules.keys.toList()

    fun module(name: String): ModuleState =
        modules[name] ?: throw NoSuchElementException("module '$name' is not loaded")

    fun tableOf(name: String): NodeTable = module(name).table

    fun rootOf(name: String): NodeId = module(name).root

    /** 移除模块（整模块卸载）；不存在返回 null。 */
    fun remove(name: String): ModuleState? = modules.remove(name)

    /** 全量装载（新建或整体替换一个模块）。 */
    fun load(source: String): ModuleState {
        val parsed = Parser(source).parse()
        val name = (parsed.table[parsed.root].fields["name"] as? FieldValue.Str)?.value
            ?: throw IllegalArgumentException("module has no name")
        val m = ModuleState(name, parsed.table, parsed.root)
        m.parseDiagnostics = parsed.diagnostics
        modules[name] = m
        recompute(m)
        return m
    }

    /**
     * 编辑操作：对模块节点表做变更（design §4.5 的 insert/replace/move/delete 等），
     * 自动触发模块重算，返回重算后的诊断。操作必须是原子的——异常时不留半变更
     * （NodeTable 编辑操作保证被拒绝的操作零副作用）。
     */
    fun <T> edit(name: String, op: (NodeTable) -> T): EditOutcome<T> {
        val m = module(name)
        val value = op(m.table)
        recompute(m)
        return EditOutcome(value, m.diagnostics, m.revision)
    }

    internal fun recompute(m: ModuleState) {
        m.revision++
        val diags = ArrayList<Diagnostic>(m.parseDiagnostics)
        diags += m.link?.diagnostics ?: emptyList()
        diags += Resolver(m.table, m.link?.symbols).resolve(m.root)
        val checker = TypeChecker(m.table, m.link?.externalRoots ?: emptyList())
        diags += checker.check(m.root)
        diags += RulesChecker(m.table).check(m.root)
        m.diagnostics = diags
        if (diags.none { it.severity == Severity.Error }) {
            try {
                val prevClasses = m.classes
                val gen = CodeGenerator(
                    m.table, checker, m.link?.externals ?: Externals.EMPTY,
                    previousClasses = prevClasses.ifEmpty { null },
                    previousSignatures = if (prevClasses.isEmpty()) null else m.fnSignatures,
                )
                m.classes = gen.generate(m.root)
                m.fnSignatures = gen.currentFnSignatures()
                m.hostClassName = gen.hostClassOf()
            } catch (e: CodegenException) {
                m.diagnostics = diags + Diagnostic(
                    m.root, Severity.Error, "E_INTERNAL", "codegen failure: ${e.message}",
                )
                m.classes = emptyMap()
            }
        } else {
            m.classes = emptyMap()
        }
    }

    /** 为所有声明了 depends 的模块执行链接（M4）。 */
    fun linkModules(source: DepSource) {
        for (name in moduleNames()) {
            val m = modules[name]!!
            m.link = Linker.link(m.table, m.root, source)
            recompute(m)
        }
    }

    /** 依赖模块的字节码目录（运行时 classpath）。 */
    fun classpathDirs(name: String): List<java.nio.file.Path> = module(name).link?.classpathDirs ?: emptyList()

    // ---------- 快照（StoragePort 消费） ----------

    fun snapshotModules(): Map<String, String> =
        modules.mapValues { LoongastCodec.serialize(it.value.table, it.value.root) }

    fun watermark(): Long = modules.values.maxOfOrNull { it.table.watermark } ?: 0L

    /** 从快照恢复（daemon 启动路径）：重建模块并立即重算。 */
    fun restore(snapshot: Map<String, String>) {
        for (json in snapshot.values) {
            val (table, root) = LoongastCodec.deserialize(json)
            val name = (table[root].fields["name"] as? FieldValue.Str)?.value ?: continue
            val m = ModuleState(name, table, root)
            modules[name] = m
            recompute(m)
        }
    }
}
