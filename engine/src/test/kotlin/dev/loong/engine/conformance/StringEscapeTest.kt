package dev.loong.engine.conformance

import dev.loong.engine.workspace.Workspace
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 字符串字面量转义回归：Lexer 只校验不反转义，运行期值由 codegen 的 cookString
 * 统一烹制（此前 "a\nb" 的值是字面反斜杠——len() == 4 而非 3）。同时覆盖
 * 标准库 split/splitLines 在转义输入下的行为。
 */
class StringEscapeTest {

    @Test
    fun `escapes are cooked to real runtime values`() {
        val src = """
            module demo.esc

            /// Newline escape length.
            public function nlLen() -> Int {
                return len("a\nb")
            }

            /// Tab escape length.
            function tabLen() -> Int {
                return len("a\tb")
            }

            /// Quote and backslash escapes.
            function quoteBackslash() -> String {
                return "say \"hi\" \\ done"
            }

            /// Unicode escape.
            function uni() -> String {
                return "\u0041B"
            }

            test "nlLen" target: demo.esc.nlLen {
                assert(nlLen() == 3)
            }
        """.trimIndent()
        val compiled = dev.loong.engine.LoongCompiler.compile(src)
        assertTrue(
            compiled.diagnostics.isEmpty(),
            compiled.diagnostics.joinToString { "${it.code}: ${it.message}" },
        )
        val loader = object : ClassLoader() {
            fun load(internal: String, bytes: ByteArray): Class<*> =
                defineClass(internal.replace('/', '.'), bytes, 0, bytes.size)
        }
        val host = loader.load(compiled.hostClassName, compiled.classes.getValue(compiled.hostClassName.replace('.', '/')))
        assertEquals(3L, host.methods.first { it.name == "nlLen" }.invoke(null))
        assertEquals(3L, host.methods.first { it.name == "tabLen" }.invoke(null))
        assertEquals("say \"hi\" \\ done", host.methods.first { it.name == "quoteBackslash" }.invoke(null))
        assertEquals("AB", host.methods.first { it.name == "uni" }.invoke(null))
    }

    @Test
    fun `stdlib split and splitLines over escaped inputs`() {
        val ws = Workspace()
        ws.load(Path.of("../stdlib/loong/core.loong").toFile().readText().replace("\r\n", "\n"))
        ws.load(
            """
            module demo.sdbg

            depends loong.core

            /// SplitLines mirror.
            function sl(s: String) -> Array<String> {
                var t: String = s
                if loong.core.ends(t, "\n") {
                    t = loong.core.sub(t, 0, loong.core.len(t) - 1)
                }
                return loong.core.split(t, '\n')
            }

            test "sl" target: demo.sdbg.sl {
                let a: Array<String> = sl("one\ntwo\n")
                assert(loong.core.len(a) == 2)
                assert(a[0] == "one")
                assert(a[1] == "two")
            }
            """.trimIndent(),
        )
        fun lookup(name: String): Pair<dev.loong.engine.ast.NodeTable, dev.loong.engine.ast.NodeId>? {
            val key = if (name.contains('/')) name else "$name/v1"
            return runCatching { ws.tableOf(key) to ws.rootOf(key) }.getOrNull()
        }
        val src = dev.loong.engine.pkg.WorkspaceSource { lookup(it) }
        val deps = object : dev.loong.engine.pkg.DepSource {
            override fun versions(module: String): List<String> =
                if (lookup(module) != null) listOf("0.0.0") else emptyList()
            override fun fetch(module: String, version: String): dev.loong.engine.pkg.FetchedDep =
                src.fetch(module, version)
        }
        ws.linkModules(deps)
        val m = ws.module("demo.sdbg/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        val core = ws.module("loong.core/v1").classes
        val results = dev.loong.engine.testrunner.TestRunner(m.table, m.hostClassName)
            .runTests(m.classes + core)
        assertTrue(results.all { it.passed }, results.joinToString { "${it.name}: ${it.error}" })
    }
}
