package jasper.llvm

import jasper.ast.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertTrue

class LlvmBackendTest {
    @Test
    fun testLlvmBackendGeneratesOutput() {
        val backend = LlvmBackend()
        val tempDir = Files.createTempDirectory("jasper-llvm-test")

        val func = JasFunction(
            name = "main",
            parameters = emptyList(),
            returnType = JasPrimitiveType("int32"),
            body = JasBlock(listOf(
                JasReturn(JasIntLiteral(42L, "42"))
            )),
            modifiers = emptyList()
        )

        backend.generate(null, listOf(func), tempDir)

        val outputFile = tempDir.resolve("output.ll")
        assertTrue(Files.exists(outputFile), "LLVM IR file should be generated")
        val content = Files.readString(outputFile)
        assertTrue(content.contains("define i32 @main"), "Should contain function definition")
        assertTrue(content.contains("ret i32 42"), "Should contain return statement")

        Files.deleteIfExists(outputFile)
        Files.deleteIfExists(tempDir)
    }
}
