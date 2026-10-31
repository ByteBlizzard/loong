import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.loong"
base { archivesName = "loong-cli" }

dependencies {
    implementation(project(":engine"))
    implementation(project(":runtime"))
    implementation(project(":daemon"))
    implementation(project(":mcp"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// 发布用单一可执行 jar：cli + engine/runtime/daemon/mcp + 全部第三方依赖（ASM/H2/kotlin-stdlib…）
// 合并为 loong-all.jar。Main-Class 是统一分发入口 LaunchKt（无参=起全栈，cli 子命令=编译器）。
// 签名文件与 module-info 必须剔除：重打包后签名已失效；各依赖的 module-info 会互相冲突。
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "把全部模块与依赖合并为单个可执行 jar（Main-Class=dev.loong.cli.LaunchKt）"
    archiveBaseName.set("loong")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.loong.cli.LaunchKt",
            "Implementation-Title" to "loong",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("**/module-info.class")
    }
}
