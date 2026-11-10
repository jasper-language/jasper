package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * do-while 里的 continue 先求值条件（Q225，LANG-DOWHILE-CONTINUE-01）。
 *
 * 修复前 continue 跳回循环体开头、跳过条件：`do { i++; continue; } while (i < 3);` 永不结束。
 * 期望值由 Kotlin 的 do-while（语义与 Java 相同）独立算出；每次运行限时，旧行为表现为超时。
 */
class DoWhileContinueTest {
    private fun run(name: String, body: String, n: Int): Int {
        val dir = Paths.get("build", "scratch", "dowhile-continue", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileToDirWithIr("package t;\npublic function main(n: int32): int32 {\n$body\n}\n", dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
                val main = loader.loadClass("t.main").getMethod("main", Int::class.javaPrimitiveType)
                return pool.submit<Int> { main.invoke(null, n) as Int }.get(10, TimeUnit.SECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `continue evaluates the condition`() {
        val body = """
            var i = 0;
            var visits = 0;
            do {
                i = i + 1;
                if (i % 2 == 0) { continue; }
                visits = visits + 1;
            } while (i < n);
            return visits * 100 + i;
        """
        for (n in listOf(0, 1, 2, 5, 8)) {
            var i = 0
            var visits = 0
            do {
                i += 1
                if (i % 2 == 0) continue
                visits += 1
            } while (i < n)
            assertEquals(visits * 100 + i, run("basic-$n", body, n), "n=$n")
        }
    }

    @Test
    fun `labeled continue to an outer do-while evaluates its condition`() {
        val body = """
            var i = 0;
            var total = 0;
            label outer: do {
                i = i + 1;
                var j = 0;
                while (j < 5) {
                    j = j + 1;
                    if (j == 2) { continue outer; }
                    total = total + j;
                }
            } while (i < n);
            return total * 100 + i;
        """
        for (n in listOf(1, 3, 6)) {
            var i = 0
            var total = 0
            outer@ do {
                i += 1
                var j = 0
                while (j < 5) {
                    j += 1
                    if (j == 2) continue@outer
                    total += j
                }
            } while (i < n)
            assertEquals(total * 100 + i, run("labeled-$n", body, n), "n=$n")
        }
    }

    @Test
    fun `random skip patterns match the Kotlin loop`() {
        val random = Random(20261114)
        repeat(20) { round ->
            val mod = random.nextInt(2, 5)
            val hit = random.nextInt(0, mod)
            val breakAt = random.nextInt(3, 30)
            val body = """
                var i = 0;
                var acc = 0;
                do {
                    i = i + 1;
                    if (i == $breakAt) { break; }
                    if (i % $mod == $hit) { continue; }
                    acc = acc + i;
                } while (i < n);
                return acc * 1000 + i;
            """
            val n = random.nextInt(0, 25)
            var i = 0
            var acc = 0
            do {
                i += 1
                if (i == breakAt) break
                if (i % mod == hit) continue
                acc += i
            } while (i < n)
            assertEquals(acc * 1000 + i, run("random-$round", body, n), "round $round")
        }
    }
}
