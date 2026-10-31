package dev.loong.engine.pkg

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity
import dev.loong.engine.resolve.SymbolTable
import dev.loong.engine.storage.LoongastCodec
import java.nio.file.Path

/** 代码生成所需的外部绑定。 */
class Externals(
    val ownerOfNode: Map<NodeId, String>,   // 外部函数节点 → 依赖宿主类点分名
    val structHost: Map<String, String>,    // 外部 struct 名 → 依赖宿主类点分名
) {
    companion object {
        val EMPTY = Externals(emptyMap(), emptyMap())
    }
}

class LinkResult(
    val symbols: SymbolTable,
    val externalRoots: List<NodeId>,
    val externals: Externals,
    val classpathDirs: List<Path>,
    val diagnostics: List<Diagnostic>,
)

/**
 * 多模块链接器（design §7.3/§7.5，plan.md M4）：
 * MVS 解析（约束的最小满足版本，传递闭包取最大下界）→ 依赖接口 AST 合并进消费方节点表 →
 * 注册跨模块符号 → 收集外部类型根与代码生成绑定。
 */
object Linker {

    fun link(consumerTable: NodeTable, consumerRoot: NodeId, source: DepSource): LinkResult {
        val diags = ArrayList<Diagnostic>()
        val symbols = SymbolTable()
        val externalRoots = ArrayList<NodeId>()
        val ownerOfNode = HashMap<NodeId, String>()
        val structHost = HashMap<String, String>()
        val classpathDirs = ArrayList<Path>()

        // 1. 消费方约束
        val rootConstraints = HashMap<String, String>() // module -> 最低版本
        val workspaceOnly = HashSet<String>()
        for (dep in consumerTable.find(Kind.Depends)) {
            val name = fs(consumerTable, dep, "module_name")
            val sourceKind = fs(consumerTable, dep, "source")
            val constraint = (consumerTable[dep].fields["constraint"] as? FieldValue.Str)?.value
            if (sourceKind == "workspace") workspaceOnly.add(name)
            rootConstraints[name] = maxVer(rootConstraints[name], minVerOf(constraint))
        }

        // 2. MVS：最大下界不动点
        val choices = HashMap<String, String>()
        val queue = ArrayDeque(rootConstraints.map { it.key to it.value })
        val fetchedTexts = HashMap<String, String>() // module@version -> ast
        while (queue.isNotEmpty()) {
            val (module, min) = queue.removeFirst()
            val required = maxVer(min, rootConstraints[module])
            val candidate = smallestAtLeast(source.versions(module), required)
            if (candidate == null) {
                diags += Diagnostic(
                    consumerRoot, Severity.Error, DiagCodes.E_UNKNOWN_MODULE,
                    "dependency '$module' >= $required not found in registry/workspace",
                )
                continue
            }
            val changed = choices[module]?.let { compareVer(candidate, it) > 0 } ?: true
            choices[module] = maxVer(choices[module], candidate)
            if (changed) {
                val key = "$module@$candidate"
                val text = fetchedTexts.getOrPut(key) { source.fetch(module, candidate).interfaceAst }
                // 传递依赖
                forEachDep(text) { depName, depConstraint ->
                    val newMin = maxVer(rootConstraints[depName], minVerOf(depConstraint))
                    if (rootConstraints[depName] != newMin || !choices.containsKey(depName)) {
                        rootConstraints[depName] = newMin
                        queue.addLast(depName to newMin)
                    }
                }
            }
        }

        // 3. 合并接口 AST 与绑定
        for ((module, version) in choices) {
            val fetched = source.fetch(module, version)
            val (depTable, depRoot) = LoongastCodec.deserialize(fetched.interfaceAst)
            val host = fs(depTable, depRoot, "host_class").ifEmpty {
                fs(depTable, depRoot, "name").substringBefore('/')
            }
            // 合并：把依赖接口 AST 的节点重建进消费方表
            val mergeTable = consumerTable
            val mapping = HashMap<NodeId, NodeId>()
            fun rebuild(id: NodeId): NodeId {
                val n = depTable[id]
                val kids = n.kids.map { rebuild(it) }
                val newId = mergeTable.create(n.kind, n.fields, kids)
                mapping[id] = newId
                return newId
            }

            val newRoot = rebuild(depRoot)
            externalRoots += newRoot
            fetched.classesDir?.let { classpathDirs.add(it) }

            // 符号注册 + 代码生成绑定（接口 AST 只含 public 声明）
            val dotted = fs(depTable, depRoot, "name").substringBefore('/')
            for (decl in depTable[depRoot].kids) {
                val node = depTable[decl]
                val name = fs(depTable, decl, "name")
                val mapped = mapping.getValue(decl)
                when (node.kind) {
                    Kind.Function -> {
                        symbols.register("$dotted.$name", mapped, Kind.Function, shortName = name)
                        ownerOfNode[mapped] = host
                    }
                    Kind.Struct -> {
                        symbols.register("$dotted.$name", mapped, Kind.Struct, prefixOnly = true, shortName = name)
                        structHost[name] = host
                    }
                    Kind.Interface ->
                        symbols.register("$dotted.$name", mapped, Kind.Interface, prefixOnly = true, shortName = name)
                    Kind.Enum -> {
                        symbols.register("$dotted.$name", mapped, Kind.Enum, prefixOnly = true, shortName = name)
                        for (v in node.kids.filter { depTable[it].kind == Kind.Variant }) {
                            val vn = fs(depTable, v, "name")
                            symbols.register(
                                "$dotted.$name.$vn", mapping.getValue(v), Kind.Variant,
                                relativeName = "$name.$vn",
                            )
                        }
                    }
                    Kind.Const -> symbols.register("$dotted.$name", mapped, Kind.Const, shortName = name)
                    else -> Unit
                }
            }
        }

        return LinkResult(symbols, externalRoots, Externals(ownerOfNode, structHost), classpathDirs, diags)
    }

    private fun forEachDep(astText: String, fn: (String, String?) -> Unit) {
        runCatching {
            val (t, r) = LoongastCodec.deserialize(astText)
            for (dep in t.find(Kind.Depends)) {
                val src = fs(t, dep, "source")
                if (src == "workspace") continue
                fn(fs(t, dep, "module_name"), (t[dep].fields["constraint"] as? FieldValue.Str)?.value)
            }
        }
    }

    private fun minVerOf(constraint: String?): String =
        constraint?.removePrefix(">=")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0"

    private fun maxVer(a: String?, b: String?): String {
        if (a == null) return b ?: "0.0.0"
        if (b == null) return a
        return if (compareVer(b, a) > 0) b else a
    }

    private fun compareVer(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun smallestAtLeast(versions: List<String>, min: String): String? =
        versions.filter { compareVer(it, min) >= 0 }.minWithOrNull { x, y -> compareVer(x, y) }

    private fun fs(t: NodeTable, id: NodeId, key: String): String =
        (t[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
