package dev.loong.engine.rules

import dev.loong.engine.LoongCompiler
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.resolve.Resolver
import dev.loong.engine.text.Parser
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RulesCheckerTest {

    /** 规则单测：只跑 解析+解析名字+规则（不跑类型检查，聚焦规则本身）。 */
    private fun rules(src: String): List<Diagnostic> {
        val r = Parser(src).parse()
        assertTrue(r.diagnostics.isEmpty(), r.diagnostics.joinToString { it.message })
        Resolver(r.table).resolve(r.root)
        return RulesChecker(r.table).check(r.root)
    }

    private fun codes(src: String): List<String> = rules(src).map { it.code }

    private fun expectOk(src: String) {
        val diags = rules(src)
        assertTrue(diags.isEmpty(), "expected no diagnostics but got:\n" + diags.joinToString("\n") { "${it.code}: ${it.message}" })
    }

    private fun expectCode(src: String, code: String) {
        assertTrue(code in codes(src), "expected $code but got: " + codes(src))
    }

    @Test
    fun `fully compliant module passes all rules`() {
        expectOk(
            """
            module m

            /// A point.
            public struct Point {
                /// x coordinate
                x: Int
                /// y coordinate
                y: Int
            }

            /// Shape contract.
            public interface Shape<S> {
                /// Computes the area.
                function area(self: S) -> Int;
            }

            impl Shape<Point> for Point {
                /// Area of a point.
                function area(self: Point) -> Int {
                    return self.x + self.y
                }
            }

            /// Adds two integers.
            public function add(x: Int, y: Int) -> Int {
                return x + y
            }

            /// Internal helper needs no test.
            function helper() -> Int {
                return 1
            }

            test "add basics" target: m.add {
                let r: Int = add(x: 1, y: 2)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `every declaration kind requires a non-empty doc`() {
        val c = rules(
            """
            module m
            public struct P {
                v: Int
            }
            public interface I<S> {
                function f(self: S) -> Int;
            }
            public function g() -> Int {
                return 0
            }
            impl I<P> for P {
                function f(self: P) -> Int {
                    return 0
                }
            }
            """.trimIndent(),
        )
        // struct、field、interface、methodsig、模块函数、impl 函数
        assertEquals(6, c.filter { it.code == DiagCodes.E_MISSING_DOC }.size, c.joinToString { it.message })
    }

    @Test
    fun `blank doc comment counts as missing`() {
        expectCode(
            """
            module m
            ///
            function g() -> Int {
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_MISSING_DOC,
        )
    }

    @Test
    fun `public functions require tests`() {
        expectCode(
            """
            module m
            /// No test here.
            public function lonely() -> Int {
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_NO_TEST,
        )
        expectOk(
            """
            module m
            /// Covered.
            public function covered() -> Int {
                return 0
            }
            /// Internal.
            function quiet() -> Int {
                return 0
            }
            test "covers" target: m.covered {
                let r: Int = covered()
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `function body statement limit is enforced`() {
        val body = StringBuilder()
        body.append("var t: Int = 0\n")
        repeat(51) { body.append("    t = t + 1\n") }
        val src = """
            module m
            /// Long function.
            function longOne() -> Int {
                ${body}                return t
            }
        """.trimIndent()
        expectCode(src, DiagCodes.E_TOO_LONG)

        val okSrc = """
            module m
            /// Short function.
            function shortOne() -> Int {
                var t: Int = 0
                t = t + 1
                return t
            }
        """.trimIndent()
        expectOk(okSrc)
    }

    @Test
    fun `self dependency is a cycle`() {
        expectCode(
            """
            module m
            depends m
            /// Nothing.
            function f() -> Int {
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_CYCLE_DEPENDS,
        )
        expectCode(
            """
            module m
            depends m/v2
            /// Nothing.
            function f() -> Int {
                return 0
            }
            """.trimIndent(),
            DiagCodes.E_CYCLE_DEPENDS,
        )
    }

    @Test
    fun `impl signature must match exactly`() {
        // 返回类型不一致
        expectCode(
            """
            module m
            /// I.
            public interface I<S> {
                /// F.
                function f(self: S) -> Int;
            }
            /// P.
            public struct P {
                /// v
                v: Int
            }
            impl I<P> for P {
                /// Wrong return type.
                function f(self: P) -> Bool {
                    return true
                }
            }
            """.trimIndent(),
            DiagCodes.E_IMPL_SIGNATURE_MISMATCH,
        )
        // 形参个数不一致
        expectCode(
            """
            module m
            /// I.
            public interface I<S> {
                /// F.
                function f(self: S, n: Int) -> Int;
            }
            /// P.
            public struct P {
                /// v
                v: Int
            }
            impl I<P> for P {
                /// Wrong arity.
                function f(self: P) -> Int {
                    return 0
                }
            }
            """.trimIndent(),
            DiagCodes.E_IMPL_SIGNATURE_MISMATCH,
        )
        // 接口上不存在的方法
        expectCode(
            """
            module m
            /// I.
            public interface I<S> {
                /// F.
                function f(self: S) -> Int;
            }
            /// P.
            public struct P {
                /// v
                v: Int
            }
            impl I<P> for P {
                /// Not in interface.
                function g(self: P) -> Int {
                    return 0
                }
            }
            """.trimIndent(),
            DiagCodes.E_IMPL_SIGNATURE_MISMATCH,
        )
    }

    @Test
    fun `builtin interface impls are not compared in m1`() {
        expectOk(
            """
            module m
            /// Counter.
            public struct Counter {
                /// limit
                limit: Int
            }
            impl Iterable<Int> for Counter {
                /// Next.
                function next(self: Counter) -> Option<Int> {
                    return Option.None
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `compiler facade aggregates all four stages`() {
        val result = LoongCompiler.check(
            """
            module m
            public function broken() -> Int {
                return "oops"
            }
            """.trimIndent(),
        )
        // 规则阶段：缺 doc、public 无测试；类型阶段：返回类型不匹配
        val codes = result.diagnostics.map { it.code }
        assertTrue(DiagCodes.E_MISSING_DOC in codes, codes.toString())
        assertTrue(DiagCodes.E_NO_TEST in codes, codes.toString())
        assertTrue(DiagCodes.E_TYPE_MISMATCH in codes, codes.toString())
        assertTrue(result.hasErrors)
    }

    @Test
    fun `compiler facade accepts a clean module`() {
        val result = LoongCompiler.check(
            """
            module m
            /// Fine.
            public function fine() -> Int {
                return 1
            }
            test "fine" target: m.fine {
                let r: Int = fine()
            }
            """.trimIndent(),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(!result.hasErrors)
    }
}
