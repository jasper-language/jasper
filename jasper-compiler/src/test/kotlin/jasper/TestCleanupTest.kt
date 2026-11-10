package jasper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * withCleanup 的失败组合（Q219）：测试体与清理各自成 / 败的四种组合、同一异常对象、
 * 内联非局部 return，以及「工作线程未排空 → awaitTermination 断言失败」的真实场景。
 */
class TestCleanupTest {
    private class BodyFailure : RuntimeException("body")
    private class CleanupFailure : RuntimeException("cleanup")

    /** 测试体失败、清理失败：抛出原失败，清理失败是它唯一的 suppressed。 */
    @Test
    fun `body failure is kept and cleanup failure is suppressed`() {
        val body = BodyFailure()
        val cleanup = CleanupFailure()
        val thrown = assertFailsWith<BodyFailure> {
            withCleanup({ throw cleanup }) { throw body }
        }
        assertSame(body, thrown)
        assertEquals(listOf<Throwable>(cleanup), thrown.suppressed.toList())
    }

    /** 测试体通过、清理失败：照常报清理失败，且清理恰好运行一次。 */
    @Test
    fun `cleanup failure is reported when the body passes`() {
        val cleanup = CleanupFailure()
        var bodyRuns = 0
        val thrown = assertFailsWith<CleanupFailure> {
            withCleanup({ throw cleanup }) { bodyRuns++ }
        }
        assertSame(cleanup, thrown)
        assertEquals(1, bodyRuns)
        assertTrue(thrown.suppressed.isEmpty())
    }

    /** 测试体失败、清理通过：抛出原失败，没有 suppressed，清理运行一次。 */
    @Test
    fun `body failure passes through when cleanup succeeds`() {
        val body = BodyFailure()
        var cleanups = 0
        val thrown = assertFailsWith<BodyFailure> {
            withCleanup({ cleanups++ }) { throw body }
        }
        assertSame(body, thrown)
        assertTrue(thrown.suppressed.isEmpty())
        assertEquals(1, cleanups)
    }

    /** 两者都通过：返回测试体的值，清理在测试体之后运行。 */
    @Test
    fun `body value is returned after cleanup runs`() {
        val order = StringBuilder()
        val value = withCleanup({ order.append("c") }) { order.append("b"); 42 }
        assertEquals(42, value)
        assertEquals("bc", order.toString())
    }

    /** 清理重新抛出测试体的同一异常对象：不做自我 suppressed，原失败照常抛出。 */
    @Test
    fun `same throwable from cleanup is not self suppressed`() {
        val body = BodyFailure()
        val thrown = assertFailsWith<BodyFailure> {
            withCleanup({ throw body }) { throw body }
        }
        assertSame(body, thrown)
        assertTrue(thrown.suppressed.isEmpty())
    }

    /** 测试体以内联非局部 return 离开：清理仍运行，且清理失败照常抛出。 */
    @Test
    fun `non local return still runs cleanup`() {
        var cleanups = 0
        fun early(): Int {
            withCleanup({ cleanups++ }) { return 7 }
        }
        assertEquals(7, early())
        assertEquals(1, cleanups)

        val cleanup = CleanupFailure()
        fun earlyFailing(): Int {
            withCleanup({ throw cleanup }) { return 7 }
        }
        assertSame(cleanup, assertFailsWith<CleanupFailure> { earlyFailing() })
    }

    /**
     * Q156 实测的场景：测试体断言失败时工作线程仍阻塞，关闭后的 awaitTermination 断言随之失败。
     * 报告必须是测试体的断言，关闭失败附在 suppressed 里。
     */
    @Test
    fun `undrained worker does not hide the body assertion`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val release = CountDownLatch(1)
        executor.submit { release.await() }
        try {
            val thrown = assertFailsWith<AssertionError> {
                withCleanup({
                    executor.shutdown()
                    assertTrue(executor.awaitTermination(50, TimeUnit.MILLISECONDS), "awaitTermination")
                }) {
                    assertEquals(36, 35, "frame result")
                }
            }
            assertTrue(thrown.message!!.contains("frame result"), thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertTrue(thrown.suppressed[0].message!!.contains("awaitTermination"), thrown.suppressed[0].message)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
