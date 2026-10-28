// ============================================================================
// LinearCoroutineEmitter.kt —— 多挂起点、条件分支与 while 循环 int32 函数的内部状态机发射器
// ============================================================================
// 职责：
//   把已经通过类型检查、且已被直接调用图判定为「可能挂起」的窄范围函数，翻译成
//   一个实现 Runnable + Action<FutureOutcome> + CoroutineTask<int32> 的生成帧类。
//   生成类保存源码函数的参数与跨挂起局部，使用 Future.onComplete 注册恢复，并通过
//   ContinuationDispatch 的串行恢复门与结果绑定桥运行；CoroutineTask 协议让
//   TaskGroup.launch 把帧作为结构化作用域的子任务登记、取消与回收。
//
// 所属层次：
//   translator 层（AST -> AST 的变换）。只依赖 ast 与同层分段规划器；不依赖
//   compiler 编排层、checker 或字节码后端，因此可由单元测试直接调用，也可由
//   compiler 层在类型检查之后接入。
//
// 与谁协作：
//   - CoroutineBlockPlanner：把函数体切成基本块，给出挂起点、分支、循环回边与局部布局；
//   - 生成代码运行期依赖 std.ContinuationDispatch / std.Future / std.FutureOutcome /
//     std.CoroutineTask；
//   - compiler 层的协程降级阶段负责挑选函数、命名生成类并处理不支持形态的诊断。
//
// 对外契约：
//   - unsupportedReason(...) 返回 null 表示该函数属于本片支持形态；否则返回一段
//     可直接进入编译诊断的原因文本。纯函数，不修改输入 AST。
//   - emit(...) 只在支持形态上返回完整 JasClass；任何不支持输入都抛
//     IllegalArgumentException，绝不返回半边生成结果。
//
// 边界（本片刻意不做）：
//   - 支持形态由 CoroutineBlockPlanner 判定（见其文件头）；至少一个 await；
//   - try/finally（第十七片）：清理在结果发布之前执行——return 先求值再清理再完成；
//     挂起点失败 / 取消、段内异常与作用域取消一律跳出分派循环，按 `$region` 由内到外
//     展开清理后再发布 Failure / Cancelled（作用域取消时结果已终结，只做清理）；清理
//     抛出时以新的 Failure 取代，继续展开外层；
//   - try/catch（第十八片，裁决 C）：有 catch 区域时挂起点失败 / 取消按 Future.get() 语义抛出
//     FutureFailureError / FutureCancelledError，段内抛出经异常路由按 `$region` 由内到外执行
//     finally 清理、按捕获类型匹配 catch，命中则转入处理块并重新进入分派循环（入口检查照常）；
//     本任务自身的取消不抛异常，只展开 finally，catch 看不到它；
//   - throw（第二十片）：段内直接抛出，与段内其他异常走同一条路由 / 展开路径；未被捕获时
//     std.Error 发布为 Failure(message, "error")，其余平台异常为 Failure(toString, "platform")，
//     与 TaskGroup.spawn 的子任务体约定一致；
//   - 不定义显式挂起标记，不改变公开函数 ABI，也不把生成帧冒充为可同步调用的
//     普通函数；
//   - 不实现 Future.get 阻塞等待；恢复只经 onComplete -> ContinuationDispatch。
// ============================================================================

package jasper.translator

import jasper.ast.*

/**
 * 多挂起点、条件分支与 while 循环 int32 函数的内部 AST 发射器。
 *
 * 做什么：把一个受限函数改写成显式状态机类；原函数参数/局部变量成为帧字段，
 *   程序位置由 `__phase`（下一个基本块编号）表示，awaited Future 的回调只请求恢复，
 *   恢复段在调度门分配的工作线程上执行。按编号顺序排列的段包在分派循环里：前向
 *   跳转在同一趟内继续，回边（循环头）开启新一趟，直到下一个挂起点或 return。
 * 什么时候用：compiler 的协程降级阶段确认函数为 SUSPENDING，且
 *   [unsupportedReason] 返回 null 时。
 * 前置条件：输入已通过类型检查；真实 std.Future await 的 `awaitResultType` 已由
 *   检查器回填；调用者负责给生成类一个在包内唯一、合法的名字。
 * 失败模式：不支持形态抛 IllegalArgumentException（消息可直接作为诊断原因）；
 *   输入合法时不会产生半成品类。
 * 为什么这样设计：把「哪些形态可发射」和「如何发射」集中在同一对象，避免编译器
 *   阶段与发射器各自维护一份易漂移的支持范围；生成结果仍是普通 AST，复用既有
 *   IR/JVM 后端，不引入动态代码生成。
 */
object LinearCoroutineEmitter {
    /** 标准库类型的限定名；生成类可位于任意用户包，不能依赖同包简单名解析。 */
    private const val FUTURE = "std.Future"
    private const val FUTURE_OUTCOME = "std.FutureOutcome"
    private const val FAILURE = "std.Failure"
    private const val DISPATCH = "std.ContinuationDispatch"
    private const val EXECUTOR = "std.Executor"
    private const val RUNNABLE = "std.Runnable"
    private const val ACTION = "std.Action"
    private const val COROUTINE_TASK = "std.CoroutineTask"
    private const val FUTURE_FAILURE_ERROR = "std.FutureFailureError"
    private const val FUTURE_CANCELLED_ERROR = "std.FutureCancelledError"
    private const val THROWABLE = "java.lang.Throwable"
    private const val ERROR = "std.Error"

    /** 仅 int32 结果；更宽类型需要重新审视字段布局与描述符，不能静默复用。 */
    private val INT = JasPrimitiveType("int32")
    private val BOOL = JasPrimitiveType("bool")

    /**
     * 判断函数是否属于本片支持形态，并给出人类可读的拒绝原因。
     *
     * 做什么：依次验证静态性、泛型、返回类型、参数类型，再交给 [CoroutineBlockPlanner]
     *   验证语句结构、await 位置与局部声明，最后要求至少一个挂起点；返回 null 才允许
     *   调用 [emit]。
     * 什么时候用：compiler 降级阶段在生成类之前调用；也供测试锁定支持边界。
     * 前置条件：function 已完成类型检查，真实 await 的 awaitResultType 已回填。
     * 失败模式：纯检查，不抛异常；所有拒绝都返回具体原因而不是布尔值，保证诊断
     *   能指出缺哪一条边界。
     * 为什么 requireStatic 是参数：类成员函数必须 static 才能在没有接收者的情况下
     *   由生成帧独立运行；顶层函数没有 static 修饰符但语义上是静态的，由调用者声明。
     */
    fun unsupportedReason(function: JasFunction, requireStatic: Boolean): String? {
        if (requireStatic && !function.modifiers.contains("static")) {
            return "only static functions are supported"
        }
        if (function.typeParameters.isNotEmpty()) {
            return "generic functions are not supported"
        }
        if (function.returnType != INT) {
            return "only int32 return type is supported"
        }
        if (function.parameters.any { !isSupportedParameter(it.type) }) {
            return "parameters must be int32, bool or std.Future<int32>"
        }
        if (function.parameters.map { it.name }.distinct().size != function.parameters.size) {
            return "parameter and local names must be unique"
        }
        val plan = CoroutineBlockPlanner.plan(function, parameterTypes(function))
        plan.reason?.let { return it }
        if (plan.suspensionCount == 0) {
            return "at least one direct await is required, found 0"
        }
        return null
    }

    /**
     * 生成完整的帧类 AST。
     *
     * 做什么：把支持范围内的函数改写为字段 + 构造器 + `run` + `accept` +
     *   `resultFuture` + `start`；构造器把结果 Future 绑定到 ContinuationDispatch，
     *   `start` 订阅自身结果 Future 后只请求第一次恢复。
     * 什么时候用：compiler 已确认 [unsupportedReason] 返回 null 后调用。
     * 前置条件：className 是合法 Java 标识符；function 未脱离原 AST 上下文完成类型检查。
     * 失败模式：形态不支持或表达式无法安全改写时抛 IllegalArgumentException；
     *   不会返回部分类，也不会修改传入的 function。
     * 为什么生成 Runnable/Action 而不是新语言 ABI：这两个是既有标准库协议，JVM
     *   执行器与 Future.onComplete 已稳定支持，可在不新增关键字/公开调用约定的
     *   前提下先验证状态机与恢复桥。
     */
    fun emit(function: JasFunction, className: String, requireStatic: Boolean = true): JasClass {
        unsupportedReason(function, requireStatic)?.let { reason ->
            throw IllegalArgumentException("unsupported linear coroutine: $reason")
        }
        require(className.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid generated class name" }

        val plan = CoroutineBlockPlanner.plan(function, parameterTypes(function))
        val resultFuture = JasNamedType(FUTURE, listOf(INT))

        // 槽位按「参数在前、局部按源码出现顺序」固定分配；字段名不直接使用用户名字，
        // 避免生成类与用户局部/字段重名，也让跨段布局在代码中可见。分支内局部同样
        // 成为帧字段：它们可能跨越分支内的挂起点。
        val slotTypes = function.parameters.map { it.name to normalize(requireNotNull(it.type)) } + plan.locals
        val slots = slotTypes.withIndex().associate { it.value.first to "__slot${it.index}" }

        /** 生成当前帧字段引用。 */
        fun field(name: String) = JasPropertyAccess(JasIdentifier("this"), name)
        /** 生成实例方法调用。 */
        fun invoke(target: JasExpression, name: String, vararg args: JasExpression) =
            JasCall(JasPropertyAccess(target, name), args.toList())
        /** 把表达式包装成语句。 */
        fun statement(expression: JasExpression) = JasExpressionStatement(expression)
        /** 生成帧字段赋值。 */
        fun assign(name: String, value: JasExpression) = statement(JasAssignment(field(name), value))
        /** 生成 `__phase = target`。 */
        fun jump(target: Int) = assign("__phase", JasIntLiteral(target.toLong(), target.toString()))

        /**
         * 把源码表达式改写成帧内表达式。
         *
         * 已绑定局部/参数 -> 帧字段；自由标识符（类型名、静态函数名）保持原样，
         * 由生成类的真实类型检查兜底。`this` 明确拒绝：源码函数若依赖实例状态，
         * 生成类没有对应接收者，不能静默改指生成帧自身。
         */
        fun rewrite(expression: JasExpression): JasExpression = when (expression) {
            is JasIdentifier -> {
                require(expression.name != "this") { "instance receiver is not supported" }
                slots[expression.name]?.let(::field) ?: expression
            }
            is JasIntLiteral, is JasFloatLiteral, is JasStringLiteral, is JasBoolLiteral,
            is JasCharLiteral, JasNullLiteral -> expression
            is JasBinaryOp -> {
                require(expression.op in setOf("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||")) {
                    "unsupported binary operator ${expression.op}"
                }
                expression.copy(left = rewrite(expression.left), right = rewrite(expression.right))
            }
            is JasUnaryOp -> {
                require(expression.awaitResultType == null) { "nested suspension is not supported" }
                when (expression.op) {
                    "await" -> rewrite(expression.operand)
                    "+", "-", "!" -> expression.copy(operand = rewrite(expression.operand))
                    else -> throw IllegalArgumentException("unsupported unary operator ${expression.op}")
                }
            }
            is JasPropertyAccess -> expression.copy(target = rewrite(expression.target))
            is JasCall -> JasCall(rewrite(expression.target), expression.args.map(::rewrite), expression.isSafe)
            is JasCastExpr -> expression.copy(expression = rewrite(expression.expression))
            is JasNew -> expression.copy(args = expression.args.map(::rewrite))
            else -> throw IllegalArgumentException("unsupported frame expression: ${expression.javaClass.simpleName}")
        }

        /** 记下待发布的失败：`__abort = 1`，`__pending = failure`。 */
        fun abortFailure(failure: JasExpression) = listOf(
            assign("__pending", failure),
            assign("__abort", JasIntLiteral(1, "1"))
        )

        // 有 catch 区域的帧：挂起点的失败 / 取消按 Future.get() 语义变成抛出的错误，经异常路由
        // 进入匹配的 catch；没有 catch 区域的帧保持第十七片的直接展开，生成代码不变。
        val hasCatch = plan.tryRegions.any { it.handlers.isNotEmpty() }

        /**
         * 恢复块入口：消费终态快照。成功取 int32 结果写入绑定；失败记为待发布的原 Failure；
         * 取消记为待发布的 Cancelled，而不是伪装成业务失败。没有 catch 区域时两者都跳出分派
         * 循环，先展开清理再发布；有 catch 区域时先记下同样的待发布终态，再抛出
         * FutureFailureError（消息取自原 Failure，与 get() 一致）/ FutureCancelledError：被 catch
         * 匹配时待发布终态被清除，未被匹配时发布的仍是原 Failure / Cancelled，与没有 catch 时相同。
         * 局部名带块编号，避免同一 run 方法内多个恢复块的临时变量重名。
         */
        fun consumeOutcome(blockId: Int, binding: String): List<JasStatement> {
            val readyName = "__ready$blockId"
            val failureName = "__failure$blockId"
            val ready = JasIdentifier(readyName)
            val failure = JasIdentifier(failureName)
            /** 抛出 std 错误；生成帧可位于任意用户包，类型名必须限定。 */
            fun raise(type: String, message: JasExpression) =
                JasThrow(JasNew(JasNamedType(type), listOf(message)))
            val onCancelled = if (!hasCatch) JasBreakStatement(null)
                else raise(FUTURE_CANCELLED_ERROR, JasStringLiteral("future was cancelled"))
            val onFailed = if (!hasCatch) listOf(JasBreakStatement(null)) else listOf(
                JasIf(JasBinaryOp(failure, "==", JasNullLiteral), JasBlock(listOf(
                    raise(FUTURE_FAILURE_ERROR, JasStringLiteral("future failed without a failure snapshot"))
                )), null),
                raise(FUTURE_FAILURE_ERROR, invoke(JasCastExpr(failure, JasNamedType(FAILURE)), "message"))
            )
            return listOf(
                JasVariableStatement(readyName, JasNamedType(FUTURE_OUTCOME),
                    JasCastExpr(field("__outcome"), JasNamedType(FUTURE_OUTCOME)), false),
                JasIf(invoke(ready, "isCancelled"), JasBlock(listOf(
                    assign("__abort", JasIntLiteral(2, "2")),
                    onCancelled
                )), null),
                JasIf(invoke(ready, "isFailed"), JasBlock(listOf(
                    JasVariableStatement(failureName, JasNullableType(JasNamedType(FAILURE)),
                        invoke(ready, "failure"), false)
                ) + abortFailure(failure) + onFailed), null),
                assign(slots.getValue(binding), JasCastExpr(invoke(ready, "value"), INT))
            )
        }

        /**
         * 把已校验的 finally 体改写成帧内同步语句：局部声明与赋值写帧字段，`++` / `--` 改写为
         * 加减 1，调用语句、if/else、while、块与 finally 内循环的 break / continue 保持结构。
         */
        fun rewriteCleanup(statement: JasStatement): JasStatement = when (statement) {
            is JasVariableStatement -> assign(slots.getValue(statement.name), rewrite(requireNotNull(statement.initializer)))
            is JasExpressionStatement -> when (val expression = statement.expression) {
                is JasAssignment -> assign(slots.getValue((expression.target as JasIdentifier).name), rewrite(expression.value))
                is JasPostfixIncDec -> {
                    val slot = slots.getValue((expression.target as JasIdentifier).name)
                    assign(slot, JasBinaryOp(field(slot), if (expression.op == "++") "+" else "-", JasIntLiteral(1, "1")))
                }
                else -> statement(rewrite(expression))
            }
            is JasIf -> JasIf(rewrite(statement.condition), JasBlock(statement.thenBody.statements.map(::rewriteCleanup)),
                statement.elseBody?.let { JasBlock(it.statements.map(::rewriteCleanup)) })
            is JasWhile -> JasWhile(rewrite(statement.condition), JasBlock(statement.body.statements.map(::rewriteCleanup)))
            is JasBreakStatement, is JasContinueStatement -> statement
            is JasBlock -> JasBlock(statement.statements.map(::rewriteCleanup))
            else -> throw IllegalArgumentException("unsupported finally statement: ${statement.javaClass.simpleName}")
        }

        // 区域清理：先把区域局部退回外层区域，再执行 finally 体；清理抛出时展开从外层继续。
        val regionSlot = slots[CoroutineBlockPlanner.REGION_LOCAL]
        val cleanupCode = plan.tryRegions.associate { region ->
            region.id to listOf(assign(requireNotNull(regionSlot), JasIntLiteral(region.parent.toLong(), region.parent.toString()))) +
                region.cleanup.statements.map(::rewriteCleanup)
        }

        // 所有用户表达式先验证并转换；任何失败都在构造类之前抛出，不返回半成品 AST。
        val segments = plan.blocks.map { block ->
            val code = mutableListOf<JasStatement>()
            block.resumeBinding?.let { code += consumeOutcome(block.id, it) }
            block.assignments.forEach { (name, value) -> code += assign(slots.getValue(name), rewrite(value)) }
            block.cleanups.forEach { code += cleanupCode.getValue(it) }
            when (val exit = block.exit) {
                is CoroutineBlockExit.Goto -> code += jump(exit.target)
                is CoroutineBlockExit.Branch -> code += JasIf(
                    rewrite(exit.condition),
                    JasBlock(listOf(jump(exit.whenTrue))),
                    JasBlock(listOf(jump(exit.whenFalse)))
                )
                is CoroutineBlockExit.Suspend -> {
                    // 操作数先求值且只求值一次；结果已被外部终结（作用域取消）时不再登记新的
                    // 回调，跳出分派循环展开清理后由 finally 回收（不能直接 return：那会跳过
                    // 所在区域的清理，而 finally 随即关闭调度门，之后不会再有展开的机会）。
                    // 否则先写下一块编号再注册：注册前已完成的 Future 会同步回调 accept，
                    // 恢复请求由调度门合并到本段结束之后。
                    code += JasVariableStatement("__awaited${block.id}", resultFuture, rewrite(exit.await.operand), false)
                    code += JasIf(invoke(field("__result"), "isDone"), JasBlock(listOf(JasBreakStatement(null))), null)
                    code += jump(exit.resume)
                    code += statement(invoke(JasIdentifier("__awaited${block.id}"), "onComplete", JasIdentifier("this")))
                    code += JasReturn(null)
                }
                is CoroutineBlockExit.Finish -> {
                    code += statement(invoke(field("__result"), "complete", rewrite(exit.value)))
                    code += JasReturn(null)
                }
                // 抛出值在段内求值后直接抛出；所在区域的 finally / catch 由外层路由按 `$region` 处理。
                is CoroutineBlockExit.Throw -> code += JasThrow(rewrite(exit.value))
            }
            JasIf(
                JasBinaryOp(field("__phase"), "==", JasIntLiteral(block.id.toLong(), block.id.toString())),
                JasBlock(code),
                null
            )
        }

        val properties = mutableListOf<JasDeclaration>(
            JasProperty("__phase", INT, listOf("private"), null, null),
            JasProperty("__outcome", JasNullableType(JasNamedType(FUTURE_OUTCOME)), listOf("private"), null, null),
            JasProperty("__abort", INT, listOf("private"), null, null),
            JasProperty("__pending", JasNullableType(JasNamedType(FAILURE)), listOf("private"), null, null),
            JasProperty("__result", resultFuture, listOf("private"), null, null),
            JasProperty("__dispatch", JasNamedType(DISPATCH), listOf("private"), null, null)
        )
        slotTypes.forEach { (name, type) ->
            properties += JasProperty(slots.getValue(name), type, listOf("private"), null, null)
        }

        val constructorBody = mutableListOf<JasStatement>(
            assign("__phase", JasIntLiteral(0, "0")),
            assign("__outcome", JasNullLiteral),
            assign("__abort", JasIntLiteral(0, "0")),
            assign("__pending", JasNullLiteral),
            assign("__result", JasIdentifier("output"))
        )
        function.parameters.forEachIndexed { index, parameter ->
            constructorBody += assign(slots.getValue(parameter.name), JasIdentifier("arg$index"))
        }
        plan.locals.forEach { (name, type) ->
            constructorBody += assign(slots.getValue(name),
                if (type == BOOL) JasBoolLiteral(false) else JasIntLiteral(0, "0"))
        }
        constructorBody += assign("__dispatch", JasNew(
            JasNamedType(DISPATCH),
            listOf(JasIdentifier("executor"), JasIdentifier("this"))
        ))
        // 第七片的结果绑定桥：必须在任何 resume 之前完成，失败/调度拒绝才有目标可发布。
        constructorBody += statement(invoke(field("__dispatch"), "bindResult", field("__result")))

        val constructor = JasConstructor(
            listOf("public"),
            listOf(JasParameter("executor", JasNamedType(EXECUTOR)), JasParameter("output", resultFuture)) +
                function.parameters.mapIndexed { index, parameter ->
                    JasParameter("arg$index", normalize(requireNotNull(parameter.type)))
                },
            null,
            JasBlock(constructorBody)
        )

        // 段按块编号顺序排列：前向跳转在同一趟内顺序走到目标段；回边把 `__phase` 设为
        // 更小的循环头编号，本趟其余段都不匹配，分派循环开启新一趟。恢复不会重放挂起点
        // 之前的块，挂起点的操作数每次经过只求值一次。
        // 每趟开头先看结果是否已被外部终结（作用域取消）：第一趟即入口检查，之后的每趟
        // 对应一次回边，不挂起的循环因此也能在回边处响应取消；终结时不再执行业务段，
        // 跳出分派循环展开清理，再落到 finally 关闭调度门并标记 settled，等待中的帧因此能被回收。
        // 挂起与完成直接 return（不展开）；挂起点失败 / 取消同样跳出分派循环。
        val dispatchLoop = JasWhile(
            JasBoolLiteral(true),
            JasBlock(
                listOf(JasIf(invoke(field("__result"), "isDone"), JasBlock(listOf(JasBreakStatement(null))), null)) + segments
            )
        )
        /** 平台异常转成待发布的 Failure。 */
        fun platformFailure(error: String) =
            JasNew(JasNamedType(FAILURE), listOf(invoke(JasIdentifier(error), "toString"), JasStringLiteral("platform")))
        /**
         * 段内或清理抛出的 [error] 记为待发布的失败：std.Error 记为 Failure(message, "error")，其余平台
         * 异常记为 Failure(toString, "platform")。为什么区分：与 TaskGroup.spawn 子任务体的约定一致，
         * 等待者按 kind 区分业务错误与平台故障，源码 throw 的错误消息原样可见。
         */
        fun abortThrown(error: String) = listOf(JasIf(
            JasInstanceOfExpr(JasIdentifier(error), JasNamedType(ERROR)),
            JasBlock(abortFailure(JasNew(JasNamedType(FAILURE), listOf(
                invoke(JasCastExpr(JasIdentifier(error), JasNamedType(ERROR)), "message"), JasStringLiteral("error")
            )))),
            JasBlock(abortFailure(platformFailure(error)))
        ))
        /**
         * 有 catch 区域时的分派：段内抛出（含挂起点失败 / 取消转成的错误）先记下待发布终态
         * （挂起点已记的原 Failure / Cancelled 不覆盖），再按 `$region` 由内到外路由——finally 区域
         * 执行清理（清理抛出时以新异常继续路由，可被外层 catch 捕获），catch 区域先退回外层区域
         * 再按源码顺序用 instanceof 匹配捕获类型；命中时清除待发布终态、`__phase` 指向处理块，
         * 重新进入分派循环，入口检查照常进行（本任务若已被取消，处理块不执行，直接展开外层
         * finally）。走完区域链仍未命中时区域为 0，后续展开为空，发布待发布终态。
         *
         * 为什么本任务自身的取消不经过这里：入口检查与挂起前检查发现结果已终结时直接跳出分派
         * 循环，不抛异常，展开只执行 finally（catch 区域只退回外层区域），catch 看不到它（裁决 C）。
         */
        fun routedLoop(): JasStatement {
            val slot = requireNotNull(regionSlot)
            val caught = JasIdentifier("__caught")
            val thrown = JasIdentifier("__thrown")
            val route = mutableListOf<JasStatement>(
                JasIf(JasBinaryOp(field("__abort"), "==", JasIntLiteral(0, "0")),
                    JasBlock(abortThrown("__error")), null),
                JasVariableStatement("__thrown", JasNamedType(THROWABLE), JasIdentifier("__error"), false)
            )
            plan.tryRegions.sortedByDescending { it.id }.forEach { region ->
                val body = if (region.handlers.isEmpty()) {
                    val error = "__routeError${region.id}"
                    listOf(JasTry(
                        JasBlock(cleanupCode.getValue(region.id)),
                        listOf(JasCatchClause(
                            JasParameter(error, JasNamedType(THROWABLE)),
                            JasBlock(abortThrown(error) +
                                statement(JasAssignment(thrown, JasIdentifier(error))))
                        )),
                        null
                    ))
                } else {
                    // 处理块按源码顺序匹配：嵌套 if/else，首个命中者生效。
                    val match = region.handlers.foldRight(null as JasStatement?) { handler, otherwise ->
                        JasIf(JasInstanceOfExpr(thrown, handler.type), JasBlock(listOf(
                            statement(JasAssignment(caught, JasBoolLiteral(true))),
                            jump(handler.entry)
                        )), otherwise?.let { JasBlock(listOf(it)) })
                    }
                    cleanupCode.getValue(region.id) + listOfNotNull(match)
                }
                route += JasIf(
                    JasBinaryOp(JasUnaryOp("!", caught, true), "&&",
                        JasBinaryOp(field(slot), "==", JasIntLiteral(region.id.toLong(), region.id.toString()))),
                    JasBlock(body),
                    null
                )
            }
            route += JasIf(caught, JasBlock(listOf(
                assign("__abort", JasIntLiteral(0, "0")),
                assign("__pending", JasNullLiteral)
            )), null)
            return JasWhile(JasBoolLiteral(true), JasBlock(listOf(
                JasVariableStatement("__caught", BOOL, JasBoolLiteral(false), false),
                JasTry(
                    JasBlock(listOf(dispatchLoop)),
                    listOf(JasCatchClause(JasParameter("__error", JasNamedType(THROWABLE)), JasBlock(route))),
                    null
                ),
                JasIf(JasUnaryOp("!", caught, true), JasBlock(listOf(JasBreakStatement(null))), null)
            )))
        }

        // 段内任何未捕获异常都记为待发布的失败，先展开清理再发布；否则等待者会永远看到 Pending，
        // TaskGroup.close 也无法回收。
        val guardedLoop = if (!hasCatch) JasTry(
            JasBlock(listOf(dispatchLoop)),
            listOf(JasCatchClause(
                JasParameter("__error", JasNamedType(THROWABLE)),
                JasBlock(abortThrown("__error"))
            )),
            null
        ) else routedLoop()
        // 展开：按区域编号从大到小检查，外层编号总小于内层，一趟即可由内到外走完整条区域链。
        val unwind = plan.tryRegions.sortedByDescending { it.id }.map { region ->
            val error = "__cleanupError${region.id}"
            JasIf(
                JasBinaryOp(field(requireNotNull(regionSlot)), "==", JasIntLiteral(region.id.toLong(), region.id.toString())),
                JasBlock(listOf(JasTry(
                    JasBlock(cleanupCode.getValue(region.id)),
                    listOf(JasCatchClause(
                        JasParameter(error, JasNamedType("java.lang.Throwable")),
                        JasBlock(abortThrown(error))
                    )),
                    null
                ))),
                null
            )
        }
        val publish = listOf(
            JasIf(JasBinaryOp(field("__abort"), "==", JasIntLiteral(1, "1")), JasBlock(listOf(
                statement(invoke(field("__result"), "fail", JasCastExpr(field("__pending"), JasNamedType(FAILURE))))
            )), null),
            JasIf(JasBinaryOp(field("__abort"), "==", JasIntLiteral(2, "2")), JasBlock(listOf(
                statement(invoke(field("__result"), "cancel"))
            )), null)
        )
        // 只有结果已终结且不在任何清理区域内时才关闭调度门、标记 settled。为什么要看区域：挂起点
        // 注册回调之后、run 返回之前作用域可能取消结果；此时结果已终结但区域清理尚未执行，若
        // 照常关闭调度门，取消回调请求的那次恢复会被丢弃，清理永远不会执行。不关闭时该请求由
        // 调度门在本段结束后重新运行帧，入口检查跳出分派循环并展开清理，展开后区域为 0 再回收。
        val settleCondition = if (regionSlot == null) invoke(field("__result"), "isDone") else JasBinaryOp(
            invoke(field("__result"), "isDone"), "&&",
            JasBinaryOp(field(regionSlot), "==", JasIntLiteral(0, "0"))
        )
        // 发布本身（结果回调）抛出时结果已终结，再次失败发布不会改写终态，只保证异常不逃出 run。
        val caught = JasIdentifier("__late")
        val guardedRun = JasTry(
            JasBlock(listOf(guardedLoop) + unwind + publish),
            listOf(JasCatchClause(
                JasParameter("__late", JasNamedType("java.lang.Throwable")),
                JasBlock(listOf(
                    statement(invoke(field("__result"), "fail", JasNew(
                        JasNamedType(FAILURE),
                        listOf(invoke(caught, "toString"), JasStringLiteral("platform"))
                    )))
                ))
            )),
            JasBlock(listOf(
                JasIf(settleCondition, JasBlock(listOf(
                    statement(invoke(field("__dispatch"), "finish")),
                    statement(invoke(field("__result"), "markTaskSettled"))
                )), null)
            ))
        )

        properties += JasFunction("run", emptyList(), JasUnitType, JasBlock(listOf(guardedRun)), listOf("public"))
        properties += JasFunction(
            "accept",
            listOf(JasParameter("snapshot", JasNamedType(FUTURE_OUTCOME))),
            JasUnitType,
            JasBlock(listOf(
                assign("__outcome", JasIdentifier("snapshot")),
                statement(invoke(field("__dispatch"), "resume"))
            )),
            listOf("public")
        )
        properties += JasFunction(
            "resultFuture", emptyList(), resultFuture,
            JasBlock(listOf(JasReturn(field("__result")))),
            listOf("public")
        )
        // start 先订阅自身结果：作用域取消结果 Future 时经 accept 请求一次恢复，挂起中的
        // 帧由此进入入口检查并回收；帧自己发布终态时的这次回调只会合并或被已结束的门忽略。
        properties += JasFunction(
            "start", emptyList(), JasUnitType,
            JasBlock(listOf(
                statement(invoke(field("__result"), "onComplete", JasIdentifier("this"))),
                statement(invoke(field("__dispatch"), "resume"))
            )),
            listOf("public")
        )

        return JasClass(
            className,
            listOf("public"),
            emptyList(),
            null,
            listOf(
                JasNamedType(RUNNABLE),
                JasNamedType(ACTION, listOf(JasNamedType(FUTURE_OUTCOME))),
                JasNamedType(COROUTINE_TASK, listOf(INT))
            ),
            properties,
            listOf(constructor)
        ).also {
            // 生成类继承源函数位置，便于诊断与证据定位；合成方法自身位置保持 0。
            it.sourceLine = function.sourceLine
            it.sourceColumn = function.sourceColumn
        }
    }

    /** 参数只接受 int32、bool 或 std.Future<int32>；短名 Future 由同一文件 import 解析。 */
    private fun isSupportedParameter(type: JasType?): Boolean =
        type == INT || type == BOOL || isFutureInt(type)

    /** 参数名到（规范化后）类型；供块规划器校验局部写入目标与名字唯一性。 */
    private fun parameterTypes(function: JasFunction): Map<String, JasType> =
        function.parameters.associate { it.name to normalize(requireNotNull(it.type)) }

    /**
     * 参数若是短名 Future<int32>，生成类统一写成 std.Future<int32>，避免用户包中
     * 同名类型改变字段语义；其它类型原样返回。
     */
    private fun normalize(type: JasType): JasType =
        if (isFutureInt(type)) JasNamedType(FUTURE, listOf(INT)) else type

    /** 识别 Future<int32> 的两种常见书写；包身份最终仍由生成类重新类型检查确认。 */
    private fun isFutureInt(type: JasType?): Boolean {
        val named = type as? JasNamedType ?: return false
        return named.name in setOf("Future", FUTURE) && named.typeArguments == listOf(INT)
    }
}
