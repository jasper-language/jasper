package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 实例字段声明初值（Q148）的端到端测试。
 *
 * 规则：初值在每个不委托 `this(...)` 的构造器里求值，时机是 super 调用之后、构造器体之前，
 * 按声明顺序。每个用例都编译并真正运行，只看编译成功发现不了「字段停在零值」。
 */
class FieldInitializerTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "field-init", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    /** 编译并调用顶层 `main()`，返回其结果。 */
    private fun run(name: String, source: String): Any? {
        val dir = scratch(name)
        val result = JasperCompiler().compileToDirWithIr(source, dir)
        assertTrue(result is JasperCompiler.CompileResult.Success, "compile $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val cls = loader.loadClass("t.main")
            return cls.getMethod("main").invoke(null)
        }
    }

    private fun compileError(name: String, source: String): String {
        val result = JasperCompiler().compileToDirWithIr(source, scratch(name))
        assertFalse(result is JasperCompiler.CompileResult.Success, "$name should be rejected")
        return (result as JasperCompiler.CompileResult.Error).message
    }

    @Test
    fun `no explicit constructor runs literal initializers`() {
        assertEquals(5, run("int", """
            package t;
            public class Box { public var v: int32 = 5; }
            public function main(): int32 { return new Box().v; }
        """))
        assertEquals("n", run("string", """
            package t;
            public class Box { public var name: string = "n"; }
            public function main(): string { return new Box().name; }
        """))
    }

    @Test
    fun `explicit constructor runs initializers before its body`() {
        assertEquals(15, run("ctor", """
            package t;
            public class Box {
                public var a: int32 = 5;
                public var b: int32;
                public constructor(x: int32) { this.b = this.a + x; }
            }
            public function main(): int32 { return new Box(10).b; }
        """))
    }

    @Test
    fun `constructor body overrides the initializer`() {
        assertEquals(7, run("override", """
            package t;
            public class Box {
                public var a: int32 = 5;
                public constructor() { this.a = 7; }
            }
            public function main(): int32 { return new Box().a; }
        """))
    }

    @Test
    fun `parameter with the field's name does not capture the initializer`() {
        assertEquals(9, run("shadow", """
            package t;
            public class Box {
                public var a: int32 = 4;
                public var b: int32 = this.a + 5;
                public constructor(a: int32) { }
            }
            public function main(): int32 { return new Box(100).b; }
        """))
    }

    @Test
    fun `this delegation runs initializers exactly once`() {
        assertEquals(1, run("delegate", """
            package t;
            public class Counter { public static var n: int32 = 0; public static function next(): int32 { Counter.n = Counter.n + 1; return Counter.n; } }
            public class Box {
                public var a: int32 = Counter.next();
                public constructor() { }
                public constructor(x: int32) { this(); }
            }
            public function main(): int32 { return new Box(3).a; }
        """))
    }

    @Test
    fun `every non delegating constructor runs initializers`() {
        assertEquals(66, run("two", """
            package t;
            public class Box {
                public var a: int32 = 6;
                public constructor() { }
                public constructor(x: int32) { }
            }
            public function main(): int32 { return new Box().a * 10 + new Box(1).a; }
        """))
    }

    @Test
    fun `superclass initializers run before subclass initializers`() {
        assertEquals("base-sub", run("order", """
            package t;
            public class Base { public var log: string = "base"; }
            public class Sub extends Base {
                public var seen: string = this.log + "-sub";
            }
            public function main(): string { return new Sub().seen; }
        """))
        assertEquals(12, run("order-super-args", """
            package t;
            public class Base {
                public var a: int32 = 2;
                public var fromArg: int32;
                public constructor(x: int32) { this.fromArg = x + this.a; }
            }
            public class Sub extends Base {
                public var b: int32 = this.fromArg + 7;
                public constructor() { super(3); }
            }
            public function main(): int32 { return new Sub().b; }
        """))
    }

    @Test
    fun `initializers widen and box like an assignment`() {
        assertEquals(5L, run("wide-long", """
            package t;
            public class Box { public var v: int64 = 5; }
            public function main(): int64 { return new Box().v; }
        """))
        assertEquals(2.0, run("wide-double", """
            package t;
            public class Box { public var v: float64 = 2; }
            public function main(): float64 { return new Box().v; }
        """))
        assertEquals(8, run("any-box", """
            package t;
            public class Box { public var v: any = 8; }
            public function main(): any { return new Box().v; }
        """))
        assertEquals(null, run("nullable", """
            package t;
            public class Box { public var v: string? = null; }
            public function main(): string? { return new Box().v; }
        """))
    }

    @Test
    fun `reference initializers construct a fresh object per instance`() {
        assertEquals(1, run("fresh", """
            package t;
            public class Inner { public var n: int32 = 0; }
            public class Box { public var inner: Inner = new Inner(); }
            public function main(): int32 {
                var a: Box = new Box();
                var b: Box = new Box();
                a.inner.n = 1;
                return a.inner.n + b.inner.n;
            }
        """))
    }

    @Test
    fun `array initializer takes the declared element type`() {
        assertEquals(6, run("array", """
            package t;
            public class Box { public var xs: int32[] = [1, 2, 3]; }
            public function main(): int32 { var b: Box = new Box(); return b.xs[0] + b.xs[1] + b.xs[2]; }
        """))
    }

    @Test
    fun `enum instance field initializers run`() {
        assertEquals(3, run("enum", """
            package t;
            public enum Color { RED, GREEN; public var weight: int32 = 3; public function w(): int32 { return this.weight; } }
            public function main(): int32 { return Color.GREEN.w(); }
        """))
    }

    @Test
    fun `static initializers are not run per instance`() {
        assertEquals(2, run("static", """
            package t;
            public class Box {
                public static var made: int32 = 0;
                public var id: int32 = Box.bump();
                public static function bump(): int32 { Box.made = Box.made + 1; return Box.made; }
            }
            public function main(): int32 { var first: Box = new Box(); return new Box().id; }
        """))
    }

    @Test
    fun `incompatible initializer is rejected at the declaration`() {
        val message = compileError("bad-type", """
            package t;
            public class Box { public var v: int32 = "x"; }
            public function main(): int32 { return new Box().v; }
        """)
        assertTrue("cannot assign" in message && "'v'" in message, message)
    }

    @Test
    fun `simple name forward and self references are rejected`() {
        val forward = compileError("forward", """
            package t;
            public class Box { public var a: int32 = b; public var b: int32 = 1; }
            public function main(): int32 { return new Box().a; }
        """)
        assertTrue("b" in forward, forward)
        val self = compileError("self", """
            package t;
            public class Box { public var a: int32 = a; }
            public function main(): int32 { return new Box().a; }
        """)
        assertTrue("a" in self, self)
    }

    @Test
    fun `const instance field and generic field initializers run`() {
        assertEquals(3, run("const", """
            package t;
            public class Box { public const k: int32 = 3; }
            public function main(): int32 { return new Box().k; }
        """))
        assertEquals(0, run("generic", """
            package t;
            import java.util.ArrayList;
            public class Box<T> { public var xs: ArrayList<T> = new ArrayList<T>(); }
            public function main(): int32 { var b: Box<string> = new Box<string>(); return b.xs.size(); }
        """))
    }

    @Test
    fun `lambda initializer compiles in every constructor`() {
        assertEquals(true, run("lambda", """
            package t;
            public class Box {
                public var f: any = (x: int32) => x + 1;
                public constructor() { }
                public constructor(y: int32) { }
            }
            public function main(): bool { return new Box().f != null && new Box(2).f != null; }
        """))
    }

    /**
     * 属性测试（种子固定）：随机生成字段初值、构造器形态与继承层数，判据按规则独立算出，
     * 不走编译器。覆盖形态：无构造器 / 有构造器 / this 委托 / 构造器体改写 / 两层继承。
     */
    @Test
    fun `random classes match the independently computed model`() {
        val random = Random(20261105)
        repeat(60) { round ->
            val baseFields = List(random.nextInt(0, 4)) { "b$it" to random.nextInt(-50, 50) }
            val subFields = List(random.nextInt(1, 4)) { "s$it" to random.nextInt(-50, 50) }
            val form = random.nextInt(4)
            val ctorSets = random.nextInt(-9, 9)
            val src = StringBuilder("package t;\n")
            src.append("public class Log { public static var n: int32 = 0; public static function tick(v: int32): int32 { Log.n = Log.n * 31 + v; return v; } }\n")
            src.append("public class Base {\n")
            baseFields.forEach { (name, v) -> src.append("  public var $name: int32 = Log.tick($v);\n") }
            src.append("}\n")
            src.append("public class Sub extends Base {\n")
            subFields.forEach { (name, v) -> src.append("  public var $name: int32 = Log.tick($v);\n") }
            when (form) {
                0 -> {}
                1 -> src.append("  public constructor() { Log.tick($ctorSets); }\n")
                2 -> src.append("  public constructor() { Log.tick($ctorSets); }\n  public constructor(x: int32) { this(); Log.tick(x); }\n")
                else -> src.append("  public constructor() { super(); this.s0 = $ctorSets; }\n")
            }
            src.append("}\n")
            val ctorCall = if (form == 2) "new Sub(4)" else "new Sub()"
            val sum = subFields.indices.joinToString(" + ") { "o.s$it" }
            src.append("public function main(): int32 { var o: Sub = $ctorCall; return Log.n * 1000003 + $sum; }\n")

            var log = 0
            baseFields.forEach { log = log * 31 + it.second }
            subFields.forEach { log = log * 31 + it.second }
            when (form) {
                1 -> log = log * 31 + ctorSets
                2 -> { log = log * 31 + ctorSets; log = log * 31 + 4 }
            }
            val fields = subFields.map { it.second }.toMutableList()
            if (form == 3) fields[0] = ctorSets
            val expected = log * 1000003 + fields.sum()
            assertEquals(expected, run("rnd-$round", src.toString()), "round=$round\n$src")
        }
    }
}
