package dev.loong.engine.text

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.Node
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable

/**
 * AST → 确定性文本渲染（grammar-v1.md 风格，4 空格缩进）。
 *
 * 渲染是纯函数：同一 AST 永远渲染出逐字节相同的文本——这是
 * parse → render → parse 幂等（往返测试）与 diff 工具的基础。
 */
class Renderer(private val table: NodeTable) {

    fun render(root: NodeId): String {
        val sb = StringBuilder()
        sb.append("module ").append(fs(root, "name"))
        if (fs(root, "host_class").isNotEmpty()) sb.append(" host: ").append(fs(root, "host_class"))
        sb.append('\n')
        val depends = kids(root, Kind.Depends)
        depends.forEach { sb.append(renderDepends(it)) }
        val decls = n(root).kids.filter { n(it).kind != Kind.Depends }
        if (decls.isNotEmpty()) {
            sb.append('\n')
            sb.append(decls.joinToString("\n\n") { renderDecl(it, 0) })
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------- 基础 ----------

    private fun n(id: NodeId): Node = table[id]

    private fun fs(id: NodeId, key: String): String =
        (n(id).fields[key] as? FieldValue.Str)?.value ?: ""

    private fun fb(id: NodeId, key: String): Boolean =
        (n(id).fields[key] as? FieldValue.Bool)?.value == true

    private fun kids(id: NodeId, kind: Kind): List<NodeId> = n(id).kids.filter { n(it).kind == kind }

    private fun firstKid(id: NodeId, kind: Kind): NodeId? = n(id).kids.firstOrNull { n(it).kind == kind }

    private fun indent(depth: Int): String = " ".repeat(4 * depth)

    // ---------- 依赖 ----------

    private fun renderDepends(id: NodeId): String {
        val sb = StringBuilder("depends ")
        if (fs(id, "source") == "workspace") sb.append("workspace:")
        sb.append(fs(id, "module_name"))
        val c = n(id).fields["constraint"]
        if (c is FieldValue.Str && c.value.startsWith(">=")) {
            sb.append(" >= ").append(c.value.substring(2))
        }
        sb.append('\n')
        return sb.toString()
    }

    // ---------- 声明 ----------

    private fun renderDecl(id: NodeId, depth: Int): String = when (n(id).kind) {
        Kind.Function -> renderFunction(id, depth)
        Kind.Struct -> renderStruct(id, depth)
        Kind.Interface -> renderInterface(id, depth)
        Kind.Impl -> renderImpl(id, depth)
        Kind.Enum -> renderEnum(id, depth)
        Kind.Const -> renderConst(id, depth)
        Kind.Test -> renderTest(id, depth)
        Kind.ExternalClass -> renderExternalClass(id, depth)
        else -> indent(depth) + "// <unrenderable: ${n(id).kind}>"
    }

    private fun renderDoc(id: NodeId, depth: Int): String =
        fs(id, "text").split("\n").joinToString("\n") { indent(depth) + "/// " + it }

    private fun renderTypeParams(tps: List<NodeId>): String =
        if (tps.isEmpty()) "" else "<" + tps.joinToString(", ") { fs(it, "name") } + ">"

    private fun renderWhere(tps: List<NodeId>): String {
        val constrained = tps.filter { n(it).fields.containsKey("constraint") }
        if (constrained.isEmpty()) return ""
        return " where " + constrained.joinToString(", ") { fs(it, "name") + ": " + fs(it, "constraint") }
    }

    private fun renderParam(id: NodeId): String = fs(id, "name") + ": " + fs(id, "type")

    private fun renderFunction(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
        if (fs(id, "visibility") == "public") sb.append("public ")
        sb.append("function ").append(fs(id, "name"))
        val tps = kids(id, Kind.TypeParam)
        sb.append(renderTypeParams(tps))
        sb.append("(").append(kids(id, Kind.Param).joinToString(", ") { renderParam(it) }).append(")")
        sb.append(" -> ").append(fs(id, "return_type"))
        sb.append(renderWhere(tps))
        val body = firstKid(id, Kind.Block)
        sb.append(" ").append(if (body == null) "{\n" + indent(depth) + "}" else renderBlock(body, depth))
        return sb.toString()
    }

    private fun renderStruct(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
        if (fs(id, "visibility") == "public") sb.append("public ")
        sb.append("struct ").append(fs(id, "name"))
        val tps = kids(id, Kind.TypeParam)
        sb.append(renderTypeParams(tps)).append(renderWhere(tps))
        sb.append(" {\n")
        for (f in kids(id, Kind.Field)) {
            firstKid(f, Kind.DocComment)?.let { sb.append(renderDoc(it, depth + 1)).append('\n') }
            sb.append(indent(depth + 1)).append(fs(f, "name")).append(": ").append(fs(f, "type")).append('\n')
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderInterface(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
        if (fs(id, "visibility") == "public") sb.append("public ")
        sb.append("interface ").append(fs(id, "name"))
        val tps = kids(id, Kind.TypeParam)
        sb.append(renderTypeParams(tps)).append(renderWhere(tps))
        sb.append(" {\n")
        for (m in kids(id, Kind.MethodSig)) {
            firstKid(m, Kind.DocComment)?.let { sb.append(renderDoc(it, depth + 1)).append('\n') }
            val mtps = kids(m, Kind.TypeParam)
            sb.append(indent(depth + 1))
                .append("function ").append(fs(m, "name")).append(renderTypeParams(mtps))
                .append("(").append(kids(m, Kind.Param).joinToString(", ") { renderParam(it) }).append(")")
                .append(" -> ").append(fs(m, "return_type"))
                .append(renderWhere(mtps))
                .append(";\n")
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderImpl(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
            .append("impl ").append(fs(id, "interface")).append(" for ").append(fs(id, "struct"))
            .append(" {\n")
        for (f in kids(id, Kind.Function)) {
            sb.append(renderFunction(f, depth + 1)).append('\n')
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderEnum(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
        if (fs(id, "visibility") == "public") sb.append("public ")
        sb.append("enum ").append(fs(id, "name"))
        val tps = kids(id, Kind.TypeParam)
        sb.append(renderTypeParams(tps)).append(renderWhere(tps))
        sb.append(" {\n")
        for (v in kids(id, Kind.Variant)) {
            firstKid(v, Kind.DocComment)?.let { sb.append(renderDoc(it, depth + 1)).append('\n') }
            sb.append(indent(depth + 1)).append(fs(v, "name"))
            val payload = n(v).fields["payload_type"]
            if (payload is FieldValue.Str) sb.append("(").append(payload.value).append(")")
            sb.append('\n')
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderConst(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
        if (fs(id, "visibility") == "public") sb.append("public ")
        sb.append("const ").append(fs(id, "name")).append(": ").append(fs(id, "type")).append(" = ")
        val value = n(id).kids.lastOrNull { n(it).kind != Kind.DocComment }
        sb.append(if (value == null) "0" else renderExpr(value, depth))
        return sb.toString()
    }

    private fun renderTest(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
            .append("test \"").append(fs(id, "name")).append("\" target: ").append(fs(id, "target_name"))
        val body = firstKid(id, Kind.Block)
        sb.append(" ").append(if (body == null) "{\n" + indent(depth) + "}" else renderBlock(body, depth))
        return sb.toString()
    }

    private fun renderExternalClass(id: NodeId, depth: Int): String {
        val sb = StringBuilder()
        firstKid(id, Kind.DocComment)?.let { sb.append(renderDoc(it, depth)).append('\n') }
        sb.append(indent(depth))
            .append("external java \"").append(fs(id, "class_fqn")).append("\" {\n")
        for (m in kids(id, Kind.ExternalMethod)) {
            firstKid(m, Kind.DocComment)?.let { sb.append(renderDoc(it, depth + 1)).append('\n') }
            sb.append(indent(depth + 1))
                .append(fs(m, "call_kind")).append(" function ").append(fs(m, "name"))
                .append("(").append(kids(m, Kind.Param).joinToString(", ") { renderParam(it) }).append(")")
                .append(" -> ").append(fs(m, "return_type"))
                .append("\n")
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    // ---------- 语句与块 ----------

    private fun renderBlock(id: NodeId, depth: Int): String {
        val stmts = n(id).kids
        if (stmts.isEmpty()) return "{\n" + indent(depth) + "}"
        val sb = StringBuilder("{\n")
        for (s in stmts) sb.append(renderStmt(s, depth + 1)).append('\n')
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderStmt(id: NodeId, depth: Int): String {
        val sb = StringBuilder(indent(depth))
        when (n(id).kind) {
            Kind.Let -> {
                if (fb(id, "shared")) sb.append("shared ")
                sb.append(if (fb(id, "mutable")) "var " else "let ")
                sb.append(fs(id, "name")).append(": ").append(fs(id, "type")).append(" = ")
                sb.append(renderExpr(n(id).kids.first(), depth))
            }
            Kind.Assign -> {
                sb.append(renderExpr(n(id).kids[0], depth))
                    .append(" = ")
                    .append(renderExpr(n(id).kids[1], depth))
            }
            Kind.While -> {
                sb.append("while ").append(renderExpr(n(id).kids[0], depth)).append(" ")
                    .append(renderBlock(n(id).kids[1], depth))
            }
            Kind.For -> {
                sb.append("for ").append(fs(id, "var_name")).append(": ").append(fs(id, "var_type"))
                    .append(" in ").append(renderExpr(n(id).kids[0], depth)).append(" ")
                    .append(renderBlock(n(id).kids[1], depth))
            }
            Kind.Return -> {
                sb.append("return")
                if (n(id).kids.isNotEmpty()) sb.append(" ").append(renderExpr(n(id).kids[0], depth))
            }
            Kind.Break -> sb.append("break")
            Kind.Continue -> sb.append("continue")
            Kind.If -> sb.append(renderIfContent(id, depth))
            Kind.IfLet -> sb.append(renderIfLetContent(id, depth))
            Kind.Match -> sb.append(renderMatchContent(id, depth))
            Kind.ExprStmt -> sb.append(renderExpr(n(id).kids.first(), depth))
            else -> sb.append("// <unrenderable stmt: ").append(n(id).kind).append(">")
        }
        return sb.toString()
    }

    // ---------- 表达式 ----------

    /** 二元运算符结合级别（越大越紧）；grammar §3 优先级表的镜像。 */
    private fun binLevel(op: String): Int = when (op) {
        "||" -> 1
        "&&" -> 2
        "==" -> 3
        "!=" -> 3
        "<" -> 4
        "<=" -> 4
        ">" -> 4
        ">=" -> 4
        "<<" -> 5
        ">>" -> 5
        "|" -> 6
        "&" -> 7
        "+" -> 8
        "-" -> 8
        "*" -> 9
        "/" -> 9
        "%" -> 9
        else -> 13
    }

    /** 表达式结合强度：括号信息不在 AST 中，渲染时按强度最小括号化保证往返保真。 */
    private fun prec(id: NodeId): Int = when (n(id).kind) {
        Kind.Binary -> binLevel(fs(id, "op"))
        Kind.Unary -> 10
        Kind.Join -> 11
        else -> 13
    }

    private fun renderOperand(id: NodeId, minPrec: Int, strict: Boolean, depth: Int): String {
        val s = renderExpr(id, depth)
        val need = if (strict) prec(id) <= minPrec else prec(id) < minPrec
        return if (need) "($s)" else s
    }

    /** 后缀（字段/索引/调用）的接收者若是弱结合表达式必须加括号。 */
    private fun renderReceiver(id: NodeId, depth: Int): String {
        val s = renderExpr(id, depth)
        return if (prec(id) < 12) "($s)" else s
    }

    private fun renderExpr(id: NodeId, depth: Int): String = when (n(id).kind) {
        Kind.Literal -> renderLiteral(id, depth)
        Kind.Path -> fs(id, "written")
        Kind.FieldAccess -> renderReceiver(n(id).kids[0], depth) + "." + fs(id, "field")
        Kind.Index -> renderReceiver(n(id).kids[0], depth) + "[" + renderExpr(n(id).kids[1], depth) + "]"
        Kind.Call -> {
            val callee = renderReceiver(n(id).kids[0], depth)
            val args = n(id).kids.drop(1).joinToString(", ") { renderArg(it, depth) }
            "$callee($args)"
        }
        Kind.Arg -> renderArg(id, depth)
        Kind.Binary -> {
            val lvl = binLevel(fs(id, "op"))
            renderOperand(n(id).kids[0], lvl, strict = false, depth) + " " + fs(id, "op") + " " +
                renderOperand(n(id).kids[1], lvl, strict = true, depth)
        }
        Kind.Unary -> {
            val operand = n(id).kids[0]
            if (prec(operand) < 10) fs(id, "op") + "(" + renderExpr(operand, depth) + ")"
            else fs(id, "op") + renderExpr(operand, depth)
        }
        Kind.Join -> {
            val operand = n(id).kids[0]
            if (prec(operand) < 11) "join (" + renderExpr(operand, depth) + ")"
            else "join " + renderExpr(operand, depth)
        }
        Kind.Spawn -> "spawn " + renderExpr(n(id).kids[0], depth)
        Kind.If -> renderIfContent(id, depth)
        Kind.IfLet -> renderIfLetContent(id, depth)
        Kind.Match -> renderMatchContent(id, depth)
        else -> "<expr:" + n(id).kind + ">"
    }

    private fun renderArg(id: NodeId, depth: Int): String {
        val name = n(id).fields["name"]
        val value = renderExpr(n(id).kids.first(), depth)
        return if (name is FieldValue.Str) name.value + ": " + value else value
    }

    private fun renderLiteral(id: NodeId, depth: Int): String = when (fs(id, "lit_kind")) {
        "string" -> "\"" + fs(id, "value") + "\""
        "char" -> "'" + fs(id, "value") + "'"
        "array" -> "[" + n(id).kids.joinToString(", ") { renderExpr(it, depth) } + "]"
        "map" -> {
            val entries = n(id).kids.joinToString(", ") {
                renderExpr(n(it).kids[0], depth) + ": " + renderExpr(n(it).kids[1], depth)
            }
            if (n(id).kids.isEmpty()) "map { }" else "map { $entries }"
        }
        else -> fs(id, "value")
    }

    private fun renderIfContent(id: NodeId, depth: Int): String {
        val kids = n(id).kids
        val sb = StringBuilder("if ")
            .append(renderExpr(kids[0], depth)).append(" ")
            .append(renderBlock(kids[1], depth))
        if (kids.size > 2) {
            sb.append(" else ")
            val els = kids[2]
            sb.append(
                if (n(els).kind == Kind.If) renderIfContent(els, depth) else renderBlock(els, depth),
            )
        }
        return sb.toString()
    }

    private fun renderIfLetContent(id: NodeId, depth: Int): String {
        val kids = n(id).kids
        val sb = StringBuilder("if let ").append(fs(id, "path"))
        val binding = n(id).fields["binding"]
        if (binding is FieldValue.Str) sb.append("(").append(binding.value).append(")")
        sb.append(" = ").append(renderExpr(kids[0], depth)).append(" ")
            .append(renderBlock(kids[1], depth))
        if (kids.size > 2) sb.append(" else ").append(renderBlock(kids[2], depth))
        return sb.toString()
    }

    private fun renderMatchContent(id: NodeId, depth: Int): String {
        val kids = n(id).kids
        val sb = StringBuilder("match ")
            .append(renderExpr(kids[0], depth)).append(" {\n")
        for (arm in kids.drop(1)) {
            sb.append(indent(depth + 1)).append(renderPattern(arm)).append(" => ")
            val body = n(arm).kids.first()
            sb.append(
                if (n(body).kind == Kind.Block) renderBlock(body, depth + 1) else renderExpr(body, depth + 1),
            )
            sb.append('\n')
        }
        sb.append(indent(depth)).append("}")
        return sb.toString()
    }

    private fun renderPattern(arm: NodeId): String = when (fs(arm, "pattern_kind")) {
        "wildcard" -> "_"
        "literal" -> fs(arm, "literal")
        "enum_ctor" -> {
            var s = fs(arm, "path")
            val binding = n(arm).fields["binding"]
            if (binding is FieldValue.Str) s += "(" + binding.value + ")"
            s
        }
        else -> "_"
    }
}
