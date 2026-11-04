// ============================================================================
// JasoPathTest.kt —— 运行时模块路径、装载计划与程序入口（JB-L3 第四片）
// ============================================================================
// 职责：Jasper 写的 std.jaso.JasoModulePath / JasoLoadPlanner / JasoProgram 的端到端与模型对照：
//   用编译器真实产出的模块跑 `--run` 的整条路径；固定种子的随机依赖图对照 Kotlin 模型。
// ============================================================================

package jasper

import com.code_intelligence.jazzer.junit.FuzzTest
import jasper.compiler.JasoModuleOptions
import jasper.compiler.JasperCompiler
import jasper.compiler.runJasoMain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 经反射驱动 JasoProgram 与计划器。 */
private object PathHost {
    private val loader get() = JasoRuntime.loader
    private fun cls(name: String) = loader.loadClass("std.jaso.$name")
    val host = JasoHost()

    /** 跑一次 JasoProgram.run，捕获错误输出；模块自己的输出用 [Mark]。 */
    fun run(vararg args: String): Pair<Int, String> {
        val captured = ByteArrayOutputStream()
        val errors = PrintStream(captured, true, "UTF-8")
        val code = cls("JasoProgram").getMethod("run", Array<String>::class.java, PrintStream::class.java).invoke(null, args, errors) as Int
        return code to captured.toString("UTF-8")
    }

    fun path(vararg files: Pair<String, ByteArray>): Any {
        val path = cls("JasoModulePath").getConstructor().newInstance()
        val add = cls("JasoModulePath").getMethod("add", String::class.java, ByteArray::class.java)
        for ((name, bytes) in files) unwrap { add.invoke(path, name, bytes) }
        return path
    }

    fun scan(vararg entries: String): Any =
        unwrap { cls("JasoModulePath").getMethod("scan", cls("JasoStrings")).invoke(null, host.strings(*entries)) }!!

    fun table(): Any = cls("JasoModuleTable").getConstructor().newInstance()

    /** 空内容的清单足以驱动计划器；这里只解析文本。 */
    fun manifest(text: String): Any {
        val b = text.toByteArray()
        return cls("JasoManifest").getMethod("parse", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, b, 0, b.size)
    }

    fun entry(source: String, text: String): Any =
        cls("JasoPathEntry").getConstructors().single().newInstance(source, ByteArray(0), manifest(text))

    /** 向路径直接放清单，不经结构读取（计划器只看清单）。 */
    fun pathOf(entries: List<Any>): Any {
        val path = cls("JasoModulePath").getConstructor().newInstance()
        val entriesField = cls("JasoModulePath").getDeclaredField("entries").apply { isAccessible = true }
        val countField = cls("JasoModulePath").getDeclaredField("count").apply { isAccessible = true }
        val array = java.lang.reflect.Array.newInstance(cls("JasoPathEntry"), entries.size + 1)
        entries.forEachIndexed { i, e -> java.lang.reflect.Array.set(array, i, e) }
        entriesField.set(path, array)
        countField.set(path, entries.size)
        return path
    }

    /** 计划结果：模块名列表（依赖在前）。 */
    fun plan(path: Any, table: Any, root: String): List<String> {
        val planner = cls("JasoLoadPlanner").getConstructors().single().newInstance(path, table)
        val out = unwrap { cls("JasoLoadPlanner").getMethod("plan", String::class.java).invoke(planner, root) }!!
        return (0 until java.lang.reflect.Array.getLength(out)).map { i ->
            val e = java.lang.reflect.Array.get(out, i)
            val m = e.javaClass.getField("manifest").get(e)
            m.javaClass.getField("name").get(m) as String
        }
    }

    fun commit(table: Any, text: String): Any {
        val entry = cls("JasoModuleTable").getMethod("reserve", cls("JasoManifest")).invoke(table, manifest(text))
        cls("JasoModuleTable").getMethod("commit", cls("JasoModuleEntry")).invoke(table, entry)
        return entry
    }

    fun beginStop(table: Any, entry: Any) {
        cls("JasoModuleTable").getMethod("beginStop", cls("JasoModuleEntry")).invoke(table, entry)
    }

    fun loadFromPath(runtime: Any, path: Any, name: String, vararg granted: String): Any =
        unwrap { cls("JasoRuntime").getMethod("loadFromPath", cls("JasoModulePath"), String::class.java, cls("JasoStrings")).invoke(runtime, path, name, host.strings(*granted)) }!!

    fun options(vararg args: String): Any =
        unwrap { cls("JasoProgramOptions").getMethod("parse", Array<String>::class.java).invoke(null, args) }!!

    fun stopAll(runtime: Any): List<String> {
        val strings = cls("JasoRuntime").getMethod("stopAll").invoke(runtime)
        val size = strings.javaClass.getMethod("size").invoke(strings) as Int
        return (0 until size).map { strings.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(strings, it) as String }
    }

    private fun unwrap(body: () -> Any?): Any? {
        try {
            return body()
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            val code = runCatching { cause.javaClass.getMethod("code").invoke(cause) as String }.getOrNull()
            if (code != null) throw Rejected(code, cause.message ?: "")
            throw cause
        }
    }
}

private fun rejectedWith(code: String, body: () -> Unit): String {
    val e = runCatching { body() }.exceptionOrNull()
    assertTrue(e is Rejected, "应被拒绝为 $code，实际 $e")
    assertEquals(code, e.code, e.message)
    return e.message ?: ""
}

/** 把模块写进临时目录的小工具。 */
private class PathBench : AutoCloseable {
    val dir: File = Files.createTempDirectory("jaso-path").toFile()

    fun write(fileName: String, options: JasoModuleOptions, vararg sources: Pair<String, String>, modulePath: Boolean = true): File {
        val file = File(dir, "$fileName.jaso")
        val result = JasperCompiler().compileToJaso(mapOf(*sources), options, file.toPath(), if (modulePath) listOf(dir.toPath()) else null)
        assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
        return file
    }

    override fun close() {
        dir.deleteRecursively()
    }
}

private fun options(name: String, vararg depends: String, requires: List<String> = emptyList(), version: String = "1.0.0") =
    JasoModuleOptions(name, version, null, null, ">=0.1,<0.2", depends.toList(), requires, null)

private object Mark {
    private const val KEY = "jaso.path.trace"
    fun clear() = System.clearProperty(KEY)
    fun get(): String = System.getProperty(KEY) ?: ""
    fun append(mark: String) = "java.lang.System.setProperty(\"$KEY\", java.lang.System.getProperty(\"$KEY\", \"\") + \"$mark\");"
}

class JasoPathTest {
    private val libSource = "add.jas" to """
        package lib.m;
        public function add(a: int32, b: int32): int32 { return a + b; }
    """.trimIndent()

    private fun hooks(pkg: String, mark: String) = arrayOf(
        "jaso_start.jas" to "package $pkg;\npublic function jaso_start(): int32 { ${Mark.append("+$mark")} return 0; }\n",
        "jaso_stop.jas" to "package $pkg;\npublic function jaso_stop(): int32 { ${Mark.append("-$mark")} return 0; }\n"
    )

    private fun mainReturning(code: String) = "main.jas" to """
        package app.m;
        from lib.m import add;
        public function main(args: string[]): int32 {
            ${Mark.append("args=")}
            var i: int32 = 0;
            while (i < args.length) {
                java.lang.System.setProperty("jaso.path.trace", java.lang.System.getProperty("jaso.path.trace", "") + args[i] + ";");
                i = i + 1;
            }
            return $code;
        }
    """.trimIndent()

    @Test
    fun `a program runs from the module path with its dependency loaded first and stopped last`() = PathBench().use { bench ->
        bench.write("lib", options("lib"), libSource, *hooks("lib.m", "lib"))
        bench.write("app", options("app", "lib"), mainReturning("add(40, 2)"), *hooks("app.m", "app"))
        Mark.clear()
        val (code, err) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "app", "x", "--not-an-option", "é")
        assertEquals(42, code, err)
        assertEquals("", err)
        assertEquals("+lib+appargs=x;--not-an-option;é;-app-lib", Mark.get())
    }

    @Test
    fun `the command line entry compiles the runtime and runs the module`() = PathBench().use { bench ->
        bench.write("lib", options("lib"), libSource)
        bench.write("app", options("app", "lib"), mainReturning("add(1, 6)"))
        assertEquals(7, runJasoMain(listOf("--module-path", bench.dir.path, "--allow-unsigned", "app")))
    }

    @Test
    fun `main without arguments or result`() = PathBench().use { bench ->
        bench.write("plain", options("plain"), "main.jas" to "package p.plain;\npublic function main(): void { ${Mark.append("ran")} }\n")
        Mark.clear()
        assertEquals(0, PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "plain").first)
        assertEquals("ran", Mark.get())
    }

    @Test
    fun `a throwing main exits 1 and still stops modules`() = PathBench().use { bench ->
        bench.write("t", options("t"), "main.jas" to "package p.t;\npublic function main(): void { throw new std.RecoverableError(\"boom\"); }\n", *hooks("p.t", "t"))
        Mark.clear()
        val (code, err) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "t")
        assertEquals(1, code)
        assertTrue(err.contains("boom"), err)
        assertEquals("+t-t", Mark.get())
    }

    @Test
    fun `a failing stop hook turns exit 0 into 1 but does not hide a main result`() = PathBench().use { bench ->
        val stop = "jaso_stop.jas" to "package p.s;\npublic function jaso_stop(): int32 { return 9; }\n"
        bench.write("s", options("s"), "main.jas" to "package p.s;\npublic function main(args: string[]): int32 { return args.length; }\n", stop)
        val (zero, err) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "s")
        assertEquals(1, zero)
        assertTrue(err.contains("stop.hook"), err)
        assertEquals(2, PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "s", "a", "b").first)
    }

    @Test
    fun `unsigned files need an explicit allowance and capabilities need an explicit grant`() = PathBench().use { bench ->
        bench.write("c", options("c", requires = listOf("net")), "main.jas" to "package p.c;\npublic function main(): void { }\n")
        val (unsigned, err) = PathHost.run("--module-path", bench.dir.path, "c")
        assertEquals(3, unsigned)
        assertTrue(err.contains("trust.unsigned"), err)
        val (denied, deniedErr) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "c")
        assertEquals(3, denied)
        assertTrue(deniedErr.contains("capability"), deniedErr)
        assertEquals(0, PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "--grant", "net", "c").first)
    }

    @Test
    fun `a bad trust key is refused before any file is read`() {
        val (code, err) = PathHost.run("--trust", "jasper.dev:jaso-:0200000000000000000000000000000000000000000000000000000000000000", "x")
        assertEquals(3, code)
        assertTrue(err.contains("trust.key"), err)
    }

    @Test
    fun `a signed module passes trust through the trust option`() {
        val key = JasoRuntime.devKey.joinToString("") { "%02x".format(it) }
        val vector = File(JasoRuntime.vectorDir, "ok-managed.jaso").path
        val name = String(File(vector).readBytes(), Charsets.ISO_8859_1).let { Regex("core.name = ([a-z.-]+)").find(it)!!.groupValues[1] }
        val (without, withoutErr) = PathHost.run("--module-path", vector, "--allow-unsigned", name)
        assertEquals(3, without)
        assertTrue(withoutErr.contains("trust.publisher"), withoutErr)
        // 信任通过之后，这个向量没有 jasper.import，停在本运行时的链接步骤。
        val (with, withErr) = PathHost.run("--module-path", vector, "--trust", "jasper.dev:jaso-:$key", name)
        assertEquals(3, with)
        assertTrue(withErr.contains("link.binding"), withErr)
    }

    @Test
    fun `entry point problems are reported before anything runs in main`() = PathBench().use { bench ->
        bench.write("none", options("none"), "f.jas" to "package p.none;\npublic function f(): void { }\n")
        val (missing, missingErr) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "none")
        assertEquals(3, missing)
        assertTrue(missingErr.contains("entry.missing"), missingErr)
        bench.write("bad", options("bad"), "main.jas" to "package p.bad;\npublic function main(a: int32): void { }\n")
        val (shape, shapeErr) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "bad")
        assertEquals(3, shape)
        assertTrue(shapeErr.contains("entry.shape"), shapeErr)
        bench.write("str", options("str"), "main.jas" to "package p.str;\npublic function main(): string { return \"x\"; }\n")
        assertTrue(PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "str").second.contains("entry.shape"))
        bench.write("nul", options("nul"), "main.jas" to "package p.nul;\npublic function main(a: string[]?): void { }\n")
        assertTrue(PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "nul").second.contains("entry.shape"))
    }

    @Test
    fun `only the top level main of the package is an entry point and it must be unique`() = PathBench().use { bench ->
        bench.write("other", options("other"), "Helper.jas" to """
            package p.other;
            public class Helper {
                public constructor() { }
                public static function main(): void { }
            }
        """.trimIndent())
        assertTrue(PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "other").second.contains("entry.missing"))
        bench.write("twice", options("twice"), "main.jas" to """
            package p.twice;
            public class main {
                public constructor() { }
                public function main(): void { }
                public function main(a: int32): void { }
            }
        """.trimIndent())
        val (code, err) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "twice")
        assertEquals(3, code)
        assertTrue(err.contains("entry.shape") && err.contains("overloaded"), err)
    }

    @Test
    fun `a dependency started by someone else after the plan is used instead of failing`() = PathBench().use { bench ->
        val shared = bench.write("shared", options("shared"), libSource, modulePath = false)
        // first 排在 shared 之前；它的 init 经系统属性里的回调替「另一个调用者」装入 shared，
        // 所以计划里的下一步一定得到 module.duplicate，且 shared 已在运行。
        bench.write("afirst", options("afirst"), "jaso_init.jas" to """
            package p.first;
            public function jaso_init(): int32 {
                var properties: java.util.Properties = java.lang.System.getProperties();
                var action: java.lang.Runnable = cast (properties.get("jaso.path.action")) as java.lang.Runnable;
                action.run();
                return 0;
            }
        """.trimIndent(), "f.jas" to "package p.first;\npublic function f(): void { }\n", modulePath = false)
        bench.write("root", options("root", "afirst", "shared"), "r.jas" to """
            package p.root;
            from lib.m import add;
            public function r(): int32 { return add(20, 22); }
        """.trimIndent())
        val host = PathHost.host
        val runtime = host.runtime()
        var calls = 0
        System.getProperties()["jaso.path.action"] = Runnable { calls++; host.load(runtime, shared.readBytes()) }
        try {
            val root = PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "root")
            assertEquals(1, calls)
            assertEquals(42, host.call(root, "p.root.r.r"))
            assertEquals(3, host.tableSize(runtime))
        } finally {
            System.getProperties().remove("jaso.path.action")
        }
    }

    @Test
    fun `usage errors exit 2`() {
        for (args in listOf(
            arrayOf(),
            arrayOf("--module-path"),
            arrayOf("--grant"),
            arrayOf("--nope", "m"),
            arrayOf("--"),
            arrayOf("--module-path", "a", "--module-path", "b", "m"),
            arrayOf("--trust", "nocolons", "m"),
            arrayOf("--trust", "a:b", "m"),
            arrayOf("--trust", ":ns:" + "0".repeat(64), "m"),
            arrayOf("--trust", "a:b:abc", "m"),
            arrayOf("--trust", "a:b:" + "G".repeat(64), "m"),
            arrayOf("--trust", "a:b:" + "A".repeat(64), "m")
        )) {
            val (code, err) = PathHost.run(*args)
            assertEquals(2, code, args.joinToString(" "))
            assertTrue(err.contains("Usage:"), err)
        }
    }

    @Test
    fun `option parsing stops at the module name`() {
        val parsed = PathHost.options("--grant", "a", "--grant", "b", "--", "--weird", "--grant", "c")
        assertEquals("--weird", parsed.javaClass.getField("moduleName").get(parsed))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("--grant", "c"), (parsed.javaClass.getField("programArguments").get(parsed) as Array<String>).toList())
    }

    @Test
    fun `path entries directories files and bad files`() = PathBench().use { bench ->
        val lib = bench.write("lib", options("lib"), libSource)
        File(bench.dir, "notes.txt").writeText("ignored: wrong extension")
        File(bench.dir, "sub").mkdir()
        File(bench.dir, "sub/hidden.jaso").writeBytes(ByteArray(3))
        assertEquals(1, pathSize(PathHost.scan(bench.dir.path)), "目录不递归，只看 *.jaso")
        assertEquals(1, pathSize(PathHost.scan(lib.path)), "单个文件")
        assertEquals(2, pathSize(PathHost.scan(bench.dir.path, lib.path)))

        assertTrue(rejectedWith("path.file") { PathHost.scan(File(bench.dir, "absent").path) }.contains("absent"))
        val bad = File(bench.dir, "bad.jaso").apply { writeBytes(ByteArray(10)) }
        val detail = rejectedWith("path.file") { PathHost.scan(bench.dir.path) }
        assertTrue(detail.contains(bad.path) && detail.contains("format."), detail)
    }

    @Test
    fun `a directory is read in name order whatever the file system returns`() = PathBench().use { bench ->
        val bytes = bench.write("seed", options("seed"), libSource, modulePath = false).readBytes()
        File(bench.dir, "seed.jaso").delete()
        val names = listOf("z", "b", "m", "a", "y", "c", "k", "d")
        names.forEach { File(bench.dir, "$it.jaso").writeBytes(bytes) }
        val path = PathHost.scan(bench.dir.path)
        val sources = (0 until pathSize(path)).map { i ->
            val e = path.javaClass.getMethod("entryAt", Int::class.javaPrimitiveType).invoke(path, i)
            File(e.javaClass.getField("source").get(e) as String).name
        }
        assertEquals(names.sorted().map { "$it.jaso" }, sources)
    }

    private fun pathSize(path: Any): Int = path.javaClass.getMethod("size").invoke(path) as Int

    @Test
    fun `split drops empty segments and keeps order`() {
        val s = File.pathSeparator
        val split = JasoRuntime.loader.loadClass("std.jaso.JasoModulePath").getMethod("split", String::class.java)
        fun parts(text: String): List<String> {
            val strings = split.invoke(null, text)
            val size = strings.javaClass.getMethod("size").invoke(strings) as Int
            return (0 until size).map { strings.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(strings, it) as String }
        }
        assertEquals(emptyList(), parts(""))
        assertEquals(emptyList(), parts("$s$s"))
        assertEquals(listOf("模块", "b"), parts("${s}模块$s${s}b$s"))
    }

    @Test
    fun `the same name twice is ambiguous only when it is needed`() = PathBench().use { bench ->
        val one = bench.write("one", options("twin"), libSource, modulePath = false)
        val two = File(bench.dir, "two.jaso").apply { writeBytes(one.readBytes()) }
        val single = bench.write("solo", options("solo"), "main.jas" to "package p.solo;\npublic function main(): void { }\n", modulePath = false)
        val path = PathHost.scan(one.path, two.path, single.path)
        assertEquals(listOf("solo"), PathHost.plan(path, PathHost.table(), "solo"))
        val detail = rejectedWith("path.ambiguous") { PathHost.plan(path, PathHost.table(), "twin") }
        assertTrue(detail.contains(one.path) && detail.contains(two.path), detail)
    }

    @Test
    fun `nothing runs when the plan fails`() = PathBench().use { bench ->
        bench.write("lib", options("lib"), libSource, *hooks("lib.m", "lib"))
        bench.write("app", options("app", "lib"), mainReturning("add(1, 1)"), *hooks("app.m", "app"))
        val libFile = File(bench.dir, "lib.jaso")
        libFile.delete()
        Mark.clear()
        val (code, err) = PathHost.run("--module-path", bench.dir.path, "--allow-unsigned", "app")
        assertEquals(3, code)
        assertTrue(err.contains("path.missing") && err.contains("app needs lib"), err)
        assertEquals("", Mark.get())
    }

    @Test
    fun `a dependency that started stays running after a later failure and a retry skips it`() = PathBench().use { bench ->
        bench.write("lib", options("lib"), libSource, *hooks("lib.m", "lib"))
        val fail = "jaso_init.jas" to "package app.f;\npublic function jaso_init(): int32 { return java.lang.Integer.getInteger(\"jaso.path.init\", 1).intValue(); }\n"
        bench.write("appf", options("appf", "lib"), "f.jas" to "package app.f;\nfrom lib.m import add;\npublic function f(): int32 { return add(2, 3); }\n", fail)
        val host = PathHost.host
        val runtime = host.runtime()
        Mark.clear()
        rejectedWith("lifecycle.init") { PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "appf") }
        assertEquals("+lib", Mark.get())
        assertEquals("running", host.phase(host.find(runtime, "lib")!!))
        System.setProperty("jaso.path.init", "0")
        try {
            val app = PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "appf")
            assertEquals(5, host.call(app, "app.f.f.f"))
            assertEquals("+lib", Mark.get(), "依赖只启动一次")
            val same = PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "appf")
            assertTrue(same === app, "已在运行时直接返回")
        } finally {
            System.clearProperty("jaso.path.init")
        }
        assertEquals(emptyList(), PathHost.stopAll(runtime))
        assertEquals("+lib-lib", Mark.get())
        assertEquals(emptyList(), PathHost.stopAll(runtime), "已停止的不再停")
        assertTrue(rejectedWith("path.stopped") { PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "lib") }.contains("lib"))
    }

    @Test
    fun `a module on the path does not replace a running one and a running version must still fit`() = PathBench().use { bench ->
        val old = bench.write("lib", options("lib", version = "1.0.0"), libSource, modulePath = false)
        val host = PathHost.host
        val runtime = host.runtime()
        PathHost.loadFromPath(runtime, PathHost.path("old" to old.readBytes()), "lib")
        val app = bench.write("app", options("app", "lib"), mainReturning("add(1, 1)"))
        val strict = bench.write("strict", options("strict", "lib@>=2.0"), "s.jas" to "package p.strict;\npublic function s(): void { }\n", modulePath = false)
        assertEquals("running", host.phase(PathHost.loadFromPath(runtime, PathHost.path("app" to app.readBytes()), "app")))
        val detail = rejectedWith("path.version") { PathHost.loadFromPath(runtime, PathHost.path("s" to strict.readBytes()), "strict") }
        assertTrue(detail.contains("strict needs lib") && detail.contains("1.0.0"), detail)
    }

    @Test
    fun `concurrent loads sharing a dependency all finish`() = PathBench().use { bench ->
        bench.write("lib", options("lib"), libSource)
        (0 until 4).forEach { i ->
            bench.write("a$i", options("a$i", "lib"), "f.jas" to "package app.a$i;\nfrom lib.m import add;\npublic function f$i(): int32 { return add($i, 1); }\n")
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                val host = PathHost.host
                val runtime = host.runtime()
                val results = (0 until 4).map { i ->
                    pool.submit(Callable {
                        // module.duplicate 表示另一线程的依赖还在装入，约定由调用者重试。
                        var outcome: Any? = null
                        while (outcome == null) {
                            try {
                                outcome = PathHost.loadFromPath(runtime, PathHost.scan(bench.dir.path), "a$i")
                            } catch (e: Rejected) {
                                assertEquals("module.duplicate", e.code)
                                Thread.yield()
                            }
                        }
                        outcome
                    })
                }
                results.forEachIndexed { i, f ->
                    assertEquals(i + 1, host.call(f.get(60, TimeUnit.SECONDS)!!, "app.a$i.f$i.f$i"))
                }
                assertEquals(5, host.tableSize(runtime))
                assertTrue(host.dependenciesHold(runtime))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 计划器的 Kotlin 模型：同一规格的递归写法。 */
    private class ModelNode(val name: String, val major: Int, val depends: List<Pair<String, Int?>>)

    private fun modelPlan(nodes: List<ModelNode>, running: Map<String, Int>, stopped: Set<String>, root: String): Pair<String?, List<String>> {
        val order = mutableListOf<String>()
        val mark = mutableMapOf<String, Int>()
        var failure: String? = null
        fun resolve(name: String, wanted: Int?): ModelNode? {
            running[name]?.let { v ->
                if (wanted != null && wanted != v) failure = "path.version"
                return null
            }
            if (name in stopped) { failure = "path.stopped"; return null }
            val found = nodes.filter { it.name == name }
            if (found.size > 1) { failure = "path.ambiguous"; return null }
            if (found.isEmpty()) { failure = "path.missing"; return null }
            if (wanted != null && wanted != found[0].major) { failure = "path.version"; return null }
            return found[0]
        }
        fun visit(n: ModelNode) {
            mark[n.name] = 1
            for ((name, wanted) in n.depends) {
                val target = resolve(name, wanted)
                if (failure != null) return
                if (target != null) {
                    if (mark[name] == 1) { failure = "path.cycle"; return }
                    if (mark[name] == null) { visit(target); if (failure != null) return }
                }
            }
            mark[n.name] = 2
            order += n.name
        }
        val first = resolve(root, null)
        if (failure == null && first != null) visit(first)
        return failure to order
    }

    private fun manifestText(name: String, major: Int, depends: List<Pair<String, Int?>>): String {
        val base = "core.name = $name\ncore.version = $major.0.0\ncore.abi = jvm.52\ncore.stage = runtime\n"
        if (depends.isEmpty()) return base
        return base + "core.depends = " + depends.joinToString(", ") { (d, w) -> if (w == null) d else "$d@>=$w.0,<${w + 1}.0" } + "\n"
    }

    @Test
    fun `random dependency graphs match the model`() {
        val random = Random(20261180L)
        val seen = mutableMapOf<String, Int>()
        repeat(4000) {
            val size = 1 + random.nextInt(7)
            val names = (0 until size).map { "m$it" }
            val forward = random.nextInt(3) != 0
            val nodes = names.mapIndexed { i, name ->
                val candidates = names.filterIndexed { j, _ -> if (forward) j > i else j != i } + (if (random.nextInt(8) == 0) listOf("absent") else emptyList())
                val depends = candidates.shuffled(kotlin.random.Random(random.nextLong())).take(random.nextInt(4))
                    .map { d -> d to (if (random.nextInt(3) == 0) 1 + random.nextInt(2) else null) }
                ModelNode(name, 1 + random.nextInt(2), depends)
            }.toMutableList()
            if (random.nextInt(10) == 0) nodes += ModelNode(names[random.nextInt(size)], 1, emptyList())
            val table = PathHost.table()
            val running = mutableMapOf<String, Int>()
            val stopped = mutableSetOf<String>()
            names.drop(1).forEach { n ->
                when (random.nextInt(12)) {
                    0 -> { val major = 1 + random.nextInt(2); PathHost.commit(table, manifestText(n, major, emptyList())); running[n] = major }
                    1 -> { val e = PathHost.commit(table, manifestText(n, 1, emptyList())); PathHost.beginStop(table, e); stopped += n }
                }
            }
            val path = PathHost.pathOf(nodes.map { PathHost.entry(it.name, manifestText(it.name, it.major, it.depends)) })
            val (failure, order) = modelPlan(nodes, running, stopped, "m0")
            val outcome = runCatching { PathHost.plan(path, table, "m0") }
            if (failure == null) {
                assertEquals(order, outcome.getOrThrow())
                val index = order.withIndex().associate { (i, n) -> n to i }
                for (n in order) {
                    nodes.single { it.name == n }.depends.forEach { (d, _) ->
                        if (d !in running) assertTrue(index.getValue(d) < index.getValue(n), "依赖在前")
                    }
                }
                seen.merge("ok", 1, Int::plus)
            } else {
                val e = outcome.exceptionOrNull()
                assertTrue(e is Rejected, "应为 $failure，实际 $outcome")
                assertEquals(failure, e.code)
                seen.merge(failure, 1, Int::plus)
            }
        }
        for (kind in listOf("ok", "path.cycle", "path.missing", "path.version", "path.stopped", "path.ambiguous")) {
            assertTrue((seen[kind] ?: 0) > 20, "$kind 覆盖不足：$seen")
        }
    }

    @Test
    fun `a long chain does not overflow the stack and a replan starts clean`() {
        val n = 20000
        val entries = (0 until n).map { i ->
            val depends: List<Pair<String, Int?>> = if (i + 1 < n) listOf("c${i + 1}" to null) else emptyList()
            PathHost.entry("c$i", manifestText("c$i", 1, depends))
        }
        val path = PathHost.pathOf(entries)
        val table = PathHost.table()
        val order = PathHost.plan(path, table, "c0")
        assertEquals(n, order.size)
        assertEquals("c${n - 1}", order.first())
        assertEquals("c0", order.last())
        assertEquals(order, PathHost.plan(path, table, "c0"))
    }

    @Test
    fun `self dependency is a cycle and the diagnosis names the edge`() {
        val path = PathHost.pathOf(listOf(PathHost.entry("s", manifestText("s", 1, listOf("s" to null)))))
        assertTrue(rejectedWith("path.cycle") { PathHost.plan(path, PathHost.table(), "s") }.contains("s -> s"))
    }

    @Test
    fun `manifest uniqueness window matches the specification`() {
        fun line(names: List<String>) = "core.name = a\ncore.version = 1.0.0\ncore.abi = c.v1\ncore.stage = runtime\ncore.depends = ${names.joinToString(", ")}\n"
        fun code(names: List<String>) = runCatching { PathHost.manifest(line(names)) }.exceptionOrNull()
            ?.let { (if (it is InvocationTargetException) it.targetException else it) }
            ?.let { it.javaClass.getMethod("code").invoke(it) as String } ?: "ok"
        val names = (0 until 32).map { "m$it" }.toMutableList()
        assertEquals("ok", code(names))
        names[31] = "m0"
        assertEquals("manifest.duplicate", code(names), "第 32 项仍在窗口内")
        names[31] = "m31"
        names += "m0"
        assertEquals("manifest.limit", code(names), "第 33 项重名只报上限")
        names += "BAD"
        assertEquals("manifest.value", code(names), "超出上限的坏项优先于上限")
    }

    /**
     * 计划器：NUL 分隔的清单文本组成路径，第一个的名字作根。任意清单只能得到 path.* 或依赖在前的次序。
     * 为什么喂清单而不是整个文件：文件读取已有 wholeFile 目标，整文件随机字节过不了摘要，到不了计划器。
     */
    @FuzzTest(maxDuration = "60s")
    fun planFromManifests(data: ByteArray) {
        val parsed = String(data, Charsets.ISO_8859_1).split('\u0000').take(16).mapNotNull { text ->
            runCatching { PathHost.entry("fuzz", text) }.getOrNull()
        }
        if (parsed.isEmpty()) return
        fun manifestOf(e: Any): Any = e.javaClass.getField("manifest").get(e)
        fun nameOf(e: Any): String = manifestOf(e).let { it.javaClass.getField("name").get(it) as String }
        val order = try {
            PathHost.plan(PathHost.pathOf(parsed), PathHost.table(), nameOf(parsed[0]))
        } catch (e: Rejected) {
            assertTrue(e.code.startsWith("path."), e.toString())
            return
        }
        assertEquals(order.size, order.toSet().size, "不重复")
        for (entry in parsed) {
            val at = order.indexOf(nameOf(entry))
            if (at < 0) continue
            val m = manifestOf(entry)
            val dependencies = m.javaClass.getField("dependencies").get(m)
            val count = m.javaClass.getField("dependencyCount").get(m) as Int
            for (i in 0 until minOf(count, 32)) {
                val d = java.lang.reflect.Array.get(dependencies, i)
                val name = d.javaClass.getField("name").get(d) as String
                assertTrue(order.indexOf(name) in 0 until at, "依赖在前")
            }
        }
    }

    @Test
    fun `corrupted files only ever become path file rejections`() {
        val good = File(JasoRuntime.vectorDir, "ok-managed.jaso").readBytes()
        val random = Random(20261181L)
        PathHost.path("good" to good)
        repeat(3000) {
            val bytes = when (it % 3) {
                0 -> ByteArray(random.nextInt(256)).also { b -> random.nextBytes(b) }
                1 -> good.copyOf(random.nextInt(good.size))
                else -> good.clone().also { b -> b[random.nextInt(b.size)] = random.nextInt(256).toByte() }
            }
            val e = runCatching { PathHost.path("f" to bytes) }.exceptionOrNull()
            if (e != null) {
                assertTrue(e is Rejected, e.toString())
                assertEquals("path.file", e.code)
            }
        }
    }

    @FuzzTest(maxDuration = "60s")
    fun programOptions(data: ByteArray) {
        // 命令行解析：参数来自操作者，但不应有用法错误以外的失败。
        val text = String(data, Charsets.UTF_8)
        val args = text.split('\n').toTypedArray()
        try {
            PathHost.options(*args)
        } catch (e: Rejected) {
            assertEquals("program.usage", e.code, e.message)
        }
    }
}
