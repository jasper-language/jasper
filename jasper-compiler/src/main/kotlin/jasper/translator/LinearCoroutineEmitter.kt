// ============================================================================
// LinearCoroutineEmitter.kt —— 单挂起点直线 int32 函数的内部状态机发射器
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
//   - LinearSuspensionPlanner：给出唯一挂起点、结果绑定与跨段活跃局部；
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
//   - 只支持「恰好一个 await 出现在变量初始化、其余语句是 int32 局部声明、最后
//     一句 return」的直线函数；不接受分支、循环、try、defer、嵌套 await 或
//     return await；
//   - 不定义显式挂起标记，不改变公开函数 ABI，也不把生成帧冒充为可同步调用的
//     普通函数；
//   - 不实现 Future.get 阻塞等待；恢复只经 onComplete -> ContinuationDispatch。
// ============================================================================

package jasper.translator

import jasper.ast.*

/**
 * 单挂起点直线 int32 函数的内部 AST 发射器。
 *
 * 做什么：把一个受限函数改写成显式状态机类；原函数参数/局部变量成为帧字段，
 *   程序位置由 `__phase` 表示，awaited Future 的回调只请求恢复，恢复段在调度门
 *   分配的工作线程上执行。
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

    /** 仅 int32 结果；更宽类型需要重新审视字段布局与描述符，不能静默复用。 */
    private val INT = JasPrimitiveType("int32")

    /**
     * 判断函数是否属于本片支持形态，并给出人类可读的拒绝原因。
     *
     * 做什么：依次验证静态性、返回类型、直线语句结构、唯一挂起点、结果绑定、
     *   参数/局部类型与命名唯一性；返回 null 才允许调用 [emit]。
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
        val body = function.body?.statements ?: return "function body is required"
        val plan = LinearSuspensionPlanner.plan(function)
        plan.unsupported?.let {
            return "unsupported control flow node ${it.javaClass.simpleName}"
        }
        if (plan.points.size != 1) {
            return "exactly one await is required, found ${plan.points.size}"
        }
        val point = plan.points.single()
        if (point.resultBinding == null) {
            return "await must initialize a local variable"
        }
        if (point.resultType != INT) {
            return "await result type must be int32"
        }
        if (body.lastOrNull() !is JasReturn) {
            return "the last statement must be return"
        }
        if (body.dropLast(1).any { it !is JasVariableStatement }) {
            return "only int32 local declarations may precede the final return"
        }
        val declarations = body.filterIsInstance<JasVariableStatement>()
        if (declarations.any { (it.type != null && it.type != INT) || it.initializer == null }) {
            return "only initialized int32 local declarations are supported"
        }
        // 省略类型的 await 绑定由检查器保证为 int32；其余声明仍要求显式 int32。
        if (declarations.any { it.name == point.resultBinding && it.type == null && point.resultType != INT }) {
            return "await binding type must be int32"
        }
        if (function.parameters.any { !isSupportedParameter(it.type) }) {
            return "parameters must be int32 or std.Future<int32>"
        }
        val names = function.parameters.map { it.name } + declarations.map { it.name }
        if (names.distinct().size != names.size) {
            return "parameter and local names must be unique"
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

        val body = requireNotNull(function.body).statements
        val plan = LinearSuspensionPlanner.plan(function)
        val point = plan.points.single()
        val binding = requireNotNull(point.resultBinding)
        val resultFuture = JasNamedType(FUTURE, listOf(INT))
        val declarations = body.filterIsInstance<JasVariableStatement>()

        // 槽位按「参数在前、局部按源码顺序」固定分配；字段名不直接使用用户名字，
        // 避免生成类与用户局部/字段重名，也让跨段布局在代码中可见。
        val names = function.parameters.map { it.name } + declarations.map { it.name }
        val slots = names.withIndex().associate { it.value to "__slot${it.index}" }

        /** 生成当前帧字段引用。 */
        fun field(name: String) = JasPropertyAccess(JasIdentifier("this"), name)
        /** 生成实例方法调用。 */
        fun invoke(target: JasExpression, name: String, vararg args: JasExpression) =
            JasCall(JasPropertyAccess(target, name), args.toList())
        /** 把表达式包装成语句。 */
        fun statement(expression: JasExpression) = JasExpressionStatement(expression)
        /** 生成帧字段赋值。 */
        fun assign(name: String, value: JasExpression) = statement(JasAssignment(field(name), value))

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
                    else -> error("unsupported unary operator ${expression.op}")
                }
            }
            is JasPropertyAccess -> expression.copy(target = rewrite(expression.target))
            is JasCall -> JasCall(rewrite(expression.target), expression.args.map(::rewrite), expression.isSafe)
            is JasCastExpr -> expression.copy(expression = rewrite(expression.expression))
            else -> error("unsupported frame expression: ${expression.javaClass.simpleName}")
        }

        // 所有用户表达式先验证并转换；失败不会返回半成品 AST。
        val rewritten = declarations.associate { declaration ->
            declaration.name to if (declaration.name == binding) null else
                rewrite(requireNotNull(declaration.initializer))
        }
        val returnValue = rewrite(requireNotNull((body.last() as JasReturn).value))
        val awaited = rewrite(point.expression.operand)

        val properties = mutableListOf<JasDeclaration>(
            JasProperty("__phase", INT, listOf("private"), null, null),
            JasProperty("__outcome", JasNullableType(JasNamedType(FUTURE_OUTCOME)), listOf("private"), null, null),
            JasProperty("__result", resultFuture, listOf("private"), null, null),
            JasProperty("__dispatch", JasNamedType(DISPATCH), listOf("private"), null, null)
        )
        for ((index, name) in names.withIndex()) {
            val type = function.parameters.getOrNull(index)?.type ?: INT
            // 参数若是短名 Future<int32>，生成类统一写成 std.Future<int32>，避免
            // 用户包中同名类型改变字段语义。
            val normalized = if (isFutureInt(type)) resultFuture else type
            properties += JasProperty(slots.getValue(name), normalized, listOf("private"), null, null)
        }

        val constructorBody = mutableListOf<JasStatement>(
            assign("__phase", JasIntLiteral(0, "0")),
            assign("__outcome", JasNullLiteral),
            assign("__result", JasIdentifier("output"))
        )
        function.parameters.forEachIndexed { index, parameter ->
            constructorBody += assign(slots.getValue(parameter.name), JasIdentifier("arg$index"))
        }
        declarations.forEach { constructorBody += assign(slots.getValue(it.name), JasIntLiteral(0, "0")) }
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
                    JasParameter("arg$index", if (isFutureInt(parameter.type)) resultFuture else parameter.type)
                },
            null,
            JasBlock(constructorBody)
        )

        // 第一段：执行挂起点之前的局部初始化，注册完成回调后立即返回工作线程。
        // 等待期间禁止任何阻塞 get；真正的恢复由 accept -> ContinuationDispatch 入队。
        val first = mutableListOf<JasStatement>()
        body.take(point.statementIndex).filterIsInstance<JasVariableStatement>().forEach {
            first += assign(slots.getValue(it.name), requireNotNull(rewritten[it.name]))
        }
        first += assign("__phase", JasIntLiteral(1, "1"))
        first += statement(invoke(awaited, "onComplete", JasIdentifier("this")))
        first += JasReturn(null)

        // 第二段：消费终态快照。成功取 int32 结果；失败原样发布 Failure 对象；
        // 取消发布 Cancelled，而不是伪装成业务失败。
        val ready = JasIdentifier("__ready")
        val second = mutableListOf<JasStatement>(
            JasVariableStatement("__ready", JasNamedType(FUTURE_OUTCOME),
                JasCastExpr(field("__outcome"), JasNamedType(FUTURE_OUTCOME)), false),
            JasIf(invoke(ready, "isCancelled"), JasBlock(listOf(
                statement(invoke(field("__result"), "cancel")),
                JasReturn(null)
            )), null),
            JasIf(invoke(ready, "isFailed"), JasBlock(listOf(
                JasVariableStatement("__failure", JasNullableType(JasNamedType(FAILURE)),
                    invoke(ready, "failure"), false),
                statement(invoke(field("__result"), "fail",
                    JasCastExpr(JasIdentifier("__failure"), JasNamedType(FAILURE)))),
                JasReturn(null)
            )), null),
            assign(slots.getValue(binding), JasCastExpr(invoke(ready, "value"), INT))
        )
        body.drop(point.statementIndex + 1).filterIsInstance<JasVariableStatement>().forEach {
            second += assign(slots.getValue(it.name), requireNotNull(rewritten[it.name]))
        }
        second += statement(invoke(field("__result"), "complete", returnValue))
        second += JasReturn(null)

        // `__phase` 在每段入口判断；两段互斥，恢复段不会重放第一段，操作数只求值一次。
        // 入口先看结果是否已被外部终结（作用域取消）：此时不再执行任何业务段，
        // 直接落到 finally 关闭调度门并标记 settled，等待中的帧因此能被回收。
        val runBody = JasBlock(listOf(
            JasIf(invoke(field("__result"), "isDone"), JasBlock(listOf(JasReturn(null))), null),
            JasIf(JasBinaryOp(field("__phase"), "==", JasIntLiteral(0, "0")), JasBlock(first), null),
            JasIf(JasBinaryOp(field("__phase"), "==", JasIntLiteral(1, "1")), JasBlock(second), null)
        ))
        val caught = JasIdentifier("__error")
        // 段内任何未捕获异常都必须变成结果 Future 的失败，并在 finally 中结束调度门、
        // 标记 settled；否则等待者会永远看到 Pending，TaskGroup.close 也无法回收。
        val guardedRun = JasTry(
            runBody,
            listOf(JasCatchClause(
                JasParameter("__error", JasNamedType("java.lang.Throwable")),
                JasBlock(listOf(
                    statement(invoke(field("__result"), "fail", JasNew(
                        JasNamedType(FAILURE),
                        listOf(invoke(caught, "toString"), JasStringLiteral("platform"))
                    )))
                ))
            )),
            JasBlock(listOf(
                JasIf(invoke(field("__result"), "isDone"), JasBlock(listOf(
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

    /** 参数只接受 int32 或 std.Future<int32>；短名 Future 由同一文件 import 解析。 */
    private fun isSupportedParameter(type: JasType?): Boolean =
        type == INT || isFutureInt(type)

    /** 识别 Future<int32> 的两种常见书写；包身份最终仍由生成类重新类型检查确认。 */
    private fun isFutureInt(type: JasType?): Boolean {
        val named = type as? JasNamedType ?: return false
        return named.name in setOf("Future", FUTURE) && named.typeArguments == listOf(INT)
    }
}
