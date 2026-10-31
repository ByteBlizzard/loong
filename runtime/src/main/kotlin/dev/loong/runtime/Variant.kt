package dev.loong.runtime

/**
 * 枚举值的统一运行时表示（M1）。
 *
 * Loong 的枚举（含内建 Option/Result 与用户枚举）统一编译为 Variant：
 * tag 区分变体，payload 为装箱后的载荷（无载荷为 null）。
 * 相等性按 tag + payload 值比较，支撑语言层的枚举 ==。
 */
class Variant(val tag: String, val payload: Any?) {

    override fun equals(other: Any?): Boolean =
        other is Variant && other.tag == tag && other.payload == payload

    override fun hashCode(): Int = tag.hashCode() * 31 + (payload?.hashCode() ?: 0)

    override fun toString(): String = if (payload == null) tag else "$tag($payload)"

    companion object {
        @JvmStatic
        fun of(tag: String, payload: Any?): Variant = Variant(tag, payload)
    }
}
