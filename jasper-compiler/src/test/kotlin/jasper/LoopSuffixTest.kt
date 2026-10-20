// ============================================================================
// LoopSuffixTest.kt —— G3：for-in / while 的循环后置块
// 真编译 + URLClassLoader 真执行；覆盖 entered/broke 两标志的互斥语义。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopSuffixTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "loop-suffix", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    private fun compile(source: String, name: String): Path {
        val dir = scratch(name)
        val result = JasperCompiler().compileToDirWithIr(source, dir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return dir
    }

    private fun runMain(source: String, name: String): Int {
        val dir = compile(source, name)
        URLClassLoader(arrayOf(dir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass("test.main")
            val main = clazz.methods.first { it.name == "main" && it.parameterCount == 0 }
            return (main.invoke(null) as Number).toInt()
        }
    }

    private fun result(source: String, name: String): JasperCompiler.CompileResult {
        return JasperCompiler().compileToDirWithIr(source, scratch(name))
    }

    @Test
    fun `while zero runs triggers onZeroRuns`() {
        val source = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                var out: int32 = 0;
                while (i > 0) { out = 1; } onZeroRuns { out = 7; }
                return out;
            }
        """.trimIndent()
        assertEquals(7, runMain(source, "while-zero"))
    }

    @Test
    fun `while normal completion triggers onComplete`() {
        val source = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                var out: int32 = 0;
                while (i < 3) { i = i + 1; out = out + 1; }
                onComplete { out = 10; }
                onZeroRuns { out = 20; }
                return out;
            }
        """.trimIndent()
        assertEquals(10, runMain(source, "while-complete"))
    }

    @Test
    fun `while break triggers onBreak only`() {
        val source = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                var out: int32 = 0;
                while (i < 5) {
                    i = i + 1;
                    if (i == 2) { break; }
                    out = out + 1;
                } onBreak { out = 30; }
                onComplete { out = 40; }
                onZeroRuns { out = 50; }
                return out;
            }
        """.trimIndent()
        assertEquals(30, runMain(source, "while-break"))
    }

    @Test
    fun `for-in zero and normal completion`() {
        val zero = """
            package test;
            public function main(): int32 {
                var xs: int32[] = [];
                var out: int32 = 0;
                for (x in xs) { out = 1; } onZeroRuns { out = 7; }
                return out;
            }
        """.trimIndent()
        assertEquals(7, runMain(zero, "for-zero"))

        val normal = """
            package test;
            public function main(): int32 {
                var xs: int32[] = [1, 2, 3];
                var out: int32 = 0;
                for (x in xs) { out = out + x; }
                onComplete { out = 100; }
                onZeroRuns { out = 200; }
                return out;
            }
        """.trimIndent()
        assertEquals(100, runMain(normal, "for-complete"))
    }

    @Test
    fun `for-in break triggers onBreak`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: int32[] = [1, 2, 3];
                var out: int32 = 0;
                for (x in xs) {
                    if (x == 2) { break; }
                    out = out + x;
                } onBreak { out = 30; }
                onComplete { out = 40; }
                return out;
            }
        """.trimIndent()
        assertEquals(30, runMain(source, "for-break"))
    }

    @Test
    fun `continue does not trigger onBreak`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: int32[] = [1, 2, 3];
                var out: int32 = 0;
                for (x in xs) {
                    if (x == 2) { continue; }
                    out = out + 1;
                } onComplete { out = out + 10; }
                onBreak { out = 99; }
                return out;
            }
        """.trimIndent()
        assertEquals(12, runMain(source, "for-continue"))
    }

    @Test
    fun `break outer only triggers outer onBreak`() {
        val source = """
            package test;
            public function main(): int32 {
                var out: int32 = 0;
                label outer: for (x in [1, 2]) {
                    for (y in [1, 2]) {
                        break outer;
                    } onBreak { out = out + 1; }
                } onBreak { out = out + 10; }
                return out;
            }
        """.trimIndent()
        assertEquals(10, runMain(source, "nested-outer"))
    }

    @Test
    fun `soft keywords remain identifiers outside loop suffix`() {
        val source = """
            package test;
            public function main(): int32 {
                var onComplete: int32 = 5;
                var onBreak: int32 = 2;
                return onComplete + onBreak;
            }
        """.trimIndent()
        assertEquals(7, runMain(source, "soft-identifiers"))
    }

    @Test
    fun `iterator for-in supports onComplete`() {
        val source = """
            package test;
            class It {
                private var i: int32;
                public constructor() { this.i = 0; }
                public function hasNext(): bool { return this.i < 2; }
                public function next(): int32 { this.i = this.i + 1; return this.i; }
            }
            public function main(): int32 {
                var out: int32 = 0;
                for (x in new It()) { out = out + x; }
                onComplete { out = 100; }
                onZeroRuns { out = 200; }
                return out;
            }
        """.trimIndent()
        assertEquals(100, runMain(source, "iterator-complete"))
    }

    @Test
    fun `classic for rejects loop suffix`() {
        val source = """
            package test;
            public function main(): int32 {
                for (var i = 0; i < 1; i++) { } onComplete { }
                return 0;
            }
        """.trimIndent()
        assertTrue(
            result(source, "classic-reject") is JasperCompiler.CompileResult.Error,
            "classic for must reject loop suffix"
        )
    }

    @Test
    fun `return exits before suffix`() {
        val source = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                while (i < 1) { i = i + 1; return 5; }
                onComplete { return 9; }
                return 0;
            }
        """.trimIndent()
        assertEquals(5, runMain(source, "return-skip"))
    }
}
