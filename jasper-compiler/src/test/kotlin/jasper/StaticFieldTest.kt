// ============================================================================
// StaticFieldTest —— Q17 回归守门：用户声明的类静态字段必须按 JVM 静态字段工作。
//
// 模块说明：
//   - 覆盖范围：`public static var` / 静态初值 / 实例共享 / 类名访问实例字段的编译错误 /
//     字段 ACC_STATIC 与 GETSTATIC-PUTSTATIC 字节码形状。
//   - 验证方式：全部走真实编译管线（ANTLR → AST → checker → IR → JVM 字节码），
//     再用 URLClassLoader 真加载、真调用；字节码形状用 JDK 自带的 javap 复核。
//   - 产物位置：所有 .class 与 javap 证据都写在模块自己的 `build/scratch/static-fields/`
//     下，不落项目外临时目录。
//
// 为什么这些断言必须存在：
//   静态字段若被生成成 GETFIELD/PUTFIELD，编译期完全看不出问题，直到运行期才以
//   `NullPointerException: Cannot assign field ... because "null" is null` 暴露
//   （JDBC #8 / R2DBC #18 / JPA #10 的共同根因）。因此这里既断言运行结果，也直接
//   断言字节码指令与字段标志，避免「碰巧能跑」掩盖错误的指令选择。
// ============================================================================
package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 做什么：Q17（类静态字段）的端到端回归测试。
 * 什么时候用：`./gradlew test` 全量回归时自动执行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/调用抛异常、或字节码指令形状不符时测试失败。
 * 为什么这样设计：静态字段的缺陷横跨 checker/translator/backend 三层，只测其中一层的
 *   单元行为无法防止「类型检查通过但字节码仍是 GETFIELD」的回归，故统一用真编译+真运行守门。
 */
class StaticFieldTest {

    /**
     * 做什么：为某个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：目录创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：复用已有测试的 scratch 约定，确保测试产物可复查且不污染仓库源码目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "static-fields", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译 Jasper 源码并断言成功，返回 class 输出目录。
     * 什么时候用：所有正向用例。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：编译失败时以断言失败报告聚合诊断。
     * 为什么这样设计：把「编译 + 失败信息格式化」收敛到一处，避免每个测试重复样板。
     */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /**
     * 做什么：加载并静态调用无参方法（约定名为 main）。
     * 什么时候用：需要真执行生成代码时。
     * 前置条件：outputDir 下有已编译的 className.class。
     * 失败模式：类/方法不存在或调用抛异常时测试失败。
     * 为什么这样设计：URLClassLoader 能验证 JVM 链接期与运行期语义，比只看 .class 存在更强。
     */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /**
     * 做什么：对生成的 class 运行 `javap -p -c`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言字段标志/访问指令形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么这样设计：javap 是 JDK 自带、与 ASM 版本无关的独立证据；把输出落盘到
     *   build/scratch 便于失败时人工复查，且不写项目外路径。
     */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-p", "-c", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val evidence = outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt")
        Files.writeString(evidence, output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /** 1. `Counter.total = 5; return Counter.total;` 必须真的返回 5。 */
    @Test
    fun `class name static field assignment round trips`() {
        val source = """
            package test;
            public class Counter {
                public static var total: int32 = 0;
            }
            public function main(): int32 {
                Counter.total = 5;
                return Counter.total;
            }
        """.trimIndent()
        val out = compile(source, "assign-read")

        assertEquals(5, invokeStatic(out, "test.main", "main"))

        // 字段自身带 ACC_STATIC。
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val field = loader.loadClass("test.Counter").declaredFields.first { it.name == "total" }
            assertTrue(
                Modifier.isStatic(field.modifiers),
                "static field 'total' must carry ACC_STATIC, got modifiers=${field.modifiers}"
            )
        }

        // 访问点必须是 GETSTATIC/PUTSTATIC。
        val mainCode = javap(out, "test.main", "assign-read")
        assertTrue(mainCode.contains("putstatic"), "expected PUTSTATIC in test.main:\n$mainCode")
        assertTrue(mainCode.contains("getstatic"), "expected GETSTATIC in test.main:\n$mainCode")
    }

    /** 2. 声明初值必须在 `<clinit>` 中执行；不显式赋值也应读到 7。 */
    @Test
    fun `static initializer runs in clinit`() {
        val source = """
            package test;
            public class Counter {
                public static var total: int32 = 7;
            }
            public function main(): int32 {
                return Counter.total;
            }
        """.trimIndent()
        val out = compile(source, "clinit-init")

        assertEquals(7, invokeStatic(out, "test.main", "main"))

        val counterCode = javap(out, "test.Counter", "clinit-init")
        // javap 把类初始化方法显示成 `static {};`（字节码里名为 `<clinit>`），
        // 因此以该标记定位初始化块，再断言其中确有 PUTSTATIC。
        val clinitStart = counterCode.indexOf("static {};")
        assertTrue(clinitStart >= 0, "expected a <clinit> in test.Counter:\n$counterCode")
        val clinit = counterCode.substring(clinitStart)
        assertTrue(
            clinit.contains("putstatic"),
            "expected the initializer to be executed by <clinit> via PUTSTATIC:\n$counterCode"
        )
    }

    /** 3. 两个实例访问同一静态字段，必须共享同一份值。 */
    @Test
    fun `static field is shared across instances`() {
        val source = """
            package test;
            public class Counter {
                public static var total: int32 = 0;
            }
            public function main(): int32 {
                var a = new Counter();
                var b = new Counter();
                a.total = 5;
                b.total = 6;
                return a.total + b.total + Counter.total;
            }
        """.trimIndent()
        val out = compile(source, "shared")

        // a.total=6, b.total=6, Counter.total=6 -> 共享语义下总和为 18。
        assertEquals(18, invokeStatic(out, "test.main", "main"))
    }

    /** 4. 通过类名读实例字段必须是明确的编译错误，而不是静默 any / 运行期 NPE。 */
    @Test
    fun `class name instance field is a clear compile error`() {
        val source = """
            package test;
            public class Counter {
                public var total: int32;
            }
            public function main(): int32 {
                return Counter.total;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "expected class-name access to an instance field to fail, got: $result"
        )
        val message = (result as JasperCompiler.CompileResult.Error).message
        assertTrue(
            message.contains("Cannot access instance field 'total' through class name 'Counter'"),
            "expected a clear static-vs-instance diagnostic, got:\n$message"
        )
    }

    /**
     * 5. 跨文件 + 跨包访问静态字段：验证预登记的类型名与 import 解析足以定位字段宿主。
     *
     * 这是 Q17 报告里要求「如实说明是否有额外障碍」的检查点：静态字段的字段符号按
     * JVM 风格类名索引，跨包访问必须经 import 解析到同一类名，否则会退化成全局按名查找
     * 或误判成实例字段。
     */
    @Test
    fun `static field access works across files and packages`() {
        val sources = mapOf(
            "Counter.jas" to """
                package test.model;
                public class Counter {
                    public static var total: int32 = 3;
                }
            """.trimIndent(),
            "Main.jas" to """
                package test.app;
                import test.model.Counter;
                public function main(): int32 {
                    Counter.total = Counter.total + 4;
                    return Counter.total;
                }
            """.trimIndent()
        )
        val out = scratchDir("cross-package")
        val result = JasperCompiler().compileAllToDir(sources, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "cross-package compilation failed: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )

        assertEquals(7, invokeStatic(out, "test.app.main", "main"))
        val mainCode = javap(out, "test.app.main", "cross-package")
        assertTrue(mainCode.contains("getstatic"), "expected GETSTATIC across packages:\n$mainCode")
        assertTrue(mainCode.contains("putstatic"), "expected PUTSTATIC across packages:\n$mainCode")
    }

    /** 6. 字节码形状：字段 ACC_STATIC + GETSTATIC/PUTSTATIC（javap 证据落盘到 build/）。 */
    @Test
    fun `bytecode uses static field access instructions`() {
        val source = """
            package test;
            public class Counter {
                public static var total: int32 = 0;
            }
            public function main(): int32 {
                Counter.total = 9;
                return Counter.total + 1;
            }
        """.trimIndent()
        val out = compile(source, "bytecode-shape")

        val counterCode = javap(out, "test.Counter", "bytecode-shape")
        assertTrue(
            Regex("""static\s+int\s+total;""").containsMatchIn(counterCode),
            "expected javap to show a static field declaration:\n$counterCode"
        )
        // 非 final 静态字段不得带 final；否则跨类 PUTSTATIC 会在运行期失败。
        assertTrue(
            !Regex("""static\s+final\s+int\s+total;""").containsMatchIn(counterCode),
            "mutable static field must not be final:\n$counterCode"
        )

        val mainCode = javap(out, "test.main", "bytecode-shape")
        assertTrue(mainCode.contains("putstatic"), "expected PUTSTATIC in test.main:\n$mainCode")
        assertTrue(mainCode.contains("getstatic"), "expected GETSTATIC in test.main:\n$mainCode")
        assertTrue(
            !mainCode.contains("putfield") && !mainCode.contains("getfield"),
            "static field access must not use instance field instructions:\n$mainCode"
        )
    }
}
