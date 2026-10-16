package jasper

import jasper.compiler.JasperCompiler
import jasper.stdlib.StdLib
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real compile-and-execute tests for the Jasper standard library v1, first
 * phase: the function-object protocol family (`Mapper`, `Predicate`, `Reducer`,
 * `Comparator`, `Action`), the `Error` hierarchy, and the iteration protocol.
 *
 * Each protocol test compiles the standard library together with a user source,
 * instantiates the protocol with a lambda, invokes it, and asserts the result.
 * Output is written under the module's own `build/` directory (never an
 * external temp path).
 */
class StdlibTest {

    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "tmp", "stdlib-tests", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** Compiles [source] with the stdlib, invokes `std.main.main()`, captures stdout. */
    private fun runMain(name: String, source: String): Pair<Any?, String> {
        val outputDir = freshOutputDir(name)
        try {
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), outputDir)
            assertTrue(
                result is JasperCompiler.CompileResult.Success,
                "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
            )

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("std.main")
            val method = clazz.getMethod("main")

            val originalOut = System.out
            val captured = ByteArrayOutputStream()
            val value = try {
                System.setOut(PrintStream(captured))
                method.invoke(null)
            } finally {
                System.setOut(originalOut)
            }
            return value to captured.toString()
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Mapper protocol is implemented by a lambda and applied`() {
        val (value, _) = runMain(
            "mapper",
            """
            package std;
            public function main(): int32 {
                var m: Mapper<int32, int32> = func(a: int32): int32 { return a + 1; };
                return m.apply(5);
            }
            """.trimIndent()
        )
        assertEquals(6, value, "Mapper.apply should add one")
    }

    @Test
    fun `Reducer protocol folds two values`() {
        val (value, _) = runMain(
            "reducer",
            """
            package std;
            public function main(): int32 {
                var r: Reducer<int32, int32> = lambda (acc: int32, x: int32) { return acc + x; };
                return r.reduce(3, 4);
            }
            """.trimIndent()
        )
        assertEquals(7, value, "Reducer.reduce should sum accumulator and value")
    }

    @Test
    fun `Comparator protocol orders two values`() {
        val (value, _) = runMain(
            "comparator",
            """
            package std;
            public function main(): int32 {
                var c: Comparator<int32> = lambda (a: int32, b: int32) { return a - b; };
                return c.compare(5, 3);
            }
            """.trimIndent()
        )
        assertEquals(2, value, "Comparator.compare should return a - b")
    }

    @Test
    fun `Predicate protocol tests true and false`() {
        val (value, _) = runMain(
            "predicate",
            """
            package std;
            public function main(): int32 {
                var p: Predicate<int32> = lambda (x: int32) { return x > 0; };
                var hits: int32 = 0;
                if (p.test(5)) { hits = hits + 1; }
                if (!p.test(0 - 3)) { hits = hits + 10; }
                return hits;
            }
            """.trimIndent()
        )
        assertEquals(11, value, "Predicate.test should classify positive and non-positive inputs")
    }

    @Test
    fun `Action protocol is invoked for its side effect`() {
        val (value, stdout) = runMain(
            "action",
            """
            package std;
            public function main(): int32 {
                var a: Action<int32> = lambda (t: int32) { println(t); };
                a.accept(9);
                return 0;
            }
            """.trimIndent()
        )
        assertEquals(0, value, "Action-using main should complete")
        assertEquals("9" + System.lineSeparator(), stdout, "Action.accept should print its argument")
    }

    @Test
    fun `Error is thrown caught and carries its message`() {
        val (value, _) = runMain(
            "error",
            """
            package std;
            public function main(): string {
                try {
                    throw new RecoverableError("boom");
                } catch (e: Error) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        assertEquals("boom", value, "Caught Error should expose the thrown message")
    }

    @Test
    fun `FatalError is catchable through the Error base class`() {
        val (value, _) = runMain(
            "fatal-error",
            """
            package std;
            public function main(): string {
                try {
                    throw new FatalError("fatal");
                } catch (e: Error) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        assertEquals("fatal", value, "FatalError should be catchable as Error")
    }

    @Test
    fun `Iterator protocol drives for-in traversal`() {
        val (value, _) = runMain(
            "iterator",
            """
            package std;
            public class IntRange implements Iterator<int32> {
                private var n: int32;
                private var i: int32;
                public constructor(n: int32) { this.n = n; this.i = 0; }
                public function hasNext(): bool { return this.i < this.n; }
                public function next(): int32 {
                    var v: int32 = this.i;
                    this.i = this.i + 1;
                    return v;
                }
            }
            public function main(): int32 {
                var r: IntRange = new IntRange(4);
                var sum: int32 = 0;
                for (x in r) {
                    sum = sum + x;
                }
                return sum;
            }
            """.trimIndent()
        )
        assertEquals(6, value, "for-in over an Iterator should visit 0+1+2+3")
    }

    @Test
    fun `standard library bundles every declared source`() {
        val sources = StdLib.sources()
        assertEquals(StdLib.FILES.toSet(), sources.keys, "StdLib.sources() should expose every declared file")
        for (file in StdLib.FILES) {
            val text = sources.getValue(file)
            assertTrue(text.contains("package std;"), "$file should declare package std")
            assertTrue(text.isNotBlank(), "$file should not be empty")
        }
    }
}
