package dev.loong.engine.resolve

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.text.Parser
import dev.loong.engine.text.Renderer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResolverTest {

    private class Resolved(
        val table: dev.loong.engine.ast.NodeTable,
        val root: NodeId,
        val diags: List<dev.loong.engine.diagnostics.Diagnostic>,
    )

    private fun resolve(src: String): Resolved {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), "parse diagnostics: " + r.diagnostics.joinToString { it.message })
        val diags = Resolver(r.table).resolve(r.root)
        return Resolved(r.table, r.root, diags)
    }

    private fun fs(t: dev.loong.engine.ast.NodeTable, id: NodeId, key: String): String =
        (t[id].fields[key] as? FieldValue.Str)?.value ?: ""

    private fun refOf(t: dev.loong.engine.ast.NodeTable, id: NodeId, key: String): NodeId? =
        (t[id].fields[key] as? FieldValue.Ref)?.id

    private fun kinds(t: dev.loong.engine.ast.NodeTable, ids: List<NodeId>) = ids.map { t[it].kind }

    @Test
    fun `dotted path splits into field access chain`() {
        val r = resolve(
            """
            module m
            function f(self: Point) -> Int {
                return self.x + self.y
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val fn = r.table.find(Kind.Function).single()
        val params = r.table[fn].kids.filter { r.table[it].kind == Kind.Param }
        val ret = r.table.find(Kind.Return).single()
        val add = r.table[ret].kids[0] // Binary +
        val lhs = r.table[add].kids[0] // FieldAccess(x)
        assertEquals(Kind.FieldAccess, r.table[lhs].kind)
        assertEquals("x", fs(r.table, lhs, "field"))
        val head = r.table[lhs].kids[0] // Path(self)
        assertEquals(Kind.Path, r.table[head].kind)
        assertEquals("self", fs(r.table, head, "full_name"))
        assertEquals(params[0], refOf(r.table, head, "resolved_id"))
        // 解析后渲染不变（拆分对渲染器透明）
        val text = Renderer(r.table).render(r.root)
        assertTrue(text.contains("return self.x + self.y"))
        assertTrue(r.table.checkInvariants().isEmpty())
    }

    @Test
    fun `fully qualified reference stays single path`() {
        val r = resolve(
            """
            module org.example.m
            public const MAX: Int = 100
            function f() -> Int {
                return org.example.m.MAX + MAX
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val cnst = r.table.find(Kind.Const).single()
        val paths = r.table.find(Kind.Path)
        val qualified = paths.single { fs(r.table, it, "written") == "org.example.m.MAX" }
        assertEquals("org.example.m.MAX", fs(r.table, qualified, "full_name"))
        assertEquals(cnst, refOf(r.table, qualified, "resolved_id"))
        val shortUse = paths.single { fs(r.table, it, "written") == "MAX" }
        assertEquals(cnst, refOf(r.table, shortUse, "resolved_id"))
        // 没有因拆分产生 FieldAccess
        assertTrue(r.table.find(Kind.FieldAccess).isEmpty())
    }

    @Test
    fun `enum variants and core builtins resolve`() {
        val r = resolve(
            """
            module m
            public enum Color {
                Red
                Blue
            }
            function f(c: Color) -> Color {
                let a: Color = Color.Red
                let b: Int = unwrap(o: Option.Some(1))
                return a
            }
            function unwrap(o: Option<Int>) -> Int {
                return 0
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val blue = r.table.find(Kind.Variant).single { fs(r.table, it, "name") == "Red" }
        val colorRed = r.table.find(Kind.Path).single { fs(r.table, it, "written") == "Color.Red" }
        assertEquals(blue, refOf(r.table, colorRed, "resolved_id"))
        // 内建：全名填充、无节点引用
        val some = r.table.find(Kind.Path).single { fs(r.table, it, "written") == "Option.Some" }
        assertEquals("loong.core.Option.Some", fs(r.table, some, "full_name"))
        assertNull(refOf(r.table, some, "resolved_id"))
    }

    @Test
    fun `bare enum name in expression position is an error`() {
        val r = resolve(
            """
            module m
            public enum Color { Red }
            function f() -> Int {
                return Color
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.any { it.code == DiagCodes.E_UNKNOWN_NAME })
    }

    @Test
    fun `impl functions resolve by short and qualified name`() {
        val r = resolve(
            """
            module m
            public interface Shape<S> {
                function area(self: S) -> Int;
            }
            public struct Circle {
                r: Int
            }
            impl Shape<Circle> for Circle {
                function area(self: Circle) -> Int {
                    return self.r
                }
            }
            function use(c: Circle) -> Int {
                let a: Int = area(self: c)
                let b: Int = Shape.area(self: c)
                return a + b
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val implFn = r.table.find(Kind.Function).single { fs(r.table, it, "name") == "area" }
        val short = r.table.find(Kind.Path).single { fs(r.table, it, "written") == "area" }
        val qualified = r.table.find(Kind.Path).single { fs(r.table, it, "written") == "Shape.area" }
        assertEquals(implFn, refOf(r.table, short, "resolved_id"))
        assertEquals("m.Shape.area", fs(r.table, qualified, "full_name"))
        assertEquals(implFn, refOf(r.table, qualified, "resolved_id"))
    }

    @Test
    fun `ambiguous short name is reported with candidates`() {
        val r = resolve(
            """
            module m
            public interface Shape<S> {
                function area(self: S) -> Int;
            }
            public interface Volume<S> {
                function area(self: S) -> Int;
            }
            public struct Cube { s: Int }
            impl Shape<Cube> for Cube {
                function area(self: Cube) -> Int { return self.s }
            }
            impl Volume<Cube> for Cube {
                function area(self: Cube) -> Int { return self.s }
            }
            function use(c: Cube) -> Int {
                return area(self: c)
            }
            """.trimIndent(),
        )
        val ambiguous = r.diags.filter { it.code == DiagCodes.E_AMBIGUOUS_NAME }
        assertEquals(1, ambiguous.size)
        assertTrue("m.Shape.area" in ambiguous[0].message && "m.Volume.area" in ambiguous[0].message)
        // 限定名不受影响
        assertTrue(r.diags.none { it.code == DiagCodes.E_UNKNOWN_NAME })
    }

    @Test
    fun `duplicate declarations are rejected`() {
        val r = resolve(
            """
            module m
            function f() -> Int { return 0 }
            function f() -> Int { return 1 }
            """.trimIndent(),
        )
        assertTrue(r.diags.any { it.code == DiagCodes.E_DUPLICATE_DECL })
        val dupBinding = resolve(
            """
            module m
            function g() -> Int {
                let x: Int = 1
                let x: Int = 2
                return x
            }
            """.trimIndent(),
        )
        assertTrue(dupBinding.diags.any { it.code == DiagCodes.E_DUPLICATE_DECL })
    }

    @Test
    fun `unknown names are reported`() {
        val r = resolve(
            """
            module m
            function f() -> Int {
                return missing
            }
            function g() -> Int {
                return a.b.c
            }
            """.trimIndent(),
        )
        assertEquals(2, r.diags.filter { it.code == DiagCodes.E_UNKNOWN_NAME }.size)
    }

    @Test
    fun `test targets resolve to function nodes`() {
        val r = resolve(
            """
            module org.example.m
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }
            test "qualified" target: org.example.m.add {
                let r: Int = add(x: 1, y: 2)
            }
            test "short" target: add {
                let r: Int = add(x: 1, y: 2)
            }
            test "bad" target: org.example.m.nope {
                let r: Int = 1
            }
            """.trimIndent(),
        )
        val fn = r.table.find(Kind.Function).single()
        val tests = r.table.find(Kind.Test)
        assertEquals(3, tests.size)
        assertEquals(fn, refOf(r.table, tests[0], "target_id"))
        assertEquals(fn, refOf(r.table, tests[1], "target_id"))
        assertNull(refOf(r.table, tests[2], "target_id"))
        assertTrue(r.diags.any { it.code == DiagCodes.E_UNRESOLVED_TARGET })
        // test 体内的调用也被解析
        assertTrue(r.diags.none { it.code == DiagCodes.E_UNKNOWN_NAME })
    }

    @Test
    fun `shadowing resolves to innermost binding`() {
        val r = resolve(
            """
            module m
            function f(x: Int) -> Int {
                let x: Int = x + 1
                return x
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val fn = r.table.find(Kind.Function).single()
        val param = r.table[fn].kids.first { r.table[it].kind == Kind.Param }
        val letNode = r.table.find(Kind.Let).single()
        // let 初始化里的 x → Param；return 的 x → Let
        val binary = r.table[letNode].kids[0] // Binary(+, x, 1)
        val initPath = r.table[binary].kids[0]
        assertEquals(Kind.Path, r.table[initPath].kind)
        assertEquals(param, refOf(r.table, initPath, "resolved_id"))
        val returnNode = r.table.find(Kind.Return).single()
        val returnPath = r.table[returnNode].kids[0]
        assertEquals(letNode, refOf(r.table, returnPath, "resolved_id"))
    }

    @Test
    fun `control flow bindings resolve to their nodes`() {
        val r = resolve(
            """
            module m
            public enum Color { Red Blue }
            function f(c: Color, items: Array<Int>) -> Int {
                if let Color.Blue(v) = c {
                    return v
                }
                for item: Int in items {
                    return item
                }
                match c {
                    Color.Blue(b) => { return b }
                    _ => { return 0 }
                }
                return 0
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val ifLet = r.table.find(Kind.IfLet).single()
        val forNode = r.table.find(Kind.For).single()
        val arm = r.table.find(Kind.MatchArm).single { (r.table[it].fields["binding"] as? FieldValue.Str)?.value == "b" }
        fun resolvedParamOf(ret: NodeId): NodeId? {
            val p = r.table[ret].kids[0]
            return refOf(r.table, p, "resolved_id")
        }
        val returns = r.table.find(Kind.Return)
        val targets = returns.map { resolvedParamOf(it) }
        assertTrue(ifLet in targets)
        assertTrue(forNode in targets)
        assertTrue(arm in targets)
    }

    @Test
    fun `external methods resolve by short name`() {
        val r = resolve(
            """
            module m
            external java "java.lang.Math" {
                static function abs(x: Int) -> Int
            }
            function f(a: Int) -> Int {
                return abs(a)
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        val method = r.table.find(Kind.ExternalMethod).single()
        val call = r.table.find(Kind.Path).single { fs(r.table, it, "written") == "abs" }
        assertEquals(method, refOf(r.table, call, "resolved_id"))
    }

    @Test
    fun `invariants hold after structural rewriting`() {
        val r = resolve(
            """
            module m
            public struct Point {
                x: Int
                y: Int
            }
            function f(p: Point) -> Int {
                let a: Int = p.x
                let b: Int = p.y
                var t: Int = a
                t = b
                return t
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.isEmpty(), r.diags.joinToString { it.message })
        assertEquals(2, r.table.find(Kind.FieldAccess).size)
        assertTrue(r.table.checkInvariants().isEmpty(), r.table.checkInvariants().joinToString())
        assertEquals(r.root, r.table.roots().single())
    }
}
