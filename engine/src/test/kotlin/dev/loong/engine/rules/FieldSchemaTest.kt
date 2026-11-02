package dev.loong.engine.rules

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.bool
import dev.loong.engine.ast.int
import dev.loong.engine.ast.str
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.resolve.Resolver
import dev.loong.engine.text.Parser
import dev.loong.engine.types.TypeChecker
import dev.loong.engine.workspace.Workspace
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 字段 schema（FieldSchema）单测——外部 agent 实战踩过的两类错位：
 * - Path 写了 name/full_name 而非 written：以前被静默忽略，解析报 "unknown name ''"；
 * - Literal.value 传 JSON 数字：以前存成 Int，类型检查放行、codegen 在模块根报 E_INTERNAL。
 * 现在：写入路径点名拒绝；门禁在节点上报 E_INVARIANT_VIOLATION；数字文本不合法报 E_INVALID_NUMBER。
 */
class FieldSchemaTest {

    @Test
    fun `schema covers every kind and matches spec required columns`() {
        assertEquals(Kind.entries.toSet(), FieldSchema.SPECS.keys)
        // 高频字段的必填/派生标记与 ast-schema-v1 §4 一致
        assertTrue(FieldSchema.specOf(Kind.Path, "written")!!.required)
        assertTrue(FieldSchema.specOf(Kind.Path, "full_name")!!.derived)
        assertTrue(FieldSchema.specOf(Kind.Path, "resolved_id")!!.derived)
        assertTrue(FieldSchema.specOf(Kind.Test, "target_name")!!.required)
        assertTrue(FieldSchema.specOf(Kind.Test, "target_id")!!.derived)
        assertTrue(FieldSchema.specOf(Kind.Literal, "lit_kind")!!.required)
        assertEquals(FieldSchema.Type.Bool, FieldSchema.specOf(Kind.Let, "mutable")!!.type)
        assertEquals(FieldSchema.Type.Str, FieldSchema.specOf(Kind.ExternalMethod, "jvm_name")!!.type)
        // 每个 kind 的描述可用于 list_ast_kinds（无字段的 kind 给 "—"）
        assertEquals("—", FieldSchema.describe(Kind.Block))
        assertTrue("written" in FieldSchema.describe(Kind.Path))
    }

    @Test
    fun `unknown field names are reported so the agent sees what is accepted`() {
        assertEquals(listOf("name"), FieldSchema.unknownFields(Kind.Path, listOf("name")))
        assertEquals(listOf("path"), FieldSchema.unknownFields(Kind.Path, listOf("path", "written")))
        assertEquals(emptyList(), FieldSchema.unknownFields(Kind.Path, listOf("written", "full_name", "resolved_id")))
        assertEquals(listOf("target"), FieldSchema.unknownFields(Kind.Test, listOf("name", "target")))
    }

    @Test
    fun `required fields and value types are enforced with targeted hints`() {
        // Path 缺 written（哪怕带了派生字段也不算）
        val p = FieldSchema.problemsOf(Kind.Path, mapOf("full_name" to str("a")))
        assertEquals(1, p.size, p.toString())
        assertTrue("requires field 'written'" in p[0] && "full_name" in p[0], p[0])

        // Literal.value 存成 Int：类型违例，提示写成 "7"
        val lit = FieldSchema.problemsOf(Kind.Literal, mapOf("lit_kind" to str("int"), "value" to int(2)))
        assertEquals(1, lit.size, lit.toString())
        assertTrue("Literal.value must be str" in lit[0] && "\"7\"" in lit[0], lit[0])

        // 标量 Literal 缺 value；array/map 不要求
        assertTrue(FieldSchema.problemsOf(Kind.Literal, mapOf("lit_kind" to str("int"))).any { "requires field 'value'" in it })
        assertEquals(emptyList(), FieldSchema.problemsOf(Kind.Literal, mapOf("lit_kind" to str("array"))))
        assertEquals(emptyList(), FieldSchema.problemsOf(Kind.Literal, mapOf("lit_kind" to str("map"))))

        // Let 的 bool 字段可缺省；给错类型则报
        assertEquals(emptyList(), FieldSchema.problemsOf(Kind.Let, mapOf("name" to str("x"), "type" to str("Int"))))
        assertTrue(
            FieldSchema.problemsOf(Kind.Let, mapOf("name" to str("x"), "type" to str("Int"), "mutable" to str("true")))
                .any { "Let.mutable must be bool" in it },
        )
        assertEquals(emptyList(), FieldSchema.problemsOf(Kind.Let, mapOf("name" to str("x"), "type" to str("Int"), "mutable" to bool(true))))

        // 无字段的 kind 永远合法
        assertEquals(emptyList(), FieldSchema.problemsOf(Kind.Block, emptyMap()))
    }

    @Test
    fun `recompute gate names the literal node instead of E_INTERNAL at the module root`() {
        val ws = Workspace()
        ws.load(
            """
            module demo.lit
            /// Adds one.
            public function inc(x: Int) -> Int {
                return x + 1
            }
            test "inc" target: demo.lit.inc {
                let r: Int = inc(41)
            }
            """.trimIndent(),
        )
        val table = ws.tableOf("demo.lit/v1")
        val literal = table.find(Kind.Literal).first { (table[it].fields["value"] as? FieldValue.Str)?.value == "1" }

        // 复现外部 agent 的写法：value 落成 Int（旧行为：类型检查放行，codegen NumberFormatException 报在根）
        val out = ws.edit("demo.lit/v1") { t ->
            t.replace(literal, fields = mapOf("lit_kind" to str("int"), "value" to int(1)))
        }
        val codes = out.diagnostics.map { it.code to it.nodeId }
        assertTrue(
            DiagCodes.E_INVARIANT_VIOLATION to literal in codes,
            "expected E_INVARIANT_VIOLATION on the literal node $literal but got $codes",
        )
        assertTrue(out.diagnostics.none { it.code == "E_INTERNAL" }, out.diagnostics.toString())

        // Path 缺 written 同样点名该 Path
        val path = table.find(Kind.Path).first { (table[it].fields["written"] as? FieldValue.Str)?.value == "x" }
        val out2 = ws.edit("demo.lit/v1") { t ->
            t.replace(literal, fields = mapOf("lit_kind" to str("int"), "value" to str("1")))
            t.replace(path, fields = mapOf("full_name" to str("x")))
        }
        assertTrue(
            out2.diagnostics.any { it.code == DiagCodes.E_INVARIANT_VIOLATION && it.nodeId == path && "written" in it.message },
            out2.diagnostics.toString(),
        )
    }

    @Test
    fun `type checker rejects unparseable number text on the literal node`() {
        val r = Parser(
            """
            module demo.num
            /// Doubles.
            public function twice(x: Int) -> Int {
                return x * 2
            }
            test "twice" target: demo.num.twice {
                let r: Int = twice(21)
            }
            """.trimIndent(),
        ).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString { it.message })
        val t = r.table
        val two = t.find(Kind.Literal).first { (t[it].fields["value"] as? FieldValue.Str)?.value == "2" }
        t.replace(two, fields = mapOf("lit_kind" to str("int"), "value" to str("two")))

        Resolver(t).resolve(r.root)
        val diags = TypeChecker(t).check(r.root)
        val hit = diags.firstOrNull { it.code == DiagCodes.E_INVALID_NUMBER }
        assertTrue(hit != null && hit.nodeId == two, diags.toString())

        // 合法形态全部放行：十进制、0x 十六进制、浮点
        for ((kind, text) in listOf("int" to "42", "int" to "0x2A", "float" to "0.5", "float" to "1e3")) {
            t.replace(two, fields = mapOf("lit_kind" to str(kind), "value" to str(text)))
            val d = TypeChecker(t).check(r.root)
            assertTrue(d.none { it.code == DiagCodes.E_INVALID_NUMBER }, "$kind '$text': $d")
        }
    }
}
