package dev.loong.engine.text

import dev.loong.engine.ast.NodeId
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Severity

/**
 * 手写词法器（grammar-v1.md §1）。
 *
 * - 字符串/字符 token 的 text 是**原始内容**（不含引号、转义未解码），
 *   渲染器原样回放以保证往返保真；转义合法性在此校验并报诊断。
 * - `///` 连续行合并为一个 DOC token；`//` 行注释与 `;` 分号直接丢弃（分号无语义）。
 */
class Lexer(private val src: String) {

    private val tokens = ArrayList<Token>()
    private val diagnostics = ArrayList<Diagnostic>()
    private var i = 0
    private var line = 1
    private var col = 1

    fun lex(): LexResult {
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' || c == '\r' || c == ' ' || c == '\t' -> advance()
                c == ';' -> advance() // 分号允许出现但无语义
                c == '/' && peek(1) == '/' && peek(2) == '/' -> lexDocComment()
                c == '/' && peek(1) == '/' -> lexLineComment()
                c == '"' -> lexString()
                c == '\'' -> lexChar()
                c.isDigit() -> lexNumber()
                c.isLetter() || c == '_' -> lexWord()
                else -> lexOperator()
            }
        }
        tokens += Token(TokenType.EOF, "", i, line, col)
        return LexResult(tokens, diagnostics)
    }

    // ---------- 基础 ----------

    private fun peek(k: Int): Char {
        val idx = i + k
        return if (idx < src.length) src[idx] else ' '
    }

    /** 当前字符；越界返回空格（NUL 会污染文件，见 M1 教训）。 */
    private fun ch(): Char = if (i < src.length) src[i] else ' '

    private fun advance(): Char {
        val c = src[i]
        i++
        if (c == '\n') {
            line++
            col = 1
        } else {
            col++
        }
        return c
    }

    private fun err(code: String, message: String) {
        diagnostics += Diagnostic(NodeId.NONE, Severity.Error, code, message)
    }

    private fun add(type: TokenType, startOffset: Int, startLine: Int, startCol: Int) {
        val raw = src.substring(startOffset, i)
        tokens += Token(type, raw, startOffset, startLine, startCol)
    }

    // ---------- 词法单元 ----------

    private fun lexWord() {
        val (o, l, c) = Triple(i, line, col)
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) advance()
        val text = src.substring(o, i)
        tokens += Token(KEYWORDS[text] ?: TokenType.IDENT, text, o, l, c)
    }

    private fun lexNumber() {
        val (o, l, c) = Triple(i, line, col)
        if (src[i] == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance(); advance()
            while (i < src.length && src[i].isLetterOrDigit()) advance()
            if (i - o <= 2) err(DiagCodes.E_INVALID_NUMBER, "incomplete hex literal at $l:$c")
            add(TokenType.INT, o, l, c)
            return
        }
        while (i < src.length && src[i].isDigit()) advance()
        var isFloat = false
        if (ch() == '.' && peek(1).isDigit()) {
            isFloat = true
            advance() // .
            while (i < src.length && src[i].isDigit()) advance()
        }
        if (ch() == 'e' || ch() == 'E') {
            val save = Triple(i, line, col)
            advance()
            if (ch() == '+' || ch() == '-') advance()
            if (ch().isDigit()) {
                isFloat = true
                while (i < src.length && src[i].isDigit()) advance()
            } else {
                // 不是指数，回退（例如标识符紧邻数字的非法场景交由语法层报错）
                i = save.first; line = save.second; col = save.third
            }
        }
        // 数字后紧跟字母 = 非法数字（如 12abc）
        if (i < src.length && (src[i].isLetter() || src[i] == '_')) {
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) advance()
            err(DiagCodes.E_INVALID_NUMBER, "invalid number '${src.substring(o, i)}' at $l:$c")
        }
        add(if (isFloat) TokenType.FLOAT else TokenType.INT, o, l, c)
    }

    private fun lexString() {
        val (o, l, c) = Triple(i, line, col)
        advance() // 开引号
        val contentStart = i
        while (true) {
            if (i >= src.length || src[i] == '\n') {
                err(DiagCodes.E_UNTERMINATED_STRING, "unterminated string at $l:$c")
                break
            }
            val ch = src[i]
            if (ch == '"') break
            if (ch == '\\') {
                validateEscape(l, c)
            } else {
                advance()
            }
        }
        val text = src.substring(contentStart, i)
        if (i < src.length && src[i] == '"') advance() // 闭引号
        tokens += Token(TokenType.STRING, text, o, l, c)
    }

    private fun lexChar() {
        val (o, l, c) = Triple(i, line, col)
        advance() // 开引号
        val contentStart = i
        if (i >= src.length || src[i] == '\n') {
            err(DiagCodes.E_UNTERMINATED_STRING, "unterminated char literal at $l:$c")
        } else if (src[i] == '\\') {
            validateEscape(l, c)
        } else {
            advance()
        }
        val text = src.substring(contentStart, i)
        if (i < src.length && src[i] == '\'') advance()
        else err(DiagCodes.E_UNTERMINATED_STRING, "unterminated char literal at $l:$c")
        tokens += Token(TokenType.CHAR, text, o, l, c)
    }

    /** 已位于 '\\'：校验转义并消费转义序列整体。 */
    private fun validateEscape(l: Int, c: Int) {
        advance() // backslash
        when (val e = src.getOrNull(i)) {
            'n', 't', 'r', '"', '\'', '\\' -> advance()
            'u' -> {
                advance()
                var ok = i + 4 <= src.length
                if (ok) {
                    val hex = src.substring(i, i + 4)
                    ok = hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                }
                if (ok) repeat(4) { advance() }
                else {
                    err(DiagCodes.E_INVALID_ESCAPE, "invalid \\u escape at $l:$c")
                    while (i < src.length && src[i].isLetterOrDigit()) advance()
                }
            }
            null, '\n' -> err(DiagCodes.E_INVALID_ESCAPE, "incomplete escape at $l:$c")
            else -> {
                err(DiagCodes.E_INVALID_ESCAPE, "invalid escape '\\$e' at $l:$c")
                advance()
            }
        }
    }

    private fun lexLineComment() {
        while (i < src.length && src[i] != '\n') advance()
    }

    /** `///` 连续行（允许行首缩进）合并为一个 DOC token；text 为各行内容（去掉 /// 与一个前导空格）以换行连接。 */
    private fun lexDocComment() {
        val (o, l, c) = Triple(i, line, col)
        val lines = ArrayList<String>()
        while (true) {
            advance(); advance(); advance() // 消费 ///
            val start = i
            while (i < src.length && src[i] != '\n') advance()
            var content = src.substring(start, i)
            if (content.startsWith(" ")) content = content.substring(1)
            lines += content
            if (i >= src.length) break
            // 回退式向前看：下一行（允许缩进）是否仍是 ///
            val saveI = i
            val saveLine = line
            val saveCol = col
            advance() // 换行
            while (i < src.length && (src[i] == ' ' || src[i] == '\t')) advance()
            if (i + 2 < src.length && src[i] == '/' && src[i + 1] == '/' && src[i + 2] == '/') {
                continue
            }
            i = saveI; line = saveLine; col = saveCol
            break
        }
        tokens += Token(TokenType.DOC, lines.joinToString("\n"), o, l, c)
    }

    // ---------- 运算符 ----------

    private val twoChar: Map<String, TokenType> = mapOf(
        "->" to TokenType.ARROW, "=>" to TokenType.FATARROW,
        "==" to TokenType.EQEQ, "!=" to TokenType.NEQ,
        "<=" to TokenType.LEQ, ">=" to TokenType.GEQ,
        "<<" to TokenType.SHL, ">>" to TokenType.SHR,
        "&&" to TokenType.AND2, "||" to TokenType.OR2,
    )

    private val oneChar: Map<Char, TokenType> = mapOf(
        '(' to TokenType.LPAREN, ')' to TokenType.RPAREN,
        '{' to TokenType.LBRACE, '}' to TokenType.RBRACE,
        '[' to TokenType.LBRACKET, ']' to TokenType.RBRACKET,
        '<' to TokenType.LT, '>' to TokenType.GT,
        ',' to TokenType.COMMA, ':' to TokenType.COLON, '.' to TokenType.DOT,
        '/' to TokenType.SLASH, ';' to TokenType.SEMI,
        '=' to TokenType.EQ, '&' to TokenType.AMP, '|' to TokenType.PIPE,
        '+' to TokenType.PLUS, '-' to TokenType.MINUS, '*' to TokenType.STAR,
        '%' to TokenType.PERCENT, '!' to TokenType.BANG,
    )

    private fun lexOperator() {
        val (o, l, c) = Triple(i, line, col)
        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
        twoChar[two]?.let {
            advance(); advance()
            tokens += Token(it, two, o, l, c)
            return
        }
        val t = oneChar[src[i]]
        if (t != null) {
            val ch = advance()
            tokens += Token(t, ch.toString(), o, l, c)
        } else {
            val ch = advance()
            err(DiagCodes.E_UNEXPECTED_TOKEN, "unexpected character '$ch' at $l:$c")
        }
    }
}
