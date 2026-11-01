package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * JVM 三方依赖（libs/ 手放 jar）+ external 编译期校验 + java_class_info 查询
 * 的端到端验证：
 * - libs/ 下的 jar 自动并入 classpath（无需 gradle/mvn）；
 * - external 声明在装载响应里就拿到 E_EXTERNAL_* 反射核对诊断（不用等运行期）；
 * - /jvm/class 列出三方类的 public API，附 loong 类型映射提示。
 */
class JvmDepsTest {

    @TempDir
    lateinit var project: Path

    private val client = HttpClient.newHttpClient()

    /** 造一个真实的三方 jar：demo.lib.TestHelper#greet(String)->String。 */
    private fun helperJar(): Path {
        val src = project.resolve("src/demo/lib/TestHelper.java")
        Files.createDirectories(src.parent)
        Files.writeString(
            src,
            """
            package demo.lib;

            public class TestHelper {
                public static String greet(String who) {
                    return "hi " + who;
                }
            }
            """.trimIndent(),
        )
        val out = project.resolve("classes")
        Files.createDirectories(out)
        val ok = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", out.toString(), src.toString(),
        )
        assertEquals(0, ok, "javac failed")
        val jar = project.resolve("libs").resolve("helper.jar")
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar), Manifest()).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("demo/lib/TestHelper.class"))
            Files.copy(out.resolve("demo/lib/TestHelper.class"), jos)
            jos.closeEntry()
        }
        return jar
    }

    @Test
    fun `libs jar feeds classpath verification and class info`() {
        helperJar()
        LoongdServer(0, project.resolve("ws.db")).use { server ->
            val port = server.port()

            // classpath 生效：jvmdeps 视图列出 libs jar
            val (_, dj) = get(port, "/jvmdeps")
            assertTrue("\"helper.jar\"" in dj, dj)

            // 类查询：三方类可见，public 静态方法与映射提示齐全
            val (_, ci) = get(port, "/jvm/class?fqn=demo.lib.TestHelper")
            assertTrue("\"found\":true" in ci, ci)
            assertTrue("\"greet\"" in ci, ci)
            assertTrue("\"java.lang.String\" to \"String\"" in ci || "Int32" in ci, ci)

            // 正确签名：装载即零诊断（反射核对通过），跨模块调用测试可跑
            val good = """
                module demo.use

                /// Greets via a third-party JVM helper.
                public function greet(who: String) -> String {
                    return jGreet(who: who)
                }

                external java "demo.lib.TestHelper" {
                    static function jGreet(who: String) -> String as "greet"
                }

                test "greet" target: demo.use.greet {
                    let r: String = greet(who: "loong")
                }
            """.trimIndent()
            val (gs, gb) = post(port, "/modules", """{"source":${jsonStr(good)}}""")
            assertEquals(200, gs, gb)
            assertTrue("\"diagnostics\":[]" in gb, gb)
            val (ts, tb) = post(port, "/modules/demo.use%2Fv1/test", "{}")
            assertEquals(200, ts, tb)
            assertTrue("\"passed\":1" in tb, tb)

            // 错误签名：装载响应当场给出 E_EXTERNAL_METHOD_NOT_FOUND（附可用重载）
            val bad = """
                module demo.bad

                external java "demo.lib.TestHelper" {
                    static function jGreet(n: Int32) -> String as "greet"
                }
            """.trimIndent()
            val (bs, bb) = post(port, "/modules", """{"source":${jsonStr(bad)}}""")
            assertEquals(200, bs, bb)
            assertTrue("E_EXTERNAL_METHOD_NOT_FOUND" in bb, bb)
            assertTrue("greet" in bb, bb)

            // 幻觉类：E_EXTERNAL_CLASS_NOT_FOUND
            val ghost = """
                module demo.ghost

                external java "com.example.no.Such" {
                    static function boom() -> Unit
                }
            """.trimIndent()
            val (_, gb2) = post(port, "/modules", """{"source":${jsonStr(ghost)}}""")
            assertTrue("E_EXTERNAL_CLASS_NOT_FOUND" in gb2, gb2)
        }
    }

    @Test
    fun `bad coordinate is rejected without resolving`() {
        LoongdServer(0, project.resolve("ws2.db")).use { server ->
            val (s, b) = post(server.port(), "/jvmdeps", """{"coordinate":"not-a-coordinate"}""")
            assertEquals(400, s, b)
            val (_, dj) = get(server.port(), "/jvmdeps")
            assertTrue("\"dependencies\":[]" in dj, dj)
        }
    }

    // ---------- HTTP 小件 ----------

    private fun get(port: Int, path: String): Pair<Int, String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { it.statusCode() to it.body() }

    private fun post(port: Int, path: String, body: String): Pair<Int, String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { it.statusCode() to it.body() }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
