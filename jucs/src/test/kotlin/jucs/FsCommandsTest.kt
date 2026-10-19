package jucs

/*
 * ============================================================================
 * FsCommandsTest.kt —— filesystem 命令的真实行为测试
 * ============================================================================
 *
 * 全部断言基于真实临时目录里的真实文件，不构造假数据。
 *
 * English summary:
 *   Real behaviour of `fs file list` against real temporary directories.
 */

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsCommandsTest {

    private fun listJson(dir: String, vararg extra: String): Pair<Int, String> {
        val run = runCli("fs", "file", "list", "--path", dir, "--output", "json", *extra)
        return run.exitCode to run.stdout
    }

    @Test
    fun hiddenEntriesAreExcludedByDefault() = withTempDir { dir ->
        Files.writeString(dir.resolve("visible.txt"), "x")
        Files.writeString(dir.resolve(".hidden"), "y")
        val (code, stdout) = listJson(dir.toString())
        assertEquals(0, code)
        val document = TestJson.parseObject(stdout)
        @Suppress("UNCHECKED_CAST")
        val names = (document["data"] as List<Map<String, Any?>>).map { it["name"] }
        assertEquals(listOf("visible.txt"), names)
    }

    @Test
    fun allIncludesHiddenEntries() = withTempDir { dir ->
        Files.writeString(dir.resolve("visible.txt"), "x")
        Files.writeString(dir.resolve(".hidden"), "y")
        val (code, stdout) = listJson(dir.toString(), "--all")
        assertEquals(0, code)
        val document = TestJson.parseObject(stdout)
        @Suppress("UNCHECKED_CAST")
        val names = (document["data"] as List<Map<String, Any?>>).map { it["name"] }
        assertTrue(names.contains(".hidden"))
        assertTrue(names.contains("visible.txt"))
    }

    @Test
    fun directoriesAreReportedAsDirectories() = withTempDir { dir ->
        Files.createDirectory(dir.resolve("sub"))
        val (code, stdout) = listJson(dir.toString())
        assertEquals(0, code)
        val document = TestJson.parseObject(stdout)
        @Suppress("UNCHECKED_CAST")
        val entry = (document["data"] as List<Map<String, Any?>>).single()
        assertEquals("directory", entry["kind"])
        assertEquals(null, entry["size"])
    }

    @Test
    fun sortingBySizeDescendingPutsLargestFirst() = withTempDir { dir ->
        Files.writeString(dir.resolve("small.txt"), "1")
        Files.writeString(dir.resolve("large.txt"), "123456789")
        Files.writeString(dir.resolve("medium.txt"), "12345")
        val (code, stdout) = listJson(dir.toString(), "--sort", "size", "--order", "desc")
        assertEquals(0, code)
        val document = TestJson.parseObject(stdout)
        @Suppress("UNCHECKED_CAST")
        val names = (document["data"] as List<Map<String, Any?>>).map { it["name"] }
        assertEquals(listOf("large.txt", "medium.txt", "small.txt"), names)
    }

    @Test
    fun sortFieldIsValidated() = withTempDir { dir ->
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--sort", "colour")
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.ARGUMENT.TYPE"))
    }

    @Test
    fun limitTruncatesAndWarns() = withTempDir { dir ->
        repeat(3) { index -> Files.writeString(dir.resolve("f$index.txt"), "x") }
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--limit", "2", "--output", "json")
        assertEquals(0, run.exitCode)
        val document = TestJson.parseObject(run.stdout)
        @Suppress("UNCHECKED_CAST")
        val data = document["data"] as List<Map<String, Any?>>
        assertEquals(2, data.size)
        assertTrue(run.stderr.contains("--limit"), "截断必须通过 stderr 如实告知")
    }

    @Test
    fun negativeLimitIsRejected() = withTempDir { dir ->
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--limit", "-1")
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.ARGUMENT.TYPE"))
    }

    @Test
    fun fileInsteadOfDirectoryIsRejected() = withTempDir { dir ->
        val file = Files.writeString(dir.resolve("plain.txt"), "x")
        val run = runCli("fs", "file", "list", "--path", file.toString())
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("不是目录"))
    }

    @Test
    fun emptyDirectoryReportsEmptyTable() = withTempDir { dir ->
        val run = runCli("fs", "file", "list", "--path", dir.toString())
        assertEquals(0, run.exitCode)
        assertTrue(run.stdout.contains("(empty)"))
    }
}
