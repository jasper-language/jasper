package jasper.translator

/**
 * 翻译器发给后端的内建语义调用名（Q224、Q152）。
 *
 * 做什么：集中定义「这次 IrCall 不是调用函数，而是一段内建指令」的调用名，翻译器登记、后端分派
 *   都只用这里的常量。
 * 为什么用保留形式 `__jasper_*__`：后端按调用名分派，旧实现直接用 `size`、`get`、`negate` 等普通
 *   名字，用户顶层函数 / 静态方法、平台方法只要同名就被内建路径抢走（用户 `size()` 报内部错误，
 *   `Bag.negate(40)` 被翻成 INEG）。源码标识符不能以双下划线包围的这种形式与之相撞。
 * 边界：`println` / `print` / `toString` 是语言的全局内建函数名，仍按源码名分派，不在此列。
 */
object IrIntrinsics {
    /** 数组长度：一个实参（数组）。 */
    const val ARRAY_SIZE = "__jasper_array_size__"
    /** 数组取元素：数组、下标。 */
    const val ARRAY_GET = "__jasper_array_get__"
    /** 数组写元素且表达式取值：数组、下标、值。 */
    const val ARRAY_SET = "__jasper_array_set__"
    /** 一元运算：取负、逻辑非、按位取反、一元加、自增、自减。 */
    const val NEGATE = "__jasper_negate__"
    const val NOT = "__jasper_not__"
    const val INV = "__jasper_inv__"
    const val UNARY_PLUS = "__jasper_unary_plus__"
    const val INC = "__jasper_inc__"
    const val DEC = "__jasper_dec__"
    /** 字符串拼接：任意个实参。 */
    const val STRING_PLUS = "__jasper_string_plus__"
    /** 闭包调用：闭包对象 + 实参。 */
    const val INVOKE_LAMBDA = "__jasper_invoke_lambda__"
    /** `lock (x)` 的进入 / 退出（Q234）：一个实参（锁对象），后端发 MONITORENTER / MONITOREXIT。 */
    const val MONITOR_ENTER = "__jasper_monitor_enter__"
    const val MONITOR_EXIT = "__jasper_monitor_exit__"
    /**
     * 平台静态字段读取（Q163）：调用名是本前缀加字段名，宿主类登记在平台静态调用宿主表里，
     * 没有实参；后端发 GETSTATIC，描述符由 PlatformMethodResolver.resolveStaticField 给出。
     */
    const val PLATFORM_GET_STATIC = "__jasper_platform_get_static__:"
}
