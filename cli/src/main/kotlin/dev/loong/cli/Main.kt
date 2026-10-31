package dev.loong.cli

import dev.loong.engine.CompileResult
import dev.loong.engine.LoongCompiler
import dev.loong.engine.testrunner.TestRunner
import dev.loong.runtime.LoongRuntime
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val EXIT_OK = 0
private const val EXIT_COMPILE = 1
private const val EXIT_TEST = 2
private const val EXIT_INTERNAL = 5

/** loong CLI（standalone 模式，design §8.7）：check/build/run/test + 稳定退出码。 */
fun main(args: Array<String>) = runCli(args)

fun runCli(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        println("usage: loong <command> <file.loong>")
        println("  check <file>   full pipeline check (exit 1 on diagnostics)")
        println("  build <file> [-d <dir>]   compile classes to dir (default loong-out)")
        println("  run <file>     compile and run main on a fresh JVM")
        println("  test <file>    compile and run inline tests (exit 2 on failure)")
        println("  --version")
        exitProcess(if (args.isEmpty()) EXIT_INTERNAL else EXIT_OK)
    }
    when (args[0]) {
        "--version" -> {
            println("loong 0.1.0 (runtime ${LoongRuntime.VERSION})")
            exitProcess(EXIT_OK)
        }

        "check" -> {
            daemonUrl()?.let { url -> daemonCheck(url, sourceOf(args)) }
            val r = doCompile(args)
            report(r.diagnostics)
            exitProcess(if (r.hasErrors) EXIT_COMPILE else EXIT_OK)
        }

        "build" -> {
            val r = doCompile(args)
            report(r.diagnostics)
            if (r.hasErrors) exitProcess(EXIT_COMPILE)
            val out = option(args, "-d")?.let { Path.of(it) } ?: Path.of("loong-out")
            r.classes.forEach { (internal, bytes) ->
                val f = out.resolve(internal + ".class")
                Files.createDirectories(f.parent)
                Files.write(f, bytes)
            }
            println("wrote ${r.classes.size} classes to $out")
            exitProcess(EXIT_OK)
        }

        "run" -> {
            val r = doCompile(args)
            report(r.diagnostics)
            if (r.hasErrors) exitProcess(EXIT_COMPILE)
            val out = Files.createTempDirectory("loong-run")
            r.classes.forEach { (internal, bytes) ->
                val f = out.resolve(internal + ".class")
                Files.createDirectories(f.parent)
                Files.write(f, bytes)
            }
            val javaBin = Path.of(
                System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
            ).toString()
            val cp = out.toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
            val proc = ProcessBuilder(javaBin, "-cp", cp, r.hostClassName).inheritIO().start()
            exitProcess(proc.waitFor())
        }

        "test" -> {
            daemonUrl()?.let { url -> daemonTest(url, sourceOf(args)) }
            val r = doCompile(args)
            report(r.diagnostics)
            if (r.hasErrors) exitProcess(EXIT_COMPILE)
            val results = TestRunner(r.table, r.hostClassName).runTests(r.classes)
            results.forEach {
                println(if (it.passed) "PASS ${it.name}" else "FAIL ${it.name} — ${it.error}")
            }
            println("${results.count { it.passed }}/${results.size} passed")
            exitProcess(if (results.all { it.passed }) EXIT_OK else EXIT_TEST)
        }

        else -> {
            System.err.println("unknown command '${args[0]}'")
            exitProcess(EXIT_INTERNAL)
        }
    }
}

private val http = HttpClient.newHttpClient()

/** daemon 发现：LOONGD_URL 指向且 /health 可达则返回，否则 null（standalone 回退）。 */
private fun daemonUrl(): String? {
    val url = System.getenv("LOONGD_URL") ?: return null
    return try {
        if (http.send(
                HttpRequest.newBuilder(URI("$url/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode() == 200
        ) {
            url
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun sourceOf(args: Array<String>): String =
    Files.readString(Path.of(args.getOrNull(1) ?: run {
        System.err.println("missing file argument")
        exitProcess(EXIT_INTERNAL)
    })).replace("\r\n", "\n")

private fun jsonStr(s: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

private fun daemonCheck(url: String, source: String) {
    val r = http.send(
        HttpRequest.newBuilder(URI("$url/modules"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"source\":" + jsonStr(source) + "\"}")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    println(r.body())
    exitProcess(if (r.statusCode() == 200) EXIT_OK else EXIT_COMPILE)
}

private fun daemonTest(url: String, source: String) {
    val load = http.send(
        HttpRequest.newBuilder(URI("$url/modules"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"source\":" + jsonStr(source) + "\"}")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    if (load.statusCode() != 200) {
        println(load.body())
        exitProcess(EXIT_COMPILE)
    }
    val mod = Regex("\"module\":\"([^\"]+)\"").find(load.body())!!.groupValues[1]
    val t = http.send(
        HttpRequest.newBuilder(
            URI("$url/modules/" + java.net.URLEncoder.encode(mod, Charsets.UTF_8) + "/test"),
        ).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    println(t.body())
    exitProcess(if (t.statusCode() == 200) EXIT_OK else EXIT_TEST)
}

private fun doCompile(args: Array<String>): CompileResult {
    val file = Path.of(args.getOrNull(1) ?: run {
        System.err.println("missing file argument")
        exitProcess(EXIT_INTERNAL)
    })
    if (!Files.exists(file)) {
        System.err.println("file not found: $file")
        exitProcess(EXIT_INTERNAL)
    }
    val source = Files.readString(file).replace("\r\n", "\n")
    return LoongCompiler.compile(source)
}

private fun option(args: Array<String>, flag: String): String? =
    args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun report(diags: List<dev.loong.engine.diagnostics.Diagnostic>) {
    diags.forEach { println("${it.severity} ${it.code}: ${it.message} (node ${it.nodeId.value})") }
    if (diags.isEmpty()) println("ok")
}
