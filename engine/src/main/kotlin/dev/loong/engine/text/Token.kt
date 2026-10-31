package dev.loong.engine.text

import dev.loong.engine.diagnostics.Diagnostic

/** 词法单元。offset/line/col 指向 token 起始位置（1 基行/列）。 */
data class Token(
    val type: TokenType,
    val text: String,
    val offset: Int,
    val line: Int,
    val col: Int,
)

enum class TokenType {
    IDENT, INT, FLOAT, STRING, CHAR, DOC,

    // 保留关键字（grammar-v1.md §1.1）
    KW_MODULE, KW_DEPENDS, KW_WORKSPACE, KW_PUBLIC, KW_FUNCTION, KW_STRUCT,
    KW_INTERFACE, KW_IMPL, KW_ENUM, KW_CONST, KW_TEST, KW_TARGET,
    KW_LET, KW_VAR, KW_SHARED, KW_IF, KW_ELSE, KW_WHILE, KW_FOR, KW_IN,
    KW_MATCH, KW_RETURN, KW_BREAK, KW_CONTINUE, KW_SPAWN, KW_JOIN,
    KW_EXTERNAL, KW_TRUE, KW_FALSE,

    // 分隔符与运算符
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    LT, GT, COMMA, COLON, DOT, SLASH, SEMI,
    ARROW, FATARROW, EQ, EQEQ, NEQ, LEQ, GEQ,
    SHL, SHR, AND2, OR2, AMP, PIPE, PLUS, MINUS, STAR, PERCENT, BANG,
    EOF
}

/** 保留字 → token 类型。上下文关键字（map/java/where/static/instance/constructor）按 IDENT 处理。 */
internal val KEYWORDS: Map<String, TokenType> = mapOf(
    "module" to TokenType.KW_MODULE,
    "depends" to TokenType.KW_DEPENDS,
    "workspace" to TokenType.KW_WORKSPACE,
    "public" to TokenType.KW_PUBLIC,
    "function" to TokenType.KW_FUNCTION,
    "struct" to TokenType.KW_STRUCT,
    "interface" to TokenType.KW_INTERFACE,
    "impl" to TokenType.KW_IMPL,
    "enum" to TokenType.KW_ENUM,
    "const" to TokenType.KW_CONST,
    "test" to TokenType.KW_TEST,
    "target" to TokenType.KW_TARGET,
    "let" to TokenType.KW_LET,
    "var" to TokenType.KW_VAR,
    "shared" to TokenType.KW_SHARED,
    "if" to TokenType.KW_IF,
    "else" to TokenType.KW_ELSE,
    "while" to TokenType.KW_WHILE,
    "for" to TokenType.KW_FOR,
    "in" to TokenType.KW_IN,
    "match" to TokenType.KW_MATCH,
    "return" to TokenType.KW_RETURN,
    "break" to TokenType.KW_BREAK,
    "continue" to TokenType.KW_CONTINUE,
    "spawn" to TokenType.KW_SPAWN,
    "join" to TokenType.KW_JOIN,
    "external" to TokenType.KW_EXTERNAL,
    "true" to TokenType.KW_TRUE,
    "false" to TokenType.KW_FALSE,
)

class LexResult(
    val tokens: List<Token>,
    val diagnostics: List<Diagnostic>,
)
