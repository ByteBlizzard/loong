package dev.loong.engine.types

import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.resolve.Resolver
import dev.loong.engine.text.Parser
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TypeCheckerTest {

    private class Checked(val table: NodeTable, val root: NodeId, val diags: List<Diagnostic>) {
        fun codes(): List<String> = diags.map { it.code }
        fun messages(): String = diags.joinToString("\n") { "${it.code}: ${it.message}" }
    }

    private fun check(src: String): Checked {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString { it.message })
        Resolver(r.table).resolve(r.root)
        val diags = TypeChecker(r.table).check(r.root)
        return Checked(r.table, r.root, diags)
    }

    private fun expectOk(src: String): Checked {
        val c = check(src)
        assertTrue(c.diags.isEmpty(), "expected no diagnostics but got:\n${c.messages()}")
        return c
    }

    private fun expectCode(src: String, code: String): Checked {
        val c = check(src)
        assertTrue(code in c.codes(), "expected $code but got:\n${c.messages()}")
        return c
    }

    @Test
    fun `happy path with literals operators calls and returns`() {
        expectOk(
            """
            module m
            function add(x: Int, y: Int) -> Int {
                let sum: Int = x + y * 2
                return sum
            }
            function max2(a: Int, b: Int) -> Int {
                if a > b { a } else { b }
            }
            function classify(v: Float) -> String {
                let s: String = "value"
                return s
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `let type mismatch and return mismatch`() {
        expectCode(
            """
            module m
            function f(x: Int) -> Int {
                let s: String = x
                return s
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
        expectCode(
            """
            module m
            function g() -> Int {
                return true
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
    }

    @Test
    fun `arity named args and duplicates`() {
        expectCode(
            """
            module m
            function f(x: Int, y: Int) -> Int { return x }
            function g() -> Int {
                return f(x: 1)
            }
            """.trimIndent(),
            DiagCodes.E_ARITY_MISMATCH,
        )
        expectCode(
            """
            module m
            function f(x: Int, y: Int) -> Int { return x }
            function g() -> Int {
                return f(x: 1, z: 2)
            }
            """.trimIndent(),
            DiagCodes.E_NAMED_ARG_UNKNOWN,
        )
    }

    @Test
    fun `mixed implicit conversion is rejected`() {
        expectCode(
            """
            module m
            function f(a: Int, b: Float) -> Float {
                return a + b
            }
            """.trimIndent(),
            DiagCodes.E_BAD_OPERAND_TYPE,
        )
    }

    @Test
    fun `if branches must agree and condition must be bool`() {
        expectCode(
            """
            module m
            function f(a: Int) -> Int {
                let r: Int = if a > 1 { a } else { false }
                return r
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
        expectCode(
            """
            module m
            function f(a: Int) -> Int {
                if a { return 1 }
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
    }

    @Test
    fun `immutable assignment is rejected`() {
        expectCode(
            """
            module m
            function f() -> Int {
                let x: Int = 1
                x = 2
                return x
            }
            """.trimIndent(),
            DiagCodes.E_ASSIGN_IMMUTABLE,
        )
        expectCode(
            """
            module m
            function g(x: Int) -> Int {
                x = 2
                return x
            }
            """.trimIndent(),
            DiagCodes.E_ASSIGN_IMMUTABLE,
        )
        expectOk(
            """
            module m
            function h() -> Int {
                var x: Int = 1
                x = 2
                return x
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `struct construction field access and unknown field`() {
        expectOk(
            """
            module m
            public struct Point {
                x: Int
                y: Int
            }
            function f() -> Int {
                let p: Point = Point(x: 1, y: 2)
                return p.x + p.y
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            public struct Point {
                x: Int
                y: Int
            }
            function f() -> Int {
                let p: Point = Point(x: 1)
                return p.x
            }
            """.trimIndent(),
            DiagCodes.E_ARITY_MISMATCH,
        )
        expectCode(
            """
            module m
            public struct Point {
                x: Int
                y: Int
            }
            function f() -> Int {
                let p: Point = Point(x: 1, y: 2)
                return p.z
            }
            """.trimIndent(),
            DiagCodes.E_UNKNOWN_FIELD,
        )
    }

    @Test
    fun `enum exhaustiveness and pattern mismatches`() {
        expectOk(
            """
            module m
            public enum Color { Red Green Blue }
            function f(c: Color) -> Int {
                match c {
                    Color.Red => 1
                    Color.Green => 2
                    Color.Blue => 3
                }
            }
            """.trimIndent(),
        )
        expectOk(
            """
            module m
            public enum Color { Red Green Blue }
            function f(c: Color) -> Int {
                match c {
                    Color.Red => 1
                    _ => 2
                }
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            public enum Color { Red Green Blue }
            function f(c: Color) -> Int {
                match c {
                    Color.Red => 1
                    Color.Green => 2
                }
            }
            """.trimIndent(),
            DiagCodes.E_NOT_EXHAUSTIVE,
        )
        expectCode(
            """
            module m
            public enum Color { Red Green Blue }
            public enum Other { X }
            function f(c: Color) -> Int {
                match c {
                    Other.X => 1
                    _ => 2
                }
            }
            """.trimIndent(),
            DiagCodes.E_PATTERN_TYPE_MISMATCH,
        )
    }

    @Test
    fun `literal match requires wildcard`() {
        expectCode(
            """
            module m
            function f(x: Int) -> Int {
                match x {
                    1 => 10
                }
            }
            """.trimIndent(),
            DiagCodes.E_NOT_EXHAUSTIVE,
        )
        expectOk(
            """
            module m
            function f(x: Int) -> Int {
                match x {
                    1 => 10
                    _ => 0
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `option result if-let and constructor inference`() {
        expectOk(
            """
            module m
            function f(v: Int) -> Option<Int> {
                return Option.Some(v)
            }
            function g() -> Int {
                let o: Option<Int> = Option.Some(1)
                if let Option.Some(v) = o {
                    return v
                } else {
                    return 0
                }
            }
            function h() -> Result<Int, String> {
                return Result.Ok(1)
            }
            function k() -> Int {
                let r: Result<Int, String> = Result.Err("boom")
                match r {
                    Result.Ok(v) => v
                    Result.Err(e) => 0
                }
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            function f() -> Int {
                let o: Option<Int> = Option.Some(true)
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
    }

    @Test
    fun `generic function unifies from arguments and checks constraints`() {
        expectOk(
            """
            module m
            public interface Show<S> {
                function show(self: S) -> String;
            }
            public struct Point {
                x: Int
            }
            impl Show<Point> for Point {
                function show(self: Point) -> String { return "point" }
            }
            function render<T>(v: T) -> String where T: Show {
                return show(self: v)
            }
            function f(p: Point) -> String {
                return render(p)
            }
            function first<T>(items: Array<T>) -> T where T: Show {
                return items[0]
            }
            function g(p: Point, ps: Array<Point>) -> Point {
                return first(ps)
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            public interface Show<S> {
                function show(self: S) -> String;
            }
            public struct Point { x: Int }
            function render<T>(v: T) -> String where T: Show {
                return "x"
            }
            function f(v: Int) -> String {
                return render(v)
            }
            """.trimIndent(),
            DiagCodes.E_CONSTRAINT_NOT_SATISFIED,
        )
    }

    @Test
    fun `bare type parameter is rejected`() {
        expectCode(
            """
            module m
            function f<T>(v: T) -> T {
                return v
            }
            """.trimIndent(),
            DiagCodes.E_MISSING_TYPE_CONSTRAINT,
        )
    }

    @Test
    fun `unknown and malformed types`() {
        expectCode(
            """
            module m
            function f(x: Missing) -> Int { return 0 }
            """.trimIndent(),
            DiagCodes.E_UNKNOWN_TYPE,
        )
        expectCode(
            """
            module m
            function f(x: Array) -> Int { return 0 }
            """.trimIndent(),
            DiagCodes.E_UNKNOWN_TYPE,
        )
    }

    @Test
    fun `for-in requires iterable and element type must match`() {
        expectOk(
            """
            module m
            function f(items: Array<Int>) -> Int {
                var total: Int = 0
                for it: Int in items {
                    total = total + it
                }
                return total
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            function f(items: Array<Int>) -> Int {
                for it: String in items {
                    return 0
                }
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
        expectCode(
            """
            module m
            function f(x: Int) -> Int {
                for it: Int in x {
                    return 0
                }
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_NOT_ITERABLE,
        )
    }

    @Test
    fun `spawn and join types`() {
        expectOk(
            """
            module m
            function work(n: Int) -> Int { return n }
            function f() -> Int {
                let h: Handle<Int> = spawn work(n: 1)
                let v: Int = join h
                return v
            }
            """.trimIndent(),
        )
        expectCode(
            """
            module m
            function f(x: Int) -> Int {
                return join x
            }
            """.trimIndent(),
            DiagCodes.E_BAD_OPERAND_TYPE,
        )
    }

    @Test
    fun `shared requires mutex`() {
        expectCode(
            """
            module m
            function f() -> Unit {
                shared var counter: Int = 0
            }
            """.trimIndent(),
            DiagCodes.E_SHARED_REQUIRES_MUTEX,
        )
        expectOk(
            """
            module m
            function g() -> Unit {
                shared var counter: Mutex<Int> = makeMutex(v: 0)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `user iterable struct supports for-in`() {
        expectOk(
            """
            module m
            public struct Counter {
                limit: Int
            }
            impl Iterable<Int> for Counter {
                function next(self: Counter) -> Option<Int> {
                    return Option.None
                }
            }
            function f(c: Counter) -> Int {
                var total: Int = 0
                for v: Int in c {
                    total = total + v
                }
                return total
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `const type must match literal`() {
        expectCode(
            """
            module m
            public const MAX: Int = 3.5
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
    }

    @Test
    fun `map and array literals check element types`() {
        expectCode(
            """
            module m
            function f() -> Int {
                let m: Map<Int, String> = map { 1: "one", "two": "2" }
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
        expectOk(
            """
            module m
            function f() -> Int {
                let a: Array<Int> = [1, 2, 3]
                let m: Map<Int, String> = map { 1: "one", 2: "two" }
                return a[0]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `test bodies are checked with unit return`() {
        expectCode(
            """
            module m
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }
            test "t" target: m.add {
                let r: String = add(x: 1, y: 2)
            }
            """.trimIndent(),
            DiagCodes.E_TYPE_MISMATCH,
        )
    }
}
