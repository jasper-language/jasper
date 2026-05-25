package jasper

import jasper.compiler.*
import org.junit.Test
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class IrBackendSmokeTest {

    private fun testIr(source: String, className: String, methodName: String, vararg args: Any?,
                       expected: Any? = null) {
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success,
                "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}")

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass(className)
            val paramTypes = args.map {
                when (it) {
                    is Int -> Int::class.java
                    is Long -> Long::class.java
                    is Float -> Float::class.java
                    is Double -> Double::class.java
                    is Boolean -> Boolean::class.java
                    is Char -> Char::class.java
                    is Byte -> Byte::class.java
                    is Short -> Short::class.java
                    else -> it?.javaClass ?: Any::class.java
                }
            }.toTypedArray()
            val method = clazz.getMethod(methodName, *paramTypes)
            val ret = method.invoke(null, *args)
            assertEquals(expected, ret)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `simple add via IR`() {
        testIr("""
            package test;
            public function add(a: int32, b: int32): int32 { return a + b; }
        """.trimIndent(), "test.add", "add", 3, 4, expected = 7)
    }

    @Test
    fun `string concat via IR`() {
        testIr("""
            package test;
            public function main(): string { return "Hello, " + "World!"; }
        """.trimIndent(), "test.main", "main", expected = "Hello, World!")
    }

    @Test
    fun `if else via IR`() {
        testIr("""
            package test;
            public function main(): int32 { if (true) { return 1; } else { return 2; } }
        """.trimIndent(), "test.main", "main", expected = 1)
    }

    @Test
    fun `while loop via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var i: int32 = 0; var s: int32 = 0;
                while (i < 5) { s += i; i += 1; }
                return s;
            }
        """.trimIndent(), "test.main", "main", expected = 10)
    }

    @Test
    fun `do while via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var i: int32 = 0; var s: int32 = 0;
                do { s += i; i += 1; } while (i < 5);
                return s;
            }
        """.trimIndent(), "test.main", "main", expected = 10)
    }

    @Test
    fun `for in via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[3];
                arr[0] = 1; arr[1] = 2; arr[2] = 3;
                var sum: int32 = 0;
                for (x in arr) { sum += x; }
                return sum;
            }
        """.trimIndent(), "test.main", "main", expected = 6)
    }

    @Test
    fun `array init via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[] { 1, 2, 3, 4, 5 };
                return arr[0] + arr[1] + arr[2] + arr[3] + arr[4];
            }
        """.trimIndent(), "test.main", "main", expected = 15)
    }

    @Test
    fun `array compound assign via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var arr: int32[] = new int32[3];
                arr[0] = 5; arr[0] += 3;
                return arr[0];
            }
        """.trimIndent(), "test.main", "main", expected = 8)
    }

    @Test
    fun `match via IR`() {
        testIr("""
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
        """.trimIndent(), "test.main", "main", expected = 20)
    }

    @Test
    fun `ternary via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var a: int32 = true ? 1 : 2;
                return a;
            }
        """.trimIndent(), "test.main", "main", expected = 1)
    }

    @Test
    fun `defer via IR`() {
        testIr("""
            package test;
            public function main(): string {
                var sb = new java.lang.StringBuilder();
                sb.append("a");
                defer { sb.append("c"); }
                sb.append("b");
                return sb.toString();
            }
        """.trimIndent(), "test.main", "main", expected = "abc")
    }

    @Test
    fun `lock via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var obj = new java.lang.Object();
                lock (obj) { return 42; }
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `dict via IR`() {
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val source = """
                package test;
                public function main(): java.util.HashMap {
                    var d = dict{ "a": 1, "b": 2 };
                    return d;
                }
            """.trimIndent()
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.getMethod("main")
            val map = method.invoke(null) as java.util.HashMap<*, *>
            assertEquals(2, map.size)
            assertEquals(1, map["a"])
            assertEquals(2, map["b"])
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class with constructor via IR`() {
        testIr("""
            package test;
            public class Foo {
                private var x: int32;
                private var y: int32;
                public constructor(x: int32, y: int32) {
                    this.x = x; this.y = y;
                }
                public function sum(): int32 { return this.x + this.y; }
            }
            public function main(): int32 {
                var f = new Foo(3, 4);
                return f.sum();
            }
        """.trimIndent(), "test.main", "main", expected = 7)
    }

    @Test
    fun `try catch via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                try { throw new java.lang.Exception(); }
                catch (e: java.lang.Exception) { return 42; }
                return 0;
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `boolean return via IR`() {
        testIr("""
            package test;
            public function main(): boolean { return true; }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `short circuit and via IR`() {
        testIr("""
            package test;
            public function main(): boolean { return true && false; }
        """.trimIndent(), "test.main", "main", expected = false)
    }

    @Test
    fun `short circuit or via IR`() {
        testIr("""
            package test;
            public function main(): boolean { return true || false; }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `power via IR`() {
        testIr("""
            package test;
            public function main(): int32 { return 2 ** 3; }
        """.trimIndent(), "test.main", "main", expected = 8)
    }

    @Test
    fun `null coalescing via IR`() {
        testIr("""
            package test;
            public function main(): string {
                var s: string? = null;
                return s ?? "default";
            }
        """.trimIndent(), "test.main", "main", expected = "default")
    }

    @Test
    fun `yield produces compile error`() {
        val source = """
            package test;
            public function main(): int32 {
                var x: int32 = 1;
                yield x;
                return 0;
            }
        """.trimIndent()
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Error,
                "Expected CompileResult.Error for yield usage but got: $result")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `string template via IR`() {
        testIr("""
            package test;
            public function main(): string {
                var name: string = "World";
                return f"Hello, {name}!";
            }
        """.trimIndent(), "test.main", "main", expected = "Hello, World!")
    }

    @Test
    fun `assert via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                assert true : "should pass";
                return 1;
            }
        """.trimIndent(), "test.main", "main", expected = 1)
    }

    @Test
    fun `increment via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var x: int32 = 5;
                x++;
                return x;
            }
        """.trimIndent(), "test.main", "main", expected = 6)
    }

    @Test
    fun `compound assign via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var x: int32 = 5;
                x += 3;
                return x;
            }
        """.trimIndent(), "test.main", "main", expected = 8)
    }

    @Test
    fun `postfix inc returns old via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var x: int32 = 5;
                return x++;
            }
        """.trimIndent(), "test.main", "main", expected = 5)
    }

    @Test
    fun `prefix inc returns new via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var x: int32 = 5;
                return ++x;
            }
        """.trimIndent(), "test.main", "main", expected = 6)
    }

    @Test
    fun `lambda via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var f = func(a: int32, b: int32): int32 { return a + b; };
                return f(3, 4);
            }
        """.trimIndent(), "test.main", "main", expected = 7)
    }

    @Test
    fun `lambda with single arg via IR`() {
        testIr("""
            package test;
            public function main(): int32 {
                var f = (x: int32) => x * 2;
                return f(5);
            }
        """.trimIndent(), "test.main", "main", expected = 10)
    }

    @Test
    fun `property getter and setter via IR`() {
        testIr("""
            package test;
            class Counter {
                var count: int32 { get; set; };
                constructor() { this.count = 0; }
                function setAndReturn(v: int32): int32 { this.count = v; return this.count; }
            }
            function main(): int32 {
                var c = new Counter();
                return c.setAndReturn(10);
            }
        """.trimIndent(), "test.main", "main", expected = 10)
    }

    @Test
    fun `interface class compiles via IR`() {
        val source = """
            package test;
            interface Greeter {
                function greet(name: string): string;
            }
            function main(): void {}
        """.trimIndent()
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            assertTrue(outputDir.resolve("test/Greeter.class").toFile().exists())
            assertTrue(outputDir.resolve("test/main.class").toFile().exists())
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `constructor delegation compiles via IR`() {
        val source = """
            package test;
            class Base {
                private var val: int32;
                constructor(v: int32) { this.val = v; }
            }
            class Derived extends Base {
                constructor() { super(42); }
            }
            function main(): void {}
        """.trimIndent()
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
            assertTrue(outputDir.resolve("test/Base.class").toFile().exists())
            assertTrue(outputDir.resolve("test/Derived.class").toFile().exists())
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `constructor delegation to this via IR`() {
        testIr("""
            package test;
            class Point {
                private var x: int32;
                private var y: int32;
                constructor() { this(0, 0); }
                constructor(x: int32, y: int32) {
                    this.x = x; this.y = y;
                }
                function sum(): int32 { return this.x + this.y; }
            }
            function main(): int32 {
                var p = new Point();
                return p.sum();
            }
        """.trimIndent(), "test.main", "main", expected = 0)
    }

    @Test
    fun `super constructor delegation via IR`() {
        testIr("""
            package test;
            class Base {
                private var val: int32;
                constructor(v: int32) { this.val = v; }
                function getVal(): int32 { return this.val; }
            }
            class Derived extends Base {
                constructor() { super(42); }
            }
            function main(): int32 {
                var d = new Derived();
                return d.getVal();
            }
            """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `method reference via IR`() {
        testIr("""
            package test;
            function greet(): string { return "hello"; }
            function main(): string {
                var f = greet::greet;
                var g = test::greet;
                return f() + g();
            }
        """.trimIndent(), "test.main", "main", expected = "hellohello")
    }

    @Test
    fun `bitwise and via IR`() {
        testIr("""
            package test;
            public function main(): int32 { return 6 & 3; }
        """.trimIndent(), "test.main", "main", expected = 2)
    }

    @Test
    fun `bitwise or via IR`() {
        testIr("""
            package test;
            public function main(): int32 { return 4 | 1; }
        """.trimIndent(), "test.main", "main", expected = 5)
    }

    @Test
    fun `bitwise xor via IR`() {
        testIr("""
            package test;
            public function main(): int32 { return 5 ^ 3; }
        """.trimIndent(), "test.main", "main", expected = 6)
    }

    @Test
    fun `bitwise not via IR`() {
        testIr("""
            package test;
            public function main(): int32 { return ~1; }
        """.trimIndent(), "test.main", "main", expected = -2)
    }

    @Test
    fun `graalvm warnings are empty for basic code`() {
        val source = """
            package test;
            public function main(): int32 {
                return 42;
            }
        """.trimIndent()
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val pipeline = JasperCompiler().compileToPipeline(source)
            val mapper = pipeline.ir.typeMapper ?: jasper.translator.TypeMapper()
            val backend = JvmIrBackend(mapper)
            backend.generate(pipeline.ir, outputDir)
            val warnings = backend.getGraalvmWarnings()
            assertTrue(warnings.isEmpty(), "Expected no GraalVM warnings for basic code, got: $warnings")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interface implementation via IR`() {
        testIr("""
            package test;
            interface Greeter { function greet(): string; }
            class HelloGreeter implements Greeter {
                function greet(): string { return "Hello from impl!"; }
            }
            function main(): string {
                var g: Greeter = new HelloGreeter();
                return g.greet();
            }
        """.trimIndent(), "test.main", "main", expected = "Hello from impl!")
    }

    @Test
    fun `interface method call with override via IR`() {
        testIr("""
            package test;
            interface Adder { function add(x: int32, y: int32): int32; }
            class MyAdder implements Adder {
                function add(x: int32, y: int32): int32 { return x + y + 1; }
            }
            function main(): int32 {
                var a: Adder = new MyAdder();
                return a.add(20, 22);
            }
        """.trimIndent(), "test.main", "main", expected = 43)
    }

    @Test
    fun `interface multiple implementing classes via IR`() {
        testIr("""
            package test;
            interface Greeter { function greet(): string; }
            class HelloGreeter implements Greeter {
                function greet(): string { return "hello"; }
            }
            class HiGreeter implements Greeter {
                function greet(): string { return "hi"; }
            }
            function main(): string {
                var h: Greeter = new HelloGreeter();
                var i: Greeter = new HiGreeter();
                return h.greet() + i.greet();
            }
        """.trimIndent(), "test.main", "main", expected = "hellohi")
    }

    @Test
    fun `interface with multiple methods via IR`() {
        testIr("""
            package test;
            interface Calculator {
                function add(x: int32, y: int32): int32;
                function mul(x: int32, y: int32): int32;
            }
            class MyCalc implements Calculator {
                function add(x: int32, y: int32): int32 { return x + y; }
                function mul(x: int32, y: int32): int32 { return x * y; }
            }
            function main(): int32 {
                var c: Calculator = new MyCalc();
                return c.add(3, 4) + c.mul(2, 5);
            }
        """.trimIndent(), "test.main", "main", expected = 17)
    }

    @Test
    fun `interface dispatch with nested calls via IR`() {
        testIr("""
            package test;
            interface IntBox { function value(): int32; }
            class MyBox implements IntBox {
                function value(): int32 { return 42; }
            }
            function main(): int32 {
                var b: IntBox = new MyBox();
                return b.value();
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `interface variable reassignment via IR`() {
        testIr("""
            package test;
            interface Greeter { function greet(): string; }
            class HelloGreeter implements Greeter {
                function greet(): string { return "hello"; }
            }
            class ByeGreeter implements Greeter {
                function greet(): string { return "bye"; }
            }
            function main(): string {
                var g: Greeter = new HelloGreeter();
                var r = g.greet();
                g = new ByeGreeter();
                return r + g.greet();
            }
        """.trimIndent(), "test.main", "main", expected = "hellobye")
    }

    @Test
    fun `interface extends interface via IR`() {
        testIr("""
            package test;
            interface Base { function baseGreet(): string; }
            interface Derived extends Base { function derivedGreet(): string; }
            class Impl implements Derived {
                function baseGreet(): string { return "base"; }
                function derivedGreet(): string { return "derived"; }
            }
            function main(): string {
                var d: Derived = new Impl();
                return d.baseGreet() + d.derivedGreet();
            }
        """.trimIndent(), "test.main", "main", expected = "basederived")
    }

    @Test
    fun `chained method calls via IR`() {
        testIr("""
            package test;
            class Counter {
                private var count: int32;
                constructor() { this.count = 0; }
                function increment(): int32 {
                    this.count = this.count + 1;
                    return this.count;
                }
                function add(x: int32): int32 {
                    this.count = this.count + x;
                    return this.count;
                }
            }
            function main(): int32 {
                var c = new Counter();
                var a = c.add(5);
                var b = c.increment();
                return a + b;
            }
        """.trimIndent(), "test.main", "main", expected = 11)
    }

    @Test
    fun `method reference with parameters via IR`() {
        testIr("""
            package test;
            function double(x: int32): int32 { return x * 2; }
            function main(): int32 {
                var f = double::double;
                return f(21);
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `implicit var type via IR`() {
        testIr("""
            package test;
            function main(): int32 {
                var x = 42;
                var y = x;
                return y;
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `multi variable declaration via IR`() {
        testIr("""
            package test;
            function main(): int32 {
                var a: int32 = 1, b: int32 = 2;
                return a + b;
            }
        """.trimIndent(), "test.main", "main", expected = 3)
    }

    @Test
    fun `int equality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return 42 == 42;
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `string equality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return "hello" == "hello";
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `string inequality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return "hello" != "world";
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `bool equality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return true == true;
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `generic function defined only via IR`() {
        testIr("""
            package test;
            function identity<T>(x: T): T { return x; }
            function main(): int32 { return 42; }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `generic function with type inference via IR`() {
        testIr("""
            package test;
            function identity<T>(x: T): T { return x; }
            function main(): string {
                return identity("hello");
            }
        """.trimIndent(), "test.main", "main", expected = "hello")
    }

    @Test
    fun `float64 addition via IR`() {
        testIr("""
            package test;
            function main(): float64 {
                return 3.5 + 2.5;
            }
        """.trimIndent(), "test.main", "main", expected = 6.0)
    }

    @Test
    fun `float64 equality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return 3.5 == 3.5;
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `int64 addition via IR`() {
        testIr("""
            package test;
            function main(): int64 {
                var a: int64 = 42;
                var b: int64 = 100;
                return a + b;
            }
        """.trimIndent(), "test.main", "main", expected = 142L)
    }

    @Test
    fun `array length via IR`() {
        testIr("""
            package test;
            function main(): int32 {
                var arr = new int32[5];
                return arr.length;
            }
        """.trimIndent(), "test.main", "main", expected = 5)
    }

    @Test
    fun `string array via IR`() {
        testIr("""
            package test;
            function main(): string {
                var arr = new string[] { "a", "b", "c" };
                return arr[1];
            }
        """.trimIndent(), "test.main", "main", expected = "b")
    }

    @Test
    fun `nested class via IR`() {
        testIr("""
            package test;
            class Outer {
                private var val: int32;
                constructor(v: int32) { this.val = v; }
                function getVal(): int32 { return this.val; }
            }
            function main(): int32 {
                var o = new Outer(42);
                return o.getVal();
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `class method override via IR`() {
        testIr("""
            package test;
            class Base {
                function greet(): string { return "base"; }
            }
            class Derived extends Base {
                function greet(): string { return "derived"; }
            }
            function main(): string {
                var d = new Derived();
                return d.greet();
            }
        """.trimIndent(), "test.main", "main", expected = "derived")
    }

    @Test
    fun `null equality via IR`() {
        testIr("""
            package test;
            function main(): bool {
                return null == null;
            }
        """.trimIndent(), "test.main", "main", expected = true)
    }

    @Test
    fun `generic function with two type params via IR`() {
        testIr("""
            package test;
            function pair<A, B>(a: A, b: B): A { return a; }
            function main(): string {
                return pair("hello", 42);
            }
        """.trimIndent(), "test.main", "main", expected = "hello")
    }

    @Test
    fun `string concat with plus via IR`() {
        testIr("""
            package test;
            function main(): string {
                return "hello" + " " + "world";
            }
        """.trimIndent(), "test.main", "main", expected = "hello world")
    }

    @Test
    fun `while with comparison via IR`() {
        testIr("""
            package test;
            function main(): int32 {
                var i = 0;
                while (i < 3) {
                    i = i + 1;
                }
                return i;
            }
        """.trimIndent(), "test.main", "main", expected = 3)
    }

    @Test
    fun `generic function with inferred type via IR`() {
        testIr("""
            package test;
            function identity<T>(x: T): T { return x; }
            function main(): int32 {
                return identity(42);
            }
        """.trimIndent(), "test.main", "main", expected = 42)
    }

    @Test
    fun `println compiles via IR`() {
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr("""
                package test;
                function main(): void { println("hello"); }
            """.trimIndent(), outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success,
                "println compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}")
            assertTrue(outputDir.resolve("test/main.class").toFile().exists())
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `print compiles via IR`() {
        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileToDirWithIr("""
                package test;
                function main(): void { print("hello"); }
            """.trimIndent(), outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success,
                "print compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}")
            assertTrue(outputDir.resolve("test/main.class").toFile().exists())
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `toString compiles via IR`() {
        testIr("""
            package test;
            function main(): string { return toString(42); }
        """.trimIndent(), "test.main", "main", expected = "42")
    }

    @Test
    fun `toString with string arg via IR`() {
        testIr("""
            package test;
            function main(): string { return toString("hello"); }
        """.trimIndent(), "test.main", "main", expected = "hello")
    }

    @Test
    fun `multi-file compilation via IR`() {
        val sources = linkedMapOf(
            "greeter.jas" to """
                package test;
                function greet(): string { return "hello"; }
            """.trimIndent(),
            "main.jas" to """
                package test;
                function main(): string {
                    return greet();
                }
            """.trimIndent()
        )

        val outputDir = Files.createTempDirectory("irtest")
        try {
            val result = JasperCompiler().compileAllToDir(sources, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")

            val cl = URLClassLoader(arrayOf(outputDir.toUri().toURL()), ClassLoader.getSystemClassLoader())
            val mainClass = cl.loadClass("test.main")
            val mainMethod = mainClass.getMethod("main")
            val ret = mainMethod.invoke(null)
            assertEquals("hello", ret as String)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}
