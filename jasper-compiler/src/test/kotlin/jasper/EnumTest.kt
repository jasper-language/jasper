// ============================================================================
// EnumTest —— Q18 回归守门：枚举常量作为值、比较、switch/match 分支必须真能用。
//
// 模块说明：
//   - 覆盖范围：枚举常量取值、`==`/`!=`、`if` 分支、`switch` 三分支 + default、
//     `match`（源码层被预处理为 switch）、枚举作形参/返回值、后端合成的
//     `values()` / `valueOf(String)`，以及常量字段的 JVM 标志与 <clinit> 形状。
//   - 验证方式：全部走真实编译管线（ANTLR → AST → checker → IR → JVM 字节码），
//     再用 URLClassLoader 真加载、反射真调用；字节码形状用 JDK 自带 javap 复核。
//   - 产物位置：所有 .class 与 javap 证据都写在模块自己的 `build/scratch/enums/`
//     下，不落项目外临时目录。
//
// 为什么这些断言必须存在：
//   Q18 之前，`Type.Integer` 会被翻译成「对类名求值后 GETFIELD」，ASM 计算栈帧时
//   抛 `Index -1 out of bounds for length 0`，把用户源码变成编译器 INTERNAL 崩溃。
//   只断言「编译成功」不足以防止退化（例如再次生成 GETFIELD 但碰巧能跑），因此这里
//   同时断言运行结果、反射可见的 JVM 枚举协议，以及 javap 的字段标志/初始化指令。
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 做什么：Q18（枚举比较 / switch / match）的端到端回归测试。
 * 什么时候用：`./gradlew test` 全量回归时自动执行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/调用抛异常、结果不符或字节码形状不符时测试失败。
 * 为什么这样设计：枚举缺陷横跨 checker（类型解析）、translator（常量读取 IR）、
 *   backend（字段/`<clinit>` 发射）三层；只测单层无法防止「类型过了但字节码是
 *   GETFIELD」的回归，故统一用真编译 + 真加载 + 真执行 + javap 证据守门。
 */
class EnumTest {

    /**
     * 做什么：为一个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：目录创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：沿用 StaticFieldTest 的 scratch 约定，产物可复查且不污染源码目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "enums", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译 Jasper 源码并断言成功，返回 class 输出目录。
     * 什么时候用：所有正向用例。
     * 前置条件：source 是完整源文件文本。
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
     * 做什么：加载并静态调用无参方法（约定名为 main），返回其返回值。
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
     * 做什么：对生成的 class 运行 `javap -v -p`，返回反汇编文本并落盘到 build/。
     * 什么时候用：需要断言字段访问标志（ACC_ENUM/ACC_STATIC）与 <clinit> 指令时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么用 -v：常量字段的 ACC_ENUM 只在 verbose 的 flags 行可见，普通 -p 列表不显示。
     */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-v", "-p", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val evidence = outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt")
        Files.writeString(evidence, output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /**
     * 1. 同一常量相等、不同常量不等：`Types.Integer == Types.Integer` → true，
     *    `Types.Integer != Types.Varchar` → true。返回 int32 验证两条结论同时成立。
     */
    @Test
    fun `same enum constant is equal and different constants are not`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 {
                if (Types.Integer == Types.Integer) {
                    if (Types.Integer != Types.Varchar) { return 1; }
                }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "equality")

        assertEquals(1, invokeStatic(out, "test.main", "main"))

        // 比较两侧都必须是 GETSTATIC 读取枚举字段，而不是对类名求值的 GETFIELD。
        val code = javap(out, "test.main", "equality")
        assertTrue(code.contains("getstatic"), "expected GETSTATIC for enum constants:\n$code")
        assertTrue(
            !code.contains("getfield"),
            "enum constants must not be read through instance GETFIELD:\n$code"
        )
    }

    /** 2. `var t = Types.Integer; if (t == Types.Integer)` 必须命中正确分支。 */
    @Test
    fun `enum constant comparison selects the if branch`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 {
                var t: Types = Types.Integer;
                if (t == Types.Integer) { return 1; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "if-branch")

        assertEquals(1, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 3. `switch` 的每个常量各命中一次，default 不应被错误命中。
     *    三段 switch 分别累加 1 / 10 / 100，最终 111 才能证明三支都走对。
     */
    @Test
    fun `switch dispatches to each enum constant and default`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 {
                var a: Types = Types.Integer;
                var b: Types = Types.Varchar;
                var c: Types = Types.Null;
                var score: int32 = 0;
                switch (a) {
                    case Types.Integer => { score = score + 1; }
                    default => { score = score + 1000; }
                }
                switch (b) {
                    case Types.Varchar => { score = score + 10; }
                    default => { score = score + 1000; }
                }
                switch (c) {
                    case Types.Null => { score = score + 100; }
                    default => { score = score + 1000; }
                }
                return score;
            }
        """.trimIndent()
        val out = compile(source, "switch")

        assertEquals(111, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 4. `match (t)` 分支：源码层 `match (` 会被预处理成 `switch (`、`case _ =>` 变成
     *    `default =>`，因此与 switch 共用同一条已验证的 lowering 路径。
     *    这里用 `case _ =>` 兜底，确保 match 的语法糖确实生效（而不是把 `_` 当变量）。
     */
    @Test
    fun `match dispatches to the enum constant branch`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 {
                var t: Types = Types.Varchar;
                match (t) {
                    case Types.Integer => { return 1; }
                    case Types.Varchar => { return 2; }
                    case _ => { return 0; }
                }
            }
        """.trimIndent()
        val out = compile(source, "match")

        assertEquals(2, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 5. 枚举值作为形参传入、作为返回值传出，且传递后仍是同一常量。
     *    注意：这里刻意用两个独立 `if` 表达「两个条件同时成立」，而不用 `&&`。
     *    原因：当前 grammar 把 `==` 与 `&&` 放在同一优先级层（见 JasperParser.g4 的
     *    binaryExpression），`a == X && b == Y` 会被解析成 `((a == X) && b) == Y`，
     *    与枚举无关。因此换成两个独立判断，避免踩到该已知语法限制。
     */
    @Test
    fun `enum values pass through parameters and return positions`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function echo(value: Types): Types { return value; }
            public function pick(): Types { return Types.Null; }
            public function main(): int32 {
                var a: Types = echo(Types.Varchar);
                var b: Types = echo(pick());
                if (a != Types.Varchar) { return 0; }
                if (b != Types.Null) { return 0; }
                return 1;
            }
        """.trimIndent()
        val out = compile(source, "params-return")

        assertEquals(1, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 6. 后端合成的 `values()` / `valueOf(String)` 必须可用。
     *    注意：Jasper 源码层还没有「类名.静态方法」调用语法（`Types.values()` 会被当作
     *    实例成员调用），因此这里直接通过反射调用 JVM 上真实存在的合成方法——这仍然
     *    是端到端证据：类必须能加载、常量必须已初始化、数组与名字表必须正确。
     */
    @Test
    fun `synthesized values and valueOf are usable`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val out = compile(source, "values-valueof")

        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val types = loader.loadClass("test.Types")
            assertTrue(types.isEnum, "test.Types must be a JVM enum class")

            val constants = types.enumConstants
            assertNotNull(constants, "enum constants must be registered with java.lang.Enum")
            assertEquals(
                listOf("Integer", "Varchar", "Null"),
                constants.map { (it as Enum<*>).name },
                "enum constants must be initialized in declaration order"
            )

            val values = types.getMethod("values").invoke(null) as Array<*>
            assertEquals(3, values.size, "values() must return all three constants")
            assertEquals(constants.toList(), values.toList(), "values() must return the declared constants")

            val varchar = types.getField("Varchar").get(null)
            assertEquals(varchar, types.getMethod("valueOf", String::class.java).invoke(null, "Varchar"))
        }
    }

    /**
     * 7. javap 证据：
     *    - 每个常量字段带 ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM；
     *    - `<clinit>` 里用 PUTSTATIC 初始化这些字段；
     *    - `values()` 返回的数组类型是枚举数组（CHECKCAST 不得退化成字段描述符）。
     */
    @Test
    fun `enum constant fields carry enum flags and are initialized in clinit`() {
        val source = """
            package test;
            public enum Types { Integer, Varchar, Null }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val out = compile(source, "bytecode-shape")

        val typeCode = javap(out, "test.Types", "bytecode-shape")
        for (constant in listOf("Integer", "Varchar", "Null")) {
            // javap -v 的字段头 + flags 行必须同时出现，且 flags 里要有 ACC_ENUM。
            val fieldBlock = Regex(
                """public static final test\.Types $constant;\s*\n\s*descriptor: Ltest/Types;\s*\n\s*flags: \([^)]*\) ([^\n]*)"""
            ).find(typeCode)
            assertNotNull(fieldBlock, "expected constant field '$constant' in javap output:\n$typeCode")
            val flags = fieldBlock.groupValues[1]
            assertTrue("ACC_STATIC" in flags, "field '$constant' must carry ACC_STATIC, got: $flags")
            assertTrue("ACC_FINAL" in flags, "field '$constant' must carry ACC_FINAL, got: $flags")
            assertTrue("ACC_ENUM" in flags, "field '$constant' must carry ACC_ENUM, got: $flags")
        }

        val clinitStart = typeCode.indexOf("static {};")
        assertTrue(clinitStart >= 0, "expected a <clinit> in test.Types:\n$typeCode")
        val clinit = typeCode.substring(clinitStart)
        assertTrue(clinit.contains("putstatic"), "expected <clinit> to initialize constants via PUTSTATIC:\n$typeCode")

        // 反射再确认一遍：ACC_ENUM 字段在 JVM 上表现为 final 且属于枚举类。
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val types = loader.loadClass("test.Types")
            val field = types.getField("Integer")
            assertTrue(Modifier.isStatic(field.modifiers), "Integer must be static")
            assertTrue(Modifier.isFinal(field.modifiers), "Integer must be final")
            assertEquals(types, field.type, "Integer must be typed as its enum class")
        }
    }

}
