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
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
    testImplementation(kotlin("test"))
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

val antlrJar = file("C:\\Users\\admin\\AppData\\Local\\Temp\\opencode\\antlr-4.13.2-complete.jar")

val generateAntlrGrammar = tasks.register<JavaExec>("generateGrammarSource") {
    description = "Generate ANTLR lexer and parser from .g4 files"
    group = "build"

    classpath = files(antlrJar)
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
