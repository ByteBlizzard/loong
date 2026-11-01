package dev.loong.engine.rules

import dev.loong.engine.LoongCompiler
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeTable
import dev.loong.engine.text.Renderer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 结构不变量（StructuralInvariants）单测：
 * - problemOf 覆盖外部 agent 实战踩过的全部错位形态；
 * - 解析器产出（真实源码）全量通过——表与 Parser 严格一致，不误报；
 * - KIND_SPEC 覆盖全部 Kind（list_ast_kinds 工具的自描述完整性）。
 */
class StructuralInvariantsTest {

    private fun problem(kind: Kind, vararg kids: Kind) = StructuralInvariants.problemOf(kind, kids.toList())

    @Test
    fun `misplaced statements are rejected with actionable messages`() {
        // Assign 挂成 If 第三个孩子（else 槽）——报告的高频错位
        assertNotNull(problem(Kind.If, Kind.Path, Kind.Block, Kind.Assign))
        // 语句挂 While 直接子位（不进 Block）——尾部语句静默丢失的根因
        assertNotNull(problem(Kind.While, Kind.Path, Kind.Block, Kind.Assign))
        assertNotNull(problem(Kind.While, Kind.Path, Kind.Block, Kind.Return))
        // Binary 缺操作数（毒节点）
        assertNotNull(problem(Kind.Binary, Kind.Path))
        assertNotNull(problem(Kind.Binary))
        // If 缺分支 / else 槽塞语句
        assertNotNull(problem(Kind.If, Kind.Path))
        assertNotNull(problem(Kind.If, Kind.Path, Kind.Block, Kind.If, Kind.Block))
        // MatchArm 体放 Return（须包 Block）
        assertNotNull(problem(Kind.MatchArm, Kind.Return))
        // Call 参数位不是 Arg
        assertNotNull(problem(Kind.Call, Kind.Path, Kind.Path))
        // DocComment 不在首位
        assertNotNull(problem(Kind.Function, Kind.Param, Kind.DocComment, Kind.Block))
        // Block 里塞声明
        assertNotNull(problem(Kind.Block, Kind.Let, Kind.Struct))
    }

    @Test
    fun `well-formed shapes pass`() {
        assertNull(problem(Kind.If, Kind.Path, Kind.Block))
        assertNull(problem(Kind.If, Kind.Path, Kind.Block, Kind.If)) // else-if 链
        assertNull(problem(Kind.If, Kind.Path, Kind.Block, Kind.Block))
        assertNull(problem(Kind.While, Kind.Binary, Kind.Block))
        assertNull(problem(Kind.Assign, Kind.Path, Kind.Binary))
        assertNull(problem(Kind.MatchArm, Kind.Block))
        assertNull(problem(Kind.MatchArm, Kind.Path))
        assertNull(problem(Kind.Call, Kind.Path, Kind.Arg, Kind.Arg))
        assertNull(problem(Kind.Function, Kind.DocComment, Kind.TypeParam, Kind.Param, Kind.Block))
        // 依赖接口 AST 的无体签名（Block 可缺省）
        assertNull(problem(Kind.Function, Kind.DocComment, Kind.Param))
        assertNull(problem(Kind.Test, Kind.Block))
        assertNull(StructuralInvariants.problemOf(Kind.Literal, emptyList(), "string"))
        assertNull(StructuralInvariants.problemOf(Kind.Literal, listOf(Kind.MapEntry, Kind.MapEntry), "map"))
        assertNotNull(StructuralInvariants.problemOf(Kind.Literal, listOf(Kind.Path), "string"))
    }

    @Test
    fun `parser output over all conformance programs passes the gate`() {
        for (name in listOf("fac", "arith", "fib", "structs", "enums", "optres", "coll", "spawn", "ext", "consts")) {
            val src = javaClass.getResourceAsStream("/conformance/$name.loong")!!.readBytes().decodeToString()
            val parsed = LoongCompiler.check(src)
            assertEquals(
                emptyList(), StructuralInvariants.check(parsed.table),
                "conformance program $name tripped the structural gate",
            )
        }
    }

    @Test
    fun `gate catches malformed table instead of downstream crashes`() {
        // 手工构造无操作数的 Binary：结构检查点名，渲染降级（不抛异常）
        val parsed = LoongCompiler.check("module demo.broken\n")
        val t = parsed.table
        val root = parsed.root
        val block = t.insert(root, 0, Kind.Block)
        val stmt = t.insert(block, 0, Kind.ExprStmt)
        t.insert(stmt, 0, Kind.Binary, mapOf("op" to dev.loong.engine.ast.str("+")))
        assertTrue(StructuralInvariants.check(t).isNotEmpty())
        // 防御式渲染：绝不抛异常
        assertTrue(Renderer(t).render(root).isNotEmpty())
    }

    @Test
    fun `kind spec covers every kind`() {
        assertEquals(Kind.entries.size, StructuralInvariants.KIND_SPEC.size)
        assertEquals(Kind.entries.toSet(), StructuralInvariants.KIND_SPEC.map { it.first }.toSet())
        StructuralInvariants.KIND_SPEC.forEach { (_, kids, _) ->
            assertTrue(kids.isNotBlank(), "every kind needs a kids description")
        }
    }
}
