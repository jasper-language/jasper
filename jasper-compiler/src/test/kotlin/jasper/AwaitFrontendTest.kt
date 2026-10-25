// S4-C 前端边界：真实 Future 解包类型，未实现的挂起路径明确拒绝，普通值保持兼容。
package jasper

import jasper.compiler.JasperCompiler
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

/** await 真实编译诊断测试；不把前端类型识别当作非阻塞协程实现完成。 */
class AwaitFrontendTest {
    /** 固定种子生成结果类型；失败时缩到单个函数，保留种子和源码便于复现。 */
    @Test
    fun `seeded await result types preserve the future argument`() {
        val seed = 20261025
        val random = Random(seed)
        val types = listOf("int32", "int64", "bool", "string", "float64", "Future<int32>")
        repeat(12) { index ->
            val type = types[random.nextInt(types.size)]
            val message = compile("seed-$index", type, "Future<$type>")
            assertTrue(message.contains("non-blocking await lowering is not implemented yet"),
                "seed=$seed case=$index type=$type: $message")
            assertFalse(message.contains("Return type mismatch"),
                "single-function counterexample: seed=$seed case=$index return $type from await Future<$type>: $message")
        }
    }

    /** 在模块 build 下编译并返回诊断与产物路径，确保负例没有生成字节码。 */
    private fun compile(name: String, returnType: String, operandType: String): String {
        val output = Paths.get("build", "tmp", "await-frontend", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Await.jas" to """
            package std;
            public function awaitResult(f: $operandType): $returnType {
                return await f;
            }
        """.trimIndent()), output)
        assertTrue(result is JasperCompiler.CompileResult.Error, result.toString())
        Files.walk(output).use { paths ->
            assertFalse(paths.anyMatch { it.toString().endsWith(".class") })
        }
        return (result as JasperCompiler.CompileResult.Error).message
    }

    /** Future<int32> 的等待结果是 int32；暂时拒绝代码生成，而非透传 Future。 */
    @Test
    fun `future await identifies result type and rejects unavailable lowering`() {
        val message = compile("int", "int32", "Future<int32>")
        assertTrue(message.contains("non-blocking await lowering is not implemented yet"), message)
        assertFalse(message.contains("Return type mismatch"), message)
    }

    /** 泛型解包保留结果类型，错误的返回声明仍须得到类型诊断。 */
    @Test
    fun `future await does not erase its result type`() {
        val message = compile("wrong-result", "bool", "std.Future<int32>")
        assertTrue(message.contains("non-blocking await lowering is not implemented yet"), message)
        assertTrue(message.contains("Return type mismatch"), message)
    }

    /** 只解包一层：Future<Future<int32>> 的一次 await 不应得到 int32。 */
    @Test
    fun `nested future await unwraps exactly one layer`() {
        val message = compile("nested", "Future<int32>", "Future<Future<int32>>")
        assertTrue(message.contains("non-blocking await lowering is not implemented yet"), message)
        assertFalse(message.contains("Return type mismatch"), message)
    }

    /** 包身份参与识别，用户定义的同名 Future 仍是普通值。 */
    @Test
    fun `user future with same name keeps ordinary await compatibility`() {
        val output = Paths.get("build", "tmp", "await-frontend", "shadowed")
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("User.jas" to """
            package user;
            public class Future<T> { }
            public function identity(f: Future<int32>): Future<int32> { return await f; }
        """.trimIndent()), output)
        assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
    }
}
