package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compiles and runs the JDBC-shaped language probe under `probes/jdbc/`.
 *
 * This test is the acceptance gate for the probe: it proves that the probe
 * source actually compiles through the real pipeline (ANTLR -> AST -> checker ->
 * Kotlin IR -> JVM bytecode) and that the in-memory fake JDBC provider runs.
 *
 * Output is written under this module's own `build/` directory (never an
 * external temp path).
 */
class JdbcProbeTest {

    private fun probesDir(): Path {
        val candidates = listOf(Paths.get("../probes/jdbc"), Paths.get("probes/jdbc"))
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("probes/jdbc not found from ${Paths.get("").toAbsolutePath()}")
    }

    private fun probeSources(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Files.list(probesDir()).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jas") }
                .sorted()
                .forEach { p -> out[p.fileName.toString()] = String(Files.readAllBytes(p)) }
        }
        return out
    }

    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "tmp", "jdbc-probe", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    @Test
    fun `jdbc probe compiles and the in-memory provider runs`() {
        val sources = probeSources()
        assertTrue(sources.isNotEmpty(), "probes/jdbc should contain at least one .jas file")

        val outputDir = freshOutputDir("run")
        val result = JasperCompiler().compileAllWithStdlib(sources, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "JDBC probe failed to compile: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )

        val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
        val mainValue = loader.loadClass("std.main").getMethod("main").invoke(null)
        assertEquals(21, mainValue, "in-memory JDBC scenario should return sum(10)+inserted(7)+updated(3)+caught(1)")

        val meta = loader.loadClass("std.buildMeta").getMethod("buildMeta").invoke(null) as java.util.HashMap<*, *>
        assertEquals(2, meta.size, "dict literal should build a two-entry map")
        assertEquals(1, meta["driver"], "dict literal should keep its values")
        assertEquals(2, meta["jdbc"], "dict literal should keep its values")

        // Evidence record for FINDINGS.md: interface constants are parsed but
        // silently dropped by the AST builder (no field is emitted).
        val statementFields = loader.loadClass("std.Statement").declaredFields.joinToString(",") { it.name }

        Files.createDirectories(Paths.get("build", "tmp"))
        Files.write(
            Paths.get("build", "tmp", "jdbc-probe-report.txt"),
            buildString {
                appendLine("probe-files=${sources.keys.sorted().joinToString(",")}")
                appendLine("main-result=$mainValue")
                appendLine("dict-size=${meta.size}")
                appendLine("Statement.declaredFields=[$statementFields]")
            }.toByteArray()
        )
    }
}
