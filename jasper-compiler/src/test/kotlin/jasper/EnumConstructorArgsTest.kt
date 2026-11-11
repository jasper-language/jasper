// ============================================================================
// EnumConstructorArgsTest —— Q230：带实参的枚举常量与枚举构造器参数。
//
// 覆盖范围：
//   - 正例：多种类型的实参（int32 / int64 / float64 / string / bool，含 int32 → int64 宽化）、
//     从外部读字段、方法使用字段、switch、values()/valueOf/ordinal 在带实参时仍正确、
//     字段声明初值先于构造器体执行、实参是表达式（含对已构造常量的静态访问）。
//   - 固定种子的随机用例：常量个数、实参、方法里的组合都随机生成，期望值由 Kotlin 独立算出。
//   - 负例：无构造器却带实参、个数不符、类型不符、同元数歧义、varargs 构造器、
//     实参里用 this、实参里的未解析名字——都必须是编译诊断，不能静默通过。
// 验证方式：真实编译管线 → URLClassLoader 加载 → 反射调用；构造器描述符用 javap 复核。
// 产物位置：模块 build/scratch/enum-ctor-args/ 下。
// ============================================================================
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
 * 做什么：Q230（`enum Color { RED(5), GREEN(7); ... }`）的端到端测试。
 * 为什么这样设计：缺陷横跨检查器（实参匹配）、翻译器（实参宽化）、后端（`<clinit>` 压栈与
 *   `<init>` 描述符）三层，只看编译成功发现不了描述符不一致导致的 NoSuchMethodError，
 *   因此每个正例都真加载、真执行。
 */
class EnumConstructorArgsTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "enum-ctor-args", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    private fun compile(name: String, source: String): Path {
        val dir = scratch(name)
        val result = JasperCompiler().compileToDirWithIr(source, dir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compile $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return dir
    }

    /** 编译并调用 `t.main` 的无参静态方法 [method]。 */
    private fun run(name: String, source: String, method: String = "main"): Any? {
        val dir = compile(name, source)
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            return loader.loadClass("t.main").getMethod(method).invoke(null)
        }
    }

    private fun compileError(name: String, source: String): String {
        val result = JasperCompiler().compileToDirWithIr(source, scratch(name))
        assertFalse(result is JasperCompiler.CompileResult.Success, "$name should be rejected")
        return (result as JasperCompiler.CompileResult.Error).message
    }

    private fun javap(dir: Path, className: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(javap, "-v", "-p", "-classpath", dir.toAbsolutePath().toString(), className)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "javap failed for $className:\n$output")
        Files.writeString(dir.resolve("javap-${className.replace('.', '_')}.txt"), output)
        return output
    }

    private val planet = """
        package t;
        public enum Planet {
            MERCURY(3, 4L, 0.5d, "hot", true),
            EARTH(10, 40000000000L, 1.0d, "home", false),
            MARS(7, 12, 0.25d, "red", true);

            public var moons: int32;
            public var radius: int64;
            public var gravity: float64;
            public var title: string;
            public var rocky: bool;

            public constructor(moons: int32, radius: int64, gravity: float64, title: string, rocky: bool) {
                this.moons = moons;
                this.radius = radius;
                this.gravity = gravity;
                this.title = title;
                this.rocky = rocky;
            }

            public function weight(mass: float64): float64 { return mass * this.gravity; }
            public function describe(): string { return this.title + ":" + this.moons; }
        }
    """.trimIndent()

    @Test
    fun `fields of every argument type are readable from outside`() {
        val src = planet + """

            public function moons(): int32 { return Planet.MARS.moons; }
            public function radius(): int64 { return Planet.EARTH.radius; }
            public function widened(): int64 { return Planet.MARS.radius; }
            public function gravity(): float64 { return Planet.MERCURY.gravity; }
            public function title(): string { return Planet.EARTH.title; }
            public function rocky(): bool { return Planet.MARS.rocky; }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val dir = compile("fields", src)
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(7, loader.loadClass("t.moons").getMethod("moons").invoke(null))
            assertEquals(40000000000L, loader.loadClass("t.radius").getMethod("radius").invoke(null))
            assertEquals(12L, loader.loadClass("t.widened").getMethod("widened").invoke(null))
            assertEquals(0.5, loader.loadClass("t.gravity").getMethod("gravity").invoke(null))
            assertEquals("home", loader.loadClass("t.title").getMethod("title").invoke(null))
            assertEquals(true, loader.loadClass("t.rocky").getMethod("rocky").invoke(null))
        }
        // 构造器描述符 = (name, ordinal) + 声明形参；<clinit> 必须按同一描述符调用。
        val code = javap(dir, "t.Planet")
        val desc = "(Ljava/lang/String;IIJDLjava/lang/String;Z)V"
        // 三个常量各一次 invokespecial，且都用带实参的描述符；不得退回 (String,int)。
        val calls = Regex("""invokespecial #\d+ +// Method "<init>":(\S+)""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(List(3) { desc }, calls, "clinit must invoke <init>$desc once per constant:\n$code")
    }

    @Test
    fun `methods use fields set by constructor arguments`() {
        assertEquals("red:7", run("describe", planet + """

            public function main(): string { return Planet.MARS.describe(); }
        """.trimIndent()))
        assertEquals(25.0, run("weight", planet + """

            public function main(): float64 { return Planet.MARS.weight(100.0d); }
        """.trimIndent()))
    }

    @Test
    fun `switch values valueOf and ordinal stay correct with arguments`() {
        val src = planet + """

            public function pick(p: Planet): int32 {
                var r: int32 = 0;
                switch (p) {
                    case Planet.MERCURY => { r = 100; }
                    case Planet.EARTH => { r = 200; }
                    default => { r = 300; }
                }
                return r + p.moons;
            }
            public function main(): int32 {
                return Planet.values().length * 10000 + pick(Planet.valueOf("EARTH")) + pick(Planet.MARS) * 1000;
            }
            public function second(): string { return Planet.values()[1].title; }
        """.trimIndent()
        val dir = compile("switch", src)
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(3 * 10000 + 210 + 307 * 1000, loader.loadClass("t.main").getMethod("main").invoke(null))
            assertEquals("home", loader.loadClass("t.second").getMethod("second").invoke(null))
            val planetClass = loader.loadClass("t.Planet")
            assertTrue(planetClass.isEnum)
            val constants = planetClass.enumConstants.map { it as Enum<*> }
            assertEquals(listOf("MERCURY", "EARTH", "MARS"), constants.map { it.name })
            assertEquals(listOf(0, 1, 2), constants.map { it.ordinal })
            assertEquals(listOf(3, 10, 7), constants.map { planetClass.getField("moons").get(it) })
            val values = planetClass.getMethod("values").invoke(null) as Array<*>
            assertEquals(constants, values.toList())
        }
    }

    @Test
    fun `field initializers run before the constructor body and arguments are expressions`() {
        // SMALL：size = 2 * 3 = 6，tag = 1；BIG：size = (6 * 2) * 3 = 36，tag = 2。
        // 36 / 4 * 3 + 36 - 6 + 5 + 1 = 63；factor 若没先于构造器体初始化，size 会是 0。
        assertEquals(63, run("init-order", """
            package t;
            public enum Scale {
                SMALL(1 + 1, "a"),
                BIG(Scale.SMALL.size * 2, "bb");

                public var factor: int32 = 3;
                public var size: int32;
                public var tag: int32;

                public constructor(base: int32, name: string) {
                    this.size = base * this.factor;
                    this.tag = name.length();
                }
            }
            public function main(): int32 {
                return Scale.BIG.size / 4 * 3 + Scale.BIG.size - Scale.SMALL.size + 5 + Scale.SMALL.tag;
            }
        """.trimIndent()))
    }

    @Test
    fun `several constructors are chosen by argument count`() {
        assertEquals(1 + 20 + 300, run("overload", """
            package t;
            public enum Mix {
                ONE(1),
                TWO(2, 10),
                ZERO;

                public var v: int32 = 0;
                public constructor() { this.v = 300; }
                public constructor(a: int32) { this.v = a; }
                public constructor(a: int32, b: int32) { this.v = a * b; }
            }
            public function main(): int32 { return Mix.ONE.v + Mix.TWO.v + Mix.ZERO.v; }
        """.trimIndent()))
    }

    /**
     * 固定种子随机用例：每轮随机生成 3–9 个常量，每个带 (int32, int64, string) 实参，
     * 枚举方法按随机系数组合字段；Kotlin 独立按同一公式计算每个常量的值与总和。
     */
    @Test
    fun `random enums with arguments match an independent Kotlin model`() {
        val rnd = Random(230_2026_11_10L)
        repeat(6) { round ->
            val count = rnd.nextInt(3, 10)
            data class C(val name: String, val a: Int, val b: Long, val s: String)
            val consts = (0 until count).map { i ->
                C(
                    "K${i}_${rnd.nextInt(1000)}",
                    rnd.nextInt(-5000, 5000),
                    rnd.nextLong(-1_000_000_000_000L, 1_000_000_000_000L),
                    (1..rnd.nextInt(0, 6)).map { 'a' + rnd.nextInt(26) }.joinToString("")
                )
            }
            val ka = rnd.nextInt(1, 9)
            val kb = rnd.nextInt(1, 9)
            fun model(c: C, ordinal: Int): Long = c.a.toLong() * ka + c.b * kb + c.s.length * 1000L + ordinal
            val decls = consts.joinToString(",\n    ") { c ->
                val a = if (c.a < 0) "-${-c.a}" else "${c.a}"
                val b = if (c.b < 0) "-${-c.b}L" else "${c.b}L"
                "${c.name}($a, $b, \"${c.s}\")"
            }
            val pick = rnd.nextInt(count)
            val src = """
                package t;
                public enum R {
                    $decls;

                    public var a: int32;
                    public var b: int64;
                    public var s: string;
                    public constructor(a: int32, b: int64, s: string) { this.a = a; this.b = b; this.s = s; }
                    public function score(ord: int32): int64 {
                        var r: int64 = this.a;
                        return r * $ka + this.b * $kb + this.s.length() * 1000 + ord;
                    }
                }
                public function picked(): int64 { return R.${consts[pick].name}.score($pick); }
                public function byName(): int64 { return R.valueOf("${consts[pick].name}").score($pick); }
                public function main(): int32 { return 0; }
            """.trimIndent()
            val dir = compile("random-$round", src)
            URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
                val expected = model(consts[pick], pick)
                assertEquals(expected, loader.loadClass("t.picked").getMethod("picked").invoke(null), "round $round picked")
                assertEquals(expected, loader.loadClass("t.byName").getMethod("byName").invoke(null), "round $round valueOf")
                val enumClass = loader.loadClass("t.R")
                val actual = enumClass.enumConstants.map { it as Enum<*> }
                assertEquals(consts.map { it.name }, actual.map { it.name }, "round $round names")
                assertEquals(consts.indices.toList(), actual.map { it.ordinal }, "round $round ordinals")
                val score = enumClass.getMethod("score", Int::class.javaPrimitiveType)
                for ((i, c) in consts.withIndex()) {
                    assertEquals(c.a, enumClass.getField("a").get(actual[i]), "round $round ${c.name}.a")
                    assertEquals(c.b, enumClass.getField("b").get(actual[i]), "round $round ${c.name}.b")
                    assertEquals(c.s, enumClass.getField("s").get(actual[i]), "round $round ${c.name}.s")
                    assertEquals(model(c, i), score.invoke(actual[i], i), "round $round ${c.name}.score")
                }
            }
        }
    }

    @Test
    fun `arguments without any constructor are rejected`() {
        val msg = compileError("no-ctor", """
            package t;
            public enum E { A(1), B; }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(msg.contains("Enum constant 'A' passes 1 argument(s), but enum 'E' declares no constructor"), msg)
    }

    @Test
    fun `wrong argument count is rejected`() {
        val msg = compileError("arity", """
            package t;
            public enum E {
                A(1, 2), B(3);
                public var v: int32;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(msg.contains("No constructor of enum 'E' takes 2 argument(s) for constant 'A' (declared: 1)"), msg)
        // 有显式构造器后不再有隐式零参构造器：不带实参的常量同样要报。
        val missing = compileError("arity-zero", """
            package t;
            public enum E {
                A(1), B;
                public var v: int32;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(missing.contains("No constructor of enum 'E' takes 0 argument(s) for constant 'B'"), missing)
    }

    @Test
    fun `argument type mismatch is rejected`() {
        val msg = compileError("type", """
            package t;
            public enum E {
                A("x");
                public var v: int32;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(msg.contains("Type mismatch: enum constant 'A' argument 1 cannot pass string to parameter 'v' of type int32"), msg)
        // 窄化同样拒绝：int64 不能隐式传给 int32。
        val narrowing = compileError("narrow", """
            package t;
            public enum E {
                A(5L);
                public var v: int32;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(narrowing.contains("enum constant 'A' argument 1 cannot pass int64"), narrowing)
    }

    @Test
    fun `ambiguous and varargs constructors are rejected`() {
        val ambiguous = compileError("ambiguous", """
            package t;
            public enum E {
                A(1);
                public var v: int32 = 0;
                public constructor(v: int32) { this.v = v; }
                public constructor(s: string) { this.v = 2; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(ambiguous.contains("Enum constant 'A' is ambiguous: enum 'E' declares 2 constructors taking 1 argument(s)"), ambiguous)
        val varargs = compileError("varargs", """
            package t;
            public enum E {
                A(1);
                public var v: int32 = 0;
                public constructor(xs: int32...) { this.v = 1; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(varargs.contains("Enum constant 'A' cannot call a varargs constructor of enum 'E'"), varargs)
    }

    @Test
    fun `arguments are checked in a static context`() {
        val usesThis = compileError("this", """
            package t;
            public enum E {
                A(this.v);
                public var v: int32 = 0;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(usesThis.contains("'this' cannot be used in the arguments of enum constant 'A'"), usesThis)
        val unresolved = compileError("unresolved", """
            package t;
            public enum E {
                A(missing);
                public var v: int32 = 0;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(unresolved.contains("missing"), unresolved)
        // 没有构造器时，多余实参里的错误同样会被检查到，不因先报个数而漏掉。
        val noCtor = compileError("no-ctor-unresolved", """
            package t;
            public enum E { A(missing2); }
            public function main(): int32 { return 0; }
        """.trimIndent())
        assertTrue(noCtor.contains("missing2"), noCtor)
    }
}
