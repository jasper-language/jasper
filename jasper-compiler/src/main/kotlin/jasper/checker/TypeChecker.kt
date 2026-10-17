// ============================================================================
// TypeChecker.kt —— Jasper 编译器「类型检查层」
// ============================================================================
// 职责：
//   对 JasSourceFile 做静态检查与类型推断：声明去重、类型参数/where 约束校验、
//   语句与表达式类型检查、成员解析、方法调用重载选择与泛型推断，产出诊断列表。
//
// 所属层次：
//   checker 层，依赖 ast（更低层）与基础符号设施 SymbolTable（symbol 层）；
//   不依赖 translator / backend。
//
// 与谁协作：
//   - 上游由 compiler 编排层调用 check() 并聚合返回的 List<TypeError>；
//   - 通过 SymbolTable 查询已声明类型与成员签名（含注解合成的 getter/setter）；
//   - 函数对象协议（Mapper/Predicate/Reducer/Comparator/Action）的方法名与
//     translator 中的 PROTOCOL_METHODS 保持一致，是两者之间的隐式契约。
//
// 对外契约：
//   - check(sourceFile): List<TypeError>：入口，清空内部状态后做一次完整检查；
//   - 只回写推断结果 expr.inferredTypeArgs（供下游生成代码使用），不改动 AST 语义；
//   - 只做检查与推断，**不生成代码**。
//
// 扩展点与已知限制：
//   - symbolTable 可空；为 null 时退化为「结构检查」，类型名/成员无法解析，仅告警；
//   - 类型兼容性是一组启发式规则（数值提升、可空性、接口子类型、SAM），非完备系统；
//   - 未知类型大量以 JasAnyType 兜底，会静默通过后续检查（可能掩盖真实错误）；
//   - 变更点集中：新增类型/表达式只需在对应 `when` 分支扩展，不牵动其它规则。
// ============================================================================

package jasper.checker

import jasper.ast.*
import jasper.compiler.SymbolTable

/**
 * 类型检查诊断：一条消息 + 可选的出错 AST 节点。
 *
 * 什么时候用：TypeChecker 内部通过 error() 产生，最终由 compiler 聚合输出。
 * 失败模式：仅承载信息，无副作用。
 * 为什么：把「一条诊断」与它在源码中的位置节点绑定，便于上层定位与展示。
 */
data class TypeError(val message: String, val node: JasNode? = null)

/**
 * 类型检查器：对单个源文件做声明/语句/表达式检查并返回诊断列表。
 *
 * 做什么：check() 先收集类-接口实现/继承关系，再逐声明做类型检查与推断。
 * 什么时候用：语法解析成功、符号表构建完成之后调用。
 * 前置条件：symbolTable 可为 null；为 null 时退化为结构检查（无法解析类型/成员）。
 * 失败模式：不抛异常，所有问题以 List<TypeError> 返回（可能为空）。
 * 为什么：在生成代码前尽早发现类型错误。
 *
 * 设计说明：errors / classImplements / interfaceExtends 是复用的实例字段，
 * 每次 check() 会先清空。因此同一实例不保证线程安全，也不适合并发检查不同文件。
 */
class TypeChecker(private val symbolTable: SymbolTable? = null) {
    private val errors = mutableListOf<TypeError>()
    private val classImplements = mutableMapOf<String, List<String>>() // className -> interfaceNames
    private val interfaceExtends = mutableMapOf<String, List<String>>() // interfaceName -> parentInterfaceNames

    /**
     * Function-object protocols recognized as "semantic" (no JVM interface
     * dispatch): the protocol name maps to its single abstract method name.
     * Declared in the Jasper standard library; see `PROTOCOL_METHODS` in the
     * translator for the call-routing counterpart.
     *
     * 中文说明：这是一张「协议名 -> 唯一抽象方法名」的静态分派表。Jasper 的函数对象
     * 协议（Mapper/Predicate/...）在 JVM 上不做接口分派，而是靠这张表与 translator 的
     * PROTOCOL_METHODS 对应，识别 lambda 与协议类型之间的 SAM 兼容性。
     */
    private val PROTOCOL_SAM = mapOf(
        "Mapper" to "apply",
        "Predicate" to "test",
        "Reducer" to "reduce",
        "Comparator" to "compare",
        "Action" to "accept"
    )

    /**
     * 检查整个源文件并返回本次累积的诊断列表。
     *
     * 做什么：清空上次状态 -> 顶层声明去重 -> 收集类/接口层次 -> 逐声明检查。
     * 什么时候用：对一个 JasSourceFile 做完整类型检查；每次检查都应调用本方法。
     * 前置条件：sourceFile 已完成解析。
     * 失败模式：不抛异常；返回列表可能为空（表示无诊断）。
     * 为什么：以「一次调用 = 一次完整、可重复的检查」为契约，避免残留上次状态。
     */
    fun check(sourceFile: JasSourceFile): List<TypeError> {
        // 每次全新开始：清空诊断与层次缓存，保证同一实例可复用而不串状态。
        errors.clear()
        classImplements.clear()
        interfaceExtends.clear()
        checkDuplicateTopLevelDeclarations(sourceFile)
        // First pass: collect class hierarchy (interfaces, implements)
        // 第一遍只收集「类->接口」「接口->父接口」的层次信息，供后续子类型判定使用，
        // 必须先于声明检查完成，否则 isSubtype 会拿不到关系。
        collectHierarchyFromDeclarations(sourceFile.declarations)
        // 跨文件：SymbolTable 汇聚了本次编译全部文件的类/接口声明，必须把它也并入层次图，
        // 否则「接口类型赋值点」与「实现类」分处不同文件时 isSubtype 查不到关系，
        // 会误报 Type mismatch（#7）。这里用「不覆盖」语义，保证当前文件的声明优先。
        symbolTable?.classes?.values?.forEach { collectClassHierarchy(it) }
        symbolTable?.interfaces?.values?.forEach { collectInterfaceHierarchy(it) }
        // 第二遍：每个顶层声明在全新的空环境中检查（局部作用域由各检查函数自行派生）。
        for (decl in sourceFile.declarations) {
            checkDeclaration(decl, TypeEnvironment())
        }
        return errors.toList()
    }

    /**
     * 做什么：从一组顶层声明中收集「类——implements——>接口」「接口——extends——>父接口」关系。
     *
     * 什么时候用：[check] 的第一遍，先处理当前文件的声明。
     * 前置条件：无；只读取 AST 上已解析的接口名。
     * 失败模式：不抛异常；非 JasNamedType 的接口引用被忽略（无法静态判定）。
     * 为什么单独抽出：跨文件修复（#7）需要对「当前文件」与「符号表里的其它文件」用
     *   同一套收集逻辑，抽成函数避免两处重复实现。
     */
    private fun collectHierarchyFromDeclarations(declarations: List<JasDeclaration>) {
        for (decl in declarations) {
            when (decl) {
                is JasClass -> collectClassHierarchy(decl)
                is JasInterface -> collectInterfaceHierarchy(decl)
                else -> {}
            }
        }
    }

    /** 记录一个类的 implements 列表；已存在的条目不被覆盖（当前文件优先）。 */
    private fun collectClassHierarchy(cls: JasClass) {
        if (classImplements.containsKey(cls.name)) return
        // 只认 JasNamedType 形式的接口名；其它类型（如泛型实参）这里不展开。
        val ifaces = cls.interfaces.mapNotNull { iface ->
            if (iface is JasNamedType) iface.name else null
        }
        classImplements[cls.name] = ifaces
    }

    /** 记录一个接口的 extends 列表；已存在的条目不被覆盖（当前文件优先）。 */
    private fun collectInterfaceHierarchy(iface: JasInterface) {
        if (interfaceExtends.containsKey(iface.name)) return
        val parents = iface.extends.mapNotNull { ext ->
            if (ext is JasNamedType) ext.name else null
        }
        interfaceExtends[iface.name] = parents
    }

    private fun error(message: String, node: JasNode? = null) {
        errors.add(TypeError(message, node))
    }

    private fun warn(message: String, node: JasNode? = null) {
        // Warnings are non-fatal diagnostics, logged but do not block compilation
        // 警告是非致命诊断：当前实现有意留空（既不记录也不影响结果），仅作为语义占位。
    }

    /**
     * 类型检查的作用域环境：保存变量名->类型、类型参数，以及循环/switch 嵌套深度。
     *
     * 做什么：为语句/表达式检查提供词法作用域的查询与写入；copy() 生成子作用域。
     * 什么时候用：进入块/函数/分支/循环时由上层创建或复制，退出后丢弃（不做回滚）。
     * 前置条件：无；初始为空环境。
     * 失败模式：查询未命中返回 null / false，不报错。
     * 为什么：类型推断需要知道局部变量与类型参数，又不希望把这些状态污染到全局。
     * 已知限制：copy() 是浅拷贝，types/typeParameters 的复制互不影响，但
     *   loopDepth/switchDepth 按值复制，子环境里的增减不会回传父环境。
     */
    class TypeEnvironment {
        private val types = mutableMapOf<String, JasType?>()
        val typeParameters = mutableMapOf<String, JasTypeParameter>()
        var loopDepth: Int = 0
        var switchDepth: Int = 0

        /** 在当前作用域登记/覆盖「变量名 -> 类型」绑定。 */
        fun set(name: String, type: JasType?) { types[name] = type }
        /** 查询变量名的类型；未登记返回 null（可能代表尚未推断出的 any）。 */
        fun get(name: String): JasType? = types[name]
        /** 判断变量名是否已在当前作用域登记（用于重复声明检测与解析优先级）。 */
        fun contains(name: String): Boolean = name in types
        /** 登记一个类型参数，使其在类型引用与推断中可被识别。 */
        fun addTypeParameter(tp: JasTypeParameter) { typeParameters[tp.name] = tp }
        /** 按名查询类型参数的声明；未命中返回 null。 */
        fun getTypeParameter(name: String): JasTypeParameter? = typeParameters[name]
        /** 判断名字是否是当前作用域可见的类型参数。 */
        fun hasTypeParameter(name: String): Boolean = name in typeParameters
        /** 生成一份浅拷贝的子作用域：变量与类型参数按值复制，循环/switch 深度一并复制。 */
        fun copy(): TypeEnvironment {
            val env = TypeEnvironment()
            env.types.putAll(this.types)
            env.typeParameters.putAll(this.typeParameters)
            env.loopDepth = this.loopDepth
            env.switchDepth = this.switchDepth
            return env
        }
    }

    private fun checkDuplicateTopLevelDeclarations(sourceFile: JasSourceFile) {
        // typeNames：任意两类同名的顶层类型都算冲突（putIfAbsent 命中即报错）。
        val typeNames = mutableMapOf<String, JasDeclaration>()
        // functionArities：函数按「名字 -> 已见参数个数集合」判重，即同名同参数个数视为重复，
        // 同名不同参数个数则是合法重载——这与 Jasper 允许重载的语义一致。
        val functionArities = mutableMapOf<String, MutableSet<Int>>()

        for (decl in sourceFile.declarations) {
            when (decl) {
                is JasClass -> checkDuplicateName(typeNames, decl.name, decl)
                is JasInterface -> checkDuplicateName(typeNames, decl.name, decl)
                is JasEnum -> checkDuplicateName(typeNames, decl.name, decl)
                is JasAnnotationType -> checkDuplicateName(typeNames, decl.name, decl)
                is JasFunction -> {
                    val arities = functionArities.getOrPut(decl.name) { mutableSetOf() }
                    if (!arities.add(decl.parameters.size)) {
                        error("Duplicate function declaration '${decl.name}' with ${decl.parameters.size} parameter(s)", decl)
                    }
                }
                else -> {}
            }
        }
    }

    private fun checkDuplicateName(names: MutableMap<String, JasDeclaration>, name: String, node: JasDeclaration) {
        // putIfAbsent 返回非 null 说明该名字此前已登记过，即重复声明。
        if (names.putIfAbsent(name, node) != null) {
            error("Duplicate type declaration '$name'", node)
        }
    }

    private fun checkDeclaration(decl: JasDeclaration, env: TypeEnvironment) {
        // 顶层声明按类型分派；新声明种类应在此 when 中新增分支（唯一变化点）。
        when (decl) {
            is JasFunction -> checkFunction(decl, env)
            is JasClass -> checkClass(decl, env)
            is JasInterface -> {
                val ifaceEnv = env.copy()
                checkTypeParameters(decl.typeParameters, ifaceEnv, "interface '${decl.name}'")
                decl.extends.forEach { checkTypeReferences(it, ifaceEnv) }
            }
            is JasEnum -> {}
            is JasProperty -> {}
            is JasImport -> {}
            is JasAnnotationType -> {
                // 注解成员若带默认值，则默认值表达式的类型必须能赋给声明的成员类型。
                for (member in decl.members) {
                    if (member.defaultValue != null) {
                        val defaultType = inferExpression(member.defaultValue, env)
                        if (defaultType != null && member.type != null && !typesCompatible(defaultType, member.type!!)) {
                            error("Annotation member default value type mismatch: expected ${member.type}, got $defaultType", member)
                        }
                    }
                }
            }
            is JasAnnotationMember -> {
                // 注解成员必须显式声明返回类型，否则无法描述注解的元数据形状。
                if (decl.type == null) {
                    error("Annotation member must have a return type", decl)
                }
            }
            else -> {}
        }
    }

    private fun checkTypeParameters(typeParams: List<JasTypeParameter>, env: TypeEnvironment, context: String) {
        // seen 用于检测同一类型参数列表内的重名；bound 若存在则必须是合法类型引用。
        val seen = mutableSetOf<String>()
        for (tp in typeParams) {
            if (!seen.add(tp.name)) {
                error("Duplicate type parameter '${tp.name}' in $context", tp)
                continue
            }
            if (tp.bound != null) {
                if (!isValidTypeReference(tp.bound!!)) {
                    error("Type parameter '${tp.name}' bound '${typeDisplayName(tp.bound!!)}' is not a valid type in $context", tp)
                }
            }
            env.addTypeParameter(tp)
        }
    }

    private fun checkWhereConstraints(constraints: List<JasTypeConstraint>, env: TypeEnvironment, context: String) {
        // 每条 where 约束必须引用一个已声明的类型参数，且上界是合法类型。
        for (wc in constraints) {
            if (!env.hasTypeParameter(wc.typeParamName)) {
                error("Where constraint references unknown type parameter '${wc.typeParamName}' in $context", null)
            }
            if (!isValidTypeReference(wc.bound)) {
                error("Where constraint bound '${typeDisplayName(wc.bound)}' is not a valid type in $context", null)
            }
        }
    }

    private fun isValidTypeReference(type: JasType): Boolean {
        // 结构递归校验：容器类型（数组/可空/函数/元组/通配）要求其内部类型同样合法。
        // 末分支 else -> true 是宽松兜底：未识别的类型默认视为可用。
        return when (type) {
            is JasPrimitiveType -> true
            is JasNamedType -> true
            is JasStringType, is JasUnitType, is JasAnyType -> true
            is JasArrayType -> isValidTypeReference(type.inner)
            is JasNullableType -> isValidTypeReference(type.inner)
            is JasNonNullType -> isValidTypeReference(type.inner)
            is JasWildcardType -> type.bound == null || isValidTypeReference(type.bound!!)
            is JasFunctionType -> type.paramTypes.all { isValidTypeReference(it) } && isValidTypeReference(type.returnType)
            is JasTupleType -> type.types.all { isValidTypeReference(it) }
            else -> true
        }
    }

    private fun checkTypeArgumentCount(name: String, typeArgs: List<JasType>, node: JasNode?) {
        // 只有能在符号表中找到的类/接口才校验类型实参个数；找不到时直接返回（不报错，
        // 因为可能来自外部/JVM 类型）。typeArgs 为空表示未显式给出实参，此时不校验。
        val typeParamCount = when {
            symbolTable?.findClass(name) != null -> symbolTable!!.findClass(name)!!.typeParameters.size
            symbolTable?.findInterface(name) != null -> symbolTable!!.findInterface(name)!!.typeParameters.size
            else -> return
        }
        if (typeArgs.isNotEmpty() && typeArgs.size != typeParamCount) {
            error("Type '$name' expects $typeParamCount type argument(s), got ${typeArgs.size}", node)
        }
    }

    private fun checkTypeReferences(type: JasType?, env: TypeEnvironment) {
        // 只做「类型引用」层面的检查（个数/嵌套合法性），不解析具体类型含义。
        if (type == null) return
        when (type) {
            is JasNamedType -> {
                checkTypeArgumentCount(type.name, type.typeArguments, null)
                type.typeArguments.forEach { checkTypeReferences(it, env) }
            }
            is JasArrayType -> checkTypeReferences(type.inner, env)
            is JasNullableType -> checkTypeReferences(type.inner, env)
            is JasNonNullType -> checkTypeReferences(type.inner, env)
            is JasFunctionType -> {
                type.paramTypes.forEach { checkTypeReferences(it, env) }
                checkTypeReferences(type.returnType, env)
            }
            is JasTupleType -> type.types.forEach { checkTypeReferences(it, env) }
            is JasWildcardType -> type.bound?.let { checkTypeReferences(it, env) }
            else -> {}
        }
    }

    private fun checkFunction(func: JasFunction, env: TypeEnvironment) {
        // 函数体在「复制后的子环境」中检查：类型参数与形参注入其中，不影响外层。
        val funcEnv = env.copy()
        checkTypeParameters(func.typeParameters, funcEnv, "function '${func.name}'")
        checkWhereConstraints(func.whereConstraints, funcEnv, "function '${func.name}'")
        val params = mutableSetOf<String>()
        for (param in func.parameters) {
            // 形参名重复即报错；随后把形参登记进环境供函数体解析。
            if (!params.add(param.name)) {
                error("Duplicate parameter '${param.name}' in function '${func.name}'", param)
            }
            checkTypeReferences(param.type, funcEnv)
            funcEnv.set(param.name, param.type)
        }
        checkTypeReferences(func.returnType, funcEnv)
        if (func.body != null) {
            // body=null 表示只有签名（内置/外部函数），无需检查语句。
            for (stmt in func.body!!.statements) {
                checkStatement(stmt, funcEnv, func.returnType)
            }
        }
    }

    private fun checkClass(cls: JasClass, env: TypeEnvironment) {
        // 类的成员检查共享同一个 clsEnv：属性会被登记，使后续方法体能解析本类字段。
        val clsEnv = env.copy()
        checkTypeParameters(cls.typeParameters, clsEnv, "class '${cls.name}'")
        checkTypeReferences(cls.superclass, clsEnv)
        cls.interfaces.forEach { checkTypeReferences(it, clsEnv) }
        val memberNames = mutableSetOf<String>()
        for (member in cls.members) {
            when (member) {
                is JasProperty -> {
                    // 属性用「名字」判重。
                    if (!memberNames.add(member.name)) {
                        error("Duplicate member '${member.name}' in class '${cls.name}'", member)
                    }
                    checkTypeReferences(member.type, clsEnv)
                    clsEnv.set(member.name, member.type)
                }
                is JasFunction -> {
                    // 方法用「名字/参数个数」判重：同名同 arity 才算重复，允许重载。
                    val signature = "${member.name}/${member.parameters.size}"
                    if (!memberNames.add(signature)) {
                        error("Duplicate method '${member.name}' with ${member.parameters.size} parameter(s) in class '${cls.name}'", member)
                    }
                }
                else -> {}
            }
            checkDeclaration(member, clsEnv)
        }
        for (ctor in cls.constructors) {
            // 构造器各自独立子环境；返回类型固定为 void，故传 JasUnitType。
            val ctorEnv = clsEnv.copy()
            val params = mutableSetOf<String>()
            for (param in ctor.parameters) {
                if (!params.add(param.name)) {
                    error("Duplicate constructor parameter '${param.name}' in class '${cls.name}'", param)
                }
                checkTypeReferences(param.type, ctorEnv)
                ctorEnv.set(param.name, param.type)
            }
            if (ctor.body != null) {
                for (stmt in ctor.body!!.statements) {
                    checkStatement(stmt, ctorEnv, JasUnitType)
                }
            }
        }
    }

    private fun checkStatement(stmt: JasStatement, env: TypeEnvironment, returnType: JasType?) {
        // returnType 用于校验 return/表达式语句；对无返回值的上下文传 null（如 lambda 初查）。
        when (stmt) {
            is JasBlock -> stmt.statements.forEach { checkStatement(it, env, returnType) }
            is JasVariableStatement -> {
                // 同一作用域内重名即报错（不做跨作用域的遮蔽检查）。
                if (env.contains(stmt.name)) {
                    error("Duplicate local declaration '${stmt.name}'", stmt)
                }
                if (stmt.initializer != null) {
                    // 声明类型显式给出时，要求初始化表达式可赋给它。
                    val initType = inferExpression(stmt.initializer, env)
                    if (stmt.type != null && initType != null) {
                        if (!typesCompatible(initType, stmt.type!!)) {
                            error("Type mismatch: cannot assign ${typeDisplayName(initType)} to variable of type ${typeDisplayName(stmt.type!!)}", stmt)
                        }
                    }
                }
                // 变量类型：优先用显式声明类型；否则用初始化表达式的推断结果（可能为 null）。
                env.set(stmt.name, stmt.type ?: stmt.initializer?.let { inferExpression(it, env) })
            }
            is JasExpressionStatement -> checkExpression(stmt.expression, env)
            is JasReturn -> {
                if (stmt.value != null) {
                    // 带值 return：值类型必须可赋给声明返回类型。
                    val valueType = inferExpression(stmt.value, env)
                    if (returnType != null && valueType != null) {
                        if (!typesCompatible(valueType, returnType)) {
                            error("Return type mismatch: expected ${typeDisplayName(returnType)}, got ${typeDisplayName(valueType)}", stmt)
                        }
                    }
                } else if (returnType != null && returnType !is JasUnitType) {
                    // 裸 return：仅当函数有非 void 返回类型时才是错误。
                    error("Return type mismatch: expected ${typeDisplayName(returnType)}, got void", stmt)
                }
            }
            is JasIf -> {
                checkBooleanCondition(stmt.condition, env, "if", stmt)
                checkStatement(stmt.thenBody, env, returnType)
                if (stmt.elseBody != null) checkStatement(stmt.elseBody, env, returnType)
            }
            is JasWhile -> {
                checkBooleanCondition(stmt.condition, env, "while", stmt)
                // 进入循环体前加深 loopDepth，使 break/continue 的合法性判定生效。
                env.loopDepth++
                checkStatement(stmt.body, env, returnType)
                env.loopDepth--
            }
            is JasDoWhile -> {
                env.loopDepth++
                checkStatement(stmt.body, env, returnType)
                env.loopDepth--
                checkBooleanCondition(stmt.condition, env, "do-while", stmt)
            }
            is JasForStatement -> {
                val forEnv = env.copy()
                if (stmt.init != null) checkStatement(stmt.init, forEnv, returnType)
                if (stmt.condition != null) checkBooleanCondition(stmt.condition, forEnv, "for", stmt)
                if (stmt.update != null) checkExpression(stmt.update, forEnv)
                forEnv.loopDepth++
                checkStatement(stmt.body, forEnv, returnType)
                forEnv.loopDepth--
            }
            is JasForInStatement -> {
                val iterableType = inferExpression(stmt.iterable, env)
                val loopEnv = env.copy()
                // Arrays expose their element type directly; a declared object
                // type is consumed through the iteration protocol, so the element
                // type is the `next()` member's return type.
                // 中文说明：数组直接取元素类型；具名类型按迭代协议消费，元素类型取
                // 其 `next()` 成员的返回类型；两者都拿不到时兜底为 any。
                val elemType = when (iterableType) {
                    is JasArrayType -> iterableType.inner
                    is JasNamedType -> (findMemberType(iterableType.name, "next", iterableType.typeArguments) as? JasFunctionType)?.returnType
                    else -> null
                } ?: JasAnyType
                // 循环变量登记到子环境；then/else 分支用外层 env（不在循环变量的作用域内）。
                loopEnv.set(stmt.varName, elemType)
                loopEnv.loopDepth++
                checkStatement(stmt.body, loopEnv, returnType)
                loopEnv.loopDepth--
                if (stmt.thenBody != null) checkStatement(stmt.thenBody, env, returnType)
                if (stmt.elseBody != null) checkStatement(stmt.elseBody, env, returnType)
            }
            is JasThrow -> inferExpression(stmt.expression, env)
            is JasTry -> {
                checkStatement(stmt.body, env, returnType)
                for (catchClause in stmt.catches) {
                    val catchEnv = env.copy()
                    catchEnv.set(catchClause.parameter.name, catchClause.parameter.type)
                    checkStatement(catchClause.body, catchEnv, returnType)
                }
                if (stmt.finallyBody != null) checkStatement(stmt.finallyBody, env, returnType)
            }
            is JasSwitch -> {
                inferExpression(stmt.expression, env)
                // 至少要有一个 case，否则 switch 无法产生任何分支。
                if (stmt.cases.isEmpty()) {
                    error("Switch must have at least one case", stmt)
                }
                // switch 深度用于放行 break（break 可作用于循环或 switch）。
                env.switchDepth++
                for (caseClause in stmt.cases) {
                    for (value in caseClause.values) {
                        inferExpression(value, env)
                    }
                    checkStatement(caseClause.body, env, returnType)
                }
                env.switchDepth--
            }
            is JasBreakStatement -> {
                // break 必须位于循环或 switch 内。
                if (env.loopDepth == 0 && env.switchDepth == 0) {
                    error("Break outside loop or switch", stmt)
                }
            }
            is JasContinueStatement -> {
                // continue 只对循环有效，switch 不能放行。
                if (env.loopDepth == 0) {
                    error("Continue outside loop", stmt)
                }
            }
            is JasDefer -> {
                checkStatement(stmt.body, env, returnType)
            }
            is JasLock -> {
                inferExpression(stmt.expression, env)
                checkStatement(stmt.body, env, returnType)
            }
            is JasAssert -> {
                checkBooleanCondition(stmt.condition, env, "assert", stmt)
                if (stmt.message != null) inferExpression(stmt.message, env)
            }
            is JasYield -> {
                // yield 需要生成器支持，当前语言尚未实现，先报错再继续检查其表达式以免静默。
                error("yield is not yet supported (generator functions are not implemented)", stmt)
                inferExpression(stmt.expression, env)
            }
            is JasLabeledStatement -> {
                checkStatement(stmt.statement, env, returnType)
            }
            is JasDestructuringDeclaration -> {
                // 已知限制：这里把初始化表达式的整体类型赋给每个绑定（未按元组/结构逐项解开），
                // 因此带显式类型的绑定以自身类型为准，其余绑定沿用整体类型。
                val initType = inferExpression(stmt.initializer, env)
                for (binding in stmt.bindings) {
                    env.set(binding.name, binding.type ?: initType)
                }
            }
            is JasMatch -> {
                inferExpression(stmt.expression, env)
                for (matchCase in stmt.cases) {
                    checkMatchCase(matchCase, env, returnType)
                }
            }
            else -> {}
        }
    }

    private fun checkMatchCase(matchCase: JasMatchCase, env: TypeEnvironment, returnType: JasType?) {
        // 模式：通配符/模式类型目前不额外校验；表达式模式则做类型推断。
        if (matchCase.pattern != null) {
            when (val pattern = matchCase.pattern) {
                is JasWildcardPattern -> {}
                is JasPattern -> {}
                is JasExpression -> inferExpression(pattern, env)
                else -> {}
            }
        }
        // guard 必须为 bool。
        if (matchCase.guard != null) {
            checkBooleanCondition(matchCase.guard, env, "match guard", matchCase)
        }
        checkStatement(matchCase.body, env, returnType)
    }

    private fun checkBooleanCondition(expr: JasExpression, env: TypeEnvironment, context: String, node: JasNode) {
        // 条件表达式（if/while/for/assert/guard）必须为 bool；推断失败（null）时放行。
        val type = inferExpression(expr, env)
        if (type != null && !typesCompatible(type, JasPrimitiveType("bool"))) {
            error("$context condition must be bool, got ${typeDisplayName(type)}", node)
        }
    }

    private fun checkExpression(expr: JasExpression, env: TypeEnvironment) {
        // 表达式语句只为触发诊断，其类型本身不使用。
        inferExpression(expr, env)
    }

    private fun inferExpression(expr: JasExpression, env: TypeEnvironment): JasType? {
        // 返回 null 表示「无法推断」（多数由 warn 记录）；调用方普遍以 ?: JasAnyType 兜底。
        return when (expr) {
            is JasIntLiteral -> {
                // 整数字面量默认 int32；带 L/l 后缀表示 int64。
                val raw = expr.raw
                if (raw.endsWith("L") || raw.endsWith("l")) {
                    JasPrimitiveType("int64")
                } else {
                    JasPrimitiveType("int32")
                }
            }
            is JasFloatLiteral -> JasPrimitiveType("float64")
            is JasStringLiteral -> JasStringType
            is JasBoolLiteral -> JasPrimitiveType("bool")
            is JasNullLiteral -> JasNullableType(JasAnyType)
            is JasIdentifier -> {
                // 解析优先级：局部/形参 -> 类型参数 -> 符号表中的类型名 -> 函数名 -> 未解析。
                if (env.contains(expr.name)) {
                    env.get(expr.name)
                } else if (env.hasTypeParameter(expr.name)) {
                    // 类型参数标识符代表其自身；无上界时用 any。
                    val tp = env.getTypeParameter(expr.name)!!
                    tp.bound ?: JasAnyType
                } else if (symbolTable != null) {
                    if (symbolTable.findClass(expr.name) != null ||
                        symbolTable.findInterface(expr.name) != null ||
                        symbolTable.findEnum(expr.name) != null ||
                        symbolTable.findAnnotation(expr.name) != null) {
                        JasNamedType(expr.name)
                    } else if (symbolTable.findFunctions(expr.name).isNotEmpty()) {
                        // Function reference - return function type
                        // 函数引用：具体签名未知，先用无参、返回 any 的函数类型占位。
                        JasFunctionType(emptyList(), JasAnyType)
                    } else {
                        // 未解析：仅告警并返回 null，不产生硬错误。
                        warn("Unresolved reference: '${expr.name}'", expr)
                        null
                    }
                } else {
                    warn("Unresolved reference: '${expr.name}' (no symbol table)", expr)
                    null
                }
            }
            is JasBinaryOp -> {
                val leftType = inferExpression(expr.left, env)
                val rightType = inferExpression(expr.right, env)
                // 比较/逻辑运算符结果恒为 bool；其余（算术等）取一侧类型兜底 int32。
                when (expr.op) {
                    "&&", "||" -> JasPrimitiveType("bool")
                    "==", "!=" -> JasPrimitiveType("bool")
                    "<", ">", "<=", ">=" -> JasPrimitiveType("bool")
                    else -> leftType ?: rightType ?: JasPrimitiveType("int32")
                }
            }
            is JasUnaryOp -> {
                inferExpression(expr.operand, env)
                when (expr.op) {
                    "!" -> JasPrimitiveType("bool")
                    else -> inferExpression(expr.operand, env) ?: JasPrimitiveType("int32")
                }
            }
            is JasCall -> {
                // 先推断所有实参类型，再按 callee 形态走两条路：具名函数（重载解析）或函数值。
                val argTypes = expr.args.map { inferExpression(it, env) }
                if (symbolTable != null && expr.target is JasIdentifier) {
                    val name = (expr.target as JasIdentifier).name
                    val funcs = symbolTable.findFunctions(name)
                    if (funcs.isEmpty()) {
                        // 符号表里没有同名函数：可能是未知/外部函数，宽松放行为 any（不报错）。
                        JasAnyType
                    } else {
                        // 重载解析第一步：按实参个数（arity）过滤候选。
                        val byArity = funcs.filter { it.parameters.size == expr.args.size }
                        if (byArity.isEmpty()) {
                            error("Function '$name' expects ${funcs.joinToString(" or ") { it.parameters.size.toString() }} argument(s), got ${expr.args.size}", expr)
                            funcs.first().returnType ?: JasAnyType
                        } else {
                            // 第二步：在 arity 匹配的候选中找第一个「实参类型全部兼容」的重载。
                            // 兼容判定额外放行「形参是裸类型参数」的情形（留待 unify 推断），
                            // 以及任一侧类型为 null（推断失败）时的宽松放行。
                            val compatible = byArity.firstOrNull { fn ->
                                fn.parameters.zip(argTypes).all { (param, argType) ->
                                    param.type == null || argType == null ||
                                    typesCompatible(argType, param.type) ||
                                    (param.type is JasNamedType && fn.typeParameters.any { tp -> tp.name == (param.type as JasNamedType).name && (param.type as JasNamedType).typeArguments.isEmpty() })
                                }
                            }
                            if (compatible == null) {
                                val signature = byArity.joinToString(" or ") { fn ->
                                    "(${fn.parameters.joinToString(", ") { p -> p.type?.let(::typeDisplayName) ?: "any" }})"
                                }
                                error("Function '$name' argument types do not match expected $signature", expr)
                                byArity.first().returnType ?: JasAnyType
                            } else {
                                if (compatible.typeParameters.isNotEmpty()) {
                                    // 泛型函数：用实参类型推断类型参数（unify 收集约束到 inferred）。
                                    val typeParamNames = compatible.typeParameters.map { it.name }.toSet()
                                    val inferred = mutableMapOf<String, JasType>()
                                    for ((param, argType) in compatible.parameters.zip(argTypes)) {
                                        if (param.type != null && argType != null) {
                                            unify(argType, param.type, typeParamNames, inferred)
                                        }
                                    }
                                    // 回填推断出的类型实参到 AST，供下游（如 translator）读取。
                                    // 已知限制：只要 inferred 非空就整体覆盖，不做与已有值的合并。
                                    if (inferred.isNotEmpty()) {
                                        expr.inferredTypeArgs = inferred.toMap()
                                    }
                                    // 校验推断结果是否满足各 where 约束（推断不出的类型参数跳过）。
                                    for (wc in compatible.whereConstraints) {
                                        val boundType = inferred[wc.typeParamName]
                                        if (boundType != null && !typesCompatible(boundType, wc.bound)) {
                                            error("Type parameter '${wc.typeParamName}' does not satisfy constraint '${typeDisplayName(wc.bound)}'", expr)
                                        }
                                    }
                                    // 把推断结果代入返回类型；无返回类型时按 void 处理。
                                    val substitutedReturn = if (inferred.isNotEmpty() && compatible.returnType != null) {
                                        substitute(compatible.returnType, inferred)
                                    } else {
                                        compatible.returnType
                                    }
                                    substitutedReturn ?: JasUnitType
                                } else {
                                    // 非泛型函数：直接返回声明返回类型；缺省 void。
                                    compatible.returnType ?: JasUnitType
                                }
                            }
                        }
                    }
                } else {
                    // callee 不是简单标识符（如函数值/lambda 变量）：按函数类型逐参校验。
                    val targetType = inferExpression(expr.target, env)
                    if (targetType is JasFunctionType) {
                        if (targetType.paramTypes.size != expr.args.size) {
                            error("Function value expects ${targetType.paramTypes.size} argument(s), got ${expr.args.size}", expr)
                        } else {
                            targetType.paramTypes.zip(argTypes).forEachIndexed { index, (expected, actual) ->
                                if (actual != null && !typesCompatible(actual, expected)) {
                                    error("Function argument ${index + 1} type mismatch: expected ${typeDisplayName(expected)}, got ${typeDisplayName(actual)}", expr)
                                }
                            }
                        }
                        targetType.returnType
                    } else {
                        // 目标既不是具名函数也不是已知函数类型：宽松放行为 any。
                        JasAnyType
                    }
                }
            }
            is JasPropertyAccess -> {
                // 成员解析顺序：具名类型查成员索引 -> 数组 length 特例 -> 其余告警并兜底 any。
                val targetType = inferExpression(expr.target, env)
                when {
                    targetType is JasNamedType -> {
                        findMemberType(targetType.name, expr.property, targetType.typeArguments) ?: run {
                            warn("Cannot resolve member '${expr.property}' on type '${targetType.name}'", expr)
                            JasAnyType
                        }
                    }
                    // 数组的 length 是语言内建属性，不来自成员声明。
                    targetType is JasArrayType && expr.property == "length" -> JasPrimitiveType("int32")
                    targetType != null -> {
                        val typeName = typeDisplayName(targetType)
                        warn("Property access '${expr.property}' on non-named type '$typeName'", expr)
                        JasAnyType
                    }
                    else -> {
                        warn("Cannot resolve property '${expr.property}' on unresolved target", expr)
                        JasAnyType
                    }
                }
            }
            is JasArrayAccess -> {
                // 下标必须是 int32；元素类型：数组取元素类型，字符串下标取 int32（字符码）。
                val targetType = inferExpression(expr.target, env)
                val indexType = inferExpression(expr.index, env)
                if (indexType != null && !typesCompatible(indexType, JasPrimitiveType("int32"))) {
                    error("Array index must be int32, got ${typeDisplayName(indexType)}", expr)
                }
                when (targetType) {
                    is JasArrayType -> targetType.inner
                    is JasStringType -> JasPrimitiveType("int32")
                    null -> {
                        warn("Array access on unresolved type", expr)
                        JasAnyType
                    }
                    else -> {
                        warn("Array access on non-array type ${typeDisplayName(targetType)}", expr)
                        JasAnyType
                    }
                }
            }
            is JasNew -> {
                // new 的类型即其表达式类型；仅对具名类型校验类型实参个数。
                if (expr.type is JasNamedType) {
                    val namedType = expr.type as JasNamedType
                    checkTypeArgumentCount(namedType.name, namedType.typeArguments, expr)
                }
                expr.type
            }
            is JasArrayCreation -> JasArrayType(expr.type)
            is JasLambdaExpr -> {
                // lambda 在子环境中检查：形参注入其中；返回类型由 body 的首个 return 推断。
                val lambdaEnv = env.copy()
                for (param in expr.parameters) {
                    lambdaEnv.set(param.name, param.type)
                }
                for (stmt in expr.body.statements) {
                    checkStatement(stmt, lambdaEnv, null)
                }
                JasFunctionType(
                    expr.parameters.map { it.type ?: JasAnyType },
                    inferLambdaReturnType(expr.body, lambdaEnv) ?: JasAnyType
                )
            }
            is JasMethodReference -> JasFunctionType(emptyList(), JasAnyType)
            is JasTernaryExpr -> {
                // 三元表达式：条件只推断不求 bool（宽松）；结果取 then/else 中先能推断出的一侧。
                inferExpression(expr.condition, env)
                val thenType = inferExpression(expr.thenExpr, env)
                val elseType = inferExpression(expr.elseExpr, env)
                thenType ?: elseType ?: JasAnyType
            }
            is JasCastExpr -> {
                // 强制转型的结果类型就是目标类型本身；仅校验具名类型的实参个数。
                if (expr.type is JasNamedType) {
                    checkTypeArgumentCount((expr.type as JasNamedType).name, (expr.type as JasNamedType).typeArguments, expr)
                }
                expr.type
            }
            is JasInstanceOfExpr -> JasPrimitiveType("bool")
            is JasNullCoalescing -> {
                // `a ?: b`：左值可空则结果为其非空内层类型；左值非空则维持其类型。
                val leftType = inferExpression(expr.left, env)
                val rightType = inferExpression(expr.right, env)
                when (leftType) {
                    is JasNullableType -> leftType.inner
                    null -> rightType ?: JasAnyType
                    else -> leftType
                }
            }
            is JasStringTemplate -> JasStringType
            is JasDictLiteral -> JasNamedType("Map")
            is JasAssignment -> {
                // 赋值兼容性：右值类型必须可赋给左值（目标）类型；结果类型为目标类型。
                val valueType = inferExpression(expr.value, env)
                val targetType = inferExpression(expr.target, env)
                if (valueType != null && targetType != null && !typesCompatible(valueType, targetType)) {
                    error("Type mismatch: cannot assign ${typeDisplayName(valueType)} to ${typeDisplayName(targetType)}", expr)
                }
                targetType ?: JasAnyType
            }
            else -> {
                // 未覆盖的表达式种类：告警并兜底为 any（新增表达式类型应在此前补充分支）。
                warn("Unhandled expression type: ${expr::class.simpleName}", expr)
                JasAnyType
            }
        }
    }

    private fun findMemberType(
        typeName: String,
        memberName: String,
        typeArguments: List<JasType> = emptyList()
    ): JasType? {
        val raw = rawMemberType(typeName, memberName) ?: return null
        // Substitute the receiver's type arguments into the declaring type's
        // type parameters, so a member of `Mapper<int32, int32>` types as
        // `(int32) -> int32` instead of `(A) -> B`.
        // 中文说明：取成员时要把「接收者的类型实参」代入「声明类型的类型参数」。
        // 仅当能找到声明类型（接口或类）且实参非空时才做替换；否则原样返回。
        val typeParams = symbolTable?.findInterface(typeName)?.typeParameters
            ?: symbolTable?.findClass(typeName)?.typeParameters
            ?: return raw
        if (typeParams.isEmpty() || typeArguments.isEmpty()) return raw
        // 位置对应：第 i 个类型参数映射到第 i 个类型实参（不校验长度一致性）。
        val mapping = typeParams.map { it.name }.zip(typeArguments).toMap()
        return substitute(raw, mapping)
    }

    private fun rawMemberType(typeName: String, memberName: String): JasType? {
        // Resolve through the symbol table's member signature index first; this
        // index includes annotation-synthesized members (e.g. @Getter/@Setter).
        // 中文说明：优先走符号表的成员签名索引（含注解合成成员），再退回遍历 AST 成员。
        when (val indexed = symbolTable?.findMemberSignature(typeName, memberName)) {
            is JasProperty -> return indexed.type
            is JasFunction -> return JasFunctionType(
                indexed.parameters.map { it.type ?: JasAnyType },
                indexed.returnType ?: JasUnitType
            )
            else -> {}
        }

        // 索引未命中时的兜底：线性遍历类成员（与旧实现保持一致的首次匹配）。
        val cls = symbolTable?.findClass(typeName)
        if (cls != null) {
            for (member in cls.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType)
                }
            }
        }

        val iface = symbolTable?.findInterface(typeName)
        if (iface != null) {
            for (member in iface.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType)
                }
            }
        }

        return null
    }

    private fun inferLambdaReturnType(body: JasBlock, env: TypeEnvironment): JasType? {
        // 扫描 body 的第一处 return 作为返回类型；if 只有双分支都兼容时才确定类型。
        for (stmt in body.statements) {
            when (stmt) {
                is JasReturn -> return stmt.value?.let { inferExpression(it, env) } ?: JasUnitType
                is JasBlock -> inferLambdaReturnType(stmt, env)?.let { return it }
                is JasIf -> {
                    val thenType = inferLambdaReturnType(stmt.thenBody, env)
                    val elseType = stmt.elseBody?.let { inferLambdaReturnType(it, env) }
                    if (thenType != null && elseType != null && typesCompatible(thenType, elseType)) {
                        return thenType
                    }
                }
            }
        }
        return null
    }

    private fun unify(
        argType: JasType,
        paramType: JasType,
        typeParams: Set<String>,
        inferred: MutableMap<String, JasType>
    ): Boolean {
        // 结构合一：把形参里的类型参数与实参类型对应起来，写入 inferred。
        return when {
            // 形参是「裸类型参数」（无实参）：第一次遇到即记录推断结果；
            // 再次遇到同名的要求已推断类型与新实参兼容（否则合一失败）。
            paramType is JasNamedType && paramType.name in typeParams && paramType.typeArguments.isEmpty() -> {
                val existing = inferred[paramType.name]
                if (existing != null) {
                    typesCompatible(argType, existing)
                } else {
                    inferred[paramType.name] = argType
                    true
                }
            }
            // 同名泛型：递归对齐每个类型实参（如 List<T> 对 List<int32>）。
            paramType is JasNamedType && argType is JasNamedType && paramType.name == argType.name -> {
                paramType.typeArguments.zip(argType.typeArguments).all { (p, a) ->
                    unify(a, p, typeParams, inferred)
                }
            }
            // 兜底：直接看兼容性（不产生新的推断）。
            typesCompatible(argType, paramType) -> true
            else -> false
        }
    }

    private fun substitute(type: JasType, mapping: Map<String, JasType>): JasType {
        // 递归把类型里的类型参数名替换为 mapping 给定的具体类型；结构未变时返回原对象。
        return when (type) {
            is JasNamedType -> {
                val sub = mapping[type.name]
                if (sub != null && type.typeArguments.isEmpty()) {
                    sub
                } else {
                    val newArgs = type.typeArguments.map { substitute(it, mapping) }
                    if (newArgs != type.typeArguments) JasNamedType(type.name, newArgs) else type
                }
            }
            is JasWildcardType -> {
                val newBound = type.bound?.let { substitute(it, mapping) }
                if (newBound != type.bound) JasWildcardType(newBound, type.extends) else type
            }
            is JasFunctionType -> {
                val newParams = type.paramTypes.map { substitute(it, mapping) }
                val newReturn = substitute(type.returnType, mapping)
                if (newParams != type.paramTypes || newReturn != type.returnType) {
                    JasFunctionType(newParams, newReturn)
                } else {
                    type
                }
            }
            is JasArrayType -> {
                val newInner = substitute(type.inner, mapping)
                if (newInner != type.inner) JasArrayType(newInner) else type
            }
            is JasNullableType -> {
                val newInner = substitute(type.inner, mapping)
                if (newInner != type.inner) JasNullableType(newInner) else type
            }
            is JasNonNullType -> {
                val newInner = substitute(type.inner, mapping)
                if (newInner != type.inner) JasNonNullType(newInner) else type
            }
            is JasTupleType -> {
                val newTypes = type.types.map { substitute(it, mapping) }
                if (newTypes != type.types) JasTupleType(newTypes) else type
            }
            // 原始/其它类型不含类型参数，原样返回。
            else -> type
        }
    }

    private fun typeDisplayName(type: JasType): String = when (type) {
        // 仅用于诊断消息的可读类型名；未知类型显示为 "unknown"。
        is JasPrimitiveType -> type.name.lowercase()
        is JasNamedType -> type.name
        is JasStringType -> "string"
        is JasUnitType -> "void"
        is JasAnyType -> "any"
        is JasArrayType -> "${typeDisplayName(type.inner)}[]"
        is JasNullableType -> "${typeDisplayName(type.inner)}?"
        is JasNonNullType -> "${typeDisplayName(type.inner)}!"
        is JasFunctionType -> "(...) -> ${typeDisplayName(type.returnType)}"
        is JasTupleType -> "(${type.types.joinToString(", ") { typeDisplayName(it) }})"
        is JasWildcardType -> if (type.bound != null) "? ${if (type.extends) "extends" else "super"} ${typeDisplayName(type.bound)}" else "?"
        is JasPointerType -> "*${typeDisplayName(type.inner)}"
        is JasReferenceType -> "&${typeDisplayName(type.inner)}"
        else -> "unknown"
    }

    private fun typesCompatible(actual: JasType, expected: JasType): Boolean {
        // 兼容性规则按顺序短路；顺序本身有意义（可空性先于 any 兜底等），勿随意调整。
        if (actual == expected) return true
        // 规范化后同名视为兼容（如 int32/Int、string/String、map 别名）。
        if (canonicalTypeName(actual) == canonicalTypeName(expected)) return true
        // Nullability: assigning nullable to non-null is rejected
        // 可空性：可空值不能赋给非可空目标（此处直接拒绝，先于 any 兜底）。
        if (actual is JasNullableType && expected !is JasNullableType) {
            return false
        }
        // any 双向兼容：任一侧为 any 都放行。
        if (expected is JasAnyType || actual is JasAnyType) return true
        if (actual is JasNamedType && actual.name == "Any") return true
        if (expected is JasNamedType && expected.name == "Any") return true
        // Map 家族互相兼容（Map/HashMap/... 视为同一类型）。
        if (actual is JasNamedType && expected is JasNamedType && mapTypesCompatible(actual.name, expected.name)) return true
        // Interface subtype checking
        // 接口/父接口子类型：沿已收集的 implements/extends 关系链判断。
        if (actual is JasNamedType && expected is JasNamedType && isSubtype(actual.name, expected.name)) return true
        // Numeric widening conversions
        // 数值拓宽：int32 -> int64 -> float64 等按 numericWidening 表放行。
        if (isNumericWidening(actual, expected)) return true
        // 两侧都可空：比较内层类型。
        if (actual is JasNullableType && expected is JasNullableType) {
            return typesCompatible(actual.inner, expected.inner)
        }
        // 目标可空、实际非空：拿实际类型与目标内层比较（非空可赋给可空）。
        if (expected is JasNullableType) {
            return typesCompatible(actual, expected.inner)
        }
        // 数组逐元素比较（不校验长度维度，Jasper 无多维数组语法）。
        if (actual is JasArrayType && expected is JasArrayType) {
            return typesCompatible(actual.inner, expected.inner)
        }
        // Function-object protocols are "semantic": a lambda (function type) may
        // be assigned to a protocol-typed variable when its shape matches the
        // protocol's single abstract method (`Mapper.apply`, `Predicate.test`,
        // `Reducer.reduce`, `Comparator.compare`, `Action.accept`).
        // 中文说明：函数对象协议是「语义」协议（JVM 上无接口分派）。lambda（函数类型）
        // 只要形状匹配协议的单一抽象方法，就可赋给协议类型变量。
        if (actual is JasFunctionType && expected is JasNamedType && isSamCompatible(actual, expected)) {
            return true
        }
        return false
    }

    /**
     * True when a lambda shape `actual` is a valid implementation of the
     * function-object protocol `expected` (e.g. `Predicate<int32>`). The
     * protocol's abstract method signature is looked up from the symbol table and
     * its type parameters are substituted with the receiver's type arguments.
     *
     * 中文说明：判断 lambda 形状是否可实现函数对象协议。协议的唯一抽象方法签名从
     * SymbolTable 的成员索引取得，并把协议本身（如 Predicate<int32>）的类型实参代入
     * 其类型参数，再逐参、逐返回值比较兼容性。
     */
    private fun isSamCompatible(actual: JasFunctionType, expected: JasNamedType): Boolean {
        val methodName = PROTOCOL_SAM[expected.name] ?: return false
        val methodType = findMemberType(expected.name, methodName, expected.typeArguments) as? JasFunctionType
            ?: return false
        if (methodType.paramTypes.size != actual.paramTypes.size) return false
        for (i in actual.paramTypes.indices) {
            if (!typesCompatible(actual.paramTypes[i], methodType.paramTypes[i])) return false
        }
        return typesCompatible(actual.returnType, methodType.returnType)
    }

    private fun isSubtype(subName: String, superName: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
        // visited 防止接口层次成环（或重复访问）导致无限递归。
        if (subName == superName) return true
        if (!visited.add(subName)) return false
        // Check direct class implements interfaces
        val ifaces = classImplements[subName]
        if (ifaces != null) {
            if (superName in ifaces) return true
            // Transitively check each implemented interface's parent hierarchy
            // 递归检查每个已实现接口的父接口层次。
            for (iface in ifaces) {
                if (isSubtype(iface, superName, visited)) return true
            }
        }
        // Check interface extends hierarchy
        val parents = interfaceExtends[subName]
        if (parents != null) {
            for (parent in parents) {
                if (isSubtype(parent, superName, visited)) return true
            }
        }
        return false
    }

    private val numericWidening = mapOf(
        // 数值类型的合法拓宽目标集合；不在表中的类型（如 int32->bool）不视为拓宽。
        "int32" to listOf("int64", "float64"),
        "int64" to listOf("float64"),
        "float32" to listOf("float64"),
        "byte" to listOf("short", "int32", "int64", "float32", "float64"),
        "short" to listOf("int32", "int64", "float32", "float64"),
        "char" to listOf("int32", "int64", "float32", "float64")
    )

    private fun isNumericWidening(actual: JasType, expected: JasType): Boolean {
        // 先规范化名字（两边都可能来自不同写法），再查拓宽表。
        val actualName = canonicalTypeName(actual) ?: return false
        val expectedName = canonicalTypeName(expected) ?: return false
        val targets = numericWidening[actualName] ?: return false
        return expectedName in targets
    }

    private fun canonicalTypeName(type: JasType): String? {
        // 把各种等价的类型写法归一到规范名，供兼容性比较与拓宽表查询使用；
        // 无法归一的类型返回 null（视为不可比较，走后续规则）。
        return when (type) {
            is JasPrimitiveType -> when (type.name.lowercase()) {
                "bool", "boolean" -> "bool"
                "int", "int32" -> "int32"
                else -> type.name.lowercase()
            }
            is JasNamedType -> when (type.name) {
                "Bool", "Boolean", "boolean", "bool" -> "bool"
                "String", "string", "java.lang.String" -> "string"
                "Int", "int", "int32" -> "int32"
                "Map", "HashMap", "java.util.Map", "java.util.HashMap" -> "map"
                else -> type.name
            }
            is JasStringType -> "string"
            is JasUnitType -> "void"
            else -> null
        }
    }

    private fun mapTypesCompatible(actual: String, expected: String): Boolean {
        // Map 家族的所有写法视为互相兼容。
        val mapNames = setOf("Map", "HashMap", "java.util.Map", "java.util.HashMap")
        return actual in mapNames && expected in mapNames
    }
}
