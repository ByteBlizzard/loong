import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.loong"

base { archivesName = "loongd" }

dependencies {
    implementation(project(":engine"))
    implementation(project(":runtime"))
    implementation(libs.h2)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
}

// 标准库源随 daemon 打包（SystemModules 启动装载为系统模块）
tasks.processResources {
    from(layout.projectDirectory.dir("../stdlib/loong")) {
        include("*.loong")
        into("stdlib")
    }
}
