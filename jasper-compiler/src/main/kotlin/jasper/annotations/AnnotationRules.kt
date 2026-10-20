// =============================================================================
// AnnotationRules.kt —— 编译期注解规则注册表（Q32 / C17）
// =============================================================================
// 职责：
//   集中描述「一种注解允许贴在哪些声明上、有哪些元素、元素是什么常量类型、
//   哪些注解互斥、以及组合约束（如 @Entity 必须有一个 @Id 属性）」。
//
// 所属层次：annotations(2)。只依赖 AST 层的数据节点，不依赖 checker/translator，
//   保证依赖单向（annotations -> ast）。
//
// 与谁协作：
//   - AnnotationValidator 读取本文件的规则执行校验；
//   - AnnotationProcessor 持有默认规则集，在登记注解索引的同一遍历里调用校验；
//   - 新增注解族时只扩展 [AnnotationRuleSet.DEFAULT]（或注入自定义规则集），
//     不需要在业务代码里散落 `if (name == "@Id")`。
//
// 设计模式：Registry（注册表）。它隔离的变化点是「注解规则本身的增减」：
//   主流程只认 [AnnotationRule] 这一稳定抽象，规则表怎么变都不影响遍历/诊断逻辑。
//   刻意不做成通用注解元模型（读 @interface 声明的类型/default）——当前编译器需要
//   校验的注解族很小，显式规则更可读、错误信息也更稳定；等规则数量真正增长时再
//   把规则来源替换为「声明驱动」即可，消费者接口不变。
//
// 边界：
//   - 未登记的注解完全忽略（例如 @Deprecated / 用户自定义注解），保持既有兼容性；
//   - 只校验本阶段能静态判断的常量字面量，不执行常量折叠/函数求值；
//   - 不做运行期反射，规则只参与编译期诊断。
// =============================================================================

package jasper.annotations

import jasper.ast.JasAnnotationLiteral
import jasper.ast.JasAnnotationNamedArg
import jasper.ast.JasAnnotationPositionalArg
import jasper.ast.JasBoolLiteral
import jasper.ast.JasCharLiteral
import jasper.ast.JasExpression
import jasper.ast.JasFloatLiteral
import jasper.ast.JasIntLiteral
import jasper.ast.JasStringLiteral
import jasper.ast.JasUnaryOp

/**
 * 注解允许出现的声明目标。
 *
 * 做什么：把「类 / 接口 / 属性 / 方法」抽象成稳定枚举，供规则表声明合法目标，
 *   也供诊断信息输出统一的英文目标名（与现有 [AnnotationRecord.targetKind] 的字符串一致）。
 * 什么时候用：定义 [AnnotationRule.targets]、匹配成员种类、生成错误消息时。
 * 前置条件：无。
 * 失败模式：无（纯数据枚举）。
 * 为什么这样设计：目标集合需要封闭可穷尽；若用裸字符串，规则表与处理流程之间会失去
 *   编译期约束，拼写错误只能到测试时才暴露。
 */
enum class AnnotationTarget(val diagnosticName: String) {
    CLASS("class"),
    INTERFACE("interface"),
    PROPERTY("property"),
    FUNCTION("function")
}

/**
 * 注解元素允许的常量类型。
 *
 * 做什么：声明规则表中某个注解元素应接受哪一类编译期常量，并在校验时判断实参是否匹配。
 * 什么时候用：[AnnotationRule.elements] 的描述与 [AnnotationValidator] 的元素校验。
 * 前置条件：无。
 * 失败模式：无；[matches] 只做静态形态判断，不读运行期值。
 * 为什么这样设计：当前 AST 里注解实参就是 [JasExpression]，直接复用字面量节点判断即可，
 *   无需为注解元素再造一套并行类型系统；[ANY_CONSTANT] 为将来的嵌套注解/数组留扩展位。
 */
enum class AnnotationValueType(val diagnosticName: String) {
    STRING("string"),
    INT("int"),
    FLOAT("float"),
    BOOLEAN("bool"),
    CHAR("char"),
    ANY_CONSTANT("constant");

    /**
     * 判断一个注解实参是否满足本类型。
     *
     * 做什么：按字面量节点种类做静态匹配（整数/浮点/字符/字符串/布尔）；
     *   带正负号的一元运算按「去掉符号后仍是对应字面量」处理。
     * 什么时候用：[AnnotationValidator] 校验元素类型时。
     * 前置条件：value 已经通过「是常量」检查；本方法不负责判断非常量表达式。
     * 失败模式：不匹配返回 false，由调用方生成诊断。
     * 为什么这样设计：只认当前 AST 能可靠判定的字面量，避免误把变量引用当成常量。
     */
    fun matches(value: JasExpression): Boolean {
        val literal = stripNumericSign(value)
        return when (this) {
            STRING -> literal is JasStringLiteral
            INT -> literal is JasIntLiteral
            FLOAT -> literal is JasFloatLiteral || literal is JasIntLiteral
            BOOLEAN -> literal is JasBoolLiteral
            CHAR -> literal is JasCharLiteral
            ANY_CONSTANT -> true
        }
    }

    /** 去掉数值字面量前的正负号；其它一元运算/表达式原样返回。 */
    private fun stripNumericSign(value: JasExpression): JasExpression =
        if (value is JasUnaryOp && (value.op == "-" || value.op == "+") &&
            (value.operand is JasIntLiteral || value.operand is JasFloatLiteral)
        ) {
            value.operand
        } else {
            value
        }
}

/**
 * 一个注解元素的规则。
 *
 * 做什么：描述元素名对应的常量类型与是否必需。
 * 什么时候用：由 [AnnotationRule.elements] 持有，[AnnotationValidator] 逐项检查。
 * 前置条件：无。
 * 失败模式：无（纯数据）。
 * 为什么这样设计：把「类型 + 是否必需」放在一个不可变值里，规则表可以像配置一样阅读，
 *   校验逻辑只消费该抽象，新增元素无需改校验代码。
 */
data class AnnotationElementRule(
    val type: AnnotationValueType,
    val required: Boolean = false
)

/**
 * 注解之间的组合约束（跨声明/跨成员）。
 *
 * 做什么：表达「某注解出现时，容器里必须还存在另一个注解」这类规则。
 * 什么时候用：[AnnotationRule.constraints] 中声明，[AnnotationValidator.validateContainer] 执行。
 * 前置条件：约束里的目标种类与注解名是稳定的简单名。
 * 失败模式：无（纯数据）；不满足时由校验器写诊断。
 * 为什么这样设计：把组合规则放在注册表里，业务代码不需要知道 `@Entity -> @Id` 这条知识，
 *   将来新增（如 `@Mapper` 要求某方法注解）只需增加一个约束值，而不改处理流程。
 */
sealed class AnnotationConstraint {

    /**
     * 要求容器内至少有一个成员携带指定注解。
     *
     * 做什么：例如 `@Entity` 类必须有一个带 `@Id` 的属性。
     * 什么时候用：规则表声明组合约束时。
     * 前置条件：annotationName 为简单名；target 为期望的成员种类。
     * 失败模式：无。
     * 为什么这样设计：用数据描述约束而不是回调/闭包，便于未来序列化规则与生成稳定诊断。
     */
    data class RequiresMemberAnnotation(
        val annotationName: String,
        val target: AnnotationTarget
    ) : AnnotationConstraint()
}

/**
 * 一种注解的完整编译期规则。
 *
 * 做什么：汇聚目标集合、元素表、互斥注解、组合约束与是否可重复。
 * 什么时候用：[AnnotationRuleSet] 注册、[AnnotationValidator] 查询。
 * 前置条件：name 为注解简单名（不带包名前缀）。
 * 失败模式：无（纯数据）。
 * 为什么这样设计：规则是只读值对象，注册表只做索引；这样规则定义与校验算法解耦，
 *   单元测试可以直接构造最小规则集，不必依赖完整编译器。
 */
data class AnnotationRule(
    val name: String,
    val targets: Set<AnnotationTarget>,
    val elements: Map<String, AnnotationElementRule> = emptyMap(),
    val conflictsWith: Set<String> = emptySet(),
    val constraints: List<AnnotationConstraint> = emptyList(),
    val repeatable: Boolean = false
) {
    /**
     * 位置实参按元素声明顺序映射时的名字序列。
     *
     * 做什么：把 [elements] 的迭代顺序固化为位置参数顺序（LinkedHashMap 顺序）。
     * 什么时候用：校验 `@Mapping("src", "dst")` 这种不带名字的实参时。
     * 前置条件：规则表用有序 Map 构造（本文件的默认规则均满足）。
     * 失败模式：无；无元素时返回空列表，位置实参会被判为多余。
     */
    val positionalElementNames: List<String> get() = elements.keys.toList()
}

/**
 * 不可变注解规则集。
 *
 * 做什么：按简单名索引一组 [AnnotationRule]，提供 O(1) 查询；默认实例由
 *   [DEFAULT] 暴露内置的四类 codegen 注解与 JPA 形状注解族。
 * 什么时候用：[AnnotationValidator] 构造时注入；新增注解族时构造自定义实例。
 * 前置条件：规则名在集合内唯一；同名后注册者覆盖先注册者（与编译器其它索引的
 *   last-writer-wins 语义一致）。
 * 失败模式：查询不存在的名字返回 null（表示「不校验」，而非错误）。
 * 为什么这样设计：用注入代替全局可变单例，测试可以只注册一条规则验证边界；
 *   生产代码默认使用 [DEFAULT]，扩展点集中在 [buildDefaultRules]。
 */
class AnnotationRuleSet(rules: List<AnnotationRule>) {

    private val byName: Map<String, AnnotationRule> = rules.associateBy { it.name }

    /**
     * 查询一个注解简单名对应的规则。
     *
     * 做什么：返回规则或 null。
     * 什么时候用：校验器遍历注解时。
     * 前置条件：name 已是简单名（调用方负责去除包名前缀）。
     * 失败模式：未登记返回 null，调用方应跳过校验（保留对未知注解的兼容）。
     */
    fun ruleFor(name: String): AnnotationRule? = byName[name]

    /**
     * 已注册的全部规则（声明顺序）。
     *
     * 做什么：供测试/文档/未来工具枚举规则。
     * 什么时候用：需要展示「当前支持哪些编译期注解」时。
     * 前置条件：无。
     * 失败模式：无；返回只读列表视图。
     */
    fun rules(): List<AnnotationRule> = byName.values.toList()

    companion object {
        /**
         * 编译期内置规则集。
         *
         * 做什么：包含现有 `@Getter/@Setter/@EqualsAndHashCode/@Mapper/@Mapping`
         *   以及 JPA 形状注解族（`@Entity/@Table/@Id/...`）的规则。
         * 什么时候用：AnnotationProcessor 的默认构造参数。
         * 前置条件：规则名使用源码中的简单名；带包名的使用通过 simpleName 归一化后命中。
         * 失败模式：无。
         * 为什么这样设计：默认规则集中在一处，新增/修改规则无需触碰校验算法。
         */
        val DEFAULT: AnnotationRuleSet = AnnotationRuleSet(buildDefaultRules())

        private fun buildDefaultRules(): List<AnnotationRule> = listOf(
            // ── 现有四类 codegen 注解：保留旧行为，只增加目标/重复/元素校验 ──
            AnnotationRule(
                name = "Getter",
                targets = setOf(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
            ),
            AnnotationRule(
                name = "Setter",
                targets = setOf(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
            ),
            AnnotationRule(
                name = "EqualsAndHashCode",
                targets = setOf(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
            ),
            AnnotationRule(
                name = "Mapper",
                targets = setOf(AnnotationTarget.INTERFACE)
            ),
            AnnotationRule(
                name = "Mapping",
                targets = setOf(AnnotationTarget.FUNCTION),
                elements = linkedMapOf(
                    "source" to AnnotationElementRule(AnnotationValueType.STRING, required = true),
                    "target" to AnnotationElementRule(AnnotationValueType.STRING, required = true)
                )
            ),

            // ── JPA 形状注解族：编译期规则化（完整 ORM 语义仍不在编译期实现） ──
            AnnotationRule(
                name = "Entity",
                targets = setOf(AnnotationTarget.CLASS),
                constraints = listOf(
                    AnnotationConstraint.RequiresMemberAnnotation(
                        annotationName = "Id",
                        target = AnnotationTarget.PROPERTY
                    )
                )
            ),
            AnnotationRule(
                name = "Table",
                targets = setOf(AnnotationTarget.CLASS),
                elements = linkedMapOf(
                    "name" to AnnotationElementRule(AnnotationValueType.STRING)
                )
            ),
            AnnotationRule(
                name = "Id",
                targets = setOf(AnnotationTarget.PROPERTY)
            ),
            AnnotationRule(
                name = "Column",
                targets = setOf(AnnotationTarget.PROPERTY),
                elements = linkedMapOf(
                    "name" to AnnotationElementRule(AnnotationValueType.STRING)
                ),
                conflictsWith = setOf("OneToMany", "ManyToOne")
            ),
            AnnotationRule(
                name = "GeneratedValue",
                targets = setOf(AnnotationTarget.PROPERTY),
                elements = linkedMapOf(
                    "strategy" to AnnotationElementRule(AnnotationValueType.STRING)
                )
            ),
            AnnotationRule(
                name = "OneToMany",
                targets = setOf(AnnotationTarget.PROPERTY),
                elements = linkedMapOf(
                    "targetEntity" to AnnotationElementRule(AnnotationValueType.STRING)
                ),
                conflictsWith = setOf("Column")
            ),
            AnnotationRule(
                name = "ManyToOne",
                targets = setOf(AnnotationTarget.PROPERTY),
                elements = linkedMapOf(
                    "targetEntity" to AnnotationElementRule(AnnotationValueType.STRING)
                ),
                conflictsWith = setOf("Column")
            ),
            AnnotationRule(
                name = "PrePersist",
                targets = setOf(AnnotationTarget.FUNCTION)
            ),
            AnnotationRule(
                name = "PostLoad",
                targets = setOf(AnnotationTarget.FUNCTION)
            )
        )
    }
}

/**
 * 判断注解实参是否是当前 AST 能静态确认的常量。
 *
 * 做什么：识别字符串/整数/浮点/字符/布尔字面量、带正负号的一元数值字面量、
 *   以及由常量组成的嵌套注解/数组实参；其它表达式（变量、调用、二元运算）一律视为非常量。
 * 什么时候用：[AnnotationValidator] 校验元素值前。
 * 前置条件：value 是注解实参表达式。
 * 失败模式：返回 false 表示「不是可静态确认的常量」，由调用方生成稳定诊断。
 * 为什么这样设计：注解值在 JVM 语义里必须是常量；编译器此时还没有做常量折叠，
 *   因此采取保守策略——只接受能直接看出的字面量，避免把运行期才知道的值误放行。
 *   将来若引入常量折叠，只需放宽本函数，校验器与规则表无需变化（隔离变化点）。
 */
internal fun isCompileTimeConstant(value: JasExpression): Boolean = when (value) {
    is JasStringLiteral, is JasIntLiteral, is JasFloatLiteral, is JasCharLiteral, is JasBoolLiteral -> true
    is JasUnaryOp -> isCompileTimeConstant(value.operand)
    is JasAnnotationLiteral -> value.annotation.arguments.all { argument ->
        when (argument) {
            is JasAnnotationNamedArg -> isCompileTimeConstant(argument.value)
            is JasAnnotationPositionalArg -> isCompileTimeConstant(argument.value)
        }
    }
    else -> false
}
