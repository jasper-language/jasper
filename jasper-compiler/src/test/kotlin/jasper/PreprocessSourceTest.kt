package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue

class PreprocessSourceTest {

    private fun compile(source: String): JasperCompiler.CompileResult {
        return JasperCompiler().compile(source)
    }

    @Test
    fun `multi char identifier power compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var count: int32 = 2;
                return count ** 3;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Multi-char identifier power should work: $result")
    }

    @Test
    fun `multi char identifier power assign compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var counter: int32 = 2;
                counter **= 3;
                return counter;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Multi-char identifier power assign should work: $result")
    }

    @Test
    fun `prefix increment on multi char var compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var counter: int32 = 5;
                ++counter;
                return counter;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Prefix increment should work on multi-char var: $result")
    }

    @Test
    fun `postfix increment on multi char var compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var counter: int32 = 5;
                counter++;
                return counter;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Postfix increment should work on multi-char var: $result")
    }

    @Test
    fun `prefix decrement on multi char var compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var counter: int32 = 5;
                --counter;
                return counter;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Prefix decrement should work on multi-char var: $result")
    }

    @Test
    fun `postfix decrement on multi char var compiles`() {
        val source = """
            package test;
            function main(): int32 {
                var counter: int32 = 5;
                counter--;
                return counter;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Postfix decrement should work on multi-char var: $result")
    }

    @Test
    fun `cast with expression compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: any = "hello";
                var s: string = cast x as string;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Cast expression should work: $result")
    }

    @Test
    fun `lambda arrow syntax compiles`() {
        val source = """
            package test;
            function main(): void {
                var f = (x: int32) => x + 1;
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Lambda arrow syntax should work: $result")
    }

    @Test
    fun `match case wildcard compiles`() {
        val source = """
            package test;
            function main(): void {
                match (42) {
                    case _ => { println("wildcard"); }
                }
            }
        """.trimIndent()
        val result = compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success,
            "Match with wildcard should work: $result")
    }
}