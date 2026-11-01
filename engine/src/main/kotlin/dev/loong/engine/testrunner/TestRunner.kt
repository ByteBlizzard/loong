package dev.loong.engine.testrunner

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable
import java.nio.file.Path

/** 单个 test 块的运行结果，按 Test 节点 ID 索引（design §8.2 测试运行器）。 */
class TestResult(
    val testNodeId: NodeId,
    val name: String,
    val passed: Boolean,
    val error: String?,
)

/**
 * 进程内测试运行器：加载模块字节码，反射调用 `$test$<节点ID>` 方法。
 * 测试失败 = 抛出的任何 Throwable（Loong 无异常，运行时错误如除零即失败）。
 * 跨模块支持：[deps] 为依赖闭包模块（含系统模块）的字节码，与被测模块同一
 * 加载器；[extraClasspath] 为额外 classpath 入口（注册表 classes 目录、三方 jar），
 * 以 URLClassLoader 挂在父链上（不能解析被测模块自身的类）。
 * 增量测试：[subtreeHash] 供调用方缓存被测函数内容指纹，未变则跳过。
 */
class TestRunner(private val table: NodeTable, private val hostClassName: String) {

    fun runTests(
        classes: Map<String, ByteArray>,
        deps: Map<String, ByteArray> = emptyMap(),
        extraClasspath: List<Path> = emptyList(),
    ): List<TestResult> {
        if (classes.isEmpty()) return emptyList()
        // extraClasspath 的 URLClassLoader 由本次调用私有：结束后关闭释放 jar 句柄
        //（Windows 上不关会锁住 libs/ 与缓存里的 jar 文件）
        val jarsLoader = if (extraClasspath.isEmpty()) null else java.net.URLClassLoader(
            extraClasspath.map { it.toUri().toURL() }.toTypedArray(),
            TestRunner::class.java.classLoader,
        )
        val parent = jarsLoader ?: TestRunner::class.java.classLoader
        try {
            return runLoaded(classes, deps, parent)
        } finally {
            jarsLoader?.let { runCatching { it.close() } }
        }
    }

    private fun runLoaded(
        classes: Map<String, ByteArray>,
        deps: Map<String, ByteArray>,
        parent: ClassLoader,
    ): List<TestResult> {
        val loader = ByteClassLoader(parent)
        classes.forEach { (internal, bytes) -> loader.define(internal, bytes) }
        deps.forEach { (internal, bytes) -> if (internal !in classes) loader.define(internal, bytes) }
        val host = try {
            loader.loadClass(hostClassName)
        } catch (e: Throwable) {
            return table.find(Kind.Test).map {
                TestResult(it, fs(it), false, "cannot load host class: ${e.message}")
            }
        }
        return table.find(Kind.Test).map { test ->
            val name = fs(test)
            val method = try {
                host.getDeclaredMethod("\$test\$${test.value}")
            } catch (e: NoSuchMethodException) {
                null
            }
            if (method == null) {
                TestResult(test, name, false, "test method was not generated")
            } else {
                try {
                    method.invoke(null)
                    TestResult(test, name, true, null)
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    TestResult(test, name, false, "${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
        }
    }

    /** 子树内容指纹（kind + 字段 + 子序，不含 ID 本身）：被测函数未变则跳过的依据。 */
    fun subtreeHash(id: NodeId): Long {
        var h = 1125899906842597L
        fun walk(nid: NodeId) {
            val node = table[nid]
            h = 31 * h + node.kind.ordinal.toLong()
            h = 31 * h + node.fields.entries.sortedBy { it.key }.joinToString("|") {
                it.key + "=" + it.value.toString()
            }.hashCode()
            node.kids.forEach { walk(it) }
        }
        walk(id)
        return h
    }

    private fun fs(id: NodeId): String =
        (table[id].fields["name"] as? FieldValue.Str)?.value ?: ""

    private class ByteClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(internalName: String, bytes: ByteArray) {
            defineClass(internalName.replace('/', '.'), bytes, 0, bytes.size)
        }
    }
}
