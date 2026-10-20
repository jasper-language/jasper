package jasper

import jasper.compiler.JasperCompiler
import jasper.compiler.SourceMode
import jasper.compiler.modeForFilename
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptModeTest {

    private val compiler = JasperCompiler()

    @Test
    fun `mode is selected by extension and explicit api only`() {
        assertEquals(SourceMode.Strict, modeForFilename("App.jas"))
        assertEquals(SourceMode.Script, modeForFilename("App.jass"))
        assertEquals(SourceMode.Script, modeForFilename("APP.JASS"))
        assertEquals(SourceMode.Strict, modeForFilename("App.jss"))
    }

    @Test
    fun `strict rejects bare statements while script accepts them`() {
        val source = """
            var value = 5 + 3;
            println(value);
        """.trimIndent()

        val strict = compiler.compile(source)
        assertIs<JasperCompiler.CompileResult.Error>(strict)
        assertTrue(strict.message.contains(".jass"), strict.message)

        assertIs<JasperCompiler.CompileResult.Success>(compiler.compileScript(source))
    }

    @Test
    fun `script supports package and imports before implicit entry`() {
        val source = """
            package demo.script;
            import std.io.Console;

            var value = 5 + 3;
            println(value);
        """.trimIndent()

        assertIs<JasperCompiler.CompileResult.Success>(compiler.compileScript(source))
    }

    @Test
    fun `script supports top level declarations mixed with executable statements`() {
        val source = """
            function twice(value: int32): int32 {
                return value * 2;
            }

            var value = twice(21);
            println(value);

            class Label {
                public var text: string = "ok";
            }
        """.trimIndent()

        val pipeline = compiler.compileToPipeline(source, SourceMode.Script)
        assertEquals(setOf("twice", "main", "Label"), pipeline.ast.declarations.mapNotNull {
            when (it) {
                is jasper.ast.JasFunction -> it.name
                is jasper.ast.JasClass -> it.name
                else -> null
            }
        }.toSet())
    }

    @Test
    fun `script variable must be explicitly declared`() {
        val result = compiler.compileScript(
            """
            a = 10;
            println(a);
            """.trimIndent()
        )
        assertIs<JasperCompiler.CompileResult.Error>(result)
    }

    @Test
    fun `var without type and initializer is rejected`() {
        val result = compiler.compileScript(
            """
            var a;
            a = 10;
            """.trimIndent()
        )
        assertIs<JasperCompiler.CompileResult.Error>(result)
        assertTrue(result.message.contains("requires an explicit type or an initializer"), result.message)
    }

    @Test
    fun `explicit type without initializer is allowed`() {
        val result = compiler.compileScript(
            """
            package demo;
            var a: int32;
            a = 10;
            println(a);
            """.trimIndent()
        )
        assertIs<JasperCompiler.CompileResult.Success>(result)
    }

    @Test
    fun `script implicit entry is emitted to bytecode and runnable`() {
        val outputDir = Files.createTempDirectory("jasper-script-mode-")
        try {
            val result = compiler.compileToDirWithIr(
                """
                package demo;
                var a: int32;
                a = 10;
                println(a);
                """.trimIndent(),
                outputDir,
                SourceMode.Script
            )
            assertIs<JasperCompiler.CompileResult.Success>(result)

            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                val script = loader.loadClass("demo.main")
                script.getMethod("main").invoke(null)
            }
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inferred variable type cannot change`() {
        val result = compiler.compileScript(
            """
            var a = 10;
            a = "hello";
            """.trimIndent()
        )
        assertIs<JasperCompiler.CompileResult.Error>(result)
        assertTrue(result.message.contains("TYPE"), result.message)
    }

    @Test
    fun `script is selected for jass compileAll input`() {
        val result = compiler.compile(
            mapOf(
                "Main.jass" to """
                    var value = 42;
                    println(value);
                """.trimIndent()
            )
        )
        assertIs<JasperCompiler.CompileResult.Success>(result)
    }

    @Test
    fun `script may import strict modules without changing their mode`() {
        val result = compiler.compile(
            linkedMapOf(
                "MathLib.jas" to """
                    package demo;
                    public function twice(value: int32): int32 {
                        return value * 2;
                    }
                """.trimIndent(),
                "Main.jass" to """
                    from "MathLib" import twice;
                    println(twice(21));
                """.trimIndent()
            )
        )
        assertIs<JasperCompiler.CompileResult.Success>(result)
    }

    @Test
    fun `strict module cannot import script unit`() {
        val result = compiler.compile(
            linkedMapOf(
                "Entry.jass" to """
                    public function helper(): int32 {
                        return 1;
                    }
                """.trimIndent(),
                "Main.jas" to """
                    from "Entry.jass" import helper;
                    function main(): int32 {
                        return helper();
                    }
                """.trimIndent()
            )
        )
        assertIs<JasperCompiler.CompileResult.Error>(result)
        assertTrue(result.message.contains("cannot import script unit"), result.message)
    }

    @Test
    fun `script mode does not propagate to bare strict input`() {
        val result = compiler.compile(
            linkedMapOf(
                "Entry.jass" to """
                    var value = 1;
                    println(value);
                """.trimIndent(),
                "Bad.jas" to """
                    println("strict");
                """.trimIndent()
            )
        )
        assertIs<JasperCompiler.CompileResult.Error>(result)
        assertTrue(result.message.contains("Top-level statements require"), result.message)
    }
}
