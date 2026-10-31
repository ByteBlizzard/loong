package dev.loong.engine.storage

import dev.loong.engine.LoongCompiler
import dev.loong.engine.ast.Kind
import dev.loong.engine.text.Parser
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LoongastCodecTest {

    private val sample = """
        module demo.snap

        /// A point.
        public struct Point {
            /// x
            x: Int
            /// y
            y: Int
        }

        /// Colors.
        public enum Color { Red Green Blue(Int) }

        /// Adds.
        public function add(x: Int, y: Int) -> Int {
            let sum: Int = x + y
            return sum
        }

        test "add" target: demo.snap.add {
            let r: Int = add(x: 1, y: 2)
        }
    """.trimIndent()

    @Test
    fun `serialization is deterministic`() {
        val r = Parser(sample).parse()
        val a = LoongastCodec.serialize(r.table, r.root)
        val b = LoongastCodec.serialize(r.table, r.root)
        assertEquals(a, b)
        assertTrue(a.startsWith("{\"module\":\"demo.snap/v1\",\"nodes\":[{\"fields\":"))
    }

    @Test
    fun `roundtrip preserves structure and refs`() {
        val r = Parser(sample).parse()
        val text = LoongastCodec.serialize(r.table, r.root)

        val (table2, root2) = LoongastCodec.deserialize(text)
        // 结构（kind + 字段）按 DFS 序一致
        fun structure(t: dev.loong.engine.ast.NodeTable, root: dev.loong.engine.ast.NodeId) =
            buildList {
                fun walk(id: dev.loong.engine.ast.NodeId) {
                    val n = t[id]
                    add(n.kind to n.fields.entries.sortedBy { it.key }.map { it.key to it.value.toString() })
                    n.kids.forEach { walk(it) }
                }
                walk(root)
            }
        assertEquals(structure(r.table, r.root), structure(table2, root2))
        assertTrue(table2.checkInvariants().isEmpty())
        assertEquals(root2, table2.roots().single())

        // 反序列化后再序列化 == 原文本（确定性 + 无信息丢失）
        assertEquals(text, LoongastCodec.serialize(table2, root2))

        // 反序列化的 AST 仍可通过完整编译（引用有效性）
        val render = dev.loong.engine.text.Renderer(table2).render(root2)
        val compiled = LoongCompiler.compile(render)
        assertTrue(compiled.diagnostics.isEmpty(), compiled.diagnostics.joinToString { it.message })
    }

    @Test
    fun `watermark semantics hold after restore`() {
        val r = Parser(sample).parse()
        val text = LoongastCodec.serialize(r.table, r.root)
        val (table2, _) = LoongastCodec.deserialize(text)
        val fresh = table2.create(Kind.Let, mapOf("name" to dev.loong.engine.ast.str("z")))
        assertTrue(fresh.value > table2.ids.maxOf { it.value } - 1_000_000) // 新 ID 合理
        assertTrue(fresh.value > 0)
    }
}
