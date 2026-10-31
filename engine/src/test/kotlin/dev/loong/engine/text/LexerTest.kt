package dev.loong.engine.text

import dev.loong.engine.diagnostics.DiagCodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LexerTest {

    private fun lex(src: String) = Lexer(src).lex()

    private fun types(src: String) = lex(src).tokens.map { it.type }

    @Test
    fun `reserved keywords vs contextual keywords`() {
        val r = lex("module let x map where java static instance constructor target")
        val t = r.tokens
        assertEquals(TokenType.KW_MODULE, t[0].type)
        assertEquals(TokenType.KW_LET, t[1].type)
        assertEquals(TokenType.KW_TARGET, t[9].type)
        // 上下文关键字一律按 IDENT
        listOf("x", "map", "where", "java", "static", "instance", "constructor").forEachIndexed { i, w ->
            assertEquals(TokenType.IDENT, t[2 + i].type, w)
            assertEquals(w, t[2 + i].text)
        }
        assertEquals(0, r.diagnostics.size)
    }

    @Test
    fun `numbers`() {
        val t = lex("1 0x1F 3.14 1e9 2.5E-3").tokens
        assertEquals(TokenType.INT, t[0].type)
        assertEquals("0x1F", t[1].text)
        assertEquals(TokenType.FLOAT, t[2].type)
        assertEquals(TokenType.FLOAT, t[3].type)
        assertEquals("2.5E-3", t[4].text)
        assertTrue(lex("12abc").diagnostics.any { it.code == DiagCodes.E_INVALID_NUMBER })
    }

    @Test
    fun `strings keep raw content`() {
        val t = lex("\"a\\nb\"").tokens
        assertEquals(TokenType.STRING, t[0].type)
        assertEquals("a\\nb", t[0].text) // 反斜杠原样保留，渲染时原样回放
        assertEquals(0, lex("\"a\\nb\"").diagnostics.size)
    }

    @Test
    fun `string errors`() {
        assertTrue(lex("\"abc").diagnostics.any { it.code == DiagCodes.E_UNTERMINATED_STRING })
        assertTrue(lex("\"a\nb\"").diagnostics.any { it.code == DiagCodes.E_UNTERMINATED_STRING })
        assertTrue(lex("\"a\\q\"").diagnostics.any { it.code == DiagCodes.E_INVALID_ESCAPE })
        assertTrue(lex("\"\\u00\"").diagnostics.any { it.code == DiagCodes.E_INVALID_ESCAPE })
    }

    @Test
    fun `char literals`() {
        val t = lex("'a' '\\n'").tokens
        assertEquals(TokenType.CHAR, t[0].type)
        assertEquals("a", t[0].text)
        assertEquals("\\n", t[1].text)
        assertTrue(lex("'a").diagnostics.any { it.code == DiagCodes.E_UNTERMINATED_STRING })
    }

    @Test
    fun `operators and punctuation`() {
        assertEquals(
            listOf(
                TokenType.ARROW, TokenType.FATARROW, TokenType.EQEQ, TokenType.NEQ,
                TokenType.LEQ, TokenType.GEQ, TokenType.SHL, TokenType.SHR,
                TokenType.AND2, TokenType.OR2, TokenType.EOF,
            ),
            types("-> => == != <= >= << >> && ||"),
        )
        assertEquals(
            listOf(TokenType.IDENT, TokenType.LT, TokenType.IDENT, TokenType.EOF),
            types("a < b"),
        )
    }

    @Test
    fun `comments and doc merging`() {
        val r = lex("/// one\n/// two\n// skip me\nlet")
        val t = r.tokens
        assertEquals(TokenType.DOC, t[0].type)
        assertEquals("one\ntwo", t[0].text) // 连续 /// 合并为一个 token
        assertEquals(TokenType.KW_LET, t[1].type) // 行注释被丢弃

        // 缩进的连续 /// 也合并；前导空格去掉一个
        val r2 = lex("/// a\n    /// b\nx")
        assertEquals("a\nb", r2.tokens[0].text)
    }

    @Test
    fun `semicolon is dropped`() {
        assertEquals(listOf(TokenType.IDENT, TokenType.EOF), types("x;"))
    }

    @Test
    fun `unknown character is a diagnostic`() {
        assertTrue(lex("a # b").diagnostics.any { it.code == DiagCodes.E_UNEXPECTED_TOKEN })
    }
}
