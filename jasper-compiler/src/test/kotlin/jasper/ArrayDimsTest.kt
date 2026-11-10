package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 数组创建的维数（Q153）：`new T[a][b]...[]...` 的结果类型有「给出长度的维数 + 尾随 `[]` 对数」层。
 *
 * 修复前尾随 `[]` 被 AST 丢弃，检查器一律把结果当一维：`new int64[4][]` 不能赋给 `int64[][]`，
 * `new int32[2][3]` 被当成 `int32[]`。期望值由测试独立写出。
 */
class ArrayDimsTest {
    private fun run(name: String, body: String, ret: String = "int64"): Any? {
        val dir: Path = Paths.get("build", "scratch", "array-dims", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileToDirWithIr("package t;\npublic function main(): $ret {\n$body\n}\n", dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            return loader.loadClass("t.main").getMethod("main").invoke(null)
        }
    }

    @Test
    fun `trailing brackets add unallocated inner dimensions`() {
        assertEquals(7L, run("jagged-long", """
            var rows: int64[][] = new int64[4][];
            rows[1] = new int64[3];
            rows[1][2] = 7L;
            if (rows[0] != null) { return -1L; }
            return rows[1][2];
        """))
        assertEquals(3, run("jagged-string", """
            var grid: string[][] = new string[2][];
            grid[0] = new string[3];
            grid[0][1] = "ab";
            if (grid[1] != null) { return -1; }
            if (grid[0][1] != "ab") { return -2; }
            return grid[0].length;
        """, "int32"))
        assertEquals(2, run("three", """
            var cube: int32[][][] = new int32[2][3][];
            cube[1][2] = new int32[5];
            cube[1][2][4] = 2;
            return cube[1][2][4];
        """, "int32"))
    }

    @Test
    fun `full dimensions keep their declared type`() {
        assertEquals(6, run("full", """
            var m: int32[][] = new int32[2][3];
            m[1][2] = 6;
            return m[1][2];
        """, "int32"))
    }

    @Test
    fun `a wrong dimension count is rejected`() {
        val dir = Paths.get("build", "scratch", "array-dims", "reject")
        Files.createDirectories(dir)
        for (src in listOf("var a: int64[] = new int64[4][];", "var b: int32[][] = new int32[2];", "var c: int32[][] = new int32[2][3][];")) {
            val result = JasperCompiler().compileToDirWithIr("package t;\npublic function main(): int32 {\n$src\nreturn 0;\n}\n", dir)
            assertIs<JasperCompiler.CompileResult.Error>(result, src)
        }
    }

    @Test
    fun `random shapes allocate the declared depth`() {
        val random = Random(20261112)
        repeat(30) { round ->
            val given = random.nextInt(1, 4)
            val extra = random.nextInt(0, 3)
            val depth = given + extra
            val lengths = List(given) { random.nextInt(1, 4) }
            val type = "int64" + "[]".repeat(depth)
            val alloc = "new int64" + lengths.joinToString("") { "[$it]" } + "[]".repeat(extra)
            // 沿每层下标 0 走到「给出长度」的最后一层，读它的长度；若还有未分配层，元素应为 null。
            val path = "[0]".repeat(given - 1)
            val nullCheck = if (extra > 0) "if (a$path[0] != null) { return -1; }\n" else ""
            val got = run("random-$round", "var a: $type = $alloc;\n${nullCheck}return a$path.length;", "int32")
            assertEquals(lengths.last(), got, "round $round: $alloc")
        }
    }
}
