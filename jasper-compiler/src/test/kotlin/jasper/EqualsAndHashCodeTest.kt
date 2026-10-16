package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q6b: `@EqualsAndHashCode` — value equality + consistent hash, generated via
 * the annotation processing phase (signatures) and late lowering (bodies).
 *
 * Every behavioral test goes through the real compile → JVM class loading →
 * reflective execution path.
 */
class EqualsAndHashCodeTest {

    private fun compile(source: String): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = Files.createTempDirectory("eqhashtest")
        val result = JasperCompiler().compileToDirWithIr(source.trimIndent(), outputDir)
        return result to outputDir
    }

    private fun <T> withCompiled(source: String, className: String, block: (Class<*>) -> T): T {
        val (result, outputDir) = compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
        )
        return try {
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            block(loader.loadClass(className))
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    private val pointSource = """
        package test;
        @EqualsAndHashCode
        class Point {
            var x: int32;
            var name: string;
            constructor(x: int32, name: string) { this.x = x; this.name = name; }
        }
    """

    @Test
    fun `instances with equal fields are equal`() {
        withCompiled(
            """
            $pointSource
            function main(): bool {
                var a = new Point(1, "hello");
                var b = new Point(1, "hello");
                return a.equals(b);
            }
            """,
            "test.main"
        ) { clazz ->
            assertEquals(true, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `instances with different fields are not equal`() {
        withCompiled(
            """
            $pointSource
            function main(): bool {
                var a = new Point(1, "hello");
                var b = new Point(2, "hello");
                var c = new Point(1, "world");
                return a.equals(b) || a.equals(c);
            }
            """,
            "test.main"
        ) { clazz ->
            assertEquals(false, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `equals is reflexive`() {
        withCompiled(
            """
            $pointSource
            function main(): bool {
                var a = new Point(7, "self");
                return a.equals(a);
            }
            """,
            "test.main"
        ) { clazz ->
            assertEquals(true, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `equals with a different class returns false and does not throw`() {
        withCompiled(
            """
            package test;
            @EqualsAndHashCode
            class Foo {
                var x: int32;
                constructor(x: int32) { this.x = x; }
            }
            class Bar {
                var x: int32;
                constructor(x: int32) { this.x = x; }
            }
            function main(): bool {
                var a = new Foo(1);
                var b = new Bar(1);
                return a.equals(b);
            }
            """,
            "test.main"
        ) { clazz ->
            // An exception inside main would surface as InvocationTargetException.
            assertEquals(false, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `equal instances have equal hash`() {
        withCompiled(
            """
            $pointSource
            function main(): bool {
                var a = new Point(1, "hello");
                var b = new Point(1, "hello");
                return a.hash() == b.hash();
            }
            """,
            "test.main"
        ) { clazz ->
            assertEquals(true, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `hash is stable across repeated calls on the same instance`() {
        withCompiled(
            """
            $pointSource
            function main(): bool {
                var a = new Point(3, "stable");
                return a.hash() == a.hash() && a.hash() == a.hash();
            }
            """,
            "test.main"
        ) { clazz ->
            assertEquals(true, clazz.getMethod("main").invoke(null))
        }
    }

    @Test
    fun `handwritten equals and hash are not overridden`() {
        withCompiled(
            """
            package test;
            @EqualsAndHashCode
            class Foo {
                var x: int32;
                constructor(x: int32) { this.x = x; }
                function equals(other: any): bool { return true; }
                function hash(): int32 { return 42; }
            }
            function main(): int32 {
                var a = new Foo(1);
                var b = new Foo(2);
                if (!a.equals(b)) { return -1; }
                return a.hash();
            }
            """,
            "test.main"
        ) { clazz ->
            // Handwritten equals ignores fields (returns true) and handwritten
            // hash wins over the synthesized one.
            assertEquals(42, clazz.getMethod("main").invoke(null))
        }
    }
}
