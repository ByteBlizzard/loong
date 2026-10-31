package dev.loong.engine.resolve

import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId

/**
 * 模块符号表：名字解析的名字空间（grammar §4.2/§4.3，ast-schema §3.7/3.9）。
 *
 * 三种键：
 * - 全名（含模块前缀，如 `org.example.demo.add`、`loong.core.Option.Some`）
 * - 相对限定名（`Shape.area`、`Color.Red`——接口限定与枚举限定）
 * - 短名（`add`）；同名多个候选在使用点报 E_AMBIGUOUS_NAME，不在注册时报错
 */
class SymbolTable {

    class Symbol(
        val fullName: String,
        val nodeId: NodeId?,
        val kind: Kind,
        /** 仅可作限定前缀（类型/枚举名），不能单独作为值使用。 */
        val prefixOnly: Boolean = false,
    )

    class AmbiguousName(val name: String, val candidates: List<Symbol>) : Exception()

    private val byFull = HashMap<String, Symbol>()
    private val byRelative = HashMap<String, Symbol>()
    private val byShort = HashMap<String, MutableList<Symbol>>()

    /**
     * 注册符号。返回冲突的全名（E_DUPLICATE_DECL 用），null 表示成功。
     * 短名/相对名冲突不在此报——留给使用点的歧义检查。
     */
    fun register(
        fullName: String,
        nodeId: NodeId?,
        kind: Kind,
        prefixOnly: Boolean = false,
        shortName: String? = null,
        relativeName: String? = null,
    ): String? {
        val existing = byFull.putIfAbsent(fullName, Symbol(fullName, nodeId, kind, prefixOnly))
        if (existing != null) return fullName
        if (relativeName != null) byRelative[relativeName] = Symbol(fullName, nodeId, kind, prefixOnly)
        if (shortName != null) {
            byShort.getOrPut(shortName) { mutableListOf() }
                .add(Symbol(fullName, nodeId, kind, prefixOnly))
        }
        return null
    }

    fun lookupFull(name: String): Symbol? = byFull[name] ?: byRelative[name]

    fun lookupShort(name: String): List<Symbol> = byShort[name] ?: emptyList()
}

/** 内建符号：loong.core 的值符号（grammar §4.5.1 预置依赖）。类型名由类型检查阶段处理。 */
object CoreSymbols {

    /** 值符号：相对名 → 规范全名。构造子是枚举变体形态。 */
    private val VALUES = mapOf(
        "Option.Some" to "loong.core.Option.Some",
        "Option.None" to "loong.core.Option.None",
        "Result.Ok" to "loong.core.Result.Ok",
        "Result.Err" to "loong.core.Result.Err",
    )

    /** 核心函数（短名直接可用）。 */
    private val FUNCTIONS = listOf("send", "recv", "println", "print", "concat", "str", "len", "newArray", "to32", "to64", "tof")

    fun registerInto(symbols: SymbolTable) {
        VALUES.forEach { (rel, full) ->
            symbols.register(full, null, Kind.Variant, relativeName = rel)
        }
        FUNCTIONS.forEach { fn ->
            symbols.register("loong.core.$fn", null, Kind.Function, shortName = fn)
        }
    }
}
