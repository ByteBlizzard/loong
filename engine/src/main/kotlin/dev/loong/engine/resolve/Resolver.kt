package dev.loong.engine.resolve

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.ast.ref
import dev.loong.engine.ast.str
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity

/**
 * 模块内名字解析（plan.md M1 第 5 步）。
 *
 * 职责：
 * 1. 收集模块声明建符号表（impl 函数挂接口名下，枚举变体挂枚举名下）；
 * 2. 表达式 Path 解析：全名命中 → 填 full_name/resolved_id；
 *    否则最长可解析前缀 + 剩余段拆分为 FieldAccess 链（ast-schema §3.7）；
 * 3. 局部作用域（参数/let/for 变量/if-let 与 match 绑定）解析到绑定节点；
 * 4. Test.target_id 填充；
 * 5. 诊断：E_UNKNOWN_NAME / E_AMBIGUOUS_NAME / E_DUPLICATE_DECL / E_UNRESOLVED_TARGET。
 *
 * 类型名（type 字段的文本）的合法性由类型检查阶段负责，此处不处理。
 * 跨模块解析（经依赖的接口 AST）在 M4 引入快照加载后实现。
 */
class Resolver(
    private val table: NodeTable,
    /** 链接器预注册的跨模块符号（M4）；为 null 时仅本模块符号。 */
    private val linked: SymbolTable? = null,
) {

    private val diags = ArrayList<Diagnostic>()
    private lateinit var symbols: SymbolTable
    private val scopes = ArrayDeque<MutableMap<String, NodeId>>()

    fun resolve(root: NodeId): List<Diagnostic> {
        val moduleDotted = fs(root, "name").substringBefore('/')
        symbols = linked ?: SymbolTable()
        CoreSymbols.registerInto(symbols)
        registerModuleSymbols(root, moduleDotted)
        resolveTestTargets(root)
        table.find(Kind.Function).forEach { resolveFunction(it) }
        table.find(Kind.Test).forEach { test ->
            scopes.clear()
            push()
            table[test].kids.firstOrNull { table[it].kind == Kind.Block }?.let { resolveBlock(it) }
            pop()
        }
        return diags
    }

    // ---------- 符号收集 ----------

    private fun registerModuleSymbols(root: NodeId, moduleDotted: String) {
        for (decl in table[root].kids) {
            val node = table[decl]
            val name = fs(decl, "name")
            val duplicate = when (node.kind) {
                Kind.Function ->
                    symbols.register("$moduleDotted.$name", decl, Kind.Function, shortName = name)
                Kind.Struct ->
                    symbols.register("$moduleDotted.$name", decl, Kind.Struct, prefixOnly = true, shortName = name)
                Kind.Interface ->
                    symbols.register("$moduleDotted.$name", decl, Kind.Interface, prefixOnly = true, shortName = name)
                Kind.Enum -> {
                    val dup = symbols.register(
                        "$moduleDotted.$name", decl, Kind.Enum, prefixOnly = true, shortName = name,
                    )
                    // 枚举变体：相对名 Enum.Variant + 全名；短名不注册（必须写 Color.Red）
                    for (v in node.kids.filter { table[it].kind == Kind.Variant }) {
                        val vname = fs(v, "name")
                        val vdup = symbols.register(
                            "$moduleDotted.$name.$vname", v, Kind.Variant,
                            relativeName = "$name.$vname",
                        )
                        if (vdup != null) reportDup(v, vdup)
                    }
                    dup
                }
                Kind.Const ->
                    symbols.register("$moduleDotted.$name", decl, Kind.Const, shortName = name)
                Kind.Impl -> {
                    val itfName = fs(decl, "interface").substringBefore('<')
                    for (fn in node.kids.filter { table[it].kind == Kind.Function }) {
                        val fnName = fs(fn, "name")
                        symbols.register(
                            "$moduleDotted.$itfName.$fnName", fn, Kind.Function,
                            shortName = fnName, relativeName = "$itfName.$fnName",
                        )
                    }
                    null
                }
                Kind.ExternalClass -> {
                    val cls = fs(decl, "class_fqn").substringAfterLast('.')
                    // 外部类短名可作构造调用 callee（StringBuilder() 形态）
                    symbols.register(
                        "$moduleDotted.$cls", decl, Kind.ExternalClass, prefixOnly = true, shortName = cls,
                    )
                    for (m in node.kids.filter { table[it].kind == Kind.ExternalMethod }) {
                        val mname = fs(m, "name")
                        symbols.register(
                            "$moduleDotted.$mname", m, Kind.ExternalMethod, shortName = mname,
                            relativeName = "$cls.$mname",
                        )
                    }
                    null
                }
                else -> null
            }
            if (duplicate != null) reportDup(decl, duplicate)
        }
    }

    private fun reportDup(node: NodeId, fullName: String) {
        diags += Diagnostic(node, Severity.Error, DiagCodes.E_DUPLICATE_DECL, "duplicate declaration '$fullName'")
    }

    // ---------- 查找 ----------

    private fun lookup(name: String): SymbolTable.Symbol? {
        // 局部作用域，内层优先
        for (i in scopes.indices.reversed()) {
            scopes[i][name]?.let { return SymbolTable.Symbol(name, it, table[it].kind) }
        }
        symbols.lookupFull(name)?.let { return it }
        if (!name.contains('.')) {
            val candidates = symbols.lookupShort(name)
            return when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates[0]
                else -> throw SymbolTable.AmbiguousName(name, candidates)
            }
        }
        return null
    }

    // ---------- Test 目标 ----------

    private fun resolveTestTargets(root: NodeId) {
        for (test in table[root].kids.filter { table[it].kind == Kind.Test }) {
            val target = fs(test, "target_name")
            val sym = try {
                lookup(target)
            } catch (e: SymbolTable.AmbiguousName) {
                diags += ambiguousDiagnostic(e)
                null
            }
            when {
                sym == null -> {
                    diags += Diagnostic(
                        test, Severity.Error, DiagCodes.E_UNRESOLVED_TARGET,
                        "test target '$target' not found",
                    )
                    clearTargetRef(test)
                }
                sym.kind != Kind.Function -> {
                    diags += Diagnostic(
                        test, Severity.Error, DiagCodes.E_UNRESOLVED_TARGET,
                        "test target '$target' is not a function",
                    )
                    clearTargetRef(test)
                }
                else -> {
                    val fields = table[test].fields.toMutableMap()
                    fields["target_id"] = ref(sym.nodeId!!)
                    table.replace(test, fields = fields)
                }
            }
        }
    }

    // ---------- 函数体解析 ----------

    private fun resolveFunction(fn: NodeId) {
        scopes.clear()
        push()
        for (p in table[fn].kids.filter { table[it].kind == Kind.Param }) {
            bind(fs(p, "name"), p)
        }
        table[fn].kids.firstOrNull { table[it].kind == Kind.Block }?.let { resolveBlock(it) }
        pop()
    }

    private fun push() {
        scopes.addLast(HashMap())
    }

    private fun pop() {
        scopes.removeLast()
    }

    private fun bind(name: String, node: NodeId) {
        val scope = scopes.last()
        if (scope.containsKey(name)) {
            diags += Diagnostic(
                node, Severity.Error, DiagCodes.E_DUPLICATE_DECL,
                "duplicate binding '$name' in the same scope",
            )
        } else {
            scope[name] = node
        }
    }

    private fun resolveBlock(block: NodeId) {
        push()
        table[block].kids.forEach { resolveStmt(it) }
        pop()
    }

    private fun resolveStmt(s: NodeId) {
        val kids = table[s].kids
        when (table[s].kind) {
            Kind.Block -> resolveBlock(s)
            Kind.Let -> {
                // 初始化表达式先解析：let x = x + 1 的 x 指向外层；无初始化的半成品节点跳过（防崩）
                kids.firstOrNull()?.let { resolveExpr(it) }
                bind(fs(s, "name"), s)
            }
            Kind.Assign, Kind.ExprStmt, Kind.Return ->
                kids.forEach { resolveExpr(it) }
            Kind.While -> {
                resolveExpr(kids[0])
                resolveBlock(kids[1])
            }
            Kind.For -> {
                resolveExpr(kids[0])
                push()
                bind(fs(s, "var_name"), s)
                resolveBlock(kids[1])
                pop()
            }
            Kind.If -> resolveIf(s)
            Kind.IfLet -> resolveIfLet(s)
            Kind.Match -> resolveMatch(s)
            Kind.Break, Kind.Continue -> Unit
            else -> kids.forEach { resolveExpr(it) }
        }
    }

    private fun resolveIf(id: NodeId) {
        val kids = table[id].kids
        resolveExpr(kids[0])
        resolveBlock(kids[1])
        kids.getOrNull(2)?.let { els ->
            if (table[els].kind == Kind.Block) resolveBlock(els) else resolveIf(els)
        }
    }

    private fun resolveIfLet(id: NodeId) {
        val kids = table[id].kids
        resolveExpr(kids[0])
        push()
        val binding = table[id].fields["binding"]
        if (binding is FieldValue.Str) bind(binding.value, id)
        resolveBlock(kids[1])
        pop()
        kids.getOrNull(2)?.let { resolveBlock(it) }
    }

    private fun resolveMatch(id: NodeId) {
        val kids = table[id].kids
        resolveExpr(kids[0])
        for (arm in kids.drop(1)) {
            push()
            val binding = table[arm].fields["binding"]
            if (binding is FieldValue.Str) bind(binding.value, arm)
            val body = table[arm].kids.firstOrNull()
            when (body?.let { table[it].kind }) {
                Kind.Block -> resolveBlock(body)
                else -> if (body != null) resolveExpr(body)
            }
            pop()
        }
    }

    private fun resolveExpr(e: NodeId) {
        val kids = table[e].kids
        when (table[e].kind) {
            Kind.Path -> resolvePath(e)
            Kind.FieldAccess -> resolveExpr(kids[0])
            Kind.Call, Kind.Arg, Kind.Binary, Kind.Unary, Kind.Index,
            Kind.MapEntry, Kind.Join, Kind.Spawn, Kind.Literal,
            -> kids.forEach { resolveExpr(it) }
            Kind.If -> resolveIf(e)
            Kind.IfLet -> resolveIfLet(e)
            Kind.Match -> resolveMatch(e)
            else -> Unit
        }
    }

    /**
     * Path 解析与点链拆分：
     * 1. 全名（或短名）命中 → 填 full_name/resolved_id，节点保持 Path；
     * 2. 否则找最长可解析前缀（局部 > 全名 > 相对名 > 唯一短名），Path 保留为头，
     *    剩余段拆成 FieldAccess 链并挂回原父节点原位置；
     * 3. 无任何前缀可解析 → E_UNKNOWN_NAME。
     */
    private fun resolvePath(e: NodeId) {
        val written = fs(e, "written")
        val direct = try {
            lookup(written)
        } catch (ex: SymbolTable.AmbiguousName) {
            diags += ambiguousDiagnostic(ex)
            return // 歧义已报告，不再叠加 unknown
        }
        if (direct != null) {
            if (direct.prefixOnly) {
                // 例外：struct 名作为构造调用的被调方（Point(x: 1) 形态，grammar §4.5）
                val isCtorCallee = (direct.kind == Kind.Struct || direct.kind == Kind.ExternalClass) &&
                    table[e].parent != NodeId.NONE &&
                    table[table[e].parent].kind == Kind.Call &&
                    table[table[e].parent].kids.firstOrNull() == e
                if (isCtorCallee) {
                    fill(e, direct)
                } else {
                    diags += Diagnostic(
                        e, Severity.Error, DiagCodes.E_UNKNOWN_NAME,
                        "'$written' is a type or enum name, not a value",
                    )
                    clearRef(e)
                }
            } else {
                fill(e, direct)
            }
            return
        }
        if (!written.contains('.')) {
            // 拆分过的链头无点（如 self.x 的头是 self）：失败时同样坍缩回单 Path
            if (table[e].parent != NodeId.NONE && table[table[e].parent].kind == Kind.FieldAccess) {
                collapseFieldChain(e)
                val merged = fs(e, "written")
                unknown(e, merged)
                clearRef(e)
                return
            }
            unknown(e, written)
            clearRef(e)
            return
        }
        val segs = written.split('.')
        for (k in segs.size - 1 downTo 1) {
            val prefix = segs.subList(0, k).joinToString(".")
            val sym = try {
                lookup(prefix)
            } catch (ex: SymbolTable.AmbiguousName) {
                diags += ambiguousDiagnostic(ex)
                null
            } ?: continue
            // 类型名只能整体命中（如 Color.Red / loong.core.Step(elem: 1, state: 2)）
            // 例外：限定 callee 的末段是构造调用 —— 点链吞噬后整链已命中类型名
            if (sym.prefixOnly) {
                val parent = table[e].parent
                val isCtorCallee = parent != NodeId.NONE &&
                    table[parent].kind == Kind.Call &&
                    table[parent].kids.firstOrNull() == e
                if (!isCtorCallee) continue
                fill(e, sym)
                return
            }
            fill(e, sym)
            // 拆分后 Path 只代表前缀：written 必须收缩，否则渲染器会重复输出剩余段
            val fields = table[e].fields.toMutableMap()
            fields["written"] = str(prefix)
            table.replace(e, fields = fields)
            splitFieldChain(e, segs.subList(k, segs.size))
            return
        }
        unknown(e, written)
        clearRef(e)
        // 错误态规范化：解析失败时把此前拆分出的 FieldAccess 链坍缩回单 Path，
        // 使增量表与全新解析产生完全一致的形态与诊断（差分测试的关键）。
        collapseFieldChain(e)
    }

    private fun fill(id: NodeId, sym: SymbolTable.Symbol) {
        val fields = table[id].fields.toMutableMap()
        fields["full_name"] = str(sym.fullName)
        if (sym.nodeId != null) fields["resolved_id"] = ref(sym.nodeId)
        table.replace(id, fields = fields)
    }

    /**
     * 清除过期的解析结果（full_name/resolved_id）：目标声明被改名或删除后，
     * 旧引用必须在重算时失效——这是解析阶段可重入（增量编译）的关键。
     */
    private fun clearRef(id: NodeId) {
        val fields = table[id].fields
        if (fields.containsKey("full_name") || fields.containsKey("resolved_id")) {
            val kept = fields.toMutableMap()
            kept.remove("full_name")
            kept.remove("resolved_id")
            table.replace(id, fields = kept)
        }
    }

    private fun clearTargetRef(test: NodeId) {
        val fields = table[test].fields
        if (fields.containsKey("target_id")) {
            val kept = fields.toMutableMap()
            kept.remove("target_id")
            table.replace(test, fields = kept)
        }
    }

    private fun splitFieldChain(pathId: NodeId, fields: List<String>) {
        val parent = table[pathId].parent
        val index = table[parent].kids.indexOf(pathId)
        table.detach(pathId)
        var head = pathId
        for (f in fields) {
            head = table.create(Kind.FieldAccess, mapOf("field" to str(f)), listOf(head))
        }
        table.attach(head, parent, index)
    }

    private fun unknown(id: NodeId, name: String) {
        diags += Diagnostic(id, Severity.Error, DiagCodes.E_UNKNOWN_NAME, "unknown name '$name'")
    }

    /**
     * 反向坍缩：e 是某 FieldAccess 链的头（此前解析成功拆分过，如今头部不可解析）时，
     * 把链合并回单个 Path（written = 全链文本），删除 FieldAccess 节点并原位挂回。
     * e 不是链头时为空操作。
     */
    private fun collapseFieldChain(e: NodeId) {
        var top = e
        var parent = table[top].parent
        val segs = ArrayList<String>()
        while (parent != NodeId.NONE && table[parent].kind == Kind.FieldAccess) {
            segs.add(fs(parent, "field"))
            top = parent
            parent = table[parent].parent
        }
        if (segs.isEmpty()) return
        val holder = table[top].parent
        if (holder == NodeId.NONE) return
        val index = table[holder].kids.indexOf(top)
        segs.reverse()
        val merged = fs(e, "written") + "." + segs.joinToString(".")
        table.detach(top) // 整链脱离
        table.detach(e) // 链头脱离链
        table.replace(e, fields = mapOf("written" to str(merged)))
        table.attach(e, holder, index)
        table.delete(top) // 清理残留的 FieldAccess 链
    }

    private fun ambiguousDiagnostic(e: SymbolTable.AmbiguousName): Diagnostic = Diagnostic(
        e.candidates.firstOrNull()?.nodeId ?: NodeId.NONE,
        Severity.Error,
        DiagCodes.E_AMBIGUOUS_NAME,
        "'${e.name}' is ambiguous: ${e.candidates.joinToString { it.fullName }}; use a qualified name",
    )

    private fun fs(id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
