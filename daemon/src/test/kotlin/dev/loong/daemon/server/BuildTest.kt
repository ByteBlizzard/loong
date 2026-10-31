package dev.loong.daemon.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 项目构建与模块生命周期验收：POST /build 产出自包含 jar（中间产物与 jar 都落项目目录
 * build/）、诊断未清零时 409、DELETE /modules 卸载并持久化、GET /modules/{name}/ast
 * 返回 .loongast 规范原始 AST。
 */
class BuildTest {

    private val http = HttpClient.newHttpClient()

    private fun call(port: Int, method: String, path: String, body: String? = null): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        when {
            body != null -> b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))

            method == "DELETE" -> b.DELETE()
            else -> b.GET()
        }
        val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }

    @Test
    fun `build produces self-contained runnable jar in project dir`() {
        val projectDir = Files.createTempDirectory("loong-build")
        val server = LoongdServer(0, projectDir.resolve("workspace.db"))
        val port = server.port()

        // 空项目自动预置 main/example（均零诊断）→ 可构建
        val (s, body) = call(port, "POST", "/build", "{}")
        assertEquals(200, s, body)
        assertTrue("\"mainClass\":\"main\"" in body, body)

        val jar = Path.of(Regex("\"jar\":\"([^\"]+)\"").find(body)!!.groupValues[1])
        assertTrue(Files.isRegularFile(jar), jar.toString())
        assertEquals(projectDir, jar.parent.parent) // <项目>/build/<项目名>.jar

        JarFile(jar.toFile()).use { jf ->
            assertEquals("main", jf.manifest.mainAttributes.getValue("Main-Class"))
            assertTrue(jf.getEntry("main.class") != null, "host class missing")
            assertTrue(jf.getEntry("dev/loong/runtime/Console.class") != null, "runtime shim missing")
            // 不携带宿主工具链运行时：runtime shim 是纯 Java（仅 JDK），任何 kotlin/ 类都算泄漏
            assertTrue(
                jf.entries().asSequence().none { it.name.startsWith("kotlin/") },
                "kotlin runtime classes leaked into build jar",
            )
        }
        // class 中间产物同样落项目目录
        assertTrue(Files.isRegularFile(projectDir.resolve("build/classes/main.class")))

        // 自包含验证：java -jar 直接跑出缺省入口的问候
        val javaBin = Path.of(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        ).toString()
        val proc = ProcessBuilder(javaBin, "-jar", jar.toString())
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        assertTrue("Hello , loong!" in out, out)

        server.close()
    }

    @Test
    fun `build refused 409 while any module has errors`() {
        val projectDir = Files.createTempDirectory("loong-build2")
        val server = LoongdServer(0, projectDir.resolve("workspace.db"))
        val port = server.port()

        // 给 main 插入缺 doc 的 public 函数 → E_MISSING_DOC / E_NO_TEST
        val (_, b1) = call(
            port, "POST", "/modules/main%2Fv1/edit",
            """{"op":"insert","parent":1,"index":1,"kind":"Function","fields":{"name":"g","visibility":"public","return_type":"Int"}}""",
        )
        val fnId = Regex("\"value\":(\\d+)").find(b1)!!.groupValues[1]

        val (s2, b2) = call(port, "POST", "/build", "{}")
        assertEquals(409, s2, b2)
        assertTrue("E_MISSING_DOC" in b2, b2)
        assertTrue("\"module\":\"main/v1\"" in b2, b2)

        // 恢复干净后可构建
        call(port, "POST", "/modules/main%2Fv1/edit", """{"op":"delete","id":$fnId}""")
        val (s3, b3) = call(port, "POST", "/build", "{}")
        assertEquals(200, s3, b3)

        server.close()
    }

    @Test
    fun `module delete persists and raw ast endpoint serves canonical loongast`() {
        val projectDir = Files.createTempDirectory("loong-life")
        val db = projectDir.resolve("workspace.db")
        val server = LoongdServer(0, db)
        val port = server.port()

        // 原始 AST：.loongast 规范形态（模块名 + 节点表 + schema_version）
        val (sa, ba) = call(port, "GET", "/modules/main%2Fv1/ast")
        assertEquals(200, sa)
        assertTrue("\"kind\":\"Module\"" in ba && "\"schema_version\":1" in ba, ba.take(200))

        // 卸载 example → 列表消失；未知模块 → 400
        val (sd, bd) = call(port, "DELETE", "/modules/example%2Fv1")
        assertEquals(200, sd, bd)
        val (_, list) = call(port, "GET", "/modules")
        assertTrue("example/v1" !in list, list)
        assertEquals(400, call(port, "DELETE", "/modules/nope%2Fv1").first)

        // 崩溃恢复路径：重启后删除仍生效（已落盘）
        server.close()
        LoongdServer(0, db).use { again ->
            val (_, list2) = call(again.port(), "GET", "/modules")
            assertTrue("example/v1" !in list2, list2)
        }
    }
}
