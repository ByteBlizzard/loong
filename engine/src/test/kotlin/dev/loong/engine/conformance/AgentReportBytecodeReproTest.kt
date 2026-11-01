package dev.loong.engine.conformance

import dev.loong.engine.LoongCompiler
import dev.loong.engine.diagnostics.DiagCodes
import dev.loong.engine.diagnostics.Severity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.MethodVisitor

/**
 * 外部 agent 实战报告 P0-2「合法源码 → 错误字节码」四项断言的回归测试：
 * 1. 缺 Return 的非 Unit 函数 → 诊断期 E_MISSING_RETURN（不再全绿后运行期 VerifyError）；
 * 2. Call 实参位是 Index（cmp(a[i], b[j]) 归并场景）→ 编译运行正确（未复现，护栏）；
 * 3. if(a){ if(b){…} 赋值 } 外层 if 尾部赋值 → 行为正确（未复现，护栏）；
 * 4. main(args: Array<String>) → 生成 public static void main(String[]) 入口桥。
 */
class AgentReportBytecodeReproTest {

    private class BytesLoader(private val classes: Map<String, ByteArray>) : ClassLoader() {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private fun errors(src: String) =
        LoongCompiler.compile(src).diagnostics.filter { it.severity == Severity.Error }

    private fun invokeStatic(src: String, method: String, vararg args: Any?): Any? {
        val compiled = LoongCompiler.compile(src)
        assertTrue(
            compiled.diagnostics.none { it.severity == Severity.Error },
            "expected clean diagnostics: " +
                compiled.diagnostics.joinToString { "${it.code}: ${it.message}" },
        )
        val cls = BytesLoader(compiled.classes).loadClass(compiled.hostClassName)
        val m = cls.methods.first { it.name == method }
        return m.invoke(null, *args)
    }

    @Test
    fun `1 - non-Unit function without return reports E_MISSING_RETURN`() {
        val src = """
            module demo.noret

            /// Half-built helper.
            function f(x: Int) -> Int {
                let y: Int = x + 1
            }
        """.trimIndent()
        val diags = errors(src)
        assertTrue(
            diags.any { it.code == DiagCodes.E_MISSING_RETURN },
            "expected E_MISSING_RETURN, got: " + diags.joinToString { it.code },
        )
        assertTrue(LoongCompiler.compile(src).classes.isEmpty(), "errors must block codegen")
    }

    @Test
    fun `1b - legitimate return shapes do not trip E_MISSING_RETURN`() {
        // 全分支 return 的 if / while true / match 尾表达式 / 块尾尾表达式
        val src = """
            module demo.okret

            /// Branches both return.
            function byIf(n: Int) -> Int {
                if n > 0 { return 1 } else { return 0 }
            }

            /// Loop is infinite and returns inside.
            function byWhile(n: Int) -> Int {
                while true { return n }
            }

            /// Match tail expression is the block value.
            function byMatch(n: Int) -> Int {
                match n { 0 => 0, _ => n }
            }

            /// Tail expression.
            function byTail(n: Int) -> Int {
                n + 1
            }

            /// Partial-return control-flow tail must still be flagged.
            function badPartial(n: Int) -> Int {
                if n > 0 { return 1 }
            }
        """.trimIndent()
        val diags = errors(src)
        val flagged = diags.filter { it.code == DiagCodes.E_MISSING_RETURN }
        assertEquals(1, flagged.size, "only badPartial should be flagged: $flagged")
        assertTrue(flagged.first().message.contains("badPartial"))
    }

    @Test
    fun `2 - Index in call argument position`() {
        val src = """
            module demo.idxarg

            /// Compares two ints.
            function cmp(a: Int, b: Int) -> Int {
                return a - b
            }

            /// Reads two slots through the comparator.
            function pick(a: Array<Int>, i: Int) -> Int {
                return cmp(a[i], a[i])
            }
        """.trimIndent()
        assertEquals(0L, invokeStatic(src, "pick", arrayOf<Any>(1L, 2L, 3L), 1L))
    }

    @Test
    fun `2b - index args variants (two arrays, named args, nested call)`() {
        val src = """
            module demo.idxarg2

            /// Orders two ints.
            function cmp(a: Int, b: Int) -> Int {
                return a - b
            }

            /// Merge-style read across two arrays.
            function both(a: Array<Int>, b: Array<Int>, i: Int, j: Int) -> Int {
                return cmp(a: a[i], b: b[j])
            }

            /// Nested call argument.
            function nested(a: Array<Int>, i: Int) -> Int {
                return cmp(a[i], cmp(a[i], a[i]))
            }
        """.trimIndent()
        val loader = run {
            val compiled = LoongCompiler.compile(src)
            assertTrue(compiled.diagnostics.none { it.severity == Severity.Error })
            BytesLoader(compiled.classes).loadClass(compiled.hostClassName)
        }
        assertEquals(
            -8L,
            loader.methods.first { it.name == "both" }
                .invoke(null, arrayOf(1L, 5L), arrayOf(3L, 9L), 0L, 1L),
        )
        assertEquals(6L, loader.methods.first { it.name == "nested" }.invoke(null, arrayOf(4L, 6L), 1L))
    }

    @Test
    fun `3 - assignment at tail of outer if is not lost`() {
        val src = """
            module demo.iftail

            /// Marks by magnitude.
            function f(n: Int) -> Int {
                var r: Int = 0
                if n > 0 {
                    if n > 1 { r = 1 }
                    r = 2
                }
                return r
            }
        """.trimIndent()
        assertEquals(2L, invokeStatic(src, "f", 5L))
        assertEquals(0L, invokeStatic(src, "f", 0L))
    }

    @Test
    fun `4 - main with args parameter gets StringArray entrypoint bridge`() {
        val src = """
            module demo.mainargs

            /// Entry point taking args.
            function main(args: Array<String>) -> Unit {
                println(str(len(args)))
            }
        """.trimIndent()
        val methods = hostMethods(src)
        assertTrue(
            "main([Ljava/lang/String;)V" in methods,
            "public static void main(String[]) bridge missing; methods = $methods",
        )
    }

    @Test
    fun `4b - zero-arg main keeps its bridge`() {
        val src = """
            module demo.mainzero

            /// Entry point.
            function main() -> Unit {
                println("hi")
            }
        """.trimIndent()
        assertTrue("main([Ljava/lang/String;)V" in hostMethods(src))
    }

    private fun hostMethods(src: String): List<String> {
        val compiled = LoongCompiler.compile(src)
        assertTrue(
            compiled.diagnostics.none { it.severity == Severity.Error },
            compiled.diagnostics.joinToString { "${it.code}: ${it.message}" },
        )
        val hostBytes = compiled.classes[compiled.hostClassName.replace('.', '/')]!!
        val methods = ArrayList<String>()
        ClassReader(hostBytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?,
                ): MethodVisitor? {
                    methods += "$name$desc"
                    return null
                }
            },
            0,
        )
        return methods
    }
}
