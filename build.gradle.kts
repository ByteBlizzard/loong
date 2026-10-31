plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// `gradle run`：前台起 daemon（含 Web UI/API，端口随机分配）。MCP 由发布包的
// loong-mcp 桥提供（ensure daemon 后以 stdio 挂接，见 ADR-0006）。
tasks.register<JavaExec>("run") {
    group = "application"
    description = "前台启动 Loong daemon（Web UI + HTTP API）；项目目录用 -Pproject=<dir> 指定"
    mainClass.set("dev.loong.cli.UpKt")
    classpath = project(":cli").the<SourceSetContainer>()["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    // -Pproject=<dir> 透传为 --project <dir>。项目目录缺省本是 CWD，但 gradle run 的
    // CWD 是仓库根——为免产物散进仓库，缺省固定为 ./loong-project 子目录
    args("--project", providers.gradleProperty("project").getOrElse("loong-project"))
}

// ===================== 发布包（dist）=====================
// `gradle dist` 一次产出两份：
//   build/dist/loong/                     展开发布目录（进去即可直接测试）
//   build/distributions/loong-<版本>.zip   压缩发布包（解压到任何目录即可用）
//
// 包结构（约定）：脚本就在发布目录根下（loong / loong-up / loongc 的 sh 与 cmd 成对），
// 所有 jar 合并为唯一的 loong.jar；脚本一律 `java -jar loong.jar …`，
// Main-Class=LaunchKt 统一分发——无参数即起 daemon + Web UI + MCP。脚本模板在 packaging/。

val cliProject = project(":cli")

val distDir = tasks.register<Sync>("distDir") {
    group = "distribution"
    description = "组卷展开发布目录 build/dist/loong"
    into(layout.buildDirectory.dir("dist"))

    // 所有模块与依赖 → 唯一可执行 jar
    from(cliProject.tasks.named("fatJar")) {
        rename { "loong.jar" }
        into("loong")
    }
    // 根目录脚本与说明
    from(rootProject.file("packaging")) {
        into("loong")
    }
}

val distZip = tasks.register<Zip>("distZip") {
    group = "distribution"
    description = "把展开发布目录压缩为 build/distributions/loong-<版本>.zip"
    archiveFileName.set("loong-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(distDir)
    // zip 内保留 Unix 可执行位（解压即用，无需 chmod）；Gradle 9 起 FileCopyDetails
    // 只剩 permissions{} API（旧 setMode/fileMode 已移除）。单一命令：只有 loong 脚本。
    eachFile {
        if (relativePath.pathString == "loong/loong") {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.register("dist") {
    group = "distribution"
    description = "生成展开发布目录 + zip 发布包"
    dependsOn(distDir, distZip)
}

// Java 编译目标与 Kotlin jvmTarget 对齐（JVM 21 基线，plan.md §3）。
// Kotlin 侧各模块在自身 build.gradle.kts 中设置。
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
    }
}
