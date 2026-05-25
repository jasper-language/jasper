package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class IrBackendTest {

    private fun createTempDir(): Path = Files.createTempDirectory("jasper-ir-test-")

    @Test
    fun `test IR simple add`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function add(a: int32, b: int32): int32 {
                    return a + b;
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.add")
            val method = clazz.getMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val ret = method.invoke(null, 3, 4)
            assertTrue(ret == 7, "Expected 7 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR boolean`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function alwaysTrue(): Bool {
                    return true && true;
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.alwaysTrue")
            val method = clazz.getMethod("alwaysTrue")
            val ret = method.invoke(null) as Boolean
            assertTrue(ret, "Expected true")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR while loop`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function factorial(n: int32): int32 {
                    var result: int32 = 1;
                    var i: int32 = 1;
                    while (i <= n) {
                        result = result * i;
                        i = i + 1;
                    }
                    return result;
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.factorial")
            val method = clazz.getMethod("factorial", Int::class.javaPrimitiveType)
            val ret = method.invoke(null, 5)
            assertTrue(ret == 120, "Expected factorial(5) = 120 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR ternary`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function pickLabel(x: int32): string {
                    return x > 0 ? "pos" : "neg";
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.pickLabel")
            val method = clazz.getMethod("pickLabel", Int::class.javaPrimitiveType)
            val retPos = method.invoke(null, 5)
            assertEquals("pos", retPos, "Expected 'pos' for positive")
            val retNeg = method.invoke(null, -3)
            assertEquals("neg", retNeg, "Expected 'neg' for negative")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR array`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function arrayTest(): int32 {
                    var arr: int32[] = new int32[3];
                    arr[0] = 10;
                    arr[1] = 20;
                    arr[2] = 30;
                    return arr[1];
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.arrayTest")
            val method = clazz.getMethod("arrayTest")
            val ret = method.invoke(null)
            assertTrue(ret == 20, "Expected arr[1] = 20 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR fizzbuzz`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function main(): int32 {
                    var sum: int32 = 0;
                    var i: int32 = 1;
                    while (i <= 10) {
                        sum = sum + i;
                        i = i + 1;
                    }
                    return sum;
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.getDeclaredMethods().first { it.name == "main" && it.parameterCount == 0 }
            method.isAccessible = true
            val ret = method.invoke(null)
            assertTrue(ret == 55, "Expected 55 but got $ret (sum 1..10)")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR deferred body`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function main(): string {
                    var sb = new java.lang.StringBuilder();
                    sb.append("a");
                    defer {
                        sb.append("c");
                    }
                    sb.append("b");
                    return sb.toString();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.getDeclaredMethods().first { it.name == "main" && it.parameterCount == 0 }
            method.isAccessible = true
            val ret = method.invoke(null)
            assertEquals("abc", ret, "Expected 'abc'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR class with constructor`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public class Foo {
                    private var x: int32;
                    private var y: int32;
                    public constructor(x: int32, y: int32) {
                        this.x = x;
                        this.y = y;
                    }
                    public function sum(): int32 {
                        return this.x + this.y;
                    }
                }
                public function main(): int32 {
                    var f = new Foo(3, 4);
                    return f.sum();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.getDeclaredMethods().first { it.name == "main" && it.parameterCount == 0 }
            method.isAccessible = true
            val ret = method.invoke(null)
            assertTrue(ret == 7, "Expected 7 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test IR string concat`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function concat(): string {
                    return "hello" + " " + "world";
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.concat")
            val method = clazz.getMethod("concat")
            val ret = method.invoke(null)
            assertEquals("hello world", ret, "Expected 'hello world'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}
