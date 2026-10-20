// ============================================================================
// AnnotationProcessor —— 注解处理阶段：把 @Getter/@Setter/@EqualsAndHashCode/@Mapper
//   合成为类成员（签名）或整块实现类，供后续类型检查与 lowering 使用。
// 所属层次：annotations(2)。运行时机在 AST 构建之后、TypeChecker 之前。
// 与谁协作：上游 compiler（JasperCompiler.compileToPipeline/compileAll 等）调用 process()；
//   本文件只依赖 ast；下游先由 SymbolTable / TypeChecker 看「签名」做语义检查，
//   再由 translator/JasperToIrTranslator 消费 JasSynthetic* 标记生成「方法体」。
// 对外契约：process(JasSourceFile) -> AnnotatedSourceFile（改写后的 AST + AnnotationIndex + 错误）。
// 扩展点 / 已知限制：新增一种注解（派生成员）只需在 Registry 分派处按注解名加一个分支，
//   主流程（遍历声明→登记→返回）不变，隔离了「新增一种注解」的变化点；
//   当前只合成签名、不生成方法体（体在 lowering 生成，见两段式合成）；
//   @Mapper 仅支持同源文件内已声明的类、单源参数、同名/显式属性映射（v1 限制）。
// ============================================================================

package jasper.annotations

import jasper.ast.JasAnnotationArgument
import jasper.ast.JasAnnotationUse
import jasper.ast.JasAnnotationNamedArg
import jasper.ast.JasAnnotationPositionalArg
import jasper.ast.JasAnyType
import jasper.ast.JasArrayType
import jasper.ast.JasAssignment
import jasper.ast.JasClass
import jasper.ast.JasDeclaration
import jasper.ast.JasEqualityField
import jasper.ast.JasFunction
import jasper.ast.JasIdentifier
import jasper.ast.JasInterface
import jasper.ast.JasMapperAssignment
import jasper.ast.JasNamedType
import jasper.ast.JasNonNullType
import jasper.ast.JasNullableType
import jasper.ast.JasParameter
import jasper.ast.JasPrimitiveType
import jasper.ast.JasProperty
import jasper.ast.JasStringLiteral
import jasper.ast.JasStringType
import jasper.ast.JasSyntheticAccessor
import jasper.ast.JasSyntheticEquality
import jasper.ast.JasSyntheticMapper
import jasper.ast.JasType
import jasper.ast.JasUnitType
import jasper.ast.JasSourceFile

/**
 * One annotation occurrence captured by the annotation processing phase.
 *
 * This is deliberately a plain data record (no compilation state) so that it can
 * later be serialized into the `.jaso` manifest as a service/extension-point
 * index without changing the compiler core.
 *
 * 做什么：记录「一次注解使用」的纯数据快照（注解简单名 + 目标种类 + 目标名 + 原始实参）。
 * 什么时候用：注解阶段识别到注解时，由 [AnnotationProcessor.record] 登记一条，供 [AnnotationIndex] 汇总。
 * 前置条件：不携带任何编译期状态，可跨阶段安全传递。
 * 为什么这样设计：刻意保持为无状态普通数据类，未来可原样序列化进 `.jaso` 清单，
 *   不必为了导出注解索引而改动编译器核心。
 */
data class AnnotationRecord(
    val annotationName: String,
    val targetKind: String,
    val targetName: String,
    val arguments: List<JasAnnotationArgument> = emptyList()
)

/**
 * Structured in-memory annotation index produced by [AnnotationProcessor].
 *
 * Shape: `className -> ordered list of annotation declarations` attached to the
 * class itself or to its members. Every entry records the annotation name, the
 * kind of declaration it sits on (`class` / `property` / `function`), the
 * declaration name, and the raw arguments. This is exactly the data a future
 * `.jaso` manifest needs for a service/extension-point index; it is produced now
 * because the data is already in the compiler's hands.
 *
 * 做什么：注解索引，形状为 `类名 -> 有序的注解记录列表`（类自身或其成员上的注解）。
 * 什么时候用：注解阶段结束后随 [AnnotatedSourceFile] 一起返回；供未来生成 `.jaso` 服务/扩展点清单。
 * 前置条件：键为类的简单名；记录顺序与源码声明顺序一致。
 * 为什么这样设计：数据本就在编译器手里，现在顺手产出，避免将来为导出清单再遍历一遍 AST。
 */
data class AnnotationIndex(val byClass: Map<String, List<AnnotationRecord>>) {
    /**
     * 做什么：查询某个类名下的全部注解记录。
     * 什么时候用：需要按类读取注解索引时。
     * 前置条件：入参为类的简单名（与 [byClass] 的键一致）。
     * 失败模式：类不存在时返回空列表（而非抛异常），调用方无需判空。
     */
    fun forClass(className: String): List<AnnotationRecord> = byClass[className] ?: emptyList()

    companion object {
        val EMPTY = AnnotationIndex(emptyMap())
    }
}

/**
 * Result of running the annotation phase: the rewritten AST plus the index.
 *
 * 做什么：注解阶段的产出封装（改写后的 AST + 注解索引 + 注解诊断列表）。
 * 什么时候用：compiler 调用 [AnnotationProcessor.process] 后立即得到；AST 继续流入检查/翻译，index 供清单导出。
 * 前置条件：ast 是「可能已追加合成声明」的新 AST；无注解时与输入可能是同一个实例。
 * 失败模式：errors 非空表示注解语义错误（如 @Mapper 目标属性未映射），由 compiler 映射为 CompileError。
 */
data class AnnotatedSourceFile(
    val ast: JasSourceFile,
    val index: AnnotationIndex,
    val errors: List<AnnotationError> = emptyList()
)

/**
 * A compile-time diagnostic produced by the annotation processing phase (e.g.
 * an unmapped target property of an `@Mapper` method). It is position-carrying
 * like [jasper.compiler.SymbolError]; the compiler maps it into the structured
 * `CompileError` hierarchy.
 *
 * 做什么：注解阶段产生的编译期诊断（如 @Mapper 方法的目标属性没有对应源）。
 * 什么时候用：注解语义可仅凭签名判定时立即产生，汇集进 [AnnotatedSourceFile.errors]。
 * 前置条件：line/column 为源码位置，缺省为 0 表示无位置信息。
 * 为什么这样设计：与 [jasper.compiler.SymbolError] 一样携带位置，compiler 可无损映射为 CompileError。
 */
data class AnnotationError(val message: String, val line: Int = 0, val column: Int = 0)

/**
 * The annotation processing phase.
 *
 * It sits **between AST building and the TypeChecker**. It reads annotations
 * (`@Getter` / `@Setter` / `@EqualsAndHashCode`), synthesizes the *member
 * signatures* those annotations imply, and attaches them to the owning class so
 * the TypeChecker can resolve them. It never synthesizes method bodies: bodies
 * need type information and are generated later, during lowering
 * (`JasperToIrTranslator`, driven by [JasSyntheticAccessor] /
 * [JasSyntheticEquality] / [JasSyntheticMapper]).
 *
 * For `@Mapper` it additionally synthesizes a whole *implementation class*
 * (`<Interface>Impl`) for an annotated interface, plus the mapper method
 * signatures (with a [JasSyntheticMapper] plan). Semantic problems that can be
 * decided from signatures alone — most importantly an **unmapped target
 * property** — are reported as [AnnotationError]s.
 *
 * Code without these annotations is returned unchanged (same AST instance where
 * possible) so the existing path is byte-for-byte identical.
 *
 * 做什么：注解处理阶段的入口类，读取注解并合成它们隐含的「成员签名」/「实现类」。
 * 什么时候用：始终位于 AST 构建之后、TypeChecker 之前。
 * 前置条件：传入的是解析完成、尚未类型检查的 AST。
 * 失败模式：语义问题（如 @Mapper 目标属性未映射、@Mapping 类型不匹配）不抛异常，而是收集进 [AnnotatedSourceFile.errors]。
 * 为什么这样设计（两段式合成）：本类只合成「签名」——因为生成的方法必须在类型检查时可见；
 *   而「方法体」需要类型信息，故推迟到 lowering 阶段，由
 *   `JasperToIrTranslator` 依据 [JasSyntheticAccessor]/[JasSyntheticEquality]/[JasSyntheticMapper] 生成。
 *   这样把「签名先于实现可用」这一变化点隔离在注解阶段，避免了在注解阶段重造类型系统。
 *
 * 规则校验（Q32 / C17）：在登记注解索引的同一趟，把注解交给 [AnnotationValidator]，
 *   按 [AnnotationRuleSet] 检查目标/元素/重复/互斥/组合约束。规则集通过构造参数注入，
 *   默认使用 [AnnotationRuleSet.DEFAULT]；这样新增注解族只改规则表，本类主流程不变。
 */
class AnnotationProcessor(
    annotationRules: AnnotationRuleSet = AnnotationRuleSet.DEFAULT
) {

    /** 规则执行器：无状态、可在多文件编译中复用；构造时绑定规则集。 */
    private val annotationValidator = AnnotationValidator(annotationRules)

    /**
     * 做什么：扫描整份源文件，按注解名分派处理：类上合成 getter/setter/equals/hash 签名，
     *   接口上按 @Mapper 合成 `<Interface>Impl` 实现类；同时登记注解索引。
     * 什么时候用：编译器流水线中紧接解析之后调用一次。
     * 前置条件：ast 为未类型检查的 AST；不要求 @Mapper 的源/目标类型已注册进 SymbolTable（v1 只认同文件声明的类）。
     * 失败模式：不抛异常；语义错误累加进返回值的 errors；错误时相关成员/实现类不生成（不产出半成品）。
     * 为什么这样设计：Registry 分派按「注解简单名」路由，新增一种注解只加一个分支，
     *   隔离了「新增一种注解」的变化点，主流程（遍历声明 → 处理 → 汇总）保持稳定。
     */
    fun process(ast: JasSourceFile): AnnotatedSourceFile {
        val records = linkedMapOf<String, MutableList<AnnotationRecord>>()
        val errors = mutableListOf<AnnotationError>()

        // Index class property lists and all declared type names up front so
        // @Mapper methods can resolve their source/target shapes (v1: types must
        // be classes declared in the same source file).
        // 为什么预先索引：@Mapper 需要按「类名」查其属性集合与类型是否存在，若边遍历边查会依赖声明顺序；
        // 先整体建索引使处理与声明顺序无关。边界：v1 只认同源文件内已声明的类/接口/枚举/注解类型。
        val classProperties = linkedMapOf<String, List<JasProperty>>()
        val declaredTypeNames = mutableSetOf<String>()
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> {
                    classProperties[decl.name] = decl.members.filterIsInstance<JasProperty>()
                    declaredTypeNames.add(decl.name)
                }
                is JasInterface -> declaredTypeNames.add(decl.name)
                is jasper.ast.JasEnum -> declaredTypeNames.add(decl.name)
                is jasper.ast.JasAnnotationType -> declaredTypeNames.add(decl.name)
                else -> {}
            }
        }

        var changed = false
        val declarations = mutableListOf<JasDeclaration>()
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> {
                    val processed = processClass(decl, records, errors)
                    if (processed !== decl) changed = true
                    declarations.add(processed)
                }
                is JasInterface -> {
                    val impl = processInterface(decl, records, classProperties, declaredTypeNames, errors)
                    declarations.add(decl)
                    if (impl != null) {
                        declarations.add(impl)
                        changed = true
                    }
                }
                else -> declarations.add(decl)
            }
        }
        val newAst = if (changed) ast.copy(declarations = declarations) else ast
        val index = AnnotationIndex(records.mapValues { it.value.toList() })
        return AnnotatedSourceFile(newAst, index, errors.toList())
    }

    private fun processClass(
        cls: JasClass,
        records: MutableMap<String, MutableList<AnnotationRecord>>,
        errors: MutableList<AnnotationError>
    ): JasClass {
        val classAnnotations = cls.modifiers.filterIsInstance<JasAnnotationUse>()
        // Registry 分派（按注解简单名路由）：类级注解先解析出「是否启用某类派生成员」的开关。
        // 用 simpleName 去掉限定前缀，使 @Getter 与 @some.pkg.Getter 等价；这里不建显式表，
        // 而是用一组布尔开关承接，新增注解时只需在此加一个开关 + 一个生成分支。
        val classHasGetter = classAnnotations.any { simpleName(it.name) == GETTER }
        val classHasSetter = classAnnotations.any { simpleName(it.name) == SETTER }
        val classHasEquality = classAnnotations.any { simpleName(it.name) == EQUALS_AND_HASH_CODE }

        // 登记所有类级注解（纯索引，不影响语义）。
        for (annotation in classAnnotations) {
            record(records, cls.name, "class", cls.name, annotation)
        }

        // 编译期规则校验：与索引登记同一趟完成，错误进入统一诊断；有错误时编译器不会进入
        // 类型检查/字节码阶段（见 JasperCompiler），因此不会产出实体 class。
        annotationValidator.validateContainer(
            ownerName = cls.name,
            containerTarget = AnnotationTarget.CLASS,
            containerAnnotations = classAnnotations,
            members = annotatedMemberViews(cls.members),
            errors = errors
        )

        // 手写方法名集合：作为「资格判定」的最后一道闸。注解合成的成员若与手写同名则不再生成，
        // 保证幂等（重复处理同一 AST 不会叠加），也让手写实现始终覆盖派生实现（last-writer-wins 的镜像）。
        val existingFunctionNames = cls.members
            .filterIsInstance<JasFunction>()
            .map { it.name }
            .toMutableSet()

        val generated = mutableListOf<JasDeclaration>()
        val propertyLevelEqualityFields = mutableListOf<JasEqualityField>()
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    val propAnnotations = member.modifiers.filterIsInstance<JasAnnotationUse>()
                    for (annotation in propAnnotations) {
                        record(records, cls.name, "property", member.name, annotation)
                    }
                    // 属性级 @EqualsAndHashCode：只登记「参与相等性」的字段，稍后并进 equals/hash 计划。
                    // 资格：有声明类型且非 static（见 isEligibleForEquality）。
                    if (propAnnotations.any { simpleName(it.name) == EQUALS_AND_HASH_CODE } &&
                        isEligibleForEquality(member)
                    ) {
                        propertyLevelEqualityFields.add(JasEqualityField(member.name, member.type))
                    }
                    // 可见性优先级：类级开关置位则所有合格属性都要，否则仅属性自身标注的才要。
                    val wantGetter = classHasGetter || propAnnotations.any { simpleName(it.name) == GETTER }
                    val wantSetter = classHasSetter || propAnnotations.any { simpleName(it.name) == SETTER }

                    // 命名规则：get + 首字母大写属性名（setter 同理）。资格：有类型且非 static；setter 还要求非 const。
                    // existingFunctionNames.add(name) 返回 false 表示已存在同名（手写或已生成），跳过以保证唯一性。
                    if (wantGetter && isEligibleForGetter(member)) {
                        val name = accessorName("get", member.name)
                        if (existingFunctionNames.add(name)) {
                            generated.add(getterSignature(name, member))
                        }
                    }
                    if (wantSetter && isEligibleForSetter(member)) {
                        val name = accessorName("set", member.name)
                        if (existingFunctionNames.add(name)) {
                            generated.add(setterSignature(name, member))
                        }
                    }
                }
                is JasFunction -> {
                    for (annotation in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                        record(records, cls.name, "function", member.name, annotation)
                    }
                }
                else -> {}
            }
        }

        // @EqualsAndHashCode (class-level or property-level): synthesize the
        // `equals` / `hash` *signatures*. Handwritten methods always win: if a
        // function with the same name already exists, the respective member is
        // not generated (idempotence).
        // 两段式合成：这里只合成 equals/hash 的「签名」，字段清单写进 JasSyntheticEquality，
        // 「函数体」由 lowering 阶段（JasperToIrTranslator.syntheticEqualityBody）依据字段类型生成。
        // 为什么体要推迟：equals 需要 instanceof 守卫与字段级哈希混合，只有拿到类型/IR 形态才好表达。
        if (classHasEquality || propertyLevelEqualityFields.isNotEmpty()) {
            // 类级注解：参与字段 = 全部合格实例属性；仅属性级注解：只用已登记的字段（顺序即声明顺序）。
            val fields = if (classHasEquality) {
                cls.members.filterIsInstance<JasProperty>()
                    .filter { isEligibleForEquality(it) }
                    .map { JasEqualityField(it.name, it.type) }
            } else {
                propertyLevelEqualityFields
            }
            if (existingFunctionNames.add(EQUALS_METHOD)) {
                generated.add(equalitySignature(isEquals = true, className = cls.name, fields = fields))
            }
            if (existingFunctionNames.add(HASH_METHOD)) {
                generated.add(equalitySignature(isEquals = false, className = cls.name, fields = fields))
            }
        }

        // 无任何派生成员时原样返回（可能返回同一实例），保证「无注解路径」字节级不变。
        if (generated.isEmpty()) return cls
        // 追加生成的成员；原有成员顺序不变（last-writer-wins 语义由 existingFunctionNames 保证）。
        return cls.copy(members = cls.members + generated)
    }

    // ── @Mapper ──

    /**
     * Processes one interface declaration. Non-`@Mapper` interfaces are indexed
     * and returned untouched (null impl). For an `@Mapper` interface a concrete
     * implementation class (`<Interface>Impl`) is synthesized: one method per
     * abstract mapper method, each carrying a [JasSyntheticMapper] plan and no
     * body (bodies are generated during lowering).
     *
     * Returns the synthesized impl class, or null when nothing was generated.
     *
     * 做什么：处理单个接口声明；非 @Mapper 接口只登记注解并返回 null；@Mapper 接口另合成一个 `<Interface>Impl` 实现类。
     * 什么时候用：process() 遍历到接口声明时调用。
     * 前置条件：classProperties/declaredTypeNames 已建好（@Mapper 靠它们解析源/目标类型）。
     * 失败模式：返回 null 表示未生成实现类（非 @Mapper、已有同名 Impl、含泛型参数、无合格方法）；错误写进 errors。
     * 为什么这样设计：把「接口 → 实现类」的整块合成收敛在此处，方法体同样推迟到 lowering，
     *   与 getter/setter/equals 的两段式合成保持一致的契约。
     */
    private fun processInterface(
        iface: JasInterface,
        records: MutableMap<String, MutableList<AnnotationRecord>>,
        classProperties: Map<String, List<JasProperty>>,
        declaredTypeNames: Set<String>,
        errors: MutableList<AnnotationError>
    ): JasClass? {
        val annotations = iface.modifiers.filterIsInstance<JasAnnotationUse>()
        for (annotation in annotations) {
            record(records, iface.name, "interface", iface.name, annotation)
        }
        for (member in iface.members) {
            when (member) {
                is JasFunction -> for (a in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                    record(records, iface.name, "function", member.name, a)
                }
                is JasProperty -> for (a in member.modifiers.filterIsInstance<JasAnnotationUse>()) {
                    record(records, iface.name, "property", member.name, a)
                }
                else -> {}
            }
        }

        // 与类相同的编译期规则校验：接口本身按 INTERFACE 目标检查（@Mapper 合法，
        // @Entity/@Getter 等目标不匹配会在这里报错），方法上的 @Mapping 等同样受检。
        annotationValidator.validateContainer(
            ownerName = iface.name,
            containerTarget = AnnotationTarget.INTERFACE,
            containerAnnotations = annotations,
            members = annotatedMemberViews(iface.members),
            errors = errors
        )

        // Registry 分派：只有接口带 @Mapper（按简单名匹配）才继续，否则本阶段对它无事可做。
        if (annotations.none { simpleName(it.name) == MAPPER }) return null

        val implName = iface.name + MAPPER_IMPL_SUFFIX
        // Idempotence: never synthesize the same impl twice (e.g. re-processing
        // an AST that was already through this phase).
        // 幂等：若 <Interface>Impl 已被声明，直接跳过，避免同一 AST 被重复处理时叠加实现类。
        if (implName in declaredTypeNames) return null

        // v1 限制：泛型接口的映射实现需要类型擦除/桥接方法支持，暂不支持，显式报错而非静默生成错误代码。
        if (iface.typeParameters.isNotEmpty()) {
            errors.add(AnnotationError(
                "@Mapper interface '${iface.name}' must not declare type parameters (v1 limitation)",
                iface.sourceLine, iface.sourceColumn
            ))
            return null
        }

        val implFunctions = mutableListOf<JasDeclaration>()
        for (member in iface.members) {
            if (member !is JasFunction) continue
            if (member.body != null) continue // only abstract mapper methods
            // 每个抽象方法合成一个签名（含 JasSyntheticMapper 计划）；任一方法失败则它返回 null，不产生半成品。
            buildMapperFunction(iface.name, member, classProperties, errors)?.let { implFunctions.add(it) }
        }

        // 没有任何可映射方法时不生成空实现类。
        if (implFunctions.isEmpty()) return null

        return JasClass(
            name = implName,
            modifiers = listOf("public"),
            typeParameters = emptyList(),
            superclass = null,
            interfaces = listOf(JasNamedType(iface.name)),
            members = implFunctions,
            constructors = emptyList()
        )
    }

    /**
     * Builds the impl-side signature for one mapper method and validates the
     * mapping plan. On any problem an [AnnotationError] is recorded and null is
     * returned (no half-built method is emitted).
     *
     * 做什么：为一个 @Mapper 方法构造实现侧签名，并校验映射计划（参数/返回类型、@Mapping、同名自动映射）。
     * 什么时候用：processInterface 遍历抽象方法时逐条调用。
     * 前置条件：方法须恰有一个形参；源参数与返回类型须是同文件已声明的「类」（v1）。
     * 失败模式：任一问题都记录 AnnotationError 并返回 null；用 errors 计数变化判断「本次是否新增了错误」，避免发出半成品。
     */
    private fun buildMapperFunction(
        interfaceName: String,
        method: JasFunction,
        classProperties: Map<String, List<JasProperty>>,
        errors: MutableList<AnnotationError>
    ): JasFunction? {
        val line = method.sourceLine
        val column = method.sourceColumn
        val before = errors.size

        if (method.parameters.size != 1) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}' in '${interfaceName}' must declare exactly one source parameter (v1 limitation)",
                line, column
            ))
            return null
        }
        val sourceParam = method.parameters[0]
        val sourceTypeName = (sourceParam.type as? JasNamedType)?.name
        val targetTypeName = (method.returnType as? JasNamedType)?.name
        if (sourceTypeName == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': source parameter '${sourceParam.name}' must have a declared class type",
                line, column
            ))
            return null
        }
        if (targetTypeName == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': return type must be a declared class type",
                line, column
            ))
            return null
        }
        val sourceProps = classProperties[sourceTypeName]
        val targetProps = classProperties[targetTypeName]
        if (sourceProps == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': source type '${sourceTypeName}' is not a class declared in this source file (v1 limitation)",
                line, column
            ))
            return null
        }
        if (targetProps == null) {
            errors.add(AnnotationError(
                "Mapper method '${method.name}': target type '${targetTypeName}' is not a class declared in this source file (v1 limitation)",
                line, column
            ))
            return null
        }

        // Explicit rename mappings: target property -> source property.
        // 显式 @Mapping(source=..., target=...) 优先于同名自动映射；这里只登记「合法的重命名」，
        // 不合法者逐条记录错误并 continue（只跳过该条，不影响其它映射）。
        val explicitByTarget = linkedMapOf<String, String>()
        for (mapping in method.modifiers.filterIsInstance<JasAnnotationUse>()
            .filter { simpleName(it.name) == MAPPING }) {
            val args = annotationStringArgs(mapping)
            val source = args["source"]
            val target = args["target"]
            if (source == null || target == null) {
                errors.add(AnnotationError(
                    "@Mapping on '${method.name}' requires both 'source' and 'target' string arguments",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            val tProp = targetProps.firstOrNull { it.name == target }
            if (tProp == null) {
                errors.add(AnnotationError(
                    "@Mapping target '$target' is not a property of target type '${targetTypeName}'",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            val sProp = sourceProps.firstOrNull { it.name == source }
            if (sProp == null) {
                errors.add(AnnotationError(
                    "@Mapping source '$source' is not a property of source type '${sourceTypeName}'",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            if (!sameSimpleType(sProp.type, tProp.type)) {
                errors.add(AnnotationError(
                    "@Mapping '${source}' -> '$target' type mismatch: ${describeType(sProp.type)} vs ${describeType(tProp.type)}",
                    mapping.sourceLine, mapping.sourceColumn
                ))
                continue
            }
            explicitByTarget[target] = source
        }

        // Auto-mapping: same-name, same-type source property.
        // 自动映射：对每个目标属性，若无显式重命名，则找同名源属性；同名但类型不同 → 报错；
        // 找不到同名 → 报「未映射目标属性」（@Mapper 的关键诊断）。
        val assignments = mutableListOf<JasMapperAssignment>()
        for (tProp in targetProps) {
            val explicitSource = explicitByTarget[tProp.name]
            if (explicitSource != null) {
                assignments.add(JasMapperAssignment(tProp.name, explicitSource))
                continue
            }
            val autoSource = sourceProps.firstOrNull { it.name == tProp.name }
            if (autoSource == null) {
                errors.add(AnnotationError(
                    "Unmapped target property '${tProp.name}' in mapper method '${method.name}': no source property named '${tProp.name}' on '${sourceTypeName}'",
                    line, column
                ))
                continue
            }
            if (!sameSimpleType(autoSource.type, tProp.type)) {
                errors.add(AnnotationError(
                    "Type mismatch for property '${tProp.name}' in mapper method '${method.name}': ${describeType(autoSource.type)} vs ${describeType(tProp.type)}",
                    line, column
                ))
                continue
            }
            assignments.add(JasMapperAssignment(tProp.name, autoSource.name))
        }

        // 只要本次调用新增过错误，就整体放弃该方法（不发出半成品）。
        if (errors.size > before) return null

        // 两段式合成：只产出「签名 + JasSyntheticMapper 映射计划」，body 留 null；
        // 计划由 lowering 阶段（JasperToIrTranslator.syntheticMapperBody）消费成具体赋值语句。
        return JasFunction(
            name = method.name,
            parameters = method.parameters,
            returnType = method.returnType,
            body = null,
            modifiers = listOf("public"),
            syntheticMapper = JasSyntheticMapper(
                parameterName = sourceParam.name,
                targetType = JasNamedType(targetTypeName),
                assignments = assignments
            )
        )
    }

    /**
     * Structural equality for the v1 "same type" rule (primitives, `string`,
     * named types without type arguments). Nullable wrappers are transparent so
     * `string?` and `string` are treated as the same source shape in v1.
     *
     * 做什么：v1 的「同类型」结构相等判定（原始类型、string、无类型实参的命名类型）。
     * 什么时候用：@Mapping 校验与自动映射时判断源/目标属性类型是否一致。
     * 前置条件：null 视为不匹配（返回 false）。
     * 边界：可空包装（`string?` 与 `string`）在 v1 被视为同一形态，不区分可空性。
     */
    private fun sameSimpleType(a: JasType?, b: JasType?): Boolean {
        val ka = typeKey(a) ?: return false
        return ka == typeKey(b)
    }

    private fun typeKey(type: JasType?): String? = when (type) {
        null -> null
        is JasPrimitiveType -> primitiveAlias(type.name)
        is JasStringType -> "string"
        is JasNamedType -> "named:${type.name}"
        is JasNullableType -> typeKey(type.inner)
        is JasNonNullType -> typeKey(type.inner)
        is JasAnyType -> "any"
        is JasUnitType -> "unit"
        is JasArrayType -> "array:" + typeKey(type.inner)
        else -> type.toString()
    }

    private fun primitiveAlias(name: String): String = when (name.lowercase()) {
        "int" -> "int32"
        "long" -> "int64"
        "float" -> "float32"
        "double" -> "float64"
        "boolean" -> "bool"
        else -> name.lowercase()
    }

    private fun describeType(type: JasType?): String = when (type) {
        null -> "<none>"
        is JasPrimitiveType -> type.name
        is JasStringType -> "string"
        is JasNamedType -> type.name
        is JasNullableType -> describeType(type.inner) + "?"
        is JasNonNullType -> describeType(type.inner) + "!"
        else -> type.toString()
    }

    /**
     * Extracts the `name = "value"` string arguments of an annotation.
     *
     * Main path: `@Mapping(source = "a", target = "b")` is normalised by the AST
     * builder ([jasper.ast.JasperAstBuilder]) into [JasAnnotationNamedArg] — the
     * grammar's `name = value` ambiguity (positional branch) is folded away at
     * AST-construction time, so consumers no longer have to adapt per call site.
     * The positional-assignment branch below is kept **defensive only**: it covers
     * ASTs assembled by other means (e.g. tests or future front-ends) and is not
     * expected to fire for source parsed by the normal pipeline.
     *
     * 做什么：抽取注解的 `name = "value"` 字符串实参，返回 名字->值 映射。
     * 什么时候用：解析 @Mapping 的 source/target 实参。
     * 前置条件：只取值为字符串字面量的实参；其余实参（含嵌套注解）忽略。
     * 为什么这样写（边界）：主路径只处理 [JasAnnotationNamedArg]——AST 构建层
     *   （JasperAstBuilder.parseAnnotationArgs）已把「位置参数里包着 JasAssignment」的
     *   形态归一化为命名实参。下面的位置分支**仅为防御**，兼容以其它方式构造的 AST，
     *   正常流水线解析出的源码不会再走到它；这样注解消费者无需各自复制适配逻辑。
     */
    private fun annotationStringArgs(annotation: JasAnnotationUse): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (argument in annotation.arguments) {
            when (argument) {
                is JasAnnotationNamedArg -> {
                    val value = argument.value as? JasStringLiteral ?: continue
                    result[argument.name] = value.text
                }
                is JasAnnotationPositionalArg -> {
                    // 防御分支：仅当归一化未生效（AST 由其它途径构造）时才可能命中。
                    val value = argument.value
                    if (value is JasAssignment && value.op == "=" &&
                        value.target is JasIdentifier && value.value is JasStringLiteral
                    ) {
                        result[(value.target as JasIdentifier).name] = (value.value as JasStringLiteral).text
                    }
                }
                else -> {}
            }
        }
        return result
    }

    /**
     * Synthesizes the *signature only* of a getter: `getFoo(): T`, body = null.
     * The body is produced later by the translator from [JasSyntheticAccessor].
     *
     * 做什么：合成 getter 的「签名」（`getFoo(): T`），body 置空。
     * 什么时候用：processClass 判定某属性需要 getter 时。
     * 前置条件：属性已通过 isEligibleForGetter。
     * 为什么这样设计：两段式合成——这里只出签名供类型检查；体由 lowering 阶段
     *   依据 [JasSyntheticAccessor]（propertyName + isGetter）生成 `return this.prop`。
     */
    private fun getterSignature(name: String, prop: JasProperty): JasFunction = JasFunction(
        name = name,
        parameters = emptyList(),
        returnType = prop.type,
        body = null,
        modifiers = listOf("public"),
        syntheticAccessor = JasSyntheticAccessor(prop.name, isGetter = true)
    )

    /**
     * Synthesizes the *signature only* of a setter: `setFoo(value: T): void`,
     * body = null. The body is produced later by the translator.
     *
     * 做什么：合成 setter 的「签名」（`setFoo(value: T): void`），body 置空。
     * 什么时候用：processClass 判定某属性需要 setter 时。
     * 前置条件：属性已通过 isEligibleForSetter（有类型、非 static、非 const）。
     * 为什么这样设计：与 getter 同理的两段式；体由 lowering 依据 [JasSyntheticAccessor] 生成 `this.prop = value`。
     */
    private fun setterSignature(name: String, prop: JasProperty): JasFunction = JasFunction(
        name = name,
        parameters = listOf(JasParameter("value", prop.type)),
        returnType = JasUnitType,
        body = null,
        modifiers = listOf("public"),
        syntheticAccessor = JasSyntheticAccessor(prop.name, isGetter = false)
    )

    /**
     * Synthesizes the *signature only* of a `@EqualsAndHashCode` member:
     * `equals(other: any): bool` or `hash(): int32`. The body is produced later
     * by the translator from [JasSyntheticEquality].
     *
     * 做什么：合成 @EqualsAndHashCode 成员的「签名」——`equals(other: any): bool` 或 `hash(): int32`。
     * 什么时候用：类级或属性级 @EqualsAndHashCode 生效且同名手写方法不存在时。
     * 前置条件：fields 为已判定的合格实例字段列表（类级=全部合格属性，属性级=被标注者）。
     * 为什么这样设计：签名需在类型检查时可见；字段清单随签名一起塞进 [JasSyntheticEquality]，
     *   让 lowering 阶段生成函数体时无需重新推导类结构。
     */
    private fun equalitySignature(isEquals: Boolean, className: String, fields: List<JasEqualityField>): JasFunction {
        val marker = JasSyntheticEquality(isEquals = isEquals, className = className, fields = fields)
        return if (isEquals) {
            JasFunction(
                name = EQUALS_METHOD,
                parameters = listOf(JasParameter("other", JasAnyType)),
                returnType = JasPrimitiveType("bool"),
                body = null,
                modifiers = listOf("public"),
                syntheticEquality = marker
            )
        } else {
            JasFunction(
                name = HASH_METHOD,
                parameters = emptyList(),
                returnType = JasPrimitiveType("int32"),
                body = null,
                modifiers = listOf("public"),
                syntheticEquality = marker
            )
        }
    }

    // 资格判定（Getter/Setter/Equality 共用一套「可用属性」概念）：
    //   1) 必须有声明类型——合成器无法为「待推导类型」生成签名；
    //   2) 必须是实例字段——static 无 this 接收者，访问器/相等性都无意义。
    // Setter 额外排除 const（常量不可写）。
    private fun isEligibleForGetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop)

    private fun isEligibleForSetter(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop) && !prop.isConst

    /** Equality/hash participation: typed instance fields only (no statics). */
    // equals/hash 参与资格：仅有类型的实例字段（排除 static）。
    private fun isEligibleForEquality(prop: JasProperty): Boolean =
        prop.type != null && !isStatic(prop)

    private fun isStatic(prop: JasProperty): Boolean =
        prop.modifiers.any { it is String && it == "static" }

    // 命名规则：前缀 + 属性名首字母大写（如 foo -> getFoo）。空名特判保证不会越界取 propName[0]。
    private fun accessorName(prefix: String, propName: String): String {
        if (propName.isEmpty()) return prefix
        return prefix + propName[0].uppercaseChar() + propName.substring(1)
    }

    private fun simpleName(qualified: String): String = qualified.substringAfterLast('.')

    /**
     * 把 AST 成员裁剪成校验器需要的「目标种类 + 名字 + 注解」视图。
     *
     * 做什么：只保留属性和方法；构造器/嵌套类型等不参与当前注解规则，直接忽略。
     * 什么时候用：processClass / processInterface 调用 validateContainer 之前。
     * 前置条件：members 为类/接口的直接成员，顺序与源码一致。
     * 失败模式：无（无匹配成员时返回空列表）。
     * 为什么这样设计：校验器不应依赖 JasProperty/JasFunction 的具体结构；裁剪后
     *   规则执行面更小，也更难被未来新增成员种类意外影响（隔离 AST 形状变化）。
     */
    private fun annotatedMemberViews(members: List<JasDeclaration>): List<AnnotatedMember> =
        members.mapNotNull { member ->
            when (member) {
                is JasProperty -> AnnotatedMember(
                    target = AnnotationTarget.PROPERTY,
                    name = member.name,
                    annotations = member.modifiers.filterIsInstance<JasAnnotationUse>()
                )
                is JasFunction -> AnnotatedMember(
                    target = AnnotationTarget.FUNCTION,
                    name = member.name,
                    annotations = member.modifiers.filterIsInstance<JasAnnotationUse>()
                )
                else -> null
            }
        }

    // 注解索引登记：把一次注解使用追加进 `类名 -> 记录列表`。只做记录、不改 AST 语义，
    // 因此任何注解（无论是否被本阶段处理）都会被登记，供 `.jaso` 清单/扩展点索引用。
    private fun record(
        records: MutableMap<String, MutableList<AnnotationRecord>>,
        className: String,
        targetKind: String,
        targetName: String,
        annotation: JasAnnotationUse
    ) {
        records.getOrPut(className) { mutableListOf() }.add(
            AnnotationRecord(
                annotationName = simpleName(annotation.name),
                targetKind = targetKind,
                targetName = targetName,
                arguments = annotation.arguments
            )
        )
    }

    // Registry 分派的「注解名常量表」：主流程按这些简单名做相等比较来路由到对应处理器分支。
    // 新增一种注解时，只在此加常量 + 在分派处加一个分支，隔离了「新增一种注解」的变化点。
    companion object {
        const val GETTER = "Getter"
        const val SETTER = "Setter"
        const val EQUALS_AND_HASH_CODE = "EqualsAndHashCode"
        const val EQUALS_METHOD = "equals"
        const val HASH_METHOD = "hash"
        const val MAPPER = "Mapper"
        const val MAPPING = "Mapping"
        const val MAPPER_IMPL_SUFFIX = "Impl"
    }
}
