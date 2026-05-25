package jasper

import jasper.compiler.JasperCompiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeaturesTranslationTest {

    @Test
    fun `test for loop compiles`() {
        val source = """
            package test;
            function main(): void {
                for (var i: int32 = 0; i < 10; i = i + 1) {
                    println(i);
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test switch statement compiles`() {
        val source = """
            package test;
            function describe(value: int32): string {
                switch (value) {
                    case 0 => {
                        return "zero";
                    }
                    case 1 => {
                        return "one";
                    }
                    default => {
                        return "many";
                    }
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test try catch compiles`() {
        val source = """
            package test;
            function main(): void {
                try {
                    println("try");
                } catch(e: string) {
                    println("catch");
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test interface declaration compiles`() {
        val source = """
            package test;
            public interface Drawable {
                public function draw(): void;
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test enum declaration compiles`() {
        val source = """
            package test;
            public enum Color {
                Red,
                Green,
                Blue
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test nested if else inside while compiles`() {
        val source = """
            package test;
            function main(): void {
                var i: int32 = 0;
                while (i < 10) {
                    if (i == 5) {
                        println("five");
                    } else {
                        println("other");
                    }
                    i = i + 1;
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test throw statement compiles`() {
        val source = """
            package test;
            function main(): void {
                throw "error occurred";
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test array creation compiles`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[10];
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test for in loop compiles`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[3];
                for (x in arr) {
                    println(x);
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test break continue compiles`() {
        val source = """
            package test;
            function main(): void {
                var i: int32 = 0;
                while (i < 10) {
                    if (i == 5) {
                        break;
                    }
                    i = i + 1;
                    continue;
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test assert statement compiles`() {
        val source = """
            package test;
            function main(): void {
                assert true;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test property declaration compiles`() {
        val source = """
            package test;
            class MyClass {
                var name: string {
                    get;
                };
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test lambda expression compiles`() {
        val source = """
            package test;
            function main(): void {
                var f = (x: int32) => x + 1;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test type expression with nullable compiles`() {
        val source = """
            package test;
            function main(): void {
                var n: string? = null;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test method reference compiles`() {
        val source = """
            package test;
            function foo(): void {}
            function main(): void {
                var f = foo;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment plus compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 1;
                x += 2;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment minus compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 10;
                x -= 3;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment multiply compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                x *= 4;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment divide compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 20;
                x /= 5;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment modulo compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 17;
                x %= 5;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test compound assignment in expression context compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 1;
                var y: int32 = (x += 2);
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test power operator compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 2 ** 3;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test power assign compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 2;
                x **= 3;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test for in binding name`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[3];
                for (x in arr) {
                    println(x);
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test loop then else`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[3];
                for (x in arr) {
                    println(x);
                } then {
                    println("completed");
                } else {
                    println("empty");
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test loop then only`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[3];
                for (x in arr) {
                    println(x);
                } then {
                    println("completed");
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test prefix increment compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                ++x;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test postfix increment compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                x++;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test prefix decrement compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                --x;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test postfix decrement compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                x--;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test fstring compiles`() {
        val source = """
            package test;
            function main(): void {
                var name: string = "world";
                println(f"Hello {name}!");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test import compiles`() {
        val source = """
            package test;
            import kotlin.Int;
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test generic class compiles`() {
        val source = """
            package test;
            public class Box<T extends Any> {
                private var value: T;
                public constructor(v: T) {
                    this.value = v;
                }
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test fstring with expression compiles`() {
        val source = """
            package test;
            function main(): void {
                var a: int32 = 1;
                var b: int32 = 2;
                println(f"result: {a + b}");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test type checker passes valid code`() {
        val source = """
            package test;
            function add(a: int32, b: int32): int32 {
                return a + b;
            }
            function main(): void {
                var x: int32 = add(1, 2);
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test match expression compiles`() {
        val source = """
            package test;
            function main(): void {
                match (42) {
                    case 1 => { println("one"); }
                    case 2 => { println("two"); }
                    default => { println("other"); }
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test match with wildcard compiles`() {
        val source = """
            package test;
            function main(): void {
                match (99) {
                    case _ => { println("wildcard"); }
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test match with literal patterns compiles`() {
        val source = """
            package test;
            function testFn(): void {
                match ("hello") {
                    case "hello" => { println("greeting"); }
                    case 0 => { println("zero"); }
                    case true => { println("truth"); }
                    default => { println("default"); }
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test do while compiles`() {
        val source = """
            package test;
            function main(): void {
                var i: int32 = 0;
                do {
                    i = i + 1;
                } while (i < 5);
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test generic interface compiles`() {
        val source = """
            package test;
            public interface Foo<T> {
                public function bar(): T;
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test generic function compiles`() {
        val source = """
            package test;
            function swap<T>(a: T, b: T): void { }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test multi variable declaration compiles`() {
        val source = """
            package test;
            function main(): void {
                var a: int32 = 1, b: int32 = 2;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test dict literal compiles`() {
        val source = """
            package test;
            function main(): void {
                var d = dict{ "key": "value" };
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test property with get and set compiles`() {
        val source = """
            package test;
            class MyClass {
                var name: string { get; set; };
            }
            function main(): void {
                println("ok");
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test null coalescing compiles`() {
        val source = """
            package test;
            function main(): void {
                var n: string? = null;
                var r = n ?? "default";
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test ternary expression compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 5;
                var r = x > 0 ? "pos" : "neg";
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test cast expression compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: string = "hello";
                var y = cast x as string;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test while loop compiles`() {
        val source = """
            package test;
            function main(): void {
                var i: int32 = 0;
                while (i < 5) {
                    i = i + 1;
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test lambda with call compiles`() {
        val source = """
            package test;
            function main(): void {
                var f = (x: int32) => x + 1;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test array init values compiles`() {
        val source = """
            package test;
            function main(): void {
                var arr: int32[] = new int32[] [1, 2, 3];
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test lock statement compiles`() {
        val source = """
            package test;
            function main(): void {
                var x: int32 = 0;
                lock (x) { }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }

    @Test
    fun `test defer inside function compiles`() {
        val source = """
            package test;
            function main(): void {
                defer {
                    var x: int32 = 1;
                }
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(result is JasperCompiler.CompileResult.Success, "Expected Success but got: $result")
    }
}
