// ============================================================================
// JasperAst.kt —— Jasper 语言无关的抽象语法树（AST）数据模型
// ============================================================================
// 职责：用 sealed class / data class / object 描述 Jasper 源码的纯结构，不含任何逻辑
//       （不涉及 LLVM IR、不做类型检查），只声明数据形状，字段均为 val 只读。
// 所属层次：ast 层（最低层），被 annotations/checker/translator/backend/compiler 等
//       所有上层依赖；本文件不 import 任何 jasper.* 分层，故永不产生下层→上层依赖。
// 与谁协作：上游 JasperAstBuilder 将 ANTLR 语法树转换为这里的节点；下游注解处理、
//       检查器、翻译器、后端消费这些节点。本层只被调用，不主动调用任何下游。
// 对外契约：所有语言构造建模为 JasNode 子类型——JasDeclaration（声明）、
//       JasStatement（语句）、JasExpression（表达式）、JasType（类型）、
//       JasPattern（模式）等 sealed/open 根；仅 JasNode.sourceLine/sourceColumn 与
//       JasCall.inferredTypeArgs 为可变状态，其余字段全部只读。
// 扩展点与限制：新增语法构造 = 新增节点子类并补齐各下游 when 分支；sealed 层次隔离
//       「穷举分派」变化点，但 Kotlin when 未穷举时不报错，新增节点须人工排查消费点。
// ============================================================================

package jasper.ast

/**
 * 所有 AST 节点的公共基类（根节点）。
 *
 * 仅承载源码位置信息，供报错/诊断定位使用；其余字段一律放在具体子类上。
 * sourceLine/sourceColumn 为 0 表示解析阶段未记录位置（例如合成的节点）。
 */
sealed class JasNode {
    var sourceLine: Int = 0
    var sourceColumn: Int = 0
}

// --- Declarations ---

/**
 * 所有「声明」类节点的 sealed 根：函数、类、接口、枚举、属性、导入、参数、注解成员等。
 *
 * 用 sealed 层次把「声明有哪几种」这一变化点隔离出来，供下游对声明做穷举式分派。
 */
sealed class JasDeclaration : JasNode()

/**
 * 函数/方法声明。
 * name 函数名；parameters 形参列表；returnType 返回类型（null 表示省略或待推导）；
 * body 函数体（null 表示无体声明，如接口方法或外部/原生声明）；modifiers 修饰符集合
 * （元素类型宽松为 Any，原样保留 public/static/override 等）；typeParameters 泛型形参；
 * whereConstraints where 子句中的类型约束。
 * 三个 synthetic* 字段是注解处理的「延迟生成」标记：只合成签名，函数体留到 lowering
 * 阶段在类型信息齐备时生成，隔离了「签名先于实现可用」这一变化点。
 */
data class JasFunction(
    val name: String, val parameters: List<JasParameter>, val returnType: JasType?,
    val body: JasBlock?, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter> = emptyList(),
    val whereConstraints: List<JasTypeConstraint> = emptyList(),
    val syntheticAccessor: JasSyntheticAccessor? = null,
    val syntheticEquality: JasSyntheticEquality? = null,
    val syntheticMapper: JasSyntheticMapper? = null
) : JasDeclaration()

/**
 * Marks a function whose *signature* was synthesized by the annotation
 * processing phase (e.g. `@Getter` / `@Setter`) but whose *body* is generated
 * later, during lowering, when full type information is available.
 *
 * 标记一个由注解处理阶段（`@Getter`/`@Setter`）合成**签名**的访问器函数，其**函数体**
 * 将在 lowering 阶段（类型信息齐备后）生成。propertyName 对应属性名；
 * isGetter=true 表示 getter，false 表示 setter。
 */
data class JasSyntheticAccessor(val propertyName: String, val isGetter: Boolean) : JasNode()

/**
 * Marks an `equals` / `hash` function whose *signature* was synthesized by the
 * annotation processing phase from `@EqualsAndHashCode`; the *body* is generated
 * later during lowering ([jasper.translator.JasperToIrTranslator]).
 *
 * The participating instance fields are captured here (name + declared type) so
 * the late body generator does not have to re-derive class structure.
 *
 * 标记由 `@EqualsAndHashCode` 合成**签名**的 equals/hash 函数，函数体在 lowering 阶段
 * （JasperToIrTranslator）生成。isEquals 区分 equals（true）与 hash（false）；
 * className 所属类名；fields 参与比较的实例字段（名字 + 声明类型），
 * 让延迟生成函数体时无需重新推导类结构。
 */
data class JasSyntheticEquality(
    val isEquals: Boolean,
    val className: String,
    val fields: List<JasEqualityField>
) : JasNode()

/**
 * JasSyntheticEquality 中参与 equals/hash 计算的一个实例字段：
 * name 字段名；type 声明类型（null 表示未知）。
 */
data class JasEqualityField(val name: String, val type: JasType?) : JasNode()

/**
 * Marks a mapper method whose *signature* was synthesized by the annotation
 * processing phase from `@Mapper` / `@Mapping`; the *body* is generated later
 * during lowering ([jasper.translator.JasperToIrTranslator]).
 *
 * The plan captured here is intentionally the *simple property-to-property*
 * shape (v1): take one source object, create the target object, assign one
 * source property to one target property per entry, return the target. No
 * collections, nested objects, multiple sources, or custom conversions.
 *
 * 标记由 `@Mapper`/`@Mapping` 合成**签名**的映射方法，函数体在 lowering 阶段生成。
 * 这里捕获的映射计划刻意只支持**简单属性到属性**（v1）：取一个源对象、创建目标对象、
 * 逐条把源属性赋给目标属性、返回目标；不支持集合、嵌套对象、多源或自定义转换。
 * parameterName 源参数名；targetType 目标类型；assignments 属性映射条目。
 */
data class JasSyntheticMapper(
    val parameterName: String,
    val targetType: JasNamedType,
    val assignments: List<JasMapperAssignment>
) : JasNode()

/**
 * 一条映射赋值：把源对象上的 sourceProperty 赋给目标对象的 targetProperty。
 */
data class JasMapperAssignment(val targetProperty: String, val sourceProperty: String) : JasNode()

/**
 * 类声明：name 类名；modifiers 修饰符；typeParameters 泛型形参；
 * superclass 父类（null 表示无显式父类）；interfaces 实现的接口列表；
 * members 成员声明；constructors 构造器列表。
 */
data class JasClass(
    val name: String, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter>,
    val superclass: JasType?, val interfaces: List<JasType>,
    val members: List<JasDeclaration>, val constructors: List<JasConstructor>
) : JasDeclaration()

/**
 * 接口声明：name 接口名；modifiers 修饰符；typeParameters 泛型形参；
 * extends 继承的父接口；members 成员声明（抽象方法、属性、常量等）。
 */
data class JasInterface(
    val name: String, val modifiers: List<Any>, val typeParameters: List<JasTypeParameter>,
    val extends: List<JasType>, val members: List<JasDeclaration>
) : JasDeclaration()

/**
 * 枚举声明：name 枚举名；modifiers 修饰符；constants 枚举常量列表；
 * members 枚举体内除常量之外的成员声明。
 */
data class JasEnum(
    val name: String, val modifiers: List<Any>,
    val constants: List<JasEnumConstant>, val members: List<JasDeclaration>
) : JasDeclaration()

/**
 * 一个枚举常量：name 常量名；args 传给枚举构造器的实参列表。
 */
data class JasEnumConstant(val name: String, val args: List<JasExpression>) : JasNode()

/**
 * 构造器声明：modifiers 修饰符；parameters 形参；
 * delegateCall 对 this(...)/super(...) 的显式构造器调用（null 表示无）；
 * body 构造器体（null 表示无体）。
 */
data class JasConstructor(
    val modifiers: List<Any>, val parameters: List<JasParameter>,
    val delegateCall: JasExplicitConstructorInvocation?,
    val body: JasBlock?
) : JasDeclaration()

/**
 * 构造器首行的显式构造器调用：isSuper=true 表示 super(...)，false 表示 this(...)；
 * args 调用实参列表。
 */
data class JasExplicitConstructorInvocation(
    val isSuper: Boolean, val args: List<JasExpression>
) : JasNode()

/**
 * 属性/字段声明：name 名字；type 声明类型（null 表示待推导）；modifiers 修饰符；
 * getter/setter 对应的访问器实现（null 表示使用默认访问器或无访问器）；
 * isConst 是否为常量；initializer 声明处初始化表达式（详见下方字段注释）。
 */
data class JasProperty(
    val name: String, val type: JasType?, val modifiers: List<Any>,
    val getter: JasPropertyAccessor?, val setter: JasPropertyAccessor?,
    val isConst: Boolean = false,
    /**
     * Initializer expression for a declaration-site constant, e.g. an
     * interface constant `const MAX_ROWS: int32 = 100;`. Class property
     * declarations currently leave this null (instance fields are assigned in
     * constructors); interface constants carry it so a `public static final`
     * field can be emitted with the constant value.
     *
     * 声明处常量的初始化表达式，例如接口常量 `const MAX_ROWS: int32 = 100;`。
     * 类的属性声明目前留空（实例字段在构造器里赋值）；接口常量会带上它，
     * 以便后端能按 `public static final` 字段连同常量值一起产出。
     */
    val initializer: JasExpression? = null
) : JasDeclaration()

/**
 * 属性访问器（getter 或 setter）实现：body 访问器体（null 表示抽象/默认）；
 * parameterName setter 的隐式参数名（getter 为 null）。
 */
data class JasPropertyAccessor(
    val body: JasBlock?, val parameterName: String?
) : JasNode()

/**
 * 注解类型声明（注解定义）：name 注解名；modifiers 修饰符；
 * members 注解成员（即注解的参数定义）。
 */
data class JasAnnotationType(
    val name: String, val modifiers: List<Any>, val members: List<JasAnnotationMember>
) : JasDeclaration()

/**
 * 注解类型的一个成员（注解参数定义）：name 成员名；type 成员类型（null 表示待推导）；
 * defaultValue 默认值（null 表示无默认值）。
 */
data class JasAnnotationMember(
    val name: String, val type: JasType?, val defaultValue: JasExpression?
) : JasDeclaration()

/**
 * import 声明：name 导入的限定名；alias 导入别名（null 表示无）；
 * isOnDemand 是否为通配导入（.*）；fromSource 记录原始来源文本。
 */
data class JasImport(
    val name: String, val alias: String?, val isOnDemand: Boolean,
    val fromSource: String?
) : JasDeclaration()

/**
 * 泛型类型形参：name 形参名；bound 上界（null 表示无界，默认 Any）。
 */
data class JasTypeParameter(val name: String, val bound: JasType?) : JasNode()

/**
 * where 子句中的类型约束：typeParamName 被约束的类型形参名；bound 约束上界。
 */
data class JasTypeConstraint(val typeParamName: String, val bound: JasType) : JasNode()

/**
 * 形参：name 参数名；type 参数类型（null 表示待推导）；vararg 是否为可变参数；
 * defaultValue 默认值表达式（null 表示无默认值）。
 * vararg=true 时 type 已是元素数组 `T[]`（由 AST 构建器改写），函数体内按数组使用。
 */
data class JasParameter(val name: String, val type: JasType?, val vararg: Boolean = false, val defaultValue: JasExpression? = null) : JasNode()

// --- Statements ---

/**
 * 语句块（花括号包裹的语句序列）：statements 块内语句的有序列表。
 */
data class JasBlock(val statements: List<JasStatement>) : JasStatement()

/**
 * 所有「语句」类节点的 open 根。用 open 而非 sealed，允许下游或后端扩展语句种类。
 */
open class JasStatement : JasNode()

/**
 * 局部变量声明语句：name 变量名；type 声明类型（null 表示待推导）；
 * initializer 初始化表达式（null 表示仅声明不初始化）；isConst 是否为常量。
 */
data class JasVariableStatement(val name: String, val type: JasType?, val initializer: JasExpression?, val isConst: Boolean = false) : JasStatement()

/**
 * 表达式语句：把表达式当语句执行（主要用于带副作用的调用或赋值）。
 */
data class JasExpressionStatement(val expression: JasExpression) : JasStatement()

/**
 * if 语句：condition 条件；thenBody then 分支；elseBody else 分支（null 表示无 else）。
 */
data class JasIf(val condition: JasExpression, val thenBody: JasBlock, val elseBody: JasBlock?) : JasStatement()

/**
 * while 循环：condition 循环条件；body 循环体。
 */
data class JasWhile(val condition: JasExpression, val body: JasBlock) : JasStatement()

/**
 * do-while 循环：先执行 body，再判断 condition。
 */
data class JasDoWhile(val body: JasBlock, val condition: JasExpression) : JasStatement()

/**
 * 经典三段式 for 循环：init 初始化语句；condition 条件表达式；update 步进表达式；
 * body 循环体。任一可为 null 表示该段省略。
 */
data class JasForStatement(val init: JasStatement?, val condition: JasExpression?, val update: JasExpression?, val body: JasBlock) : JasStatement()

/**
 * for-in 遍历语句：varName 迭代变量名；iterable 被遍历表达式；body 循环体；
 * thenBody/elseBody 可选的 then/else 分支（用于带 else 的遍历语义）。
 */
data class JasForInStatement(val varName: String, val iterable: JasExpression, val body: JasBlock, val thenBody: JasBlock? = null, val elseBody: JasBlock? = null) : JasStatement()

/**
 * switch 语句：expression 被匹配的表达式；cases 分支列表。
 */
data class JasSwitch(val expression: JasExpression, val cases: List<JasCaseClause>) : JasStatement()

/**
 * switch 的一个 case 分支：values 该分支匹配的常量表达式（可多个，用于多标签共享分支体）；
 * body 分支体。
 */
data class JasCaseClause(val values: List<JasExpression>, val body: JasBlock) : JasNode()

/**
 * return 语句：value 返回值表达式（null 表示无返回值）。
 */
data class JasReturn(val value: JasExpression?) : JasStatement()

/**
 * break 语句：label 跳出的标签（null 表示跳出最内层循环/switch）。
 */
data class JasBreakStatement(val label: String?) : JasStatement()

/**
 * continue 语句：label 继续的标签（null 表示继续最内层循环）。
 */
data class JasContinueStatement(val label: String?) : JasStatement()

/**
 * throw 语句：expression 抛出的异常/错误表达式。
 */
data class JasThrow(val expression: JasExpression) : JasStatement()

/**
 * try 语句：body try 块；catches catch 分支列表；finallyBody finally 块（null 表示无）。
 */
data class JasTry(val body: JasBlock, val catches: List<JasCatchClause>, val finallyBody: JasBlock?) : JasStatement()

/**
 * 一个 catch 分支：parameter 捕获参数（含异常类型与变量名）；body 分支体。
 */
data class JasCatchClause(val parameter: JasParameter, val body: JasBlock) : JasNode()

/**
 * defer 语句：body 在作用域退出时执行的延迟块。
 */
data class JasDefer(val body: JasBlock) : JasStatement()

/**
 * lock 语句：expression 作为锁对象；body 持锁执行的临界区。
 */
data class JasLock(val expression: JasExpression, val body: JasBlock) : JasStatement()

/**
 * assert 语句：condition 断言条件；message 失败时的消息表达式（null 表示无）。
 */
data class JasAssert(val condition: JasExpression, val message: JasExpression?) : JasStatement()

/**
 * yield 语句：expression 产出的值（用于生成器/协程）。
 */
data class JasYield(val expression: JasExpression) : JasStatement()

/**
 * 带标签语句：label 标签名；statement 被标记的语句（供 break/continue 引用）。
 */
data class JasLabeledStatement(val label: String, val statement: JasStatement) : JasStatement()

/**
 * 把后置自增/自减当语句使用的包装：increment 自增/自减表达式。
 */
data class JasStatementIncrement(val increment: JasPostfixIncDec) : JasStatement()

/**
 * 赋值表达式：target 赋值目标；value 右侧值；op 运算符（默认 "="，复合赋值如 "+="）。
 * 它继承自 JasExpression，通常作为语句出现。
 */
data class JasAssignment(val target: JasExpression, val value: JasExpression, val op: String = "=") : JasExpression()

/**
 * 复合自增/自减赋值表达式：对 target 施加 increment（如把 `x += 1` 建模为赋值目标 + 自增）。
 */
data class JasAssignmentIncrement(val target: JasExpression, val increment: JasPostfixIncDec) : JasExpression()

/**
 * 解构声明语句：bindings 解构出的各绑定；initializer 右侧被解构的表达式。
 */
data class JasDestructuringDeclaration(val bindings: List<JasDestructuringBinding>, val initializer: JasExpression) : JasStatement()

/**
 * 解构声明中的一个绑定：name 绑定名；type 声明类型（null 表示待推导）。
 */
data class JasDestructuringBinding(val name: String, val type: JasType?) : JasNode()

// --- Pattern Matching ---

/**
 * match 表达式/语句：expression 被匹配表达式；cases 匹配分支列表。
 */
data class JasMatch(val expression: JasExpression, val cases: List<JasMatchCase>) : JasStatement()

/**
 * 一个 match 分支：pattern 匹配模式（null 表示默认/兜底分支）；guard 守卫条件（null 表示无）；
 * body 分支体。
 */
data class JasMatchCase(val pattern: JasNode?, val guard: JasExpression?, val body: JasBlock) : JasNode()

/**
 * 所有「模式」节点的 sealed 根，用于 match 分支的结构匹配。
 */
sealed class JasPattern : JasNode()

/**
 * 通配模式 `_`：匹配任意值且不绑定（match 的兜底分支常用）。
 */
object JasWildcardPattern : JasPattern()

// --- Expressions ---

/**
 * 所有「表达式」类节点的 open 根。用 open 而非 sealed，允许语法或后端扩展表达式种类。
 */
open class JasExpression : JasNode()

/**
 * 整数字面量：value 解析后的数值（Long）；raw 源码原文（保留进制/下划线/后缀等写法）。
 */
data class JasIntLiteral(val value: Long, val raw: String) : JasExpression()

/**
 * 浮点字面量：value 解析后的数值（Double）；raw 源码原文（保留写法与后缀）。
 */
data class JasFloatLiteral(val value: Double, val raw: String) : JasExpression()

/**
 * 字符字面量：value 码点数值（Int）；raw 源码原文（含引号与转义写法）。
 */
data class JasCharLiteral(val value: Int, val raw: String) : JasExpression()

/**
 * 字符串字面量：text 字符串内容；raw=true 表示原始字符串（不处理转义）。
 */
data class JasStringLiteral(val text: String, val raw: Boolean = false) : JasExpression()

/**
 * 布尔字面量：value 取值 true/false。
 */
data class JasBoolLiteral(val value: Boolean) : JasExpression()

/**
 * null 字面量（无字段的单例）。
 */
object JasNullLiteral : JasExpression()

/**
 * 标识符引用：name 被引用的名字（变量/函数/类型等，由后续语义分析消解）。
 */
data class JasIdentifier(val name: String) : JasExpression()

/**
 * 二元运算：left 左操作数；op 运算符文本（如 "+"、"&&"）；right 右操作数。
 */
data class JasBinaryOp(val left: JasExpression, val op: String, val right: JasExpression) : JasExpression()

/**
 * 一元运算：op 运算符文本（如 "-"、"!"）；operand 操作数；
 * prefix=true 表示前缀（如 -x），false 表示后缀形式。
 */
data class JasUnaryOp(val op: String, val operand: JasExpression, val prefix: Boolean = true) : JasExpression()

/**
 * 后置自增/自减表达式：target 被操作表达式；op 运算符（"++" 或 "--"）。
 */
data class JasPostfixIncDec(val target: JasExpression, val op: String) : JasExpression()

/**
 * 函数/方法调用表达式：target 被调用的目标（标识符或属性访问）；args 实参列表；
 * isSafe=true 表示安全调用（?.）。
 *
 * inferredTypeArgs 是本文件唯一的可变字段之一：记录类型推断阶段回填的泛型实参
 * （形参名 -> 具体类型），隔离「推断结果需写回 AST」这一变化点；
 * 为 null 表示尚未推断或无需推断。
 */
class JasCall(
    val target: JasExpression,
    val args: List<JasExpression>,
    val isSafe: Boolean = false
) : JasExpression() {
    var inferredTypeArgs: Map<String, JasType>? = null

    /**
     * varargs 调用点的解析结果（由类型检查器回填，翻译器只按它降级）：
     * null 表示不是 varargs 调用；否则见 [JasVarargCall]。
     */
    var varargCall: JasVarargCall? = null
}

/**
 * varargs 调用点的降级指令。
 * fixedCount：被调函数的固定形参个数（不含 varargs 形参）；
 * arrayType：varargs 形参的数组类型 `T[]`；
 * passThrough：true 表示最后一个实参本身就是数组、原样传入；false 表示把
 *   `args[fixedCount..]` 打包成新数组（可能为空数组）。
 */
data class JasVarargCall(val fixedCount: Int, val arrayType: JasArrayType, val passThrough: Boolean)

/**
 * 属性访问表达式：target 访问对象；property 属性名；isSafe=true 表示安全访问（?.）。
 */
data class JasPropertyAccess(val target: JasExpression, val property: String, val isSafe: Boolean = false) : JasExpression()

/**
 * 下标访问表达式：target 被索引的对象/数组；index 下标表达式。
 */
data class JasArrayAccess(val target: JasExpression, val index: JasExpression) : JasExpression()

/**
 * 对象创建表达式（new）：type 目标类型；args 构造实参列表。
 */
data class JasNew(val type: JasType, val args: List<JasExpression>) : JasExpression()

/**
 * 数组创建表达式：type 元素/数组类型；dims 各维长度表达式；init 可选的初始化器
 * （null 表示按维度长度分配）。
 */
data class JasArrayCreation(val type: JasType, val dims: List<JasExpression>, val init: JasArrayInit?) : JasExpression()

/**
 * 数组初始化器的 sealed 根，区分「显式元素列表」与「仅给定长度」两种形态。
 */
sealed class JasArrayInit : JasNode()

/**
 * 数组初始化：values 显式列出的各元素表达式。
 */
data class JasArrayInitValues(val values: List<JasExpression>) : JasArrayInit()

/**
 * 数组初始化：length 元素个数（或维度长度）表达式。
 */
data class JasArrayInitLength(val length: JasExpression) : JasArrayInit()

/**
 * lambda 表达式：parameters 形参列表；body 函数体；
 * inferredTypes=true 表示形参类型由上下文推导而非显式书写。
 */
data class JasLambdaExpr(val parameters: List<JasParameter>, val body: JasBlock, val inferredTypes: Boolean = false) : JasExpression()

/**
 * 方法引用表达式：target 接收者表达式（null 表示无显式接收者，如静态/顶层方法）；
 * method 方法名；isSuper 是否为 super 引用；packageName 可选的限定包名前缀。
 */
data class JasMethodReference(val target: JasExpression?, val method: String, val isSuper: Boolean = false, val packageName: String? = null) : JasExpression()

/**
 * instanceof 判断表达式：expression 被判断的表达式；type 目标类型。
 */
data class JasInstanceOfExpr(val expression: JasExpression, val type: JasType) : JasExpression()

/**
 * 类型转换表达式：expression 被转换的表达式；type 目标类型；
 * isSafe=true 表示安全转换（如 as?，失败返回 null）。
 */
data class JasCastExpr(val expression: JasExpression, val type: JasType, val isSafe: Boolean = false) : JasExpression()

/**
 * 空合并表达式：left 为空时取 right，否则取 left。
 */
data class JasNullCoalescing(val left: JasExpression, val right: JasExpression) : JasExpression()

/**
 * 三目条件表达式：condition 条件；thenExpr 真分支；elseExpr 假分支。
 */
data class JasTernaryExpr(val condition: JasExpression, val thenExpr: JasExpression, val elseExpr: JasExpression) : JasExpression()

/**
 * 元组表达式：elements 各元素表达式。
 */
data class JasTupleExpr(val elements: List<JasExpression>) : JasExpression()

/**
 * 字符串模板/插值表达式：parts 按顺序拼接的片段（字面文本或嵌入表达式）。
 */
data class JasStringTemplate(val parts: List<JasTemplatePart>) : JasExpression()

/**
 * 字符串模板片段的 sealed 根，区分字面文本与嵌入表达式。
 */
sealed class JasTemplatePart : JasNode()

/**
 * 模板中的字面文本片段：text 文本内容。
 */
data class JasTemplateLiteral(val text: String) : JasTemplatePart()

/**
 * 模板中嵌入的表达式片段：expr 被求值并转换为字符串的表达式。
 */
data class JasTemplateExpr(val expr: JasExpression) : JasTemplatePart()

/**
 * 字典/映射字面量表达式：entries 键值对列表。
 */
data class JasDictLiteral(val entries: List<JasDictEntry>) : JasExpression()

/**
 * 字典字面量中的一个键值对：key 键表达式；value 值表达式。
 */
data class JasDictEntry(val key: JasExpression, val value: JasExpression) : JasNode()

/**
 * 注解使用（注解调用点）：name 注解名；arguments 实参列表（命名与位置参数混合）。
 */
data class JasAnnotationUse(val name: String, val arguments: List<JasAnnotationArgument>) : JasNode()

/**
 * 注解实参的 sealed 根，区分命名实参与位置实参两种写法。
 */
sealed class JasAnnotationArgument : JasNode()

/**
 * 命名注解实参：name 参数名；value 参数值表达式。
 */
data class JasAnnotationNamedArg(val name: String, val value: JasExpression) : JasAnnotationArgument()

/**
 * 位置注解实参：value 参数值表达式（按注解成员的声明顺序对应）。
 */
data class JasAnnotationPositionalArg(val value: JasExpression) : JasAnnotationArgument()

// --- Types ---

/**
 * 所有「类型」节点的 sealed 根，表示源码中出现的静态类型。
 */
sealed class JasType : JasNode()

/**
 * 具名类型引用：name 类型名（可为限定名）；typeArguments 泛型实参（无则为空）。
 */
data class JasNamedType(val name: String, val typeArguments: List<JasType> = emptyList()) : JasType()

/**
 * 基本/内置类型：name 类型名（如 int32、bool、float64）。
 */
data class JasPrimitiveType(val name: String) : JasType()

/**
 * Unit（无返回值）类型单例。
 */
object JasUnitType : JasType()

/**
 * Any（顶类型）单例。
 */
object JasAnyType : JasType()

/**
 * 内置字符串类型单例。
 */
object JasStringType : JasType()

/**
 * 内置字节序列类型单例。
 */
object JasBytesType : JasType()

/**
 * 内置正则类型单例。
 */
object JasRegexType : JasType()

/**
 * 可空类型：inner 内部类型，表示 inner?（允许为 null）。
 */
data class JasNullableType(val inner: JasType) : JasType()

/**
 * 非空类型：inner 内部类型，显式断言 inner 不为 null。
 */
data class JasNonNullType(val inner: JasType) : JasType()

/**
 * 数组类型：inner 元素类型。
 */
data class JasArrayType(val inner: JasType) : JasType()

/**
 * 函数类型：paramTypes 形参类型列表；returnType 返回类型；
 * varargs=true 表示最后一个形参是 varargs（其类型为数组 `T[]`）。
 */
data class JasFunctionType(val paramTypes: List<JasType>, val returnType: JasType, val varargs: Boolean = false) : JasType()

/**
 * 通配类型（泛型实参占位）：bound 边界类型（null 表示无界）；
 * extends=true 表示上界（? extends），false 表示下界（? super）。
 */
data class JasWildcardType(val bound: JasType? = null, val extends: Boolean = true) : JasType()

/**
 * 元组类型：types 各元素类型。
 */
data class JasTupleType(val types: List<JasType>) : JasType()

/**
 * 指针类型：inner 指向的类型（用于底层/原生互操作）。
 */
data class JasPointerType(val inner: JasType) : JasType()

/**
 * 引用类型：inner 被引用的类型（用于底层/原生互操作中的引用语义）。
 */
data class JasReferenceType(val inner: JasType) : JasType()

/**
 * 一个源文件的语法树根：packageName 包名（null 表示无包声明）；
 * declarations 顶层声明列表；imports 导入声明列表。
 */
data class JasSourceFile(
    val packageName: String?,
    val declarations: List<JasDeclaration>,
    val imports: List<JasDeclaration> = emptyList()
)
