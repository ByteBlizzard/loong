package dev.loong.engine.text

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 往返测试：render ∘ parse 幂等（plan.md M1 第 4 步）。
 *
 * 性质一（文本级）：t2 = render(parse(t))，则 render(parse(t2)) == t2。
 * 性质二（结构级）：parse(t2) 与 parse(t) 的节点结构（kind + 字段，忽略 ID）完全一致。
 */
class RoundtripTest {

    private fun render(src: String): String {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString())
        return Renderer(r.table).render(r.root)
    }

    private fun structureOf(src: String): List<Pair<Kind, List<Pair<String, String>>>> {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString())
        return structure(r.table, r.root)
    }

    private fun structure(table: NodeTable, root: NodeId): List<Pair<Kind, List<Pair<String, String>>>> {
        val out = ArrayList<Pair<Kind, List<Pair<String, String>>>>()
        fun walk(id: NodeId) {
            val n = table[id]
            val fields = n.fields.entries.sortedBy { it.key }.map { (k, v) -> k to v.toString() }
            out += n.kind to fields
            n.kids.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    @Test
    fun `sample roundtrips at text and structure level`() {
        val t1 = ParserTest.SAMPLE
        val t2 = render(t1)
        val t3 = render(t2)
        assertEquals(t2, t3, "render(parse(t2)) must equal t2")
        assertEquals(structureOf(t1), structureOf(t2))
    }

    @Test
    fun `minimal module roundtrips`() {
        val t1 = "module org.example.empty"
        val t2 = render(t1)
        assertEquals("module org.example.empty/v1\n", t2)
        assertEquals(t2, render(t2))
    }

    @Test
    fun `grouped depends are canonicalized to one per line`() {
        val t1 = """
            module m
            depends a.b, c.d >= 2.0.0
        """.trimIndent()
        val t2 = render(t1)
        val expected = "module m/v1\ndepends a.b/v1\ndepends c.d/v1 >= 2.0.0\n"
        assertEquals(expected, t2)
        assertEquals(t2, render(t2))
    }

    @Test
    fun `expression shapes survive roundtrip`() {
        val t1 = """
            module m
            function f(a: Int, b: Bool, c: String) -> Bool {
                let x: Bool = !(a == 1) && b || a >= 2 && a <= 3 && a != 4
                let y: Int = -a + 2 * 3 % 4
                let arr: Array<Int> = [1, 2, 3]
                let mp: Map<Int, String> = map { 1: "one", 2: "two" }
                let call1: Int = g(a: 1, b: 2)
                let call2: Int = g(1, 2)
                let idx: Int = arr[a + 1].depth
                let h: Handle<Int> = spawn calc(x: a)
                let v: Int = join h
                let s: String = "raw \n escape"
                return x && y > 0 && v == 1
            }
        """.trimIndent()
        val t2 = render(t1)
        assertEquals(t2, render(t2))
        assertEquals(structureOf(t1), structureOf(t2))
        // 关键形态仍在
        assertTrue(t2.contains("map { 1: \"one\", 2: \"two\" }"))
        assertTrue(t2.contains("arr[a + 1].depth"))
        assertTrue(t2.contains("spawn calc(x: a)"))
        assertTrue(t2.contains("join h"))
        assertTrue(t2.contains("\"raw \\n escape\""))
    }

    @Test
    fun `if-let match and blocks roundtrip`() {
        val t1 = """
            module m
            function f(c: Color) -> Int {
                let a: Int = if true { 1 } else { 2 }
                if let Color.Blue(v) = c {
                    return v
                } else {
                    return 0
                }
                match c {
                    Color.Red => 1
                    Color.Green => { return 2 }
                    _ => { }
                }
                return a
            }
        """.trimIndent()
        val t2 = render(t1)
        assertEquals(t2, render(t2))
        assertEquals(structureOf(t1), structureOf(t2))
        // 表达式体 match 臂保持表达式体
        assertTrue(t2.contains("Color.Red => 1"))
    }

    @Test
    fun `all declaration kinds roundtrip`() {
        val t1 = """
            module org.example.all

            /// Doc for struct.
            public struct Box<T> where T: Val {
                value: T
            }

            /// Doc for interface.
            public interface Val {
                function ok(self: Val) -> Bool;
            }

            impl Val<Box<Int>> for Box<Int> {
                /// Impl doc.
                function ok(self: Box<Int>) -> Bool {
                    return true
                }
            }

            /// Doc for enum.
            public enum Op {
                Add(Int)
                Empty
            }

            /// Doc for const.
            public const K: Float = 3.5

            /// Doc for test.
            test "k test" target: org.example.all.f {
                let x: Float = K
            }

            external java "java.lang.System" {
                /// Doc for method.
                static function println(x: String) -> Unit
            }
        """.trimIndent()
        val t2 = render(t1)
        assertEquals(t2, render(t2))
        assertEquals(structureOf(t1), structureOf(t2))
        assertTrue(t2.contains("function ok(self: Val) -> Bool;"))
        assertTrue(t2.contains("static function println(x: String) -> Unit"))
    }

    @Test
    fun `field value forms are preserved`() {
        val t1 = """
            module m
            function f() -> Unit {
                let a: Int = 0x1F
                let b: Float = 1e9
                let c: Char = 'x'
                let d: Char = '\n'
                let e: Bool = true
                let g: Bool = false
            }
        """.trimIndent()
        val t2 = render(t1)
        assertEquals(t2, render(t2))
        assertEquals(structureOf(t1), structureOf(t2))
        assertTrue(t2.contains("0x1F"))
        assertTrue(t2.contains("1e9"))
        assertTrue(t2.contains("'\\n'"))
    }
}
