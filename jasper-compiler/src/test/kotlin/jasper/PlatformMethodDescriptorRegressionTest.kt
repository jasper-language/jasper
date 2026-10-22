// ============================================================================
// PlatformMethodDescriptorRegressionTest —— 缺陷 C27（第一步）的回归测试
// 所属层次：compiler 模块的端到端回归；每个用例都走完整流水线
//           （ANTLR → AST → checker → IR → JVM 字节码）并真正加载执行生成的类。
// 为什么必须执行：平台方法描述符写错时，编译/写盘都可能成功，错误只在类加载
//   （VerifyError）或调用（NoSuchMethodError）时暴露；只断言“编译成功”抓不住回归。
// 与生产代码的关系：被测点是 JvmIrBackend 的实例成员调用描述符选择 +
//   PlatformMethodResolver 的 JDK 反射签名解析。
// 产物位置：`build/scratch/platform-method-descriptor/`（仓库内，不使用外部临时目录）。
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
 * 做什么：验证平台（JDK）类型实例方法按**真实签名**生成调用描述符，而不是按
 *   检查器给出的 `any`/Unit 调用点类型猜成 `()Ljava/lang/Object;` 或 `()V`。
 *
 * 什么时候用：修改 JvmIrBackend 的成员调用描述符选择、或修改
 *   [jasper.compiler.PlatformMethodResolver] 时必须跑。
 * 前置条件：测试运行在带有 JDK 反射的普通 JVM 上（构建虚拟机内即可）。
 * 失败模式：编译失败时把聚合诊断带进断言；运行期失败会以 NoSuchMethodError /
 *   VerifyError 等形式让测试直接失败。
 * 为什么这样设计：每个成功用例都执行生成的类，覆盖基本类型返回、布尔返回、
 *   基本类型形参、接口派发、用户同名方法五种关键路径；失败用例只断言
 *   “不出现内部异常”，因为任务书允许编译期报错或保持旧行为两种结果。
 */
class PlatformMethodDescriptorRegressionTest {

    /** 创建并清空一个模块内 scratch 目录。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "platform-method-descriptor", name)
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
     * 基本类型返回：`BigInteger.bitLength()` 的真实描述符是 `()I`。
     * 旧实现按 IR 调用点类型（无平台声明签名）拼成 `()Ljava/lang/Object;`，
     * 运行期抛 NoSuchMethodError。结果 8 来自 255 的二进制位数。
     */
    @Test
    fun `platform method returning int uses real descriptor`() {
        val source = """
            package test;
            public function main(): int32 {
                return new java.math.BigInteger("255").bitLength();
            }
        """.trimIndent()
        val out = compile(source, "int-return")
        assertEquals(8, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 布尔返回：`BigInteger.testBit(0)` 的真实描述符是 `(I)Z`。
     * 覆盖「返回 boolean 基本类型 + 实参 int」这一组合。
     */
    @Test
    fun `platform method returning boolean uses real descriptor`() {
        val source = """
            package test;
            public function main(): bool {
                return new java.math.BigInteger("7").testBit(0);
            }
        """.trimIndent()
        val out = compile(source, "boolean-return")
        assertEquals(true, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 基本类型形参 + 引用返回：`BigInteger.shiftLeft(int)` 的真实描述符是
     * `(I)Ljava/math/BigInteger;`。随后 `.toString()` 仍应得到十进制文本 "1024"。
     * 旧实现会把实参个数之外的信息全部丢掉，拼出不存在的方法描述符。
     */
    @Test
    fun `platform method taking primitive argument uses real descriptor`() {
        val source = """
            package test;
            public function main(): string {
                return new java.math.BigInteger("1").shiftLeft(10).toString();
            }
        """.trimIndent()
        val out = compile(source, "primitive-argument")
        assertEquals("1024", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 宽类型返回丢弃：`BigInteger.longValue()` 的真实描述符是 `()J`，放在语句位置时
     * 调用点 IR 类型缺失（Unit），丢弃时必须按双槽位处理，否则验证器在加载时报
     * 栈型错误（与 C26 同类）。随后的 return 证明方法体正常执行完。
     */
    @Test
    fun `discarded platform method returning long keeps stack consistent`() {
        val source = """
            package test;
            public function main(): int32 {
                new java.math.BigInteger("5").longValue();
                return 1;
            }
        """.trimIndent()
        val out = compile(source, "long-return-discarded")
        assertEquals(1, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 接口派发：`java.util.List.size()` 必须用 INVOKEINTERFACE，且真实返回是 `()I`。
     * 若按类调用发 INVOKEVIRTUAL，链接期会报 IncompatibleClassChangeError；
     * 若按旧逻辑拼 `()V`/`()Ljava/lang/Object;`，调用期失败。
     */
    @Test
    fun `platform interface method uses INVOKEINTERFACE and real descriptor`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: java.util.List = cast (new java.util.ArrayList()) as java.util.List;
                xs.add("a");
                xs.add("b");
                return xs.size();
            }
        """.trimIndent()
        val out = compile(source, "interface-dispatch")
        assertEquals(2, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 用户同名方法不受影响：用户类声明的 `bitLength(): string` 必须继续走
     * translator 预登记的声明签名，平台解析器不能把返回类型改成 `int`。
     */
    @Test
    fun `user defined method with platform method name keeps declared signature`() {
        val source = """
            package test;
            public class UserBig {
                public constructor() {}
                public function bitLength(): string { return "user"; }
            }
            public function main(): string {
                return new UserBig().bitLength();
            }
        """.trimIndent()
        val out = compile(source, "user-defined")
        assertEquals("user", invokeStatic(out, "test.main", "main"))
    }

    /**
     * 失败场景 1：平台类上不存在的方法。
     *
     * 实际行为（本测试已实测）：编译器保持旧行为，**编译成功**并写出 class；
     * 后端解析器零候选返回 null，于是按 IR 调用点类型（Unit）生成 `()V`，
     * 直到运行期真正调用才会 NoSuchMethodError。本用例只断言“编译器不抛内部异常”，
     * 因为任务书明确允许「编译期报错」或「保持旧行为」两种结果；这里不接受的是
     * 解析器崩溃/内部异常。
     */
    @Test
    fun `missing platform method does not raise internal compiler error`() {
        val source = """
            package test;
            public function main(): int32 {
                var b = new java.math.BigInteger("7");
                b.definitelyNotAPlatformMethod();
                return 0;
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

    /**
     * 失败场景 2：实参个数与任何平台方法都不匹配。
     *
     * 实际行为（本测试已实测）：检查器对未知平台方法不报错，后端解析器零候选
     * 返回 null，沿旧逻辑生成 `(I)V`，编译成功；运行期才会 NoSuchMethodError。
     * 同样只断言“不出现内部异常”，把两种允许行为都覆盖到。
     */
    @Test
    fun `platform method arity mismatch does not raise internal compiler error`() {
        val source = """
            package test;
            public function main(): int32 {
                var b = new java.math.BigInteger("7");
                b.bitLength(1);
                return 0;
            }
        """.trimIndent()
        val outputDir = scratchDir("arity-mismatch")
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        if (result is JasperCompiler.CompileResult.Error) {
            assertFalse(
                result.errors.any { it is CompileError.Internal },
                "arity mismatch must not surface as an internal compiler error: ${result.message}"
            )
        } else {
            assertTrue(classFiles(outputDir).isNotEmpty(), "successful compilation should emit class files")
        }
    }
}
