package dev.loong.engine.text

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.diagnostics.DiagCodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ParserTest {

    private fun parse(src: String): Parser.Result = Parser(src).parse()

    private fun f(id: dev.loong.engine.ast.NodeId, table: dev.loong.engine.ast.NodeTable, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""

    @Test
    fun `module header normalized to v1 and depends parsed`() {
        val r = parse(
            """
            module org.example.demo
            depends org.example.lib >= 1.2.0
            depends workspace: org.example.sibling
            """.trimIndent(),
        )
        assertEquals("org.example.demo/v1", f(r.root, r.table, "name"))
        assertEquals(0, r.diagnostics.size)
        val deps = r.table[r.root].kids.map { r.table[it] }
        assertEquals(2, deps.size)
        assertEquals("org.example.lib/v1", deps[0].fields["module_name"]?.let { (it as FieldValue.Str).value })
        assertEquals(">=1.2.0", deps[0].fields["constraint"]?.let { (it as FieldValue.Str).value })
        assertEquals(
            "workspace",
            deps[1].fields["source"]?.let { (it as FieldValue.Str).value },
        )
    }

    @Test
    fun `module with explicit version`() {
        val r = parse("module org.example.http/v2")
        assertEquals("org.example.http/v2", f(r.root, r.table, "name"))
    }

    @Test
    fun `function with doc params and body`() {
        val r = parse(
            """
            module m
            /// Adds two ints.
            public function add(x: Int, y: Int) -> Int {
                let sum: Int = x + y
                return sum
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size)
        val fn = r.table.find(Kind.Function).single()
        assertEquals("add", f(fn, r.table, "name"))
        assertEquals("public", f(fn, r.table, "visibility"))
        assertEquals("Int", f(fn, r.table, "return_type"))
        val kids = r.table[fn].kids.map { r.table[it].kind }
        assertEquals(listOf(Kind.DocComment, Kind.Param, Kind.Param, Kind.Block), kids)
        val doc = r.table[fn].kids[0]
        assertEquals("Adds two ints.", f(doc, r.table, "text"))
        val block = r.table[fn].kids.last()
        assertEquals(listOf(Kind.Let, Kind.Return), r.table[block].kids.map { r.table[it].kind })
    }

    @Test
    fun `generic function with where constraint`() {
        val r = parse(
            """
            module m
            function pick<T>(a: T, b: Bool) -> T where T: Shape<Item> {
                if b { return a } else { return a }
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size)
        val fn = r.table.find(Kind.Function).single()
        val tp = r.table.find(Kind.TypeParam).single()
        assertEquals("T", f(tp, r.table, "name"))
        assertEquals("Shape<Item>", f(tp, r.table, "constraint"))
        assertTrue(fn in r.table.ancestors(tp))
    }

    @Test
    fun `struct with documented field and interface impl enum`() {
        val r = parse(
            """
            module m
            /// Point.
            public struct Point {
                x: Int
                /// y
                y: Int
            }
            /// Shape.
            public interface Shape<S> {
                function area(self: S) -> Int;
            }
            impl Shape<Point> for Point {
                /// Area.
                function area(self: Point) -> Int {
                    return self.x * self.x
                }
            }
            /// Color.
            public enum Color {
                Red
                Blue(Int)
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        val st = r.table.find(Kind.Struct).single()
        assertEquals(listOf(Kind.DocComment, Kind.Field, Kind.Field), r.table[st].kids.map { r.table[it].kind })
        val y = r.table[st].kids.last() // 第二个字段带 doc
        assertEquals(listOf(Kind.DocComment), r.table[y].kids.map { r.table[it].kind })

        val itf = r.table.find(Kind.Interface).single()
        assertEquals(1, r.table.find(Kind.MethodSig).size)
        assertEquals("S", f(r.table.find(Kind.TypeParam).single(), r.table, "name"))

        val impl = r.table.find(Kind.Impl).single()
        assertEquals("Shape<Point>", f(impl, r.table, "interface"))
        assertEquals("Point", f(impl, r.table, "struct"))
        assertEquals(1, r.table.find(Kind.Function).size) // impl 函数也是 Function

        val variants = r.table.find(Kind.Variant)
        assertEquals(listOf("Red", "Blue"), variants.map { f(it, r.table, "name") })
        assertEquals("Int", f(variants[1], r.table, "payload_type"))
    }

    @Test
    fun `statements full coverage`() {
        val r = parse(
            """
            module m
            function demo(items: Array<Int>, c: Color) -> Int {
                var total: Int = 0
                total = items[0]
                while total > 0 {
                    total = total - 1
                }
                for it: Int in items {
                    total = total + it
                }
                if total == 0 {
                    return 0
                }
                if let Color.Blue(v) = c {
                    total = v
                } else {
                    total = 1
                }
                match c {
                    Color.Red => { total = 2 }
                    Color.Green => total + 1
                    _ => { }
                }
                break
                continue
                return total
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        val block = r.table.find(Kind.Block).first()
        val kinds = r.table[block].kids.map { r.table[it].kind }
        // if/if-let/match 是表达式，语句位置包装为 ExprStmt
        assertEquals(
            listOf(
                Kind.Let, Kind.Assign, Kind.While, Kind.For, Kind.ExprStmt,
                Kind.ExprStmt, Kind.ExprStmt, Kind.Break, Kind.Continue, Kind.Return,
            ),
            kinds,
        )
        val inner = r.table[block].kids.drop(4).take(3).map { r.table[r.table[it].kids[0]].kind }
        assertEquals(listOf(Kind.If, Kind.IfLet, Kind.Match), inner)
        val match = r.table.find(Kind.Match).single()
        val arms = r.table[match].kids.drop(1)
        assertEquals(3, arms.size)
        assertEquals("enum_ctor", f(arms[0], r.table, "pattern_kind"))
        assertEquals("Color.Red", f(arms[0], r.table, "path"))
        assertEquals("wildcard", f(arms[2], r.table, "pattern_kind"))
        // 第二臂是表达式体（arm 的 kid 直接是表达式节点）
        assertEquals(Kind.Binary, r.table[r.table[arms[1]].kids[0]].kind)
    }

    @Test
    fun `expressions operators literals and calls`() {
        val r = parse(
            """
            module m
            function e(a: Int, b: Bool, s: String) -> Bool {
                let x: Bool = !(a == 1) && b || false
                let y: Int = -a + 2 * 3 % 4
                let arr: Array<Int> = [1, 2, 3]
                let mp: Map<Int, String> = map { 1: "one" }
                let c: Bool = a >= 1 && a <= 9 && a != 5
                let named: Int = f(a: 1, b: 2)
                let plain: Int = g(1, 2)
                let z: Int = arr[0].x
                let h: Handle<Int> = spawn calc(x: 1)
                let v: Int = join h
                let t: Bool = a == 1
                return x && y > 0 && t
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        // 具名实参与位置实参
        val args = r.table.find(Kind.Arg).map { r.table[it] }
        assertTrue(args.any { (it.fields["name"] as? FieldValue.Str)?.value == "a" && it.kids.size == 1 })
        assertTrue(args.any { !it.fields.containsKey("name") })
        // 点链 FieldAccess 仅出现在非 Path 接收者上（arr[0].x）
        val fa = r.table.find(Kind.FieldAccess)
        assertEquals(1, fa.size)
        assertEquals("x", f(fa[0], r.table, "field"))
        assertEquals(Kind.Index, r.table[r.table[fa[0]].kids[0]].kind)
        // arr[0] 是 Index
        assertEquals(1, r.table.find(Kind.Index).size)
        // spawn 包住 Call；join 单独存在
        val spawn = r.table.find(Kind.Spawn).single()
        assertEquals(Kind.Call, r.table[r.table[spawn].kids[0]].kind)
        assertEquals(1, r.table.find(Kind.Join).size)
        // 数组与 map 字面量
        val lits = r.table.find(Kind.Literal).map { f(it, r.table, "lit_kind") }
        assertTrue("array" in lits && "map" in lits)
        assertEquals(1, r.table.find(Kind.MapEntry).size)
    }

    @Test
    fun `path chains merge into single path node`() {
        val r = parse(
            """
            module m
            function e(self: Point) -> Int {
                return self.x.y + org.example.f(z: 1)
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size)
        val paths = r.table.find(Kind.Path).map { f(it, r.table, "written") }
        assertTrue("self.x.y" in paths) // 点链最大吞噬
        assertTrue("org.example.f" in paths) // 全限定调用名
    }

    @Test
    fun `nested generics split shr correctly`() {
        val r = parse(
            """
            module m
            function e(x: Array<Array<Int>>, f: (Int, Bool) -> String) -> Map<Int, Array<Int>> {
                return map { 1: [1] }
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        val params = r.table.find(Kind.Param).map { f(it, r.table, "type") }
        assertTrue("Array<Array<Int>>" in params)
        assertTrue("(Int, Bool) -> String" in params)
    }

    @Test
    fun `test and external declarations`() {
        val r = parse(
            """
            module org.example.math
            /// Adds.
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }
            test "add basics" target: org.example.math.add {
                let r: Int = add(x: 1, y: 2)
            }
            external java "java.lang.Math" {
                static function abs(x: Int) -> Int
                constructor function new(x: Int) -> Int
            }
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        val test = r.table.find(Kind.Test).single()
        assertEquals("add basics", f(test, r.table, "name"))
        assertEquals("org.example.math.add", f(test, r.table, "target_name"))
        val ext = r.table.find(Kind.ExternalClass).single()
        assertEquals("java.lang.Math", f(ext, r.table, "class_fqn"))
        val methods = r.table.find(Kind.ExternalMethod)
        assertEquals(listOf("static", "constructor"), methods.map { f(it, r.table, "call_kind") })
    }

    @Test
    fun `const declaration`() {
        val r = parse(
            """
            module m
            /// Max.
            public const MAX: Int = 100
            """.trimIndent(),
        )
        assertEquals(0, r.diagnostics.size)
        val c = r.table.find(Kind.Const).single()
        assertEquals("MAX", f(c, r.table, "name"))
        assertEquals("public", f(c, r.table, "visibility"))
        val value = r.table[c].kids.lastOrNull { r.table[it].kind == Kind.Literal }
        assertEquals("100", value?.let { f(it, r.table, "value") })
    }

    @Test
    fun `errors are reported without crashing`() {
        val missing = parse("function f() -> Int { return 0 }")
        assertTrue(missing.diagnostics.any { it.code == DiagCodes.E_MISSING_MODULE_DECL })

        val garbage = parse("module m\n!!! garbage $$$")
        assertTrue(garbage.diagnostics.isNotEmpty())

        val unterminated = parse("module m\nfunction f() -> Int { let s: String = \"abc }")
        assertTrue(unterminated.diagnostics.any { it.code == DiagCodes.E_UNTERMINATED_STRING })
    }

    @Test
    fun `table invariants hold after parsing`() {
        val r = parse(SAMPLE)
        assertEquals(0, r.diagnostics.size, r.diagnostics.joinToString())
        assertTrue(r.table.checkInvariants().isEmpty(), r.table.checkInvariants().joinToString())
        assertEquals(r.root, r.table.roots().single())
    }

    companion object {
        val SAMPLE = """
            module org.example.demo

            depends org.example.lib >= 1.2.0
            depends workspace: org.example.sibling

            /// Adds two integers.
            public function add(x: Int, y: Int) -> Int {
                let sum: Int = x + y
                return sum
            }

            function first<T>(items: Array<T>) -> T where T: Shape<Item> {
                return items[0]
            }

            /// A 2-D point.
            public struct Point {
                x: Int
                /// y coordinate
                y: Int
            }

            /// Shape contract.
            public interface Shape<S> {
                function area(self: S) -> Int;
            }

            impl Shape<Point> for Point {
                /// Area of a point.
                function area(self: Point) -> Int {
                    return self.x * self.x + self.y * self.y
                }
            }

            /// Colors.
            public enum Color {
                Red
                Green
                Blue
            }

            /// Max attempts.
            public const MAX: Int = 100

            function demo(c: Color, p: Point, items: Array<Int>) -> String {
                let a: Int = add(x: 1, y: 2)
                let m: Int = if a > 2 { a } else { 0 }
                var total: Int = 0
                total = m + MAX
                while total > 0 {
                    total = total - 1
                }
                for item: Int in items {
                    total = total + item
                }
                match c {
                    Color.Red => { return "red" }
                    Color.Green => { total = total + 1 }
                    _ => { }
                }
                if let Color.Blue = c {
                    total = 1
                } else {
                    total = 2
                }
                let first: Int = items[0]
                let z: Int = items[0].x
                let label: String = "total: "
                let pairs: Map<Int, String> = map { 1: "one", 2: "two" }
                let b: Bool = !(a == 1) && total >= 0 || false
                let neg: Int = -a
                let h: Handle<Int> = spawn compute(x: 1)
                let v: Int = join h
                return label
            }

            test "add basics" target: org.example.demo.add {
                let r: Int = add(x: 1, y: 2)
            }

            external java "java.lang.Math" {
                static function abs(x: Int) -> Int
            }
        """.trimIndent()
    }
}
