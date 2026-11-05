package jasper

import jasper.compiler.JasperCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

/**
 * 字段只能经 `this.` 或 `类名.` 访问（Q220）。
 *
 * 修复前简单名能过检查器，翻译后退化成 this：读产生 VerifyError，或在字段与类同类型时
 * 静默得到 this；写被静默丢弃。这里既查拒绝（诊断要说清该怎么改），也编译运行合法写法，
 * 防止诊断误伤形参、局部变量和类型名。
 */
class SimpleNameFieldTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "simple-name-field", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    private fun run(name: String, source: String): Any? {
        val dir = scratch(name)
        val result = JasperCompiler().compileToDirWithIr(source, dir)
        assertTrue(result is JasperCompiler.CompileResult.Success, "compile $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            return loader.loadClass("t.main").getMethod("main").invoke(null)
        }
    }

    private fun compileError(name: String, source: String): String {
        val result = JasperCompiler().compileToDirWithIr(source, scratch(name))
        assertFalse(result is JasperCompiler.CompileResult.Success, "$name should be rejected")
        return (result as JasperCompiler.CompileResult.Error).message
    }

    private fun boxWith(name: String, method: String): String = """
        package t;
        public class Box {
            public var a: int32 = 1;
            public var other: Box? = null;
            $method
        }
        public function main(): int32 { return 0; }
    """

    private fun assertInstanceFieldRejected(name: String, method: String, field: String = "a") {
        val message = compileError(name, boxWith(name, method))
        assertTrue("Field '$field' cannot be accessed by its simple name; write 'this.$field'" in message, message)
    }

    @Test
    fun `every use of a simple field name is rejected with the fix`() {
        val forms = mapOf(
            "read" to "public function m(): int32 { return a + 1; }",
            "write" to "public function m(): void { a = 9; }",
            "compound" to "public function m(): void { a += 1; }",
            "increment" to "public function m(): void { a++; }",
            "template" to "public function m(): string { return f\"v{a}\"; }",
            "argument" to "public function n(v: int32): int32 { return v; } public function m(): int32 { return this.n(a); }",
            "branch" to "public function m(): int32 { if (a > 0) { return 1; } return 0; }",
            "lambda" to "public function m(): int32 { var f: any = (x: int32) => x + a; return 0; }",
            "constructor" to "public constructor() { a = 3; }",
            "tuple" to "public function m(): void { var t = (a, 1); }",
        )
        for ((name, method) in forms) assertInstanceFieldRejected(name, method)
    }

    @Test
    fun `increment and template operands are checked`() {
        val undeclared = compileError("inc-undeclared", """
            package t;
            public function main(): int32 { nothing++; return 0; }
        """)
        assertTrue("Unresolved reference: 'nothing'" in undeclared, undeclared)
        val text = compileError("inc-string", """
            package t;
            public function main(): int32 { var s: string = "x"; s++; return 0; }
        """)
        assertTrue("Operator '++' needs a numeric operand, got string" in text, text)
        val template = compileError("template-undeclared", """
            package t;
            public function main(): string { return f"v{nothing}"; }
        """)
        assertTrue("Unresolved reference: 'nothing'" in template, template)
        val value = compileError("inc-as-value", """
            package t;
            public function main(): int32 { var i: int32 = 0; var s: string = ""; s = i++; return 0; }
        """)
        assertTrue("cannot assign" in value, value)
        assertInstanceFieldRejected("inc-assignment-source", "public function m(): int32 { var y: int32 = 0; y = a++; return y; }")
    }

    @Test
    fun `template over this field runs`() {
        assertEquals("v7", run("template-this", """
            package t;
            public class Box { public var a: int32 = 7; public function m(): string { return f"v{this.a}"; } }
            public function main(): string { return new Box().m(); }
        """))
    }

    @Test
    fun `valid increments still run`() {
        val result = run("inc-valid", """
            package t;
            public class Box {
                public var a: int32 = 5;
                public static var n: int32 = 1;
                public function bump(): void { this.a++; Box.n++; }
            }
            public function main(): int32 {
                var box: Box = new Box();
                box.bump();
                var xs: int32[] = [3];
                xs[0]++;
                var i: int32 = 1;
                var j: int32 = 0;
                j = i++;
                return box.a + Box.n + xs[0] + i + j;
            }
        """)
        assertEquals(6 + 2 + 4 + 2 + 1, result)
    }

    @Test
    fun `same type read that used to silently yield this is rejected`() {
        assertInstanceFieldRejected("same-type", "public function m(): Box? { return other; }", "other")
        assertInstanceFieldRejected("receiver", "public function m(): int32 { return other?.a ?? 0; }", "other")
    }

    @Test
    fun `method declared before the field is rejected the same way`() {
        val message = compileError("order", """
            package t;
            public class Box {
                public function m(): int32 { return a; }
                public var a: int32 = 1;
            }
            public function main(): int32 { return 0; }
        """)
        assertTrue("write 'this.a'" in message, message)
    }

    @Test
    fun `static field points to the class name`() {
        val inStatic = compileError("static", """
            package t;
            public class Box {
                public static var n: int32 = 1;
                public static function m(): int32 { return n; }
            }
            public function main(): int32 { return 0; }
        """)
        assertTrue("write 'Box.n'" in inStatic, inStatic)
        val inInstance = compileError("static-from-instance", """
            package t;
            public class Box {
                public static var n: int32 = 1;
                public function m(): void { n = 2; }
            }
            public function main(): int32 { return 0; }
        """)
        assertTrue("write 'Box.n'" in inInstance, inInstance)
    }

    @Test
    fun `initializer must qualify earlier fields`() {
        val message = compileError("initializer", """
            package t;
            public class Box { public var a: int32 = 1; public var b: int32 = a + 1; }
            public function main(): int32 { return 0; }
        """)
        assertTrue("write 'this.a'" in message, message)
        assertEquals(2, run("initializer-this", """
            package t;
            public class Box { public var a: int32 = 1; public var b: int32 = this.a + 1; }
            public function main(): int32 { return new Box().b; }
        """))
    }

    @Test
    fun `inherited and outer fields stay unresolved`() {
        val inherited = compileError("inherited", """
            package t;
            public class Base { public var a: int32 = 1; }
            public class Sub extends Base { public function m(): int32 { return a; } }
            public function main(): int32 { return 0; }
        """)
        assertTrue("Unresolved reference: 'a'" in inherited, inherited)
        assertEquals(1, run("inherited-this", """
            package t;
            public class Base { public var a: int32 = 1; }
            public class Sub extends Base { public function m(): int32 { return this.a; } }
            public function main(): int32 { return new Sub().m(); }
        """))
        val outer = compileError("outer", """
            package t;
            public class Outer {
                public var a: int32 = 1;
                public class Inner { public function m(): int32 { return a; } }
            }
            public function main(): int32 { return 0; }
        """)
        assertTrue("Unresolved reference: 'a'" in outer, outer)
    }

    @Test
    fun `outer class diagnostics survive a nested class`() {
        val message = compileError("after-nested", """
            package t;
            public class Outer {
                public var a: int32 = 1;
                public class Inner { public var b: int32 = 2; }
                public function m(): int32 { return a; }
            }
            public function main(): int32 { return 0; }
        """)
        assertTrue("write 'this.a'" in message, message)
    }

    @Test
    fun `field name does not leak outside its class`() {
        val message = compileError("leak", """
            package t;
            public class Box { public var a: int32 = 1; }
            public function main(): int32 { return a; }
        """)
        assertTrue("Unresolved reference: 'a'" in message, message)
        assertFalse("simple name" in message, message)
    }

    @Test
    fun `parameters and locals shadow fields`() {
        assertEquals(1234, run("shadow", """
            package t;
            public class Box {
                public var a: int32 = 1000;
                public var b: int32 = 200;
                public function byParam(a: int32): int32 { return a; }
                public function byLocal(): int32 { var b: int32 = 4; return b; }
                public function write(a: int32): void { a = a + 1; this.a = this.a + a; }
            }
            public function main(): int32 {
                var box: Box = new Box();
                box.write(29);
                return box.a + box.byParam(200) + box.byLocal();
            }
        """))
    }

    @Test
    fun `constructor parameter with the field name assigns through this`() {
        assertEquals(7, run("ctor-param", """
            package t;
            public class Box {
                public var a: int32 = 1;
                public constructor(a: int32) { this.a = a; }
            }
            public function main(): int32 { return new Box(7).a; }
        """))
    }

    @Test
    fun `field named like a type does not capture the type name`() {
        assertEquals(5, run("type-name", """
            package t;
            public class Counter { public static var n: int32 = 5; }
            public class Box {
                public var Counter: int32 = 9;
                public function m(): int32 { return Counter.n; }
            }
            public function main(): int32 { return new Box().m(); }
        """))
    }

    @Test
    fun `top level function named like a field stays callable`() {
        assertEquals(3, run("function-name", """
            package t;
            public function amount(): int32 { return 3; }
            public class Box {
                public var amount: int32 = 9;
                public function m(): int32 { return amount(); }
            }
            public function main(): int32 { return new Box().m(); }
        """))
    }

    /**
     * 固定种子：随机类里每个名字独立决定是字段、形参还是局部。判据由生成器自己算：
     * 只要方法体里有未被遮蔽的字段简单名就必须拒绝，并且诊断点名其中一个；否则运行结果
     * 必须等于模型值。模型不看编译器，遮蔽漏判和误判都会被抓到。
     */
    @Test
    fun `random bodies match the independently computed model`() {
        val random = Random(20261106)
        var rejected = 0
        var accepted = 0
        repeat(120) { round ->
            val names = listOf("p", "q", "r", "s")
            val fieldValues = names.associateWith { random.nextInt(-40, 40) }
            val paramNames = names.filter { random.nextInt(4) == 0 }
            val localNames = (names - paramNames.toSet()).filter { random.nextInt(4) == 0 }
            val argValues = paramNames.associateWith { random.nextInt(-40, 40) }
            val localValues = localNames.associateWith { random.nextInt(-40, 40) }
            val scoped = argValues + localValues
            val terms = List(random.nextInt(1, 6)) {
                val name = names.random(random)
                val qualified = random.nextBoolean()
                Triple(name, qualified, if (qualified) fieldValues.getValue(name) else scoped[name])
            }
            val src = StringBuilder("package t;\npublic class Box {\n")
            val methodFirst = random.nextBoolean()
            val fieldsText = fieldValues.entries.joinToString("\n") { "  public var ${it.key}: int32 = ${it.value};" }
            val body = StringBuilder("  public function m(${paramNames.joinToString(", ") { "$it: int32" }}): int32 {\n")
            localValues.forEach { (name, value) -> body.append("    var $name: int32 = $value;\n") }
            body.append("    return ${terms.joinToString(" + ") { (name, qualified, _) -> if (qualified) "this.$name" else name }};\n  }\n")
            if (methodFirst) src.append(body).append(fieldsText).append("\n") else src.append(fieldsText).append("\n").append(body)
            src.append("}\npublic function main(): int32 { return new Box().m(${paramNames.joinToString(", ") { argValues.getValue(it).toString() }}); }\n")

            val bare = terms.filter { (_, qualified, value) -> !qualified && value == null }.map { it.first }.toSet()
            if (bare.isEmpty()) {
                accepted++
                assertEquals(terms.sumOf { it.third!! }, run("random", src.toString()), "round $round:\n$src")
            } else {
                rejected++
                val message = compileError("random", src.toString())
                assertTrue(bare.any { "Field '$it' cannot be accessed by its simple name; write 'this.$it'" in message }, "round $round: $message\n$src")
            }
        }
        // 生成器如果退化到只出一边，这个属性就什么都没测。
        assertTrue(rejected >= 20 && accepted >= 20, "rejected=$rejected accepted=$accepted")
    }
}
