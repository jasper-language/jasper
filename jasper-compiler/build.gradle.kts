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
    add(antlrTool.name, "org.antlr:antlr4:4.13.2")
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
