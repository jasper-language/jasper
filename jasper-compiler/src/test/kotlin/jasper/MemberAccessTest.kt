// ============================================================================
// MemberAccessTest.kt —— Q19 回归守门：成员访问、静态调用与运算符优先级
// ============================================================================
// 职责：
//   - 固定 Q19 的三条核心契约：
//       C23：对**已声明具名类型**的未解析成员访问必须在编译期报错，且不得产出任何
//            .class（旧实现会静默兜底成假字段 + Unit 描述符，生成非法字节码）。
//       C22：用户声明的类静态方法 `A.f()` 必须识别为静态调用并降为 INVOKESTATIC。
//       运算符优先级：权威 grammar 的 `binaryExpression` 实际不施加二元优先级（扁平左
//            结合），故比较参与 `&&` 时必须显式加括号；用例锁定受支持的分组写法并记录
//            上游修复点（详见用例 6 与 EnumTest 用例 5 的说明）。
//   - 同时正向锁定「数组 length 内建属性」「实例/静态字段」「枚举合成 values()/valueOf」
//     等合法成员访问不被 C23 的严格化误伤。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开入口与 JDK 自带 javap / URLClassLoader，
//   不触碰 TypeChecker / translator / backend 的内部实现，因此描述的是「可观察行为」。
//
// 为什么这些断言必须存在：
//   把「未解析成员」从 warn 升级为 error 之后，最容易出现的退化是「合法成员也被误杀」
//   （继承成员、接口成员、枚举合成方法）。因此每个负向用例都配正向对照，并且既断言
//   运行结果，也用 javap 断言指令选择（INVOKESTATIC vs GETFIELD/GETSTATIC），
//   避免「碰巧能跑」掩盖错误的指令或错误的编译期放行。
//
// 产物位置：所有 .class 与 javap 证据都写在模块自己的 `build/scratch/member-access/`
//   下，不落项目外临时目录。
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

/**
 * 做什么：Q19（成员访问 / 静态调用 / 运算符优先级）的端到端回归测试。
 * 什么时候用：`./gradlew test` 全量回归时自动执行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译结果与预期不符、加载/调用抛异常、或字节码形状不符时测试失败。
 * 为什么这样设计：这些缺陷横跨 checker（成员解析）、translator（静态调用分流）、
 *   backend（INVOKESTATIC / fail-fast）三层；只测单层无法防止「类型过了但字节码错了」，
 *   故统一用真编译 + 真加载 + 真执行 + javap 证据守门。
 */
class MemberAccessTest {

    /**
     * 做什么：为单个用例准备干净输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：目录创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：沿用 StaticFieldTest / UnknownTypeTest 的 scratch 约定，产物可复查
     *   且不污染源码目录、不写仓库外路径。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "member-access", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译 Jasper 源码并断言成功，返回 class 输出目录。
     * 什么时候用：所有正向用例。
     * 前置条件：source 是完整单文件源码文本。
     * 失败模式：编译失败时以断言失败报告聚合诊断（含类别与位置）。
     * 为什么这样设计：把「编译 + 失败信息格式化」收敛到一处，避免每个用例重复样板。
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
     * 做什么：加载并静态调用无参方法（约定名为 `main`），返回其返回值。
     * 什么时候用：需要真执行生成代码时。
     * 前置条件：outputDir 下有已编译的 className.class，且该方法无参数。
     * 失败模式：类/方法不存在或调用抛异常时测试失败。
     * 为什么这样设计：反射调用能验证 JVM 链接期（字段/描述符/验证器）与运行期语义，
     *   比只看 .class 是否存在强得多。
     */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /**
     * 做什么：对生成的 class 运行 `javap -p -c`，返回反汇编文本并落盘到 build/。
     * 什么时候用：需要断言静态/实例访问指令形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么这样设计：javap 是 JDK 自带、与 ASM 版本无关的独立证据；落盘便于失败时人工复查。
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

    /**
     * 1. C23 负向：对已声明具名类型的未解析成员必须在编译期报错，且不落任何 .class。
     *
     * 断言消息里必须同时出现成员名与类型名（可操作）、且输出目录没有任何 class 文件
     * （说明翻译/后端根本没运行，不会产出对不存在字段的引用）。
     */
    @Test
    fun `unresolved member on a declared type is a compile error with no class output`() {
        val source = """
            package test;
            public class Foo {
                public var x: int32;
            }
            public function main(): int32 {
                var f = new Foo();
                return f.missing;
            }
        """.trimIndent()

        val outputDir = scratchDir("unresolved-member")
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "unresolved member access must fail compilation, got: $result"
        )
        val message = (result as JasperCompiler.CompileResult.Error).message
        assertTrue(message.contains("missing"), "diagnostic must name the missing member, got:\n$message")
        assertTrue(message.contains("Foo"), "diagnostic must name the receiver type, got:\n$message")

        // 编译在类型检查阶段即中断，翻译/后端不得执行，因此不应有任何 class 产物。
        val classFiles = outputDir.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .toList()
        assertTrue(classFiles.isEmpty(), "no .class must be emitted for unresolved members, found: $classFiles")
    }

    /**
     * 2. C23 正向对照：合法的继承成员（父类/接口）与数组内建 `length` 不得被误报。
     *
     * 覆盖「子类实例调用父类方法」与「接口实例调用父接口方法」两种继承访问，以及
     * `arr.length`（IR 层是 ARRAYLENGTH，不来自字段声明）。
     */
    @Test
    fun `inherited members and array length are valid member accesses`() {
        val source = """
            package test;
            class Base {
                public function base(): int32 { return 1; }
            }
            class Derived extends Base {
                public function own(): int32 { return 2; }
            }
            interface Greeter { function greet(): int32; }
            class Impl implements Greeter {
                public function greet(): int32 { return 3; }
            }
            public function main(): int32 {
                var d = new Derived();
                var g: Greeter = new Impl();
                var xs: int32[] = new int32[4];
                return d.base() + d.own() + g.greet() + xs.length;
            }
        """.trimIndent()
        val out = compile(source, "inherited-and-length")

        // 1 + 2 + 3 + 4 = 10。
        assertEquals(10, invokeStatic(out, "test.main", "main"))

        // 数组 length 必须是 ARRAYLENGTH，而不是读一个不存在的 length 字段。
        val code = javap(out, "test.main", "inherited-and-length")
        assertTrue(code.contains("arraylength"), "expected ARRAYLENGTH for xs.length:\n$code")
    }

    /**
     * 3. C23 正向对照：已声明的实例字段与静态字段访问都必须真能读写。
     *
     * `instance.name` 走 GETFIELD，`Config.limit` 走 GETSTATIC；两者相加得到 15，
     * 证明成员解析与字段指令选择都正确（静默兜底若存在会被这里的结果暴露）。
     */
    @Test
    fun `declared instance and static fields are accessible`() {
        val source = """
            package test;
            public class Config {
                public static var limit: int32 = 10;
                public var name: int32;
                public constructor(n: int32) { this.name = n; }
            }
            public function main(): int32 {
                var c = new Config(5);
                return c.name + Config.limit;
            }
        """.trimIndent()
        val out = compile(source, "instance-static-fields")

        assertEquals(15, invokeStatic(out, "test.main", "main"))

        val code = javap(out, "test.main", "instance-static-fields")
        assertTrue(code.contains("getfield"), "expected GETFIELD for instance field access:\n$code")
        assertTrue(code.contains("getstatic"), "expected GETSTATIC for static field access:\n$code")
    }

    /**
     * 4. C22：用户声明的类静态方法 `A.f()` 必须识别为静态调用。
     *
     * 断言三件事：运行返回 5；调用点字节码是 `invokestatic test/A.f:()I`（而非
     * getfield/invokevirtual）；被调方法自身带 ACC_STATIC（javap 显示 `public static`）。
     */
    @Test
    fun `class name static method call lowers to invokestatic`() {
        val source = """
            package test;
            public class A { public static function f(): int32 { return 5; } }
            public function main(): int32 { return A.f(); }
        """.trimIndent()
        val out = compile(source, "static-method-call")

        assertEquals(5, invokeStatic(out, "test.main", "main"))

        val mainCode = javap(out, "test.main", "static-method-call")
        assertTrue(
            mainCode.contains("invokestatic") && mainCode.contains("test/A.f:()I"),
            "expected INVOKESTATIC test/A.f:()I in test.main:\n$mainCode"
        )
        assertTrue(
            !mainCode.contains("invokevirtual") && !mainCode.contains("invokespecial"),
            "static call must not go through instance dispatch:\n$mainCode"
        )

        val aCode = javap(out, "test.A", "static-method-call")
        assertTrue(
            Regex("""public\s+static\s+int\s+f\(\);""").containsMatchIn(aCode),
            "declared static method must carry ACC_STATIC in test.A:\n$aCode"
        )
    }

    /**
     * 5. C23/枚举正向对照：源码层 `Types.values()` 与 `Types.valueOf(String)` 必须真能用。
     *
     * 这两个方法由后端为每个枚举类合成；源码层把它们当静态调用使用。断言：
     * `values().length == 3`、`valueOf("Varchar") == Types.Varchar`；并用 javap 复核
     * 调用点确实是 `invokestatic test/Types.values`（而非把类名当实例接收者）。
     */
    @Test
    fun `enum synthesized values and valueOf are usable at source level`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 {
                var n: int32 = Types.values().length;
                var t: Types = Types.valueOf("Varchar");
                if (t == Types.Varchar) { return n; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "enum-synthesized")

        // values().length == 3 且 valueOf("Varchar") 命中 Varchar，故返回 3。
        assertEquals(3, invokeStatic(out, "test.main", "main"))

        val code = javap(out, "test.main", "enum-synthesized")
        assertTrue(
            code.contains("invokestatic") && code.contains("test/Types.values"),
            "expected INVOKESTATIC test/Types.values:\n$code"
        )
        assertTrue(
            code.contains("test/Types.valueOf"),
            "expected INVOKESTATIC test/Types.valueOf:\n$code"
        )
    }

    /**
     * 6. `&&` 与比较的分组语义（含已知语法限制说明）。
     *
     * 已知限制（Q19 复核）：`JasperParser.g4` 的 `binaryExpression` 各运算符备选右侧是
     * `unaryExpression`（不是递归回 `binaryExpression`），因此 **grammar 实际不施加二元运算
     * 优先级**，`a == x && b == y` 会被解析成 `((a == x) && b) == y`（左结合、同优先级）。
     * 该 grammar 是权威仓库的 submodule，本实现禁止就地修改，修复须在上游完成。
     *
     * 因此在当前 grammar 下，比较参与逻辑运算时必须显式加括号。本用例锁定受支持的括号写法：
     * `(1 == 1) && (2 == 3)` 为假、`(2 == 2) && (3 == 3)` 为真，从而确认 `&&` 两侧都被正确求值。
     */
    @Test
    fun `logical and combines comparisons only with explicit grouping`() {
        val source = """
            package test;
            public function main(): int32 {
                if ((1 == 1) && (2 == 3)) { return 1; }
                if ((2 == 2) && (3 == 3)) { return 2; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "precedence")

        // 第一支为假、第二支为真，故返回 2。
        assertEquals(2, invokeStatic(out, "test.main", "main"))
    }
}
