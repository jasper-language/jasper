package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 解析不到的调用目标是编译错误，不再静默放行（Q251，appfw FB-theme-10 / FB-m1-006 / FB-theme-11，LANG-UNRESOLVED-CALL-01）。
 *
 * 修复前 `noSuchFunction(...)`、未 import 的 `Wire.split(...)` / `Helper.twice(...)` 都能编译，运行期才
 * VerifyError / NoClassDefFoundError `<包>/void`，报错不指向缺失的名字。期望值由用例写出。
 */
class UnresolvedCallTest {
    private fun compile(name: String, files: Map<String, String>): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "unresolved-call", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileAllWithStdlib(files, dir) to dir
    }

    private fun rejected(name: String, files: Map<String, String>, vararg expect: String) {
        val (result, _) = compile(name, files)
        val error = assertIs<JasperCompiler.CompileResult.Error>(result, "$name should not compile")
        for (e in expect) assertTrue(error.errors.any { e in it.message }, "$name: expected '$e' in\n${error.message}")
    }

    private fun runEntry(name: String, files: Map<String, String>, pkg: String): Any? {
        val (result, dir) = compile(name, files)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve(pkg)).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("$pkg." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `an undefined free function is diagnosed (FB-theme-10)`() {
        rejected("free", mapOf("m.jas" to "package t;\npublic function entry(): float64 {\nvar x: float64 = noSuchFunction(0.5, 2.4);\nreturn x;\n}\n"), "noSuchFunction")
    }

    @Test
    fun `a static call on a type that is not imported is diagnosed (FB-theme-11, FB-m1-006)`() {
        val lib = "package t.lib;\npublic class Helper {\npublic static function twice(n: int32): int32 { return n * 2; }\n}\n"
        rejected("no-import", mapOf("lib.jas" to lib, "m.jas" to "package t;\npublic function entry(): int32 {\nreturn Helper.twice(21);\n}\n"), "Helper")
        rejected("no-import-arg", mapOf("lib.jas" to lib, "m.jas" to "package t;\npublic function entry(): int32 {\nreturn java.lang.Math.abs(Helper.twice(-21));\n}\n"), "Helper")
        rejected("unknown-type", mapOf("m.jas" to "package t;\npublic function entry(): int32 {\nreturn Wire.split(\"a\", 9).size();\n}\n"), "Wire")
        // 同一程序加上 import 就能编译运行。
        assertEquals(42, runEntry("with-import", mapOf("lib.jas" to lib, "m.jas" to "package t;\nimport t.lib.Helper;\npublic function entry(): int32 {\nreturn Helper.twice(21);\n}\n"), "t"))
    }

    @Test
    fun `legitimate unqualified calls still compile`() {
        val src = """
            package t;
            import std.Mapper;
            public class Calc {
                private var base: int32 = 10;
                public function add(n: int32): int32 { return this.base + n; }
                public function twiceAdd(n: int32): int32 { return this.add(n) + this.add(n); }
                public static function sq(n: int32): int32 { return n * n; }
                public static function sqPlus(n: int32): int32 { return sq(n) + 1; }
            }
            function helper(n: int32): int32 { return n + 1; }
            public function entry(): int32 {
                var c = new Calc();
                var f = (x: int32) => x * 3;
                println("ok");
                return helper(1) + c.twiceAdd(1) + Calc.sqPlus(3) + f(2);
            }
        """.trimIndent()
        // helper(1)=2，twiceAdd(1)=22，sqPlus(3)=10，f(2)=6。
        assertEquals(40, runEntry("legit", mapOf("m.jas" to src), "t"))
    }

    @Test
    fun `an instance method called by its simple name must use this`() {
        val src = "package t;\npublic class C {\npublic function a(): int32 { return 1; }\npublic function b(): int32 { return a(); }\n}\npublic function entry(): int32 { return new C().b(); }\n"
        rejected("implicit-this", mapOf("m.jas" to src), "this.a(")
    }

    @Test
    fun `random programs with one misspelled callee are always rejected`() {
        val random = Random(20261117)
        repeat(20) { k ->
            val names = listOf("alpha", "beta", "gamma", "delta")
            val decls = names.joinToString("\n") { "function $it(n: int32): int32 { return n + ${it.length}; }" }
            val calls = List(random.nextInt(2, 6)) { names[random.nextInt(names.size)] }.toMutableList()
            val bad = random.nextInt(calls.size)
            calls[bad] = calls[bad] + "x"
            val body = calls.joinToString(" + ") { "$it(1)" }
            rejected("random$k", mapOf("m.jas" to "package t;\n$decls\npublic function entry(): int32 {\nreturn $body;\n}\n"), calls[bad])
        }
    }
}
