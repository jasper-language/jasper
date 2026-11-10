// ============================================================================
// BuiltinResultWideningTest.kt —— 返回 int32 的内建在 int64 / float64 目标位置的拓宽（Q231）
// ============================================================================
// 缺陷：`var len: int64 = s.length();` 加载时 VerifyError。根因是检查器把调用形式
//   `s.length()` 放给「函数值」兜底定为 any，翻译成 JVM `String.length()`：IR 类型不是
//   int32（后端按 IR 类型决定是否补 I2L），而且算出来的是 UTF-16 码元数，与语言层
//   `.length` 的码点数（§11.3）是两个答案。修法：`length` 在 string / 数组上只是属性，
//   调用形式编译期拒绝；属性形式与其余 int32 内建（`byteLength()`、`codePointAt(i)`、
//   数组 `.length`、集合 `size()`）在局部、赋值、字段、返回、实参（静态 / 成员 / 构造器）、
//   数组元素各 int64 / float64 目标位置都必须真实加载执行并得到正确值。同族第二个根因：
//   静态调用的实参只做装箱与引用宽化、不做数值拓宽，`W.takeL(s.length)` 同样 VerifyError；
//   改为与成员调用共用 emitCallArgumentConversion。期望值一律由 Kotlin 独立计算。
//   性质登记：LANG-BUILTIN-WIDEN-01。
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinResultWideningTest {

    private fun outDir(name: String): Path {
        val dir = Paths.get("build", "scratch", "builtin-widening", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    /** 编译整段类源码，加载 `p.W` 并返回按名字调用静态方法的函数。 */
    private fun <T> withClass(name: String, body: String, block: ((String, Array<Any?>) -> Any?) -> T): T {
        val source = "package p;\npublic class W {\n$body\n}\n"
        val out = outDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val cls = loader.loadClass("p.W")
            block { method, args -> cls.methods.first { it.name == method }.invoke(null, *args) }
        }
    }

    private fun codePoints(s: String): Int = s.codePointCount(0, s.length)
    private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size
    private fun codePointAt(s: String, i: Int): Int = s.codePointAt(s.offsetByCodePoints(0, i))

    /** 每个内建 × 每种目标位置，int64 与 float64 两种宽目标。 */
    private val positions = """
        public var lf: int64;
        public var df: float64;
        public static function takeL(v: int64): int64 { return v; }
        public static function takeD(v: float64): float64 { return v; }
        public constructor() { }
        public constructor(l: int64, d: float64) { this.lf = l; this.df = d; }
        public function viaMember(v: int64): int64 { return v; }

        public static function lenLocal(s: string): int64 { var n: int64 = s.length; return n; }
        public static function lenAssign(s: string): int64 { var n: int64 = -1L; n = s.length; return n; }
        public static function lenField(s: string): int64 { var w: W = new W(); w.lf = s.length; return w.lf; }
        public static function lenReturn(s: string): int64 { return s.length; }
        public static function lenArg(s: string): int64 { return W.takeL(s.length); }
        public static function lenElem(s: string): int64 { var a: int64[] = new int64[2]; a[1] = s.length; return a[1]; }
        public static function lenCtor(s: string): int64 { var w: W = new W(s.length, 0.0); return w.lf; }
        public static function lenMember(s: string): int64 { var w: W = new W(); return w.viaMember(s.length); }
        public static function lenCtorD(s: string): float64 { var w: W = new W(0L, s.length); return w.df; }
        public static function lenLocalD(s: string): float64 { var d: float64 = s.length; return d; }
        public static function lenFieldD(s: string): float64 { var w: W = new W(); w.df = s.length; return w.df; }
        public static function lenReturnD(s: string): float64 { return s.length; }
        public static function lenArgD(s: string): float64 { return W.takeD(s.length); }
        public static function lenElemD(s: string): float64 { var a: float64[] = new float64[1]; a[0] = s.length; return a[0]; }

        public static function bytesLocal(s: string): int64 { var n: int64 = s.byteLength(); return n; }
        public static function bytesAssign(s: string): int64 { var n: int64 = 0L; n = s.byteLength(); return n; }
        public static function bytesField(s: string): int64 { var w: W = new W(); w.lf = s.byteLength(); return w.lf; }
        public static function bytesReturn(s: string): int64 { return s.byteLength(); }
        public static function bytesArg(s: string): int64 { return W.takeL(s.byteLength()); }
        public static function bytesElem(s: string): int64 { var a: int64[] = new int64[1]; a[0] = s.byteLength(); return a[0]; }
        public static function bytesReturnD(s: string): float64 { return s.byteLength(); }
        public static function bytesArgD(s: string): float64 { return W.takeD(s.byteLength()); }

        public static function cpLocal(s: string, i: int32): int64 { var n: int64 = s.codePointAt(i); return n; }
        public static function cpAssign(s: string, i: int32): int64 { var n: int64 = 0L; n = s.codePointAt(i); return n; }
        public static function cpField(s: string, i: int32): int64 { var w: W = new W(); w.lf = s.codePointAt(i); return w.lf; }
        public static function cpReturn(s: string, i: int32): int64 { return s.codePointAt(i); }
        public static function cpArg(s: string, i: int32): int64 { return W.takeL(s.codePointAt(i)); }
        public static function cpElem(s: string, i: int32): int64 { var a: int64[] = new int64[1]; a[0] = s.codePointAt(i); return a[0]; }
        public static function cpLocalD(s: string, i: int32): float64 { var d: float64 = s.codePointAt(i); return d; }
        public static function cpMember(s: string, i: int32): int64 { var w: W = new W(); return w.viaMember(s.codePointAt(i)); }
        public static function cpCtorD(s: string, i: int32): float64 { var w: W = new W(0L, s.codePointAt(i)); return w.df; }
        public static function cpElemD(s: string, i: int32): float64 { var a: float64[] = new float64[1]; a[0] = s.codePointAt(i); return a[0]; }

        public static function arrLocal(k: int32): int64 { var xs: int32[] = new int32[k]; var n: int64 = xs.length; return n; }
        public static function arrField(k: int32): int64 { var xs: int32[] = new int32[k]; var w: W = new W(); w.lf = xs.length; return w.lf; }
        public static function arrReturn(k: int32): int64 { var xs: int32[] = new int32[k]; return xs.length; }
        public static function arrArg(k: int32): int64 { var xs: int32[] = new int32[k]; return W.takeL(xs.length); }
        public static function arrElem(k: int32): int64 { var xs: int32[] = new int32[k]; var a: int64[] = new int64[1]; a[0] = xs.length; return a[0]; }
        public static function arrReturnD(k: int32): float64 { var xs: int32[] = new int32[k]; return xs.length; }

        public static function sizeLocal(k: int32): int64 {
            var l: MutableList<int32> = new MutableList<int32>();
            for (var i: int32 = 0; i < k; i++) { l.add(i); }
            var n: int64 = l.size();
            return n;
        }
        public static function sizeField(k: int32): int64 {
            var l: MutableList<int32> = new MutableList<int32>();
            for (var i: int32 = 0; i < k; i++) { l.add(i); }
            var w: W = new W();
            w.lf = l.size();
            return w.lf;
        }
        public static function sizeReturn(k: int32): int64 {
            var l: MutableList<int32> = new MutableList<int32>();
            for (var i: int32 = 0; i < k; i++) { l.add(i); }
            return l.size();
        }
        public static function sizeArg(k: int32): int64 {
            var l: MutableList<int32> = new MutableList<int32>();
            for (var i: int32 = 0; i < k; i++) { l.add(i); }
            return W.takeL(l.size());
        }
        public static function sizeElemD(k: int32): float64 {
            var l: MutableList<int32> = new MutableList<int32>();
            for (var i: int32 = 0; i < k; i++) { l.add(i); }
            var a: float64[] = new float64[1];
            a[0] = l.size();
            return a[0];
        }
    """.trimIndent()

    private val lenNames = listOf("lenLocal", "lenAssign", "lenField", "lenReturn", "lenArg", "lenElem", "lenCtor", "lenMember")
    private val lenNamesD = listOf("lenLocalD", "lenFieldD", "lenReturnD", "lenArgD", "lenElemD", "lenCtorD")
    private val bytesNames = listOf("bytesLocal", "bytesAssign", "bytesField", "bytesReturn", "bytesArg", "bytesElem")
    private val bytesNamesD = listOf("bytesReturnD", "bytesArgD")
    private val cpNames = listOf("cpLocal", "cpAssign", "cpField", "cpReturn", "cpArg", "cpElem", "cpMember")
    private val cpNamesD = listOf("cpLocalD", "cpElemD", "cpCtorD")
    private val arrNames = listOf("arrLocal", "arrField", "arrReturn", "arrArg", "arrElem")
    private val sizeNames = listOf("sizeLocal", "sizeField", "sizeReturn", "sizeArg")

    /** 逐个断言一组样本：每个字符串、每个位置都与 Kotlin 独立算出的值一致。 */
    private fun checkStrings(call: (String, Array<Any?>) -> Any?, samples: List<String>, cpIndex: (String) -> Int?) {
        for (s in samples) {
            val n = codePoints(s).toLong()
            val b = utf8(s).toLong()
            lenNames.forEach { assertEquals(n, call(it, arrayOf(s)), "$it(\"$s\")") }
            lenNamesD.forEach { assertEquals(n.toDouble(), call(it, arrayOf(s)), "$it(\"$s\")") }
            bytesNames.forEach { assertEquals(b, call(it, arrayOf(s)), "$it(\"$s\")") }
            bytesNamesD.forEach { assertEquals(b.toDouble(), call(it, arrayOf(s)), "$it(\"$s\")") }
            val i = cpIndex(s) ?: continue
            val cp = codePointAt(s, i).toLong()
            cpNames.forEach { assertEquals(cp, call(it, arrayOf(s, i)), "$it(\"$s\", $i)") }
            cpNamesD.forEach { assertEquals(cp.toDouble(), call(it, arrayOf(s, i)), "$it(\"$s\", $i)") }
        }
    }

    private fun checkCounts(call: (String, Array<Any?>) -> Any?, counts: List<Int>) {
        for (k in counts) {
            arrNames.forEach { assertEquals(k.toLong(), call(it, arrayOf(k)), "$it($k)") }
            assertEquals(k.toDouble(), call("arrReturnD", arrayOf(k)), "arrReturnD($k)")
            sizeNames.forEach { assertEquals(k.toLong(), call(it, arrayOf(k)), "$it($k)") }
            assertEquals(k.toDouble(), call("sizeElemD", arrayOf(k)), "sizeElemD($k)")
        }
    }

    /** 固定样本：空串、ASCII、BMP 中文、补充平面 emoji（码点数 ≠ UTF-16 码元数）。 */
    @Test
    fun `int32 builtins widen at every int64 and float64 target position`() {
        withClass("fixed", positions) { call ->
            checkStrings(call, listOf("", "abc", "你好", "a😀b", "😀😀"), cpIndex = { s ->
                if (codePoints(s) == 0) null else codePoints(s) - 1
            })
            checkCounts(call, listOf(0, 1, 7))
        }
    }

    /** 固定种子随机：任意码点（含补充平面、排除代理项）组成的字符串，随机码点索引与容器大小。 */
    @Test
    fun `random strings and sizes agree with kotlin at every position`() {
        val random = Random(20261231)
        val samples = List(25) {
            val count = random.nextInt(0, 12)
            buildString {
                repeat(count) {
                    var cp: Int
                    do { cp = random.nextInt(0x20, 0x110000) } while (cp in 0xD800..0xDFFF)
                    appendCodePoint(cp)
                }
            }
        }
        withClass("random", positions) { call ->
            checkStrings(call, samples, cpIndex = { s ->
                val n = codePoints(s)
                if (n == 0) null else random.nextInt(0, n)
            })
            checkCounts(call, List(10) { random.nextInt(0, 40) })
        }
    }

    /** 调用形式 `s.length()` / `arr.length()` 编译期拒绝，诊断指向属性写法，且不产 class。 */
    @Test
    fun `length call form is rejected for string and array`() {
        val cases = mapOf(
            "string-local" to ("string" to "public static function f(s: string): int64 { var len: int64 = s.length(); return len; }"),
            "string-return" to ("string" to "public static function f(s: string): int32 { return s.length(); }"),
            "array-local" to ("array" to "public static function f(): int64 { var xs: int32[] = new int32[3]; var n: int64 = xs.length(); return n; }")
        )
        for ((name, pair) in cases) {
            val (kind, body) = pair
            val out = outDir("reject-$name")
            val result = JasperCompiler().compileToDirWithIr("package p;\npublic class W {\n$body\n}\n", out)
            val error = result as? JasperCompiler.CompileResult.Error
            assertNotNull(error, "expected rejection for $name, got $result")
            assertTrue(
                error.message.contains("'length' of a $kind is a property, not a method"),
                "diagnostic for $name should name the property form, got: ${error.message}"
            )
            val classes = Files.walk(out).use { st -> st.filter { it.toString().endsWith(".class") }.count() }
            assertEquals(0L, classes, "no class files for rejected $name")
        }
    }
}
