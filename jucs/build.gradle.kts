plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "jasper"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("jucs.cli.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

/*
 * 说明 / Note:
 * 本模块不依赖 jasper-compiler。依赖方向必须是 jasper-compiler -> jucs，
 * 这样将来 jasperc 迁移为 JUCS 命令时不会把命令运行时绑死进编译器。
 * This module must not depend on jasper-compiler: the dependency direction is
 * jasper-compiler -> jucs so the command runtime stays compiler-independent.
 */
