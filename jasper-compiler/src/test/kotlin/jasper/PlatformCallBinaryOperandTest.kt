package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 平台调用作二元运算操作数（LANG-PLATFORM-BINOP-01）：translator 把没有声明签名的平台调用的 IR 类型
 * 记为 Unit，后端二元算术 / 比较此前只看 IR 类型，`System.nanoTime() - 1L` 因此选了 ISUB；直线代码里
 * 类加载时 VerifyError，`if` 体内则在 ASM 帧计算处编译内部错误。修复后操作数描述符经平台解析器回填。
 *
 * 全部经真实解析 → 检查器 → IR → JVM 字节码并执行（类加载即过字节码校验）；随机部分用固定种子生成
 * `Math.abs(a) op 字面量` 的算术与比较（int32 / int64 / float64，直线与 if 体两种位置），与 Kotlin 计算比对。
 */
class PlatformCallBinaryOperandTest {

    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "platform-binop", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并按函数名收集 demo.Program 的静态方法。 */
    private fun compileFunctions(source: String, name: String): Map<String, Method> {
        val out = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        val loader = URLClassLoader(arrayOf(out.toUri().toURL()))
        return loader.loadClass("demo.Program").declaredMethods.associateBy { it.name }
    }

    @Test
    fun `nanoTime minus a long literal compiles and runs in straight line and if body`() {
        val methods = compileFunctions(
            """
            package demo;
            public class Program {
                public static function straight(): int64 { return java.lang.System.nanoTime() - 1L; }
                public static function inIf(c: bool): int64 {
                    var x: int64 = 0L;
                    if (c) { x = java.lang.System.nanoTime() - 1L; }
                    return x;
                }
                public static function compared(): bool { return java.lang.System.nanoTime() - 1L < java.lang.System.nanoTime(); }
            }
            """.trimIndent(),
            "nanotime"
        )
        val before = System.nanoTime()
        val straight = methods.getValue("straight").invoke(null) as Long
        assertTrue(straight - before >= -1, "straight $straight before $before")
        val inIf = methods.getValue("inIf").invoke(null, true) as Long
        assertTrue(inIf - before >= -1, "inIf $inIf before $before")
        assertEquals(0L, methods.getValue("inIf").invoke(null, false))
        assertEquals(true, methods.getValue("compared").invoke(null))
    }

    private enum class Num(val jas: String, val zero: String) {
        I("int32", "0"), J("int64", "0L"), D("float64", "0.0");

        fun literal(v: Int): String = when (this) { I -> "$v"; J -> "${v}L"; D -> "$v.0" }
        fun box(v: Int): Any = when (this) { I -> v; J -> v.toLong(); D -> v.toDouble() }
    }

    @Test
    fun `fixed seed platform call operands agree with Kotlin arithmetic`() {
        val seed = 20261127L
        val random = Random(seed)
        data class Case(val name: String, val type: Num, val op: String, val literal: Int, val inIf: Boolean)
        val ops = listOf("+", "-", "*", "<", ">=", "==")
        val cases = List(240) {
            Case("f$it", Num.entries[random.nextInt(3)], ops[random.nextInt(ops.size)], random.nextInt(-40, 40), random.nextBoolean())
        }
        val source = buildString {
            append("package demo;\npublic class Program {\n")
            for (c in cases) {
                val compare = c.op in setOf("<", ">=", "==")
                val result = if (compare) "bool" else c.type.jas
                val zero = if (compare) "false" else c.type.zero
                val expr = "java.lang.Math.abs(a) ${c.op} ${c.type.literal(c.literal)}"
                append("    public static function ${c.name}(c: bool, a: ${c.type.jas}): $result {\n")
                if (c.inIf) {
                    append("        var x: $result = $zero;\n        if (c) { x = $expr; }\n        return x;\n")
                } else {
                    append("        return $expr;\n")
                }
                append("    }\n")
            }
            append("}\n")
        }
        val methods = compileFunctions(source, "seeded")
        for (c in cases) {
            repeat(4) {
                val a = random.nextInt(-1000, 1000)
                val actual = methods.getValue(c.name).invoke(null, true, c.type.box(a))
                val expected: Any = when (c.type) {
                    Num.I -> kotlin.math.abs(a).let { x -> op(c.op, x.toLong(), c.literal.toLong()) { it.toInt() } }
                    Num.J -> kotlin.math.abs(a.toLong()).let { x -> op(c.op, x, c.literal.toLong()) { it } }
                    Num.D -> kotlin.math.abs(a.toDouble()).let { x -> opD(c.op, x, c.literal.toDouble()) }
                }
                assertEquals(expected, actual, "seed $seed case $c a=$a")
            }
        }
        assertTrue(cases.count { it.inIf && it.type == Num.J } >= 20 && cases.count { !it.inIf && it.type == Num.J } >= 20,
            "int64 coverage in both positions")
    }

    private fun op(op: String, x: Long, y: Long, narrow: (Long) -> Any): Any = when (op) {
        "+" -> narrow(x + y)
        "-" -> narrow(x - y)
        "*" -> narrow(x * y)
        "<" -> x < y
        ">=" -> x >= y
        else -> x == y
    }

    private fun opD(op: String, x: Double, y: Double): Any = when (op) {
        "+" -> x + y
        "-" -> x - y
        "*" -> x * y
        "<" -> x < y
        ">=" -> x >= y
        else -> x == y
    }
}
