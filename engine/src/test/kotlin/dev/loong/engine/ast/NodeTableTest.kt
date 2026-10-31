package dev.loong.engine.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeTableTest {

    private fun module(name: String = "org.example.test"): NodeId {
        val table = NodeTable()
        return table.create(Kind.Module, mapOf("name" to str(name)))
    }

    private fun table() = NodeTable()

    @Test
    fun `insert maintains kids order and parent backrefs`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val f1 = t.insert(root, 0, Kind.Function, mapOf("name" to str("f1")))
        val f2 = t.insert(root, 1, Kind.Function, mapOf("name" to str("f2")))
        val f0 = t.insert(root, 0, Kind.Function, mapOf("name" to str("f0")))

        assertEquals(listOf(f0, f1, f2), t.kidsOf(root))
        assertEquals(root, t[f0].parent)
        assertEquals(root, t[f1].parent)
        assertEquals(root, t[f2].parent)
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `replace keeps node id and returns ancestor chain as affected`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val fn = t.insert(root, 0, Kind.Function, mapOf("name" to str("f")))
        val body = t.insert(fn, 0, Kind.Block)
        t.insert(body, 0, Kind.Return)

        val affected = t.replace(fn, fields = mapOf("name" to str("renamed")))

        assertEquals(fn, t.find(Kind.Function).single())
        assertEquals("renamed", (t[fn].fields["name"] as FieldValue.Str).value)
        assertEquals(setOf(fn, root), affected)
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `replace kids deletes old subtree`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val fn = t.insert(root, 0, Kind.Function)
        val oldBlock = t.insert(fn, 0, Kind.Block)
        t.insert(oldBlock, 0, Kind.Return)

        val newBlock = t.create(Kind.Block)
        t.replace(fn, kids = listOf(newBlock))

        assertEquals(null, t.getOrNull(oldBlock)) // 旧子树整体删除
        assertEquals(listOf(newBlock), t[fn].kids)
        assertEquals(fn, t[newBlock].parent)
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `move reorders across parents and reports both chains`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val a = t.insert(root, 0, Kind.Struct, mapOf("name" to str("A")))
        val b = t.insert(root, 1, Kind.Struct, mapOf("name" to str("B")))
        val field = t.insert(a, 0, Kind.Field, mapOf("name" to str("x")))

        val affected = t.move(field, b, 0)

        assertEquals(listOf(field), t.kidsOf(b))
        assertTrue(t.kidsOf(a).isEmpty())
        assertEquals(setOf(field, a, b, root), affected)
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `move into own subtree is rejected and leaves tree unchanged`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val s = t.insert(root, 0, Kind.Struct)
        val f = t.insert(s, 0, Kind.Field)

        assertThrows<IllegalStateException> { t.move(s, f, 0) }
        // 被拒绝的操作零副作用：树保持原状
        assertEquals(root, t[s].parent)
        assertEquals(listOf(s), t.kidsOf(root))
        assertEquals(listOf(f), t.kidsOf(s))
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `delete removes whole subtree and never reuses ids`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val fn = t.insert(root, 0, Kind.Function)
        val block = t.insert(fn, 0, Kind.Block)
        val ret = t.insert(block, 0, Kind.Return)
        val watermarkBefore = t.watermark

        val affected = t.delete(fn)

        assertEquals(null, t.getOrNull(fn))
        assertEquals(null, t.getOrNull(block))
        assertEquals(null, t.getOrNull(ret))
        assertEquals(setOf(fn, root), affected)
        assertTrue(t.kidsOf(root).isEmpty())

        // ID 永不复用：新节点 ID 严格大于历史水位
        val fresh = t.insert(root, 0, Kind.Function)
        assertTrue(fresh.value > watermarkBefore)
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `create adopts detached kids and rejects attached ones`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        val param = t.create(Kind.Param, mapOf("name" to str("x")))
        val fn = t.create(Kind.Function, kids = listOf(param))

        assertEquals(fn, t[param].parent)

        assertThrows<IllegalArgumentException> {
            t.create(Kind.Struct, kids = listOf(param)) // param 已有父节点
        }
        assertTrue(t.checkInvariants().isEmpty())
    }

    @Test
    fun `find filters by kind and predicate`() {
        val t = table()
        val root = t.create(Kind.Module, mapOf("name" to str("m")))
        t.insert(root, 0, Kind.Function, mapOf("name" to str("alpha")))
        t.insert(root, 1, Kind.Function, mapOf("name" to str("beta")))
        t.insert(root, 2, Kind.Struct, mapOf("name" to str("alpha")))

        assertEquals(2, t.find(Kind.Function).size)
        val namedAlpha = t.find { node ->
            node.fields["name"] == FieldValue.Str("alpha")
        }
        assertEquals(2, namedAlpha.size)
    }

    @Test
    fun `random edit sequences keep invariants`() {
        repeat(20) { round ->
            val t = table()
            val rng = java.util.Random(round.toLong())
            val root = t.create(Kind.Module, mapOf("name" to str("m")))
            val containers = mutableListOf(root)

            repeat(200) {
                when (rng.nextInt(4)) {
                    0 -> { // insert
                        val parent = containers[rng.nextInt(containers.size)]
                        val kind = Kind.entries[rng.nextInt(Kind.entries.size)]
                        val id = t.insert(parent, rng.nextInt(t.kidsOf(parent).size + 1), kind)
                        if (kind in setOf(Kind.Module, Kind.Struct, Kind.Function, Kind.Block)) {
                            containers += id
                        }
                    }
                    1 -> { // delete a random non-root node
                        val candidates = t.ids.filter { it != root }
                        if (candidates.isNotEmpty()) {
                            t.delete(candidates[rng.nextInt(candidates.size)])
                            containers.removeAll { t.getOrNull(it) == null }
                        }
                    }
                    2 -> { // move a random attached node to a random container
                        val candidates = t.ids.filter { it != root }
                        if (candidates.isNotEmpty()) {
                            val id = candidates[rng.nextInt(candidates.size)]
                            val target = containers[rng.nextInt(containers.size)]
                            runCatching { t.move(id, target, 0) } // 自子树移动会被拒绝，属正常输入
                        }
                    }
                    else -> { // replace fields
                        val candidates = t.ids.toList()
                        if (candidates.isNotEmpty()) {
                            t.replace(
                                candidates[rng.nextInt(candidates.size)],
                                fields = mapOf("touched" to bool(true)),
                            )
                        }
                    }
                }
                t.checkInvariants().let {
                    assertTrue(it.isEmpty(), "round $round invariant broken: $it")
                }
            }
        }
    }

    @Test
    fun `example function ast from schema doc`() {
        // 复现 ast-schema-v1.md 中 Function 的形态：DocComment?, Param*, Block
        val t = table()
        val mod = t.create(Kind.Module, mapOf("name" to str("org.example.math")))
        val doc = t.create(Kind.DocComment, mapOf("text" to str("Adds two integers.")))
        val p1 = t.create(Kind.Param, mapOf("name" to str("x"), "type" to str("Int")))
        val p2 = t.create(Kind.Param, mapOf("name" to str("y"), "type" to str("Int")))
        val ret = t.create(Kind.Return)
        val body = t.create(Kind.Block, kids = listOf(ret))
        val fn = t.create(
            Kind.Function,
            mapOf(
                "name" to str("add"),
                "visibility" to str("public"),
                "return_type" to str("Int"),
            ),
            kids = listOf(doc, p1, p2, body),
        )
        t.attach(fn, mod, 0)

        assertEquals(listOf(doc, p1, p2, body), t.kidsOf(fn))
        assertEquals(mod, t.roots().single())
        assertEquals(7, t.size)
        assertTrue(t.checkInvariants().isEmpty())
    }
}
