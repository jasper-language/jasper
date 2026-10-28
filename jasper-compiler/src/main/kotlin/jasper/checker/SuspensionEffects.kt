// S4-C 挂起能力推导：沿已解析的直接调用边传播，未知动态边不能当作同步。
package jasper.checker

import jasper.ast.*
import java.util.Collections
import java.util.IdentityHashMap

/** 三值效果：确认可能挂起、在已覆盖闭合调用图内同步、信息不足。 */
enum class SuspensionEffect { SUSPENDING, SYNCHRONOUS, UNKNOWN }

/** 按声明对象身份索引，重载或结构相同的声明不能因名字/结构相同而合并。 */
class SuspensionEffects internal constructor(private val effects: IdentityHashMap<JasFunction, SuspensionEffect>) {
    /** 不在分析单元中的函数没有已知效果，不能默认其同步。 */
    operator fun get(function: JasFunction): SuspensionEffect = effects[function] ?: SuspensionEffect.UNKNOWN
}

/**
 * 根据类型检查器回填的 awaitResultType 与 resolvedFunction 推导直接调用的挂起能力。
 * 本片不选择显式标记名称、不改变返回类型或 ABI；接口动态分派、外部函数及未覆盖
 * AST 结构保守返回 UNKNOWN。只有无未知边的闭合调用图可以判为 SYNCHRONOUS。
 */
object SuspensionEffectAnalysis {
    private class Facts {
        var suspends = false
        var unknown = false
        val callees: MutableSet<JasFunction> = Collections.newSetFromMap(IdentityHashMap())
    }

    /** 收集编译单元中顶层及类型成员函数，跨文件统一求解，避免逐文件漏掉调用链。 */
    fun analyzeSources(sources: Iterable<JasSourceFile>): SuspensionEffects {
        val functions = mutableListOf<JasFunction>()
        /** 递归进入类型成员，构造器与属性由调用扫描保守处理，不伪装成已知函数。 */
        fun collect(declaration: JasDeclaration) {
            when (declaration) {
                is JasFunction -> functions += declaration
                is JasClass -> declaration.members.forEach(::collect)
                is JasInterface -> declaration.members.forEach(::collect)
                is JasEnum -> declaration.members.forEach(::collect)
                else -> Unit
            }
        }
        sources.forEach { source -> source.declarations.forEach(::collect) }
        return analyze(functions)
    }

    /** 最小不动点传播支持前向引用与递归环；声明身份而非函数名决定调用边。 */
    fun analyze(functions: List<JasFunction>): SuspensionEffects {
        val facts = IdentityHashMap<JasFunction, Facts>()
        for (function in functions) {
            facts[function] = Facts().also { scan(function.body, it) }
        }
        val effects = IdentityHashMap<JasFunction, SuspensionEffect>()
        for ((function, local) in facts) {
            effects[function] = when {
                local.suspends -> SuspensionEffect.SUSPENDING
                local.unknown -> SuspensionEffect.UNKNOWN
                else -> SuspensionEffect.SYNCHRONOUS
            }
        }
        var changed: Boolean
        do {
            changed = false
            for ((function, local) in facts) {
                val next = when {
                    local.suspends || local.callees.any { effects[it] == SuspensionEffect.SUSPENDING } -> SuspensionEffect.SUSPENDING
                    local.unknown || local.callees.any { effects[it] == null || effects[it] == SuspensionEffect.UNKNOWN } -> SuspensionEffect.UNKNOWN
                    else -> SuspensionEffect.SYNCHRONOUS
                }
                if (effects[function] != next) { effects[function] = next; changed = true }
            }
        } while (changed)
        return SuspensionEffects(effects)
    }

    /** 扫描已覆盖的结构；未实现的结构不猜测其子表达式无挂起。 */
    private fun scan(node: JasNode?, facts: Facts) {
        when (node) {
            null -> facts.unknown = true
            is JasBlock -> node.statements.forEach { scan(it, facts) }
            is JasReturn -> node.value?.let { scan(it, facts) }
            is JasVariableStatement -> node.initializer?.let { scan(it, facts) }
            is JasExpressionStatement -> scan(node.expression, facts)
            is JasUnaryOp -> {
                if (node.awaitResultType != null) facts.suspends = true
                scan(node.operand, facts)
            }
            is JasBinaryOp -> { scan(node.left, facts); scan(node.right, facts) }
            is JasIf -> {
                scan(node.condition, facts); scan(node.thenBody, facts)
                node.elseBody?.let { scan(it, facts) }
            }
            is JasWhile -> {
                scan(node.condition, facts); scan(node.body, facts)
                // 循环后置块尚未覆盖：不猜测其中无挂起。
                if (node.suffix != null) facts.unknown = true
            }
            is JasDoWhile -> { scan(node.body, facts); scan(node.condition, facts) }
            is JasForStatement -> {
                // 三段都可省略；省略的段没有子表达式，不能当作未知结构。
                node.init?.let { scan(it, facts) }
                node.condition?.let { scan(it, facts) }
                node.update?.let { scan(it, facts) }
                scan(node.body, facts)
                if (node.suffix != null) facts.unknown = true
            }
            is JasBreakStatement, is JasContinueStatement -> Unit
            is JasAssignment -> { scan(node.target, facts); scan(node.value, facts) }
            is JasPostfixIncDec -> scan(node.target, facts)
            is JasCall -> {
                val selected = node.resolvedFunction
                val direct = node.target is JasIdentifier || selected?.modifiers?.contains("static") == true
                if (selected != null && direct) facts.callees += selected else facts.unknown = true
                // 调用目标是成员时仍需考虑接收者求值；不把方法选择本身当 getter。
                val target = node.target
                scan(if (target is JasPropertyAccess) target.target else target, facts)
                node.args.forEach { scan(it, facts) }
            }
            is JasIdentifier, is JasIntLiteral, is JasFloatLiteral, is JasStringLiteral,
            is JasBoolLiteral, is JasCharLiteral, JasNullLiteral -> Unit
            else -> facts.unknown = true
        }
    }
}
