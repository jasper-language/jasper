package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue

class JasperCompilerTest {

    @Test
    fun `multiple syntax errors reported`() {
        val source = """
            package test;
            function foo( { }  // missing param
            function bar( { }  // also missing param
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error,
            "Expected Error for multiple syntax errors but got: $result")
        val errors = (result as JasperCompiler.CompileResult.Error).errors
        assertTrue(errors.size >= 2,
            "Expected >=2 errors but got ${errors.size}: ${errors.joinToString { it.message }}")
    }

    @Test
    fun `test parse simple source`() {
        val source = """
            package main;

            function main(): void {
                println("Hello, Jasper!");
            }
        """.trimIndent()

        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test syntax error reports error`() {
        val source = "package main; this is not valid syntax @@@"

        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for bad syntax but got: $result")
    }

    @Test
    fun `test empty file compiles`() {
        val source = """
            package main;
        """.trimIndent()

        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test syntax error line and column`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error but got: $result")
        val error = result as JasperCompiler.CompileResult.Error
        assertTrue(error.message.contains("line"), "Expected line info in: ${error.message}")
    }

    @Test
    fun `test invalid syntax returns error`() {
        val source = "package main; function {:}"
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for invalid syntax but got: $result")
    }

    @Test
    fun `test type error detection`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = "hello";
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for type mismatch but got: $result")
    }

    @Test
    fun `test return type error detection`() {
        val source = """
            package test;
            function answer(): int32 {
                return "nope";
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for bad return type but got: $result")
    }

    @Test
    fun `test known function call arity error detection`() {
        val source = """
            package test;
            function add(a: int32, b: int32): int32 {
                return a + b;
            }
            function main(): int32 {
                return add(1);
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for wrong arity but got: $result")
    }

    @Test
    fun `test known function call argument type error detection`() {
        val source = """
            package test;
            function twice(a: int32): int32 {
                return a + a;
            }
            function main(): int32 {
                return twice("bad");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for wrong argument type but got: $result")
    }

    @Test
    fun `test non boolean condition error detection`() {
        val source = """
            package test;
            function main(): void {
                if (1) {
                    println("bad");
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for non-bool condition but got: $result")
    }

    @Test
    fun `test array index type error detection`() {
        val source = """
            package test;
            function main(): int32 {
                var xs: int32[] = new int32[2];
                return xs["bad"];
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for bad array index but got: $result")
    }

    @Test
    fun `test duplicate local declaration error detection`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 1;
                var x: int32 = 2;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for duplicate local declaration but got: $result")
    }

    @Test
    fun testCrossFileCompilation() {
        val sources = mapOf(
            "utils.jas" to """
                function helper(): int32 { return 42; }
            """,
            "main.jas" to """
                from "utils" import helper;
                function main(): int32 { return helper(); }
            """
        )
        val compiler = JasperCompiler()
        val result = compiler.compile(sources)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Cross-file compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message}")
    }

    @Test
    fun testCrossFileFunctionCall() {
        val sources = mapOf(
            "math.jas" to """
                function add(a: int32, b: int32): int32 { return a + b; }
            """,
            "main.jas" to """
                from "math" import add;
                function main(): int32 { return add(3, 4); }
            """
        )
        val compiler = JasperCompiler()
        val pipeline = compiler.compileToPipeline(sources["main.jas"]!!)
    }

    @Test
    fun testScriptMode() {
        val code = """
            var x = 5 + 3;
            println(x);
        """
        val compiler = JasperCompiler()
        val result = compiler.compile(code)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Script compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message}")
    }

    @Test
    fun `structured error output contains category`() {
        val source = "package main; function {:}"
        val compiler = JasperCompiler()
        val result = compiler.compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected Error for bad syntax but got: $result")
        val msg = (result as JasperCompiler.CompileResult.Error).message
        assertTrue(msg.contains("SYNTAX") || msg.contains("SYMBOL") || msg.contains("TYPE"),
            "Structured error should contain category tag, got: $msg")
    }

    @Test
    fun testSyntaxErrorReporting() {
        val code = """
            function main() {
                var x = 
            }
        """
        val compiler = JasperCompiler()
        val result = compiler.compile(code)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Should have reported syntax error")
        assertTrue(result.message.contains("syntax", ignoreCase = true) || result.message.contains("Syntax", ignoreCase = true), "Error message should mention syntax: ${result.message}")
    }
}
