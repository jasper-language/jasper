// ============================================================================
// ThrowsManifestTest.kt —— `throws` 元数据 + 编译器 manifest 的回归测试
// ============================================================================
// 职责：
//   钉住 Q24 / C19 的交付面：`throws` 子句在 AST 层被解析（method / constructor /
//   interface method 三条路径），并在成功编译后写进 `<outputDir>/jasper-manifest.json`。
//   所有用例都走**真编译**（ANTLR → AST → checker → IR → JVM 字节码写盘）后读回
//   manifest 文本断言，避免测到假实现。
//
// 覆盖条目：
//   1. 类方法 throws 单个类型；
//   2. 多异常顺序；
//   3. 构造器 throws；
//   4. 接口方法 throws；
//   5. 无 throws 写 `[]`；
//   6. 不强制捕获（声明不抛、抛出未声明都仍编译成功）；
//   7. JSON 基本合法（schema、名字片段、括号/引号配平）；
//   8. 多文件 compileAllToDir 两个文件的 throws 都出现。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 公开入口与 JasperManifest 渲染 API（以及为
//   转义用例手工构造的 ast 数据类），不触碰编译器内部实现。
//   测试产物写到本模块的 build/scratch/ 下，绝不使用 /tmp 或仓库外的临时目录。
//
// 边界（与用户裁决一致）：
//   本测试**不**断言任何 checked exception 强制——`throws` 只是签名元信息；
//   manifest 是**编译器侧 JSON**，不是 OS 的 `.jaso` 二进制格式（见 FINDINGS #18/#20）。
// ============================================================================

package jasper

import jasper.ast.JasClass
import jasper.ast.JasConstructor
import jasper.ast.JasFunction
import jasper.ast.JasInterface
import jasper.ast.JasNamedType
import jasper.ast.JasSourceFile
import jasper.compiler.JasperCompiler
import jasper.compiler.JasperManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `throws` 解析与 manifest 写出的端到端测试集合。
 *
 * 做什么：编译最小复现源码到临时输出目录，读回 `jasper-manifest.json` 并断言关键片段。
 * 什么时候用：改动 AST builder 的 throws 处理、或 manifest 渲染/写出逻辑后运行。
 * 前置条件：无外部依赖；编译器可在当前 JVM 内自举完成编译。
 * 失败模式：编译失败时以编译诊断断言失败；manifest 缺失/片段不符时以断言失败。
 * 为什么直接断言文本片段：本次交付就是「稳定的 JSON 文本契约」，片段断言能同时
 *   锁定字段名、缩进结构与数组形态，且无需引入 JSON 库依赖。
 */
class ThrowsManifestTest {

    /** 为单个用例准备干净的输出目录；路径固定在模块 build/scratch 下。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "throws-manifest", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 单文件编译并断言成功，返回输出目录。 */
    private fun compileOne(source: String, name: String): Path {
        val out = scratchDir(name)
        val result = JasperCompiler().compileToDir(source, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return out
    }

    /** 多文件编译并断言成功，返回输出目录。 */
    private fun compileMany(sources: Map<String, String>, name: String): Path {
        val out = scratchDir(name)
        val result = JasperCompiler().compileAllToDir(sources, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return out
    }

    /** 读回输出目录里的 manifest 文本。 */
    private fun readManifest(out: Path): String {
        val manifest = out.resolve(JasperManifest.FILE_NAME)
        assertTrue(Files.exists(manifest), "expected ${JasperManifest.FILE_NAME} at $out")
        return Files.readString(manifest)
    }

    // ── 1. 类方法 throws 单个类型 ─────────────────────────────────────────

    @Test
    fun `class method throws single type appears in manifest`() {
        val source = """
            package test;
            public class Svc {
                public function f(): void throws std.Error { }
            }
        """.trimIndent()
        val text = readManifest(compileOne(source, "class-method-single"))
        assertTrue(text.contains("\"schema\": \"jasper-manifest/0\""), "schema missing in:\n$text")
        assertTrue(text.contains("\"name\": \"test.Svc\""), "type name missing in:\n$text")
        assertTrue(text.contains("\"kind\": \"class\""), "kind missing in:\n$text")
        assertTrue(text.contains("\"name\": \"f\""), "method name missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"std.Error\"]"), "throws fragment missing in:\n$text")
    }

    // ── 2. 多异常顺序 ─────────────────────────────────────────────────────

    @Test
    fun `multiple throws keep declaration order`() {
        val source = """
            package test;
            public class Svc {
                public function f(): void throws std.Error, app.Boom, net.Timeout { }
            }
        """.trimIndent()
        val text = readManifest(compileOne(source, "multi-throws"))
        assertTrue(
            text.contains("\"throws\": [\"std.Error\", \"app.Boom\", \"net.Timeout\"]"),
            "ordered throws list missing in:\n$text"
        )
    }

    // ── 3. 构造器 throws ──────────────────────────────────────────────────

    @Test
    fun `constructor throws appears in manifest`() {
        val source = """
            package test;
            public class Svc {
                public constructor() throws std.Error { }
            }
        """.trimIndent()
        val text = readManifest(compileOne(source, "constructor-throws"))
        assertTrue(text.contains("\"name\": \"<init>\""), "init entry missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"std.Error\"]"), "ctor throws fragment missing in:\n$text")
    }

    // ── 4. 接口方法 throws ────────────────────────────────────────────────

    @Test
    fun `interface method throws appears in manifest`() {
        val source = """
            package test;
            public interface Sink {
                public function send(): void throws std.Error;
            }
        """.trimIndent()
        val text = readManifest(compileOne(source, "interface-method-throws"))
        assertTrue(text.contains("\"name\": \"test.Sink\""), "iface name missing in:\n$text")
        assertTrue(text.contains("\"kind\": \"interface\""), "iface kind missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"std.Error\"]"), "iface throws fragment missing in:\n$text")
    }

    // ── 5. 无 throws → 写空数组 ───────────────────────────────────────────

    @Test
    fun `absent throws is written as empty array`() {
        val source = """
            package test;
            public class Svc {
                public function f(): void { }
            }
        """.trimIndent()
        val text = readManifest(compileOne(source, "no-throws"))
        assertTrue(text.contains("\"name\": \"f\""), "method missing in:\n$text")
        assertTrue(text.contains("\"throws\": []"), "expected empty throws array in:\n$text")
    }

    // ── 6. 不强制捕获 ─────────────────────────────────────────────────────

    @Test
    fun `declared throws without throwing still compiles`() {
        val source = """
            package test;
            public function f(): int32 throws std.Error { return 1; }
            public function main(): int32 { return f(); }
        """.trimIndent()
        val out = compileOne(source, "declared-not-thrown")
        val text = readManifest(out)
        // 调用点未 try/catch 仍编译成功（compileOne 已断言）；且元信息被记录。
        assertTrue(text.contains("\"name\": \"test.f\""), "fq function name missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"std.Error\"]"), "declared throws missing in:\n$text")
    }

    @Test
    fun `throwing without declaration still compiles`() {
        val source = """
            package test;
            public function boom(): void { throw "boom"; }
        """.trimIndent()
        val text = readManifest(compileOne(source, "thrown-not-declared"))
        assertTrue(text.contains("\"name\": \"test.boom\""), "fq function name missing in:\n$text")
        // 未声明 throws：按约定写空数组（不因为函数体有 throw 而自动补）。
        assertTrue(text.contains("\"throws\": []"), "expected empty throws array in:\n$text")
    }

    // ── 7. JSON 基本合法性 ────────────────────────────────────────────────

    @Test
    fun `manifest text is structurally balanced json`() {
        val source = """
            package test;
            public class Svc {
                public function f(): void throws std.Error, app.Boom { }
                public constructor() throws std.Error { }
            }
            public interface Sink { public function send(): void throws std.Error; }
            public function top(): void throws std.Error { }
        """.trimIndent()
        val text = readManifest(compileOne(source, "json-balance"))
        assertTrue(text.contains("\"schema\": \"jasper-manifest/0\""), "schema missing")
        assertJsonBalanced(text)
    }

    @Test
    fun `manifest escapes special characters in names`() {
        // 手工构造 AST：类型名里塞入引号 / 反斜杠 / 换行，验证转义不会产出非法 JSON。
        val weirdName = "a\"b\\c\nd"
        val fn = JasFunction(
            name = "f", parameters = emptyList(), returnType = null, body = null,
            modifiers = emptyList(), throwsTypes = listOf(JasNamedType(weirdName))
        )
        val ast = JasSourceFile(packageName = "p", declarations = listOf(fn))
        val text = JasperManifest.render(listOf(ast))
        // 原始引号与反斜杠必须被转义；换行必须变成 \n；不得出现裸的换行在字符串内。
        assertTrue(text.contains("\\\""), "escaped quote missing in:\n$text")
        assertTrue(text.contains("\\\\"), "escaped backslash missing in:\n$text")
        assertTrue(text.contains("\\n"), "escaped newline missing in:\n$text")
        assertJsonBalanced(text)
    }

    // ── 8. 多文件 compileAllToDir ─────────────────────────────────────────

    @Test
    fun `multi file manifest contains throws from every file`() {
        val a = """
            package app;
            public class A { public function fa(): void throws std.Error { } }
        """.trimIndent()
        val b = """
            package app;
            public class B { public function fb(): void throws app.Boom { } }
        """.trimIndent()
        val out = compileMany(mapOf("A.jas" to a, "B.jas" to b), "multi-file")
        val text = readManifest(out)
        assertTrue(text.contains("\"name\": \"app.A\""), "A missing in:\n$text")
        assertTrue(text.contains("\"name\": \"app.B\""), "B missing in:\n$text")
        assertTrue(text.contains("\"name\": \"fa\""), "fa missing in:\n$text")
        assertTrue(text.contains("\"name\": \"fb\""), "fb missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"std.Error\"]"), "A throws missing in:\n$text")
        assertTrue(text.contains("\"throws\": [\"app.Boom\"]"), "B throws missing in:\n$text")
        assertJsonBalanced(text)
    }

    // ── 私有辅助：极简 JSON 结构校验（不引入依赖） ─────────────────────────

    /**
     * 极简 JSON 结构校验：括号/方括号配平、字符串状态机正确、字符串外不得有裸控制字符。
     *
     * 做什么：单遍扫描字符，维护 inString/escape 状态与括号栈，遇不配平或字符串内
     * 未转义的裸换行即 [fail]。
     * 什么时候用：每个真编译产出的 manifest 断言后追加调用，作为最低限度的合法性门。
     * 前置条件：text 为 UTF-8 解码后的完整 JSON 文本。
     * 失败模式：结构不合法时直接 fail 并打印全文，便于定位。
     * 为什么不用 JSON 库：本任务要求「不要引入新依赖」，且此处只需结构门而非完整解析。
     */
    private fun assertJsonBalanced(text: String) {
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()
        for ((idx, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                    c == '\n' || c == '\r' -> fail("raw control char at index $idx inside string:\n$text")
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.removeLastOrNull() != '{') fail("unbalanced } at $idx:\n$text")
                ']' -> if (stack.removeLastOrNull() != '[') fail("unbalanced ] at $idx:\n$text")
            }
        }
        assertTrue(!inString, "unterminated string:\n$text")
        assertEquals(0, stack.size, "unbalanced brackets:\n$text")
    }
}
