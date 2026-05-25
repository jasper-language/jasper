package jasper

import jasper.compiler.*
import org.junit.Test
import org.junit.Assume
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.test.assertTrue

class GraalVmNativeImageTest {

    @Test
    fun `simple jasper program compiles to native image and runs`() {
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val nativeImageCmd = if (isWin) "native-image.cmd" else "native-image"
        val whichCmd = if (isWin) "where" else "which"

        val isAvailable = try {
            val proc = ProcessBuilder(whichCmd, nativeImageCmd).start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }

        Assume.assumeTrue("GraalVM native-image not on PATH, skipping test", isAvailable)

        val tempDir = Files.createTempDirectory("native-image-test")
        try {
            val classesDir = tempDir.resolve("classes")
            Files.createDirectories(classesDir)

            val source = """
                package test;
                function main(): void {}
            """.trimIndent()

            val result = JasperCompiler().compileToDirWithIr(source, classesDir)
            assertTrue(result is JasperCompiler.CompileResult.Success,
                "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message}")

            val classFiles = Files.walk(classesDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .toList()
            assertTrue(classFiles.isNotEmpty(), "No .class files generated")

            val jarFile = tempDir.resolve("app.jar")
            val manifest = Manifest()
            manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
            manifest.mainAttributes.put(Attributes.Name.MAIN_CLASS, "test.main")

            JarOutputStream(Files.newOutputStream(jarFile), manifest).use { jos ->
                for (classFile in classFiles) {
                    val entryName = classesDir.relativize(classFile).toString().replace('\\', '/')
                    jos.putNextEntry(JarEntry(entryName))
                    Files.copy(classFile, jos)
                    jos.closeEntry()
                }
            }

            val nativeImageDir = tempDir.resolve("native-image-output")
            Files.createDirectories(nativeImageDir)
            val buildLog = ByteArrayOutputStream()
            val buildProc = ProcessBuilder(
                nativeImageCmd,
                "-jar", jarFile.toAbsolutePath().toString(),
                "--no-fallback",
                "--report-unsupported-elements-at-runtime",
                nativeImageDir.resolve("app").toAbsolutePath().toString()
            )
                .redirectErrorStream(true)
                .start()
            buildProc.inputStream.transferTo(buildLog)
            val buildExit = buildProc.waitFor()
            assertTrue(buildExit == 0,
                "native-image build failed (exit $buildExit):\n${buildLog.toString(Charsets.UTF_8)}")

            val binary = nativeImageDir.resolve(if (isWin) "app.exe" else "app")
            val runLog = ByteArrayOutputStream()
            val runProc = ProcessBuilder(binary.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
            runProc.inputStream.transferTo(runLog)
            val runExit = runProc.waitFor()

            assertTrue(runExit == 0,
                "Native binary exited with code $runExit:\n${runLog.toString(Charsets.UTF_8)}")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
