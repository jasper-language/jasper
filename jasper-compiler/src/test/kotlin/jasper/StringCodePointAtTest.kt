// ============================================================================
// StringCodePointAtTest —— S5 第二步：string.codePointAt(i) 的端到端验收
// 所属层次：compiler 端到端测试（ANTLR → AST → checker → IR → JVM 字节码 → 真实加载执行）。
// 为什么必须真实执行：codePointAt 的核心风险不在“能否编译”，而在“码点索引是否被错误地
//   当成 UTF-16 码元索引”“代理对是否被拆开”“越界是否静默返回哨兵值”。这些只有加载
//   生成的 class 并调用才会暴露（VerifyError / NoSuchMethodError / 语义错位）。
// 产物位置：build/scratch/string-code-point-at/（仓库内，不写系统临时目录）。
// ============================================================================

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 做什么：验证 `string.codePointAt(i: int32): int32` 的语言语义与错误行为。
 *
 * 什么时候用：修改检查器的 string 内建成员分流、翻译器的合成调用、JVM 后端的
 *   码点/UTF-16 转换，或调整 string 索引单位时必须运行。
 *
 * 前置条件：`JasperCompiler.compileToDirWithIr` 能把源码写成完整 class；测试进程的
 *   JDK 支持 Java 8 的 `String.codePoints()` / `StringBuilder.appendCodePoint`。
 *
 * 失败模式：任何用例断言失败即说明语言语义或生成字节码偏离约定；负向用例还会
 *   断言“没有 class 产物”，防止编译失败却留下半成品。
 *
 * 为什么这样设计：固定用例覆盖 ASCII/BMP/补充平面/组合字符/边界/畸形输入，
 *   属性测试用固定种子随机生成码点序列并与 Java 的 `String.codePoints()` 参考实现
 *   逐项比对；两者互补——固定用例可读、属性测试扩大输入空间。
 */
class StringCodePointAtTest {

    /** 创建并清空一个模块内 scratch 目录（构建产物只留在仓库内）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "string-code-point-at", name)
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
            "compilation failed for $name: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 编译源码并断言失败，返回输出目录与聚合诊断文本。 */
    private fun compileExpectError(source: String, name: String): Pair<Path, String> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        val error = result as? JasperCompiler.CompileResult.Error
        assertNotNull(error, "expected compilation to fail for $name, got: $result")
        return outputDir to error.message
    }

    /** 加载输出目录并调用无参静态方法。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        return withClass(outputDir, className) { clazz ->
            clazz.methods.first { it.name == method && it.parameterCount == 0 }.invoke(null)
        }
    }

    /**
     * 加载输出目录里的类并交给 [block]；URLClassLoader 在 block 结束后关闭。
     *
     * 为什么暴露 Class 而不是每次重新加载：属性测试要调用上千次，重复建类加载器
     * 既慢又容易掩盖“类只加载一次”的真实运行期行为。
     */
    private fun <T> withClass(outputDir: Path, className: String, block: (Class<*>) -> T): T {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            return block(loader.loadClass(className))
        }
    }

    /** 递归统计输出目录里的 class 文件，用于断言负向用例“没有产物”。 */
    private fun classFiles(outputDir: Path): List<Path> =
        Files.walk(outputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.toList()
        }

    /** 从反射调用异常里取出被调用方法真正抛出的异常。 */
    private fun targetException(failure: InvocationTargetException): Throwable =
        failure.targetException

    /** 断言某个反射调用抛出指定类型的运行期异常，并返回该异常。 */
    private inline fun <reified T : Throwable> assertInvocationFailsWith(
        message: String,
        block: () -> Unit
    ): T {
        val failure = assertFailsWith<InvocationTargetException>(message) { block() }
        val target = targetException(failure)
        assertTrue(target is T, "$message: expected ${T::class.simpleName}, got ${target::class.qualifiedName}")
        return target as T
    }

    // ── 1. ASCII：码点数 == UTF-16 码元数，守住最基本路径 ──────────────────

    /** `"abc".codePointAt(1)` 必须等于 'b'（U+0062）。 */
    @Test
    fun `ascii code point at returns the character code`() {
        val source = """
            package test;
            public function main(): int32 { return "abc".codePointAt(1); }
        """.trimIndent()
        val out = compile(source, "ascii")
        assertEquals('b'.code, invokeStatic(out, "test.main", "main"))
    }

    // ── 2. BMP 中文：每个汉字 1 个码点 ────────────────────────────────────

    /** `"你好".length == 2`，索引 1 返回「好」（U+597D）。 */
    @Test
    fun `bmp chinese uses code point index`() {
        val source = """
            package test;
            public function lengthOf(): int32 { return "你好".length; }
            public function cp(): int32 { return "你好".codePointAt(1); }
        """.trimIndent()
        val out = compile(source, "bmp-chinese")
        assertEquals(2, invokeStatic(out, "test.lengthOf", "lengthOf"))
        assertEquals(0x597D, invokeStatic(out, "test.cp", "cp"))
    }

    // ── 3. 补充平面 emoji：1 个码点、2 个 UTF-16 码元 ─────────────────────

    /**
     * `"a😀b"` 的码点数为 3；索引 1 必须返回 U+1F600。
     * 若后端把语言索引直接传给 `String.codePointAt`，会取到高位代理项 0xD83D。
     */
    @Test
    fun `supplementary plane emoji merges surrogate pair`() {
        val source = """
            package test;
            public function lengthOf(): int32 { return "a😀b".length; }
            public function cp(): int32 { return "a😀b".codePointAt(1); }
        """.trimIndent()
        val out = compile(source, "supplementary-plane")
        assertEquals(3, invokeStatic(out, "test.lengthOf", "lengthOf"))
        assertEquals(0x1F600, invokeStatic(out, "test.cp", "cp"))
    }

    // ── 4. 组合字符：按码点计数，不按字素簇合并 ──────────────────────────

    /** `"e" + U+0301` 是 2 个码点；索引 0/1 分别返回 'e' 与 U+0301。 */
    @Test
    fun `combining sequence exposes separate code points`() {
        val text = "e\u0301"
        val source = """
            package test;
            public function lengthOf(): int32 { return "$text".length; }
            public function cp0(): int32 { return "$text".codePointAt(0); }
            public function cp1(): int32 { return "$text".codePointAt(1); }
        """.trimIndent()
        val out = compile(source, "combining")
        assertEquals(2, invokeStatic(out, "test.lengthOf", "lengthOf"))
        assertEquals('e'.code, invokeStatic(out, "test.cp0", "cp0"))
        assertEquals(0x0301, invokeStatic(out, "test.cp1", "cp1"))
    }

    // ── 5. 负索引与越界：结构化错误，不返回哨兵值 ────────────────────────

    /**
     * `-1`、`s.length` 以及更大的索引都必须抛 `IndexOutOfBoundsException`，
     * 消息含方法名与索引；不能返回 -1、0 或钳制到最后一个码点。
     */
    @Test
    fun `negative and out of bounds indices throw structured errors`() {
        val source = """
            package test;
            public function cp(s: string, i: int32): int32 { return s.codePointAt(i); }
            public function cpAtLength(s: string): int32 { return s.codePointAt(s.length); }
        """.trimIndent()
        val out = compile(source, "bounds")
        withClass(out, "test.cp") { clazz ->
            val cp = clazz.getMethod("cp", String::class.java, Int::class.java)
            val cases = listOf(-1 to "abc", 3 to "abc", 2 to "你好")
            for ((index, text) in cases) {
                val error = assertInvocationFailsWith<IndexOutOfBoundsException>(
                    "codePointAt($index) on \"$text\" must fail"
                ) { cp.invoke(null, text, index) }
                val message = error.message.orEmpty()
                assertTrue(
                    message.contains("String.codePointAt"),
                    "越界消息必须含方法名，实际: $message"
                )
                assertTrue(
                    message.contains("index $index"),
                    "越界消息必须含索引 $index，实际: $message"
                )
            }
        }
        // 显式用 s.length 作为索引：同样必须越界（证明索引单位与 .length 一致）。
        // 顶层函数各自生成独立的 class（test.cpAtLength），不能从 test.cp 上取方法。
        withClass(out, "test.cpAtLength") { clazz ->
            val atLength = clazz.getMethod("cpAtLength", String::class.java)
            val error = assertInvocationFailsWith<IndexOutOfBoundsException>(
                "codePointAt(s.length) must fail"
            ) { atLength.invoke(null, "abc") }
            assertTrue(
                error.message.orEmpty().contains("index 3"),
                "codePointAt(s.length) 的消息应含 index 3，实际: ${error.message}"
            )
        }
    }

    // ── 6. 孤立代理项：非法 Unicode，不返回替换字符 ──────────────────────

    /**
     * 通过反射边界把含孤立代理项的 JVM 字符串传入语言层；读取到该代理项时
     * 必须抛 `IllegalArgumentException`，消息说明 unpaired surrogate，且不能
     * 返回 U+FFFD 或代理区数值。
     */
    @Test
    fun `isolated surrogate is rejected as illegal unicode`() {
        val source = """
            package test;
            public function cp(s: string, i: int32): int32 { return s.codePointAt(i); }
        """.trimIndent()
        val out = compile(source, "isolated-surrogate")
        withClass(out, "test.cp") { clazz ->
            val cp = clazz.getMethod("cp", String::class.java, Int::class.java)
            val cases = listOf(
                "\uD800" to 0,      // 孤立高代理项
                "\uDC00" to 0,      // 孤立低代理项
                "a\uD800" to 1,     // 高代理项在末尾，缺少低代理项
                "a\uDC00" to 1      // 低代理项单独出现
            )
            for ((text, index) in cases) {
                val error = assertInvocationFailsWith<IllegalArgumentException>(
                    "isolated surrogate at index $index must be rejected"
                ) { cp.invoke(null, text, index) }
                val message = error.message.orEmpty()
                assertTrue(
                    message.contains("String.codePointAt"),
                    "非法 Unicode 消息必须含方法名，实际: $message"
                )
                assertTrue(
                    message.contains("invalid Unicode"),
                    "非法 Unicode 消息必须说明 invalid Unicode，实际: $message"
                )
                assertTrue(
                    message.contains("unpaired surrogate"),
                    "非法 Unicode 消息必须说明 unpaired surrogate，实际: $message"
                )
                assertFalse(
                    message.contains("\uFFFD"),
                    "非法 Unicode 不应退化成替换字符，实际: $message"
                )
            }
        }
    }

    // ── 7. 编译期签名校验：参数个数与类型 ────────────────────────────────

    /**
     * `codePointAt` 恰好接受 1 个 int32：缺参/多参/错误类型都必须在编译期报错，
     * 不能留到后端生成一个不存在或语义不同的 JVM 调用。
     */
    @Test
    fun `codePointAt rejects wrong arity and argument type`() {
        val noArgument = """
            package test;
            public function main(): int32 { return "abc".codePointAt(); }
        """.trimIndent()
        val (_, arityMessage) = compileExpectError(noArgument, "code-point-at-arity")
        assertTrue(
            arityMessage.contains("Function 'codePointAt' expects 1 argument(s), got 0"),
            "缺参应报告参数个数错误，实际: $arityMessage"
        )

        val wrongType = """
            package test;
            public function main(): int32 { return "abc".codePointAt("1"); }
        """.trimIndent()
        val (_, typeMessage) = compileExpectError(wrongType, "code-point-at-type")
        assertTrue(
            typeMessage.contains("Function 'codePointAt' argument 1 type mismatch"),
            "字符串实参应报告类型错误，实际: $typeMessage"
        )
    }

    // ── 8. 回归：s[i] 仍被检查器拒绝 ──────────────────────────────────────

    /** 新增 codePointAt 不能顺带放宽字符串整数下标；编译必须失败且无 class 产物。 */
    @Test
    fun `string integer indexing is still rejected`() {
        val source = """
            package test;
            public function main(): int32 {
                var s: string = "abc";
                return s[0];
            }
        """.trimIndent()
        val (out, message) = compileExpectError(source, "string-index")
        assertTrue(
            message.contains("cannot be indexed"),
            "字符串索引应被拒绝并给出 cannot be indexed 诊断，实际: $message"
        )
        assertEquals(0, classFiles(out).size, "编译失败时不应产生任何 class 文件")
    }

    // ── 8. 形式化验证（FV-B）：固定种子属性测试 ───────────────────────────

    /**
     * 随机生成码点序列（含 ASCII、中文、emoji、组合字符与一般 BMP），断言：
     *   - `.length` 等于参考实现的码点数；
     *   - 每个合法索引的 `codePointAt(i)` 等于参考实现 `String.codePoints()[i]`；
     *   - `-1` 与 `length` 必抛越界。
     *
     * 参考实现：JVM 的 `String.codePoints()`（按 Unicode scalar value 产出 int 流）。
     * 固定种子与样本数写在测试里，失败消息带样本序号与字符串，便于复现。
     */
    @Test
    fun `property - random code point sequences match the reference implementation`() {
        val seed = 20261023L
        val samples = 300
        val source = """
            package test;
            public function cp(s: string, i: int32): int32 { return s.codePointAt(i); }
            public function len(s: string): int32 { return s.length; }
        """.trimIndent()
        val out = compile(source, "property")
        val random = Random(seed)
        // 顶层函数各自生成独立 class：test.cp 与 test.len 必须分别加载。
        withClass(out, "test.cp") { cpClass ->
            withClass(out, "test.len") { lenClass ->
                val cp = cpClass.getMethod("cp", String::class.java, Int::class.java)
                val len = lenClass.getMethod("len", String::class.java)
                repeat(samples) { sampleIndex ->
                    val text = randomText(random)
                    val expected = text.codePoints().toArray()
                    val where = "seed=$seed sample=$sampleIndex text=${escapeForMessage(text)}"
                    assertEquals(
                        expected.size,
                        len.invoke(null, text),
                        "length mismatch ($where)"
                    )
                    for (index in expected.indices) {
                        assertEquals(
                            expected[index],
                            cp.invoke(null, text, index),
                            "codePointAt($index) mismatch ($where)"
                        )
                    }
                    for (badIndex in intArrayOf(-1, expected.size)) {
                        val error = assertInvocationFailsWith<IndexOutOfBoundsException>(
                            "out of bounds must fail at index $badIndex ($where)"
                        ) { cp.invoke(null, text, badIndex) }
                        val message = error.message.orEmpty()
                        assertTrue(
                            message.contains("String.codePointAt") &&
                                message.contains("index $badIndex"),
                            "out-of-bounds message must contain method and index ($where), got: $message"
                        )
                    }
                }
            }
        }
    }

    /** 生成 0..8 个随机合法码点（不产生代理区数值）。 */
    private fun randomText(random: Random): String {
        val length = random.nextInt(0, 9)
        val builder = StringBuilder()
        repeat(length) {
            builder.appendCodePoint(randomCodePoint(random))
        }
        return builder.toString()
    }

    /**
     * 生成一个 Unicode scalar value。
     *
     * 为什么排除代理区：语言层 string 的合法值不含 U+D800..U+DFFF；属性测试要验证
     *   “合法输入必须成功”，畸形输入由孤立代理项用例单独覆盖。
     */
    private fun randomCodePoint(random: Random): Int {
        return when (random.nextInt(5)) {
            0 -> random.nextInt(0x20, 0x7F)       // ASCII
            1 -> random.nextInt(0x4E00, 0xA000)   // CJK 统一表意文字
            2 -> random.nextInt(0x1F300, 0x1FB00) // 补充平面 emoji
            3 -> random.nextInt(0x0300, 0x0370)   // 组合附加符号
            else -> {
                // 一般 BMP：反复抽样直到落在代理区之外。
                var cp: Int
                do {
                    cp = random.nextInt(0x20, 0x10000)
                } while (cp in 0xD800..0xDFFF)
                cp
            }
        }
    }

    /** 把字符串转成可读的转义形式，避免失败消息里的 emoji/控制字符破坏输出。 */
    private fun escapeForMessage(text: String): String {
        val builder = StringBuilder("\"")
        for (ch in text) {
            when {
                ch == '\\' -> builder.append("\\\\")
                ch == '"' -> builder.append("\\\"")
                ch.code in 0x20..0x7E -> builder.append(ch)
                else -> builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            }
        }
        return builder.append('"').toString()
    }
}
