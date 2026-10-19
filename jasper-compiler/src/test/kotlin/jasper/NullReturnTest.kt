// ============================================================================
// NullReturnTest.kt —— Q26（C9 + C14）回归守门：`return null` 字节码与可空引用字段
// ============================================================================
// 职责：
//   - 固定 C9 的契约：源码里的 `null` 字面量在 `any` / 可空引用返回位置必须生成
//     `aconst_null; areturn`，不能因为 translator 用「Unit 类型的 IrConst(Null)」
//     表示无返回值而被误当成 void，只发裸 `areturn`（会触发
//     `VerifyError: Operand stack underflow`）。
//   - 固定 C14 的契约：`C?` 字段的 JVM 字段描述符必须是 `Lp/C;`（引用），
//     构造、读写、经字段调用都正确；本用例用 javap 留证，防止类型映射回退。
//   - 同时正向覆盖可空局部、可空参数、提前 return、接口返回、`any[]` 元素等
//     `null` 出现的常见位置，确认它们与 C9 同源或已正常。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开入口与 JDK 自带 `javap` / `URLClassLoader`，
//   不触碰 translator / backend 内部实现，因此断言的是「可观察的字节码与运行行为」，
//   内部重构（例如把判据从 kind 改成返回描述符）不会无意义地打破这些用例。
//
// 根因备注（供后来者接手）：
//   translator 把两种语义都编码成 `IrConstKind.Null`：
//     1) `typeMapper.unitExpr()`：类型 `kotlin.Unit`（描述符 `V`）——表示「无值」；
//     2) `typeMapper.nullConst()`：类型 `kotlin.Any?`（描述符 `Ljava/lang/Object;`）
//        ——表示源码 `null`，必须压栈。
//   后端旧判据只看 `kind == Null`，把第 2 种也当成无值。修复后由
//   `JvmIrBackend.isIrVoidExpr` 按「JVM 返回描述符是否为 V」区分；本文件的用例
//   就是这条判据的守门人。
//
// 产物位置：所有 .class 与 javap 证据都写在模块自己的
//   `build/scratch/null-return/` 下，不落项目外临时目录。
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
 * 做什么：Q26（C9 + C14）的端到端回归测试。
 * 什么时候用：`./gradlew test` 全量回归时自动执行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译结果与预期不符、加载/调用抛异常、或字节码形状不符时断言失败。
 * 为什么这样设计：`return null` 的缺陷横跨 translator（null 字面量类型）与 backend
 *   （判断是否压返回值）两层，只测 IR 或只看 .class 是否存在都不足以发现
 *   「类型检查通过但验证器拒绝」的问题；因此统一用真编译 + 真加载 + 真执行 +
 *   javap 证据守门。C14 只需回归，故不重写类型映射，只用 javap 锁住描述符。
 */
class NullReturnTest {

    /**
     * 做什么：为单个用例准备干净输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：目录创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：沿用仓库既有 scratch 约定，产物可复查且不写仓库外路径。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "null-return", name)
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
     * 做什么：编译 Jasper 源码并断言失败，返回输出目录与诊断文本。
     * 什么时候用：负向/边界用例（如向非可空返回类型 `return null`）。
     * 前置条件：source 应在类型检查阶段被拒绝。
     * 失败模式：编译意外成功时断言失败。
     * 为什么这样设计：负向用例既需要诊断文本，也需要检查「未产出 .class」，
     *   因此同时返回目录，让调用方可以继续断言副作用。
     */
    private fun compileExpectError(source: String, name: String): Pair<Path, String> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "expected compilation to fail for $name, got: $result"
        )
        return outputDir to (result as JasperCompiler.CompileResult.Error).message
    }

    /**
     * 做什么：加载并静态调用无参方法（约定名为 `main`），返回其返回值。
     * 什么时候用：需要真执行生成代码时。
     * 前置条件：outputDir 下有已编译的 className.class，且该方法无参数。
     * 失败模式：类/方法不存在或调用抛异常（如 VerifyError）时测试失败。
     * 为什么这样设计：反射调用能验证 JVM 链接期（描述符/验证器）与运行期语义，
     *   比只看 .class 是否存在强得多；本缺陷正是「能编译、加载时才炸」。
     */
    private fun invokeStaticNoArg(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /**
     * 做什么：对生成的 class 运行 `javap -p -c`，返回反汇编文本并落盘到 build/。
     * 什么时候用：需要断言 `aconst_null` / `areturn` / 字段描述符等指令形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么这样设计：javap 是 JDK 自带、与 ASM 版本无关的独立证据；落盘便于失败时
     *   人工复查，也避免「碰巧能跑」掩盖错误的指令。
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

    // 「aconst_null 紧跟 areturn」的形状：这正是 C9 修复后的正确形态。
    // 允许中间夹着 javap 的行号与任意空白，避免因指令偏移变化而误报。
    private val nullThenReturn = Regex("""aconst_null\s*\n\s*\d+:\s*areturn""")

    /**
     * 1. C9：`any` 返回类型的 `if` 分支 `return null` 必须压 null。
     *
     * 复现任务书原始形状：true 分支返回对象、false 分支 `return null`。修复前 false
     * 分支只有一个裸 `areturn`，加载时 `VerifyError: Operand stack underflow`。
     * 这里既断言运行时 `maybe(false) == null`，也用 javap 断言
     * `aconst_null; areturn` 确实存在。
     */
    @Test
    fun `any return with if branch emits aconst_null before areturn`() {
        val source = """
            package p;
            public class Node {
                public var code: int32;
                public constructor(code: int32) { this.code = code; }
            }
            function maybe(flag: bool): any {
                if (flag) { return new Node(1); }
                return null;
            }
            public function main(): int32 {
                var x: any = maybe(false);
                if (x == null) {
                    if (maybe(true) != null) { return 1; }
                }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "any-return")

        // false 分支必须正常返回 null，而不是在加载期就抛 VerifyError。
        assertEquals(1, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.maybe", "any-return")
        assertTrue(
            code.contains("public static java.lang.Object maybe(boolean);"),
            "any return must use Object descriptor:\n$code"
        )
        assertTrue(
            nullThenReturn.containsMatchIn(code),
            "false branch must emit aconst_null immediately before areturn:\n$code"
        )
        assertTrue(
            Regex("areturn").findAll(code).count() >= 2,
            "both branches must return a value (two areturn):\n$code"
        )
    }

    /**
     * 2. C9：可空引用返回类型 `C?` 的 `return null` 必须生成合法引用返回。
     *
     * 与用例 1 的区别是返回描述符为 `Lp/C;`（不是 Object），用于确认修复不依赖
     * 「擦除到 Object」这一特殊情况：任何非 V 的返回描述符都应压 null。
     */
    @Test
    fun `nullable reference return supports return null`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            function maybe(flag: bool): C? {
                if (flag) { return new C(); }
                return null;
            }
            public function main(): int32 {
                if (maybe(false) == null) {
                    if (maybe(true) != null) { return 1; }
                }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "nullable-return")

        assertEquals(1, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.maybe", "nullable-return")
        assertTrue(
            code.contains("public static p.C maybe(boolean);"),
            "nullable reference return must use p.C descriptor:\n$code"
        )
        assertTrue(
            nullThenReturn.containsMatchIn(code),
            "nullable return branch must emit aconst_null then areturn:\n$code"
        )
    }

    /**
     * 3. 可空局部 `var x: C? = null`：初始化与 `x == null` 判定都正常。
     *
     * 该形状在本缺陷前已可用，保留用例是为了防止后端把「null 常量」统一改成
     * 「无值」时误伤局部变量初始化路径。
     */
    @Test
    fun `nullable local initialized to null is usable in null check`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            public function main(): int32 {
                var x: C? = null;
                if (x == null) { return 2; }
                return x.v();
            }
        """.trimIndent()
        val out = compile(source, "nullable-local")

        assertEquals(2, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.main", "nullable-local")
        assertTrue(code.contains("aconst_null"), "local init must emit aconst_null:\n$code")
        assertTrue(code.contains("astore"), "null must be stored into the local slot:\n$code")
    }

    /**
     * 4. 可空参数 `use(x: C?)`：传 `null` 与传对象两条路径都正常。
     *
     * 断言运行时 `use(null) + use(new C()) == 7 + 1`，并用 javap 确认参数描述符是
     * `Lp/C;`、调用点确实压了 `aconst_null`。
     */
    @Test
    fun `nullable parameter accepts null and non-null values`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            function use(x: C?): int32 {
                if (x == null) { return 7; }
                return x.v();
            }
            public function main(): int32 {
                return use(null) + use(new C());
            }
        """.trimIndent()
        val out = compile(source, "nullable-param")

        assertEquals(8, invokeStaticNoArg(out, "p.main", "main"))

        val useCode = javap(out, "p.use", "nullable-param")
        assertTrue(
            useCode.contains("public static int use(p.C);"),
            "nullable parameter must use p.C descriptor:\n$useCode"
        )
        val mainCode = javap(out, "p.main", "nullable-param")
        assertTrue(
            mainCode.contains("aconst_null"),
            "call site passing null must emit aconst_null:\n$mainCode"
        )
    }

    /**
     * 5. C14 回归：`C?` 字段的描述符/读写/经字段调用都正确。
     *
     * 任务书实测该形状已正常（修复来自 Q21/Q25 的类型映射），本用例只做守门：
     *   - javap 字段声明必须是 `p.C slot;`（引用），不是 Object；
     *   - `getfield` / `putfield` 的描述符必须是 `Lp/C;`；
     *   - `this.slot.v()` 能真正分派；把字段清成 null 后 `== null` 成立。
     * 这里刻意不重写类型映射，避免为了「修 C14」而破坏已正确的路径。
     */
    @Test
    fun `nullable reference field keeps reference descriptor and supports access`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            public class H {
                private var slot: C?;
                public constructor() { this.slot = new C(); }
                public function get(): int32 { return this.slot.v(); }
                public function clear(): void { this.slot = null; }
                public function isNull(): bool { return this.slot == null; }
            }
            public function main(): int32 {
                var h = new H();
                var a: int32 = h.get();
                h.clear();
                if (h.isNull()) { return a + 10; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "nullable-field")

        // get() 返回 1；clear() 后 isNull() 为真，故 1 + 10 = 11。
        assertEquals(11, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.H", "nullable-field")
        assertTrue(
            Regex("""\bp\.C slot;""").containsMatchIn(code),
            "nullable field declaration must use reference descriptor p.C:\n$code"
        )
        assertTrue(
            code.contains("Field slot:Lp/C;"),
            "getfield/putfield must reference slot:Lp/C;:\n$code"
        )
        assertTrue(code.contains("getfield"), "field read must use getfield:\n$code")
        assertTrue(code.contains("putfield"), "field write must use putfield:\n$code")
        assertTrue(code.contains("aconst_null"), "clearing field must emit aconst_null:\n$code")
    }

    /**
     * 6. 提前 return：`if (x == null) return null;` 与正常 `return x;` 混用。
     *
     * 该用例覆盖「同一方法里既有空返回又有非空返回」的控制流，确认 null 只压在
     * 需要它的那条分支上，不会污染另一条路径的栈。
     */
    @Test
    fun `early return null mixes with normal return`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            function pick(x: C?): C? {
                if (x == null) { return null; }
                return x;
            }
            public function main(): int32 {
                if (pick(null) == null) {
                    if (pick(new C()) != null) { return 1; }
                }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "early-return")

        assertEquals(1, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.pick", "early-return")
        assertTrue(
            nullThenReturn.containsMatchIn(code),
            "early return branch must emit aconst_null then areturn:\n$code"
        )
        assertTrue(
            Regex("areturn").findAll(code).count() >= 2,
            "both early and normal return paths must return a value:\n$code"
        )
    }

    /**
     * 7. 负向/边界：向**非可空**返回类型 `return null` 必须在编译期被拒绝。
     *
     * 现状（Q26 实测）：checker 把 `null` 推断为 `any?`，与非可空 `C` 不兼容，报
     * `Return type mismatch: expected C, got any?`。本用例把这条语义固定下来：
     * 若未来放宽为「允许并运行期抛异常」，测试会失败并提醒更新契约；同时断言
     * 编译失败时不产出任何 .class，避免半成品字节码落盘。
     */
    @Test
    fun `return null from non-nullable return type is a compile error`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            function f(x: int32): C {
                if (x == 0) { return null; }
                return new C();
            }
            public function main(): int32 { return f(0).v(); }
        """.trimIndent()
        val (out, message) = compileExpectError(source, "non-nullable-return")

        assertTrue(
            message.contains("Return type mismatch"),
            "diagnostic must explain the return type mismatch, got:\n$message"
        )
        assertTrue(
            message.contains("expected C") && message.contains("any?"),
            "diagnostic must name expected/actual types, got:\n$message"
        )
        val classFiles = out.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .toList()
        assertTrue(classFiles.isEmpty(), "no .class must be emitted on type error, found: $classFiles")
    }

    /**
     * 8. javap 专项：无条件 `return null` 必须正好是 `aconst_null; areturn`。
     *
     * 这是 C9 的最小复现：修复前翻译成单个裸 `areturn`（加载即 VerifyError），
     * 修复后是两条指令。断言「aconst_null 紧跟 areturn」且方法体没有 `athrow`
     * 等掩盖手段，确保修复是真正把 null 压栈，而不是补控制流绕过去。
     */
    @Test
    fun `unconditional return null emits exactly aconst_null and areturn`() {
        val source = """
            package p;
            function onlyNull(): any { return null; }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val out = compile(source, "only-null")

        assertEquals(0, invokeStaticNoArg(out, "p.main", "main"))

        val code = javap(out, "p.onlyNull", "only-null")
        assertTrue(
            nullThenReturn.containsMatchIn(code),
            "unconditional return null must emit aconst_null then areturn:\n$code"
        )
        assertTrue(
            Regex("areturn").findAll(code).count() == 1,
            "onlyNull must have exactly one areturn:\n$code"
        )
        assertTrue(
            !code.contains("athrow"),
            "null return must not be disguised with athrow:\n$code"
        )
    }

    /**
     * 9. 其它 `null` 出现位置（接口返回 / `any[]` 元素）的正向守门。
     *
     * 任务书要求评估 `null` 在数组元素、接口返回等位置是否同源。实测这些位置在
     * 修复后均正常，故以正向用例锁住：
     *   - 接口方法 `get(flag): C?` 的 null 分支生成 `aconst_null; areturn`；
     *   - `any[]` 元素写入 null 走 `aastore`，读取比较正常。
     * 已知语法边界（不在本任务范围、且禁止改 .g4）：`C?[]` 这种「可空元素数组」
     * 类型写法当前无法解析（`no viable alternative at input 'C?['`），因此这里用
     * `any[]` 覆盖数组元素位，而不是假装 `C?[]` 可用。
     */
    @Test
    fun `null in interface return and any array element stays valid`() {
        val source = """
            package p;
            public class C { public function v(): int32 { return 1; } }
            public interface I { function get(flag: bool): C?; }
            public class Impl implements I {
                public function get(flag: bool): C? {
                    if (flag) { return new C(); }
                    return null;
                }
            }
            public function main(): int32 {
                var i: I = new Impl();
                if (i.get(false) != null) { return 0; }
                var xs: any[] = new any[2];
                xs[0] = null;
                if (xs[0] == null) { return 3; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "other-positions")

        assertEquals(3, invokeStaticNoArg(out, "p.main", "main"))

        val implCode = javap(out, "p.Impl", "other-positions")
        assertTrue(
            nullThenReturn.containsMatchIn(implCode),
            "interface implementation return null must emit aconst_null then areturn:\n$implCode"
        )
        val mainCode = javap(out, "p.main", "other-positions")
        assertTrue(
            mainCode.contains("aastore"),
            "null stored into any[] element must use aastore:\n$mainCode"
        )
    }
}
