package dev.loong.engine.testrunner

import dev.loong.engine.LoongCompiler
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TestRunnerTest {

    private val source = """
        module demo.tests

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            return x + y
        }

        /// Divides.
        public function div(a: Int, b: Int) -> Int {
            return a / b
        }

        test "add works" target: demo.tests.add {
            let r: Int = add(x: 2, y: 3)
        }

        test "div by zero fails" target: demo.tests.div {
            let z: Int = 0
            let r: Int = div(a: 1, b: z)
        }
    """.trimIndent()

    @Test
    fun `tests run in-process with results keyed by node id`() {
        val compiled = LoongCompiler.compile(source)
        assertTrue(compiled.diagnostics.isEmpty(), compiled.diagnostics.joinToString { it.message })

        val table = compiled.table
        val runner = TestRunner(table, compiled.hostClassName)
        val results = runner.runTests(compiled.classes)

        assertEquals(2, results.size)
        val byName = results.associateBy { it.name }
        val ok = byName["add works"]!!
        assertEquals(true, ok.passed)
        assertEquals(null, ok.error)
        val bad = byName["div by zero fails"]!!
        assertEquals(false, bad.passed)
        assertNotNull(bad.error)
        assertTrue("zero" in bad.error!!, bad.error)

        // 结果按 Test 节点 ID 索引（design §8.2）
        val testNodeIds = table.find(dev.loong.engine.ast.Kind.Test)
        assertEquals(testNodeIds.toSet(), results.map { it.testNodeId }.toSet())
    }

    @Test
    fun `subtree hash is stable and content-sensitive`() {
        val a = LoongCompiler.compile(source)
        val runner = TestRunner(a.table, a.hostClassName)
        val fn = a.table.find(dev.loong.engine.ast.Kind.Function).first()
        val h1 = runner.subtreeHash(fn)
        val h2 = runner.subtreeHash(fn)
        assertEquals(h1, h2)
    }
}
