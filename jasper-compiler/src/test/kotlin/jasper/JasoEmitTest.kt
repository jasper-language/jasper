// ============================================================================
// JasoEmitTest.kt —— 编译器产出 Managed `.jaso`（JB-L2）
// ============================================================================
// 职责：真实编译固定模块，用 Jasper 读取器判定产物，从产物的代码段加载 class 并按
//   jasper.import 的签名调用；固定种子的随机接口与随机元数据对照读取器；公开形态的
//   诊断逐条覆盖。样本写到 build/tmp/jaso-emit-samples，交给参考实现核对（verification/jaso-diff）。
// ============================================================================

package jasper

import jasper.ast.JasArrayType
import jasper.ast.JasClass
import jasper.ast.JasConstructor
import jasper.ast.JasDeclaration
import jasper.ast.JasFunction
import jasper.ast.JasImport
import jasper.ast.JasInterface
import jasper.ast.JasNamedType
import jasper.ast.JasNullableType
import jasper.ast.JasParameter
import jasper.ast.JasPrimitiveType
import jasper.ast.JasSourceFile
import jasper.ast.JasStringType
import jasper.ast.JasType
import jasper.ast.JasUnitType
import jasper.compiler.CompileError
import jasper.compiler.JasoExports
import jasper.compiler.JasoInterface
import jasper.compiler.JasoManifestText
import jasper.compiler.JasoModuleOptions
import jasper.compiler.JasoWriter
import jasper.compiler.JasperCompiler
import jasper.compiler.emitJasoMain
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 读取产物里的字段；只按规范的目录结构走，不依赖写出器。 */
internal class JasoSections(val bytes: ByteArray) {
    private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun section(kind: Int): ByteArray {
        val count = b.getInt(20)
        val dir = b.getInt(24)
        for (i in 0 until count) {
            val e = dir + 32 * i
            if (b.getInt(e) == kind) return bytes.copyOfRange(b.getInt(e + 8), b.getInt(e + 8) + b.getInt(e + 12))
        }
        error("no section of kind $kind")
    }

    val manifest: String get() = String(section(1), Charsets.UTF_8)
    val zip: ByteArray get() = section(9).let { it.copyOfRange(8, it.size) }
    val importText: String get() = section(8).let { String(it.copyOfRange(24, it.size), Charsets.UTF_8) }

    /** 导出表的名字，按表中顺序。 */
    val exportNames: List<String>
        get() {
            val t = section(3)
            val e = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until e.getInt(0)).map { i ->
                String(t, e.getInt(4 + 16 * i), e.getInt(8 + 16 * i), Charsets.US_ASCII)
            }
        }

    /** 代码段里的 class：ZIP 内路径 → 字节。 */
    val classes: Map<String, ByteArray>
        get() {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    out[entry.name] = z.readBytes()
                }
            }
            return out
        }
}

/** 给未签名文件加上签名段：目录加一项，各段后移，末尾 104 字节，再重算摘要。 */
internal fun signJaso(unsigned: ByteArray, keys: KeyPair): ByteArray {
    val u = ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN)
    val count = u.getInt(20)
    val dir = u.getInt(24)
    val out = ByteArray(unsigned.size + 32 + 104)
    val o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    val dirEnd = dir + 32 * count
    unsigned.copyInto(out, 0, 0, dirEnd)
    unsigned.copyInto(out, dirEnd + 32, dirEnd, unsigned.size)
    for (i in 0 until count) {
        val e = dir + 32 * i
        o.putInt(e + 8, u.getInt(e + 8) + 32)
    }
    o.putInt(20, count + 1)
    o.putInt(28, out.size)
    val sigAt = out.size - 104
    o.putInt(dirEnd, 5)
    o.putInt(dirEnd + 8, sigAt)
    o.putInt(dirEnd + 12, 104)
    o.putInt(sigAt, 1)
    val publicKey = keys.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
    publicKey.copyInto(out, sigAt + 8)
    ByteArray(32).copyInto(out, 40)
    val digest = MessageDigest.getInstance("SHA-256").digest(out)
    val domain = "jaso.signature.v1".toByteArray(Charsets.US_ASCII)
    val signature = Signature.getInstance("Ed25519").run {
        initSign(keys.private)
        update(domain + 0 + digest)
        sign()
    }
    signature.copyInto(out, sigAt + 40)
    resealJaso(out)
    return out
}

/** 一致性上下文下合法的元数据：发布者、名字空间、依赖、能力都在上下文之内。 */
private val CONFORMING = JasoModuleOptions(
    name = "jaso-demo-api",
    version = "1.2.3",
    description = "demo module",
    publisher = "jasper.dev",
    depends = listOf("jaso-base@>=1.0,<2.0"),
    requires = listOf("log", "alloc"),
    lifecycle = "plugin"
)

private val API_SOURCE = """
    package demo.api;

    import java.lang.StringBuilder;

    public interface Shape {
        function area(): int32;
    }

    public class Square implements Shape {
        private var side: int32;
        public constructor(side: int32) { this.side = side; }
        public function area(): int32 { return this.side * this.side; }
        public static function unit(): Square { return new Square(1); }
        public function tag(prefix: string?): string { return (prefix ?? "none") + ":" + this.side; }
        function moduleOnly(): int32 { return 1; }
        private function secret(): int32 { return 2; }
    }

    public class Plain {
        public function twice(x: int32): int32 { return x * 2; }
        public function twice(x: int32, y: int32): int32 { return x * 2 + y; }
    }

    public abstract class Base {
        public constructor() { }
        public function one(): int32 { return 1; }
    }

    class Hidden {
        public function nothing(): int32 { return 0; }
    }

    public function add(a: int32, b: int32): int32 { return a + b; }
    public function total(xs: int32[]): int64 {
        var sum: int64 = 0L;
        var i: int32 = 0;
        while (i < xs.length) { sum = sum + xs[i]; i = i + 1; }
        return sum;
    }
    public function maybe(x: int32?): int32? { return x; }
    public function quiet() { }
    function helper(): int32 { return 3; }
""".trimIndent()

private val MORE_SOURCE = """
    package demo.api;

    public class Second {
        public function area(s: Square): int32 { return s.area(); }
    }
""".trimIndent()

private val EXPECTED_IMPORT = listOf(
    "package demo.api",
    "type Base",
    "type Plain",
    "type Second",
    "type Shape",
    "type Square",
    "cap alloc",
    "cap log",
    "fn demo.api.Base.one(this: demo.api.Base): int32",
    "fn demo.api.Plain.new(): demo.api.Plain",
    "fn demo.api.Plain.twice\$HASH_INT(this: demo.api.Plain, x: int32): int32",
    "fn demo.api.Plain.twice\$HASH_TWO(this: demo.api.Plain, x: int32, y: int32): int32",
    "fn demo.api.Second.area(this: demo.api.Second, s: demo.api.Square): int32",
    "fn demo.api.Second.new(): demo.api.Second",
    "fn demo.api.Shape.area(this: demo.api.Shape): int32",
    "fn demo.api.Square.area(this: demo.api.Square): int32",
    "fn demo.api.Square.new(side: int32): demo.api.Square",
    "fn demo.api.Square.tag(this: demo.api.Square, prefix: string?): string",
    "fn demo.api.Square.unit(): demo.api.Square",
    "fn demo.api.add.add(a: int32, b: int32): int32",
    "fn demo.api.maybe.maybe(x: int32?): int32?",
    "fn demo.api.quiet.quiet(): void",
    "fn demo.api.total.total(xs: Array<int32>): int64"
)

/** 编译一个模块，返回产物；失败时测试失败并带上诊断。 */
private fun emit(name: String, sources: Map<String, String>, options: JasoModuleOptions): ByteArray {
    val out = Paths.get("build", "tmp", "jaso-emit", "$name.jaso")
    Files.deleteIfExists(out)
    val result = JasperCompiler().compileToJaso(sources, options, out)
    assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
    return Files.readAllBytes(out)
}

/** 样本目录：交给 Rust 参考实现核对（check-emit）。 */
private fun sample(name: String, bytes: ByteArray, expected: String) {
    val dir = Paths.get("build", "tmp", "jaso-emit-samples").toFile().apply { mkdirs() }
    File(dir, "$name.jaso").writeBytes(bytes)
    File(dir, "$name.expect").writeText("$expected\n")
}

/** 产物 + 只认产物里的 class 与标准库的加载器。 */
private object Emitted {
    val file: ByteArray by lazy { emit("api", linkedMapOf("Api.jas" to API_SOURCE, "More.jas" to MORE_SOURCE), CONFORMING) }
    val sections: JasoSections by lazy { JasoSections(file) }

    /** 标准库单独编译；模块 class 来自产物的 ZIP，父加载器看不到模块源码。 */
    val loader: ClassLoader by lazy {
        val dir = Files.createTempDirectory(Paths.get("build", "tmp").also { Files.createDirectories(it) }, "jaso-zip")
        val zip = dir.resolve("code.zip")
        Files.write(zip, sections.zip)
        URLClassLoader(arrayOf(zip.toUri().toURL()), JasoRuntime.loader)
    }
}

/** jasper.import 的独立语法判定（SPEC 第 9.4 节），与实现不共享代码。 */
private object ImportGrammar {
    private val IDENT = "[A-Za-z_][A-Za-z0-9_]*"
    private val DOTTED = "$IDENT(\\.$IDENT)*"

    /** 递归下降解析一个类型，返回结束位置；不合法返回 -1。 */
    private fun type(s: String, start: Int, depth: Int): Int {
        if (depth > 8) return -1
        val m = Regex(DOTTED).matchAt(s, start) ?: return -1
        var i = m.range.last + 1
        if (i < s.length && s[i] == '<') {
            i++
            while (true) {
                i = type(s, i, depth + 1)
                if (i < 0) return -1
                if (i >= s.length) return -1
                if (s[i] == '>') { i++; break }
                if (s[i] != ',') return -1
                i++
            }
        }
        if (i < s.length && s[i] == '?') i++
        return i
    }

    fun typeOk(s: String): Boolean = type(s, 0, 0) == s.length

    /** 一行 fn：返回符号、形参（名 → 类型）、返回类型。 */
    data class Fn(val symbol: String, val params: List<Pair<String, String>>, val returns: String)

    fun fn(line: String): Fn {
        val body = line.removePrefix("fn ")
        val open = body.indexOf('(')
        val close = body.lastIndexOf("): ")
        check(open > 0 && close >= open) { line }
        val symbol = body.substring(0, open)
        check(Regex("[A-Za-z0-9_.$]{1,128}").matches(symbol)) { line }
        val inner = body.substring(open + 1, close)
        val params = if (inner.isEmpty()) emptyList() else splitTop(inner).map { p ->
            val (name, type) = p.trim().split(": ", limit = 2)
            check(Regex(IDENT).matches(name) && typeOk(type)) { line }
            name to type
        }
        val returns = body.substring(close + 3)
        check(typeOk(returns)) { line }
        return Fn(symbol, params, returns)
    }

    private fun splitTop(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var from = 0
        s.forEachIndexed { i, c ->
            if (c == '<') depth++
            if (c == '>') depth--
            if (c == ',' && depth == 0) {
                out += s.substring(from, i)
                from = i + 1
            }
        }
        out += s.substring(from)
        return out
    }

    /** 判定整段文本并返回 fn 行；同时核对行的种类与不重复。 */
    fun check(text: String): List<Fn> {
        val lines = text.lines().filter { it.isNotEmpty() }
        assertEquals(lines.size, lines.toSet().size, "重复行")
        assertEquals(1, lines.count { it.startsWith("package ") })
        for (l in lines) {
            assertTrue(l.startsWith("package ") || l.startsWith("type ") || l.startsWith("cap ") || l.startsWith("fn "), l)
        }
        return lines.filter { it.startsWith("fn ") }.map { fn(it) }
    }
}

/** 把 jasper.import 类型映射成 JVM 类，用于对照真实方法。 */
private fun jvmClass(type: String, loader: ClassLoader): Class<*> {
    if (type.startsWith("Array<")) return java.lang.reflect.Array.newInstance(jvmClass(type.removePrefix("Array<").removeSuffix(">"), loader), 0).javaClass
    return when (type) {
        "int32" -> Int::class.javaPrimitiveType!!
        "int64" -> Long::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        "string", "string?" -> String::class.java
        "int32?" -> Int::class.javaObjectType
        "any" -> Any::class.java
        else -> loader.loadClass(type.removeSuffix("?").substringBefore('<'))
    }
}

/** Managed `.jaso` 产物：格式、接口、运行。 */
class JasoEmitTest {
    private fun hash(fingerprint: String): String =
        MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 8)

    private val keys: KeyPair by lazy { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() }
    private val publicKey: ByteArray by lazy { keys.public.encoded.let { it.copyOfRange(it.size - 32, it.size) } }

    @Test
    fun `the reader accepts the file and a signed copy passes every load step`() {
        assertEquals("ok", JasoRuntime.structural(Emitted.file))
        assertEquals("trust.unsigned", JasoRuntime.check(Emitted.file))
        val signed = signJaso(Emitted.file, keys)
        val conformance = JasoRuntime.loader.loadClass("std.jaso.JasoConformance")
        assertEquals("ok", JasoRuntime.call(conformance, "check", signed, publicKey))
        sample("api", Emitted.file, "ok")
        val tampered = signed.copyOf()
        val at = tampered.size - 200
        tampered[at] = (tampered[at].toInt() xor 1).toByte()
        resealJaso(tampered)
        assertEquals("signature.invalid", JasoRuntime.call(conformance, "check", tampered, publicKey))
    }

    @Test
    fun `header and manifest say managed jvm runtime`() {
        val b = ByteBuffer.wrap(Emitted.file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b.getShort(8).toInt())
        assertEquals(2, b.getShort(10).toInt(), "Managed")
        assertEquals(0, b.getShort(12).toInt(), "arch any")
        assertEquals(0, b.get(14).toInt(), "runtime")
        val m = Emitted.sections.manifest
        for (line in listOf(
            "core.name = jaso-demo-api", "core.version = 1.2.3", "core.abi = jvm.52", "core.stage = runtime",
            "core.runtime = >=0.1,<0.2", "core.lifecycle = plugin", "core.depends = jaso-base@>=1.0,<2.0",
            "core.requires = alloc,log", "core.description = demo module", "core.publisher = jasper.dev"
        )) {
            assertTrue(m.lines().contains(line), "$line\n$m")
        }
        assertEquals("core.exports = " + Emitted.sections.exportNames.joinToString(","), m.lines().first { it.startsWith("core.exports") })
    }

    @Test
    fun `the interface is exactly what the source declares public`() {
        val expected = EXPECTED_IMPORT.map {
            it.replace("HASH_INT", hash("int32")).replace("HASH_TWO", hash("int32,int32"))
        }.joinToString("\n", postfix = "\n")
        assertEquals(expected, Emitted.sections.importText)
        val fns = ImportGrammar.check(Emitted.sections.importText)
        assertEquals(fns.map { it.symbol }, Emitted.sections.exportNames)
        assertFalse(Emitted.sections.importText.contains("Hidden"))
        assertFalse(Emitted.sections.importText.contains("secret"))
        assertFalse(Emitted.sections.importText.contains("moduleOnly"))
        assertFalse(Emitted.sections.importText.contains("helper"))
        assertFalse(Emitted.sections.importText.contains("Base.new"), "abstract class has no exported constructor")
    }

    @Test
    fun `every declared signature exists on the JVM with the same shape`() {
        val loader = Emitted.loader
        for (fn in ImportGrammar.check(Emitted.sections.importText)) {
            val parts = fn.symbol.split('.')
            val member = parts.last().substringBefore('$')
            val owner = loader.loadClass(parts.dropLast(1).joinToString("."))
            val instance = fn.params.firstOrNull()?.first == "this"
            val params = fn.params.drop(if (instance) 1 else 0).map { jvmClass(it.second, loader) }.toTypedArray()
            if (member == "new") {
                assertEquals(fn.returns, owner.name, fn.symbol)
                owner.getConstructor(*params)
            } else {
                val method = owner.getMethod(member, *params)
                assertEquals(!instance, java.lang.reflect.Modifier.isStatic(method.modifiers), fn.symbol)
                assertEquals(jvmClass(fn.returns, loader), method.returnType, fn.symbol)
                if (instance) assertEquals(fn.params[0].second, owner.name, fn.symbol)
            }
        }
    }

    @Test
    fun `the image runs from the archive inside the file`() {
        val loader = Emitted.loader
        val add = loader.loadClass("demo.api.add").getMethod("add", Int::class.java, Int::class.java)
        assertEquals(5, add.invoke(null, 2, 3))
        val square = loader.loadClass("demo.api.Square")
        val four = square.getConstructor(Int::class.java).newInstance(4)
        assertEquals(16, square.getMethod("area").invoke(four))
        assertEquals("p:4", square.getMethod("tag", String::class.java).invoke(four, "p"))
        assertEquals("none:4", square.getMethod("tag", String::class.java).invoke(four, null))
        assertEquals(1, square.getMethod("area").invoke(square.getMethod("unit").invoke(null)))
        val total = loader.loadClass("demo.api.total").getMethod("total", IntArray::class.java)
        assertEquals(6L, total.invoke(null, intArrayOf(1, 2, 3)))
        val second = loader.loadClass("demo.api.Second")
        assertEquals(16, second.getMethod("area", square).invoke(second.getConstructor().newInstance(), four), "跨文件的类型")
        assertEquals(null, loader.loadClass("demo.api.maybe").getMethod("maybe", Int::class.javaObjectType).invoke(null, null))
    }

    @Test
    fun `the image holds module classes only at class version 52 and full verification`() {
        val classes = Emitted.sections.classes
        assertTrue(classes.keys.all { it.endsWith(".class") })
        assertTrue(classes.keys.none { it.startsWith("std/") }, "std 属于运行时")
        assertTrue(classes.keys.containsAll(listOf("demo/api/Square.class", "demo/api/Hidden.class", "demo/api/helper.class")), classes.keys.toString())
        for ((name, bytes) in classes) {
            val b = ByteBuffer.wrap(bytes)
            assertEquals(0xCAFEBABE.toInt(), b.getInt(0), name)
            assertEquals(52, b.getShort(6).toInt(), name)
        }
        val dir = Files.createTempDirectory(Paths.get("build", "tmp"), "jaso-verify")
        val jar = dir.resolve("code.zip")
        Files.write(jar, Emitted.sections.zip)
        val stdlib = JasoRuntime.loader as URLClassLoader
        val classpath = (stdlib.urLs.map { Paths.get(it.toURI()).toString() } + jar.toString()).joinToString(File.pathSeparator)
        val main = dir.resolve("LoadAll.java")
        Files.write(main, """
            public class LoadAll {
                public static void main(String[] names) throws Exception {
                    for (String n : names) Class.forName(n);
                }
            }
        """.trimIndent().toByteArray())
        val names = classes.keys.map { it.removeSuffix(".class").replace('/', '.') }
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, "-Xverify:all", "-cp", classpath, main.toString()) + names)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `output is byte identical across runs and source order`() {
        val again = emit("api-again", linkedMapOf("More.jas" to MORE_SOURCE, "Api.jas" to API_SOURCE), CONFORMING)
        assertTrue(Emitted.file.contentEquals(again))
    }

    @Test
    fun `a suspending function is exported with its declared signature and its frame stays internal`() {
        val source = """
            package demo.co;

            import std.Future;

            public class Waiter {
                public static function plusOne(f: Future<int32>): int32 {
                    var v: int32 = await f;
                    return v + 1;
                }
            }
        """.trimIndent()
        val file = emit("co", mapOf("Co.jas" to source), JasoModuleOptions("jaso-co", "0.1.0", publisher = "jasper.dev"))
        val s = JasoSections(file)
        assertEquals("ok", JasoRuntime.structural(file))
        sample("co", file, "ok")
        assertEquals(listOf("demo.co.Waiter.new", "demo.co.Waiter.plusOne"), s.exportNames)
        assertTrue(s.importText.contains("fn demo.co.Waiter.plusOne(f: std.Future<int32>): int32\n"), s.importText)
        assertTrue(s.classes.keys.any { it.contains("Coroutine") }, "帧类在镜像里: ${s.classes.keys}")
        assertFalse(s.importText.contains("Coroutine"), "帧类不是接口")
    }

    @Test
    fun `a module with nothing public still gives a valid file without an export table`() {
        val file = emit("empty", mapOf("E.jas" to "package demo.none;\nclass Inner { }\n"), JasoModuleOptions("jaso-empty", "0.0.1", publisher = "jasper.dev"))
        assertEquals("ok", JasoRuntime.structural(file))
        sample("empty", file, "ok")
        assertFalse(JasoSections(file).manifest.contains("core.exports"))
    }

    /** 期望拒绝：不写文件，诊断里含 [fragment]。 */
    private fun rejected(fragment: String, sources: Map<String, String>, options: JasoModuleOptions = CONFORMING) {
        val out = Paths.get("build", "tmp", "jaso-emit", "rejected.jaso")
        Files.deleteIfExists(out)
        val result = JasperCompiler().compileToJaso(sources, options, out)
        assertTrue(result is JasperCompiler.CompileResult.Error, "应被拒绝：$fragment")
        assertTrue(result.message.contains(fragment), "${result.message}\n应含 $fragment")
        assertFalse(Files.exists(out), "失败时不写文件")
    }

    private fun one(body: String): Map<String, String> = mapOf("M.jas" to "package demo.m;\n$body\n")

    @Test
    fun `shapes the interface cannot express are diagnosed`() {
        rejected("generic type 'Box'", one("public class Box<T> { }"))
        rejected("generic function", one("public function id<T>(x: T): T { return x; }"))
        rejected("nested type", one("public class A { public class B { } }"))
        rejected("public enum 'E'", one("public enum E { X, Y }"))
        rejected("public field", one("public class A { public var x: int32; }"))
        rejected("vararg parameter", one("public function f(xs: int32...): int32 { return 0; }"))
        rejected("which is not public", one("class Inner { }\npublic function f(x: Inner): int32 { return 0; }"))
        rejected("which is not public", one("class Inner { }\npublic function f(): Inner[] { return new Inner[0]; }"))
    }

    @Test
    fun `module shape and metadata are diagnosed before anything is written`() {
        rejected("no package", mapOf("M.jas" to "public class A { }\n"))
        rejected("several packages", mapOf("A.jas" to "package a;\npublic class A { }\n", "B.jas" to "package b;\npublic class B { }\n"))
        rejected("belongs to the runtime", mapOf("M.jas" to "package std.mine;\npublic class A { }\n"))
        rejected("Script unit", mapOf("M.jass" to "print(1);\n"))
        rejected("taken by the standard library", mapOf("List.jas" to "package a;\npublic class A { }\n"))
        rejected("at least one source", emptyMap())
        rejected("module name", one("public class A { }"), CONFORMING.copy(name = "Bad Name"))
        rejected("module version", one("public class A { }"), CONFORMING.copy(version = "1.2"))
        rejected("runtime range", one("public class A { }"), CONFORMING.copy(runtime = "0.1"))
        rejected("capability", one("public class A { }"), CONFORMING.copy(requires = listOf("Bad")))
        rejected("listed twice", one("public class A { }"), CONFORMING.copy(depends = listOf("a", "a@>=1.0")))
        rejected("lifecycle", one("public class A { }"), CONFORMING.copy(lifecycle = "forever"))
        rejected("description", one("public class A { }"), CONFORMING.copy(description = "two\nlines"))
    }

    @Test
    fun `export count limit is diagnosed`() {
        val body = (0..256).joinToString("\n") { "public function f$it(): int32 { return $it; }" }
        rejected("at most 256", one(body))
    }

    @Test
    fun `parameters the interface cannot express are diagnosed from the tree`() {
        // 默认实参当前没有函数形参语法，这些形态由 AST 直接构造。
        val withDefault = JasParameter("x", JasPrimitiveType("int32"), defaultValue = jasper.ast.JasIntLiteral(1L, "1"))
        val untyped = JasParameter("y", null)
        // 类型检查器拒绝未知类型，走到这里的未解析名字只有经 AST 才能构造；这是兜底。
        val elsewhere = JasParameter("z", JasNamedType("Elsewhere"))
        val errors = JasoExports.plan(listOf(JasSourceFile("p", listOf(
            JasFunction("f", listOf(withDefault), JasUnitType, null, listOf("public")),
            JasFunction("g", listOf(untyped), JasUnitType, null, listOf("public")),
            JasFunction("h", listOf(elsewhere), JasUnitType, null, listOf("public"))
        ))), emptyList()).errors.map { it.message }
        for (fragment in listOf("default value", "untyped parameter", "must be imported")) {
            assertTrue(errors.any { it.contains(fragment) }, "$fragment: $errors")
        }
    }

    @Test
    fun `format limits on names depth and size are diagnosed`() {
        fun fn(name: String, vararg params: JasParameter) = JasFunction(name, params.toList(), JasUnitType, null, listOf("public"))
        fun errors(vararg decls: JasDeclaration) = JasoExports.plan(listOf(JasSourceFile("p", decls.toList())), emptyList()).errors.map { it.message }

        // 符号上限 128 字节：下面 127 字节的能过，名字加一个字符后 129 字节就报。
        val fits = "a".repeat(62)
        assertEquals(127, "p.$fits.$fits".length)
        assertTrue(errors(fn("a".repeat(62))).isEmpty())
        assertTrue(errors(fn("a".repeat(63))).any { it.contains("is not 1..128 bytes") })
        assertTrue(errors(fn("名字")).any { it.contains("is not 1..128 bytes") })

        var deep: JasType = JasPrimitiveType("int32")
        repeat(8) { deep = JasArrayType(deep) }
        assertTrue(errors(fn("ok", JasParameter("x", deep))).isEmpty(), "嵌套 8 层是上限")
        assertTrue(errors(fn("bad", JasParameter("x", JasArrayType(deep)))).any { it.contains("nesting deeper") })

        assertTrue(errors(fn("f", JasParameter("名", JasPrimitiveType("int32")))).any { it.contains("parameter name") })
        assertTrue(errors(JasClass("类", listOf("public"), emptyList(), null, emptyList(), emptyList(), emptyList())).any { it.contains("type name") })

        // 导出没超 256，文本超 64 KiB。
        val many = List(30) { JasParameter("parameter_number_$it", JasNamedType("x.y.A_rather_long_type_name_$it")) }
        val big = (0 until 256).map { i -> fn("f$i", *many.toTypedArray()) }
        assertTrue(errors(*big.toTypedArray()).any { it.contains("jasper.import is limited") })
    }

    @Test
    fun `a binding that disagrees with the export table goes to the reference implementation as a negative control`() {
        // 读取器的结构判定不看绑定内容；这个样本单独放，供 JB-OS2 的第 6a 步确认判定确实生效。
        val options = JasoModuleOptions("jaso-negative", "1.0.0", publisher = "jasper.dev")
        val file = JasoWriter.assemble(
            JasoManifestText.render(options, listOf("p.A.f", "p.A.g")), mapOf("p/A.class" to byteArrayOf(1)),
            listOf("p.A.f", "p.A.g"), "package p\nfn p.A.f(): void\n"
        )
        assertEquals("ok", JasoRuntime.structural(file))
        val dir = Paths.get("build", "tmp", "jaso-emit-negative").toFile().apply { mkdirs() }
        File(dir, "missing-fn.jaso").writeBytes(file)
    }

    @Test
    fun `identical overloads after type rendering are reported instead of colliding`() {
        val a = JasFunction("f", listOf(JasParameter("x", JasNullableType(JasPrimitiveType("int32")))), JasUnitType, null, listOf("public"))
        val result = JasoExports.plan(listOf(JasSourceFile("p", listOf(a, a))), emptyList())
        assertTrue(result.errors.any { it.message.contains("share the symbol") }, result.errors.toString())
    }

    private val pool = listOf<JasType>(
        JasPrimitiveType("int32"), JasPrimitiveType("int64"), JasPrimitiveType("bool"), JasStringType,
        JasNullableType(JasStringType), JasNullableType(JasPrimitiveType("int32")), JasArrayType(JasPrimitiveType("int32")),
        JasArrayType(JasArrayType(JasStringType)), JasNamedType("Imported"), JasNamedType("x.y.Full"),
        JasNamedType("Imported", listOf(JasPrimitiveType("int32"), JasNullableType(JasNamedType("T0")))),
        JasNullableType(JasArrayType(JasNamedType("T0")))
    )

    /** 随机接口：若干公开类 + 公开函数 + 非公开干扰项，含重载。 */
    private fun randomAst(r: Random, pkg: String): JasSourceFile {
        fun type() = pool[r.nextInt(pool.size)]
        fun params(): List<JasParameter> = List(r.nextInt(4)) { JasParameter("p$it", type()) }
        val decls = mutableListOf<JasDeclaration>()
        decls += JasClass("T0", listOf("public"), emptyList(), null, emptyList(), emptyList(), emptyList())
        repeat(r.nextInt(4)) { c ->
            val members = List(r.nextInt(5)) { m ->
                val name = "m" + r.nextInt(3)
                // 同名方法的第一个形参类型取自序号，保证重载之间形参表不同。
                val distinct = JasParameter("d", JasNamedType("x.y.D$m"))
                val mods = if (r.nextInt(4) == 0) listOf("private") else if (r.nextBoolean()) listOf("public", "static") else listOf("public")
                JasFunction(name, listOf(distinct) + params(), type(), null, mods)
            }
            val ctors = if (r.nextBoolean()) emptyList() else listOf(JasConstructor(listOf("public"), params(), null, null))
            decls += JasClass("C$c", if (r.nextInt(5) == 0) emptyList() else listOf("public"), emptyList(), null, emptyList(), members, ctors)
        }
        if (r.nextBoolean()) {
            decls += JasInterface("I", listOf("public"), emptyList(), emptyList(), listOf(JasFunction("call", params(), JasUnitType, null, emptyList())))
        }
        repeat(r.nextInt(4)) { decls += JasFunction("top$it", params(), type(), null, listOf("public")) }
        return JasSourceFile(pkg, decls, listOf(JasImport("a.b.Imported", null, false, null)))
    }

    @Test
    fun `random interfaces give grammar valid text that matches the export table`() {
        val r = Random(20261156)
        repeat(600) { round ->
            val ast = randomAst(r, "gen.p$round")
            val result = JasoExports.plan(listOf(ast), listOf("log"))
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            val module: JasoInterface = result.module!!
            val fns = ImportGrammar.check(module.importText)
            assertEquals(module.exports, fns.map { it.symbol })
            assertEquals(module.exports.sorted(), module.exports)
            assertEquals(module.exports.size, module.exports.toSet().size)
            assertEquals(module.importText, JasoExports.plan(listOf(ast.copy(declarations = ast.declarations.reversed())), listOf("log")).module!!.importText, "声明顺序不影响结果")
            // 公开成员都在表里，私有的都不在。
            for (decl in ast.declarations.filterIsInstance<JasClass>()) {
                for (m in decl.members.filterIsInstance<JasFunction>()) {
                    val prefix = "gen.p$round.${decl.name}.${m.name}"
                    val found = module.exports.any { it == prefix || it.startsWith("$prefix$") }
                    if ("public" in decl.modifiers && "public" in m.modifiers) assertTrue(found, prefix)
                    if ("public" !in decl.modifiers) assertFalse(found, prefix)
                }
            }
            val options = JasoModuleOptions("jaso-gen$round", "1.0.$round", publisher = "jasper.dev", requires = listOf("log"))
            val file = JasoWriter.assemble(JasoManifestText.render(options, module.exports), mapOf("gen/X.class" to byteArrayOf(1, 2, 3)), module.exports, module.importText)
            assertEquals("ok", JasoRuntime.structural(file), module.importText)
            val s = JasoSections(file)
            if (module.exports.isNotEmpty()) assertEquals(module.exports, s.exportNames)
            assertEquals(module.importText, s.importText)
            if (round % 3 == 0) sample("gen$round", file, "ok")
        }
    }

    private val goodNames = listOf("a", "jaso-x", "a.b_c-d", "z".repeat(64))
    private val badNames = listOf("", "A", "a b", "z".repeat(65), "é")
    private val goodVersions = listOf("0.0.0", "1.2.3", "999999999.0.1")
    private val badVersions = listOf("", "1.2", "1.2.3.4", "1.x.3", "1234567890.0.0")
    private val goodRanges = listOf(">=0.1", ">=0.1,<0.2", "=1.2.3", ">1.0,<2.0,<=1.5,>=1.1")
    private val badRanges = listOf("", "0.1", ">=", ">=1", ">=1.0,", ">1.0,<2.0,<=1.5,>=1.1,=1.2", "~1.0")
    private val goodCaps = listOf("log", "a.b_c", "z".repeat(64))
    private val badCaps = listOf("", "Log", "a-b", "z".repeat(65))

    private fun <T> pick(r: Random, good: List<T>, bad: List<T>, badChance: Int): T =
        if (r.nextInt(badChance) == 0) bad[r.nextInt(bad.size)] else good[r.nextInt(good.size)]

    @Test
    fun `metadata validation agrees with the reader in both directions`() {
        val r = Random(20261157)
        val classes = mapOf("gen/X.class" to byteArrayOf(1))
        var accepted = 0
        var refused = 0
        repeat(3000) {
            val depends = List(r.nextInt(3)) {
                val name = pick(r, goodNames, badNames, 12)
                if (r.nextBoolean()) name else name + "@" + pick(r, goodRanges, badRanges, 12)
            }
            val o = JasoModuleOptions(
                name = pick(r, goodNames, badNames, 10),
                version = pick(r, goodVersions, badVersions, 10),
                publisher = if (r.nextBoolean()) null else pick(r, goodNames, badNames, 10),
                runtime = pick(r, goodRanges, badRanges, 10),
                depends = depends,
                requires = List(r.nextInt(3)) { pick(r, goodCaps, badCaps, 12) },
                lifecycle = listOf(null, "plugin", "system", "other")[r.nextInt(4)]
            )
            val ok = JasoManifestText.validate(o).isEmpty()
            val reader = JasoRuntime.structural(JasoWriter.assemble(JasoManifestText.render(o, listOf("p.A.f")), classes, listOf("p.A.f"), "package p\nfn p.A.f(): void\n"))
            // 写出器只查单个取值；读取器还会拒绝的只有写出器同样拒绝的。空能力、空依赖列表项的
            // 切分差异不算不一致：写出器按项校验，所以更严，见下方断言的方向。
            if (ok) {
                assertEquals("ok", reader, o.toString())
                accepted++
            } else {
                assertTrue(reader != "ok" || o.requires.any { it.isEmpty() } || o.depends.any { it.startsWith("@") || it.isEmpty() }, "写出器拒绝而读取器接受：$o")
                refused++
            }
        }
        assertTrue(accepted > 300 && refused > 300, "两类都要覆盖到：$accepted / $refused")
    }

    @Test
    fun `the stored archive reads back through the JDK and is independent of map order`() {
        val r = Random(20261158)
        repeat(200) { round ->
            val entries = LinkedHashMap<String, ByteArray>()
            repeat(1 + r.nextInt(12)) { i ->
                val data = ByteArray(r.nextInt(2000)).also { r.nextBytes(it) }
                val dir = listOf("a/", "a/b/", "", "汉/")[r.nextInt(4)]
                entries["${dir}C$i.class"] = data
            }
            val zip = JasoWriter.storedZip(entries)
            val shuffled = entries.entries.shuffled(r).associateTo(LinkedHashMap()) { it.key to it.value }
            assertTrue(zip.contentEquals(JasoWriter.storedZip(shuffled)))
            val read = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    read[e.name] = z.readBytes()
                }
            }
            assertEquals(entries.keys.sorted(), read.keys.toList())
            for ((k, v) in entries) assertTrue(v.contentEquals(read[k]), "$round $k")
            val file = Files.createTempFile(Paths.get("build", "tmp").also { Files.createDirectories(it) }, "zip", ".zip")
            try {
                Files.write(file, zip)
                ZipFile(file.toFile()).use { z ->
                    assertEquals(entries.size, z.size())
                    for ((k, v) in entries) {
                        val e = z.getEntry(k)
                        assertEquals(0, e.method, "只存储不压缩")
                        assertEquals(CRC32().apply { update(v) }.value, e.crc)
                        assertTrue(v.contentEquals(z.getInputStream(e).readBytes()))
                    }
                }
            } finally {
                Files.delete(file)
            }
        }
        assertFailsWith<IllegalArgumentException> { JasoWriter.storedZip(emptyMap()) }
    }

    @Test
    fun `the command line writes a file and maps failures to exit codes`() {
        val dir: Path = Files.createTempDirectory(Paths.get("build", "tmp").also { Files.createDirectories(it) }, "jaso-cli")
        val src = dir.resolve("Cli.jas")
        Files.write(src, "package demo.cli;\npublic function hello(): string { return \"hi\"; }\n".toByteArray())
        val out = dir.resolve("sub").resolve("cli.jaso")
        val args = listOf(
            "--emit", "jaso", "-o", out.toString(), "--name", "jaso-cli", "--version", "3.2.1", "--publisher", "jasper.dev",
            "--depends", "jaso-base", "--requires", "log", "--requires", "alloc", "--description", "from the command line", src.toString()
        )
        assertEquals(0, emitJasoMain(args))
        val bytes = Files.readAllBytes(out)
        assertEquals("ok", JasoRuntime.structural(bytes))
        sample("cli", bytes, "ok")
        val s = JasoSections(bytes)
        assertTrue(s.manifest.contains("core.requires = alloc,log"))
        assertEquals(listOf("demo.cli.hello.hello"), s.exportNames)
        assertEquals(2, emitJasoMain(listOf("--emit", "jaso")), "缺参数")
        assertEquals(2, emitJasoMain(args.map { if (it == "--publisher") "--unknown" else it }), "未知选项")
        assertEquals(2, emitJasoMain(listOf("--emit", "class", "-o", "x", "--name", "a", "--version", "1.0.0", src.toString())), "未知产物")
        assertEquals(2, emitJasoMain(args.dropLast(1) + "no-such-file.jas"), "源文件不存在")
        Files.write(src, "package demo.cli;\npublic function broken(: {\n".toByteArray())
        assertEquals(1, emitJasoMain(args), "编译失败")
    }

    @Test
    fun `samples outside the conformance context get the expected load codes`() {
        val src = mapOf("S.jas" to "package demo.s;\npublic class S { }\n")
        val conformance = JasoRuntime.loader.loadClass("std.jaso.JasoConformance")
        val cases = listOf(
            Triple("outside-namespace", JasoModuleOptions("demo-s", "1.0.0", publisher = "jasper.dev"), "trust.namespace"),
            Triple("capability", JasoModuleOptions("jaso-s1", "1.0.0", publisher = "jasper.dev", requires = listOf("net")), "capability.denied"),
            Triple("dependency", JasoModuleOptions("jaso-s2", "1.0.0", publisher = "jasper.dev", depends = listOf("jaso-none")), "dependency.unsatisfied"),
            Triple("runtime", JasoModuleOptions("jaso-s3", "1.0.0", publisher = "jasper.dev", runtime = ">=9.0"), "dependency.runtime")
        )
        for ((name, options, expected) in cases) {
            val file = emit(name, src, options)
            assertEquals(expected, JasoRuntime.call(conformance, "check", signJaso(file, keys), publicKey), name)
            sample(name, file, expected)
        }
    }
}
