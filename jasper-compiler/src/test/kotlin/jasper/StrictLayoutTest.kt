package jasper

import jasper.compiler.CompileError
import jasper.compiler.CompileOptions
import jasper.compiler.JasperCompiler
import jasper.compiler.emitJasoMain
import jasper.stdlib.StdLib
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * strict 选项：一文件一类型（LANG-STRICT-LAYOUT-01）与兼容包装的行号（Q159）。
 *
 * 期望值都由用例自己写出或由生成器独立算出，不调用被测的 StrictLayout。
 */
class StrictLayoutTest {
    private val strict = JasperCompiler(CompileOptions(strict = true))
    private val loose = JasperCompiler()

    private fun codes(result: JasperCompiler.CompileResult): Set<String> {
        val error = assertIs<JasperCompiler.CompileResult.Error>(result)
        return error.errors.mapNotNullTo(sortedSetOf()) { Regex("""\[(STRICT-\d{3})]""").find(it.message)?.groupValues?.get(1) }
    }

    private fun strictErrors(result: JasperCompiler.CompileResult, code: String): List<CompileError> =
        assertIs<JasperCompiler.CompileResult.Error>(result).errors.filter { it.message.startsWith("[$code]") }

    private fun ok(result: JasperCompiler.CompileResult) {
        assertIs<JasperCompiler.CompileResult.Success>(result, (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
    }

    private val single = """
        // 文件头注释；分号也可能在注释里：a; b
        package demo.strict;
        /* 导入前的块注释 */
        import std.Future;
        // 导入之间的行注释
        import std.Error;

        public class Counter {
            private var n: int32 = 0;
            public function next(): int32 {
                this.n = this.n + 1;
                return this.n;
            }
            public class Inner {
                public function one(): int32 { return 1; }
            }
        }
    """.trimIndent()

    @Test
    fun `one type per file with a matching name is accepted`() {
        ok(strict.compile(mapOf("Counter.jas" to single)))
        ok(strict.compile(mapOf("src/demo/strict/Counter.jas" to single)))
        ok(strict.compile(single))
        ok(strict.compile(mapOf(
            "Shape.jas" to "package demo.s; public interface Shape { function area(): int32; }",
            "Color.jas" to "package demo.s; public enum Color { RED, GREEN }",
            "Square.jas" to "package demo.s; public class Square implements Shape { public function area(): int32 { return 4; } }"
        )))
    }

    @Test
    fun `without strict the relaxed layouts still compile`() {
        val relaxed = """
            package demo.loose;
            public function twice(x: int32): int32 { return x * 2; }
            public class A { }
            public class B { }
        """.trimIndent()
        ok(loose.compile(mapOf("Whatever.jas" to relaxed)))
        ok(loose.compile(relaxed))
        ok(loose.compileScript("var x = 1;\nprintln(x);"))
        assertEquals(setOf("STRICT-002", "STRICT-003"), codes(strict.compile(mapOf("Whatever.jas" to relaxed))))
    }

    @Test
    fun `top level statements and script units are STRICT-001`() {
        val statement = "package demo.x;\npublic class X { }\nvar y = 1;\n"
        val e = strictErrors(strict.compile(mapOf("X.jas" to statement)), "STRICT-001").single()
        assertEquals(3, e.line)
        assertEquals(setOf("STRICT-001"), codes(strict.compile(mapOf("X.jass" to "package demo.x;\npublic class X { }\n"))))
        assertEquals(setOf("STRICT-001"), codes(strict.compileScript("public class X { }")))
        assertEquals(setOf("STRICT-001"), codes(strict.compile("println(1);")))
    }

    @Test
    fun `a second top level type is STRICT-002 at that type`() {
        val two = "package demo.two;\n\npublic class A { }\n\npublic class B { }\n"
        val e = strictErrors(strict.compile(mapOf("A.jas" to two)), "STRICT-002").single()
        assertEquals(5, e.line)
        assertTrue("'B'" in e.message && "'A'" in e.message, e.message)
        assertEquals(2, strictErrors(strict.compile(mapOf("A.jas" to two + "public enum C { X }\n")), "STRICT-002").size)
    }

    @Test
    fun `bare top level functions are STRICT-003 and a file without a type is STRICT-005`() {
        val src = "package demo.f;\n// c; d\nimport std.Error;\n\npublic function f(): int32 { return 1; }\npublic class F { }\n"
        val e = strictErrors(strict.compile(mapOf("F.jas" to src)), "STRICT-003").single()
        assertEquals(5, e.line)
        assertTrue("'f'" in e.message, e.message)
        assertEquals(setOf("STRICT-003", "STRICT-005"), codes(strict.compile(mapOf("G.jas" to "package demo.g;\npublic function g(): int32 { return 1; }\n"))))
        assertEquals(setOf("STRICT-005"), codes(strict.compile(mapOf("Empty.jas" to ""))))
        assertEquals(setOf("STRICT-005"), codes(strict.compile(mapOf("Empty.jas" to "package demo.e;\nimport std.Error;\n// nothing\n"))))
    }

    @Test
    fun `the file stem must equal the type name STRICT-004`() {
        val e = strictErrors(strict.compile(mapOf("dir/Counters.jas" to single)), "STRICT-004").single()
        assertEquals(8, e.line)
        assertTrue("Counter.jas" in e.message, e.message)
        assertEquals(setOf("STRICT-004"), codes(strict.compile(mapOf("counter.jas" to single))))
    }

    @Test
    fun `bundled stdlib is exempt but a user file reusing a stdlib name is not`() {
        val out = Files.createTempDirectory(Paths.get("build", "tmp").also { Files.createDirectories(it) }, "strict-stdlib")
        ok(strict.compileAllWithStdlib(mapOf("Counter.jas" to single), out))
        // 标准库里确有多类型文件（如 Future.jas），原样随附时不受 strict 约束。
        assertTrue(StdLib.sources().getValue("Future.jas").split("\nclass ", "\npublic class ").size > 2)
        val impostor = "package demo.f;\npublic class A { }\npublic class B { }\n"
        assertEquals(setOf("STRICT-002"), codes(strict.compile(mapOf("Future.jas" to impostor))))
        assertEquals(setOf("STRICT-004"), codes(strict.compile(mapOf("Future.jas" to "package demo.f;\npublic class NotFuture { }\n"))))
    }

    @Test
    fun `random layouts get exactly the codes an independent model predicts`() {
        val random = Random(20261110)
        var rejected = 0
        var accepted = 0
        repeat(150) { round ->
            // 约四成轮次生成合格布局，保证通过一侧也有足够样本。
            val clean = random.nextInt(0, 5) < 2
            val types = if (clean) 1 else random.nextInt(0, 4)
            val functions = if (clean) 0 else random.nextInt(0, 3)
            val statement = !clean && random.nextInt(0, 6) == 0
            val script = !clean && random.nextInt(0, 8) == 0
            val nameMatches = clean || random.nextBoolean()
            val names = List(types) { "T${round}x$it" }
            val items = mutableListOf<String>()
            names.forEach { items += "public class $it { public function v(): int32 { return 1; } }" }
            repeat(functions) { items += "public function f${round}x$it(): int32 { return $it; }" }
            items.shuffle(random)
            if (statement) items.add(random.nextInt(0, items.size + 1), "var s$round = 1;")
            val header = buildString {
                if (random.nextBoolean()) append("// head; comment\n")
                append("package demo.r$round;\n")
                if (random.nextBoolean()) append("/* between */\n")
                if (random.nextBoolean()) append("import std.Error; // trailing; comment\n")
            }
            val stem = if (nameMatches && names.isNotEmpty()) names.first() else "File$round"
            val file = stem + if (script) ".jass" else ".jas"
            val source = header + items.joinToString("\n") + "\n"

            // 独立模型：语句或脚本单元只报 001；否则逐条累加。
            val expected = sortedSetOf<String>()
            if (statement || script) {
                expected += "STRICT-001"
            } else if (types == 0 && functions == 0) {
                expected += "STRICT-005"
            } else {
                if (functions > 0) expected += "STRICT-003"
                if (types > 1) expected += "STRICT-002"
                if (types == 0) expected += "STRICT-005"
                if (types == 1 && stem != names.single()) expected += "STRICT-004"
            }
            val result = strict.compile(mapOf(file to source))
            if (expected.isEmpty()) {
                ok(result)
                accepted++
            } else {
                assertEquals(expected, codes(result), "round $round:\n$source")
                rejected++
            }
        }
        assertTrue(accepted >= 15 && rejected >= 15, "accepted=$accepted rejected=$rejected")
    }

    @Test
    fun `compat wrapping keeps original line numbers and comment separated imports (Q159)`() {
        // 多顶层类型走兼容包装；包内第 9 行的类型错误必须报在第 9 行。
        val src = """
            // header; with a semicolon
            package demo.q159;
            /* between package and import */
            import std.Error;
            // between imports
            import std.Future;
            public class A { }
            public class B {
                public function bad(): int32 { return "no"; }
            }
        """.trimIndent()
        val error = assertIs<JasperCompiler.CompileResult.Error>(loose.compile(mapOf("Q.jas" to src)))
        assertEquals(listOf(9), error.errors.map { it.line }, error.message)
        val fixed = src.replace("return \"no\";", "return 1;")
        ok(loose.compile(mapOf("Q.jas" to fixed)))
        ok(loose.compile(fixed))
    }

    @Test
    fun `the jaso command line accepts --strict`() {
        val dir: Path = Files.createTempDirectory(Paths.get("build", "tmp").also { Files.createDirectories(it) }, "strict-cli")
        val src = dir.resolve("Cli.jas")
        Files.write(src, "package demo.cli;\npublic function hello(): string { return \"hi\"; }\n".toByteArray())
        val base = listOf("--emit", "jaso", "-o", dir.resolve("cli.jaso").toString(), "--name", "strict-cli", "--version", "1.0.0")
        assertEquals(0, emitJasoMain(base + src.toString()))
        assertEquals(1, emitJasoMain(base + "--strict" + src.toString()))
    }
}
