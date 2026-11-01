package dev.loong.engine.stdlib

import dev.loong.engine.pkg.LocalRegistry
import dev.loong.engine.pkg.Packages
import dev.loong.engine.workspace.Workspace
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * loong.net 狗粮：HTTP/TCP 平台库以真实 Loong 代码编写。进程内测试覆盖 TCP
 * echo 回环（spawn + accept/send/recv）；HTTP happy path 用本地 HttpServer +
 * 子进程消费方（发布 loong.net 到临时注册表，走完整跨模块链路）。
 */
class StdlibNetTest {

    private fun netSource(): String =
        Path.of("../stdlib/loong/net.loong").toFile().readText().replace("\r\n", "\n")

    @Test
    fun `loong net compiles clean and its tests pass`() {
        val ws = Workspace()
        ws.load(netSource())
        val m = ws.module("loong.net/v1")
        assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })
        assertTrue(m.classes.isNotEmpty())

        val results = dev.loong.engine.testrunner.TestRunner(m.table, m.hostClassName).runTests(m.classes)
        assertEquals(10, results.size)
        assertTrue(results.all { it.passed }, results.joinToString { "${it.name}: ${it.error}" })
    }

    @Test
    fun `consumer depends on loong net and fetches http`() {
        val server = com.sun.net.httpserver.HttpServer.create(
            InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0,
        )
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/hello" -> "hi from server".toByteArray()
                "/echo" -> exchange.requestBody.readBytes()
                else -> "nope".toByteArray()
            }
            val status = if (exchange.requestURI.path == "/missing") 404 else 200
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.start()
        try {
            val port = server.address.port

            // 发布 loong.net 到临时注册表
            val lib = Workspace()
            lib.load(netSource())
            val libModule = lib.module("loong.net/v1")
            assertTrue(libModule.diagnostics.isEmpty(), libModule.diagnostics.joinToString { it.message })
            val registry = Files.createTempDirectory("loong-net-reg")
            Packages.publish(registry, lib.tableOf("loong.net/v1"), lib.rootOf("loong.net/v1"), "1.0.0", libModule.classes)

            val consumer = """
                module demo.http

                depends loong.net/v1 >= 1.0.0

                /// Entry.
                function main() -> Unit {
                    let base: String = "http://127.0.0.1:${'$'}PORT"
                    match loong.net.httpGet(concat(base, "/hello")) {
                        Result.Ok(v) => println(v)
                        Result.Err(e) => println(concat("ERR ", e))
                    }
                    match loong.net.httpPost(concat(base, "/echo"), "ping-body", "text/plain") {
                        Result.Ok(v) => println(v)
                        Result.Err(e) => println(concat("ERR ", e))
                    }
                    match loong.net.httpGet(concat(base, "/missing")) {
                        Result.Ok(v) => println(v)
                        Result.Err(e) => println(e)
                    }
                }
            """.trimIndent().replace("\$PORT", port.toString())

            val ws = Workspace()
            ws.load(consumer)
            ws.linkModules(LocalRegistry(registry))
            val m = ws.module("demo.http/v1")
            assertTrue(m.diagnostics.isEmpty(), m.diagnostics.joinToString { "${it.code}: ${it.message}" })

            val out = Files.createTempDirectory("loong-net-out")
            m.classes.forEach { (internal, bytes) ->
                val f = out.resolve(internal + ".class")
                Files.createDirectories(f.parent)
                Files.write(f, bytes)
            }
            val cp = (listOf(out.toString()) + ws.classpathDirs("demo.http/v1").map { it.toString() } +
                listOf(System.getProperty("java.class.path"))).joinToString(java.io.File.pathSeparator)
            val javaBin = System.getProperty("java.home") + "/bin/" +
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
            val proc = ProcessBuilder(javaBin, "-cp", cp, m.hostClassName)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.readBytes().decodeToString().replace("\r\n", "\n")
            assertEquals(0, proc.waitFor(), output)
            assertEquals("hi from server\nping-body\nHTTP 404: nope\n", output)
        } finally {
            server.stop(0)
        }
    }
}
