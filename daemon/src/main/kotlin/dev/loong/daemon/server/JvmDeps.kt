package dev.loong.daemon.server

import dev.loong.engine.json.Json
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock

/** 一次解析的结果：成功带 jar 清单，失败带可展示错误。 */
internal class ResolveResult(
    val ok: Boolean,
    val jars: List<Path> = emptyList(),
    val error: String? = null,
    val tool: String? = null,
)

/**
 * JVM 三方依赖管理（design §5.3 FFI / §6.2 的落地配套）。
 *
 * - 配置 `<项目>/jvm-deps.json`：`{"dependencies": ["group:artifact:version", ...]}`，
 *   纯文本、人机都可编辑，standalone CLI 也能读；
 * - 解析委托宿主构建工具（不自己实现 Maven 语义）：在 `<项目>/.loong/deps/`
 *   生成微型工程，PATH 上有 gradle 用 gradle（`writeClasspath` 任务），否则
 *   mvn（`dependency:build-classpath`）；产物 `classpath.txt` = jar 绝对路径列表；
 * - 手动兜底：`<项目>/libs` 目录下手放的 .jar 始终并入（离线/私有 jar 场景）；
 * - 消费方：java_class_info 反射、run_tests（TestRunner extraClasspath）、
 *   build_project（复制进 build/libs/ + manifest Class-Path）、编译期 external 校验。
 */
internal class JvmDeps(private val projectDir: Path) {

    private val configFile: Path = projectDir.resolve("jvm-deps.json")
    private val depsDir: Path = projectDir.resolve(".loong").resolve("deps")
    private val classpathFile: Path = depsDir.resolve("classpath.txt")
    private val libsDir: Path = projectDir.resolve("libs")

    /** 解析互斥（ProcessBuilder 跑 gradle/mvn 可能数秒，防并发踩踏产物文件）。 */
    private val lock = ReentrantLock()

    /** 配置中的坐标列表（无配置 = 空表）。 */
    fun list(): List<String> {
        if (!Files.exists(configFile)) return emptyList()
        return runCatching {
            val obj = Json.parse(Files.readString(configFile)) as? Json.Obj ?: return emptyList()
            (obj.map["dependencies"] as? Json.Arr)?.items?.mapNotNull { (it as? Json.Str)?.v } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun write(coords: List<String>) {
        if (coords.isEmpty() && !Files.exists(configFile)) return
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            Json.write(mapOf("dependencies" to coords)) + "\n",
        )
    }

    /** 当前生效的 classpath（libs/ 手放 jar + 解析产物），解析失败时仅 libs。 */
    fun jars(): List<Path> {
        val out = ArrayList<Path>()
        if (Files.isDirectory(libsDir)) {
            Files.list(libsDir).use { s ->
                s.filter { it.fileName.toString().endsWith(".jar") }.sorted().forEach { out.add(it) }
            }
        }
        if (Files.exists(classpathFile)) {
            // 按本机 pathSeparator 分割（产物由本机 gradle/mvn 生成；Windows 路径
            // 含盘符冒号，不能用 ':' 通吃分割）
            Files.readString(classpathFile).split(java.io.File.pathSeparatorChar)
                .filter { it.isNotBlank() && Files.exists(Path.of(it)) }
                .forEach { out.add(Path.of(it)) }
        }
        return out.distinct()
    }

    /** 供反射查询/测试运行用的类加载器（项目 jar 优先于 daemon 自身 classpath）。 */
    @Volatile
    private var cachedLoader: Pair<List<Path>, URLClassLoader>? = null

    fun loader(): URLClassLoader {
        // 缓存按 jar 清单失效：反复反射校验不反复开 jar 句柄（Windows 上未关闭的
        // loader 会锁住 jar 文件，替换依赖时无法落盘）
        val jars = jars()
        cachedLoader?.let { (key, l) -> if (key == jars) return l }
        val l = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        cachedLoader?.second?.let { old -> runCatching { old.close() } }
        cachedLoader = jars to l
        return l
    }

    /** 释放 jar 句柄（daemon 关停路径；Windows 上不关会锁住 libs/ 下的文件）。 */
    fun close() {
        cachedLoader?.second?.let { runCatching { it.close() } }
        cachedLoader = null
    }

    /** 是否已有成功解析产物（classpath.txt 存在）。 */
    fun resolved(): Boolean = Files.exists(classpathFile)

    fun add(coord: String): ResolveResult {
        require(coord.matches(COORD)) { "not a maven coordinate (group:artifact:version): $coord" }
        lock.lock()
        try {
            val next = (list() + coord).distinct().sorted()
            write(next)
            return resolve()
        } finally {
            lock.unlock()
        }
    }

    fun remove(coord: String): ResolveResult {
        lock.lock()
        try {
            val next = list().filter { it != coord }
            require(next.size != list().size) { "dependency not configured: $coord" }
            write(next)
            return resolve()
        } finally {
            lock.unlock()
        }
    }

    /** 重解析（生成工程 → 跑工具 → 读 classpath.txt）。无坐标时清空产物。 */
    fun resolve(): ResolveResult {
        lock.lock()
        try {
            val coords = list()
            if (coords.isEmpty()) {
                Files.deleteIfExists(classpathFile)
                return ResolveResult(ok = true, jars = jars(), tool = "none")
            }
            Files.deleteIfExists(classpathFile)
            Files.createDirectories(depsDir)
            val (tool, cmd) = pickTool()
                ?: return ResolveResult(
                    ok = false, jars = jars(),
                    error = "no gradle or maven found on PATH — install one, or drop jars into $libsDir",
                )
            return when (tool) {
                "gradle" -> resolveWithGradle(cmd, coords)
                else -> resolveWithMaven(cmd, coords)
            }
        } finally {
            lock.unlock()
        }
    }

    // ---------- 工具选择 ----------

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * PATH 上找 gradle，其次 mvn。返回（工具名, 可执行命令名）。
     * Windows 上必须用带 .bat/.cmd 后缀的命令名——CreateProcess 不自动解析
     * 批处理后缀，裸 "gradle" 会 CreateProcess error=2。
     */
    private fun pickTool(): Pair<String, String>? {
        val gradleCmd = if (isWindows) "gradle.bat" else "gradle"
        val mvnCmd = if (isWindows) "mvn.cmd" else "mvn"
        if (canRun(gradleCmd)) return "gradle" to gradleCmd
        if (canRun(mvnCmd)) return "maven" to mvnCmd
        return null
    }

    private fun canRun(cmd: String): Boolean = runCatching {
        ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    // ---------- gradle 路径 ----------

    private fun resolveWithGradle(cmd: String, coords: List<String>): ResolveResult {
        Files.writeString(
            depsDir.resolve("settings.gradle.kts"),
            """
            rootProject.name = "loong-jvmdeps"
            """.trimIndent() + "\n",
        )
        val deps = coords.joinToString("\n") { "    implementation(\"$it\")" }
        Files.writeString(
            depsDir.resolve("build.gradle.kts"),
            """
            import java.io.File

            plugins { java }

            repositories { mavenCentral() }

            dependencies {
            $deps
            }

            val cpFile = layout.projectDirectory.file("classpath.txt")

            tasks.register("writeClasspath") {
                outputs.file(cpFile)
                doLast {
                    val cp = sourceSets.named("main").get().runtimeClasspath.joinToString(File.pathSeparator)
                    cpFile.asFile.writeText(cp)
                }
            }
            """.trimIndent() + "\n",
        )
        val proc = ProcessBuilder(
            cmd, "--no-configuration-cache", "--console=plain", "-q", "--project-dir",
            depsDir.toString(), "writeClasspath",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        val code = proc.waitFor()
        return finish(code, out, "gradle")
    }

    // ---------- maven 路径 ----------

    private fun resolveWithMaven(cmd: String, coords: List<String>): ResolveResult {
        val xml = StringBuilder()
        xml.append(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.loong.deps</groupId>
              <artifactId>loong-jvmdeps</artifactId>
              <version>1</version>
              <packaging>pom</packaging>
            """.trimIndent(),
        )
        xml.append("\n  <dependencies>\n")
        for (c in coords) {
            val (g, a, v) = c.split(":")
            xml.append(
                """
                <dependency>
                  <groupId>$g</groupId>
                  <artifactId>$a</artifactId>
                  <version>$v</version>
                </dependency>
                """.trimIndent().prependIndent("    "),
            ).append('\n')
        }
        xml.append("  </dependencies>\n</project>\n")
        Files.writeString(depsDir.resolve("pom.xml"), xml.toString())
        val proc = ProcessBuilder(
            cmd, "-q", "-f", depsDir.resolve("pom.xml").toString(),
            "dependency:build-classpath",
            "-Dmdep.outputFile=${classpathFile.toAbsolutePath()}",
            "-Dmdep.pathSeparator=${java.io.File.pathSeparator}",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        val code = proc.waitFor()
        return finish(code, out, "maven")
    }

    private fun finish(code: Int, output: String, tool: String): ResolveResult {
        if (code == 0 && Files.exists(classpathFile)) {
            return ResolveResult(ok = true, jars = jars(), tool = tool)
        }
        return ResolveResult(
            ok = false, jars = jars(), tool = tool,
            error = "$tool resolve failed (exit $code):\n${output.trim().takeLast(2000)}",
        )
    }

    companion object {
        /** group:artifact:version（version 允许 [1.0,2.0) 类区间——交给工具解析）。 */
        private val COORD = Regex("""^[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+:.+$""")

        /** 把解析出的 jar 复制进 build/libs/，返回相对 Class-Path 条目（供 manifest）。 */
        fun copyIntoBuild(jars: List<Path>, buildDir: Path): List<String> {
            if (jars.isEmpty()) return emptyList()
            val libs = buildDir.resolve("libs")
            Files.createDirectories(libs)
            val entries = ArrayList<String>()
            for (jar in jars) {
                val name = uniqueName(libs, jar)
                Files.copy(jar, libs.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                entries.add("libs/$name")
            }
            return entries
        }

        /** 同名不同源（libs 手放与解析产物同名、或同名不同版）：以源路径哈希为前缀。 */
        private fun uniqueName(libs: Path, jar: Path): String {
            val name = jar.fileName.toString()
            if (!Files.exists(libs.resolve(name))) return name
            return "${jar.toAbsolutePath().hashCode().toUInt().toString(16)}-$name"
        }
    }
}
