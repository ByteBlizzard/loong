package dev.loong.engine.types

/**
 * 类型结构化表示。AST 中的类型以规范文本存储（ast-schema §2），
 * 类型检查器按需解析为本模型；相等性即名义相等（名字 + 实参）。
 *
 * 泛型采用擦除式签名 + 调用点统一（plan.md M1 第 6 步），
 * 类型参数在此模型中就是不透明的 [Named]。
 */
sealed class LoongType {

    data class Named(val name: String, val args: List<LoongType> = emptyList()) : LoongType()

    data class Func(val params: List<LoongType>, val ret: LoongType) : LoongType()

    fun render(): String = when (this) {
        is Named -> if (args.isEmpty()) name else name + args.joinToString(", ", "<", ">") { it.render() }
        is Func -> "(" + params.joinToString(", ") { it.render() } + ") -> " + ret.render()
    }

    companion object {

        /** 解析规范类型文本（Parser 产出的形态：", " 分隔、嵌套泛型以字面 ">>" 收尾）。 */
        fun parse(text: String): LoongType {
            var i = 0
            fun skipSpace() {
                while (i < text.length && text[i] == ' ') i++
            }

            fun parseOne(): LoongType {
                skipSpace()
                if (i < text.length && text[i] == '(') {
                    i++
                    val params = ArrayList<LoongType>()
                    skipSpace()
                    if (i < text.length && text[i] != ')') {
                        params += parseOne()
                        skipSpace()
                        while (i < text.length && text[i] == ',') {
                            i++
                            params += parseOne()
                            skipSpace()
                        }
                    }
                    if (i < text.length && text[i] == ')') i++
                    skipSpace()
                    if (i + 1 < text.length && text[i] == '-' && text[i + 1] == '>') i += 2
                    return Func(params, parseOne())
                }
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                if (i == start) return Named("<error>")
                val name = text.substring(start, i)
                val args = ArrayList<LoongType>()
                if (i < text.length && text[i] == '<') {
                    i++
                    args += parseOne()
                    skipSpace()
                    while (i < text.length && text[i] == ',') {
                        i++
                        args += parseOne()
                        skipSpace()
                    }
                    // 嵌套泛型：内层已消费一个 '>'，此处消费自己那一个（"xx>>" 的第二个）
                    if (i < text.length && text[i] == '>') i++
                }
                return Named(name, args)
            }

            return parseOne()
        }
    }
}
