package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 非 void 函数的返回路径（LANG-RETURN-PATHS-01）与 `while (true)` 结尾（Q154）。
 *
 * 修复前：检查器不查返回路径，后端在方法末尾补一条返回指令。函数体能走到末尾时那条指令没有值可返回，
 * `while (true)` 结尾时它经条件跳转在字节码里可达——两者都是 VerifyError（Operand stack underflow）。
 */
class ReturnPathsTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "return-paths", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    /** 带标准库编译（用例要 `std.Error`）。 */
    private fun compile(name: String, source: String): Pair<JasperCompiler.CompileResult, Path> {
        val dir = scratch(name)
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir) to dir
    }

    /** 调用包 `t` 里声明顶层函数 `f` 的类；返回 Result，异常以 InvocationTargetException 的原因表示。 */
    private fun call(dir: Path, n: Int): Result<Any?> =
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val classes = Files.list(dir.resolve("t")).use { files -> files.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
            val m = classes.flatMap { it.methods.toList() }.single { it.name == "f" }
            try {
                Result.success(m.invoke(null, n))
            } catch (e: InvocationTargetException) {
                Result.failure(e.targetException)
            }
        }

    private fun accepted(name: String, body: String, type: String = "int32"): Path {
        val (result, dir) = compile(name, "package t;\nimport std.Error;\npublic function f(n: int32): $type {\n$body\n}\n")
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        return dir
    }

    private fun missingReturn(name: String, body: String, type: String = "int32") {
        val (result, _) = compile(name, "package t;\nimport std.Error;\npublic function f(n: int32): $type {\n$body\n}\n")
        val error = assertIs<JasperCompiler.CompileResult.Error>(result, "$name should be rejected")
        assertTrue(error.errors.any { "Missing return" in it.message && "'f'" in it.message }, error.message)
    }

    @Test
    fun `functions ending in an infinite loop compile and run (Q154)`() {
        val loops = mapOf(
            "while" to "var i = 0;\nwhile (true) { i = i + 1; if (i > n) { return i; } }",
            "for" to "var i = 0;\nfor (;;) { i = i + 1; if (i > n) { return i; } }",
            "do" to "var i = 0;\ndo { i = i + 1; if (i > n) { return i; } } while (true);",
            "labeled" to "var i = 0;\nlabel outer: while (true) { while (true) { i = i + 1; if (i > n) { return i; } if (i % 2 == 0) { continue outer; } } }"
        )
        for ((name, body) in loops) {
            val dir = accepted("loop-$name", body)
            for (n in listOf(0, 3, 10)) assertEquals(n + 1, call(dir, n).getOrThrow(), "$name n=$n")
        }
        val dir = accepted("loop-int64", "var i: int64 = 0L;\nwhile (true) { i = i + 2L; if (i > 10L) { return i; } }", "int64")
        assertEquals(12L, call(dir, 0).getOrThrow())
        val str = accepted("loop-string", "while (true) { if (n > 0) { return \"x\"; } throw new Error(\"neg\"); }", "string")
        assertEquals("x", call(str, 1).getOrThrow())
    }

    @Test
    fun `paths that reach the end are rejected`() {
        missingReturn("if-no-else", "if (n > 0) { return 1; }")
        missingReturn("empty", "")
        missingReturn("break-out", "while (true) { if (n > 0) { break; } }")
        missingReturn("labeled-break", "label outer: while (true) { while (true) { break outer; } }")
        missingReturn("cond-loop", "while (n > 0) { return 1; }")
        missingReturn("switch-case-falls", "switch (n) { case 1 => { return 1; } default => { n = 2; } }")
        missingReturn("try-catch-falls", "try { return 1; } catch (e: Error) { }")
        missingReturn("for-in", "for (x in [1, 2]) { return x; }")
        missingReturn("string", "if (n > 0) { return \"a\"; }", "string")
        val (method, _) = compile("method", "package t;\npublic class C {\n public function g(): int32 { if (true) { return 1; } }\n}\npublic function main(): int32 { return 0; }\n")
        assertIs<JasperCompiler.CompileResult.Error>(method)
    }

    @Test
    fun `every path returning or throwing is accepted`() {
        val cases = mapOf(
            "if-else" to ("if (n > 0) { return 1; } else { return 2; }" to listOf(0 to 2, 1 to 1)),
            "nested" to ("if (n > 0) { if (n > 5) { return 3; } else { return 1; } } else { throw new Error(\"neg\"); }" to listOf(1 to 1, 6 to 3)),
            "try-catch" to ("try { if (n > 0) { throw new Error(\"x\"); } return 1; } catch (e: Error) { return 2; }" to listOf(0 to 1, 1 to 2)),
            "finally-returns" to ("try { var x = n; } finally { return 7; }" to listOf(0 to 7)),
            "switch-default" to ("switch (n) { case 1 => { return 10; } default => { return 20; } }" to listOf(1 to 10, 2 to 20)),
            "block" to ("{ return n; }" to listOf(4 to 4))
        )
        for ((name, case) in cases) {
            val dir = accepted("ok-$name", case.first)
            for ((n, want) in case.second) assertEquals(want, call(dir, n).getOrThrow(), "$name n=$n")
        }
        // void 函数与没有 return 的 void 方法不受影响。
        val (void, _) = compile("void", "package t;\nimport std.Error;\npublic function f(n: int32): void {\nif (n > 0) { return; }\n}\n")
        assertIs<JasperCompiler.CompileResult.Success>(void)
    }

    /** 随机语句树：独立模型判定「能否走到末尾」，接受的程序必须能运行且不出 VerifyError。 */
    private sealed class S {
        data class Ret(val v: Int) : S()
        data class Throw(val tag: Int) : S()
        object Break : S()
        object Nop : S()
        data class If(val k: Int, val then: List<S>, val other: List<S>?) : S()
        data class Forever(val body: List<S>) : S()
        data class Bounded(val body: List<S>) : S()
    }

    private fun gen(r: Random, depth: Int, inLoop: Boolean): List<S> = List(r.nextInt(1, 4)) {
        val roll = r.nextInt(if (depth > 2) 4 else 7)
        when (roll) {
            0 -> S.Ret(r.nextInt(100))
            1 -> S.Throw(r.nextInt(100))
            2 -> if (inLoop && r.nextBoolean()) S.Break else S.Nop
            3 -> S.Nop
            4 -> S.If(r.nextInt(4), gen(r, depth + 1, inLoop), if (r.nextBoolean()) gen(r, depth + 1, inLoop) else null)
            5 -> S.Forever(gen(r, depth + 1, true))
            else -> S.Bounded(gen(r, depth + 1, true))
        }
    }

    // 模型：返回 (能否落到后继, 是否含跳出当前最内层循环的 break)。
    // 规则写明是保守的：循环体里任何位置的 break（包括 return 之后不可达的）都算能跳出。
    private fun completes(list: List<S>): Pair<Boolean, Boolean> {
        val parts = list.map(::completes)
        return parts.all { it.first } to parts.any { it.second }
    }

    private fun completes(s: S): Pair<Boolean, Boolean> = when (s) {
        is S.Ret, is S.Throw -> false to false
        S.Break -> false to true
        S.Nop -> true to false
        is S.If -> {
            val (tc, tb) = completes(s.then)
            val (oc, ob) = s.other?.let(::completes) ?: (true to false)
            (tc || oc) to (tb || ob)
        }
        is S.Forever -> completes(s.body).second to false
        is S.Bounded -> true to false
    }

    private var seq = 0

    private fun render(list: List<S>, out: StringBuilder, loopVar: Int) {
        for (s in list) when (s) {
            is S.Ret -> out.append("return ${s.v};\n")
            is S.Throw -> out.append("throw new Error(\"t${s.tag}\");\n")
            S.Break -> out.append("break;\n")
            S.Nop -> out.append("n = n + 0;\n")
            is S.If -> {
                out.append("if (n % 4 == ${s.k}) {\n"); render(s.then, out, loopVar); out.append("}")
                if (s.other != null) { out.append(" else {\n"); render(s.other, out, loopVar); out.append("}") }
                out.append("\n")
            }
            is S.Forever -> {
                // 有界保护：真无限循环在若干趟后抛出，测试不会卡住；这不改变「能否走到末尾」。
                val v = "g${seq++}"
                out.append("var $v = 0;\nwhile (true) {\n$v = $v + 1;\nif ($v > 50) { throw new Error(\"spin\"); }\nn = n + 1;\n")
                render(s.body, out, loopVar + 1); out.append("}\n")
            }
            is S.Bounded -> {
                val v = "b${seq++}"
                out.append("var $v = 0;\nwhile ($v < 3) {\n$v = $v + 1;\n"); render(s.body, out, loopVar + 1); out.append("}\n")
            }
        }
    }

    @Test
    fun `random statement trees agree with an independent model`() {
        val random = Random(20261111)
        var accepted = 0
        var rejected = 0
        repeat(80) { round ->
            val tree = gen(random, 0, false)
            val body = StringBuilder().also { render(tree, it, 0) }.toString()
            val (result, dir) = compile("random-$round", "package t;\nimport std.Error;\npublic function f(n: int32): int32 {\n$body}\n")
            if (completes(tree).first) {
                val error = assertIs<JasperCompiler.CompileResult.Error>(result, "round $round should be rejected:\n$body")
                assertTrue(error.errors.any { "Missing return" in it.message }, error.message)
                rejected++
            } else {
                assertIs<JasperCompiler.CompileResult.Success>(result, "round $round:\n$body\n" + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
                for (n in 0..3) {
                    val outcome = call(dir, n)
                    // 只允许返回 int 或 Jasper 抛出的 Error；VerifyError 等都是失败。
                    outcome.exceptionOrNull()?.let { e ->
                        assertTrue(e.javaClass.name == "std.Error", "round $round n=$n: $e\n$body")
                    } ?: assertIs<Int>(outcome.getOrThrow())
                }
                accepted++
            }
        }
        assertTrue(accepted >= 15 && rejected >= 15, "accepted=$accepted rejected=$rejected")
    }
}
