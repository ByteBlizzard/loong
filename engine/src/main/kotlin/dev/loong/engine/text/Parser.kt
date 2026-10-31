package dev.loong.engine.text

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.ast.bool
import dev.loong.engine.ast.str
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.Severity

/**
 * 递归下降语法分析器（grammar-v1.md）。产物为 NodeTable 中的完整模块 AST。
 *
 * 约定：
 * - 表达式点链 a.b.c 解析为单 Path（written=全链），FieldAccess 拆分是名字解析的职责；
 * - 类型以规范文本存入字段；嵌套泛型收尾时按需把 >> 拆成两个 >；
 * - 语法错误就近恢复（跳到下一个声明/语句边界），诊断锚定最近完成的节点；
 * - 期望 token 失败时不推进游标，由外层循环保证前进。
 */
class Parser(source: String) {

    class Result(
        val table: NodeTable,
        val root: NodeId,
        val diagnostics: List<Diagnostic>,
    )

    private val lex = Lexer(source).lex()
    private val toks: MutableList<Token> = lex.tokens.toMutableList()
    private val diags: MutableList<Diagnostic> = lex.diagnostics.toMutableList()
    val table = NodeTable()
    private var p = 0
    private var anchor = NodeId.NONE

    companion object {
        const val DEFAULT_VERSION = "/v1"
        private val DECL_SYNC = setOf(
            TokenType.KW_PUBLIC, TokenType.KW_FUNCTION, TokenType.KW_STRUCT,
            TokenType.KW_INTERFACE, TokenType.KW_IMPL, TokenType.KW_ENUM,
            TokenType.KW_CONST, TokenType.KW_TEST, TokenType.KW_DEPENDS,
            TokenType.KW_EXTERNAL,
        )
    }

    fun parse(): Result {
        val root = parseModuleHeader()
        parseDepends(root)
        parseDecls(root)
        if (!at(TokenType.EOF)) {
            err(DiagCodes.E_UNEXPECTED_TOKEN, "unexpected '${cur().text}' after declarations")
        }
        return Result(table, root, diags)
    }

    // ---------- 游标与基础 ----------

    private fun cur(): Token = toks[p]

    private fun la(k: Int): Token = toks[minOf(p + k, toks.size - 1)]

    private fun advance(): Token {
        val t = toks[p]
        if (t.type != TokenType.EOF) p++
        return t
    }

    private fun at(t: TokenType) = cur().type == t

    private fun atCtx(word: String) = cur().type == TokenType.IDENT && cur().text == word

    private fun eat(t: TokenType) = if (at(t)) { advance(); true } else false

    private fun eatCtx(word: String) = if (atCtx(word)) { advance(); true } else false

    private fun expect(t: TokenType, what: String): Token {
        if (at(t)) return advance()
        err(DiagCodes.E_UNEXPECTED_TOKEN, "expected $what but found '${desc()}'")
        return Token(t, "", -1, cur().line, cur().col)
    }

    private fun desc(): String = cur().text.ifEmpty { "end of input" }

    private fun err(code: String, message: String) {
        diags += Diagnostic(anchor, Severity.Error, code, message)
    }

    private fun ident(): String? {
        if (at(TokenType.IDENT)) return advance().text
        err(DiagCodes.E_UNEXPECTED_TOKEN, "expected identifier but found '${desc()}'")
        return null
    }

    private fun fs(n: dev.loong.engine.ast.Node, key: String): String =
        (n.fields[key] as? FieldValue.Str)?.value ?: ""

    // ---------- 模块头与依赖 ----------

    private fun parseModuleHeader(): NodeId {
        if (!at(TokenType.KW_MODULE)) {
            err(DiagCodes.E_MISSING_MODULE_DECL, "file must start with a module declaration")
            val root = table.create(Kind.Module, mapOf("name" to str("<error>")))
            anchor = root
            return root
        }
        advance()
        val name = parseModuleFullName()
        // 显式宿主类（design §6.3）：module a.b host: com.foo.Bar
        val fields = HashMap<String, FieldValue>()
        fields["name"] = str(name)
        if (atCtx("host") && la(1).type == TokenType.COLON) {
            advance()
            advance()
            fields["host_class"] = str(dottedName())
        }
        val root = table.create(Kind.Module, fields)
        anchor = root
        return root
    }

    /** dotted 名 + 可选 /vN；省略版本时规范化为 /v1。 */
    private fun parseModuleFullName(): String {
        val first = ident() ?: run {
            err(DiagCodes.E_BAD_MODULE_NAME, "expected module name")
            return "<error>"
        }
        val parts = arrayListOf(first)
        while (at(TokenType.DOT)) {
            advance()
            val seg = ident() ?: break
            parts += seg
        }
        var name = parts.joinToString(".")
        if (eat(TokenType.SLASH)) {
            val v = ident()
            if (v == null || !Regex("v[0-9]+").matches(v)) {
                err(DiagCodes.E_BAD_MODULE_NAME, "version segment must look like /v2")
            } else {
                name += "/$v"
            }
        } else {
            name += DEFAULT_VERSION
        }
        return name
    }

    private fun parseDepends(root: NodeId) {
        while (at(TokenType.KW_DEPENDS)) {
            advance()
            while (true) {
                val dep = parseDependsItem()
                table.attach(dep, root, table[root].kids.size)
                anchor = dep
                if (!eat(TokenType.COMMA)) break
            }
        }
    }

    private fun parseDependsItem(): NodeId {
        if (eat(TokenType.KW_WORKSPACE)) {
            expect(TokenType.COLON, ":")
            val name = dottedName()
            return table.create(
                Kind.Depends,
                mapOf("module_name" to str(name), "source" to str("workspace")),
            )
        }
        val name = parseModuleFullName()
        val fields = mutableMapOf<String, FieldValue>(
            "module_name" to str(name),
            "source" to str("registry"),
        )
        if (eat(TokenType.GEQ)) {
            fields["constraint"] = str(">=" + parseVersion())
        }
        return table.create(Kind.Depends, fields)
    }

    /**
     * 版本号 x.y.z。词法层会把 "1.2" 识别为 FLOAT，这里按
     * (INT|FLOAT) "." INT 组装回三段文本。
     */
    private fun parseVersion(): String {
        val majorMinor = when {
            at(TokenType.INT) -> advance().text + ".0"
            at(TokenType.FLOAT) -> {
                val t = advance().text
                if (t.contains('e') || t.contains('E')) {
                    err(DiagCodes.E_UNEXPECTED_TOKEN, "invalid version number '$t'")
                }
                t
            }
            else -> {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "expected version number but found '${desc()}'")
                "0.0"
            }
        }
        expect(TokenType.DOT, ".")
        return "$majorMinor.${expectInt()}"
    }

    private fun expectInt(): String {
        if (at(TokenType.INT)) return advance().text
        err(DiagCodes.E_UNEXPECTED_TOKEN, "expected version number but found '${desc()}'")
        return "0"
    }

    private fun dottedName(): String {
        val first = ident() ?: return "<error>"
        val parts = arrayListOf(first)
        while (at(TokenType.DOT)) {
            advance()
            val seg = ident() ?: break
            parts += seg
        }
        return parts.joinToString(".")
    }

    // ---------- 声明 ----------

    private fun parseDecls(root: NodeId) {
        while (!at(TokenType.EOF)) {
            val doc = parseDocCommentOpt()
            val before = p
            var visibility = "internal"
            if (at(TokenType.KW_PUBLIC)) {
                advance()
                visibility = "public"
            }
            when {
                at(TokenType.KW_FUNCTION) -> parseFunction(root, doc, visibility)
                at(TokenType.KW_STRUCT) -> parseStruct(root, doc, visibility)
                at(TokenType.KW_INTERFACE) -> parseInterface(root, doc, visibility)
                at(TokenType.KW_IMPL) -> parseImpl(root, doc)
                at(TokenType.KW_ENUM) -> parseEnum(root, doc, visibility)
                at(TokenType.KW_CONST) -> parseConst(root, doc, visibility)
                at(TokenType.KW_TEST) -> parseTest(root, doc)
                at(TokenType.KW_EXTERNAL) -> parseExternal(root, doc)
                else -> {
                    err(DiagCodes.E_UNEXPECTED_TOKEN, "expected declaration but found '${desc()}'")
                    syncToDecl()
                }
            }
            if (p == before) advance() // 保证推进
        }
    }

    private fun syncToDecl() {
        while (!at(TokenType.EOF) && cur().type !in DECL_SYNC && !atCtx("external")) advance()
    }

    private fun parseDocCommentOpt(): NodeId? {
        if (!at(TokenType.DOC)) return null
        val lines = ArrayList<String>()
        while (at(TokenType.DOC)) lines += advance().text
        return table.create(Kind.DocComment, mapOf("text" to str(lines.joinToString("\n"))))
    }

    private fun parseFunction(parent: NodeId, doc: NodeId?, visibility: String) {
        advance() // function
        val name = ident() ?: "<error>"
        val tpNames = ArrayList<String>()
        val tpNodes = ArrayList<NodeId>()
        parseTypeParams(tpNames, tpNodes)
        val params = parseParamList()
        expect(TokenType.ARROW, "->")
        val ret = parseType()
        parseWhereClause(tpNames, tpNodes)
        val body = parseBlock()
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += tpNodes
        kids += params
        kids += body
        val fn = table.create(
            Kind.Function,
            mapOf(
                "name" to str(name),
                "visibility" to str(visibility),
                "return_type" to str(ret),
            ),
            kids,
        )
        table.attach(fn, parent, table[parent].kids.size)
        anchor = fn
    }

    private fun parseParamList(): List<NodeId> {
        expect(TokenType.LPAREN, "(")
        val params = ArrayList<NodeId>()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            val before = p
            val pn = ident() ?: break
            expect(TokenType.COLON, ":")
            val pt = parseType()
            params += table.create(Kind.Param, mapOf("name" to str(pn), "type" to str(pt)))
            if (!eat(TokenType.COMMA)) break
            if (p == before) advance()
        }
        expect(TokenType.RPAREN, ")")
        return params
    }

    private fun parseTypeParams(names: MutableList<String>, nodes: MutableList<NodeId>) {
        if (!eat(TokenType.LT)) return
        while (!at(TokenType.GT) && !at(TokenType.SHR) && !at(TokenType.EOF)) {
            val n = ident() ?: break
            names += n
            nodes += table.create(Kind.TypeParam, mapOf("name" to str(n)))
            if (!eat(TokenType.COMMA)) break
        }
        consumeGt()
    }

    private fun parseWhereClause(names: List<String>, nodes: List<NodeId>) {
        if (!eatCtx("where")) return
        while (true) {
            val n = ident() ?: break
            expect(TokenType.COLON, ":")
            val constraint = parseTypeNameText()
            val idx = names.indexOf(n)
            if (idx < 0) {
                err(DiagCodes.E_UNKNOWN_NAME, "where constraint references unknown type parameter '$n'")
            } else {
                val node = nodes[idx]
                val fields = table[node].fields.toMutableMap()
                fields["constraint"] = str(constraint)
                table.replace(node, fields = fields)
            }
            if (!eat(TokenType.COMMA)) break
        }
    }

    private fun parseStruct(parent: NodeId, doc: NodeId?, visibility: String) {
        advance()
        val name = ident() ?: "<error>"
        val tpNames = ArrayList<String>()
        val tpNodes = ArrayList<NodeId>()
        parseTypeParams(tpNames, tpNodes)
        parseWhereClause(tpNames, tpNodes)
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += tpNodes
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val fdoc = parseDocCommentOpt()
            val fname = ident() ?: break
            expect(TokenType.COLON, ":")
            val ftype = parseType()
            val fkids = ArrayList<NodeId>()
            if (fdoc != null) fkids += fdoc
            kids += table.create(Kind.Field, mapOf("name" to str(fname), "type" to str(ftype)), fkids)
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        val node = table.create(Kind.Struct, mapOf("name" to str(name), "visibility" to str(visibility)), kids)
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    private fun parseInterface(parent: NodeId, doc: NodeId?, visibility: String) {
        advance()
        val name = ident() ?: "<error>"
        val tpNames = ArrayList<String>()
        val tpNodes = ArrayList<NodeId>()
        parseTypeParams(tpNames, tpNodes)
        parseWhereClause(tpNames, tpNodes)
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += tpNodes
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val mdoc = parseDocCommentOpt()
            if (!at(TokenType.KW_FUNCTION)) {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "interface member must be a function signature")
                while (!at(TokenType.EOF) && !at(TokenType.KW_FUNCTION) && !at(TokenType.RBRACE)) advance()
                if (p == before) advance()
                continue
            }
            advance()
            val mname = ident() ?: break
            val mtpNames = ArrayList<String>()
            val mtpNodes = ArrayList<NodeId>()
            parseTypeParams(mtpNames, mtpNodes)
            val params = parseParamList()
            expect(TokenType.ARROW, "->")
            val ret = parseType()
            parseWhereClause(mtpNames, mtpNodes)
            eat(TokenType.SEMI)
            val mkids = ArrayList<NodeId>()
            if (mdoc != null) mkids += mdoc
            mkids += mtpNodes
            mkids += params
            kids += table.create(Kind.MethodSig, mapOf("name" to str(mname), "return_type" to str(ret)), mkids)
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        val node = table.create(Kind.Interface, mapOf("name" to str(name), "visibility" to str(visibility)), kids)
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    private fun parseImpl(parent: NodeId, doc: NodeId?) {
        advance()
        val itf = parseTypeNameText()
        expect(TokenType.KW_FOR, "for")
        val st = parseTypeNameText()
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val fdoc = parseDocCommentOpt()
            var visibility = "internal"
            if (at(TokenType.KW_PUBLIC)) {
                advance()
                visibility = "public"
            }
            if (!at(TokenType.KW_FUNCTION)) {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "impl body must contain functions")
                while (!at(TokenType.EOF) && !at(TokenType.KW_FUNCTION) && !at(TokenType.RBRACE)) advance()
                if (p == before) advance()
                continue
            }
            parseImplFunction(kids, fdoc, visibility)
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        val node = table.create(Kind.Impl, mapOf("interface" to str(itf), "struct" to str(st)), kids)
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    /** impl 内函数：解析进 kids 列表（不挂模块）。 */
    private fun parseImplFunction(kids: MutableList<NodeId>, doc: NodeId?, visibility: String) {
        advance() // function
        val name = ident() ?: "<error>"
        val tpNames = ArrayList<String>()
        val tpNodes = ArrayList<NodeId>()
        parseTypeParams(tpNames, tpNodes)
        val params = parseParamList()
        expect(TokenType.ARROW, "->")
        val ret = parseType()
        parseWhereClause(tpNames, tpNodes)
        val body = parseBlock()
        val fkids = ArrayList<NodeId>()
        if (doc != null) fkids += doc
        fkids += tpNodes
        fkids += params
        fkids += body
        kids += table.create(
            Kind.Function,
            mapOf(
                "name" to str(name),
                "visibility" to str(visibility),
                "return_type" to str(ret),
            ),
            fkids,
        )
    }

    private fun parseEnum(parent: NodeId, doc: NodeId?, visibility: String) {
        advance()
        val name = ident() ?: "<error>"
        val tpNames = ArrayList<String>()
        val tpNodes = ArrayList<NodeId>()
        parseTypeParams(tpNames, tpNodes)
        parseWhereClause(tpNames, tpNodes)
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += tpNodes
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val vdoc = parseDocCommentOpt()
            val vname = ident() ?: break
            val fields = mutableMapOf<String, FieldValue>("name" to str(vname))
            if (eat(TokenType.LPAREN)) {
                fields["payload_type"] = str(parseType())
                expect(TokenType.RPAREN, ")")
            }
            val vkids = ArrayList<NodeId>()
            if (vdoc != null) vkids += vdoc
            kids += table.create(Kind.Variant, fields, vkids)
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        val node = table.create(Kind.Enum, mapOf("name" to str(name), "visibility" to str(visibility)), kids)
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    private fun parseConst(parent: NodeId, doc: NodeId?, visibility: String) {
        advance()
        val name = ident() ?: "<error>"
        expect(TokenType.COLON, ":")
        val t = parseType()
        expect(TokenType.EQ, "=")
        val value = parsePrimary()
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += value
        val node = table.create(
            Kind.Const,
            mapOf("name" to str(name), "visibility" to str(visibility), "type" to str(t)),
            kids,
        )
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    private fun parseTest(parent: NodeId, doc: NodeId?) {
        advance()
        val nameTok = expect(TokenType.STRING, "test name string")
        expect(TokenType.KW_TARGET, "target")
        expect(TokenType.COLON, ":")
        val target = dottedName()
        val body = parseBlock()
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        kids += body
        val node = table.create(
            Kind.Test,
            mapOf("name" to str(nameTok.text), "target_name" to str(target)),
            kids,
        )
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    private fun parseExternal(parent: NodeId, doc: NodeId?) {
        advance() // external（上下文关键字）
        if (!eatCtx("java")) err(DiagCodes.E_UNEXPECTED_TOKEN, "expected 'java' after 'external'")
        val fqnTok = expect(TokenType.STRING, "target class fully-qualified name")
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        if (doc != null) kids += doc
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val mdoc = parseDocCommentOpt()
            val callKind = when {
                atCtx("static") -> "static"
                atCtx("instance") -> "instance"
                atCtx("constructor") -> "constructor"
                else -> null
            }
            if (callKind == null) {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "expected 'static', 'instance' or 'constructor'")
                while (!at(TokenType.EOF) && !at(TokenType.RBRACE) && !atCtx("static") &&
                    !atCtx("instance") && !atCtx("constructor")
                ) advance()
                if (p == before) advance()
                continue
            }
            advance()
            expect(TokenType.KW_FUNCTION, "function")
            val mname = ident() ?: break
            val params = parseParamList()
            expect(TokenType.ARROW, "->")
            val ret = parseType()
            // 别名：static function f(...) -> T as "jvmName"——符号名与 JVM 方法名分离
            var jvmName: String? = null
            if (atCtx("as") && la(1).type == TokenType.STRING) {
                advance()
                jvmName = advance().text
            }
            eat(TokenType.SEMI)
            val mkids = ArrayList<NodeId>()
            if (mdoc != null) mkids += mdoc
            mkids += params
            val mfields = mutableMapOf<String, FieldValue>(
                "name" to str(mname),
                "call_kind" to str(callKind),
                "return_type" to str(ret),
            )
            if (jvmName != null) mfields["jvm_name"] = str(jvmName)
            kids += table.create(Kind.ExternalMethod, mfields, mkids)
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        val node = table.create(Kind.ExternalClass, mapOf("class_fqn" to str(fqnTok.text)), kids)
        table.attach(node, parent, table[parent].kids.size)
        anchor = node
    }

    // ---------- 类型（规范文本） ----------

    private fun parseType(): String {
        if (eat(TokenType.LPAREN)) {
            val parts = ArrayList<String>()
            if (!at(TokenType.RPAREN)) {
                parts += parseType()
                while (eat(TokenType.COMMA)) parts += parseType()
            }
            expect(TokenType.RPAREN, ")")
            if (!at(TokenType.ARROW)) {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "parenthesized type must be a function type '(A, B)' -> C")
                return parts.singleOrNull() ?: "Unit"
            }
            advance()
            val r = parseType()
            return "(" + parts.joinToString(", ") + ") -> " + r
        }
        return parseTypeNameText()
    }

    private fun parseTypeNameText(): String {
        val base = dottedName()
        if (base == "<error>") return base
        if (at(TokenType.LT)) {
            advance()
            val args = ArrayList<String>()
            while (!at(TokenType.GT) && !at(TokenType.SHR) && !at(TokenType.EOF)) {
                args += parseType()
                if (!eat(TokenType.COMMA)) break
            }
            consumeGt()
            return base + "<" + args.joinToString(", ") + ">"
        }
        return base
    }

    /** 泛型实参收尾；嵌套泛型时按需把 >> 拆成两个 >。 */
    private fun consumeGt() {
        when {
            at(TokenType.GT) -> advance()
            at(TokenType.SHR) -> {
                val t = cur()
                toks[p] = Token(TokenType.GT, ">", t.offset, t.line, t.col)
                toks.add(p + 1, Token(TokenType.GT, ">", t.offset + 1, t.line, t.col + 1))
                advance()
            }
            else -> err(DiagCodes.E_UNEXPECTED_TOKEN, "expected '>' to close type arguments")
        }
    }

    // ---------- 语句 ----------

    private fun parseBlock(): NodeId {
        val block = table.create(Kind.Block)
        expect(TokenType.LBRACE, "{")
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            val stmt = parseStmt()
            table.attach(stmt, block, table[block].kids.size)
            anchor = stmt
            if (p == before) advance()
        }
        eat(TokenType.RBRACE)
        return block
    }

    private fun atStmtEnd(): Boolean =
        at(TokenType.RBRACE) || at(TokenType.EOF) || cur().type in setOf(
            TokenType.KW_LET, TokenType.KW_VAR, TokenType.KW_SHARED,
            TokenType.KW_WHILE, TokenType.KW_FOR, TokenType.KW_RETURN,
            TokenType.KW_BREAK, TokenType.KW_CONTINUE,
        )

    private fun parseStmt(): NodeId {
        return when {
            at(TokenType.KW_SHARED) || at(TokenType.KW_LET) || at(TokenType.KW_VAR) -> parseLet()
            at(TokenType.KW_WHILE) -> {
                advance()
                val cond = parseExpr()
                val body = parseBlock()
                table.create(Kind.While, kids = listOf(cond, body))
            }
            at(TokenType.KW_FOR) -> {
                advance()
                val n = ident() ?: "<error>"
                expect(TokenType.COLON, ":")
                val t = parseType()
                expect(TokenType.KW_IN, "in")
                val iter = parseExpr()
                val body = parseBlock()
                table.create(Kind.For, mapOf("var_name" to str(n), "var_type" to str(t)), listOf(iter, body))
            }
            at(TokenType.KW_RETURN) -> {
                advance()
                if (atStmtEnd()) table.create(Kind.Return)
                else table.create(Kind.Return, kids = listOf(parseExpr()))
            }
            at(TokenType.KW_BREAK) -> {
                advance()
                table.create(Kind.Break)
            }
            at(TokenType.KW_CONTINUE) -> {
                advance()
                table.create(Kind.Continue)
            }
            else -> {
                val e = parseExpr()
                if (at(TokenType.EQ)) {
                    advance()
                    if (table[e].kind !in setOf(Kind.Path, Kind.FieldAccess, Kind.Index)) {
                        err(DiagCodes.E_UNEXPECTED_TOKEN, "assignment target must be a path, field access or index")
                    }
                    val v = parseExpr()
                    table.create(Kind.Assign, kids = listOf(e, v))
                } else {
                    table.create(Kind.ExprStmt, kids = listOf(e))
                }
            }
        }
    }

    private fun parseLet(): NodeId {
        val shared = eat(TokenType.KW_SHARED)
        val mutable = when {
            eat(TokenType.KW_VAR) -> true
            else -> {
                expect(TokenType.KW_LET, "let or var")
                false
            }
        }
        if (shared && !mutable) {
            err(DiagCodes.E_UNEXPECTED_TOKEN, "'shared' must modify 'var'")
        }
        val name = ident() ?: "<error>"
        expect(TokenType.COLON, ":")
        val t = parseType()
        expect(TokenType.EQ, "=")
        val init = parseExpr()
        return table.create(
            Kind.Let,
            mapOf(
                "name" to str(name),
                "type" to str(t),
                "mutable" to bool(mutable),
                "shared" to bool(shared),
            ),
            listOf(init),
        )
    }

    // ---------- 表达式 ----------

    private val binLevels = listOf(
        setOf("||"), setOf("&&"), setOf("==", "!="),
        setOf("<", "<=", ">", ">="), setOf("<<", ">>"), setOf("|"), setOf("&"),
        setOf("+", "-"), setOf("*", "/", "%"),
    )

    private fun binOpAt(level: Int): String? {
        val op = when (cur().type) {
            TokenType.OR2 -> "||"
            TokenType.AND2 -> "&&"
            TokenType.EQEQ -> "=="
            TokenType.NEQ -> "!="
            TokenType.LT -> "<"
            TokenType.LEQ -> "<="
            TokenType.GT -> ">"
            TokenType.GEQ -> ">="
            TokenType.SHL -> "<<"
            TokenType.SHR -> ">>"
            TokenType.PIPE -> "|"
            TokenType.AMP -> "&"
            TokenType.PLUS -> "+"
            TokenType.MINUS -> "-"
            TokenType.STAR -> "*"
            TokenType.SLASH -> "/"
            TokenType.PERCENT -> "%"
            else -> null
        } ?: return null
        return if (op in binLevels[level]) op else null
    }

    private fun parseExpr(): NodeId = parseBin(0)

    private fun parseBin(level: Int): NodeId {
        if (level >= binLevels.size) return parseUnary()
        var lhs = parseBin(level + 1)
        while (true) {
            val op = binOpAt(level) ?: break
            advance()
            val rhs = parseBin(level + 1)
            lhs = table.create(Kind.Binary, mapOf("op" to str(op)), listOf(lhs, rhs))
        }
        return lhs
    }

    private fun parseUnary(): NodeId = when {
        at(TokenType.MINUS) || at(TokenType.BANG) -> {
            val op = if (at(TokenType.MINUS)) "-" else "!"
            advance()
            val operand = parseUnary()
            table.create(Kind.Unary, mapOf("op" to str(op)), listOf(operand))
        }
        at(TokenType.KW_JOIN) -> {
            advance()
            val e = parsePostfix()
            table.create(Kind.Join, kids = listOf(e))
        }
        else -> parsePostfix()
    }

    private fun parsePostfix(): NodeId {
        var e = parsePrimary()
        while (true) {
            when {
                at(TokenType.DOT) -> {
                    advance()
                    val seg = ident() ?: break
                    val node = table[e]
                    if (node.kind == Kind.Path) {
                        // 点链最大吞噬：a.b.c 保持单 Path，拆分留给名字解析
                        val written = fs(node, "written")
                        val fields = node.fields.toMutableMap()
                        fields["written"] = str("$written.$seg")
                        table.replace(e, fields = fields)
                    } else {
                        e = table.create(Kind.FieldAccess, mapOf("field" to str(seg)), listOf(e))
                    }
                }
                at(TokenType.LBRACKET) -> {
                    advance()
                    val idx = parseExpr()
                    expect(TokenType.RBRACKET, "]")
                    e = table.create(Kind.Index, kids = listOf(e, idx))
                }
                at(TokenType.LPAREN) -> {
                    val args = parseArgList()
                    val kids = ArrayList<NodeId>()
                    kids += e
                    kids += args
                    e = table.create(Kind.Call, kids = kids)
                }
                else -> return e
            }
        }
        return e // 不可达
    }

    private fun parseArgList(): List<NodeId> {
        expect(TokenType.LPAREN, "(")
        val args = ArrayList<NodeId>()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            val before = p
            val arg: NodeId = if (at(TokenType.IDENT) && la(1).type == TokenType.COLON) {
                val n = advance().text
                advance() // :
                val e = parseExpr()
                table.create(Kind.Arg, mapOf("name" to str(n)), listOf(e))
            } else {
                val e = parseExpr()
                table.create(Kind.Arg, kids = listOf(e))
            }
            args += arg
            if (!eat(TokenType.COMMA)) break
            if (p == before) advance()
        }
        expect(TokenType.RPAREN, ")")
        return args
    }

    private fun literal(kind: String, text: String): NodeId =
        table.create(Kind.Literal, mapOf("lit_kind" to str(kind), "value" to str(text)))

    private fun parsePrimary(): NodeId {
        return when {
            at(TokenType.INT) -> literal("int", advance().text)
            at(TokenType.FLOAT) -> literal("float", advance().text)
            at(TokenType.STRING) -> literal("string", advance().text)
            at(TokenType.CHAR) -> literal("char", advance().text)
            at(TokenType.KW_TRUE) -> {
                advance(); literal("bool", "true")
            }
            at(TokenType.KW_FALSE) -> {
                advance(); literal("bool", "false")
            }
            at(TokenType.LBRACKET) -> {
                advance()
                val elems = ArrayList<NodeId>()
                while (!at(TokenType.RBRACKET) && !at(TokenType.EOF)) {
                    elems += parseExpr()
                    if (!eat(TokenType.COMMA)) break
                }
                expect(TokenType.RBRACKET, "]")
                table.create(Kind.Literal, mapOf("lit_kind" to str("array")), elems)
            }
            atCtx("map") && la(1).type == TokenType.LBRACE -> {
                advance() // map
                advance() // {
                val entries = ArrayList<NodeId>()
                while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                    val k = parseExpr()
                    expect(TokenType.COLON, ":")
                    val v = parseExpr()
                    entries += table.create(Kind.MapEntry, kids = listOf(k, v))
                    if (!eat(TokenType.COMMA)) break
                }
                expect(TokenType.RBRACE, "}")
                table.create(Kind.Literal, mapOf("lit_kind" to str("map")), entries)
            }
            at(TokenType.IDENT) -> {
                val t = advance()
                table.create(Kind.Path, mapOf("written" to str(t.text)))
            }
            at(TokenType.KW_SPAWN) -> {
                advance()
                val e = parsePostfix()
                if (table[e].kind != Kind.Call) {
                    err(DiagCodes.E_UNEXPECTED_TOKEN, "'spawn' requires a call")
                }
                table.create(Kind.Spawn, kids = listOf(e))
            }
            at(TokenType.KW_IF) -> if (la(1).type == TokenType.KW_LET) parseIfLet() else parseIf()
            at(TokenType.KW_MATCH) -> parseMatch()
            at(TokenType.LPAREN) -> {
                advance()
                val e = parseExpr()
                expect(TokenType.RPAREN, ")")
                e
            }
            else -> {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "expected expression but found '${desc()}'")
                if (!at(TokenType.EOF) && !at(TokenType.RBRACE)) advance()
                literal("int", "0") // 错误恢复占位
            }
        }
    }

    private fun parseIf(): NodeId {
        advance() // if
        val cond = parseExpr()
        val thenB = parseBlock()
        val kids = ArrayList<NodeId>()
        kids += cond
        kids += thenB
        if (eat(TokenType.KW_ELSE)) {
            kids += if (at(TokenType.KW_IF)) parseIf() else parseBlock()
        }
        return table.create(Kind.If, kids = kids)
    }

    private fun parseIfLet(): NodeId {
        advance() // if
        advance() // let
        val path = dottedName()
        var binding: String? = null
        if (eat(TokenType.LPAREN)) {
            binding = ident()
            expect(TokenType.RPAREN, ")")
        }
        expect(TokenType.EQ, "=")
        val scrut = parseExpr()
        val thenB = parseBlock()
        val kids = ArrayList<NodeId>()
        kids += scrut
        kids += thenB
        if (eat(TokenType.KW_ELSE)) kids += parseBlock()
        val fields = mutableMapOf<String, FieldValue>("path" to str(path))
        if (binding != null) fields["binding"] = str(binding)
        return table.create(Kind.IfLet, fields, kids)
    }

    private fun parseMatch(): NodeId {
        advance() // match
        val scrut = parseExpr()
        expect(TokenType.LBRACE, "{")
        val kids = ArrayList<NodeId>()
        kids += scrut
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            val before = p
            kids += parseMatchArm()
            if (p == before) advance()
        }
        expect(TokenType.RBRACE, "}")
        return table.create(Kind.Match, kids = kids)
    }

    private fun parseMatchArm(): NodeId {
        val pattern = parsePattern()
        expect(TokenType.FATARROW, "=>")
        val body = if (at(TokenType.LBRACE)) parseBlock() else parseExpr()
        return table.create(Kind.MatchArm, pattern, listOf(body))
    }

    private fun parsePattern(): MutableMap<String, FieldValue> {
        return when {
            at(TokenType.IDENT) && cur().text == "_" -> {
                advance()
                mutableMapOf("pattern_kind" to str("wildcard"))
            }
            at(TokenType.INT) || at(TokenType.FLOAT) || at(TokenType.STRING) ||
                at(TokenType.CHAR) || at(TokenType.KW_TRUE) || at(TokenType.KW_FALSE) -> {
                val text = advance().text
                mutableMapOf("pattern_kind" to str("literal"), "literal" to str(text))
            }
            at(TokenType.IDENT) -> {
                val path = dottedName()
                val fields = mutableMapOf<String, FieldValue>(
                    "pattern_kind" to str("enum_ctor"),
                    "path" to str(path),
                )
                if (eat(TokenType.LPAREN)) {
                    fields["binding"] = str(ident() ?: "")
                    expect(TokenType.RPAREN, ")")
                }
                fields
            }
            else -> {
                err(DiagCodes.E_UNEXPECTED_TOKEN, "expected pattern but found '${desc()}'")
                if (!at(TokenType.EOF)) advance()
                mutableMapOf("pattern_kind" to str("wildcard"))
            }
        }
    }
}
