// ============================================================================
// JasoRuntimeTest.kt —— Managed 模块 L1 第 7–9 步与 stop / unload（JB-L3 第二片）
// ============================================================================
// 职责：用编译器真实产出的模块驱动 Jasper 写的 std.jaso.JasoRuntime：全有或全无、生命周期顺序、
//   依赖可见性、拒绝路径；固定种子的随机操作序列对照 Kotlin 模型。
// ============================================================================

package jasper

import com.code_intelligence.jazzer.junit.FuzzTest
import jasper.compiler.JasoManifestText
import jasper.compiler.JasoModuleOptions
import jasper.compiler.JasoWriter
import jasper.compiler.JasperCompiler
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 经反射驱动 std.jaso；把反射包装解开成「码名」。 */
internal class JasoHost(private val cl: ClassLoader = JasoRuntime.loader) {
    private fun cls(name: String) = cl.loadClass("std.jaso.$name")

    fun strings(vararg items: String): Any {
        val s = cls("JasoStrings").getConstructor().newInstance()
        val append = cls("JasoStrings").getMethod("append", String::class.java)
        items.forEach { append.invoke(s, it) }
        return s
    }

    private fun devPolicy(): Any {
        val publisher = cls("JasoPublisher")
        val empty = java.lang.reflect.Array.newInstance(publisher, 0)
        return cls("JasoTrustPolicy").getConstructor(empty.javaClass, Boolean::class.javaPrimitiveType).newInstance(empty, false)
    }

    fun enforcePolicy(): Any {
        val publisher = cls("JasoPublisher")
        val empty = java.lang.reflect.Array.newInstance(publisher, 0)
        return cls("JasoTrustPolicy").getConstructor(empty.javaClass, Boolean::class.javaPrimitiveType).newInstance(empty, true)
    }

    /** enforce 模式，信任向量旁的开发用公钥。 */
    fun vectorPolicy(): Any {
        val publisher = cls("JasoPublisher")
        val trusted = java.lang.reflect.Array.newInstance(publisher, 1)
        java.lang.reflect.Array.set(trusted, 0, publisher.getConstructors().single().newInstance("jasper.dev", "jaso-", JasoRuntime.devKey))
        return cls("JasoTrustPolicy").getConstructor(trusted.javaClass, Boolean::class.javaPrimitiveType).newInstance(trusted, true)
    }

    private fun version(a: Long, b: Long, c: Long): Any =
        cls("JasoVersion").getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType).newInstance(a, b, c)

    fun runtime(policy: Any = devPolicy()): Any =
        cls("JasoRuntime").getConstructors().single().newInstance(policy, version(0, 1, 0))

    /** 成功返回模块对象；失败抛 [Rejected]。 */
    fun load(runtime: Any, bytes: ByteArray, vararg granted: String): Any =
        unwrap { cls("JasoRuntime").getMethod("load", ByteArray::class.java, cls("JasoStrings")).invoke(runtime, bytes, strings(*granted)) }!!

    fun stop(runtime: Any, module: Any) {
        unwrap { cls("JasoRuntime").getMethod("stop", cls("JasoModule")).invoke(runtime, module) }
    }

    fun unload(runtime: Any, module: Any): String =
        unwrap { cls("JasoRuntime").getMethod("unload", cls("JasoModule")).invoke(runtime, module) } as String

    fun find(runtime: Any, name: String): Any? = cls("JasoRuntime").getMethod("find", String::class.java).invoke(runtime, name)

    fun call(module: Any, symbol: String, vararg args: Any?): Any? =
        unwrap { cls("JasoModule").getMethod("call", String::class.java, Array<Any>::class.java).invoke(module, symbol, arrayOf(*args)) }

    fun phase(module: Any): String = cls("JasoModule").getMethod("phase").invoke(module) as String

    fun symbols(module: Any): List<String> = list(cls("JasoModule").getMethod("symbols").invoke(module))

    private fun list(strings: Any): List<String> {
        val size = cls("JasoStrings").getMethod("size").invoke(strings) as Int
        val get = cls("JasoStrings").getMethod("get", Int::class.javaPrimitiveType)
        return (0 until size).map { get.invoke(strings, it) as String }
    }

    fun audit(runtime: Any): List<String> {
        val audit = cls("JasoRuntime").getMethod("audit").invoke(runtime)
        return list(cls("JasoAudit").getMethod("snapshot").invoke(audit))
    }

    fun tableSize(runtime: Any): Int {
        val table = cls("JasoRuntime").getMethod("table").invoke(runtime)
        return cls("JasoModuleTable").getMethod("size").invoke(table) as Int
    }

    fun dependenciesHold(runtime: Any): Boolean {
        val table = cls("JasoRuntime").getMethod("table").invoke(runtime)
        return cls("JasoModuleTable").getMethod("dependenciesHold").invoke(table) as Boolean
    }

    private fun unwrap(body: () -> Any?): Any? {
        try {
            return body()
        } catch (e: InvocationTargetException) {
            val t = e.targetException
            val name = t.javaClass.name
            if (name == "std.jaso.JasoError" || name == "std.jaso.JasoRuntimeError") {
                throw Rejected(t.javaClass.getMethod("code").invoke(t) as String, t.message ?: "")
            }
            throw t
        }
    }
}

/** 一次结构化拒绝。 */
internal class Rejected(val code: String, message: String) : RuntimeException("$code ($message)")

/** 把一组源码编译成未签名模块。 */
internal fun compileModule(options: JasoModuleOptions, vararg sources: Pair<String, String>): ByteArray {
    val dir = Files.createTempDirectory("jaso-runtime-test")
    try {
        val out = dir.resolve("m.jaso")
        val r = JasperCompiler().compileToJaso(mapOf(*sources), options, out)
        check(r is JasperCompiler.CompileResult.Success) { "compile failed: $r" }
        return Files.readAllBytes(out)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/** 按规范写只存储的 ZIP；compress = true 时写压缩项（负向用例）。 */
internal fun zipOf(entries: List<Pair<String, ByteArray>>, compress: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((name, data) in entries) {
            val e = ZipEntry(name)
            if (!compress) {
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = CRC32().also { it.update(data) }.value
            }
            z.putNextEntry(e)
            z.write(data)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

/** 把代码段载荷换成任意字节（长度不超过原载荷，其余补零），再重算摘要。 */
internal fun withCodePayload(bytes: ByteArray, payload: ByteArray): ByteArray {
    val out = bytes.copyOf()
    val b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val dir = b.getInt(24)
    for (i in 0 until b.getInt(20)) {
        val e = dir + 32 * i
        if (b.getInt(e) == 9) {
            val at = b.getInt(e + 8) + 8
            val size = b.getInt(e + 12) - 8
            check(payload.size <= size) { "载荷超长：${payload.size} > $size" }
            java.util.Arrays.fill(out, at, at + size, 0)
            payload.copyInto(out, at)
        }
    }
    resealJaso(out)
    return out
}

private fun rejected(code: String, body: () -> Unit) {
    try {
        body()
    } catch (r: Rejected) {
        assertEquals(code, r.code, r.message)
        return
    }
    throw AssertionError("expected rejection $code")
}

/** 记录钩子轨迹：模块经系统属性写入，因为 stop 之后无法再调用模块。 */
private object Trace {
    private const val KEY = "jaso.runtime.test.trace"
    fun clear() = System.clearProperty(KEY)
    fun get(): String = System.getProperty(KEY) ?: ""
    fun src(mark: String) =
        "java.lang.System.setProperty(\"$KEY\", java.lang.System.getProperty(\"$KEY\", \"\") + \"$mark;\");"
}

class JasoRuntimeTest {
    private val host = JasoHost()

    private fun opts(name: String, vararg depends: String, requires: List<String> = emptyList(), lifecycle: String? = null, version: String = "1.0.0") =
        JasoModuleOptions(name, version, depends = depends.toList(), requires = requires, lifecycle = lifecycle)

    /** 带三个钩子的模块，返回值由参数决定。 */
    private fun hooked(name: String, pkg: String, init: Int = 0, start: Int = 0, stop: Int = 0, vararg depends: String): ByteArray {
        fun hook(fn: String, ret: Int) = "$fn.jas" to """
            package $pkg;
            public function $fn(): int32 {
                ${Trace.src("$name.$fn")}
                return $ret;
            }
        """.trimIndent()
        return compileModule(
            opts(name, *depends),
            hook("jaso_init", init), hook("jaso_start", start), hook("jaso_stop", stop),
            "value.jas" to "package $pkg;\npublic function value(): int32 { return 7; }\n"
        )
    }

    private val demo: ByteArray by lazy {
        compileModule(
            opts("demo-mod"),
            "Api.jas" to """
                package demo.mod;
                public function add(a: int32, b: int32): int32 { return a + b; }
                public function greet(n: string?): string { return "hi " + (n ?? "none"); }
                public function total(xs: int32[]): int64 {
                    var t: int64 = 0L;
                    var i: int32 = 0;
                    while (i < xs.length) { t = t + xs[i]; i = i + 1; }
                    return t;
                }
                public function boom(): int32 { throw new std.RecoverableError("boom"); }
                public function maybe(x: int32?): int32? { return x; }
                public class Square {
                    private var side: int32;
                    public constructor(side: int32) { this.side = side; }
                    public function area(): int32 { return this.side * this.side; }
                    public static function unit(): Square { return new Square(1); }
                }
                class Hidden {
                    public static function secret(): int32 { return 1; }
                }
            """.trimIndent()
        )
    }

    @Test
    fun `a module runs through declared symbols only`() {
        val rt = host.runtime()
        val m = host.load(rt, demo)
        assertEquals("running", host.phase(m))
        assertEquals(5, host.call(m, "demo.mod.add.add", 2, 3))
        assertEquals("hi x", host.call(m, "demo.mod.greet.greet", "x"))
        assertEquals("hi none", host.call(m, "demo.mod.greet.greet", null))
        assertEquals(6L, host.call(m, "demo.mod.total.total", intArrayOf(1, 2, 3)))
        assertEquals(4, host.call(m, "demo.mod.maybe.maybe", 4))
        assertNull(host.call(m, "demo.mod.maybe.maybe", null))
        val square = host.call(m, "demo.mod.Square.new", 3)
        assertEquals(9, host.call(m, "demo.mod.Square.area", square))
        assertEquals(1, host.call(m, "demo.mod.Square.area", host.call(m, "demo.mod.Square.unit")))
        assertTrue("demo.mod.add.add" in host.symbols(m))
        assertFalse(host.symbols(m).any { "Hidden" in it })
        rejected("module.symbol") { host.call(m, "demo.mod.Hidden.secret") }
        rejected("module.argument") { host.call(m, "demo.mod.add.add", "x", 1) }
        rejected("module.argument") { host.call(m, "demo.mod.Square.area") }
        assertSame(m, host.find(rt, "demo-mod"))
    }

    private fun assertSame(a: Any?, b: Any?) = assertTrue(a === b)

    @Test
    fun `exceptions from module code reach the caller unchanged`() {
        val m = host.load(host.runtime(), demo)
        val thrown = runCatching { host.call(m, "demo.mod.boom.boom") }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals("std.RecoverableError", thrown.javaClass.name)
        assertEquals("boom", thrown.message)
    }

    // ---- 生命周期 ----

    @Test
    fun `init runs before start and stop runs once`() {
        Trace.clear()
        val rt = host.runtime()
        val m = host.load(rt, hooked("life-a", "life.a"))
        assertEquals("life-a.jaso_init;life-a.jaso_start;", Trace.get())
        assertEquals(7, host.call(m, "life.a.value.value"))
        assertFalse(host.symbols(m).any { it.contains("jaso_") }, "生命周期导出不是服务")
        host.stop(rt, m)
        assertEquals("life-a.jaso_init;life-a.jaso_start;life-a.jaso_stop;", Trace.get())
        assertEquals("stopped", host.phase(m))
        rejected("module.stopped") { host.call(m, "life.a.value.value") }
        rejected("stop.already") { host.stop(rt, m) }
        assertEquals("life-a.jaso_init;life-a.jaso_start;life-a.jaso_stop;", Trace.get(), "钩子只运行一次")
        rejected("module.duplicate") { host.load(rt, hooked("life-a", "life.a")) }
    }

    /** 样本交给参考实现核对（verification/jaso-diff check-emit）：导出表里的生命周期裸名，没有对应 fn 行。 */
    @Test
    fun `lifecycle exports are written for the reference implementation`() {
        val image = compileModule(
            JasoModuleOptions("jaso-life", "1.0.0", publisher = "jasper.dev"),
            "jaso_init.jas" to "package jaso.life;\npublic function jaso_init(): int32 { return 0; }\n",
            "jaso_stop.jas" to "package jaso.life;\npublic function jaso_stop(): int32 { return 0; }\n",
            "f.jas" to "package jaso.life;\npublic function f(): int32 { return 1; }\n"
        )
        val sections = JasoSections(image)
        assertEquals(listOf("jaso.life.f.f", "jaso_init", "jaso_stop"), sections.exportNames.sorted())
        assertFalse("jaso_init" in sections.importText)
        val dir = java.io.File("build/tmp/jaso-emit-samples").apply { mkdirs() }
        java.io.File(dir, "lifecycle.jaso").writeBytes(image)
        java.io.File(dir, "lifecycle.expect").writeText("ok\n")
    }

    @Test
    fun `failed init or start leaves no module and frees the name`() {
        Trace.clear()
        val rt = host.runtime()
        rejected("lifecycle.init") { host.load(rt, hooked("bad-a", "bad.a", init = 3)) }
        assertEquals("bad-a.jaso_init;", Trace.get(), "init 失败后不运行 start 与 stop")
        assertEquals(0, host.tableSize(rt))
        assertNull(host.find(rt, "bad-a"))
        Trace.clear()
        rejected("lifecycle.start") { host.load(rt, hooked("bad-a", "bad.a", start = 4)) }
        assertEquals("bad-a.jaso_init;bad-a.jaso_start;", Trace.get(), "start 失败后不运行 stop")
        assertEquals(0, host.tableSize(rt))
        val ok = host.load(rt, hooked("bad-a", "bad.a"))
        assertEquals("running", host.phase(ok), "失败的装载不占用名字")
    }

    @Test
    fun `a throwing hook fails the load and a failing stop hook still stops`() {
        val rt = host.runtime()
        val throwing = compileModule(
            opts("throw-a"),
            "jaso_init.jas" to "package throw_a;\npublic function jaso_init(): int32 { throw new std.RecoverableError(\"no\"); }\n"
        )
        rejected("lifecycle.init") { host.load(rt, throwing) }
        assertEquals(0, host.tableSize(rt))
        val m = host.load(rt, hooked("stop-a", "stop.a", stop = 9))
        rejected("stop.hook") { host.stop(rt, m) }
        assertEquals("stopped", host.phase(m))
    }

    @Test
    fun `no module code runs before init`() {
        Trace.clear()
        val rt = host.runtime()
        val base = JasoSections(compileModule(
            opts("clinit-a"),
            "Eager.jas" to """
                package clinit.a;
                public class Eager {
                    private static var mark: string = Eager.note();
                    private static function note(): string {
                        var old: string? = java.lang.System.setProperty("jaso.runtime.test.trace", "clinit;");
                        return old ?? "x";
                    }
                    public static function touch(): int32 { return 1; }
                }
            """.trimIndent()
        ))
        val missing = "fn clinit.a.Eager.absent(): int32\n"
        val bad = JasoWriter.assemble(
            JasoManifestText.render(opts("clinit-a"), base.exportNames + "clinit.a.Eager.absent"),
            base.classes, base.exportNames + "clinit.a.Eager.absent", base.importText + missing
        )
        rejected("link.symbol") { host.load(rt, bad) }
        assertEquals("", Trace.get(), "链接失败不初始化任何 class")
        val m = host.load(rt, JasoWriter.assemble(JasoManifestText.render(opts("clinit-a"), base.exportNames), base.classes, base.exportNames, base.importText))
        assertEquals("", Trace.get(), "没有被调用的 class 不初始化")
        assertEquals(1, host.call(m, "clinit.a.Eager.touch"))
        assertEquals("clinit;", Trace.get(), "夹具的静态初始化确实会写痕迹")
    }

    // ---- 依赖与可见性 ----

    private val libA: ByteArray by lazy {
        compileModule(
            opts("lib-a", version = "1.2.0"),
            "one.jas" to "package lib.a;\npublic function one(): int32 { return Inner.value(); }\nclass Inner { public static function value(): int32 { return 41; } }\n",
            "Shape.jas" to "package lib.a;\npublic class Shape { public constructor() { } }\n"
        )
    }

    private fun appB(vararg depends: String, name: String = "app-b"): ByteArray = compileModule(
        opts(name, *depends),
        "Probe.jas" to """
            package app.b;
            public class Probe {
                public constructor() { }
                public function see(name: string): bool {
                    var plain: java.lang.Object = cast this as java.lang.Object;
                    var loader: java.lang.ClassLoader = plain.getClass().getClassLoader();
                    try {
                        loader.loadClass(name);
                        return true;
                    } catch (error: java.lang.ClassNotFoundException) {
                        return false;
                    }
                }
                public function viaLib(): int32 {
                    var plain: java.lang.Object = cast this as java.lang.Object;
                    var loader: java.lang.ClassLoader = plain.getClass().getClassLoader();
                    var value: java.lang.Integer = cast (loader.loadClass("lib.a.one").getMethod("one", new java.lang.Class[0]).invoke(null, new any[0])) as java.lang.Integer;
                    return value.intValue();
                }
            }
        """.trimIndent()
    )

    @Test
    fun `a module sees only its declared dependencies and their public classes`() {
        val rt = host.runtime()
        val lib = host.load(rt, libA)
        val unrelated = host.load(rt, compileModule(opts("other-c"), "z.jas" to "package other.c;\npublic function z(): int32 { return 0; }\n"))
        val app = host.load(rt, appB("lib-a@>=1.0,<2.0"))
        val probe = host.call(app, "app.b.Probe.new")
        fun see(name: String) = host.call(app, "app.b.Probe.see", probe, name)
        assertEquals(41, host.call(app, "app.b.Probe.viaLib", probe), "跨模块调用由载体链接")
        assertEquals(true, see("lib.a.one"))
        assertEquals(true, see("lib.a.Shape"))
        assertEquals(false, see("lib.a.Inner"), "依赖模块的内部 class 不可见")
        assertEquals(false, see("other.c.z"), "未声明的模块不可见")
        assertEquals(true, see("java.lang.String"))
        assertEquals(true, see("std.RecoverableError"))
        assertEquals(false, see("jasper.compiler.JasperCompiler"), "宿主 class 不可见")
        assertEquals(false, see("kotlin.Unit"))
        assertEquals("running", host.phase(lib))
        assertEquals("running", host.phase(unrelated))
    }

    @Test
    fun `dependencies are checked against running modules and stop respects dependents`() {
        val rt = host.runtime()
        rejected("dependency.unsatisfied") { host.load(rt, appB("lib-a")) }
        val lib = host.load(rt, libA)
        rejected("dependency.unsatisfied") { host.load(rt, appB("lib-a@>=2.0")) }
        val app = host.load(rt, appB("lib-a@>=1.0,<2.0"))
        assertTrue(host.dependenciesHold(rt))
        rejected("stop.dependent") { host.stop(rt, lib) }
        assertEquals("running", host.phase(lib), "被拒绝的 stop 不改变状态")
        host.stop(rt, app)
        host.stop(rt, lib)
        assertTrue(host.dependenciesHold(rt))
        rejected("dependency.unsatisfied") { host.load(rt, appB("lib-a", name = "app-c")) }
    }

    @Test
    fun `unload is deferred or refused and changes nothing`() {
        val rt = host.runtime()
        val plugin = host.load(rt, compileModule(opts("plug-a", lifecycle = "plugin"), "f.jas" to "package plug.a;\npublic function f(): int32 { return 1; }\n"))
        val system = host.load(rt, compileModule(opts("sys-a", lifecycle = "system"), "g.jas" to "package sys.a;\npublic function g(): int32 { return 2; }\n"))
        assertEquals("deferred", host.unload(rt, plugin))
        assertEquals("refused", host.unload(rt, system))
        assertEquals("running", host.phase(plugin))
        assertEquals(1, host.call(plugin, "plug.a.f.f"))
        assertEquals(2, host.tableSize(rt))
        host.stop(rt, plugin)
        assertEquals("deferred", host.unload(rt, plugin), "停止之后同样只推迟")
    }

    @Test
    fun `earlier steps keep their codes and capabilities come from the caller`() {
        val needs = compileModule(opts("cap-a", requires = listOf("net.tcp")), "f.jas" to "package cap.a;\npublic function f(): int32 { return 1; }\n")
        val rt = host.runtime()
        rejected("capability.denied") { host.load(rt, needs) }
        assertEquals("running", host.phase(host.load(rt, needs, "net.tcp")))
        rejected("trust.unsigned") { host.load(host.runtime(host.enforcePolicy()), demo) }
        rejected("format.truncated") { host.load(rt, ByteArray(4)) }
    }

    @Test
    fun `admitting an unsigned file and every rejection leave an audit record`() {
        val rt = host.runtime()
        host.load(rt, demo)
        rejected("format.truncated") { host.load(rt, ByteArray(4)) }
        val lines = host.audit(rt)
        assertEquals(listOf("unsigned admitted demo-mod", "loaded demo-mod", "rejected format.truncated"), lines)
    }

    // ---- 链接的拒绝路径 ----

    private val fixture: JasoSections by lazy { JasoSections(compileModule(opts("fix-a"), "f.jas" to "package fix.a;\npublic function f(x: int32): int32 { return x; }\n")) }

    private fun assembled(
        classes: Map<String, ByteArray> = fixture.classes,
        exports: List<String> = fixture.exportNames,
        importText: String = fixture.importText
    ): ByteArray = JasoWriter.assemble(JasoManifestText.render(opts("fix-a"), exports), classes, exports, importText)

    private fun link(code: String, bytes: ByteArray) = rejected(code) { host.load(host.runtime(), bytes) }

    private fun linkFor(detail: String, bytes: ByteArray) {
        val failure = runCatching { host.load(host.runtime(), bytes) }.exceptionOrNull() as? Rejected
        assertEquals("link.payload", failure?.code)
        assertTrue(detail in failure?.message.orEmpty(), failure?.message)
    }

    @Test
    fun `the fixture itself loads`() {
        assertEquals(5, host.call(host.load(host.runtime(), assembled()), "fix.a.f.f", 5))
    }

    @Test
    fun `payload rules`() {
        val good = fixture.classes.values.first()
        // 坏 class 名字也会被 JVM 拒绝，所以这几条核对拒绝原因，确认是约定本身在起作用。
        linkFor("outside package", assembled(fixture.classes + ("fix/a/readme.txt" to good)))
        linkFor("outside package", assembled(fixture.classes + ("evil/Other.class" to good)))
        linkFor("outside package", assembled(fixture.classes + ("fix/a/deep/Other.class" to good)))
        link("link.payload", assembled(fixture.classes + ("fix/a/Junk.class" to ByteArray(32))))
        link("link.payload", assembled(fixture.classes + ("fix/a/Short.class" to byteArrayOf(-54, -2))))
        val future = good.copyOf().also { it[6] = 0; it[7] = 53 }
        linkFor("class version 53", assembled(fixture.classes + ("fix/a/Future.class" to future)))
        val truncated = good.copyOf(good.size / 2)
        link("link.payload", assembled(fixture.classes + ("fix/a/Cut.class" to truncated)))
        link("link.payload", assembled(fixture.classes + ("fix/a/Renamed.class" to good)))
    }

    @Test
    fun `compressed truncated and duplicate archives are refused`() {
        val padded = assembled(fixture.classes + ("fix/a/Pad.class" to ByteArray(4096)))
        val path = "fix/a/f.class"
        val data = fixture.classes.getValue(path)
        link("link.payload", withCodePayload(padded, zipOf(listOf(path to data), compress = true)))
        link("link.payload", withCodePayload(padded, zipOf(listOf(path to data)).copyOf(data.size / 2)))
        link("link.payload", withCodePayload(padded, ByteArray(16) { 7 }))
        link("link.payload", withCodePayload(padded, zipOf(emptyList())))
        val twice = ByteArrayOutputStream().also { out ->
            // ZipOutputStream 拒绝重名，这里把同一个单项 ZIP 的本地头写两遍。
            val one = zipOf(listOf(path to data))
            val local = 30 + path.length + data.size
            out.write(one, 0, local)
            out.write(one, 0, local)
        }.toByteArray()
        link("link.payload", withCodePayload(padded, twice))
    }

    @Test
    fun `declarations must match the classes`() {
        fun declare(line: String, symbol: String) = assembled(
            exports = fixture.exportNames + symbol,
            importText = fixture.importText + line + "\n"
        )
        link("link.symbol", declare("fn java.lang.System.exit(a: int32): void", "java.lang.System.exit"))
        link("link.symbol", declare("fn fix.a.f.absent(): int32", "fix.a.f.absent"))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: int32): int64")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: int64): int32")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(this: fix.a.f, x: int32): int32")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: own int32): int32")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: nosuch): int32")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: other.Missing): int32")))
        link("link.symbol", assembled(importText = fixture.importText.replace("fn fix.a.f.f(x: int32): int32", "fn fix.a.f.f(x: Array<int32, int32>): int32")))
        link("link.symbol", declare("fn fix.a.f.hashCode(this: fix.a.f): int32", "fix.a.f.hashCode"))
        val square = JasoSections(demo)
        val wrongArray = square.importText.replace("xs: Array<int32>", "xs: Array<int32, int32>")
        assertTrue(wrongArray != square.importText)
        link("link.symbol", JasoWriter.assemble(JasoManifestText.render(opts("demo-mod"), square.exportNames), square.classes, square.exportNames, wrongArray))
        val wrongNew = square.importText.replace("fn demo.mod.Square.new(side: int32): demo.mod.Square", "fn demo.mod.Square.new(side: int32): string")
        assertTrue(wrongNew != square.importText)
        link("link.symbol", JasoWriter.assemble(JasoManifestText.render(opts("demo-mod"), square.exportNames), square.classes, square.exportNames, wrongNew))
        link("link.symbol", assembled(exports = fixture.exportNames + "jaso_init"))
    }

    @Test
    fun `lifecycle exports must be static and return int32`() {
        fun hookClass(member: String): ByteArray {
            val image = JasoSections(compileModule(
                opts("hook-z"),
                "jaso_init.jas" to "package hook.z;\npublic class jaso_init {\n    public constructor() { }\n    $member\n}\n"
            ))
            val exports = image.exportNames + "jaso_init"
            return JasoWriter.assemble(JasoManifestText.render(opts("hook-z"), exports), image.classes, exports, image.importText)
        }
        rejected("link.symbol") { host.load(host.runtime(), hookClass("public function jaso_init(): int32 { return 0; }")) }
        rejected("link.symbol") { host.load(host.runtime(), hookClass("public static function jaso_init(): int64 { return 0L; }")) }
    }

    @Test
    fun `a module with more classes than the initial table links`() {
        val sources = (0 until 80).map { "f$it.jas" to "package many.a;\npublic function f$it(): int32 { return $it; }\n" }
        val m = host.load(host.runtime(), compileModule(opts("many-a"), *sources.toTypedArray()))
        for (i in listOf(0, 31, 32, 79)) assertEquals(i, host.call(m, "many.a.f$i.f$i"))
    }

    @Test
    fun `signed vectors pass trust and then meet the limits of this runtime`() {
        fun vector(name: String) = java.io.File(JasoRuntime.vectorDir, "$name.jaso").readBytes()
        val rt = host.runtime(host.vectorPolicy())
        rejected("link.binding") { host.load(rt, vector("ok-managed")) }
        rejected("platform.mismatch") { host.load(rt, vector("ok-native")) }
        rejected("platform.mismatch") { host.load(rt, vector("ok-hybrid-binding")) }
        assertEquals(0, host.tableSize(rt))
    }

    @Test
    fun `binding and package are required`() {
        link("link.binding", assembled(importText = fixture.importText.replace("package fix.a\n", "")))
        val text = fixture.importText.replace("package fix.a\n", "package std.fake\n")
        link("link.binding", assembled(importText = text))
        link("link.binding", assembled(importText = fixture.importText.replace("package fix.a\n", "package java.fake\n")))
    }

    @Test
    fun `lifecycle functions are diagnosed at compile time`() {
        fun errors(source: String): String {
            val dir = Files.createTempDirectory("jaso-life")
            try {
                val r = JasperCompiler().compileToJaso(mapOf("x.jas" to source), opts("life-z"), dir.resolve("x.jaso"))
                return (r as? JasperCompiler.CompileResult.Error)?.message ?: "ok"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        assertEquals("ok", errors("package life.z;\npublic function jaso_init(): int32 { return 0; }\npublic function other(): int32 { return 0; }\n"))
        for (bad in listOf(
            "function jaso_init(): int32 { return 0; }",
            "public function jaso_start(a: int32): int32 { return 0; }",
            "public function jaso_stop(): void { }",
            "public function jaso_stop(): int64 { return 0L; }"
        )) {
            assertTrue("must be declared" in errors("package life.z;\npublic function other(): int32 { return 0; }\n$bad\n"), bad)
        }
    }

    // ---- 随机操作序列对照 Kotlin 模型 ----

    /** 模型里的一个模块：依赖名与版本要求（satisfiable = false 表示区间排除现有版本）、钩子行为。 */
    private class ModelModule(val name: String, val deps: List<String>, val badRange: Boolean, val behaviour: Int)

    /** 与 JasoModuleTable 同语义的 Kotlin 模型：null 表示未装入。 */
    private class Model(val modules: List<ModelModule>) {
        val phase = HashMap<String, String>()

        fun load(m: ModelModule): String {
            val depsOk = !m.badRange && m.deps.all { phase[it] == "running" }
            return when {
                !depsOk -> "dependency.unsatisfied"
                m.name in phase -> "module.duplicate"
                m.behaviour == 1 -> "lifecycle.init"
                m.behaviour == 2 -> "lifecycle.start"
                else -> { phase[m.name] = "running"; "ok" }
            }
        }

        fun stop(m: ModelModule): String = when {
            m.name !in phase -> "no-handle"
            phase[m.name] == "stopped" -> "stop.already"
            modules.any { phase[it.name] == "running" && m.name in it.deps } -> "stop.dependent"
            else -> {
                phase[m.name] = "stopped"
                if (m.behaviour == 3) "stop.hook" else "ok"
            }
        }
    }

    @Test
    fun `random operations match the model`() {
        val pkgHook = { init: Int, start: Int, stop: Int ->
            JasoSections(compileModule(
                opts("seed-z"),
                "jaso_init.jas" to "package seed.z;\npublic function jaso_init(): int32 { return $init; }\n",
                "jaso_start.jas" to "package seed.z;\npublic function jaso_start(): int32 { return $start; }\n",
                "jaso_stop.jas" to "package seed.z;\npublic function jaso_stop(): int32 { return $stop; }\n",
                "f.jas" to "package seed.z;\npublic function f(): int32 { return 1; }\n"
            ))
        }
        val images = listOf(pkgHook(0, 0, 0), pkgHook(1, 0, 0), pkgHook(0, 1, 0), pkgHook(0, 0, 1))
        val random = Random(20261162L)
        repeat(120) { round ->
            val count = 3 + random.nextInt(6)
            val modules = (0 until count).map { i ->
                val deps = (0 until i).filter { random.nextInt(3) == 0 }.map { "seed-$it" }
                val roll = random.nextInt(10)
                val behaviour = when (roll) { 0 -> 1; 1 -> 2; 2, 3 -> 3; else -> 0 }
                ModelModule("seed-$i", deps, deps.isNotEmpty() && random.nextInt(12) == 0, behaviour)
            }
            val files = modules.map { m ->
                val image = images[m.behaviour]
                val depends = m.deps.map { if (m.badRange) "$it@>=9.0" else "$it@>=1.0,<2.0" }
                val options = JasoModuleOptions(m.name, "1.0.0", depends = depends)
                JasoWriter.assemble(JasoManifestText.render(options, image.exportNames), image.classes, image.exportNames, image.importText)
            }
            val model = Model(modules)
            val rt = host.runtime()
            val handles = HashMap<String, Any>()
            repeat(40) { step ->
                val pick = random.nextInt(count)
                val m = modules[pick]
                val where = "round $round step $step ${m.name} ${modules.map { it.name to it.deps }}"
                when (random.nextInt(4)) {
                    0, 1 -> {
                        val expected = model.load(m)
                        val got = try {
                            handles[m.name] = host.load(rt, files[pick])
                            "ok"
                        } catch (r: Rejected) {
                            r.code
                        }
                        assertEquals(expected, got, where)
                    }
                    2 -> {
                        val handle = handles[m.name]
                        val expected = model.stop(m)
                        if (handle == null) {
                            assertEquals("no-handle", expected, where)
                        } else {
                            val got = try {
                                host.stop(rt, handle)
                                "ok"
                            } catch (r: Rejected) {
                                r.code
                            }
                            assertEquals(expected, got, where)
                        }
                    }
                    else -> handles[m.name]?.let {
                        assertEquals(model.phase.getValue(m.name), host.phase(it), where)
                        assertEquals("deferred", host.unload(rt, it), where)
                    }
                }
                assertTrue(host.dependenciesHold(rt), where)
                assertEquals(model.phase.size, host.tableSize(rt), where)
            }
        }
    }

    // ---- 真实线程 ----

    @Test
    fun `concurrent loads of one name admit exactly one`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(30) {
                val rt = host.runtime()
                val results = (0 until 8).map {
                    pool.submit(Callable {
                        try {
                            host.load(rt, demo)
                            "ok"
                        } catch (r: Rejected) {
                            r.code
                        }
                    })
                }.map { it.get(60, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it == "ok" }, results.toString())
                assertEquals(7, results.count { it == "module.duplicate" }, results.toString())
                assertEquals(1, host.tableSize(rt))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a concurrent stop never strands a dependent`() {
        val image = JasoSections(demo)
        val dependent = JasoWriter.assemble(
            JasoManifestText.render(JasoModuleOptions("dep-x", "1.0.0", depends = listOf("demo-mod")), image.exportNames),
            image.classes, image.exportNames, image.importText
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            var stopped = 0
            var loaded = 0
            repeat(150) {
                val rt = host.runtime()
                val base = host.load(rt, demo)
                val a = pool.submit(Callable {
                    try {
                        host.load(rt, dependent)
                        true
                    } catch (r: Rejected) {
                        assertEquals("dependency.unsatisfied", r.code)
                        false
                    }
                })
                val b = pool.submit(Callable {
                    try {
                        host.stop(rt, base)
                        true
                    } catch (r: Rejected) {
                        assertEquals("stop.dependent", r.code)
                        false
                    }
                })
                val gotLoad = a.get(60, TimeUnit.SECONDS)
                val gotStop = b.get(60, TimeUnit.SECONDS)
                assertTrue(gotLoad != gotStop, "恰好一方成功：load=$gotLoad stop=$gotStop")
                assertTrue(host.dependenciesHold(rt))
                if (gotStop) stopped++ else loaded++
            }
            assertTrue(stopped + loaded == 150)
        } finally {
            pool.shutdownNow()
        }
    }
}

/**
 * Lincheck 模型检查：模块表的预订 / 提交与被依赖模块的 stop 竞争。
 * 固定场景 + EpsilonVerifier：检查交错下的不变量，不是线性化证明。
 */
class JasoModuleTableLincheckTest {
    private val table = JasoTableProbe.newTable()
    private val base = JasoTableProbe.running(table)
    @Volatile private var dependent: Any? = null
    @Volatile private var stopped = false

    @Operation fun loadDependent() {
        val entry = runCatching { JasoTableProbe.reserveDependent(table) }.getOrNull() ?: return
        dependent = entry
        JasoTableProbe.commit(table, entry)
    }

    @Operation fun stopBase() {
        stopped = runCatching { JasoTableProbe.beginStop(table, base) }.isSuccess
    }

    @Validate fun validate() {
        check(JasoTableProbe.dependenciesHold(table)) { "依赖不变量被破坏" }
        check((dependent != null) != stopped) { "恰好一方成功：dependent=$dependent stopped=$stopped" }
    }

    @Test
    fun `model check reserve against stop`() {
        JasoTableProbe.warm()
        ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(2000)
            .verifier(EpsilonVerifier::class.java)
            .addCustomScenario {
                parallel {
                    thread { actor(JasoModuleTableLincheckTest::loadDependent) }
                    thread { actor(JasoModuleTableLincheckTest::stopBase) }
                }
            }
            .check(this::class)
    }
}

/** 直接驱动 JasoModuleTable；Lincheck 插桩的是系统类加载器上的运行时类。 */
internal object JasoTableProbe {
    private val compiled: ClassLoader by lazy { JasoRuntime.loader }
    private val tableClass by lazy { compiled.loadClass("std.jaso.JasoModuleTable") }
    private val manifestClass by lazy { compiled.loadClass("std.jaso.JasoManifest") }
    private val entryClass by lazy { compiled.loadClass("std.jaso.JasoModuleEntry") }

    private fun manifest(text: String): Any {
        val data = text.toByteArray()
        return manifestClass.getMethod("parse", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, data, 0, data.size)
    }

    private val baseManifest by lazy { manifest("core.name = base\ncore.version = 1.0.0\ncore.abi = jvm.52\ncore.stage = runtime\n") }
    private val dependentManifest by lazy { manifest("core.name = dep\ncore.version = 1.0.0\ncore.abi = jvm.52\ncore.stage = runtime\ncore.depends = base\n") }

    private val reserveMethod by lazy { tableClass.getMethod("reserve", manifestClass) }
    private val commitMethod by lazy { tableClass.getMethod("commit", entryClass) }
    private val beginStopMethod by lazy { tableClass.getMethod("beginStop", entryClass) }
    private val holdMethod by lazy { tableClass.getMethod("dependenciesHold") }

    /**
     * 在模型检查之前走遍两条成功路径与两条拒绝路径：Lincheck 下首次加载 class（如拒绝用的错误类）
     * 会在受控线程里卡在 JDK 类加载器的锁上，被报告成挂起。
     */
    fun warm() {
        val table = newTable()
        runCatching { reserveDependent(table) }
        val base = running(table)
        val dependent = reserveDependent(table)
        runCatching { beginStop(table, base) }
        commit(table, dependent)
        dependenciesHold(table)
    }

    fun newTable(): Any = tableClass.getConstructor().newInstance()
    private fun reserve(table: Any, m: Any): Any = unwrap { reserveMethod.invoke(table, m) }!!
    fun commit(table: Any, entry: Any) { unwrap { commitMethod.invoke(table, entry) } }
    fun beginStop(table: Any, entry: Any) { unwrap { beginStopMethod.invoke(table, entry) } }
    fun dependenciesHold(table: Any): Boolean = holdMethod.invoke(table) as Boolean
    fun reserveDependent(table: Any): Any = reserve(table, dependentManifest)
    fun running(table: Any): Any = reserve(table, baseManifest).also { commit(table, it) }

    private fun unwrap(body: () -> Any?): Any? = try {
        body()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

/** fuzz 的基础模块：每个进程只编译一次。 */
internal object JasoLinkFuzzBase {
    val base: JasoSections by lazy {
        JasoSections(compileModule(JasoModuleOptions("fuzz-a", "1.0.0"), "f.jas" to "package fuzz.a;\npublic function f(): int32 { return 1; }\n"))
    }
}

/** Jazzer：任意代码段载荷只得到 JasoRuntimeError；接口文本过了第 6a 步后解析不抛其它异常。 */
class JasoLinkFuzzTest {
    private val base: JasoSections get() = JasoLinkFuzzBase.base

    /**
     * 输入是对一个合法载荷的编辑：首字节选择改 ZIP 本身还是改其中的 class，其后每 3 字节是（偏移低位、
     * 偏移高位、异或值）。直接用随机字节当 ZIP 或 class 只会停在第一个魔数上。
     * 结果只能是通过或结构化拒绝。
     */
    @FuzzTest(maxDuration = "60s")
    fun payload(data: ByteArray) {
        if (data.isEmpty()) return
        val host = JasoHost()
        val path = "fuzz/a/f.class"
        fun edited(original: ByteArray): ByteArray {
            val out = original.copyOf()
            for (i in 1 until data.size - 2 step 3) {
                val at = ((data[i].toInt() and 255) or ((data[i + 1].toInt() and 255) shl 8)) % out.size
                out[at] = (out[at].toInt() xor data[i + 2].toInt()).toByte()
            }
            return out
        }
        val pad = mapOf(path to base.classes.getValue(path), "fuzz/a/Pad.class" to ByteArray(4096))
        val padded = JasoWriter.assemble(JasoManifestText.render(JasoModuleOptions("fuzz-a", "1.0.0"), base.exportNames), pad, base.exportNames, base.importText)
        val zip = if (data[0].toInt() and 1 == 0) {
            edited(zipOf(listOf(path to base.classes.getValue(path))))
        } else {
            zipOf(listOf(path to edited(base.classes.getValue(path))))
        }
        val file = withCodePayload(padded, zip)
        try {
            host.load(host.runtime(), file)
        } catch (_: Rejected) {
        }
    }

    /** 声明文本：过了第 6a 步的任意 fn 声明，链接只能通过或给出结构化拒绝。 */
    @FuzzTest(maxDuration = "60s")
    fun declaration(data: ByteArray) {
        val host = JasoHost()
        val text = "package fuzz.a\n" + String(data, Charsets.ISO_8859_1).filter { it != '\r' }
        val symbols = Regex("(?m)^\\s*fn\\s+([A-Za-z0-9_.$]+)").findAll(text).map { it.groupValues[1] }.toList().distinct()
        val valid = symbols.isNotEmpty() && symbols.all { it.length <= 128 } && symbols.size <= 64
        if (!valid) return
        val file = JasoWriter.assemble(JasoManifestText.render(JasoModuleOptions("fuzz-a", "1.0.0"), symbols), base.classes, symbols, text)
        try {
            host.load(host.runtime(), file)
        } catch (_: Rejected) {
        } catch (e: IllegalArgumentException) {
            // 清单校验之外的写出器拒绝不属于被测对象
            if (e.stackTrace.none { it.className.startsWith("jasper.compiler") }) throw e
        }
    }
}
