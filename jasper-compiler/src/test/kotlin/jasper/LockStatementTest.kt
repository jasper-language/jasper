package jasper

import jasper.compiler.JasperCompiler
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `lock (x) { ... }` 是 x 的对象监视器互斥（docs/10 §10.9；Q234，LANG-LOCK-01）。
 *
 * 修复前翻译器只生成空 finally，没有 MONITORENTER / MONITOREXIT：appfw 实测 4×20 万次自增得 34 万，
 * 嵌入式库 WAL 交错丢提交。期望值由用例写出或由线程数 × 次数直接算出。
 */
class LockStatementTest {
    private val source = """
        package t;
        public class Counter {
            private var n: int64 = 0L;
            public function inc(times: int32): void {
                for (var i = 0; i < times; i++) {
                    lock (this) {
                        this.n = this.n + 1L;
                    }
                }
            }
            public function value(): int64 { return this.n; }
        }
        public class Probe {
            public static function holdsInside(o: any): bool {
                lock (o) {
                    return java.lang.Thread.holdsLock(o);
                }
            }
            public static function returnInside(o: any): int32 {
                lock (o) {
                    return 7;
                }
            }
            public static function breakInside(o: any, stop: int32): int32 {
                var count = 0;
                while (true) {
                    lock (o) {
                        count = count + 1;
                        if (count == stop) { break; }
                        if (count % 2 == 0) { continue; }
                    }
                }
                return count;
            }
            public static function throwInside(o: any): int32 {
                lock (o) {
                    var a: int32[] = new int32[1];
                    return a[5];
                }
            }
            public static function nested(a: any, b: any): bool {
                lock (a) {
                    lock (b) {
                        return java.lang.Thread.holdsLock(a) && java.lang.Thread.holdsLock(b);
                    }
                }
            }
        }
    """.trimIndent()

    private fun build(name: String, src: String = source): Pair<JasperCompiler.CompileResult, Path> {
        val dir = Paths.get("build", "scratch", "lock", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to src), dir) to dir
    }

    private fun <T> withClasses(name: String, body: (ClassLoader, Path) -> T): T {
        val (result, dir) = build(name)
        assertIs<JasperCompiler.CompileResult.Success>(result, (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        return URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { body(it, dir) }
    }

    @Test
    fun `concurrent increments under lock are never lost`() = withClasses("counter") { loader, _ ->
        val cls = loader.loadClass("t.Counter")
        val counter = cls.getConstructor().newInstance()
        val inc = cls.getMethod("inc", Int::class.javaPrimitiveType)
        val threads = 4
        val times = 50_000
        val start = CountDownLatch(1)
        val workers = List(threads) { Thread { start.await(); inc.invoke(counter, times) } }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(threads.toLong() * times, cls.getMethod("value").invoke(counter))
    }

    @Test
    fun `the monitor is held inside and released on every exit`() = withClasses("exits") { loader, _ ->
        val probe = loader.loadClass("t.Probe")
        val o = Any()
        assertEquals(true, probe.getMethod("holdsInside", Any::class.java).invoke(null, o))
        assertFalse(Thread.holdsLock(o))
        assertEquals(7, probe.getMethod("returnInside", Any::class.java).invoke(null, o))
        assertFalse(Thread.holdsLock(o))
        assertEquals(5, probe.getMethod("breakInside", Any::class.java, Int::class.javaPrimitiveType).invoke(null, o, 5))
        assertFalse(Thread.holdsLock(o))
        val thrown = runCatching { probe.getMethod("throwInside", Any::class.java).invoke(null, o) }.exceptionOrNull()
        assertIs<InvocationTargetException>(thrown)
        assertFalse(Thread.holdsLock(o))
        val b = Any()
        assertEquals(true, probe.getMethod("nested", Any::class.java, Any::class.java).invoke(null, o, b))
        assertFalse(Thread.holdsLock(o) || Thread.holdsLock(b))
    }

    @Test
    fun `bytecode contains balanced monitor instructions`() = withClasses("bytecode") { _, dir ->
        var enters = 0
        var exits = 0
        val bytes = Files.readAllBytes(dir.resolve("t").resolve("Counter.class"))
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitInsn(opcode: Int) {
                        if (opcode == Opcodes.MONITORENTER) enters++
                        if (opcode == Opcodes.MONITOREXIT) exits++
                    }
                }
        }, 0)
        assertEquals(1, enters)
        // 正常出口与异常处理器各一条 MONITOREXIT。
        assertTrue(exits >= 2, "exits=$exits")
    }

    @Test
    fun `primitive lock targets and await inside lock are diagnosed`() {
        val (prim, _) = build("primitive", "package t;\npublic function f(): int32 {\nlock (5) { }\nreturn 0;\n}\n")
        val primError = assertIs<JasperCompiler.CompileResult.Error>(prim)
        assertTrue(primError.errors.any { "lock needs an object" in it.message }, primError.message)
        val awaitSrc = """
            package t;
            import std.Future;
            public function f(x: Future<int32>): int32 {
                var o = new java.lang.Object();
                lock (o) {
                    var v = await x;
                    return v;
                }
            }
        """.trimIndent()
        val (aw, _) = build("await", awaitSrc)
        val awError = assertIs<JasperCompiler.CompileResult.Error>(aw)
        assertTrue(awError.errors.any { "await is not allowed inside a lock" in it.message }, awError.message)
    }

    @Test
    fun `random thread counts and increments add up exactly`() = withClasses("random") { loader, _ ->
        val cls = loader.loadClass("t.Counter")
        val inc = cls.getMethod("inc", Int::class.javaPrimitiveType)
        val random = Random(20261114)
        repeat(6) {
            val counter = cls.getConstructor().newInstance()
            val plan = List(random.nextInt(2, 7)) { random.nextInt(1_000, 20_000) }
            val start = CountDownLatch(1)
            val workers = plan.map { n -> Thread { start.await(); inc.invoke(counter, n) } }
            workers.forEach { it.start() }
            start.countDown()
            workers.forEach { it.join() }
            assertEquals(plan.sumOf { it.toLong() }, cls.getMethod("value").invoke(counter), "plan=$plan")
        }
    }
}
