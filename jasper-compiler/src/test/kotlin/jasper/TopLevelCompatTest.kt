// =============================================================================
// TopLevelCompatTest.kt —— 「文件头注释 + package + 多顶层类型」兼容路径回归
// =============================================================================
// 职责：固定 R2DBC 探针 #12 的修复——兼容包装路径的头部正则必须跳过
//   package 之前的注释（行注释与块注释都算），否则极常见的
//   「文件头版权/说明注释 + package + 多个顶层类型」写法会报一条莫名其妙的语法错误。
//
// 所属层次：测试层，只依赖 JasperCompiler 的公开入口。
// 失败模式：编译返回 Error 时直接断言失败，并把真实诊断带进消息。
// =============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test

class TopLevelCompatTest {

    private fun scratch(name: String): Path {
        val base = Paths.get("build", "scratch", "toplevel-compat", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    private fun assertCompiles(source: String, name: String) {
        val result = JasperCompiler().compileToDirWithIr(source, scratch(name))
        val err = result as? JasperCompiler.CompileResult.Error
        kotlin.test.assertNull(err, "期望编译成功，实际报错：${err?.message}")
    }

    /** 行注释在 package 之前（兼容路径的正则必须跳过它）。 */
    @Test
    fun `line comment before package compiles`() {
        assertCompiles(
            """
            // 文件头注释：说明这个文件做什么
            package test;
            public class A { public function v(): int32 { return 1; } }
            public class B { public function v(): int32 { return 2; } }
            """.trimIndent(),
            "line-comment",
        )
    }

    /** 块注释在 package 之前。 */
    @Test
    fun `block comment before package compiles`() {
        assertCompiles(
            """
            /* 多行
               文件头注释 */
            package test;
            public class A { public function v(): int32 { return 1; } }
            """.trimIndent(),
            "block-comment",
        )
    }
}
