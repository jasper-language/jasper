// ============================================================================
// JasoImportTest.kt —— 源码 import 解析到 `.jaso`（JB-L3 第三片）
// ============================================================================
// 职责：真实编译模块 B 与导入它的模块 A，经 std.jaso 运行时依次装载并调用，看结果；
//   缺模块、未声明依赖、版本不符、类型不符、能力不足、不可见成员、坏文件逐条看诊断。
//   样本写到 build/tmp/jaso-emit-samples，交给参考实现核对（verification/jaso-diff）。
// ============================================================================

package jasper

import jasper.ast.JasArrayType
import jasper.ast.JasNamedType
import jasper.ast.JasNullableType
import jasper.ast.JasPrimitiveType
import jasper.ast.JasStringType
import jasper.compiler.JasoModuleFile
import jasper.compiler.JasoModuleFormatException
import jasper.compiler.JasoModuleOptions
import jasper.compiler.JasoSignatureParser
import jasper.compiler.JasperCompiler
import jasper.compiler.emitJasoMain
import jasper.compiler.jasoRangeContains
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 在一个临时模块路径上编译模块；用完删除。 */
internal class ModuleBench : AutoCloseable {
    val dir: Path = Files.createTempDirectory("jaso-import-test")

    /** 编译并放到模块路径上，失败抛出。 */
    fun module(options: JasoModuleOptions, vararg sources: Pair<String, String>, sample: String? = null, expected: String = "ok"): ByteArray {
        val out = dir.resolve("${options.name}.jaso")
        val r = JasperCompiler().compileToJaso(mapOf(*sources), options, out, listOf(dir))
        check(r is JasperCompiler.CompileResult.Success) { "compile failed: ${(r as JasperCompiler.CompileResult.Error).message}" }
        val bytes = Files.readAllBytes(out)
        if (sample != null) {
            val samples = Paths.get("build/tmp/jaso-emit-samples")
            Files.createDirectories(samples)
            Files.write(samples.resolve("$sample.jaso"), bytes)
            Files.write(samples.resolve("$sample.expect"), "$expected\n".toByteArray())
        }
        return bytes
    }

    /** 编译必须失败，返回诊断文本。 */
    fun rejected(options: JasoModuleOptions, vararg sources: Pair<String, String>): String {
        val out = dir.resolve("rejected-${options.name}.out")
        val r = JasperCompiler().compileToJaso(mapOf(*sources), options, out, listOf(dir))
        assertTrue(r is JasperCompiler.CompileResult.Error, "expected a diagnostic, compiled instead: ${sources.last().second}")
        assertFalse(Files.exists(out), "a failed compile must not write the output")
        return r.message
    }

    override fun close() {
        dir.toFile().deleteRecursively()
    }
}

class JasoImportTest {
    private val host = JasoHost()

    private fun lib(name: String = "lib", version: String = "1.0.0", requires: List<String> = emptyList()) =
        JasoModuleOptions(name, version, publisher = "jasper.dev", requires = requires)

    private fun app(vararg depends: String, requires: List<String> = emptyList()) =
        JasoModuleOptions("app", "1.0.0", publisher = "jasper.dev", depends = depends.toList(), requires = requires)

    private val mathSource = "add.jas" to """
        package lib.math;
        public function add(a: int32, b: int32): int32 { return a + b; }
    """.trimIndent()

    private val counterSource = "Counter.jas" to """
        package lib.math;
        public class Counter {
            private var n: int32;
            public constructor(start: int32) { this.n = start; }
            public function bump(by: int32): int32 { this.n = this.n + by; return this.n; }
            public static function twice(x: int32): int32 { return x * 2; }
            private function hidden(): int32 { return 1; }
        }
    """.trimIndent()

    /** 装 B 再装 A，调 A 的导出函数。 */
    private fun run(b: ByteArray, a: ByteArray, symbol: String, vararg args: Any?): Any? {
        val runtime = host.runtime()
        host.load(runtime, b)
        return host.call(host.load(runtime, a), symbol, *args)
    }

    @Test
    fun `a program imports a compiled module and calls typed functions`() = ModuleBench().use { bench ->
        // 样本要过参考实现的一致性上下文：名字落在开发发布者的命名空间里。
        val b = bench.module(lib("jaso-lib"), mathSource, counterSource, sample = "import-lib")
        val a = bench.module(JasoModuleOptions("jaso-app", "1.0.0", publisher = "jasper.dev", depends = listOf("jaso-lib@>=1.0,<2.0")), "compute.jas" to """
            package app.main;
            import lib.math.Counter;
            from lib.math import add;
            public function compute(x: int32): int32 {
                var c: Counter = new Counter(add(x, 1));
                c.bump(10);
                return Counter.twice(c.bump(100));
            }
        """.trimIndent(), sample = "import-app", expected = "dependency.unsatisfied") // 一致性上下文里没有运行中的依赖
        assertEquals((5 + 1 + 110) * 2, run(b, a, "app.main.compute.compute", 5))
    }

    @Test
    fun `a dependency that is not running stops the load`() = ModuleBench().use { bench ->
        bench.module(lib(), mathSource)
        val a = bench.module(app("lib"), "f.jas" to """
            package app.main;
            from lib.math import add;
            public function f(): int32 { return add(1, 2); }
        """.trimIndent())
        val rejected = assertFailsWith<Rejected> { host.load(host.runtime(), a) }
        assertEquals("dependency.unsatisfied", rejected.code)
    }

    @Test
    fun `import forms alias types and qualified names`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), mathSource, counterSource)
        val a = bench.module(app("lib"), "g.jas" to """
            package app.main;
            from lib.math import Counter as Tally;
            public function g(): int32 {
                var c: Tally = new Tally(1);
                var d: lib.math.Counter = c;
                return d.bump(2);
            }
        """.trimIndent())
        assertEquals(3, run(b, a, "app.main.g.g"))
    }

    @Test
    fun `interfaces inheritance overloads arrays nullable and strings cross the boundary`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "Shape.jas" to """
            package lib.geo;
            public interface Shape { function area(): int32; }
        """.trimIndent(), "Base.jas" to """
            package lib.geo;
            class Base implements Shape {
                public constructor() {}
                public function area(): int32 { return 1; }
            }
        """.trimIndent(), "Square.jas" to """
            package lib.geo;
            public class Square extends Base {
                private var s: int32;
                public constructor(s: int32) { this.s = s; }
                public constructor(a: int32, b: int32) { this.s = a + b; }
                public function area(): int32 { return this.s * this.s; }
                public function tag(prefix: string?): string { return prefix == null ? "none" : prefix; }
                public function pick(xs: int32[], i: int32): int32 { return xs[i]; }
                public function pick(xs: string[], i: int32, n: int32): string { return xs[i + n]; }
            }
        """.trimIndent(), "total.jas" to """
            package lib.geo;
            public function total(shapes: Shape[]): int32 {
                var sum: int32 = 0;
                var i: int32 = 0;
                while (i < shapes.length) { sum = sum + shapes[i].area(); i = i + 1; }
                return sum;
            }
        """.trimIndent())
        val a = bench.module(app("lib"), "h.jas" to """
            package app.main;
            import lib.geo.Shape;
            import lib.geo.Square;
            from lib.geo import total;
            class Unit implements Shape {
                public constructor() {}
                public function area(): int32 { return 7; }
            }
            public function h(): string {
                var s: Square = new Square(3);
                var t: Shape = new Square(1, 1);
                var all: Shape[] = new Shape[3];
                all[0] = s;
                all[1] = t;
                all[2] = new Unit();
                var xs: int32[] = new int32[2];
                xs[1] = 40;
                var names: string[] = new string[1];
                names[0] = "n";
                return "" + total(all) + "," + s.pick(xs, 1) + "," + s.pick(names, 0, 0) + "," + s.tag(null) + "," + s.tag("p");
            }
        """.trimIndent())
        assertEquals("20,40,n,none,p", run(b, a, "app.main.h.h"))
    }

    @Test
    fun `a module's capabilities must be requested by the importer`() = ModuleBench().use { bench ->
        val b = bench.module(lib(requires = listOf("net.client")), mathSource)
        val src = "f.jas" to """
            package app.main;
            from lib.math import add;
            public function f(): int32 { return add(1, 2); }
        """.trimIndent()
        val message = bench.rejected(app("lib"), src)
        assertTrue("needs capabilities net.client" in message, message)
        val a = bench.module(app("lib", requires = listOf("net.client")), src)
        val runtime = host.runtime()
        host.load(runtime, b, "net.client")
        assertEquals(3, host.call(host.load(runtime, a, "net.client"), "app.main.f.f"))
    }

    /** 每条：源码片段 → 诊断里必须出现的文字。模块路径上只有 lib。 */
    private fun rejects(expected: String, body: String, options: JasoModuleOptions = app("lib")) = ModuleBench().use { bench ->
        bench.module(lib(), mathSource, counterSource)
        val message = bench.rejected(options, "t.jas" to "package app.main;\n$body")
        assertTrue(expected in message, "wanted '$expected' in: $message")
    }

    @Test
    fun `missing module`() = rejects("No module on the module path provides package 'lib.nope'", """
        import lib.nope.Thing;
        public function f(): int32 { return 1; }
    """)

    @Test
    fun `name the module does not export`() = rejects("does not export 'Nope'", """
        import lib.math.Nope;
        public function f(): int32 { return 1; }
    """)

    @Test
    fun `import without a declared dependency`() = rejects("add --depends lib", """
        from lib.math import add;
        public function f(): int32 { return add(1, 2); }
    """, app())

    @Test
    fun `declared dependency not on the path`() = rejects("Dependency 'other' is not on the module path", """
        public function f(): int32 { return 1; }
    """, app("other"))

    @Test
    fun `version outside the declared range`() = rejects("does not accept version 1.0.0", """
        public function f(): int32 { return 1; }
    """, app("lib@>=2.0"))

    @Test
    fun `argument type mismatch on a function`() = rejects("add", """
        from lib.math import add;
        public function f(): int32 { return add("x", 2); }
    """)

    @Test
    fun `argument count mismatch on a function`() = rejects("expects 2 argument(s), got 1", """
        from lib.math import add;
        public function f(): int32 { return add(1); }
    """)

    @Test
    fun `result type mismatch`() = rejects("string", """
        from lib.math import add;
        public function f(): string { return add(1, 2); }
    """)

    @Test
    fun `argument type mismatch on a constructor`() = rejects("No constructor of 'Counter' accepts", """
        import lib.math.Counter;
        public function f(): int32 { var c: Counter = new Counter("x"); return 1; }
    """)

    @Test
    fun `argument type mismatch on a static method`() = rejects("does not accept", """
        import lib.math.Counter;
        public function f(): int32 { return Counter.twice("x"); }
    """)

    @Test
    fun `argument count mismatch on a static method`() = rejects("expects 1 argument(s), got 2", """
        import lib.math.Counter;
        public function f(): int32 { return Counter.twice(1, 2); }
    """)

    @Test
    fun `overloads by count`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "Over.jas" to """
            package lib.math;
            public class Over {
                public constructor() {}
                public function f(a: int32): int32 { return a; }
                public function f(a: int32, c: int32): int32 { return a + c; }
            }
        """.trimIndent())
        val a = bench.module(app("lib"), "t.jas" to """
            package app.main;
            import lib.math.Over;
            public function t(): int32 { var o: Over = new Over(); return o.f(1) * 10 + o.f(2, 3); }
        """.trimIndent())
        assertEquals(15, run(b, a, "app.main.t.t"))
    }

    @Test
    fun `argument type mismatch on a method`() = rejects("bump", """
        import lib.math.Counter;
        public function f(): int32 { var c: Counter = new Counter(1); return c.bump("x"); }
    """)

    @Test
    fun `member outside the interface`() = rejects("hidden", """
        import lib.math.Counter;
        public function f(): int32 { var c: Counter = new Counter(1); return c.hidden(); }
    """)

    @Test
    fun `on-demand import of a module package`() = rejects("one by one", """
        import lib.math.*;
        public function f(): int32 { return 1; }
    """)

    @Test
    fun `function alias is diagnosed`() = rejects("cannot be imported under another name", """
        from lib.math import add as plus;
        public function f(): int32 { return plus(1, 2); }
    """)

    @Test
    fun `imported function colliding with a local one`() = rejects("same name as another function", """
        from lib.math import add;
        public function add(x: string): string { return x; }
        public function f(): int32 { return 1; }
    """)

    @Test
    fun `an import in one file does not open the name for another`() = ModuleBench().use { bench ->
        bench.module(lib(), mathSource, counterSource)
        val fn = bench.rejected(app("lib"), "one.jas" to """
            package app.main;
            from lib.math import add;
            public function one(): int32 { return add(1, 2); }
        """.trimIndent(), "two.jas" to """
            package app.main;
            public function two(): int32 { return add(3, 4); }
        """.trimIndent())
        assertTrue("does not import it" in fn, fn)
        val type = bench.rejected(app("lib"), "one.jas" to """
            package app.main;
            import lib.math.Counter;
            public function one(): int32 { return Counter.twice(1); }
        """.trimIndent(), "two.jas" to """
            package app.main;
            public function two(c: Counter): int32 { return 1; }
        """.trimIndent())
        assertTrue("Unknown type 'Counter'" in type, type)
    }

    @Test
    fun `a class with no public constructor cannot be created`() = ModuleBench().use { bench ->
        bench.module(lib(), "Single.jas" to """
            package lib.math;
            public class Single {
                private constructor() {}
                public static function make(): Single { return new Single(); }
                public function value(): int32 { return 9; }
            }
        """.trimIndent())
        val message = bench.rejected(app("lib"), "t.jas" to """
            package app.main;
            import lib.math.Single;
            public function f(): int32 { return new Single().value(); }
        """.trimIndent())
        assertTrue("no public constructor" in message, message)
    }

    @Test
    fun `an abstract class cannot be created`() = ModuleBench().use { bench ->
        bench.module(lib(), "Base.jas" to """
            package lib.math;
            public abstract class Base {
                public constructor() {}
                public abstract function value(): int32;
            }
        """.trimIndent())
        val message = bench.rejected(app("lib"), "t.jas" to """
            package app.main;
            import lib.math.Base;
            public function f(): int32 { return new Base().value(); }
        """.trimIndent())
        assertTrue("Cannot create abstract class" in message, message)
    }

    @Test
    fun `an interface cannot be created`() = ModuleBench().use { bench ->
        bench.module(lib(), "Shape.jas" to """
            package lib.geo;
            public interface Shape { function area(): int32; }
        """.trimIndent())
        val message = bench.rejected(app("lib"), "t.jas" to """
            package app.main;
            import lib.geo.Shape;
            public function f(): int32 { return new Shape().area(); }
        """.trimIndent())
        assertTrue("it is not a class" in message, message)
    }

    @Test
    fun `a class extends an imported class and calls inherited and overridden members`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "Base.jas" to """
            package lib.geo;
            public abstract class Base {
                private var k: int32;
                public constructor(k: int32) { this.k = k; }
                public abstract function value(): int32;
                public function scaled(): int32 { return this.k * this.value(); }
            }
        """.trimIndent())
        val a = bench.module(app("lib"), "k.jas" to """
            package app.main;
            import lib.geo.Base;
            class Five extends Base {
                public constructor() { super(3); }
                public function value(): int32 { return 5; }
            }
            public function k(): int32 { return new Five().scaled(); }
        """.trimIndent())
        assertEquals(15, run(b, a, "app.main.k.k"))
    }

    @Test
    fun `super calls must match the module interface`() = ModuleBench().use { bench ->
        bench.module(lib(), "Base.jas" to """
            package lib.geo;
            public abstract class Base {
                public constructor(k: int32) {}
                public abstract function value(): int32;
            }
        """.trimIndent())
        fun rejected(body: String) = bench.rejected(app("lib"), "t.jas" to "package app.main;\nimport lib.geo.Base;\n$body\n")
        val wrongType = rejected("class A extends Base { public constructor() { super(\"x\"); } public function value(): int32 { return 1; } }")
        assertTrue("No constructor of 'Base' accepts" in wrongType, wrongType)
        val implicit = rejected("class A extends Base { public function value(): int32 { return 1; } }")
        assertTrue("expects 1 argument(s), got 0" in implicit, implicit)
    }

    @Test
    fun `calling the constructor of an abstract module class through the runtime is refused`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "Base.jas" to """
            package lib.geo;
            public abstract class Base {
                public constructor() {}
                public abstract function value(): int32;
            }
        """.trimIndent())
        val module = host.load(host.runtime(), b)
        assertEquals("module.argument", assertFailsWith<Rejected> { host.call(module, "lib.geo.Base.new") }.code)
    }

    @Test
    fun `an abstract class in one compilation runs and is not creatable`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "Base.jas" to """
            package lib.geo;
            public abstract class Base {
                public abstract function value(): int32;
                public function twice(): int32 { return this.value() * 2; }
            }
            class One extends Base {
                public function value(): int32 { return 21; }
            }
            public function run(): int32 { return new One().twice(); }
        """.trimIndent())
        assertEquals(42, host.call(host.load(host.runtime(), b), "lib.geo.run.run"))
        val creating = bench.rejected(app(), "t.jas" to """
            package app.main;
            abstract class Local { public abstract function v(): int32; }
            public function f(): int32 { return new Local().v(); }
        """.trimIndent())
        assertTrue("Cannot create abstract class" in creating, creating)
        val misplaced = bench.rejected(app(), "t.jas" to """
            package app.main;
            public class Local { public abstract function v(): int32; }
        """.trimIndent())
        assertTrue("needs an abstract class" in misplaced, misplaced)
    }

    @Test
    fun `a module's types flow through a third module`() = ModuleBench().use { bench ->
        val c = bench.module(JasoModuleOptions("base", "1.0.0"), "Box.jas" to """
            package lib.base;
            public class Box {
                private var v: int32;
                public constructor(v: int32) { this.v = v; }
                public function get(): int32 { return this.v; }
            }
        """.trimIndent())
        val b = bench.module(JasoModuleOptions("mid", "1.0.0", depends = listOf("base")), "wrap.jas" to """
            package lib.mid;
            import lib.base.Box;
            public function wrap(v: int32): Box { return new Box(v + 1); }
        """.trimIndent())
        val src = "m.jas" to """
            package app.main;
            from lib.mid import wrap;
            public function m(): int32 { return wrap(1).get(); }
        """.trimIndent()
        val message = bench.rejected(app("mid"), src)
        assertTrue("Module 'base' appears in the interface" in message, message)
        val a = bench.module(app("mid", "base"), src)
        val runtime = host.runtime()
        host.load(runtime, c)
        host.load(runtime, b)
        assertEquals(2, host.call(host.load(runtime, a), "app.main.m.m"))
    }

    @Test
    fun `a Future returned by a module is awaited by the importer`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), "later.jas" to """
            package lib.math;
            import std.Future;
            public function later(x: int32): Future<int32> {
                var f: Future<int32> = new Future<int32>();
                f.complete(x + 1);
                return f;
            }
        """.trimIndent())
        val a = bench.module(app("lib"), "w.jas" to """
            package app.main;
            import std.Future;
            from lib.math import later;
            public function now(x: int32): int32 { return later(x).get(); }
        """.trimIndent())
        assertEquals(2, run(b, a, "app.main.now.now", 1))
    }

    @Test
    fun `two modules providing the imported package`() = ModuleBench().use { bench ->
        bench.module(lib("one"), mathSource)
        bench.module(lib("two"), mathSource)
        val message = bench.rejected(app("one"), "t.jas" to "package app.main;\nfrom lib.math import add;\npublic function f(): int32 { return add(1, 2); }\n")
        assertTrue("provided by several modules" in message, message)
    }

    @Test
    fun `a dependency present twice on the path`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), mathSource)
        Files.write(bench.dir.resolve("copy.jaso"), b)
        val message = bench.rejected(app("lib"), "t.jas" to "package app.main;\npublic function f(): int32 { return 1; }\n")
        assertTrue("more than once" in message, message)
    }

    @Test
    fun `a dependency defining the importer's own package`() = ModuleBench().use { bench ->
        bench.module(lib(), mathSource)
        val message = bench.rejected(app("lib"), "t.jas" to "package lib.math;\npublic function f(): int32 { return 1; }\n")
        assertTrue("which this module also defines" in message, message)
    }

    @Test
    fun `a stale copy of this module does not satisfy its imports`() = ModuleBench().use { bench ->
        bench.module(app(), "Thing.jas" to "package old.p;\npublic class Thing { public constructor() {} }\n")
        val message = bench.rejected(app(), "t.jas" to "package app.main;\nimport old.p.Thing;\npublic function f(): int32 { return 1; }\n")
        assertTrue("No module on the module path provides package 'old.p'" in message, message)
    }

    @Test
    fun `functions that are not imported do not collide with local names`() = ModuleBench().use { bench ->
        val b = bench.module(lib(), mathSource, "sub.jas" to "package lib.math;\npublic function sub(a: int32, b: int32): int32 { return a - b; }\n")
        val a = bench.module(app("lib"), "t.jas" to """
            package app.main;
            from lib.math import add;
            public function sub(x: string): int32 { return 100; }
            public function t(): int32 { return add(1, 2) + sub("x"); }
        """.trimIndent())
        assertEquals(103, run(b, a, "app.main.t.t"))
    }

    @Test
    fun `a class must implement the abstract methods of an imported interface`() = ModuleBench().use { bench ->
        bench.module(lib(), "Shape.jas" to "package lib.geo;\npublic interface Shape { function area(): int32; }\n")
        val message = bench.rejected(app("lib"), "t.jas" to """
            package app.main;
            import lib.geo.Shape;
            public class Empty implements Shape { public constructor() {} }
        """.trimIndent())
        assertTrue("area" in message, message)
    }

    @Test
    fun `files that cannot be imported are refused by the reader`() = ModuleBench().use { bench ->
        val good = bench.module(lib(), mathSource)
        val path = bench.dir.resolve("x.jaso")
        val native = good.copyOf().also { it[10] = 1 }
        assertTrue("not a Managed module" in assertFailsWith<JasoModuleFormatException> { JasoModuleFile.parse(path, native) }.message!!)
        val compressed = withCodePayload(good, zipOf(listOf("lib/math/add.class" to ByteArray(64)), compress = true))
        assertTrue("is compressed" in assertFailsWith<JasoModuleFormatException> { JasoModuleFile.parse(path, compressed) }.message!!)
    }

    @Test
    fun `a stale copy of the module being compiled is ignored`() = ModuleBench().use { bench ->
        val source = "f.jas" to "package app.main;\npublic function f(): int32 { return 1; }\n"
        bench.module(app(), source)
        bench.module(app(), source)
    }

    @Test
    fun `a broken file on the path is reported by path`() = ModuleBench().use { bench ->
        Files.write(bench.dir.resolve("broken.jaso"), ByteArray(200))
        val message = bench.rejected(app(), "f.jas" to "package app.main;\npublic function f(): int32 { return 1; }\n")
        assertTrue("broken.jaso" in message && "not a .jaso file" in message, message)
    }

    @Test
    fun `module path on the command line`() = ModuleBench().use { bench ->
        bench.module(lib(), mathSource)
        val src = bench.dir.resolve("f.jas")
        Files.write(src, "package app.main;\nfrom lib.math import add;\npublic function f(): int32 { return add(1, 2); }\n".toByteArray())
        val out = bench.dir.resolve("out").resolve("app.jaso")
        fun emit(vararg extra: String) = emitJasoMain(listOf("--emit", "jaso", "-o", out.toString(), "--name", "app", "--version", "1.0.0") + extra + listOf(src.toString()))
        assertEquals(1, emit("--module-path", bench.dir.toString()), "undeclared dependency")
        assertEquals(0, emit("--module-path", bench.dir.toString(), "--depends", "lib"))
        assertTrue(Files.exists(out))
    }

    @Test
    fun `version range agrees with the runtime`() {
        val range = JasoRuntime.loader.loadClass("std.jaso.JasoVersionRange")
        val version = JasoRuntime.loader.loadClass("std.jaso.JasoVersion")
        val parse = range.getMethod("parse", ByteArray::class.java, Int::class.java, Int::class.java)
        val contains = range.getMethod("contains", version)
        val make = version.constructors.first { it.parameterCount == 3 }
        val random = java.util.Random(20261170L)
        val ops = listOf(">=", ">", "<=", "<", "=")
        repeat(4000) {
            val text = (0..random.nextInt(4)).joinToString(",") {
                val patch = if (random.nextBoolean()) ".${random.nextInt(3)}" else ""
                "${ops[random.nextInt(5)]}${random.nextInt(3)}.${random.nextInt(3)}$patch"
            }
            val v = Triple(random.nextInt(3).toLong(), random.nextInt(3).toLong(), random.nextInt(3).toLong())
            val bytes = text.toByteArray()
            val parsed = parse.invoke(null, bytes, 0, bytes.size)
            assertEquals(contains.invoke(parsed, make.newInstance(v.first, v.second, v.third)), jasoRangeContains(text, v), "$text vs $v")
        }
    }

    /** 随机接口：类型表 → 字面量写法与期望的值。 */
    private class Kind(val type: String, val literal: (Int) -> String, val value: (Int) -> Any, val wrong: String)

    private val kinds = listOf(
        Kind("int32", { "$it" }, { it }, "\"s\""),
        Kind("int64", { "${it}L" }, { it.toLong() }, "\"s\""),
        Kind("bool", { if (it % 2 == 0) "true" else "false" }, { it % 2 == 0 }, "1"),
        Kind("string", { "\"v$it\"" }, { "v$it" }, "1"),
        Kind("float64", { "$it.5" }, { it + 0.5 }, "\"s\"")
    )

    /**
     * 随机接口的端到端：B 的每个函数返回某个形参，A 调用并拼成结果，与模型比对；
     * 再把每个调用的一个实参换成错类型，必须被诊断。
     */
    @Test
    fun `random interfaces agree with the model and reject wrong arguments`() {
        val random = java.util.Random(20261171L)
        repeat(3) { round ->
            ModuleBench().use { bench ->
                class Call(val text: (Int?) -> String, val expected: Any, val arity: Int)
                val sources = linkedMapOf<String, String>()
                val calls = mutableListOf<Call>()
                val imports = StringBuilder()
                sources["Holder.jas"] = buildString {
                    append("package lib.rnd;\npublic class Holder {\n    public constructor() {}\n")
                    repeat(8) { m ->
                        val params = List(1 + random.nextInt(4)) { kinds[random.nextInt(kinds.size)] }
                        val pick = random.nextInt(params.size)
                        val isStatic = random.nextBoolean()
                        val signature = params.withIndex().joinToString { (i, k) -> "p$i: ${k.type}" }
                        append("    public ${if (isStatic) "static " else ""}function m$m($signature): ${params[pick].type} { return p$pick; }\n")
                        val args = params.map { random.nextInt(100) }
                        val target = if (isStatic) "Holder.m$m" else "new Holder().m$m"
                        calls += Call(
                            { bad -> "$target(" + params.indices.joinToString { i -> if (i == bad) params[i].wrong else params[i].literal(args[i]) } + ")" },
                            params[pick].value(args[pick]), params.size
                        )
                    }
                    append("}\n")
                }
                repeat(8) { f ->
                    val params = List(1 + random.nextInt(4)) { kinds[random.nextInt(kinds.size)] }
                    val pick = random.nextInt(params.size)
                    val signature = params.withIndex().joinToString { (i, k) -> "p$i: ${k.type}" }
                    sources["fn$f.jas"] = "package lib.rnd;\npublic function fn$f($signature): ${params[pick].type} { return p$pick; }\n"
                    imports.append("from lib.rnd import fn$f;\n")
                    val args = params.map { random.nextInt(100) }
                    calls += Call(
                        { bad -> "fn$f(" + params.indices.joinToString { i -> if (i == bad) params[i].wrong else params[i].literal(args[i]) } + ")" },
                        params[pick].value(args[pick]), params.size
                    )
                }
                val b = bench.module(lib(), *sources.toList().toTypedArray())
                fun appSource(body: String) = "package app.main;\nimport lib.rnd.Holder;\n$imports$body"
                val all = calls.joinToString(" + \"|\" + ") { "(\"\" + ${it.text(null)})" }
                val a = bench.module(app("lib"), "go.jas" to appSource("public function go(): string { return $all; }\n"))
                val expected = calls.joinToString("|") { it.expected.toString() }
                assertEquals(expected, run(b, a, "app.main.go.go"), "round $round")
                for (call in calls) {
                    val bad = random.nextInt(call.arity)
                    val message = bench.rejected(app("lib"), "go.jas" to appSource("public function go(): any { return ${call.text(bad)}; }\n"))
                    assertTrue(message.isNotEmpty(), message)
                }
            }
        }
    }

    /** 坏字节不让读取器崩溃；运行时判定合法的文件，编译期读取得到相同的身份。 */
    @Test
    fun `the compile-time reader survives mutated files and agrees with the runtime reader`() = ModuleBench().use { bench ->
        val good = bench.module(lib(requires = listOf("net.client")), mathSource, counterSource)
        val path = bench.dir.resolve("x.jaso")
        val first = JasoModuleFile.parse(path, good)
        assertEquals("lib", first.name)
        assertEquals("lib.math", first.packageName)
        assertEquals(listOf("net.client"), first.capabilities)
        val random = java.util.Random(20261172L)
        var parsed = 0
        var valid = 0
        repeat(6000) {
            val bytes = good.copyOf()
            when (random.nextInt(4)) {
                // 集中改头部与目录：这是结构读取走的路径。
                0 -> repeat(1 + random.nextInt(4)) { bytes[random.nextInt(280)] = random.nextInt(256).toByte() }
                1 -> repeat(1 + random.nextInt(8)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                2 -> {
                    val at = random.nextInt(bytes.size - 4)
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(at, listOf(-1, 0, Int.MAX_VALUE, Int.MIN_VALUE, bytes.size)[random.nextInt(5)])
                }
                else -> Unit
            }
            val input = if (random.nextInt(8) == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes
            val view = try {
                JasoModuleFile.parse(path, input).also { parsed++ }
            } catch (e: JasoModuleFormatException) {
                null
            }
            // 结构读取不验摘要，改到无关字节的文件仍判 ok，这些文件的身份必须读得一样。
            if (JasoRuntime.structural(input) == "ok") {
                valid++
                assertTrue(view != null, "the runtime reader accepted a file the compiler refuses")
                assertEquals(first.name, view.name)
                assertEquals(first.packageName, view.packageName)
            }
        }
        assertTrue(parsed > 0 && valid > 0, "parsed $parsed valid $valid")
    }

    /** 类型语法：每种写法解析成预期的 AST；坏写法都是格式错误。 */
    @Test
    fun `signature text parses or is refused`() {
        fun parse(text: String) = JasoSignatureParser(text).wholeType()
        assertEquals(JasPrimitiveType("int32"), parse("int32"))
        assertEquals(JasNullableType(JasStringType), parse("string?"))
        assertEquals(JasArrayType(JasArrayType(JasNamedType("a.b.C"))), parse("Array<Array<a.b.C>>"))
        assertEquals(
            JasNullableType(JasNamedType("std.Future", listOf(JasNullableType(JasPrimitiveType("int64"))))),
            parse("std.Future<int64?>?")
        )
        for (bad in listOf("", "Foo", "Array", "Array<int32,int32>", "int32<int32>", "own a.B", "a.B<", "a.B>", "int32 x", "1x", "a..b", "Array<".repeat(12) + "int32" + ">".repeat(12))) {
            assertFailsWith<JasoModuleFormatException>(bad) { parse(bad) }
        }
    }
}
