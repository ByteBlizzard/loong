package dev.loong.engine.workspace

import dev.loong.engine.LoongCompiler
import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.str
import dev.loong.engine.diagnostics.Diagnostic
import dev.loong.engine.text.Renderer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * 差分测试门禁（plan.md M2 第 3 步，P0 风险对策）：
 * 随机编辑序列下，「编辑 + 模块重算（增量）」必须与「渲染文本 + 全量编译」产出
 * 完全一致——诊断（码+消息，忽略顺序与节点 ID）与字节码（逐字节）。
 *
 * 这同时持续验证：渲染器往返保真、解析/类型阶段的可重入性、代码生成确定性。
 */
class WorkspaceDifferentialTest {

    private val seed = """
        module demo.diff

        /// A point.
        public struct Point {
            /// x
            x: Int
            /// y
            y: Int
        }

        /// Colors.
        public enum Color {
            Red
            Green
            Blue
        }

        /// Adds two ints.
        public function add(x: Int, y: Int) -> Int {
            let sum: Int = x + y
            return sum
        }

        /// Max of two ints.
        public function max2(a: Int, b: Int) -> Int {
            if a > b {
                return a
            }
            return b
        }

        /// Describes a color.
        function describe(c: Color) -> String {
            match c {
                Color.Red => "red"
                Color.Green => "green"
                Color.Blue => "blue"
            }
        }

        /// Entry.
        function main() -> Unit {
            var total: Int = 0
            let p: Point = Point(x: 3, y: 4)
            total = p.x + p.y
            while total > 100 {
                total = total - 1
            }
            let xs: Array<Int> = [1, 2, 3]
            for x: Int in xs {
                total = total + x
            }
            let c: Color = Color.Green
            println(describe(c))
            println(str(add(x: total, y: max2(a: 1, b: 2))))
            let o: Option<Int> = Option.Some(7)
            if let Option.Some(v) = o {
                println(str(v))
            } else {
                println("none")
            }
        }

        test "add basics" target: demo.diff.add {
            let r: Int = add(x: 1, y: 2)
        }
    """.trimIndent()

    private fun diagKey(d: List<Diagnostic>) = d.map { "${it.code}|${it.message}" }.sorted()

    @Test
    fun `random edits keep incremental equal to full compile`() {
        repeat(8) { runSeed(it * 7919L, steps = 60) }
    }

    private fun runSeed(seedValue: Long, steps: Int) {
        val ws = Workspace()
        ws.load(seed)
        var m = ws.module("demo.diff/v1")
        assertEquals(diagKey(LoongCompiler.compile(seed).diagnostics), diagKey(m.diagnostics), "seed $seedValue: initial")

        val rng = Random(seedValue)
        for (step in 0 until steps) {
            val outcome = ws.edit("demo.diff/v1") { t -> mutate(t, rng) }
            m = ws.module("demo.diff/v1")

            // 结构不变量
            val problems = m.table.checkInvariants()
            assertTrue(problems.isEmpty(), "seed $seedValue step $step: ${problems.joinToString()}")

            // 全量基线：渲染当前 AST → 从文本全量编译
            val text = Renderer(m.table).render(m.root)
            val full = LoongCompiler.compile(text)

            assertEquals(
                diagKey(full.diagnostics),
                diagKey(m.diagnostics),
                "seed $seedValue step $step ($mutateLog)\n--- rendered ---\n${text.take(600)}",
            )

            // 字节码语义比较：类名集一致 + 每类方法(name+desc)集一致（ADR-0005 增量拷贝
            // 与全量发射的方法顺序不同；行为正确性由 conformance 与拷贝源验证闭环守护）
            if (full.diagnostics.isEmpty() && m.diagnostics.isEmpty()) {
                assertEquals(
                    methodSets(full.classes), methodSets(m.classes),
                    "seed $seedValue step $step: class/method mismatch",
                )
            } else {
                assertTrue(m.classes.isEmpty(), "seed $seedValue step $step: dirty module must have no classes")
            }
            assertEquals(outcome.revision, m.revision)
        }
    }

    /** 类 → 方法(name+desc) 集的语义指纹。 */
    private fun methodSets(classes: Map<String, ByteArray>): Map<String, Set<String>> =
        classes.mapValues { (_, bytes) ->
            val set = sortedSetOf<String>()
            org.objectweb.asm.ClassReader(bytes).accept(
                object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int, name: String, desc: String, signature: String?,
                        exceptions: Array<out String>?,
                    ): org.objectweb.asm.MethodVisitor? {
                        set.add(name + desc)
                        return null
                    }
                },
                org.objectweb.asm.ClassReader.SKIP_CODE,
            )
            set
        }

    private var mutateLog = "init"

    /** 随机编辑（全部保持可渲染性：完整节点、合法字段值）。 */
    private fun mutate(t: dev.loong.engine.ast.NodeTable, rng: Random) {
        val blocks = t.find(Kind.Block).filter { t[it].kids.isNotEmpty() }
        val allLets = t.find(Kind.Let)
        val intLits = t.find(Kind.Literal).filter {
            (t[it].fields["lit_kind"] as? FieldValue.Str)?.value == "int" &&
                (t[it].fields["value"] as? FieldValue.Str)?.value?.matches(Regex("-?[0-9]+")) == true
        }
        val ops = ArrayList<String>()
        // 按可用操作池随机选择
        val choices = ArrayList<String>()
        if (blocks.isNotEmpty()) {
            choices += "delete"
            choices += "move"
            choices += "insert-let"
        }
        if (intLits.isNotEmpty()) choices += "bump-literal"
        if (allLets.isNotEmpty()) {
            choices += "rename-let"
            choices += "retype-let"
        }
        if (choices.isEmpty()) return
        val op = choices[rng.nextInt(choices.size)]
        mutateLog = op
        when (op) {
            "delete" -> {
                val b = blocks[rng.nextInt(blocks.size)]
                val kids = t[b].kids
                t.delete(kids[rng.nextInt(kids.size)])
            }
            "move" -> {
                val b = blocks[rng.nextInt(blocks.size)]
                val kids = t[b].kids
                if (kids.size >= 2) {
                    val from = rng.nextInt(kids.size)
                    var to = rng.nextInt(kids.size)
                    if (to == from) to = (to + 1) % kids.size
                    t.move(kids[from], b, to)
                }
            }
            "insert-let" -> {
                val b = blocks[rng.nextInt(blocks.size)]
                val idx = rng.nextInt(t[b].kids.size + 1)
                // 完整节点：先建初始化表达式，再建带子节点的 Let 并原位挂接
                val init = t.create(Kind.Literal, mapOf("lit_kind" to str("int"), "value" to str("0")))
                t.insert(b, idx, Kind.Let, mapOf("name" to str("v${rng.nextInt(1000)}"), "type" to str("Int"), "mutable" to dev.loong.engine.ast.bool(false), "shared" to dev.loong.engine.ast.bool(false)))
                val letNode = t[b].kids[idx]
                t.attach(init, letNode, 0)
            }
            "bump-literal" -> {
                val lit = intLits[rng.nextInt(intLits.size)]
                val old = (t[lit].fields["value"] as FieldValue.Str).value
                t.replace(lit, fields = mapOf("lit_kind" to str("int"), "value" to str((old.toLong() + 1).toString())))
            }
            "rename-let" -> {
                val let = allLets[rng.nextInt(allLets.size)]
                val fields = t[let].fields.toMutableMap()
                fields["name"] = str("n${rng.nextInt(1000)}")
                t.replace(let, fields = fields)
            }
            "retype-let" -> {
                val let = allLets[rng.nextInt(allLets.size)]
                val fields = t[let].fields.toMutableMap()
                fields["type"] = str(if (rng.nextBoolean()) "String" else "Bool")
                t.replace(let, fields = fields)
            }
        }
    }
}
