package dev.loong.engine.workspace

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 增量 codegen（ADR-0005）：编辑后经拷贝路径生成的类必须真实运行正确。 */
class IncrementalRunTest {

    private fun runMain(ws: Workspace, name: String): String {
        val m = ws.module(name)
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())
        val dir = Files.createTempDirectory("loong-inc")
        m.classes.forEach { (internal, bytes) ->
            val f = dir.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val cp = dir.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java.exe", "-cp", cp, m.hostClassName,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
        assertEquals(0, proc.waitFor(), out)
        return out
    }

    @Test
    fun `copied and re-emitted methods run correctly after edits`() {
        val ws = Workspace()
        ws.load(
            """
            module demo.inc

            /// Adds one.
            function addOne(x: Int) -> Int {
                return x + 1
            }

            /// Doubles.
            function dbl(x: Int) -> Int {
                return x * 2
            }

            /// Entry.
            function main() -> Unit {
                println(str(addOne(10)))
                println(str(dbl(6)))
            }
            """.trimIndent(),
        )
        assertEquals("11\n12\n", runMain(ws, "demo.inc/v1"))

        // 编辑 addOne（+1 → +2）：addOne 重发射，main 与 dbl 应走拷贝路径
        val m = ws.module("demo.inc/v1")
        ws.edit("demo.inc/v1") { t ->
            val lit = t.find(dev.loong.engine.ast.Kind.Literal)
                .first { (t[it].fields["value"] as? dev.loong.engine.ast.FieldValue.Str)?.value == "1" }
            t.replace(lit, fields = mapOf("lit_kind" to dev.loong.engine.ast.str("int"), "value" to dev.loong.engine.ast.str("2")))
        }
        assertTrue(m.revision == 2L)
        assertEquals("12\n12\n", runMain(ws, "demo.inc/v1"))

        // 再编辑 dbl（*2 → *3）：另一函数重发射，addOne 上轮字节应被拷贝复用
        ws.edit("demo.inc/v1") { t ->
            // 在 dbl 体内找乘数 2（跳过 addOne 里的 +2）
            fun enclosingFn(id: dev.loong.engine.ast.NodeId): String {
                var cur = id
                while (t[cur].parent != dev.loong.engine.ast.NodeId.NONE) {
                    cur = t[cur].parent
                    val n = t[cur]
                    if (n.kind == dev.loong.engine.ast.Kind.Function) {
                        return (n.fields["name"] as? dev.loong.engine.ast.FieldValue.Str)?.value ?: ""
                    }
                }
                return ""
            }
            val lit = t.find(dev.loong.engine.ast.Kind.Literal).first {
                (t[it].fields["value"] as? dev.loong.engine.ast.FieldValue.Str)?.value == "2" &&
                    enclosingFn(it) == "dbl"
            }
            t.replace(lit, fields = mapOf("lit_kind" to dev.loong.engine.ast.str("int"), "value" to dev.loong.engine.ast.str("3")))
        }
        assertEquals("12\n18\n", runMain(ws, "demo.inc/v1"))
    }
}
