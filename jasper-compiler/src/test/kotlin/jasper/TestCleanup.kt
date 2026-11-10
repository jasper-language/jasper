// ============================================================================
// TestCleanup.kt —— 测试体之后执行可能失败的清理，且不让清理失败盖掉测试体的失败
// ============================================================================
// 做什么：提供 withCleanup：先运行测试体，再运行清理（典型为关闭执行器并断言 awaitTermination）。
//   测试体失败时原失败照常抛出，清理失败作为 suppressed 附在原失败上；测试体通过而清理失败时
//   抛出清理失败；两者都通过时返回测试体的结果。
//
// 为什么需要：`try { body } finally { shutdown() }` 中 shutdown 自身的断言失败会替换 body 的失败。
//   协程测试里 body 失败常使工作线程无法排空，随后 awaitTermination 断言失败，报告只剩
//   「expected true」而真正原因丢失（Q156 实测）。语义与 Kotlin 标准库 `use` 对 close 的处理一致。
//
// 前置条件：cleanup 无论测试体成败都可以安全调用。
// 失败模式：见上；cleanup 抛出的恰是 body 抛出的同一对象时不做自我 suppressed（JDK 会拒绝）。
// ============================================================================

package jasper

/**
 * 运行 [body] 后运行 [cleanup]，返回 [body] 的结果。[body] 抛出时先运行 [cleanup]，
 * [cleanup] 的失败加入原失败的 suppressed 列表后再抛出原失败；[body] 正常结束（含内联的
 * 非局部 return）时 [cleanup] 的失败直接抛出。
 */
inline fun <R> withCleanup(cleanup: () -> Unit, body: () -> R): R {
    var primary: Throwable? = null
    try {
        return body()
    } catch (t: Throwable) {
        primary = t
        throw t
    } finally {
        val failed = primary
        if (failed == null) {
            cleanup()
        } else {
            try {
                cleanup()
            } catch (c: Throwable) {
                if (c !== failed) failed.addSuppressed(c)
            }
        }
    }
}
