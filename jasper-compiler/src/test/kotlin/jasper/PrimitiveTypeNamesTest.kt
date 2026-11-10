package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 基本类型名的一致性（Q149，LANG-PRIMITIVE-NAMES-01）。
 *
 * 修复前：`int8` 过了检查器、运行时 NoClassDefFoundError（TypeMapper 没有它）；`short` 不是 Jasper 类型名
 * 却在 TypeMapper 里有映射；别名 `int` / `float` 与规范名在检查器里被当成不同类型；`uint32` 等规范
 * 列出但未实现的类型同样能过检查器。期望值独立写出。
 */
class PrimitiveTypeNamesTest {
    private fun compile(name: String, source: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "primitive-names", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileToDirWithIr(source, dir) to dir
    }

    private fun run(name: String, ret: String, body: String): Any? {
        val (result, dir) = compile(name, "package t;\npublic function main(): $ret {\n$body\n}\n")
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { return it.loadClass("t.main").getMethod("main").invoke(null) }
    }

    @Test
    fun `int8 and int16 compile run and wrap at their width`() {
        assertEquals((-128).toByte(), run("int8", "int8", "var a: int8 = cast 127 as int8;\na++;\nreturn a;"))
        assertEquals((-32768).toShort(), run("int16", "int16", "var b: int16 = cast 32767 as int16;\nb++;\nreturn b;"))
        // 窄类型可拓宽到 int32 / int64。
        assertEquals(300L, run("widen", "int64", "var a: int8 = cast 100 as int8;\nvar b: int16 = cast 200 as int16;\nvar c: int64 = a;\nvar d: int32 = b;\nreturn c + d;"))
        assertEquals(3, run("array", "int32", "var xs: int16[] = new int16[3];\nreturn xs.length;"))
    }

    @Test
    fun `aliases are the same type as their canonical names`() {
        assertEquals(7, run("int-alias", "int", "var a: int32 = 3;\nvar b: int = a;\nvar c: int32 = b + 4;\nreturn c;"))
        assertEquals(2.5f, run("float-alias", "float", "var a: float32 = 2.5f;\nvar b: float = a;\nvar c: float32 = b;\nreturn c;"))
    }

    @Test
    fun `specified but unimplemented types are diagnosed`() {
        for (type in listOf("uint", "uint8", "uint16", "uint32", "uint64", "int128", "uint128", "char8", "char16", "char32", "float128")) {
            val (result, _) = compile("u-$type", "package t;\npublic function main(): int32 {\nvar x: $type[]? = null;\nreturn 0;\n}\n")
            val error = assertIs<JasperCompiler.CompileResult.Error>(result, type)
            val canonical = if (type == "uint") "uint32" else type
            assertTrue(error.errors.any { "'$canonical' is specified but not yet supported" in it.message }, error.message)
        }
        val (param, _) = compile("u-param", "package t;\npublic function f(x: uint32): int32 { return 0; }\npublic function main(): int32 { return 0; }\n")
        assertIs<JasperCompiler.CompileResult.Error>(param)
    }

    @Test
    fun `short is not a Jasper type name`() {
        val (result, _) = compile("short", "package t;\npublic function main(): int32 {\nvar s: short = cast 1 as short;\nreturn 0;\n}\n")
        assertIs<JasperCompiler.CompileResult.Error>(result)
    }
}
