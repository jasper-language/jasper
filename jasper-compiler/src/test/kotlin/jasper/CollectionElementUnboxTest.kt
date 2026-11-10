package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 基本类型元素的集合读取要拆箱（Q227，LANG-COLLECTION-UNBOX-01）。
 *
 * 修复前 `var l = new MutableList<int32>(); … l.get(0) + 1` 在类加载时 VerifyError：
 * get 返回 Object 却直接参与 int 运算。期望值独立写出。
 */
class CollectionElementUnboxTest {
    private fun run(name: String, ret: String, body: String): Any? {
        val dir = Paths.get("build", "scratch", "collection-unbox", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to "package t;\npublic function entry(): $ret {\n$body\n}\n"), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `inferred list locals unbox their elements`() {
        assertEquals(12, run("inferred", "int32", "var l = new MutableList<int32>();\nl.add(5);\nl.add(6);\nreturn l.get(0) + l.get(1) + 1;"))
    }

    @Test
    fun `declared list locals unbox their elements`() {
        assertEquals(12, run("declared", "int32", "var l: MutableList<int32> = new MutableList<int32>();\nl.add(5);\nl.add(6);\nreturn l.get(0) + l.get(1) + 1;"))
    }

    @Test
    fun `other primitive element types unbox too`() {
        assertEquals(7L, run("long", "int64", "var l = new MutableList<int64>();\nl.add(3L);\nl.add(4L);\nreturn l.get(0) + l.get(1);"))
        assertEquals(2.5, run("double", "float64", "var l = new MutableList<float64>();\nl.add(1.25);\nreturn l.get(0) * 2.0;"))
        assertEquals(true, run("bool", "bool", "var l = new MutableList<bool>();\nl.add(true);\nreturn l.get(0) && true;"))
    }

    @Test
    fun `map values unbox when used in arithmetic`() {
        assertEquals(43, run("map", "int32", "var m = new dict<string, int32>();\nm.put(\"a\", 42);\nreturn m.get(\"a\") + 1;"))
    }
}
