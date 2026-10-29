plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "jasper"
version = "0.1.0"

repositories {
    mavenCentral()
}

// The ANTLR tool used to generate the parser is resolved from Maven Central so
// that grammar generation works on any machine (the previous build hardcoded an
// absolute path to a jar on one developer's Windows box).
val antlrTool: Configuration by configurations.creating

dependencies {
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
    testImplementation(kotlin("test"))
    // 一致性套件使用 JUnit Jupiter 的动态测试；Vintage 引擎继续承载既有的
    // kotlin-test/JUnit 4 测试，确保原有测试全部照常执行。
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    // 既有部分测试直接 import org.junit.Test / org.junit.Assume，必须继续提供 JUnit 4 API。
    testImplementation("junit:junit:4.13.2")
    // S4-C 运行时源码验证链：Lincheck（并发交错模型检查）与 Jazzer（覆盖引导 fuzz）。
    testImplementation("org.jetbrains.kotlinx:lincheck:2.39")
    testImplementation("com.code-intelligence:jazzer-junit:0.30.0")
    add(antlrTool.name, "org.antlr:antlr4:4.13.2")
}

// Lincheck 模型检查单独一个测试 JVM：Lincheck 只经系统类加载器读取 / 查找被插桩类，Jasper 编译
// 产物必须在系统类路径上才会被插桩（否则 std 运行时类静默不插桩，模型检查只覆盖 Kotlin 驱动层）。
// 该目录在 JVM 启动前建好、启动后由测试写入类文件（目录类路径按需查找）；放在独立 JVM 里，
// 避免普通测试经父加载器委派拿到这里的 std 类。test 依赖本任务，./gradlew test 仍运行全部测试。
val lincheckRuntimeDir = layout.buildDirectory.dir("lincheck-runtime")
val lincheckTest = tasks.register<Test>("lincheckTest") {
    description = "Runs Lincheck model checks with the compiled Jasper runtime on the system classpath"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + files(lincheckRuntimeDir)
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    filter { includeTestsMatching("*LincheckTest") }
    systemProperty("jasper.lincheck.runtimeDir", lincheckRuntimeDir.get().asFile.absolutePath)
    doFirst {
        val dir = lincheckRuntimeDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
    }
}

tasks.test {
    // 同时运行 JUnit Jupiter（动态一致性测试）与 Vintage（既有 JUnit 4 测试）。
    useJUnitPlatform()
    // Lincheck 与 Jazzer 在测试 JVM 内动态挂载插桩代理；JDK 21 需显式允许。
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Lincheck 模型检查由 lincheckTest 在独立 JVM 中运行（见上）。
    filter { excludeTestsMatching("*LincheckTest") }
    dependsOn(lincheckTest)
}

application {
    mainClass.set("jasper.compiler.JasperCompilerKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

val generatedAntlrDir = layout.buildDirectory.dir("generated-src/antlr/main")

val generateAntlrGrammar = tasks.register<JavaExec>("generateGrammarSource") {
    description = "Generate ANTLR lexer and parser from .g4 files"
    group = "build"

    classpath = antlrTool
    mainClass.set("org.antlr.v4.Tool")

    val g4Dir = file("src/main/antlr")
    val outDir = generatedAntlrDir.get().asFile

    args(
        "-o", outDir.absolutePath,
        "-lib", g4Dir.absolutePath,
        "-visitor",
        "-no-listener",
        "-package", "jasper.parser",
        file("$g4Dir/JasperLexer.g4").absolutePath,
        file("$g4Dir/JasperParser.g4").absolutePath
    )

    inputs.dir(g4Dir)
    outputs.dir(outDir)
}

kotlin.sourceSets["main"].kotlin.srcDir(generatedAntlrDir)
java.sourceSets["main"].java.srcDir(generatedAntlrDir)

tasks.named("compileKotlin") {
    dependsOn(generateAntlrGrammar)
}

tasks.named("compileTestKotlin") {
    dependsOn(generateAntlrGrammar)
}
