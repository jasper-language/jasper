/* ============================================================================
 * StdLib.kt —— Jasper 标准库装载器（stdlib 层）
 * ============================================================================
 *
 * 职责：
 *   集中维护 Jasper 语言标准库 v1 的源码清单（FILES），并在编译期把这些
 *   **固化的** `.jas` 源文件从 classpath 读入内存，交给编译器与用户源码
 *   一起翻译。本对象只负责「列举 + 读取」，不做解析、校验或翻译。
 *
 * 所属层次：
 *   stdlib 层（对应 scripts/check-code-style.sh 的 LAYER_RANK rank=2）。
 *   它只被上层（compiler 编排层）调用，自身**不依赖编译器内部**
 *   （import 仅 java.*），因此与 translator/checker/backend 之间没有耦合。
 *
 * 与谁协作：
 *   - 调用方：jasper.compiler 的 JasperCompiler.compileAllWithStdlib，
 *     把本对象返回的「文件名 -> 源码文本」映射并入本次编译的输入。
 *   - 资源来源：编译产物 classpath 下的 `/stdlib/<name>.jas`（由构建脚本打进
 *     resources），文件名与 FILES 一一对应。
 *
 * 对外契约：
 *   - sources() 返回按 FILES 顺序构建的 LinkedHashMap：键是合成文件名
 *     （如 "Mapper.jas"），值是该文件完整源码文本；
 *   - FILES 中的每个资源都必须存在，否则 read() 抛 IllegalStateException
 *     （error(...)），即装载失败是「致命且立即」的，不做静默降级。
 *
 * 扩展点 / 已知限制：
 *   - 新增标准库文件时，必须**同时**把文件名追加到 FILES，并保证对应资源
 *     存在于 `/stdlib/` 下；这里刻意不做 classpath 扫描，换取确定性顺序
 *     与 GraalVM native-image 友好（原生镜像里扫描不可靠且构建期无法固化）。
 *   - 当前只覆盖 v1 第一阶段：函数对象协议、Error 层级、迭代协议、文本构建器。
 * ========================================================================= */

package jasper.stdlib

/**
 * Jasper 标准库 v1 装载器（无状态单例）。
 *
 * 做什么：对外提供标准库源文件清单 FILES，并把对应 `.jas` 资源读成字符串
 *   映射返回；本身不解析、不校验、不翻译。
 *
 * 什么时候用：任何需要「把标准库一起编译」的入口在组装编译输入时调用，
 *   典型为 `JasperCompiler.compileAllWithStdlib`。
 *
 * 前置条件：classpath 上存在与 FILES 完全对应的 `/stdlib/<name>.jas` 资源。
 *
 * 失败模式：资源缺失时 read() 抛 IllegalStateException（error(...)），
 *   不静默跳过——宁可让整次编译失败，也不产生「少了一半标准库」的假编译。
 *
 * 为什么用 object：清单与读取逻辑天然无状态，单例即可，避免各调用方各存
 *   一份易腐化的清单副本。
 *
 * Loader for the Jasper standard library v1 (first phase: function-object
 * protocols, the `Error` hierarchy, the iteration protocol, and the text
 * builder).
 *
 * The library is distributed as Jasper **source** resources under
 * `/stdlib/` on the compiler's classpath. The file list is explicit (no
 * classpath scanning) so the set is deterministic and GraalVM-native-image
 * friendly. Callers compile the returned sources together with user sources;
 * see `JasperCompiler.compileAllWithStdlib`.
 */
object StdLib {

    /**
     * 标准库源文件清单，按翻译顺序排列。
     *
     * 为什么是这个顺序：接口与基类型必须排在其派生类型之前，这样 translator
     * 的「后写覆盖」（last-writer-wins）成员索引最终会指向最具体的声明，
     * 而不是被基类声明反向覆盖。
     *
     * 为什么是显式列表：不做 classpath 扫描，保证顺序确定、可审计，并在
     * GraalVM native-image 下可靠（扫描在原生镜像里不可靠）。
     *
     * Standard-library source files, in translation order. Interfaces and base
     * types come before the types that depend on them so the translator's
     * last-writer-wins member indexes end up pointing at the most concrete
     * declaration.
     */
    val FILES: List<String> = listOf(
        "Mapper.jas",
        "Predicate.jas",
        "Reducer.jas",
        "Comparator.jas",
        "Action.jas",
        "Equatable.jas",
        "Hashable.jas",
        "Comparable.jas",
        "List.jas",
        "MutableList.jas",
        "Map.jas",
        "Iterator.jas",
        "Iterable.jas",
        "StringBuilder.jas",
        "Error.jas",
        "RecoverableError.jas",
        "NumberFormatError.jas",
        "Numbers.jas",
        "FatalError.jas",
        // S4-B(J)：Jasper 自举协程运行时骨架。Future 文件同时定义 Failure 快照与
        // Future 状态机，因此必须排在 TaskGroup 之前；执行器接口先于 JVM 适配器。
        "Runnable.jas",
        "Executor.jas",
        "Future.jas",
        "CancellationToken.jas",
        "TaskGroup.jas",
        "Context.jas",
        "CleanupBudget.jas",
        "JvmExecutor.jas"
    )

    /**
     * 读出全部标准库源码，返回「合成文件名 -> 源码文本」的映射。
     *
     * 做什么：按 FILES 顺序逐个读取，保序返回（LinkedHashMap，迭代顺序即
     *   翻译顺序）。
     *
     * 什么时候用：编译器在组装本次编译输入时调用一次。
     *
     * 失败模式：任一资源缺失即由 read() 抛 IllegalStateException，不会返回
     *   残缺映射。
     *
     * The stdlib sources keyed by their synthetic file name.
     */
    fun sources(): Map<String, String> {
        // LinkedHashMap 保证迭代顺序 == FILES 顺序 == 翻译顺序。
        val out = LinkedHashMap<String, String>()
        for (file in FILES) {
            out[file] = read(file)
        }
        return out
    }

    /**
     * 读取单个标准库资源。
     *
     * 做什么：从 classpath 的 `/stdlib/<file>` 读全部字节并按 UTF-8 解码。
     * 失败模式：资源缺失时抛 IllegalStateException；读取异常由 use{} 关闭流后
     *   原样向上抛。私有实现细节，仅被 sources() 调用。
     */
    private fun read(file: String): String {
        // 资源路径固定在 classpath 根下的 /stdlib/，与构建时打进 resources 的布局一致。
        val resource = "/stdlib/$file"
        // 取不到即视为构建/打包事故：直接 error() 失败，而不是返回空串掩盖问题。
        val stream = StdLib::class.java.getResourceAsStream(resource)
            ?: error("Standard library resource not found on classpath: $resource")
        // use{} 确保流在读取（含异常路径）后被关闭；按 UTF-8 解码为文本。
        return stream.use { it.readBytes().decodeToString() }
    }
}
