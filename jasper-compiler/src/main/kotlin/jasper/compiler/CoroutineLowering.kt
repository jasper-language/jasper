// ============================================================================
// CoroutineLowering.kt —— S4-C 编译期协程降级编排（第八片起，第十片扩展分支，第十一片扩展 while）
// ============================================================================
// 职责：
//   在类型检查与挂起效果分析完成之后、AST -> IR 之前，挑选属于「多挂起点、条件
//   分支与 while 循环 int32 函数」的挂起函数，交给 LinearCoroutineEmitter 生成内部状态机类；其余
//   真实挂起形态统一产生明确诊断，禁止把 Future 同步透传给后端。
//
// 所属层次：
//   compiler 编排层。向上被 JasperCompiler 调用，向下依赖 checker 的效果快照、
//   translator 的发射器以及 ast；不反向依赖 backend。
//
// 与谁协作：
//   - SuspensionEffectAnalysis / SuspensionEffects：判断函数是否可能挂起；
//   - LinearCoroutineEmitter：支持范围与生成帧 AST 的唯一事实来源；
//   - SuspendingAwaitScanner：降级后残留真实 await 的安全网；
//   - JasperCompiler：注册生成类、重新类型检查、再交给既有 IR/JVM 后端。
//
// 对外契约：
//   - lower(...) 是纯内存变换：输入 AST 不被原地修改，返回新的源文件列表；
//   - 只要 errors 非空，调用方必须停止编译，不得使用 sources 继续生成；
//   - 生成类只放在原文件同包顶层，不嵌套、不改变原函数公开签名，也不引入新语法。
//
// 设计边界：
//   - 支持范围以 LinearCoroutineEmitter / CoroutineBlockPlanner 为准：局部声明、
//     局部赋值、if/else、while / do-while / 三段式 for、无标签 break/continue、return，
//     await 可在局部写入右侧、条件与 return 值中（按求值顺序提升为临时局部）；for-in、
//     异常/defer、实例 / 接收者调用实参中的 await、对其它挂起函数的同步调用等全部
//     诊断，而不是退化为同步求值；
//   - 函数被发射后，其原声明从生成 AST 中移除：生成帧需要 executor 与输出 Future，
//     还不是公开源码调用 ABI；任何调用方都会因自身也变为 SUSPENDING 而得到诊断；
//   - 本层不生成字节码、不选择显式挂起标记名称、不改公开 ABI/文法。
// ============================================================================

package jasper.compiler

import jasper.ast.*
import jasper.checker.SuspensionEffect
import jasper.checker.SuspensionEffects
import jasper.translator.LinearCoroutineEmitter
import java.util.IdentityHashMap

/**
 * 一个已发射的内部协程帧。
 *
 * 做什么：记录生成类、来源函数与所属包，供编译器注册符号、重新类型检查和证据定位。
 * 什么时候用：CoroutineLowering.lower 返回后由 JasperCompiler 消费。
 * 失败模式：纯数据；declaration 已通过发射器支持范围检查（仍可能被二次类型检查拒绝）。
 * 为什么保留来源信息：生成类名是编译器约定，不应让调用方靠字符串猜来源。
 * 为什么带 [imports]：生成类追加在来源文件中翻译，源码写出的类型名（如 catch 的捕获类型）按
 *   来源文件的 import 解析；二次类型检查必须看到同一组 import，否则同一名字在两处解析不一致。
 */
data class GeneratedCoroutineFrame(
    val packageName: String?,
    val ownerName: String?,
    val functionName: String,
    val className: String,
    val declaration: JasClass,
    val imports: List<JasDeclaration> = emptyList()
)

/**
 * 降级结果：新的源文件列表、生成帧清单与诊断。
 *
 * 做什么：让调用方在一次操作中拿到「可否继续编译」与「新增了哪些类」。
 * 什么时候用：JasperCompiler 的 compileAll / compileToPipeline。
 * 失败模式：errors 非空时 sources/frames 只用于定位，不应继续后端生成。
 */
data class CoroutineLoweringResult(
    val sources: List<JasSourceFile>,
    val frames: List<GeneratedCoroutineFrame>,
    val errors: List<CompileError>
)

/**
 * 编译期协程降级器（无状态单例）。
 *
 * 做什么：按源文件遍历每个函数，依据挂起效果与线性支持范围决定生成、保留或诊断；
 *   最后扫描整棵 AST，确认没有未处理的真实 await 残留。
 * 什么时候用：类型检查成功后、IR 翻译前，且必须使用同一批源文件的共享效果快照。
 * 前置条件：源的 AST 已由 TypeChecker 回填 awaitResultType / resolvedFunction。
 * 失败模式：不支持形态进入 errors，不抛异常；发射器内部不支持表达式也会被转成诊断。
 * 为什么单独成层：把「选择哪些函数可发射」与「如何发射」「如何写字节码」分开，
 *   隔离后续扩展控制流时的变化点，也避免 JasperCompiler 继续膨胀成上帝类。
 */
object CoroutineLowering {
    /** 稳定诊断前缀：既有前端测试与文档依赖这句「尚未实现」的明确措辞。 */
    private const val UNSUPPORTED_PREFIX = "non-blocking await lowering is not implemented yet"

    /**
     * 执行降级。
     *
     * 做什么：先统计重载与类型名冲突，再逐文件改写函数声明并追加生成类，最后用
     *   SuspendingAwaitScanner 检查残留真实 await。
     * 什么时候用：所有真实编译入口，在类型检查之后调用一次。
     * 前置条件：effects 必须覆盖 sources 中全部函数；否则未知效果不会被当作可挂起。
     * 失败模式：不抛异常；生成器抛出的 IllegalArgumentException 被收敛为带位置的诊断。
     * 为什么保留未挂起函数原样：本片不改变同步语义，普通值 await 的兼容路径不能被误删。
     */
    fun lower(sources: List<JasSourceFile>, effects: SuspensionEffects): CoroutineLoweringResult {
        val errors = mutableListOf<CompileError>()
        val frames = mutableListOf<GeneratedCoroutineFrame>()
        val reportedAwaits = IdentityHashMap<JasUnaryOp, Boolean>()

        val overloads = collectOverloads(sources)
        val usedTypeNames = collectTypeNames(sources)
        val rewritten = mutableListOf<JasSourceFile>()

        for (source in sources) {
            val localFrames = mutableListOf<JasClass>()
            val declarations = source.declarations.mapNotNull { declaration ->
                when (declaration) {
                    is JasFunction -> lowerFunction(
                        source, null, declaration, false, effects, overloads, usedTypeNames,
                        frames, localFrames, errors, reportedAwaits
                    )
                    is JasClass -> declaration.copy(members = lowerMembers(
                        source, declaration, declaration.members, true, effects, overloads,
                        usedTypeNames, frames, localFrames, errors, reportedAwaits
                    ))
                    is JasInterface -> declaration.copy(members = lowerMembers(
                        source, declaration, declaration.members, true, effects, overloads,
                        usedTypeNames, frames, localFrames, errors, reportedAwaits
                    ))
                    is JasEnum -> declaration.copy(members = lowerMembers(
                        source, declaration, declaration.members, true, effects, overloads,
                        usedTypeNames, frames, localFrames, errors, reportedAwaits
                    ))
                    else -> declaration
                }
            }
            rewritten += source.copy(declarations = declarations + localFrames)
        }

        // 安全网：非函数位置（字段初始化器、构造器、lambda 等）或未来新增节点若留下
        // 真实 await，必须显式失败；不能因为效果分析没有覆盖就偷偷同步透传。
        for (source in rewritten) {
            for (await in SuspendingAwaitScanner.scan(source)) {
                if (reportedAwaits.containsKey(await)) continue
                errors += CompileError.Type(
                    "$UNSUPPORTED_PREFIX: await at this position is outside a supported " +
                        "int32 coroutine function",
                    line = await.sourceLine,
                    column = await.sourceColumn
                )
            }
        }

        return CoroutineLoweringResult(rewritten, frames, errors)
    }

    /** 处理一个类/接口/枚举的成员；只接管函数，其它成员原样保留。 */
    private fun lowerMembers(
        source: JasSourceFile,
        owner: JasDeclaration,
        members: List<JasDeclaration>,
        requireStatic: Boolean,
        effects: SuspensionEffects,
        overloads: Map<String, Int>,
        usedTypeNames: MutableSet<String>,
        frames: MutableList<GeneratedCoroutineFrame>,
        localFrames: MutableList<JasClass>,
        errors: MutableList<CompileError>,
        reportedAwaits: IdentityHashMap<JasUnaryOp, Boolean>
    ): List<JasDeclaration> {
        val ownerName = when (owner) {
            is JasClass -> owner.name
            is JasInterface -> owner.name
            is JasEnum -> owner.name
            else -> null
        }
        return members.mapNotNull { member ->
            if (member is JasFunction) {
                lowerFunction(
                    source, ownerName, member, requireStatic, effects, overloads,
                    usedTypeNames, frames, localFrames, errors, reportedAwaits
                )
            } else {
                member
            }
        }
    }

    /**
     * 处理单个函数：返回 null 表示它已被生成帧替代。
     *
     * 为什么返回可空：调用方用 mapNotNull 同时完成「移除原声明」与「保留其它声明」，
     * 避免二次生成或漏掉原函数导致真实 await 进入后端。
     */
    private fun lowerFunction(
        source: JasSourceFile,
        ownerName: String?,
        function: JasFunction,
        requireStatic: Boolean,
        effects: SuspensionEffects,
        overloads: Map<String, Int>,
        usedTypeNames: MutableSet<String>,
        frames: MutableList<GeneratedCoroutineFrame>,
        localFrames: MutableList<JasClass>,
        errors: MutableList<CompileError>,
        reportedAwaits: IdentityHashMap<JasUnaryOp, Boolean>
    ): JasFunction? {
        if (effects[function] != SuspensionEffect.SUSPENDING) return function

        val key = overloadKey(source.packageName, ownerName, function.name)
        val reason = when {
            (overloads[key] ?: 0) > 1 -> "overloaded suspending functions are not supported"
            else -> LinearCoroutineEmitter.unsupportedReason(function, requireStatic)
                ?: synchronousSuspendingCall(function, effects)
        }
        if (reason != null) {
            reportFunctionError(errors, source, ownerName, function, reason)
            SuspendingAwaitScanner.scanFunction(function).forEach { reportedAwaits[it] = true }
            return function
        }

        val className = generatedClassName(ownerName, function.name)
        val typeKey = typeKey(source.packageName, className)
        if (!usedTypeNames.add(typeKey)) {
            reportFunctionError(
                errors, source, ownerName, function,
                "generated frame name '$className' conflicts with an existing type"
            )
            SuspendingAwaitScanner.scanFunction(function).forEach { reportedAwaits[it] = true }
            return function
        }

        val generated = try {
            LinearCoroutineEmitter.emit(function, className, requireStatic)
        } catch (error: IllegalArgumentException) {
            reportFunctionError(
                errors, source, ownerName, function,
                error.message ?: "expression cannot be lowered"
            )
            SuspendingAwaitScanner.scanFunction(function).forEach { reportedAwaits[it] = true }
            return function
        }
        frames += GeneratedCoroutineFrame(source.packageName, ownerName, function.name, className, generated, source.imports)
        localFrames += generated
        return null
    }

    /**
     * 查找函数体中对 SUSPENDING 函数的同步调用。
     *
     * 做什么：形态已通过发射器检查后，再遍历局部写入、分支 / 循环条件与返回值中的调用；若被调函数
     *   本身是 SUSPENDING，返回拒绝原因。
     * 为什么必须拒绝：被发射的挂起函数原声明会被生成帧替代，同步调用既没有可链接目标，
     *   也会把挂起语义退化为同步求值；本片没有「调用挂起函数」的帧间协议，只能诊断。
     * 失败模式：纯检查；发射器不支持的表达式不在此深入，由发射器自身拒绝。
     */
    private fun synchronousSuspendingCall(function: JasFunction, effects: SuspensionEffects): String? {
        /** 深度优先返回首个被调函数为 SUSPENDING 的调用；无则 null。 */
        fun find(expression: JasExpression?): JasCall? = when (expression) {
            is JasCall -> {
                val callee = expression.resolvedFunction
                if (callee != null && effects[callee] == SuspensionEffect.SUSPENDING) expression
                else find(expression.target) ?: expression.args.firstNotNullOfOrNull(::find)
            }
            is JasBinaryOp -> find(expression.left) ?: find(expression.right)
            is JasUnaryOp -> find(expression.operand)
            is JasPropertyAccess -> find(expression.target)
            is JasCastExpr -> find(expression.expression)
            else -> null
        }
        /**
         * 按语句递归查找；分支条件与两条分支体（第十片起支持 if/else）、循环条件与循环体
         * （第十一片起支持 while，第十二片起支持 do-while 与三段式 for 的初始化 / 步进段，
         * 第十六片起循环可带标签，第十七片起 try 体 / finally 体）都要覆盖。
         */
        fun search(statement: JasStatement): JasCall? = when (statement) {
            is JasVariableStatement -> find(statement.initializer)
            is JasExpressionStatement -> (statement.expression as? JasAssignment)?.let { find(it.value) }
                ?: find(statement.expression)
            is JasReturn -> find(statement.value)
            is JasIf -> find(statement.condition)
                ?: statement.thenBody.statements.firstNotNullOfOrNull(::search)
                ?: statement.elseBody?.statements?.firstNotNullOfOrNull(::search)
            is JasWhile -> find(statement.condition)
                ?: statement.body.statements.firstNotNullOfOrNull(::search)
            is JasDoWhile -> statement.body.statements.firstNotNullOfOrNull(::search)
                ?: find(statement.condition)
            is JasForStatement -> statement.init?.let(::search)
                ?: find(statement.condition)
                ?: (statement.update as? JasAssignment)?.let { find(it.value) }
                ?: statement.body.statements.firstNotNullOfOrNull(::search)
            is JasBlock -> statement.statements.firstNotNullOfOrNull(::search)
            is JasLabeledStatement -> search(statement.statement)
            is JasTry -> statement.body.statements.firstNotNullOfOrNull(::search)
                ?: statement.catches.firstNotNullOfOrNull { clause -> clause.body.statements.firstNotNullOfOrNull(::search) }
                ?: statement.finallyBody?.statements?.firstNotNullOfOrNull(::search)
            else -> null
        }
        val call = function.body?.statements.orEmpty().firstNotNullOfOrNull(::search) ?: return null
        val name = call.resolvedFunction?.name ?: "<unknown>"
        return "synchronous call to suspending function '$name' is not supported"
    }

    /** 生成类名把来源写进名字，便于证据和反射驱动稳定定位；不使用随机/动态名称。 */
    private fun generatedClassName(ownerName: String?, functionName: String): String =
        if (ownerName.isNullOrEmpty()) "${functionName}_Coroutine" else "${ownerName}_${functionName}_Coroutine"

    /** 诊断尽量带源函数位置；合成节点无位置时退化为 0（格式化层会省略）。 */
    private fun reportFunctionError(
        errors: MutableList<CompileError>,
        source: JasSourceFile,
        ownerName: String?,
        function: JasFunction,
        reason: String
    ) {
        val qualified = listOfNotNull(source.packageName, ownerName, function.name).joinToString(".")
        val line = function.sourceLine.takeIf { it > 0 }
            ?: function.body?.statements?.firstOrNull()?.sourceLine ?: 0
        val column = function.sourceColumn.takeIf { it > 0 }
            ?: function.body?.statements?.firstOrNull()?.sourceColumn ?: 0
        errors += CompileError.Type(
            "$UNSUPPORTED_PREFIX: suspending function '$qualified' has an unsupported shape: $reason",
            line = line,
            column = column
        )
    }

    /** 按「包 + 所属类型 + 函数名」统计重载；顶层函数所属类型记为空串。 */
    private fun collectOverloads(sources: List<JasSourceFile>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        sources.forEach { source ->
            source.declarations.forEach { declaration ->
                when (declaration) {
                    is JasFunction -> {
                        val key = overloadKey(source.packageName, null, declaration.name)
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                    is JasClass -> countMembers(source, declaration.name, declaration.members, counts)
                    is JasInterface -> countMembers(source, declaration.name, declaration.members, counts)
                    is JasEnum -> countMembers(source, declaration.name, declaration.members, counts)
                    else -> Unit
                }
            }
        }
        return counts
    }

    /** 统计类型成员中的同名函数。 */
    private fun countMembers(
        source: JasSourceFile,
        ownerName: String,
        members: List<JasDeclaration>,
        counts: MutableMap<String, Int>
    ) {
        members.filterIsInstance<JasFunction>().forEach { function ->
            val key = overloadKey(source.packageName, ownerName, function.name)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    /** 重载身份键；ownerName 为 null 表示顶层函数，避免与同名类成员合并。 */
    private fun overloadKey(packageName: String?, ownerName: String?, functionName: String): String =
        "${packageName ?: ""}|${ownerName ?: "<top>"}|$functionName"

    /** 收集现有顶层类型名，防止生成类与用户类型冲突。 */
    private fun collectTypeNames(sources: List<JasSourceFile>): MutableSet<String> {
        val names = mutableSetOf<String>()
        sources.forEach { source ->
            source.declarations.forEach { declaration ->
                when (declaration) {
                    is JasClass -> names += typeKey(source.packageName, declaration.name)
                    is JasInterface -> names += typeKey(source.packageName, declaration.name)
                    is JasEnum -> names += typeKey(source.packageName, declaration.name)
                    is JasAnnotationType -> names += typeKey(source.packageName, declaration.name)
                    else -> Unit
                }
            }
        }
        return names
    }

    /** 类型身份键与 SymbolTable 的口径一致：包名 + 简单名。 */
    private fun typeKey(packageName: String?, simpleName: String): String =
        if (packageName.isNullOrEmpty()) simpleName else "$packageName.$simpleName"
}
