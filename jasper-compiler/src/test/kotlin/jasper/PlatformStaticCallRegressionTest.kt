// ============================================================================
// PlatformStaticCallRegressionTest —— 缺陷 C28 的端到端回归测试
// 所属层次：compiler 模块；每个成功用例都走完整流水线并真正加载、执行生成类。
// 为什么必须执行：静态调用的 owner 与描述符在字节码层决定是否正确；编译成功但不能
//   加载/执行时，错误通常以 VerifyError、NoClassDefFoundError 或 NoSuchMethodError
//   出现，只有实际调用生成类才能覆盖。
// 产物位置：build/scratch/platform-static-call/（仓库内，不使用外部临时目录）。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 做什么：锁定「全限定平台类名 + 静态方法」从检查、IR 翻译到 INVOKESTATIC 的完整行为。
 *
 * 什么时候用：修改 [jasper.checker.TypeChecker] 的静态调用识别、translator 的静态调用
 *   登记、JVM 后端的方法描述符回填，或 [jasper.compiler.PlatformMethodResolver] 时。
 * 前置条件：测试运行在可加载 JDK 标准库的构建虚拟机上。
 * 失败模式：成功用例会在编译、类加载或方法调用阶段直接失败；失败用例要求编译期给出
 *   含类名与方法名的语义诊断，绝不接受 Internal 异常。
 * 为什么这样设计：成功路径覆盖基本类型返回、对象返回后继续实例调用、重载决议、静态
 *   结果作实参，以及既有用户静态方法/枚举合成方法；失败路径分别覆盖缺类、缺方法和
 *   以类名调用实例方法。
 */
class PlatformStaticCallRegressionTest {

    /** 创建并清空一个模块内 scratch 目录。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "platform-static-call", name)
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

    /**
     * 编译一个必须失败的源码，并断言错误含指定片段且不含 Internal 异常。
     *
     * 做什么：统一失败路径的编译结果形状与诊断内容检查。
     * 什么时候用：平台静态调用的缺类、缺方法、调用实例方法三类负例。
     * 前置条件：source 应确实在检查阶段产生语义错误。
     * 失败模式：编译成功、出现 Internal 错误或诊断缺少类/方法名时由断言失败报告。
     * 为什么集中：三条负例对错误质量的要求相同，集中检查可避免各测试使用不同宽松度。
     */
    private fun assertCompileErrorContains(source: String, name: String, vararg fragments: String) {
        val result = JasperCompiler().compileToDirWithIr(source, scratchDir(name))
        assertTrue(result is JasperCompiler.CompileResult.Error, "expected compile-time error for $name, got $result")
        val error = result as JasperCompiler.CompileResult.Error
        assertFalse(
            error.errors.any { it is CompileError.Internal },
            "platform static-call failure must be a semantic error, not Internal: ${error.message}"
        )
        for (fragment in fragments) {
            assertTrue(fragment in error.message, "expected '$fragment' in diagnostics: ${error.message}")
        }
    }

    /**
     * 基本类型静态方法参与整数运算。
     *
     * 在 main（4b02560）上实测的旧失败形态是编译期
     * `[TYPE] Unresolved reference: 'java'`：检查器把全限定名链的根 `java`
     * 当作普通标识符，而不是平台类路径。修复后必须返回 42 + 1 = 43。
     */
    @Test
    fun `Integer parseInt participates in integer arithmetic`() {
        val source = """
            package test;
            public function main(): int32 {
                return java.lang.Integer.parseInt("42") + 1;
            }
        """.trimIndent()
        val out = compile(source, "parse-int")
        assertEquals(43, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 静态方法返回对象，返回对象继续参与实例方法链。
     * `valueOf(5)` 返回 BigInteger，`pow(3)` 再返回 BigInteger，最终 toString 得到 "125"。
     */
    @Test
    fun `static object result continues through instance method chain`() {
        val source = """
            package test;
            public function main(): string {
                return java.math.BigInteger.valueOf(5).pow(3).toString();
            }
        """.trimIndent()
        val out = compile(source, "object-chain")
        assertEquals("125", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 静态重载按实参描述符选择：Math.max(int,int) 与 Math.abs(double)。
     *
     * 两个独立生成类都真正执行，避免只因其中一种描述符偶然可链接而误报通过。
     */
    @Test
    fun `Math static overloads are selected by argument descriptors`() {
        val maxSource = """
            package test;
            public function main(): int32 {
                return java.lang.Math.max(3, 9);
            }
        """.trimIndent()
        assertEquals(9, invokeStatic(compile(maxSource, "math-max"), "test.main", "main"))

        val absSource = """
            package test;
            public function main(): float64 {
                return java.lang.Math.abs(-2.5);
            }
        """.trimIndent()
        assertEquals(2.5, invokeStatic(compile(absSource, "math-abs"), "test.main", "main"))
    }

    /**
     * 静态调用结果作为另一个平台静态调用的实参：
     * `parseInt("7") * 6` 必须先按 int 求值，再匹配 `Integer.toString(int)` 得 "42"。
     */
    @Test
    fun `static result is an argument to another platform static call`() {
        val source = """
            package test;
            public function main(): string {
                return java.lang.Integer.toString(java.lang.Integer.parseInt("7") * 6);
            }
        """.trimIndent()
        val out = compile(source, "nested-static-argument")
        assertEquals("42", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 既有用户静态方法与枚举合成静态方法行为不变。
     *
     * 用户 `A.f()` 返回 4；枚举 `values()` 长度为 3 且 `valueOf("Second")` 命中
     * 第二个常量。只有三者同时正确时才返回 7。
     */
    @Test
    fun `user static and enum synthesized calls remain unchanged`() {
        val source = """
            package test;
            public class A {
                public static function f(): int32 { return 4; }
            }
            public enum Types { First, Second, Third }
            public function main(): int32 {
                var byName: Types = Types.valueOf("Second");
                if (Types.values().length != 3) { return 1; }
                if (byName != Types.Second) { return 2; }
                return A.f() + 3;
            }
        """.trimIndent()
        val out = compile(source, "existing-static-forms")
        assertEquals(7, invokeStatic(out, "test.main", "main"))
    }

    /** 不存在的平台类必须在检查阶段失败，诊断含完整类名和方法名。 */
    @Test
    fun `missing platform class is a semantic compile error`() {
        val source = """
            package test;
            public function main(): int32 {
                return java.lang.NoSuchThing.f();
            }
        """.trimIndent()
        assertCompileErrorContains(source, "missing-class", "java.lang.NoSuchThing", "f")
    }

    /** 存在的平台类上没有同名 public static 方法时必须编译期失败。 */
    @Test
    fun `missing platform static method is a semantic compile error`() {
        val source = """
            package test;
            public function main(): int32 {
                return java.lang.Integer.noSuch();
            }
        """.trimIndent()
        assertCompileErrorContains(source, "missing-static-method", "java.lang.Integer", "noSuch")
    }

    /** 通过类名调用实例方法必须拒绝，不得降级为 INVOKESTATIC。 */
    @Test
    fun `instance method through platform class name is a semantic compile error`() {
        val source = """
            package test;
            public function main(): int32 {
                return java.lang.String.length();
            }
        """.trimIndent()
        assertCompileErrorContains(source, "instance-through-class", "java.lang.String", "length")
    }
}
