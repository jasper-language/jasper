// =============================================================================
// AnnotationValidator.kt —— 注解规则执行器（Q32 / C17）
// =============================================================================
// 职责：
//   把 [AnnotationRuleSet] 里的声明式规则应用到一份 AST 的注解使用上，产出
//   [AnnotationError] 编译诊断：目标不匹配、重复/互斥注解、未知/重复/缺失元素、
//   非常量元素值，以及跨成员的组合约束（如 @Entity 必须有 @Id 属性）。
//
// 所属层次：annotations(2)。依赖 AST 与同包的规则/诊断数据，不依赖 checker、
//   translator 或后端，保持依赖单向。
//
// 与谁协作：
//   - AnnotationProcessor 在遍历类/接口成员、登记注解索引的同一趟调用本类；
//   - 诊断最终由 JasperCompiler 映射成 CompileError.Annotation，进入统一错误列表；
//     只要存在注解错误，编译器不会进入 IR/字节码阶段，因此不会产生 class 产物。
//
// 设计模式：Specification / Rule Object（规则对象）+ 注册表。
//   隔离的变化点是「校验规则的种类与组合」：校验算法遍历规则，新增规则只是新增数据；
//   算法本身不出现任何具体注解名，避免业务分支散落。
//
// 边界：
//   - 只校验注册表登记过的注解；未知注解静默跳过，保持向后兼容；
//   - 只做静态 AST 判断，不做类型解析/常量折叠/运行期反射；
//   - 重复注解按「同一成员上的同一简单名」判定；不同成员各出现一次是合法的。
// =============================================================================

package jasper.annotations

import jasper.ast.JasAnnotationArgument
import jasper.ast.JasAnnotationNamedArg
import jasper.ast.JasAnnotationPositionalArg
import jasper.ast.JasAnnotationUse
import jasper.ast.JasBoolLiteral
import jasper.ast.JasCharLiteral
import jasper.ast.JasExpression
import jasper.ast.JasFloatLiteral
import jasper.ast.JasIntLiteral
import jasper.ast.JasStringLiteral

/**
 * 校验器看到的一个成员视图。
 *
 * 做什么：把类/接口成员裁剪成「目标种类 + 名字 + 注解列表」，让校验器不必关心
 *   成员是属性还是方法的具体 AST 子类。
 * 什么时候用：AnnotationProcessor 在调用 [AnnotationValidator.validateContainer] 前构造。
 * 前置条件：target 只能是 [AnnotationTarget.PROPERTY] 或 [AnnotationTarget.FUNCTION]。
 * 失败模式：无（纯数据）。
 * 为什么这样设计：校验逻辑只依赖最小信息面，避免把整棵 AST 传进规则执行器，
 *   降低耦合，也让规则单测可以手工构造成员视图。
 */
data class AnnotatedMember(
    val target: AnnotationTarget,
    val name: String,
    val annotations: List<JasAnnotationUse>
)

/**
 * 注解规则执行器。
 *
 * 做什么：对容器（类/接口）及其成员的注解使用执行规则校验，并把诊断追加到调用方列表。
 * 什么时候用：注解处理阶段遍历到类或接口声明时。
 * 前置条件：注解名可为限定名（内部按简单名匹配）；规则集不可变。
 * 失败模式：不抛异常；所有问题以 [AnnotationError] 返回，便于与其它阶段错误聚合。
 * 为什么这样设计：把「规则数据」与「执行算法」分开，新增注解族只改规则表；
 *   校验器保持无状态，可安全复用于单文件与多文件编译。
 */
class AnnotationValidator(
    private val ruleSet: AnnotationRuleSet = AnnotationRuleSet.DEFAULT
) {

    /**
     * 校验一个类或接口容器及其成员。
     *
     * 做什么：依次校验容器自身的注解、每个成员的注解，再执行跨成员的组合约束。
     * 什么时候用：AnnotationProcessor.processClass / processInterface 中调用一次。
     * 前置条件：members 已按源码顺序提供；容器注解来自声明的 modifiers。
     * 失败模式：不抛异常；错误追加到 errors（可能一次产生多条，便于用户一次修完）。
     * 为什么这样设计：组合约束需要同时看到容器注解和成员注解，所以入口放在容器级；
     *   单成员规则仍由私有方法统一执行，避免两套判定逻辑漂移。
     */
    fun validateContainer(
        ownerName: String,
        containerTarget: AnnotationTarget,
        containerAnnotations: List<JasAnnotationUse>,
        members: List<AnnotatedMember>,
        errors: MutableList<AnnotationError>
    ) {
        validateDeclaration(ownerName, containerTarget, ownerName, containerAnnotations, errors)
        for (member in members) {
            validateDeclaration(ownerName, member.target, member.name, member.annotations, errors)
        }
        validateConstraints(ownerName, containerTarget, containerAnnotations, members, errors)
    }

    /**
     * 校验一个声明（容器或成员）上的注解列表。
     *
     * 做什么：按简单名分组后检查目标、重复、互斥与元素规则；未登记的注解直接忽略。
     * 什么时候用：validateContainer 内部对容器/成员逐个调用。
     * 前置条件：annotations 保持源码顺序，便于稳定地定位第一条重复/冲突。
     * 失败模式：不抛异常；问题写入 errors。
     * 为什么这样设计：所有声明共用同一套判定，避免「类注解一套、属性注解另一套」的重复逻辑。
     */
    private fun validateDeclaration(
        ownerName: String,
        target: AnnotationTarget,
        declarationName: String,
        annotations: List<JasAnnotationUse>,
        errors: MutableList<AnnotationError>
    ) {
        // 只保留注册过的注解，并按简单名分组。LinkedHashMap 保留源码首次出现顺序，
        // 保证同一份源码重复编译时诊断顺序稳定。
        val known = linkedMapOf<String, MutableList<JasAnnotationUse>>()
        for (annotation in annotations) {
            val name = simpleName(annotation.name)
            if (ruleSet.ruleFor(name) == null) continue
            known.getOrPut(name) { mutableListOf() }.add(annotation)
        }

        for ((name, uses) in known) {
            val rule = ruleSet.ruleFor(name) ?: continue
            val first = uses.first()

            if (target !in rule.targets) {
                errors.add(
                    AnnotationError(
                        "@$name is not allowed on ${target.diagnosticName} '$declarationName' " +
                            "(allowed targets: ${rule.targets.joinToString(", ") { it.diagnosticName }})",
                        first.sourceLine,
                        first.sourceColumn
                    )
                )
            }

            if (uses.size > 1 && !rule.repeatable) {
                val duplicate = uses[1]
                errors.add(
                    AnnotationError(
                        "Duplicate @$name on ${target.diagnosticName} '$declarationName': " +
                            "this annotation is not repeatable",
                        duplicate.sourceLine,
                        duplicate.sourceColumn
                    )
                )
            }

            for (use in uses) {
                validateElements(name, rule, use, errors)
            }
        }

        // 互斥注解：只在字典序较小的一侧报告一次，避免 A/B 互相声明冲突时产生两条相同诊断。
        // 两个方向都看：既接受「本方规则声明冲突」，也接受「对方规则反向声明」，
        // 这样规则表只写单向冲突也不会漏报。
        for ((name, uses) in known) {
            val rule = ruleSet.ruleFor(name) ?: continue
            val conflict = rule.conflictsWith.firstOrNull { other -> other in known }
                ?: known.keys.firstOrNull { other ->
                    other != name && ruleSet.ruleFor(other)?.conflictsWith?.contains(name) == true
                }
                ?: continue
            if (name < conflict) {
                val first = uses.first()
                errors.add(
                    AnnotationError(
                        "Conflicting annotations @$name and @$conflict on " +
                            "${target.diagnosticName} '$ownerName.$declarationName'",
                        first.sourceLine,
                        first.sourceColumn
                    )
                )
            }
        }
    }

    /**
     * 校验单个注解使用的元素（命名参数 + 位置参数）。
     *
     * 做什么：把实参按名字归一化（位置参数按规则表元素顺序映射），再检查未知/重复/
     *   缺失元素、非常量值与常量类型。
     * 什么时候用：validateDeclaration 遍历每个已注册注解时。
     * 前置条件：rule.name 与注解简单名一致。
     * 失败模式：不抛异常；每个问题单独追加诊断，继续检查剩余元素。
     * 为什么这样设计：先归一化成「元素名 -> 值」再统一检查，位置/命名两种写法共享
     *   同一套规则，避免各写一遍导致行为分叉（隔离「实参书写形态」这一变化点）。
     */
    private fun validateElements(
        annotationName: String,
        rule: AnnotationRule,
        annotation: JasAnnotationUse,
        errors: MutableList<AnnotationError>
    ) {
        val provided = linkedMapOf<String, JasExpression>()
        val positionalNames = rule.positionalElementNames
        var positionalIndex = 0

        for (argument in annotation.arguments) {
            when (argument) {
                is JasAnnotationNamedArg -> {
                    val name = argument.name
                    if (rule.elements[name] == null) {
                        errors.add(
                            AnnotationError(
                                "Unknown element '$name' on @$annotationName" +
                                    allowedElementsSuffix(rule),
                                argument.sourceLine,
                                argument.sourceColumn
                            )
                        )
                        continue
                    }
                    if (provided.put(name, argument.value) != null) {
                        reportDuplicateElement(annotationName, name, argument, errors)
                    }
                }
                is JasAnnotationPositionalArg -> {
                    if (positionalIndex >= positionalNames.size) {
                        errors.add(
                            AnnotationError(
                                "Too many positional arguments for @$annotationName" +
                                    allowedElementsSuffix(rule),
                                argument.sourceLine,
                                argument.sourceColumn
                            )
                        )
                        positionalIndex++
                        continue
                    }
                    val name = positionalNames[positionalIndex]
                    positionalIndex++
                    if (provided.put(name, argument.value) != null) {
                        reportDuplicateElement(annotationName, name, argument, errors)
                    }
                }
            }
        }

        for ((name, elementRule) in rule.elements) {
            val value = provided[name]
            if (value == null) {
                if (elementRule.required) {
                    errors.add(
                        AnnotationError(
                            "@$annotationName is missing required element '$name' " +
                                "(${elementRule.type.diagnosticName} constant)",
                            annotation.sourceLine,
                            annotation.sourceColumn
                        )
                    )
                }
                continue
            }
            if (!isCompileTimeConstant(value)) {
                errors.add(
                    AnnotationError(
                        "Element '$name' of @$annotationName must be a compile-time constant",
                        annotation.sourceLine,
                        annotation.sourceColumn
                    )
                )
                continue
            }
            if (!elementRule.type.matches(value)) {
                errors.add(
                    AnnotationError(
                        "Element '$name' of @$annotationName must be a " +
                            "${elementRule.type.diagnosticName} constant, got ${describeValue(value)}",
                        annotation.sourceLine,
                        annotation.sourceColumn
                    )
                )
            }
        }
    }

    /**
     * 报告「同一元素被指定多次」。
     *
     * 做什么：命名实参与位置实参归一化后都会走同一张 provided 表，重复写入即报错。
     * 什么时候用：validateElements 的命名/位置两个分支。
     * 前置条件：调用方已确认 put 返回了旧值（即确实重复）。
     * 失败模式：无。
     * 为什么抽出来：两种书写形态的重复诊断必须逐字一致，集中一处避免文案漂移。
     */
    private fun reportDuplicateElement(
        annotationName: String,
        elementName: String,
        argument: JasAnnotationArgument,
        errors: MutableList<AnnotationError>
    ) {
        errors.add(
            AnnotationError(
                "Element '$elementName' of @$annotationName is specified more than once",
                argument.sourceLine,
                argument.sourceColumn
            )
        )
    }

    /**
     * 执行跨成员组合约束。
     *
     * 做什么：对容器上每个已注册注解的 constraints，检查成员注解集合是否满足要求。
     * 什么时候用：容器自身与成员规则都校验完之后。
     * 前置条件：members 包含容器内所有属性和方法（其余成员与注解约束无关）。
     * 失败模式：不抛异常；不满足时在容器注解位置报告。
     * 为什么这样设计：约束可能涉及任意成员，只有容器级入口能看到全貌；把判定集中在这里，
     *   避免 Order 依赖（例如必须等所有成员遍历完才知道有没有 @Id）。
     */
    private fun validateConstraints(
        ownerName: String,
        containerTarget: AnnotationTarget,
        containerAnnotations: List<JasAnnotationUse>,
        members: List<AnnotatedMember>,
        errors: MutableList<AnnotationError>
    ) {
        for (annotation in containerAnnotations) {
            val name = simpleName(annotation.name)
            val rule = ruleSet.ruleFor(name) ?: continue
            for (constraint in rule.constraints) {
                when (constraint) {
                    is AnnotationConstraint.RequiresMemberAnnotation -> {
                        val satisfied = members.any { member ->
                            member.target == constraint.target && member.annotations.any {
                                simpleName(it.name) == constraint.annotationName
                            }
                        }
                        if (!satisfied) {
                            errors.add(
                                AnnotationError(
                                    "@$name ${containerTarget.diagnosticName} '$ownerName' " +
                                        "must declare at least one @${constraint.annotationName} " +
                                        constraint.target.diagnosticName,
                                    annotation.sourceLine,
                                    annotation.sourceColumn
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /** 用于诊断的元素清单后缀；无元素时明确写 none，避免读者以为消息被截断。 */
    private fun allowedElementsSuffix(rule: AnnotationRule): String =
        if (rule.elements.isEmpty()) " (this annotation declares no elements)"
        else " (allowed elements: ${rule.elements.keys.joinToString(", ")})"

    /** 注解名归一化：`@some.pkg.Table` 与 `@Table` 命中同一条规则。 */
    private fun simpleName(qualified: String): String = qualified.substringAfterLast('.')

    /** 把实参形态转成简短英文描述，供类型错误消息使用（不输出完整 AST）。 */
    private fun describeValue(value: JasExpression): String = when (value) {
        is JasStringLiteral -> "string literal"
        is JasIntLiteral -> "integer literal"
        is JasFloatLiteral -> "float literal"
        is JasCharLiteral -> "char literal"
        is JasBoolLiteral -> "boolean literal"
        else -> "non-literal expression"
    }
}
