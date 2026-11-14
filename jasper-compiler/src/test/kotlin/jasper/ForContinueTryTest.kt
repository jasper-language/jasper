package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 三段式 for 的 continue 与 try 内 for 之后的控制流（Q240 / Q259，appfw FB-build-011 / FB-m1-005，LANG-FOR-CONTINUE-TRY-01）。
 *
 * FB-build-011：for 里 continue 跳过更新子句 → 死循环。FB-m1-005：try-catch 内一个 for 之后的语句被静默跳过。
 * 期望值由用例写出或由生成器用 Kotlin 同形循环独立算出；每个程序都带迭代上限，死循环表现为返回哨兵值而不是挂住测试。
 */
class ForContinueTryTest {
    private fun run(name: String, ret: String, body: String, extra: String = ""): Any? {
        val dir = Paths.get("build", "scratch", "for-continue-try", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\n$extra\npublic function entry(): $ret {\n$body\n}\n"
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `continue in a classic for runs the update clause (FB-build-011)`() {
        assertEquals("n=4 g=5", run("assign-update", "string", """
            var guard: int32 = 0;
            var n: int32 = 0;
            for (var i: int32 = 0; i < 5; i = i + 1) {
                guard = guard + 1;
                if (guard > 50) { return "stuck"; }
                if (i == 2) { continue; }
                n = n + 1;
            }
            return "n=" + n + " g=" + guard;
        """))
        assertEquals(4, run("method", "int32", "return new Loop().count();", """
            class Loop {
                public function count(): int32 {
                    var n: int32 = 0;
                    var guard: int32 = 0;
                    for (var i: int32 = 0; i < 5; i = i + 1) {
                        guard = guard + 1;
                        if (guard > 50) { return -1; }
                        if (i == 2) { continue; }
                        n = n + 1;
                        if (i > 100) { return -1; }
                    }
                    return n;
                }
            }
        """.trimIndent()))
    }

    @Test
    fun `statements after a for inside try still run (FB-m1-005 shape)`() {
        val extra = """
            import std.Error;
            class Host {
                private var log: string = "";
                private var script: List<string> = new List<string>();
                private var idle: int32 = 1;
                private var code: int32 = 0;
                public function step(s: string): void { this.log = this.log + "s" + s; }
                public function note(s: string): void { this.log = this.log + s; }
                public function close(): void { this.log = this.log + "C"; }
                public function run(withScript: bool): string {
                    if (withScript) { this.script.add("1"); this.script.add("2"); }
                    try {
                        this.note("A");
                        for (s1 in this.script) {
                            this.step(s1);
                        }
                        this.note("B");
                        if (this.idle > 0) {
                            this.note("I");
                        }
                    } catch (e: Error) {
                        this.note("X");
                        this.code = 1;
                    }
                    this.note("H");
                    try {
                        this.close();
                    } catch (e3: Error) {
                        this.note("Y");
                        if (this.code == 0) {
                            this.code = 1;
                        }
                    } finally {
                        this.note("F");
                    }
                    return this.log + this.code;
                }
            }
        """.trimIndent()
        assertEquals("ABIHCF0", run("empty-script", "string", "return new Host().run(false);", extra))
        assertEquals("As1s2BIHCF0", run("with-script", "string", "return new Host().run(true);", extra))
    }

    @Test
    fun `random classic for loops with continue match a Kotlin model`() {
        val random = Random(20261116)
        val body = StringBuilder("var out = \"\";\n")
        val expected = StringBuilder()
        repeat(40) { k ->
            val limit = random.nextInt(1, 12)
            val step = random.nextInt(1, 4)
            val skip = random.nextInt(2, 5)
            val inTry = random.nextBoolean()
            val loop = """
                var n$k: int32 = 0;
                var g$k: int32 = 0;
                for (var i$k: int32 = 0; i$k < $limit; i$k = i$k + $step) {
                    g$k = g$k + 1;
                    if (g$k > 100) { break; }
                    if (i$k % $skip == 0) { continue; }
                    n$k = n$k + i$k;
                }
                out = out + n$k + "/" + g$k + ";";
            """.trimIndent()
            body.append(if (inTry) "try {\n$loop\nout = out + \"t\";\n} catch (e$k: std.Error) { out = out + \"E\"; }\n" else "$loop\n")
            var n = 0
            var g = 0
            var i = 0
            while (i < limit) {
                g++
                if (i % skip != 0) n += i
                i += step
            }
            expected.append("$n/$g;")
            if (inTry) expected.append('t')
        }
        body.append("return out;")
        assertEquals(expected.toString(), run("random", "string", body.toString()))
    }
}
