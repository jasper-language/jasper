package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class ParserIntegrationTest {

    @Test
    fun `test hello world parses`() {
        assertParses("hello.jas")
    }

    @Test
    fun `test language features parses`() {
        assertParses("features.jas")
    }

    @Test
    fun `test literals parses`() {
        assertParses("literals.jas")
    }

    private fun assertParses(fileName: String) {
        val file = File("src/test/resources/$fileName")
        assertTrue(file.exists(), "Test file not found: $fileName")

        val source = file.readText()
        val result = JasperCompiler().compile(source)

        when (result) {
            is JasperCompiler.CompileResult.Success -> {
                println("✅ $fileName: ${result.message}")
            }
            is JasperCompiler.CompileResult.Error -> {
                println("❌ $fileName: ${result.message}")
            }
        }
        assertTrue(result is JasperCompiler.CompileResult.Success, "Failed to parse $fileName")
    }
}
