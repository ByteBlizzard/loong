package dev.loong.engine.json

/**
 * 极小 JSON 工具（解析 + 渲染，零依赖）。
 * daemon 的 HTTP API、后续 MCP 消息封装与 .loongast 编解码共用。
 * 渲染 Map 时键排序，保证确定性。
 */
object Json {

    sealed class Value

    class Obj(val map: LinkedHashMap<String, Value>) : Value()
    class Arr(val items: List<Value>) : Value()
    data class Str(val v: String) : Value()
    data class Num(val v: Long) : Value()
    data class Bool(val v: Boolean) : Value()
    object Null : Value()

    // ---------- 解析 ----------

    fun parse(text: String): Value {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) error("json: trailing content")
        return v
    }

    private class Parser(private val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Value {
            if (atEnd()) error("json: unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(string())
                't' -> { expectWord("true"); Bool(true) }
                'f' -> { expectWord("false"); Bool(false) }
                'n' -> { expectWord("null"); Null }
                else -> number()
            }
        }

        fun obj(): Obj {
            i++ // {
            val map = LinkedHashMap<String, Value>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return Obj(map)
            }
            while (true) {
                skipWs()
                val k = string()
                skipWs()
                if (s[i] != ':') error("json: expected ':'")
                i++
                skipWs()
                map[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Obj(map)
                    }
                    else -> error("json: expected ',' or '}'")
                }
            }
        }

        fun arr(): Arr {
            i++ // [
            val items = ArrayList<Value>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                skipWs()
                items += value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return Arr(items)
                    }
                    else -> error("json: expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            i++ // 开引号
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) error("json: unterminated string")
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        i++
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(0x0C.toChar())
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> error("json: bad escape '\\$e'")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        fun number(): Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i == start) error("json: bad number")
            return Num(s.substring(start, i).toLong())
        }

        fun expectWord(w: String) {
            if (!s.startsWith(w, i)) error("json: expected '$w'")
            i += w.length
        }
    }

    // ---------- 渲染 ----------

    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, v)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                val entries = v.entries.sortedBy { it.key.toString() }
                entries.forEachIndexed { i, (k, item) ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, item)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            is Value -> writeParsed(sb, v)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeParsed(sb: StringBuilder, v: Value) {
        when (v) {
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, item) in v.map) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeParsed(sb, item)
                }
                sb.append('}')
            }
            is Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeParsed(sb, item)
                }
                sb.append(']')
            }
            is Str -> writeString(sb, v.v)
            is Num -> sb.append(v.v)
            is Bool -> sb.append(v.v)
            Null -> sb.append("null")
        }
    }

    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
