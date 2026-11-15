package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `MutableList<T>.remove(value)` 按值删除并返回是否删到（stdlib MutableList.jas；Q242，appfw FB-build-010，LANG-LIST-REMOVE-VALUE-01）。
 *
 * 修复前 `MutableList<int32>.remove(v)` 带着 int 去解析 JDK 重载，选中 `remove(int 下标)`：按下标删、返回被删元素。
 * 期望值由 Kotlin MutableList 的 remove(element) 独立算出。
 */
class ListRemoveValueTest {
    private fun run(name: String, ret: String, body: String): Any? {
        val dir = Paths.get("build", "scratch", "list-remove", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to "package t;\npublic function entry(): $ret {\n$body\n}\n"), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `remove takes a value, not an index`() {
        // [10, 20, 30, 1]：按值删 1 应删掉末尾的 1，而不是下标 1 的 20。
        assertEquals("true:10,20,30", run("int-value", "string", """
            var xs = new MutableList<int32>();
            xs.add(10); xs.add(20); xs.add(30); xs.add(1);
            var removed = xs.remove(1);
            return "" + removed + ":" + xs.get(0) + "," + xs.get(1) + "," + xs.get(2);
        """))
        assertEquals("false:3", run("missing", "string", """
            var xs = new MutableList<int32>();
            xs.add(10); xs.add(20); xs.add(30);
            var removed = xs.remove(99);
            return "" + removed + ":" + xs.size();
        """))
        assertEquals("true:b", run("string-value", "string", """
            var xs = new MutableList<string>();
            xs.add("a"); xs.add("b");
            var removed = xs.remove("a");
            return "" + removed + ":" + xs.get(0);
        """))
        assertEquals(2, run("int64-value", "int32", """
            var xs = new MutableList<int64>();
            xs.add(5L); xs.add(1L); xs.add(7L);
            xs.remove(1L);
            return xs.size();
        """))
    }

    @Test
    fun `random add and remove sequences match a Kotlin list`() {
        val random = Random(20261120)
        val body = StringBuilder("var xs = new MutableList<int32>();\nvar out = \"\";\n")
        val model = mutableListOf<Int>()
        val expected = StringBuilder()
        repeat(120) {
            val v = random.nextInt(0, 12)
            if (random.nextInt(3) == 0) {
                body.append("out = out + xs.remove($v) + \",\";\n")
                expected.append(model.remove(v)).append(',')
            } else {
                body.append("xs.add($v);\n")
                model.add(v)
            }
        }
        body.append("for (var i = 0; i < xs.size(); i = i + 1) { out = out + \"|\" + xs.get(i); }\nreturn out;")
        model.forEach { expected.append('|').append(it) }
        assertEquals(expected.toString(), run("random", "string", body.toString()))
    }
}
