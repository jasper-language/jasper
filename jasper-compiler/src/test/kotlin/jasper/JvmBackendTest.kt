package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class JvmBackendTest {

    @Test
    fun `test jvm backend produces class files`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                function main(): void {
                    var x: int32 = 42;
                    var y: int32 = x + 1;
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val classFile = outputDir.resolve("test/main.class").toFile()
            assertTrue(classFile.exists(), "Expected main.class to exist")
            assertTrue(classFile.length() > 0, "Expected main.class to be non-empty")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test jvm backend produces class files for classes`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public class Box {
                    private var value: int32;
                    public constructor(v: int32) {
                        this.value = v;
                    }
                    public function getValue(): int32 {
                        return this.value;
                    }
                }
                function main(): void {
                    var b: Box = new Box(42);
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val classFile = outputDir.resolve("test/Box.class").toFile()
            assertTrue(classFile.exists(), "Expected Box.class to exist")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test compiled class can be loaded`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function add(a: int32, b: int32): int32 {
                    return a + b;
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.add")
            assertTrue(clazz.methods.any { it.name == "add" }, "Expected 'add' method")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test compiled main method is invocable`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                function main(): void {
                    var x: int32 = 1;
                    var y: int32 = 2;
                    var z: int32 = x + y;
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val mainMethod = clazz.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, arrayOf<String>())
            assertTrue(true, "main() executed without exception")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test compound assignment compiled code can be loaded and invoked`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function compute(): int32 {
                    var x: int32 = 10;
                    x += 5;
                    x *= 2;
                    x -= 3;
                    x /= 3;
                    return x;
                }
                function main(): void {
                    compute();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.compute")
            val method = clazz.getMethod("compute")
            val ret = method.invoke(null)
            // (10 + 5) * 2 - 3 = 27 / 3 = 9
            assertTrue(ret == 9, "Expected 9 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test boolean return true`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function alwaysTrue(): Bool {
                    return true;
                }
                function main(): void {
                    alwaysTrue();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
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
    fun `test and short circuit true && true`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testAndTrue(): Bool {
                    return true && true;
                }
                function main(): void {
                    testAndTrue();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testAndTrue")
            val method = clazz.getMethod("testAndTrue")
            val ret = method.invoke(null) as Boolean
            assertTrue(ret, "Expected true && true to be true")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test and short circuit true && false`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testAndFalse(): Bool {
                    return true && false;
                }
                function main(): void {
                    testAndFalse();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testAndFalse")
            val method = clazz.getMethod("testAndFalse")
            val ret = method.invoke(null) as Boolean
            assertTrue(!ret, "Expected true && false to be false")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test and short circuit false && true`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testAndShortCircuit(): Bool {
                    return false && true;
                }
                function main(): void {
                    testAndShortCircuit();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testAndShortCircuit")
            val method = clazz.getMethod("testAndShortCircuit")
            val ret = method.invoke(null) as Boolean
            assertTrue(!ret, "Expected false && true to be false (short-circuit)")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test or short circuit true || false`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testOrTrue(): Bool {
                    return true || false;
                }
                function main(): void {
                    testOrTrue();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testOrTrue")
            val method = clazz.getMethod("testOrTrue")
            val ret = method.invoke(null) as Boolean
            assertTrue(ret, "Expected true || false to be true")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test or short circuit false || true`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testOrTrue2(): Bool {
                    return false || true;
                }
                function main(): void {
                    testOrTrue2();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testOrTrue2")
            val method = clazz.getMethod("testOrTrue2")
            val ret = method.invoke(null) as Boolean
            assertTrue(ret, "Expected false || true to be true")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test or short circuit false || false`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testOrFalse(): Bool {
                    return false || false;
                }
                function main(): void {
                    testOrFalse();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testOrFalse")
            val method = clazz.getMethod("testOrFalse")
            val ret = method.invoke(null) as Boolean
            assertTrue(!ret, "Expected false || false to be false")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test and short circuit side effect prevented`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testAndSideEffect(): Bool {
                    return false && true;
                }
                function main(): void {
                    testAndSideEffect();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val testClass = loader.loadClass("test.testAndSideEffect")
            val method = testClass.getMethod("testAndSideEffect")
            val ret = method.invoke(null) as Boolean
            assertTrue(!ret, "Expected false && true to be false (short-circuit)")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test or short circuit side effect prevented`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testOrSideEffect(): Bool {
                    return true || false;
                }
                function main(): void {
                    testOrSideEffect();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val testClass = loader.loadClass("test.testOrSideEffect")
            val method = testClass.getMethod("testOrSideEffect")
            val ret = method.invoke(null) as Boolean
            assertTrue(ret, "Expected true || false to be true (short-circuit)")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ─── Increment/Decrement Tests ───

    @Test
    fun `test prefix increment returns new value`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testPrefixInc(): int32 {
                    var x: int32 = 5;
                    var y: int32 = ++x;
                    return y;
                }
                function main(): void {
                    testPrefixInc();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testPrefixInc")
            val method = clazz.getMethod("testPrefixInc")
            val ret = method.invoke(null)
            // ++x → x becomes 6, y = 6
            assertTrue(ret == 6, "Expected 6 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test postfix increment returns old value`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testPostfixInc(): int32 {
                    var x: int32 = 5;
                    var y: int32 = x++;
                    return y;
                }
                function main(): void {
                    testPostfixInc();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testPostfixInc")
            val method = clazz.getMethod("testPostfixInc")
            val ret = method.invoke(null)
            // x++ returns old value 5
            assertTrue(ret == 5, "Expected 5 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test postfix increment side effect`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testPostfixSideEffect(): int32 {
                    var x: int32 = 5;
                    var y: int32 = x++;
                    return x;
                }
                function main(): void {
                    testPostfixSideEffect();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testPostfixSideEffect")
            val method = clazz.getMethod("testPostfixSideEffect")
            val ret = method.invoke(null)
            // x++ increments x to 6
            assertTrue(ret == 6, "Expected 6 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test prefix decrement returns new value`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testPrefixDec(): int32 {
                    var x: int32 = 5;
                    var y: int32 = --x;
                    return y;
                }
                function main(): void {
                    testPrefixDec();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testPrefixDec")
            val method = clazz.getMethod("testPrefixDec")
            val ret = method.invoke(null)
            // --x → x becomes 4, y = 4
            assertTrue(ret == 4, "Expected 4 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test postfix decrement returns old value`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testPostfixDec(): int32 {
                    var x: int32 = 5;
                    var y: int32 = x--;
                    return y;
                }
                function main(): void {
                    testPostfixDec();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testPostfixDec")
            val method = clazz.getMethod("testPostfixDec")
            val ret = method.invoke(null)
            // x-- returns old value 5
            assertTrue(ret == 5, "Expected 5 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test increment standalone statement`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testIncStmt(): int32 {
                    var x: int32 = 5;
                    x++;
                    return x;
                }
                function main(): void {
                    testIncStmt();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testIncStmt")
            val method = clazz.getMethod("testIncStmt")
            val ret = method.invoke(null)
            // x++ as statement → x becomes 6
            assertTrue(ret == 6, "Expected 6 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test decrement standalone statement`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function testDecStmt(): int32 {
                    var x: int32 = 5;
                    x--;
                    return x;
                }
                function main(): void {
                    testDecStmt();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.testDecStmt")
            val method = clazz.getMethod("testDecStmt")
            val ret = method.invoke(null)
            // x-- as statement → x becomes 4
            assertTrue(ret == 4, "Expected 4 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test fstring compiled and loaded`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function greet(name: string): string {
                    return f"Hello {name}!";
                }
                function main(): void {
                    greet("world");
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.greet")
            val method = clazz.getMethod("greet", String::class.java)
            val ret = method.invoke(null, "Jasper")
            assertTrue(ret == "Hello Jasper!", "Expected 'Hello Jasper!' but got '$ret'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test power operator produces correct result`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function compute(): int32 {
                    return 2 ** 3;
                }
                function main(): void {
                    compute();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.compute")
            val method = clazz.getMethod("compute")
            val ret = method.invoke(null)
            assertTrue(ret == 8, "Expected 8 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test power assign produces correct result`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function compute(): int32 {
                    var x: int32 = 2;
                    x **= 3;
                    return x;
                }
                function main(): void {
                    compute();
                }
            """.trimIndent()

            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.compute")
            val method = clazz.getMethod("compute")
            val ret = method.invoke(null)
            assertTrue(ret == 8, "Expected 8 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test property getter and setter`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public class Box {
                    private var value: int32;
                    public constructor(v: int32) {
                        this.value = v;
                    }
                }
                function main(): void {
                    var b: Box = new Box(42);
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val classFile = outputDir.resolve("test/Box.class").toFile()
            assertTrue(classFile.exists(), "Expected Box.class to exist")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test string concatenation at runtime`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function concat(): string {
                    return "hello" + " " + "world";
                }
                function main(): void {
                    concat();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.concat")
            val method = clazz.getMethod("concat")
            val ret = method.invoke(null)
            assertTrue(ret == "hello world", "Expected 'hello world' but got '$ret'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test while loop runtime`() {
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
                function main(): void {
                    factorial(5);
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
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
    fun `test do while runtime`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function sumUpTo(n: int32): int32 {
                    var i: int32 = 0;
                    var sum: int32 = 0;
                    do {
                        sum = sum + i;
                        i = i + 1;
                    } while (i <= n);
                    return sum;
                }
                function main(): void {
                    sumUpTo(5);
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.sumUpTo")
            val method = clazz.getMethod("sumUpTo", Int::class.javaPrimitiveType)
            val ret = method.invoke(null, 5)
            // 0 + 1 + 2 + 3 + 4 + 5 = 15
            assertTrue(ret == 15, "Expected sumUpTo(5) = 15 but got $ret")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test ternary runtime`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function pickLabel(x: int32): string {
                    return x > 0 ? "pos" : "neg";
                }
                function main(): void {
                    pickLabel(5);
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.pickLabel")
            val method = clazz.getMethod("pickLabel", Int::class.javaPrimitiveType)
            val retPos = method.invoke(null, 5)
            assertTrue(retPos == "pos", "Expected 'pos' for positive but got '$retPos'")
            val retNeg = method.invoke(null, -3)
            assertTrue(retNeg == "neg", "Expected 'neg' for negative but got '$retNeg'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test array creation and indexing runtime`() {
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
                function main(): void {
                    arrayTest();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
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
    fun `test dict literal runtime`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function dictTest(): string {
                    var d = dict{ "key": "value" };
                    return "ok";
                }
                function main(): void {
                    dictTest();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.dictTest")
            val method = clazz.getMethod("dictTest")
            val ret = method.invoke(null)
            assertTrue(ret == "ok", "Expected 'ok' but got '$ret'")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `test for in compiles with success`() {
        val outputDir = createTempDir()
        try {
            val source = """
                package test;
                public function sumArray(): int32 {
                    var arr: int32[] = new int32[3];
                    arr[0] = 1;
                    arr[1] = 2;
                    arr[2] = 3;
                    var sum: int32 = 0;
                    for (x in arr) {
                        sum = sum + x;
                    }
                    return sum;
                }
                function main(): void {
                    sumArray();
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ─── runAndCheck helpers ───

    private fun runAndCheck(code: String, expected: Any?) {
        val outputDir = createTempDir()
        try {
            val result = JasperCompiler().compileToDir(code, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.declaredMethods.first { it.name == "main" && it.parameterCount == 0 }
            method.isAccessible = true
            val ret = method.invoke(null)
            assertEquals(expected, ret)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    private fun runAndCheck(code: String, assertions: (Any?) -> Unit) {
        val outputDir = createTempDir()
        try {
            val result = JasperCompiler().compileToDir(code, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.declaredMethods.first { it.name == "main" && it.parameterCount == 0 }
            method.isAccessible = true
            val ret = method.invoke(null)
            assertions(ret)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ─── Runtime Tests ───

    @Test
    fun testForInLoopRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[3];
                arr[0] = 1;
                arr[1] = 2;
                arr[2] = 3;
                var sum: int32 = 0;
                for (x in arr) {
                    sum += x;
                }
                return sum;
            }
        """.trimIndent()
        runAndCheck(code, 6)
    }

    @Test
    fun testDeferRuns() {
        val code = """
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
        runAndCheck(code, "abc")
    }

    @Test
    fun testLockRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var obj = new java.lang.Object();
                lock (obj) {
                    return 42;
                }
                return 0;
            }
        """.trimIndent()
        runAndCheck(code, 42)
    }

    @Test
    fun testAssertPasses() {
        val code = """
            package test;
            public function main(): int32 {
                assert true : "should pass";
                return 1;
            }
        """.trimIndent()
        runAndCheck(code, 1)
    }

    @Test
    fun testYieldExits() {
        val code = """
            package test;
            public function main(): int32 {
                var x: int32 = 1;
                yield x;
                return 0;
            }
        """.trimIndent()
        val outputDir = createTempDir()
        try {
            val result = JasperCompiler().compileToDir(code, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Error,
                "Expected CompileResult.Error for yield usage but got: $result")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDictLiteralRuns() {
        val code = """
            package test;
            public function main(): java.util.HashMap {
                var d = dict{ "a": 1, "b": 2 };
                return d;
            }
        """.trimIndent()
        runAndCheck(code) { result ->
            val map = result as java.util.HashMap<*, *>
            assertEquals(2, map.size)
            assertEquals(1, map["a"])
            assertEquals(2, map["b"])
        }
    }

    @Test
    fun testMatchExpressionRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var x: int32 = 2;
                match (x) {
                    case 1 => { return 10; }
                    case 2 => { return 20; }
                    default => { return 30; }
                }
                return 0;
            }
        """.trimIndent()
        runAndCheck(code, 20)
    }

    @Test
    fun testTernaryExprRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var a: int32 = true ? 1 : 2;
                return a;
            }
        """.trimIndent()
        runAndCheck(code, 1)
    }

    @Test
    fun testNullCoalescingRuns() {
        val code = """
            package test;
            public function main(): string {
                var s: string? = null;
                return s ?? "default";
            }
        """.trimIndent()
        runAndCheck(code, "default")
    }

    @Test
    fun testClassWithConstructorRuns() {
        val code = """
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
        runAndCheck(code, 7)
    }

    @Test
    fun testWhileLoopRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                var sum: int32 = 0;
                while (i < 5) {
                    sum += i;
                    i += 1;
                }
                return sum;
            }
        """.trimIndent()
        runAndCheck(code, 10)
    }

    @Test
    fun testDoWhileLoopRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var i: int32 = 0;
                var sum: int32 = 0;
                do {
                    sum += i;
                    i += 1;
                } while (i < 5);
                return sum;
            }
        """.trimIndent()
        runAndCheck(code, 10)
    }

    @Test
    fun testTryCatchRuns() {
        val code = """
            package test;
            public function main(): int32 {
                try {
                    throw new java.lang.Exception();
                } catch (e: java.lang.Exception) {
                    return 42;
                }
                return 0;
            }
        """.trimIndent()
        runAndCheck(code, 42)
    }

    @Test
    fun testArrayInitializationRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[] [1, 2, 3, 4, 5];
                return arr[0] + arr[1] + arr[2] + arr[3] + arr[4];
            }
        """.trimIndent()
        runAndCheck(code, 15)
    }

    @Test
    fun testStringTemplateRuns() {
        val code = """
            package test;
            public function main(): string {
                var name: string = "World";
                return f"Hello, {name}!";
            }
        """.trimIndent()
        runAndCheck(code, "Hello, World!")
    }

    @Test
    fun testArrayCompoundAssignmentRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[3];
                arr[0] = 5;
                arr[0] += 3;
                return arr[0];
            }
        """.trimIndent()
        runAndCheck(code, 8)
    }

    @Test
    fun testLambdaRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var fn = (x: int32) => x * 2;
                return fn(5) as int32;
            }
        """.trimIndent()
        runAndCheck(code, 10)
    }

    @Test
    fun testAnnotationCompiles() {
        val code = """
            package test;
            @interface MyAnn {
                string value();
            }
            @MyAnn("test")
            public function main(): int32 {
                return 1;
            }
        """.trimIndent()
        runAndCheck(code, 1)
    }

    @Test
    fun testForInWithElseRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[0];
                for (x in arr) {
                    return 0;
                } then {
                } else {
                    return 42;
                }
                return 0;
            }
        """.trimIndent()
        runAndCheck(code, 42)
    }

    @Test
    fun testSwitchStatementRuns() {
        val code = """
            package test;
            public function main(): int32 {
                var x: int32 = 2;
                switch (x) {
                    case 1 => { return 10; }
                    case 2 => { return 20; }
                    default => { return 30; }
                }
                return 0;
            }
        """.trimIndent()
        runAndCheck(code, 20)
    }

    private fun createTempDir(): Path {
        return Files.createTempDirectory("jasper-test-")
    }
}
