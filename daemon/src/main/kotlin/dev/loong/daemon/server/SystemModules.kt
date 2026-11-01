package dev.loong.daemon.server

import dev.loong.engine.workspace.Workspace

/**
 * 系统模块：随 daemon 内置的标准库（loong.core / loong.io / loong.net）。
 *
 * - 源码经 gradle（daemon build.gradle.kts processResources）从 `stdlib` 目录
 *   复制进 jar 资源 `stdlib` 包，与 engine 测试同源；
 * - 启动时确保装载（先恢复用户模块、再补系统模块，ID 分配互不冲突）；
 * - **不持久化**：每次启动从资源重新装载——标准库随 daemon 升级即生效，
 *   数据库里不存它的 AST 快照（persist 时过滤）；
 * - **不可删除、不可被 create_module 同名覆盖**：它是项目环境的一部分，
 *   不是项目状态；list_modules 可见（消费方 depends 它即用）。
 */
internal object SystemModules {

    /** 资源名 → 工作区模块名（装载顺序即列表顺序）。 */
    private val MODULES = linkedMapOf(
        "core.loong" to "loong.core/v1",
        "io.loong" to "loong.io/v1",
        "net.loong" to "loong.net/v1",
    )

    private val NAMES = MODULES.values.toSet()

    fun isSystem(name: String): Boolean = NAMES.contains(name)

    fun names(): List<String> = MODULES.values.toList()

    private fun source(res: String): String =
        requireNotNull(SystemModules::class.java.getResourceAsStream("/stdlib/$res")) {
            "stdlib resource missing: /stdlib/$res (daemon build must copy stdlib/loong)"
        }.readBytes().decodeToString().replace("\r\n", "\n")

    /** 装载缺失的系统模块；返回本次装载的模块名。已存在（同名）则跳过不覆盖。 */
    fun ensureLoaded(workspace: Workspace): List<String> {
        val loaded = ArrayList<String>()
        for ((res, name) in MODULES) {
            if (name in workspace.moduleNames()) continue
            val m = workspace.load(source(res))
            check(m.name == name) { "stdlib $res declared module ${m.name}, expected $name" }
            loaded.add(name)
        }
        return loaded
    }

    /** 从源码文本预读模块名（create_module 的系统模块覆盖防线；缺省回 null）。 */
    fun declaredName(source: String): String? =
        Regex("""(?m)^\s*module\s+([A-Za-z0-9_.]+)\s*$""")
            .find(source)?.groupValues?.get(1)
            ?.let { if (it.contains('/')) it else "$it/v1" }
}
