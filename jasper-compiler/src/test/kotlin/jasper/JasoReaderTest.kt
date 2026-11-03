// ============================================================================
// JasoReaderTest.kt —— `.jaso` beta 1 Jasper 读取器的一致性测试（JB-L1）
// ============================================================================
// 职责：把标准库 std.jaso 真实编译成 class，再跑参考实现发布的全部一致性向量，
//   结果必须与 `.expect` 逐字相等。向量是带提交号的只读拷贝（见 jaso-vectors/SOURCE）。
// ============================================================================

package jasper

import jasper.stdlib.StdLib
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 编译一次读取器，经反射调用；全部测试共用。 */
internal object JasoRuntime {
    val loader: ClassLoader by lazy { CompiledRuntimeLoader.compile("jaso-reader", StdLib.jasoSources()) }
    private val conformance: Class<*> by lazy { loader.loadClass("std.jaso.JasoConformance") }
    val vectorDir: File = File("src/test/resources/jaso-vectors")
    val devKey: ByteArray by lazy { hex(File(vectorDir, "jasper.dev.pub").readText().trim()) }

    /** 一致性判定：`ok` 或码名；其它异常（实现缺陷）原样抛出。 */
    fun check(bytes: ByteArray): String = call(conformance, "check", bytes, devKey) as String

    /** 结构读取：`ok` 或码名。 */
    fun structural(bytes: ByteArray): String = call(conformance, "structural", bytes) as String

    /** 调用 `cls` 的公开静态方法，把反射包装解开。 */
    fun call(cls: Class<*>, name: String, vararg args: Any?): Any? {
        val method = cls.methods.first { it.name == name && it.parameterCount == args.size }
        try {
            return method.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** 十六进制解码。 */
    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** 一致性向量与错误码表。 */
class JasoReaderTest {
    private fun vectors(): List<File> =
        JasoRuntime.vectorDir.listFiles()!!.filter { it.name.endsWith(".jaso") }.sortedBy { it.name }

    private fun expected(f: File): String = File(f.path.removeSuffix(".jaso") + ".expect").readText().trim()

    @Test
    fun `every conformance vector matches its expect line`() {
        val files = vectors()
        assertEquals(68, files.size, "向量个数变化：重新核对来源提交")
        val failures = files.mapNotNull { f ->
            val got = JasoRuntime.check(f.readBytes())
            if (got == expected(f)) null else "${f.name}: expected ${expected(f)} got $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `vectors cover all fifty one codes and the code table matches the specification`() {
        val table = File(JasoRuntime.vectorDir, "codes.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        assertEquals(51, table.size)
        val cls = JasoRuntime.loader.loadClass("std.jaso.JasoError")
        for ((number, code) in table) {
            assertEquals(number.toInt(), JasoRuntime.call(cls, "numberOf", code), code)
        }
        assertEquals(0, JasoRuntime.call(cls, "numberOf", "no.such.code"))
        val covered = vectors().map { expected(it) }.toSet()
        assertEquals(table.map { it[1] }.toSet() + "ok", covered)
    }
}
