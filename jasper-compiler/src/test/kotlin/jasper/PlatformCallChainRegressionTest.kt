// ============================================================================
// PlatformCallChainRegressionTest —— 缺陷 C27（第二步）的回归测试
// 所属层次：compiler 模块的端到端回归；每个用例都走完整流水线
//           （ANTLR → AST → checker → IR → JVM 字节码）并真正加载执行生成的类。
// 为什么必须执行：平台调用链的 owner/描述符是在后端写字节码时确定的，编译成功
//   不代表链接/执行正确；错误通常只在类加载（VerifyError / NoClassDefFoundError）
//   或首次调用（NoSuchMethodError）时暴露。
// 与生产代码的关系：被测点是 JvmIrBackend.receiverJvmDescriptor 的递归描述符
//   回填、PlatformMethodResolver 的 JDK 反射签名解析，以及 translator 对
//   「成员调用符号/返回类型」的隔离（防止同名顶层函数污染成员调用）。
// 产物位置：`build/scratch/platform-call-chain/`（仓库内，不使用外部临时目录）。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import java.math.BigInteger
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 做什么：验证「平台类型实例方法链式调用」在任意深度上都按每一层的真实返回类型
 *   继续解析，而不是在第二层及以后退化成不存在的 `void` owner 或顶层函数调用。
 *
 * 什么时候用：修改 [jasper.compiler.JvmIrBackend] 的成员调用描述符选择、修改
 *   [jasper.compiler.PlatformMethodResolver]，或调整 translator 的成员调用符号/
 *   返回类型登记时必须跑。
 * 前置条件：测试运行在带有 JDK 反射的普通 JVM 上（构建虚拟机内即可）。
 * 失败模式：编译失败时把聚合诊断带进断言；运行期失败会以 NoClassDefFoundError /
 *   VerifyError / NoSuchMethodError 等形式让测试直接失败。
 * 为什么这样设计：每个成功用例都执行生成的类，覆盖「两层链、三层以上返回基本类型、
 *   链结果存局部变量、链中夹带用户类方法、与顶层函数同名、实参内部再嵌套平台调用」
 *   六种关键路径；失败用例只断言“不出现内部异常”，因为任务书允许编译期报错或
 *   保持旧行为两种结果。
 */
class PlatformCallChainRegressionTest {

    /** 创建并清空一个模块内 scratch 目录。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "platform-call-chain", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并断言成功；失败时把聚合诊断带进断言信息。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 加载输出目录并调用无参静态方法（顶层 `main` 编译到 `包名.main` 类）。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /** 递归统计输出目录里的 class 文件。 */
    private fun classFiles(outputDir: Path): List<Path> =
        Files.walk(outputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.toList()
        }

    /**
     * 两层链 + 引用返回 + 最终转字符串：
     * `BigInteger.pow(int)` 返回 BigInteger，`subtract(BigInteger)` 也返回 BigInteger。
     * 旧实现只把第一层（pow）的返回描述符回填给 subtract；subtract 自己的 IR 类型
     * 仍是 Unit，导致外层 toString 把接收者当成 `test/void`。
     *
     * 在 main（2ae0a5d）上的实际失败形态：**运行期**加载/首次调用时抛
     * `NoClassDefFoundError: test/void`（编译与写盘均成功）。
     */
    @Test
    fun `two level chain returns big integer then string`() {
        val source = """
            package test;
            public function main(): string {
                return new java.math.BigInteger("2").pow(10).subtract(new java.math.BigInteger("24")).toString();
            }
        """.trimIndent()
        val out = compile(source, "two-level")
        assertEquals("1000", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 三层以上链 + 基本类型返回：`pow(4)` → `add(...)` → `bitLength()`。
     * bitLength 的真实描述符是 `()I`；链上每一层都必须递归回填，否则第二层之后
     * 的 owner 会退化成 void。3^4 + 1 = 82，二进制位数是 7。
     */
    @Test
    fun `deep chain ending in primitive return executes`() {
        val source = """
            package test;
            public function main(): int32 {
                return new java.math.BigInteger("3").pow(4).add(new java.math.BigInteger("1")).bitLength();
            }
        """.trimIndent()
        val out = compile(source, "deep-primitive")
        assertEquals(7, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 链的结果先存入局部变量，再调用一次平台方法。
     *
     * 为什么局部变量要写显式类型：平台调用的调用点类型在检查器里按既有策略是
     * any/Unit（任务书明确不在本缺陷里改这个总体策略），因此 `var r = 平台链`
     * 会让变量本身失去真实类型；显式标注 `java.math.BigInteger` 后，变量的槽位
     * 描述符与后续 `r.toString()` 的接收者类型都是确定的。本用例验证的正是
     * 「链本身已算对 + 变量槽位存取正确」。
     */
    @Test
    fun `chain result stored in local variable can call platform method again`() {
        val source = """
            package test;
            public function main(): string {
                var r: java.math.BigInteger =
                    new java.math.BigInteger("2").pow(10).subtract(new java.math.BigInteger("24"));
                return r.toString();
            }
        """.trimIndent()
        val out = compile(source, "local-variable")
        assertEquals("1000", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 链中夹带用户自定义类方法：`Box.value()` 声明返回 `java.math.BigInteger`，
     * 之后继续接平台方法 `pow(3)`。用户方法必须继续按 translator 预登记的声明签名
     * 生成（返回 BigInteger），不能被平台解析器或同名推断改写。
     * 同时用反射断言 `Box.value` 的声明返回类型仍是 BigInteger。
     */
    @Test
    fun `user method returning platform type keeps declared signature in chain`() {
        val source = """
            package test;
            public class Box {
                public var v: java.math.BigInteger;
                public constructor(v: java.math.BigInteger) { this.v = v; }
                public function value(): java.math.BigInteger { return this.v; }
            }
            public function main(): string {
                return new Box(new java.math.BigInteger("2")).value().pow(3).toString();
            }
        """.trimIndent()
        val out = compile(source, "user-method")
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val box = loader.loadClass("test.Box")
            assertEquals(
                BigInteger::class.java,
                box.getMethod("value").returnType,
                "user-declared method must keep its declared BigInteger return type"
            )
        }
        assertEquals("8", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 顶层函数与平台方法同名：用户声明了顶层 `subtract(int32, int32)`，链式调用
     * `big.subtract(other)` 仍必须调成员方法，而 `subtract(10, 3)` 仍必须调顶层函数。
     *
     * 返回值的含义：链式结果是 "3" 且顶层调用结果是 7；任何一边走错都会得到
     * 其他数字或直接 VerifyError（旧实现曾把成员调用发成 INVOKESTATIC）。
     */
    @Test
    fun `member call wins over same named top level function`() {
        val source = """
            package test;
            public function subtract(a: int32, b: int32): int32 { return a - b; }
            public function main(): int32 {
                var memberResult: string =
                    new java.math.BigInteger("10").subtract(new java.math.BigInteger("7")).toString();
                if (memberResult != "3") { return 1; }
                return subtract(10, 3);
            }
        """.trimIndent()
        val out = compile(source, "same-name")
        assertEquals(7, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 实参内部再嵌套平台调用：`add(x.pow(2))` 的实参本身是一层平台调用。
     * 解析 add 的真实签名时不能把实参类型当成 Unit/`V`，否则重载解析失败。
     * 2 + 3^2 - 4 = 7。
     */
    @Test
    fun `platform call nested inside argument is resolved recursively`() {
        val source = """
            package test;
            public function main(): string {
                return new java.math.BigInteger("2")
                    .add(new java.math.BigInteger("3").pow(2))
                    .subtract(new java.math.BigInteger("4"))
                    .toString();
            }
        """.trimIndent()
        val out = compile(source, "nested-argument")
        assertEquals("7", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 失败场景：链中某一层是平台类上不存在的方法。
     *
     * 实际行为（本测试已实测）：编译器保持旧行为，**编译成功**并写出 class；
     * 后端解析器对不存在的方法返回 null，仍按调用点类型生成 `()V`，直到运行期
     * 真正调用才抛 `NoSuchMethodError`。本用例只断言“编译器不抛内部异常”，因为
     * 任务书明确允许「编译期报错」或「保持旧行为」两种结果；不接受的是解析器
     * 崩溃/内部异常。
     */
    @Test
    fun `missing method in chain does not raise internal compiler error`() {
        val source = """
            package test;
            public function main(): string {
                return new java.math.BigInteger("2").definitelyNotAPlatformMethod().toString();
            }
        """.trimIndent()
        val outputDir = scratchDir("missing-method")
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        if (result is JasperCompiler.CompileResult.Error) {
            assertFalse(
                result.errors.any { it is CompileError.Internal },
                "missing platform method must not surface as an internal compiler error: ${result.message}"
            )
        } else {
            // 旧行为分支：编译成功应确实产出 class，而不是“成功但什么都没写”。
            assertTrue(classFiles(outputDir).isNotEmpty(), "successful compilation should emit class files")
        }
    }
}
