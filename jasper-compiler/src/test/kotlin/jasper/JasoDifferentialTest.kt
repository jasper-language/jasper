// ============================================================================
// JasoDifferentialTest.kt —— Jasper `.jaso` 读取器对参考实现的差分与性质测试（JB-L1）
// ============================================================================
// 职责：
//   - 差分：verification/jaso-diff 用 Rust 参考实现判定一批变异输入，期望表入库为
//     jaso-diff-*.tsv.gz；这里逐条复现输入，要求错误码、解析出的清单内容、验签结论完全相同。
//   - 性质：固定种子的随机字节不会让读取器抛 JasoError 以外的异常；UTF-8 判定与 JDK 严格解码一致。
//   - JVM 替代证据：全部 class 版本 52，子 JVM 全量字节码校验；并发调用无共享状态。
//   - Jazzer：覆盖引导 fuzz，回归模式跑入库语料，JAZZER_FUZZ=1 时进入覆盖引导。
// 重新生成期望表的方法见 verification/jaso-diff。
// ============================================================================

package jasper

import com.code_intelligence.jazzer.junit.FuzzTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 经反射访问读取器内部对象的小工具。 */
private object JasoProbe {
    private val loader get() = JasoRuntime.loader
    private val errorClass by lazy { loader.loadClass("std.jaso.JasoError") }
    private val manifestClass by lazy { loader.loadClass("std.jaso.JasoManifest") }
    private val versionClass by lazy { loader.loadClass("std.jaso.JasoVersion") }
    private val ed25519 by lazy { loader.loadClass("std.jaso.Ed25519") }
    private val probes by lazy {
        listOf(0L to 1L, 1L to 2L, 2L to 0L).map { (major, minor) ->
            versionClass.getConstructor(Long::class.java, Long::class.java, Long::class.java).newInstance(major, minor, 0L)
        }
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private fun method(o: Any, name: String, vararg args: Any?): Any? =
        o.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(o, *args)

    private fun strings(o: Any?): String =
        (0 until method(o!!, "size") as Int).joinToString(";") { method(o, "get", it) as String }

    private fun range(r: Any?): String =
        if (r == null) "*" else probes.joinToString("") { if (method(r, "contains", it) as Boolean) "1" else "0" }

    /** 清单：错误码，或与 Rust 工具同格式的规范化转储。 */
    fun manifest(text: ByteArray): String {
        val m = try {
            JasoRuntime.call(manifestClass, "parse", text, 0, text.size)!!
        } catch (e: Throwable) {
            if (!errorClass.isInstance(e)) throw e
            return method(e, "code") as String
        }
        val version = field(m, "version")!!
        val deps = field(m, "dependencies") as Array<*>
        val depText = (0 until field(m, "dependencyCount") as Int).joinToString(";") { i ->
            "${field(deps[i]!!, "name")}=${range(field(deps[i]!!, "range"))}"
        }
        val stage = listOf("runtime", "boot", "build")[field(m, "stage") as Int]
        return "ok|${field(m, "name")}|${field(version, "major")}.${field(version, "minor")}.${field(version, "patch")}|" +
            "${field(m, "abi")}|$stage|${field(m, "lifecycle")}|${field(m, "host") ?: "~"}|" +
            "${hex(((field(m, "description") ?: "~") as String).toByteArray())}|${field(m, "publisher") ?: "~"}|" +
            "${range(field(m, "runtime"))}|${strings(field(m, "requires"))}|${strings(field(m, "exports"))}|$depText"
    }

    fun validKey(key: ByteArray): Boolean = JasoRuntime.call(ed25519, "validKey", key) as Boolean

    fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        JasoRuntime.call(ed25519, "verifyStrict", key, message, signature) as Boolean

    /** 只把清单解析中的 JasoError 当正常结果；其它异常向外抛。 */
    fun parseManifestOnly(text: ByteArray) {
        try {
            JasoRuntime.call(manifestClass, "parse", text, 0, text.size)
        } catch (e: Throwable) {
            if (!errorClass.isInstance(e)) throw e
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** 重算 `.jaso` 头部的完整性摘要。 */
internal fun resealJaso(b: ByteArray) {
    val sha = MessageDigest.getInstance("SHA-256")
    sha.update(b, 0, 40)
    sha.update(ByteArray(32))
    sha.update(b, 72, b.size - 72)
    sha.digest().copyInto(b, 40)
}

/** 差分表与性质测试。 */
class JasoDifferentialTest {
    private fun table(name: String): List<List<String>> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/jaso-diff-$name.tsv.gz")) { name }
        return GZIPInputStream(stream).bufferedReader().use { r -> r.readLines().filter { it.isNotEmpty() }.map { it.split('\t') } }
    }

    private fun report(kind: String, total: Int, failures: List<String>) {
        assertTrue(failures.isEmpty(), "$kind: ${failures.size}/$total 不一致\n" + failures.take(20).joinToString("\n"))
    }

    @Test
    fun `mutated vectors give the same result as the reference implementation`() {
        val rows = table("files")
        assertEquals(6000, rows.size)
        val originals = HashMap<String, ByteArray>()
        val failures = rows.mapIndexedNotNull { line, row ->
            val (name, resized, writes, sealed, sig) = row
            val code = row[5]
            var b = originals.getOrPut(name) { File(JasoRuntime.vectorDir, name).readBytes() }.copyOf()
            if (writes != "-") {
                for (w in writes.split(',')) {
                    val (off, v) = w.split(':')
                    b[off.toInt()] = v.toInt().toByte()
                }
            }
            if (resized != "-") {
                val len = resized.toInt()
                b = b.copyOf(len)
                if (len >= 32) ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28, len)
            }
            if (sig != "-") {
                val (at, value) = sig.split(':')
                JasoRuntime.hex(value).copyInto(b, at.toInt() + 40)
            }
            if (sealed == "sealed") resealJaso(b)
            val got = JasoRuntime.check(b)
            if (got == code) null else "line ${line + 1} ($name $writes): reference $code, reader $got"
        }
        report("files", rows.size, failures)
        // 差分表确实走到了结构读取之后：至少这些装载码出现过，否则表本身失去意义
        val seen = rows.map { it[5] }.toSet()
        assertTrue(seen.containsAll(setOf("ok", "signature.invalid", "platform.mismatch", "dependency.unsatisfied", "capability.denied")), "$seen")
    }

    @Test
    fun `random manifests in whole signed files give the same load result`() {
        val rows = table("load")
        assertEquals(600, rows.size)
        report("load", rows.size, rows.mapIndexedNotNull { line, (file, code) ->
            val got = JasoRuntime.check(JasoRuntime.hex(file))
            if (got == code) null else "line ${line + 1}: reference $code, reader $got"
        })
        assertTrue(rows.map { it[1] }.toSet().containsAll(setOf("ok", "trust.unsigned", "trust.namespace", "dependency.runtime", "capability.denied")))
    }

    @Test
    fun `boundary cases on each rule match the reference`() {
        val rows = table("targeted")
        assertEquals(20, rows.size)
        report("targeted", rows.size, rows.mapIndexedNotNull { line, (file, code) ->
            val got = JasoRuntime.check(JasoRuntime.hex(file))
            if (got == code) null else "line ${line + 1}: reference $code, reader $got"
        })
        // 每组边界两侧都要真的落在不同结果上，表才有区分力
        val seen = rows.map { it[1] }.toSet()
        assertTrue(seen.containsAll(setOf("ok", "image.size", "image.reloc_range", "image.code", "extension.malformed", "extension.critical", "extension.required", "image.index", "signature.section")), "$seen")
    }

    @Test
    fun `platform mismatch reports the same field and values`() {
        val rows = table("platforms")
        assertEquals(8, rows.size)
        val cls = JasoRuntime.loader.loadClass("std.jaso.JasoConformance")
        report("platforms", rows.size, rows.mapIndexedNotNull { line, row ->
            val expected = if (row[1] == "platform.mismatch") row.drop(1).joinToString("\t") else row[1]
            val got = JasoRuntime.call(cls, "detail", JasoRuntime.hex(row[0]), JasoRuntime.devKey) as String
            if (got == expected) null else "line ${line + 1}: reference $expected, reader $got"
        })
        assertEquals(setOf("isa", "isa_ext", "target", "abi", "float_abi"), rows.filter { it[1] == "platform.mismatch" }.map { it[2] }.toSet())
    }

    @Test
    fun `manifest parsing matches the reference in code and content`() {
        val rows = table("manifests")
        assertEquals(3000, rows.size)
        report("manifests", rows.size, rows.mapIndexedNotNull { line, (text, expected) ->
            val got = JasoProbe.manifest(JasoRuntime.hex(text))
            if (got == expected) null else "line ${line + 1}: $text\n  reference $expected\n  reader    $got"
        })
    }

    @Test
    fun `strict signature verification matches ed25519-dalek`() {
        val rows = table("signatures")
        assertTrue(rows.size > 1200)
        report("signatures", rows.size, rows.mapIndexedNotNull { line, row ->
            val key = JasoRuntime.hex(row[0])
            val keyOk = JasoProbe.validKey(key)
            val verdict = JasoProbe.verify(key, JasoRuntime.hex(row[1]), JasoRuntime.hex(row[2]))
            if (keyOk.toString() == row[3] && verdict.toString() == row[4]) null
            else "line ${line + 1}: key ${row[0]} sig ${row[2]} reference ${row[3]}/${row[4]}, reader $keyOk/$verdict"
        })
        assertTrue(rows.any { it[4] == "true" } && rows.any { it[3] == "false" })
    }

    @Test
    fun `verification agrees with the JDK provider on honest and tampered signatures`() {
        val random = java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(20261151L) }
        val generator = java.security.KeyPairGenerator.getInstance("Ed25519").apply { initialize(255, random) }
        val rng = Random(20261151)
        repeat(40) { round ->
            val pair = generator.generateKeyPair()
            val key = pair.public.encoded.takeLast(32).toByteArray()
            val message = rng.nextBytes(rng.nextInt(200))
            val signature = java.security.Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(message)
                sign()
            }
            assertTrue(JasoProbe.verify(key, message, signature), "round $round")
            val tampered = signature.copyOf().also { it[rng.nextInt(64)] = (it[rng.nextInt(64)] + 1).toByte() }
            if (!tampered.contentEquals(signature)) {
                val jdk = java.security.Signature.getInstance("Ed25519").run {
                    initVerify(pair.public)
                    update(message)
                    try { verify(tampered) } catch (_: java.security.SignatureException) { false }
                }
                assertEquals(jdk, JasoProbe.verify(key, message, tampered), "round $round")
            }
        }
    }

    @Test
    fun `random bytes only ever produce a stable code`() {
        val rng = Random(20261152)
        val template = File(JasoRuntime.vectorDir, "ok-native.jaso").readBytes()
        repeat(3000) {
            val raw = rng.nextBytes(rng.nextInt(300))
            assertTrue(JasoRuntime.check(raw) != "ok")
            val mutated = template.copyOf()
            repeat(1 + rng.nextInt(8)) { mutated[rng.nextInt(mutated.size)] = rng.nextInt().toByte() }
            ByteBuffer.wrap(mutated).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28, mutated.size)
            resealJaso(mutated)
            JasoRuntime.check(mutated)
        }
    }

    @Test
    fun `utf8 validation agrees with the JDK strict decoder`() {
        val cls = JasoRuntime.loader.loadClass("std.jaso.JasoBytes")
        val rng = Random(20261153)
        val interesting = intArrayOf(0x00, 0x7f, 0x80, 0xbf, 0xc0, 0xc1, 0xc2, 0xdf, 0xe0, 0xa0, 0x9f, 0xed, 0xef, 0xf0, 0x90, 0x8f, 0xf4, 0xf5, 0xff, 0x41)
        repeat(40000) {
            val bytes = ByteArray(rng.nextInt(7)) { interesting[rng.nextInt(interesting.size)].toByte() }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val jdk = try {
                decoder.decode(ByteBuffer.wrap(bytes))
                true
            } catch (_: CharacterCodingException) {
                false
            }
            assertEquals(jdk, JasoRuntime.call(cls, "validUtf8", bytes, 0, bytes.size), bytes.joinToString(" ") { "%02x".format(it) })
        }
    }

    @Test
    fun `concurrent checks share no state`() {
        val files = JasoRuntime.vectorDir.listFiles()!!.filter { it.name.endsWith(".jaso") }.sortedBy { it.name }
        val inputs = files.map { it.readBytes() to File(it.path.removeSuffix(".jaso") + ".expect").readText().trim() }
        JasoRuntime.check(inputs[0].first)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker ->
                Callable { repeat(3) { inputs.forEachIndexed { i, (b, e) -> if (i % 4 == worker || i % 3 == 0) assertEquals(e, JasoRuntime.check(b)) } } }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `reader classes are version 52 and pass full bytecode verification`() {
        JasoRuntime.loader
        val output = Paths.get("build", "tmp", "jaso-reader")
        val classes = Files.walk(output).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.map { output.relativize(it).toString() }.sorted().toList()
        }
        val jaso = classes.filter { it.startsWith("std/jaso/") }
        assertTrue(jaso.size >= 10, "$jaso")
        for (name in jaso) {
            val bytes = Files.readAllBytes(output.resolve(name))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF), name)
        }
        val names = classes.map { it.removeSuffix(".class").replace('/', '.') }
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = output.toAbsolutePath().toString() + File.pathSeparator + System.getProperty("java.class.path")
        val process = ProcessBuilder(listOf(javaBin, "-Xverify:all", "-Xcheck:jni", "-cp", classpath, VerifyAllLoader::class.java.name) + names)
            .redirectErrorStream(true).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        val log = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "verification JVM timed out")
        assertEquals(0, process.exitValue(), log)
        assertTrue(log.contains("verified ${names.size} classes"), log)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：任意输入只允许得到码名，读取器内部的越界或空指针都算失败。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class JasoFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun wholeFile(data: ByteArray) {
        // 修好长度与摘要，否则几乎所有变异都停在 format.digest
        if (data.size >= 80) {
            ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28, data.size)
            resealJaso(data)
        }
        JasoRuntime.check(data)
    }

    @FuzzTest(maxDuration = "60s")
    fun manifestText(data: ByteArray) {
        JasoProbe.parseManifestOnly(data)
    }
}
