package dev.loong.engine.text

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 节点级渲染（Web UI 原始 AST 树「导出成代码」）：
 * 任意节点子树都能渲染为对应的源代码表示，模块根与整模块 render 等价。
 */
class RenderNodeTest {

    private val source = """
        module demo.render

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            return x + y
        }

        /// Val.
        public interface Val {
            function ok(self: Val) -> Bool;
        }

        /// Op.
        public enum Op {
            Add(Int)
            Empty
        }

        external java "java.lang.System" {
            static function println(x: String) -> Unit
        }

        test "add" target: demo.render.add {
            let r: Int = add(x: 1, y: 2)
            match r {
                1 => 2
                _ => { }
            }
        }
    """.trimIndent()

    private class Parsed(val table: dev.loong.engine.ast.NodeTable, val root: NodeId, val renderer: Renderer) {
        fun first(kind: Kind, name: String? = null): NodeId = table.find(kind) {
            name == null || (it.fields["name"] as? FieldValue.Str)?.value == name
        }.firstOrNull() ?: error("no $kind named $name")
        fun node(id: NodeId) = renderer.renderNode(id)
    }

    private fun parse(src: String = source): Parsed {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString())
        return Parsed(r.table, r.root, Renderer(r.table))
    }

    @Test
    fun `module node renders like whole module`() {
        val p = parse()
        assertEquals(p.renderer.render(p.root), p.node(p.root))
    }

    @Test
    fun `declaration nodes render with doc and signature`() {
        val p = parse()
        val fn = p.node(p.first(Kind.Function, "add"))
        assertTrue(fn.contains("/// Adds."), fn)
        assertTrue(fn.contains("public function add(x: Int, y: Int) -> Int {"), fn)
        assertTrue(fn.contains("return x + y"), fn)

        val iface = p.node(p.first(Kind.Interface, "Val"))
        assertTrue(iface.contains("interface Val {"), iface)
        assertTrue(iface.contains("function ok(self: Val) -> Bool;"), iface)

        val en = p.node(p.first(Kind.Enum, "Op"))
        assertTrue(en.contains("enum Op {") && en.contains("Add(Int)") && en.contains("Empty"), en)

        val ext = p.node(p.first(Kind.ExternalClass))
        assertTrue(ext.contains("external java \"java.lang.System\" {"), ext)
        assertTrue(ext.contains("static function println(x: String) -> Unit"), ext)
    }

    @Test
    fun `member and clause nodes render standalone`() {
        val p = parse()
        assertEquals("x: Int", p.node(p.first(Kind.Param, "x")))
        assertEquals("function ok(self: Val) -> Bool;", p.node(p.first(Kind.MethodSig, "ok")))
        assertEquals("static function println(x: String) -> Unit", p.node(p.first(Kind.ExternalMethod, "println")))
        assertEquals("Add(Int)", p.node(p.first(Kind.Variant, "Add")))
        assertEquals("/// Adds.", p.node(p.table.ids.first { p.table[it].kind == Kind.DocComment }))
    }

    @Test
    fun `statement and expression nodes render`() {
        val p = parse()
        assertEquals("let r: Int = add(x: 1, y: 2)", p.node(p.first(Kind.Let, "r")))
        assertEquals("return x + y", p.node(p.first(Kind.Return)))
        assertTrue(p.node(p.first(Kind.Binary)).contains("x + y"))
        val block = p.node(p.first(Kind.Block))
        assertTrue(block.startsWith("{\n") && block.contains("return x + y"), block)
        val arm = p.node(p.first(Kind.MatchArm))
        assertEquals("1 => 2", arm.trim())
    }
}
