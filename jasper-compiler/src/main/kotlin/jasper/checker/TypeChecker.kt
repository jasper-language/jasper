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
    // className -> direct superclassName（简单名）。null 表示无显式父类（等价 Object）。
    // 这是「类继承参与子类型判定」的唯一事实来源：isSubtype 沿它向上走传递闭包。
    // 为什么单列一张表：classImplements 只记接口方向，类继承方向此前完全缺失，
    //   正是「子类不能赋给基类变量」的根因（R2DBC #9 / JPA #4）。接口与类关系保持独立，
    //   避免把两种继承语义混在一张表里（隔离的变化点：继承关系来源）。
    private val classExtends = mutableMapOf<String, String?>()

    /**
     * 当前被检查文件声明的包名（null 表示无 package 声明）。
     *
     * 做什么：判断「某类型简单名是否与本文件同包因而可直接引用」时的基准包。
     * 什么时候用：每次 [check] 开始时由传入的 sourceFile 设定。
     * 失败模式：无；仅参与可见性判定。
     */
    private var currentPackageName: String? = null

    /**
     * 当前被检查文件的**文件级 import 表**：`简单名/别名 -> 被导入的全限定名（点分）`。
     *
     * 为什么用文件级而不是 SymbolTable.imports：后者是多文件合并后的全局映射，会用
     *   某个文件的 import 掩盖另一文件缺失 import 的错误；显式类型可见性必须按文件判定。
     * 什么时候用：每次 [check] 开始时由 sourceFile.imports 构建。
     * 失败模式：未命中返回 null，不抛异常。
     */
    private var fileImports: Map<String, String> = emptyMap()

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
        classExtends.clear()
        // 显式类型引用可见性判定所需的本文件上下文：包名 + 本文件 import 表。
        currentPackageName = sourceFile.packageName
        fileImports = collectFileImports(sourceFile)
        // 把本文件的包/import 装进符号表：多文件编译共享同一张表，findXxx 的简单名
        // 必须按「正在检查的文件」解析，否则 p1/p2 同名类型会串包。
        symbolTable?.beginFileScope(currentPackageName, fileImports)
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
        // 层次图完备后再做一次成环检测：语法允许 `class A extends B; class B extends A`，
        // 若不诊断，用户只会看到后续莫名的错误，甚至无错误。诊断独立成函数，避免污染收集逻辑。
        reportHierarchyCycles()
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

    /**
     * 记录一个类的 implements 列表与直接父类；已存在的条目不被覆盖（当前文件优先）。
     *
     * 键使用「包 + 简单名」的限定名：跨包的 `p1.Impl` / `p2.Impl` 必须各自维护自己的
     * 父类型关系，否则后遍历的包会覆盖前一个包，生成错误的接口表。
     * 接口名解析使用声明所在文件自己的 import 上下文（而不是当前检查文件的上下文），
     * 这样跨文件声明 `class Impl implements ImportedThing` 也能得到正确的限定名。
     */
    private fun collectClassHierarchy(cls: JasClass) {
        val self = qualifiedTypeName(cls)
        if (classImplements.containsKey(self)) return
        // 只认 JasNamedType 形式的接口名；其它类型（如泛型实参）这里不展开。
        val ifaces = cls.interfaces.mapNotNull { iface ->
            if (iface is JasNamedType) resolveDeclarationTypeName(cls, iface.name) else null
        }
        classImplements[self] = ifaces
        // 父类同样只认 JasNamedType；无显式父类时记 null（等价 java.lang.Object）。
        classExtends[self] = (cls.superclass as? JasNamedType)?.let { resolveDeclarationTypeName(cls, it.name) }
    }

    /**
     * 做什么：检测类继承链与接口继承图中是否存在环，并各报一条诊断；无环时不产生任何输出。
     *
     * 什么时候用：[check] 第一遍收集完 classExtends/interfaceExtends 之后、第二遍声明检查之前。
     * 前置条件：classExtends 与 interfaceExtends 已填充（当前文件 + 符号表）。
     * 失败模式：不抛异常；只在发现环时向 errors 追加 TypeError。
     * 为什么这样设计：循环继承本身是非法层次图，若不诊断，`isSubtype` 的 visited 保护虽能
     *   避免无限递归，但会静默给出错误答案；把「层次图是否有环」这一独立关注点收进单独函数，
     *   与子类型遍历解耦（隔离的变化点：图合法性 vs 图遍历）。
     * 已知限制：只沿用户已声明类型判定；跨到外部类型（如 java.lang.Object）的链在表外自然终止。
     */
    private fun reportHierarchyCycles() {
        // 类：每个类至多一个直接父类，沿单链走即可发现环。
        val reportedClasses = mutableSetOf<String>()
        for (start in classExtends.keys) {
            if (start in reportedClasses) continue
            val path = mutableListOf<String>()
            val onPath = mutableSetOf<String>()
            var current: String? = start
            while (current != null && current !in reportedClasses) {
                if (!onPath.add(current)) {
                    val cycle = path.subList(path.indexOf(current), path.size)
                    reportedClasses.addAll(cycle)
                    error("Cyclic class inheritance detected: ${(cycle + current).joinToString(" -> ")}")
                    break
                }
                path.add(current)
                current = classExtends[current]
            }
        }
        // 接口：一个接口可有多个父接口，用「从起点沿父边能否回到起点」的可达性检查判环。
        // 每个成环接口各报一条（同一环的多个成员会各报一次，属明确、可定位的诊断）。
        // 用显式队列而非递归：既不引入被风格护栏误判的局部函数，也避免深层次导致栈溢出。
        val reportedInterfaces = mutableSetOf<String>()
        for (start in interfaceExtends.keys) {
            if (start in reportedInterfaces) continue
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque(interfaceExtends[start] ?: emptyList())
            var cyclic = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == start) {
                    cyclic = true
                    break
                }
                if (!visited.add(node)) continue
                interfaceExtends[node]?.forEach { queue.add(it) }
            }
            if (cyclic) {
                reportedInterfaces.add(start)
                error("Cyclic interface inheritance detected involving '$start'")
            }
        }
    }

    /** 记录一个接口的 extends 列表；已存在的条目不被覆盖（当前文件优先）。 */
    private fun collectInterfaceHierarchy(iface: JasInterface) {
        val self = qualifiedTypeName(iface)
        if (interfaceExtends.containsKey(self)) return
        val parents = iface.extends.mapNotNull { ext ->
            if (ext is JasNamedType) resolveDeclarationTypeName(iface, ext.name) else null
        }
        interfaceExtends[self] = parents
    }

    /**
     * 把声明 [owner] 的简单名落成限定名（包 + 名）。
     *
     * 什么时候用：[collectClassHierarchy]/[collectInterfaceHierarchy] 为继承图建键时。
     * 前置条件：owner 已经过符号表采集；symbolTable 可为 null（结构检查模式）。
     * 失败模式：符号表缺失或声明未登记时退回「当前文件包 + 简单名」，保证行为可继续。
     * 为什么不能用 `cls.name`：跨包同名类型会让 p1/p2 的继承边互相覆盖。
     */
    private fun qualifiedTypeName(owner: JasDeclaration): String {
        val key = symbolTable?.typeKeyOfDeclaration(owner)
        if (key != null) return key.qualifiedName
        // 无符号表时仍能处理当前文件的四类顶层声明；其它声明不会进入继承图。
        val simpleName = when (owner) {
            is JasClass -> owner.name
            is JasInterface -> owner.name
            is JasEnum -> owner.name
            is JasAnnotationType -> owner.name
            else -> ""
        }
        return jasTypeKey(simpleName).qualifiedName
    }

    /**
     * 在声明 [owner] 自己的文件上下文中解析一个类型引用，返回点分限定名。
     *
     * 失败模式：无法解析时保留原写法（例如 JDK 外部类型 `java.lang.Throwable`），
     *   这样可以继续按原名字做宽泛比较，而不是伪造一个本程序的包名。
     */
    private fun resolveDeclarationTypeName(owner: JasDeclaration, name: String): String {
        return symbolTable?.resolveTypeKeyForDeclaration(owner, name)?.qualifiedName ?: name
    }

    /**
     * 用当前检查文件的包名构造一个兜底 [TypeKey]；无符号表时所有声明都在当前文件，
     * 因此这与符号表身份语义一致。
     */
    private fun jasTypeKey(simpleName: String): jasper.compiler.TypeKey =
        jasper.compiler.TypeKey(currentPackageName ?: "", simpleName)

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
                // 接口成员（抽象方法形参/返回、属性、常量）也是显式类型引用，逐一校验。
                decl.members.forEach { checkMemberTypeReferences(it, ifaceEnv) }
            }
            is JasEnum -> {
                // 枚举体内除常量外的成员同样可能带显式类型引用。
                decl.members.forEach { checkMemberTypeReferences(it, env) }
            }
            is JasProperty -> {}
            is JasImport -> {}
            is JasAnnotationType -> {
                // 注解成员类型也是显式类型引用（如 `string value();` 的 string 走单例，
                // 但 `Error cause();` 这类具名类型需要可见性校验）。
                decl.members.forEach { checkMemberTypeReferences(it, env) }
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

    /**
     * 校验「接口/枚举/注解」成员的显式类型引用。
     *
     * 做什么：对属性/注解成员校验其声明类型，对函数校验形参与返回类型（并登记其
     *   类型参数）；**不做**函数体语句检查——接口方法通常无体，且本函数只服务于
     *   「显式类型引用可见性」这一个目标，保持最小介入、避免牵连其它检查。
     * 什么时候用：[checkDeclaration] 处理 JasInterface / JasEnum / JasAnnotationType
     *   的成员时。
     * 前置条件：env 已包含外层类型（类/接口）的类型参数。
     * 失败模式：不抛异常；不可见的类型名会经 checkTypeReferences 追加诊断。
     * 为什么单独抽出：接口/枚举/注解的成员类型检查逻辑一致，抽到一处避免三份重复。
     */
    private fun checkMemberTypeReferences(member: JasDeclaration, env: TypeEnvironment) {
        when (member) {
            is JasProperty -> checkTypeReferences(member.type, env)
            is JasFunction -> {
                val memberEnv = env.copy()
                checkTypeParameters(member.typeParameters, memberEnv, "method '${member.name}'")
                member.parameters.forEach { checkTypeReferences(it.type, memberEnv) }
                checkTypeReferences(member.returnType, memberEnv)
            }
            is JasAnnotationMember -> checkTypeReferences(member.type, env)
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
            // 上界也是「显式类型引用」，同样需要名字可见性校验（在登记本类型参数之后，
            // 以便自引用/F-约束（如 T extends Comparable<T>）中的 T 已被识别）。
            checkTypeReferences(tp.bound, env)
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
            // 约束上界同样是显式类型引用，需做名字可见性校验。
            checkTypeReferences(wc.bound, env)
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
        // 只做「显式类型引用」层面的检查：名字可见性 + 泛型实参个数 + 嵌套合法性，
        // 不在此处做类型推断（推断由 inferExpression 负责）。
        if (type == null) return
        when (type) {
            is JasNamedType -> {
                // 关键：用户显式写出的具名类型必须可解析，否则报编译期错误，
                // 而不是静默兜底成 JasAnyType 并生成对不存在类的引用（FINDINGS #13）。
                checkNamedTypeVisibility(type, env)
                checkTypeArgumentCount(type.name, type.typeArguments, type)
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
            is JasPointerType -> checkTypeReferences(type.inner, env)
            is JasReferenceType -> checkTypeReferences(type.inner, env)
            else -> {}
        }
    }

    /**
     * 校验一个**显式写出的具名类型引用**是否可解析；不可解析则报错（带位置与名字）。
     *
     * 做什么：把类型参数、内建/互操作、本程序声明、显式 import、限定名五类来源合起来
     *   判断；命中任一即可，否则产生一条 `Unknown type '...'` 诊断。
     * 什么时候用：[checkTypeReferences] 的 JasNamedType 分支；所有显式类型位置
     *   （变量/字段/形参/返回/catch/泛型实参/cast/new/instanceof 等）都经由它。
     * 前置条件：env 携带当前函数/类的类型参数；symbolTable 可为 null（结构检查模式）。
     * 失败模式：不抛异常；仅在日志中追加一条诊断（同一名字多处出现会各报一次）。
     * 为什么：这是本任务的核心——把「未知类型静默通过」改成编译期硬错误，且**不**产出
     *   对不存在类的引用（错误发生后编译中断，翻译阶段不会执行）。
     */
    private fun checkNamedTypeVisibility(type: JasNamedType, env: TypeEnvironment) {
        if (isVisibleTypeName(type.name, env)) return
        error(unknownTypeMessage(type.name), type)
    }

    /**
     * 判断一个简单类型名在当前文件能否被直接引用（不做任何报错/副作用）。
     *
     * 可见性来源（任一命中即可见，顺序仅影响可读性）：
     *   1. 当前作用域的类型参数（函数/类/接口泛型形参）；
     *   2. 语言内建别名或保留的 JDK 互操作类型（见 [KnownTypeNames]）；
     *   3. 限定名（含 `.`，如 `java.lang.Exception`）：视为显式外部/JDK 引用；
     *   4. 本文件显式 import（简单名或别名）；
     *   5. 本程序声明的类型，且**与当前文件同包**（跨文件同包可见；别包必须 import；
     *      无 package 声明归一为空串，只能引用默认包内声明）。
     *
     * 前置条件：无（symbolTable 为 null 时退化为「结构检查」：声明来源无法判定，
     *   为避免误报，凡不属于前四类者一律放行）。
     * 失败模式：不抛异常，返回 false 表示不可见（由调用方报错）。
     * 为什么这样分：把「名字从哪来」拆成互相独立的来源，任一新来源可单独扩展；
     *   同时明确拒绝「未知就放行」的兜底，未知名字默认 false。
     */
    private fun isVisibleTypeName(name: String, env: TypeEnvironment): Boolean {
        if (env.hasTypeParameter(name)) return true
        if (KnownTypeNames.isBuiltinOrInterop(name)) return true
        if (name.contains('.')) return true
        if (fileImports.containsKey(name)) return true
        // 无符号表：结构检查模式，无法判定声明，放行以避免误报（symbolTable 可空是既有契约）。
        val st = symbolTable ?: return true
        val declared = st.findClass(name) != null ||
            st.findInterface(name) != null ||
            st.findEnum(name) != null ||
            st.findAnnotation(name) != null
        if (!declared) return false
        // 声明存在：要求与当前文件同包才可直接引用；无 package 声明归一为空串，
        // 因而只能引用同样位于「默认包」的声明。别包类型必须显式 import，否则即未知。
        val packages = st.typePackages[name] ?: return true
        return (currentPackageName ?: "") in packages
    }

    /**
     * 构造「未知类型」诊断消息，并在能找到同名别包类型时给出 import 提示。
     *
     * 做什么：例如 `Unknown type 'Error' (did you mean to import std.Error?)`；
     *   找不到候选时退化为 `Unknown type 'Error'`。
     * 什么时候用：仅由 [checkNamedTypeVisibility] 在判定不可见后调用。
     * 失败模式：无；纯字符串构造。
     * 为什么带 import 提示：用户最常见的成因正是「忘写 import」（FINDINGS #13），
     *   直接给出可用 import 目标能让错误可立即修复。
     */
    private fun unknownTypeMessage(name: String): String {
        val suggestion = suggestImport(name)
        return if (suggestion != null) {
            "Unknown type '$name' (did you mean to import $suggestion?)"
        } else {
            "Unknown type '$name'"
        }
    }

    /**
     * 为一个未知简单名寻找可建议的 import 目标（形如 `std.Error`）；找不到返回 null。
     *
     * 前置条件：名字不含 `.`（限定名不会被判未知）。
     * 失败模式：无符号表或无同名声明时返回 null。
     * 为什么：只有「别包确实声明了同名类型」时才提示 import，避免给出编造的目标。
     */
    private fun suggestImport(name: String): String? {
        if (name.contains('.')) return null
        val st = symbolTable ?: return null
        val packages = st.typePackages[name] ?: return null
        val pkg = packages.firstOrNull { it.isNotEmpty() } ?: return null
        return "$pkg.$name"
    }

    /**
     * 从源文件的 import 声明构建「简单名/别名 -> 全限定名」映射（点分）。
     *
     * 什么时候用：[check] 开始时为当前文件构建一次，供显式类型可见性判定使用。
     * 前置条件：sourceFile.imports 中的元素为 JasImport（其它节点被忽略）。
     * 失败模式：不抛异常；无 import 时返回空映射。
     * 为什么按文件构建：显式类型必须按**本文件**的 import 判定，不能被其它文件的
     *   import 掩盖（SymbolTable.imports 是跨文件合并的全局表）。
     */
    private fun collectFileImports(sourceFile: JasSourceFile): Map<String, String> {
        // 复用符号表的同一份 import 解析规则（含 from-import、别名与非通配过滤），
        // 避免类型检查器与符号表对「简单名 -> 全限定名」得出不同结论。
        return jasper.compiler.buildImportMap(sourceFile.imports)
    }

    private fun checkFunction(func: JasFunction, env: TypeEnvironment) {
        // 函数体在「复制后的子环境」中检查：类型参数与形参注入其中，不影响外层。
        val funcEnv = env.copy()
        checkTypeParameters(func.typeParameters, funcEnv, "function '${func.name}'")
        checkWhereConstraints(func.whereConstraints, funcEnv, "function '${func.name}'")
        checkVarargsPosition(func.parameters, "function '${func.name}'")
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

    /** varargs 形参只允许出现在最后一个位置（否则调用点无法确定打包边界）。 */
    private fun checkVarargsPosition(parameters: List<JasParameter>, owner: String) {
        for ((i, param) in parameters.withIndex()) {
            if (param.vararg && i != parameters.lastIndex) {
                error("Varargs parameter '${param.name}' in $owner must be the last parameter", param)
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
                    // 方法用「名字/形参个数/是否 vararg」判重：同名不同元数、以及
                    // 定长 1 参 vs vararg 1 参都是合法重载，必须允许共存。
                    // 同元数不同参数类型暂不支持（本任务非目标），在声明处就明确拒绝，
                    // 而不是等到调用点静默选第一个。
                    val signature = "${member.name}/${member.parameters.size}/${member.parameters.lastOrNull()?.vararg == true}"
                    if (!memberNames.add(signature)) {
                        error(
                            "Duplicate method '${member.name}' with ${member.parameters.size} parameter(s) " +
                                "in class '${cls.name}' (same-arity overloads are not supported yet)",
                            member
                        )
                    }
                }
                else -> {}
            }
            checkDeclaration(member, clsEnv)
        }
        for (ctor in cls.constructors) {
            // 构造器各自独立子环境；返回类型固定为 void，故传 JasUnitType。
            val ctorEnv = clsEnv.copy()
            checkVarargsPosition(ctor.parameters, "constructor of class '${cls.name}'")
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
        checkOverrideReturnTypes(cls)
        checkRequiredInterfaceMethods(cls)
    }

    /**
     * 做什么：校验具体类是否实现了其（直接或间接）接口声明的全部抽象方法。
     *
     * 什么时候用：[checkClass] 成员与构造器检查完成后；此时继承图已由 [check] 第一遍建立。
     * 前置条件：cls 是普通类；symbolTable 可空（为空时无法取得接口声明，直接跳过）。
     * 失败模式：不抛异常；每缺一个方法追加一条含类名、方法名与接口名的诊断。
     * 为什么需要：JVM 允许具体类不实现接口抽象方法，直到调用点才抛 AbstractMethodError；
     *   这会把「声明即错误」的契约问题推迟到运行期。语言层要求在编译期拒绝，否则
     *   `class P implements Hashable<P>` 缺 `hash()` 这类错误会被静默写进 class 文件。
     * 边界：只检查抽象方法（body == null）；接口默认方法（有 body）不强制实现。
     *   继承来的实现（父类方法）视为满足；方法按「名字 + 形参个数」匹配，与当前
     *   语言「同元数即同一签名」的成员重载限制一致。
     * 为什么放在 checker 而不是后端：这是语言级契约，不是某个后端的编码细节；
     *   放在这里可同时约束所有后端，并让诊断在生成字节码之前出现。
     */
    private fun checkRequiredInterfaceMethods(cls: JasClass) {
        // 抽象类本来就可以把实现留给子类；具体类才要求方法齐全。
        if (cls.modifiers.contains("abstract")) return
        val symbols = symbolTable ?: return

        // 已提供的方法签名集合：本类声明 + 父类继承。接口抽象方法体为 null。
        val provided = mutableSetOf<String>()
        val addMethods: (List<JasDeclaration>) -> Unit = { members ->
            for (member in members) {
                if (member is JasFunction) {
                    provided.add(methodImplementationKey(member.name, member.parameters.size))
                }
            }
        }
        addMethods(cls.members)
        // 继承图以「包 + 简单名」为键；先算一次祖先闭包，供实现继承与接口闭包共用。
        val selfName = qualifiedTypeName(cls)
        val ancestors = ancestorTypeNames(selfName)
        for (ancestor in ancestors) {
            symbols.findClass(ancestor)?.let { addMethods(it.members) }
        }

        // 直接实现的接口 + 经接口继承可达的父接口。ancestorTypeNames 已做传递闭包与去重。
        val interfaces = LinkedHashSet<String>()
        classImplements[selfName]?.forEach { interfaces.add(it) }
        interfaces.addAll(ancestors)
        for (interfaceName in interfaces) {
            val iface = symbols.findInterface(interfaceName) ?: continue
            for (member in iface.members) {
                if (member !is JasFunction || member.body != null) continue
                val key = methodImplementationKey(member.name, member.parameters.size)
                if (key !in provided) {
                    error(
                        "Class '${cls.name}' does not implement method '${member.name}' " +
                            "required by interface '${iface.name}'",
                        cls
                    )
                }
            }
        }
    }

    /**
     * 做什么：把「方法名 + 形参个数」编码成实现匹配用的稳定键。
     * 什么时候用：[checkRequiredInterfaceMethods] 对比接口契约与类实现。
     * 前置条件：name 非空；arity 为非负整数。
     * 失败模式：无（纯字符串拼接）。
     * 为什么不用 `JasFunctionType`：当前语言不支持同元数重载，名字+元数已足够区分，
     *   且不引入泛型实参替换的复杂度（接口方法存在性只关心签名骨架）。
     */
    private fun methodImplementationKey(name: String, arity: Int): String = "$name/$arity"

    /**
     * 做什么：校验类方法对祖先（父类/接口）同签名方法的返回类型覆盖是否协变兼容。
     *
     * 什么时候用：[checkClass] 成员与构造器都检查完之后。
     * 前置条件：层次图（classExtends/classImplements/interfaceExtends）已由 [check] 第一遍建立；
     *   cls 的返回类型若非 null，会被视为显式声明。
     * 失败模式：不抛异常；仅在不兼容时追加一条明确诊断。
     * 为什么需要：子类 override 返回类型不兼容（如父类 `string`、子类 `int32`）在 JVM 上会让
     *   经父类型分派的调用点拿到错误类型的栈值（VerifyError 或静默的语义错误）。这类问题
     *   在编译期完全可判定，必须给出「哪两个类型不兼容」的明确错误，而不是留到运行期。
     * 为什么只查返回类型、要求协变（child 可赋给 parent）而不查形参：v1 语言对成员重载支持
     *   有限，形参逆变/不变性检查超出本任务边界；返回类型协变是 `var b: Base = new Child();
     *   b.greet()` 语义正确性的最低保证。
     * 已知限制：同 arity 同名即视为「同一方法」，不做逐参类型匹配；返回类型为 null（省略）时跳过。
     */
    private fun checkOverrideReturnTypes(cls: JasClass) {
        for (member in cls.members) {
            if (member !is JasFunction) continue
            val childReturn = member.returnType ?: continue
            for (ancestor in ancestorTypeNames(cls.name)) {
                val ancestorType = directMemberType(ancestor, member.name) as? JasFunctionType ?: continue
                if (ancestorType.paramTypes.size != member.parameters.size) continue
                // 祖先返回类型若含该祖先自己的类型形参（如 `Repo<T>.get(): T` 或
                // `Repo<T>.find(): List<T>`），其实际类型由子类 implements 的接口实参决定，
                // 静态不宜直接比较，跳过（泛型继承链协变属本任务非目标）。
                val ancestorTypeParams = (symbolTable?.findClass(ancestor)?.typeParameters?.map { it.name }
                    ?: symbolTable?.findInterface(ancestor)?.typeParameters?.map { it.name }
                    ?: emptyList()).toSet()
                if (typeContainsTypeParameter(ancestorType.returnType, ancestorTypeParams)) continue
                if (!typesCompatible(childReturn, ancestorType.returnType)) {
                    error(
                        "Incompatible override: method '${member.name}' in class '${cls.name}' returns " +
                            "${typeDisplayName(childReturn)}, which is not compatible with " +
                            "${typeDisplayName(ancestorType.returnType)} from '${ancestor}'",
                        member
                    )
                    break
                }
            }
        }
    }

    /**
     * 做什么：递归判断一个类型里是否引用到给定集合中的任一类型形参名。
     *
     * 什么时候用：[checkOverrideReturnTypes] 判定祖先方法返回类型是否含未定类型变量。
     * 前置条件：names 是祖先类型声明的类型形参名集合（可为空）。
     * 失败模式：不抛异常；type 为 null 或 names 为空时返回 false。
     * 为什么需要：含类型形参的祖先返回类型（`T`、`List<T>`）其真实类型由子类的接口实参决定，
     *   静态不可直接比较，必须先排除，否则会对合法的泛型实现产生误报。
     */
    private fun typeContainsTypeParameter(type: JasType?, names: Set<String>): Boolean {
        if (type == null || names.isEmpty()) return false
        return when (type) {
            is JasNamedType ->
                type.name in names || type.typeArguments.any { typeContainsTypeParameter(it, names) }
            is JasArrayType -> typeContainsTypeParameter(type.inner, names)
            is JasNullableType -> typeContainsTypeParameter(type.inner, names)
            is JasNonNullType -> typeContainsTypeParameter(type.inner, names)
            is JasWildcardType -> typeContainsTypeParameter(type.bound, names)
            is JasTupleType -> type.types.any { typeContainsTypeParameter(it, names) }
            is JasFunctionType ->
                type.paramTypes.any { typeContainsTypeParameter(it, names) } ||
                    typeContainsTypeParameter(type.returnType, names)
            else -> false
        }
    }

    private fun checkLoopSuffix(suffix: LoopSuffix?, env: TypeEnvironment, returnType: JasType?) {
        if (suffix == null) return
        suffix.onComplete?.let { checkStatement(it, env, returnType) }
        suffix.onBreak?.let { checkStatement(it, env, returnType) }
        suffix.onZeroRuns?.let { checkStatement(it, env, returnType) }
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
                // 没有显式类型时必须有初始化器；禁止 `var x;` 依赖后续赋值来偷偷引入类型。
                if (stmt.type == null && stmt.initializer == null) {
                    error("Variable '${stmt.name}' requires an explicit type or an initializer", stmt)
                }
                // 变量声明的显式类型必须先校验可见性（未知类型不得静默通过）。
                checkTypeReferences(stmt.type, env)
                if (stmt.initializer != null) {
                    // 声明类型显式给出时，要求初始化表达式可赋给它。
                    // 把声明类型作为「期望类型」传入：数组字面量据此直接采用目标元素类型，
                    // 既避免公共父类型推断的歧义，也保证翻译器拿到正确的数组描述符。
                    val initType = inferExpression(stmt.initializer, env, stmt.type)
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
                    // 带值 return：值类型必须可赋给声明返回类型。返回类型作为期望类型传入，
                    // 使 `return [a, b]` 能按声明的数组类型生成正确元素描述符。
                    val valueType = inferExpression(stmt.value, env, returnType)
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
                checkLoopSuffix(stmt.suffix, env, returnType)
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
                if (stmt.suffix != null) {
                    error("onComplete/onBreak/onZeroRuns are only supported on for-in and while", stmt)
                }
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
                checkLoopSuffix(stmt.suffix, env, returnType)
            }
            is JasThrow -> inferExpression(stmt.expression, env)
            is JasTry -> {
                checkStatement(stmt.body, env, returnType)
                for (catchClause in stmt.catches) {
                    val catchEnv = env.copy()
                    // catch 形参类型是显式类型引用（FINDINGS #13 的原始触发点）。
                    checkTypeReferences(catchClause.parameter.type, catchEnv)
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
                    // 解构绑定的显式类型同样是类型引用，先校验可见性。
                    checkTypeReferences(binding.type, env)
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

    /**
     * 做什么：推断表达式的静态类型；[expected] 是调用方已知的「期望类型」，用于有上下文的推断。
     * 什么时候用：类型检查的表达式入口；变量声明/赋值/return 会传入目标类型，其余位置省略。
     * 前置条件：env 已包含当前作用域可见的局部/形参；expected 可为 null。
     * 失败模式：返回 null 表示无法推断（通常已伴随 warn）；不抛异常。
     * 为什么需要 expected：裸数组字面量 `[a, b]` 自身没有元素类型，仅靠元素求公共父类型
     *   在「多个互不相关的接口」或「用户想显式声明更宽类型」时会得到不精确甚至错误的结果。
     *   把上下文类型下传后，字面量可直接采用目标元素类型，翻译器也能生成正确描述符；
     *   这是「上下文相关推断」的最小落点，不改变其它表达式的既有行为（默认 null）。
     */
    private fun inferExpression(expr: JasExpression, env: TypeEnvironment, expected: JasType? = null): JasType? {
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
            is JasArrayLiteral -> inferArrayLiteral(expr, env, expected)
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
                    } else if (expr.name == "this") {
                        // `this` 是语言内建接收者，由类/方法上下文确定；它不是局部变量。
                        // 具体类型由 translator 的接收者路径处理，这里不把它误报成未声明名字。
                        null
                    } else {
                        // 名字未声明是静态语言错误；脚本模式同样不能用赋值隐式创建变量。
                        error("Unresolved reference: '${expr.name}'", expr)
                        null
                    }
                } else {
                    error("Unresolved reference: '${expr.name}' (no symbol table)", expr)
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
                // 类名静态方法调用（`A.f()` / 枚举合成方法 `Types.values()`）必须在「按函数值
                // 推断 target」之前分流：否则 `A.f` 会被当成对类名的属性访问，落入
                // 「类名不能访问实例成员」的错误路径，而正确的语义是静态调用（Q19 C22）。
                val staticTarget = expr.target
                if (staticTarget is JasPropertyAccess && isTypeNameReference(staticTarget.target, env)) {
                    return inferStaticMemberCall(expr, staticTarget, argTypes)
                }
                if (symbolTable != null && expr.target is JasIdentifier) {
                    val name = (expr.target as JasIdentifier).name
                    val funcs = symbolTable.findFunctions(name)
                    if (funcs.isEmpty()) {
                        // 符号表里没有同名函数：可能是未知/外部函数，宽松放行为 any（不报错）。
                        JasAnyType
                    } else {
                        // 重载解析第一步：按实参个数（arity）过滤候选。
                        // varargs 函数接受「固定形参个数」及以上的任意实参个数。
                        val byArity = funcs.filter { fn ->
                            fn.parameters.size == expr.args.size ||
                                (fn.parameters.lastOrNull()?.vararg == true && expr.args.size >= fn.parameters.size - 1)
                        }
                        if (byArity.isEmpty()) {
                            error("Function '$name' expects ${funcs.joinToString(" or ") { it.parameters.size.toString() }} argument(s), got ${expr.args.size}", expr)
                            funcs.first().returnType ?: JasAnyType
                        } else {
                            // 第二步：在 arity 匹配的候选中找第一个「实参类型全部兼容」的重载。
                            // 兼容判定额外放行「形参是裸类型参数」的情形（留待 unify 推断），
                            // 以及任一侧类型为 null（推断失败）时的宽松放行。
                            // 每个候选先决定 varargs 降级方式（无 varargs 时为 null），再逐参校验。
                            val resolved = byArity.asSequence().mapNotNull { fn ->
                                val vararg = resolveVarargCall(fn.parameters.map { it.type ?: JasAnyType }, fn.parameters.lastOrNull()?.vararg == true, argTypes)
                                val ok = if (vararg == null) {
                                    fn.parameters.size == argTypes.size &&
                                        fn.parameters.zip(argTypes).all { (param, argType) -> parameterAccepts(fn, param.type, argType) }
                                } else {
                                    val fixedOk = (0 until vararg.fixedCount).all { i -> parameterAccepts(fn, fn.parameters[i].type, argTypes[i]) }
                                    val tail = argTypes.drop(vararg.fixedCount)
                                    fixedOk && (vararg.passThrough || tail.all { parameterAccepts(fn, vararg.arrayType.inner, it) })
                                }
                                if (ok) fn to vararg else null
                            }.firstOrNull()
                            val compatible = resolved?.first
                            expr.varargCall = resolved?.second
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
                                    val vararg = expr.varargCall
                                    for ((i, argType) in argTypes.withIndex()) {
                                        // varargs 打包的尾部实参与「元素类型」统一，其余与对应形参统一。
                                        val paramType = when {
                                            vararg != null && !vararg.passThrough && i >= vararg.fixedCount -> vararg.arrayType.inner
                                            else -> compatible.parameters.getOrNull(i)?.type
                                        }
                                        if (paramType != null && argType != null) {
                                            unify(argType, paramType, typeParamNames, inferred)
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
                    // 实例成员调用：`obj.method(...)`。此前这里把 `obj.method` 当成一个
                    // 函数值（JasFunctionType），而该类型只由「首个同名声明」推导，导致
                    // 第二个重载永远不可达（Q28）。现在先按接收者声明类型收集同名候选，
                    // 统一做 arity/类型重载决议；返回 null 表示该名字不是函数成员
                    // （例如字段里存的是函数值），交回下面的函数值调用路径。
                    if (expr.target is JasPropertyAccess) {
                        val receiverType = inferExpression(expr.target.target, env)
                        if (receiverType is JasNamedType) {
                            inferInstanceMemberCall(expr, expr.target, receiverType, argTypes)?.let { return it }
                        }
                    }
                    // callee 不是简单标识符（如函数值/lambda 变量）：按函数类型逐参校验。
                    val targetType = inferExpression(expr.target, env)
                    val memberVararg = if (targetType is JasFunctionType) {
                        resolveVarargCall(targetType.paramTypes, targetType.varargs, argTypes)
                    } else null
                    if (targetType is JasFunctionType && memberVararg != null) {
                        // varargs 成员调用：固定形参逐个校验，尾部实参按元素类型（或数组直传）校验。
                        expr.varargCall = memberVararg
                        val fixed = memberVararg.fixedCount
                        targetType.paramTypes.take(fixed).zip(argTypes).forEachIndexed { index, (expected, actual) ->
                            if (actual != null && !typesCompatible(actual, expected)) {
                                error("Function argument ${index + 1} type mismatch: expected ${typeDisplayName(expected)}, got ${typeDisplayName(actual)}", expr)
                            }
                        }
                        if (!memberVararg.passThrough) {
                            val element = memberVararg.arrayType.inner
                            argTypes.drop(fixed).forEachIndexed { index, actual ->
                                if (actual != null && !typesCompatible(actual, element)) {
                                    error("Function argument ${fixed + index + 1} type mismatch: expected ${typeDisplayName(element)}, got ${typeDisplayName(actual)}", expr)
                                }
                            }
                        }
                        targetType.returnType
                    } else if (targetType is JasFunctionType) {
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
                // 成员解析顺序：类名静态成员 -> 具名类型查成员索引 -> 数组 length 特例
                //   -> 其余（未解析 target / 非具名类型）告警并兜底 any。
                // 为什么对「具名类型」的未命中改为 error：具名类型的成员集合是编译期可判定的，
                //   未命中就是用户笔误或缺少声明；若只 warn 并兜底 any，translator 会生成对
                //   不存在字段的访问（假符号 + Unit 描述符），最终产出非法字节码
                //   （ClassFormatError / NoSuchFieldError，Q19 C23）。any 接收者的成员集合
                //   不可知，仍按宽松处理（保持既有动态调用语义）。
                val targetType = inferExpression(expr.target, env)
                // 「类名.字段」与「值.字段」是两种不同语义：前者只能访问静态字段。
                // 先单独处理，否则会把类名当成一个值为 null 的变量，导致实例字段被静默放行，
                // 直到运行期才以 NPE 的形式暴露（JDBC #8 / R2DBC #18 / JPA #10）。
                if (isTypeNameReference(expr.target, env)) {
                    val typeName = (expr.target as JasIdentifier).name
                    // 枚举常量（`Color.Red`）的静态类型就是枚举类型本身。必须先于字段/成员
                    // 索引识别：常量不是 JasProperty，SymbolTable 不会为它建成员索引，否则
                    // 会被误报为「Cannot resolve member」并降级成 any（比较/赋值仍能过，
                    // 但失去了正确的类型信息）。
                    val enumDecl = symbolTable?.findEnum(typeName)
                    if (enumDecl != null && enumDecl.constants.any { it.name == expr.property }) {
                        return JasNamedType(typeName)
                    }
                    val property = findPropertyDeclaration(typeName, expr.property)
                    if (property != null && !isStaticProperty(typeName, property)) {
                        error("Cannot access instance field '${expr.property}' through class name '$typeName'", expr)
                        property.type ?: JasAnyType
                    } else {
                        val typeArguments = (targetType as? JasNamedType)?.typeArguments ?: emptyList()
                        findMemberType(typeName, expr.property, typeArguments) ?: run {
                            error("Cannot resolve member '${expr.property}' on type '$typeName'", expr)
                            JasAnyType
                        }
                    }
                } else when {
                    targetType is JasNamedType -> {
                        findMemberType(targetType.name, expr.property, targetType.typeArguments) ?: run {
                            // 只有「符号表中确有的具名类型」才把未命中升级为错误；类型参数
                            // （如 T）、外部 JDK 类型（如 java.util.HashMap）等的成员集合不可知，
                            // 维持 warn+any 以免误伤泛型与平台互操作。
                            if (isDeclaredTypeName(targetType.name)) {
                                error("Cannot resolve member '${expr.property}' on type '${targetType.name}'", expr)
                            } else {
                                warn("Cannot resolve member '${expr.property}' on type '${targetType.name}'", expr)
                            }
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
                // new 的类型即其表达式类型；显式类型引用需校验可见性与类型实参个数。
                checkTypeReferences(expr.type, env)
                expr.type
            }
            is JasArrayCreation -> {
                // 数组创建的元素类型是显式类型引用（如 `new Row[n]` 的 Row）。
                checkTypeReferences(expr.type, env)
                JasArrayType(expr.type)
            }
            is JasLambdaExpr -> {
                // lambda 在子环境中检查：形参注入其中；返回类型由 body 的首个 return 推断。
                val lambdaEnv = env.copy()
                for (param in expr.parameters) {
                    checkTypeReferences(param.type, lambdaEnv)
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
                // 强制转型的目标类型是显式类型引用；校验可见性与类型实参个数。
                checkTypeReferences(expr.type, env)
                expr.type
            }
            is JasInstanceOfExpr -> {
                // instanceof 的目标类型也是显式类型引用。
                checkTypeReferences(expr.type, env)
                JasPrimitiveType("bool")
            }
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
                // 先推断目标再带期望类型推断右值：数组字面量赋值 `a = [x, y]` 需要知道
                // a 的数组元素类型，才能逐元素校验并让翻译器生成正确的 aastore 描述符。
                val targetType = inferExpression(expr.target, env)
                val valueType = inferExpression(expr.value, env, targetType)
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

    /**
     * 推断「类名.静态方法(...)」调用的类型，并给出可操作的诊断。
     *
     * 做什么：对形如 `A.f(...)` 的调用返回方法声明类型；同时识别枚举类的合成静态方法
     *   `values()`（返回 `T[]`）与 `valueOf(string)`（返回 `T`）。若既不是静态方法也不是
     *   合成方法，则报错（区分「实例方法被类名调用」与「完全没有该成员」两种情形）。
     * 什么时候用：[inferExpression] 的 `JasCall` 分支，且 target 是「类名」引用时。
     * 前置条件：target.target 已被 [isTypeNameReference] 认定为类型名；argTypes 已推断。
     * 失败模式：不抛异常；无法解析时返回 [JasAnyType] 并记录一条错误诊断。
     * 为什么这样设计：静态调用与实例调用在 JVM 上走不同指令（INVOKESTATIC vs
     *   INVOKEVIRTUAL），checker 必须先认出来，translator 才能据此不生成接收者实参。
     *   把「枚举合成方法」与「用户静态方法」收敛在同一入口，调用点无需知道二者差异
     *   （隔离的变化点：方法来源）。
     */
    private fun inferStaticMemberCall(
        expr: JasCall,
        target: JasPropertyAccess,
        argTypes: List<JasType?>
    ): JasType? {
        val typeName = (target.target as JasIdentifier).name
        val memberName = target.property
        // 枚举的 values()/valueOf(String) 由后端为每个枚举类合成，源码层按静态方法使用。
        val enumDecl = symbolTable?.findEnum(typeName)
        if (enumDecl != null) {
            when (memberName) {
                "values" -> {
                    if (expr.args.isNotEmpty()) {
                        error("Function 'values' expects 0 argument(s), got ${expr.args.size}", expr)
                    }
                    return JasArrayType(JasNamedType(typeName))
                }
                "valueOf" -> {
                    if (expr.args.size != 1) {
                        error("Function 'valueOf' expects 1 argument(s), got ${expr.args.size}", expr)
                    }
                    val arg = argTypes.firstOrNull()
                    if (arg != null && !typesCompatible(arg, JasStringType)) {
                        error("Function 'valueOf' argument 1 type mismatch: expected string, got ${typeDisplayName(arg)}", expr)
                    }
                    return JasNamedType(typeName)
                }
            }
        }
        val fn = findFunctionMember(typeName, memberName)
        if (fn != null && fn.modifiers.any { it is String && it == "static" }) {
            val required = fn.parameters.count { it.defaultValue == null && !it.vararg }
            val hasVararg = fn.parameters.lastOrNull()?.vararg == true
            val arityOk = expr.args.size >= required &&
                (hasVararg || expr.args.size <= fn.parameters.size)
            if (!arityOk) {
                error("Function '$memberName' expects ${fn.parameters.size} argument(s), got ${expr.args.size}", expr)
            }
            return fn.returnType ?: JasUnitType
        }
        // 不是静态方法：给出比「未解析」更精确的诊断，帮助用户区分「忘写 static」与「名字写错」。
        if (fn != null) {
            error("Cannot call instance method '$memberName' through class name '$typeName'", expr)
        } else {
            error("Cannot resolve static method '$memberName' on type '$typeName'", expr)
        }
        return JasAnyType
    }

    /**
     * 在类型（类/接口/枚举）的成员中查找名为 [memberName] 的函数声明。
     *
     * 做什么：先查符号表成员索引（含注解合成成员），未命中再线性遍历类/接口/枚举成员。
     * 什么时候用：[inferStaticMemberCall] 判断某名字是否为函数成员。
     * 前置条件：typeName 是已声明类型的简单名。
     * 失败模式：未命中返回 null（不抛异常）；不区分重载，返回首次声明的那个。
     * 为什么这样设计：与 [rawMemberType] 保持同一查找顺序，避免「类型能解析、成员却查不到」
     *   导致的静态性误判。
     */
    private fun findFunctionMember(typeName: String, memberName: String): JasFunction? {
        (symbolTable?.findMemberSignature(typeName, memberName) as? JasFunction)?.let { return it }
        symbolTable?.findClass(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) return member
        }
        symbolTable?.findInterface(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) return member
        }
        symbolTable?.findEnum(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) return member
        }
        return null
    }

    /**
     * 一次成员重载决议的结果。
     *
     * 为什么用密封类型而不是「返回 JasFunction? + 错误码」：三种结果携带的信息不同
     *   （选中项需要 vararg 降级方案；元数不匹配需要可用元数列表；歧义需要候选列表），
     *   用密封类型让调用方必须显式处理每一种情况，避免再次退化成「静默取第一个」。
     */
    private sealed interface MemberOverloadResolution {
        /** 恰好选中一个候选；[parameterTypes] 已代入接收者泛型实参；[vararg] 为打包/直传方案。 */
        data class Selected(
            val function: JasFunction,
            val parameterTypes: List<JasType>,
            val vararg: JasVarargCall?
        ) : MemberOverloadResolution

        /** 没有任何候选接受该实参个数；[availableArities] 是诊断用的去重元数列表。 */
        data class ArityMismatch(val availableArities: List<Int>) : MemberOverloadResolution

        /** 多个候选同时接受该调用，且无法在本阶段安全裁决。 */
        data class Ambiguous(val candidates: List<JasFunction>) : MemberOverloadResolution
    }

    /** 实参类型与单个候选形参的匹配结果；vararg 为 null 表示按普通定长参数调用。 */
    private data class MemberArgumentsMatch(val vararg: JasVarargCall?)

    /**
     * 推断一次实例成员调用的返回类型，并完成按元数/类型的选择。
     *
     * 做什么：以接收者的声明类型为起点，收集自身与继承链上的同名实例方法，按
     *   「元数可接受 -> 精确元数优先 -> 实参类型兼容」的顺序裁决，把选中的 vararg
     *   降级方案写回 AST，并返回该方法的声明返回类型。
     * 什么时候用：[inferExpression] 的 `JasCall` 分支，且 callee 是 `obj.method` 形式。
     * 前置条件：receiverType 是已声明的具名类型；argTypes 已推断（可能含 null）。
     * 失败模式：候选为空时返回 null（交回函数值调用路径）；元数不匹配/歧义时记录
     *   明确诊断并返回一个兜底类型，不抛异常。
     * 为什么这样设计：旧路径只把 `obj.method` 当成由「首个同名声明」生成的函数值，
     *   第二个重载永远不可达。把「候选收集 + 决议 + 诊断」收敛到这一个入口，调用点与
     *   translator/backend 都只需根据「接收者类型 + 方法名 + 实参个数」得到同一重载。
     */
    private fun inferInstanceMemberCall(
        call: JasCall,
        target: JasPropertyAccess,
        receiverType: JasNamedType,
        argTypes: List<JasType?>
    ): JasType? {
        val candidates = findInstanceFunctionMembers(receiverType.name, target.property)
        if (candidates.isEmpty()) return null
        return when (val resolution = resolveMemberOverload(candidates, argTypes, receiverType)) {
            is MemberOverloadResolution.ArityMismatch -> {
                val available = resolution.availableArities.joinToString(" or ")
                error(
                    "Function '${target.property}' expects $available argument(s), got ${call.args.size} " +
                        "(available overloads: ${resolution.availableArities.joinToString(", ")})",
                    call
                )
                candidates.first().returnType ?: JasUnitType
            }
            is MemberOverloadResolution.Ambiguous -> {
                // 同元数不同类型在 v1 尚未实现完整的特异性排序：宁可明确报歧义，也不能
                // 静默选中第一个（那正是本任务要消灭的旧行为）。
                error(
                    "Ambiguous member overload '${target.property}': ${resolution.candidates.size} candidates " +
                        "accept ${call.args.size} argument(s) with compatible types; " +
                        "same-arity overload resolution is not supported yet",
                    call
                )
                candidates.first().returnType ?: JasUnitType
            }
            is MemberOverloadResolution.Selected -> {
                call.varargCall = resolution.vararg
                validateMemberArguments(call, target.property, resolution.function, resolution.parameterTypes, argTypes)
                memberReturnType(resolution.function, receiverType)
            }
        }
    }

    /**
     * 收集接收者类型及其祖先类型上的同名**实例**方法。
     *
     * 做什么：先收本类型（含同元数重载），再按 BFS 祖先顺序补充「元数尚未出现」的继承
     *   方法；子类同元数声明会遮蔽祖先同名方法（override 语义）。
     * 什么时候用：[inferInstanceMemberCall] 取得候选列表。
     * 前置条件：层次图已由 [check] 第一遍建立；[directMemberFunctions] 能按限定名解析。
     * 失败模式：未命中返回空列表；环状继承由 [ancestorTypeNames] 的 visited 保护。
     * 为什么按元数去重：本任务只支持「不同元数」的重载；同元数由子类覆盖祖先时不应
     *   人为制造歧义。同一声明类型内部的同元数重载仍全部保留，交由决议层报歧义。
     */
    private fun findInstanceFunctionMembers(typeName: String, memberName: String): List<JasFunction> {
        val result = mutableListOf<JasFunction>()
        val seenArities = mutableSetOf<Int>()
        for (fn in directMemberFunctions(typeName, memberName)) {
            if (isStaticFunction(fn)) continue
            result.add(fn)
            seenArities.add(fn.parameters.size)
        }
        for (ancestor in ancestorTypeNames(typeName)) {
            val fresh = mutableListOf<JasFunction>()
            val freshArities = mutableSetOf<Int>()
            for (fn in directMemberFunctions(ancestor, memberName)) {
                if (isStaticFunction(fn)) continue
                // 同一祖先内部的同元数候选全部保留（可能是歧义）；只过滤已被更近类型
                // 覆盖的元数。
                if (fn.parameters.size !in seenArities && freshArities.add(fn.parameters.size)) {
                    fresh.add(fn)
                }
            }
            result.addAll(fresh)
            seenArities.addAll(freshArities)
        }
        return result
    }

    /**
     * 查询单个类型**直接声明**的同名函数；索引未命中时线性遍历 AST 兜底。
     *
     * 什么时候用：[findInstanceFunctionMembers] 每处理一个类型时。
     * 前置条件：symbolTable 已完成 collectDeclarations（注解合成成员已进索引）。
     * 失败模式：未命中返回空列表；不展开继承链。
     */
    private fun directMemberFunctions(typeName: String, memberName: String): List<JasFunction> {
        val indexed = symbolTable?.findMemberFunctions(typeName, memberName)
        if (!indexed.isNullOrEmpty()) return indexed
        val result = mutableListOf<JasFunction>()
        symbolTable?.findClass(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) result.add(member)
        }
        symbolTable?.findInterface(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) result.add(member)
        }
        symbolTable?.findEnum(typeName)?.members?.forEach { member ->
            if (member is JasFunction && member.name == memberName) result.add(member)
        }
        return result
    }

    /** 判断成员函数是否声明为 static；实例调用路径据此排除静态方法。 */
    private fun isStaticFunction(fn: JasFunction): Boolean =
        fn.modifiers.any { it is String && it == "static" }

    /**
     * 在候选列表中裁决一次调用。
     *
     * 规则（顺序不可随意调换，否则精确重载会被 default/vararg 抢走）：
     *   1. 按元数过滤：required = 无默认值且非 vararg 的形参个数；
     *      有 vararg 时可接受 >= required 的任意个数，否则只接受 [required, 形参总数]。
     *   2. 精确元数优先：形参个数恰等于实参个数且最后一个形参不是 vararg 的候选，
     *      优先于「靠默认值/vararg 扩展」的候选。
     *   3. 类型匹配：在优先池里找实参类型全部兼容的候选；恰好一个则选中。
     *   4. 同元数多个兼容 -> 歧义；唯一候选但类型不兼容 -> 仍选中它，让逐参校验给出
     *      具体的类型错误（保持旧诊断风格）。
     * 失败模式：不抛异常，以 [MemberOverloadResolution] 表达结果。
     */
    private fun resolveMemberOverload(
        candidates: List<JasFunction>,
        argTypes: List<JasType?>,
        receiverType: JasNamedType
    ): MemberOverloadResolution {
        val byArity = candidates.filter { acceptsArity(it, argTypes.size) }
        if (byArity.isEmpty()) {
            return MemberOverloadResolution.ArityMismatch(
                candidates.map { it.parameters.size }.distinct().sorted()
            )
        }
        val exactArity = byArity.filter { fn ->
            fn.parameters.size == argTypes.size && fn.parameters.lastOrNull()?.vararg != true
        }
        val pool = if (exactArity.isNotEmpty()) exactArity else byArity
        val typed = pool.mapNotNull { fn ->
            val parameterTypes = memberParameterTypes(fn, receiverType)
            matchMemberArguments(fn, parameterTypes, argTypes)?.let { match -> fn to match }
        }
        return when {
            typed.size == 1 -> {
                val fn = typed[0].first
                MemberOverloadResolution.Selected(
                    fn,
                    memberParameterTypes(fn, receiverType),
                    typed[0].second.vararg
                )
            }
            typed.size > 1 -> MemberOverloadResolution.Ambiguous(typed.map { it.first })
            pool.size == 1 -> {
                // 唯一候选但类型不兼容：仍选中它，逐参校验会报出 expected/got。
                val fn = pool[0]
                val parameterTypes = memberParameterTypes(fn, receiverType)
                val vararg = if (fn.parameters.lastOrNull()?.vararg == true) {
                    resolveVarargCall(parameterTypes, true, argTypes)
                } else null
                MemberOverloadResolution.Selected(fn, parameterTypes, vararg)
            }
            else -> MemberOverloadResolution.Ambiguous(pool)
        }
    }

    /**
     * 判断一个候选能否接受 [argCount] 个实参。
     *
     * required 只统计「没有默认值且不是 vararg」的形参；vararg 形参本身不要求实参
     * （零个尾部实参是合法的）。
     */
    private fun acceptsArity(fn: JasFunction, argCount: Int): Boolean {
        val required = fn.parameters.count { it.defaultValue == null && !it.vararg }
        val hasVararg = fn.parameters.lastOrNull()?.vararg == true
        return argCount >= required && (hasVararg || argCount <= fn.parameters.size)
    }

    /**
     * 判断实参类型是否全部能传给候选；兼容时返回 vararg 降级方案。
     *
     * 失败模式：返回 null 表示不兼容（或 vararg 结构不成立）；不记录诊断。
     * 默认参数：只校验实际提供的实参，未提供且带默认值的尾部形参不参与比较。
     */
    private fun matchMemberArguments(
        fn: JasFunction,
        parameterTypes: List<JasType>,
        argTypes: List<JasType?>
    ): MemberArgumentsMatch? {
        val isVarargs = fn.parameters.lastOrNull()?.vararg == true
        val vararg = resolveVarargCall(parameterTypes, isVarargs, argTypes)
        if (isVarargs) {
            // 是 vararg 但实参个数不足以覆盖固定形参：resolveVarargCall 返回 null。
            if (vararg == null) return null
            val fixedOk = (0 until vararg.fixedCount).all { i ->
                parameterAccepts(fn, parameterTypes[i], argTypes[i])
            }
            if (!fixedOk) return null
            if (!vararg.passThrough) {
                val tailOk = argTypes.drop(vararg.fixedCount).all {
                    parameterAccepts(fn, vararg.arrayType.inner, it)
                }
                if (!tailOk) return null
            }
            return MemberArgumentsMatch(vararg)
        }
        if (argTypes.size > parameterTypes.size) return null
        val ok = argTypes.withIndex().all { (index, argType) ->
            parameterAccepts(fn, parameterTypes[index], argType)
        }
        return if (ok) MemberArgumentsMatch(null) else null
    }

    /**
     * 单个实参是否可传给形参：类型兼容、任一侧未知、或形参是待推断的裸类型参数。
     *
     * 为什么抽到类级：顶层函数重载与成员重载必须使用同一套兼容规则，否则两处诊断会
     *   随改动漂移（重复逻辑收口到正确层次）。
     */
    private fun parameterAccepts(fn: JasFunction, paramType: JasType?, argType: JasType?): Boolean =
        paramType == null || argType == null ||
            typesCompatible(argType, paramType) ||
            (paramType is JasNamedType && fn.typeParameters.any { tp ->
                tp.name == paramType.name && paramType.typeArguments.isEmpty()
            })

    /**
     * 对选中的成员重载逐参校验，产生与顶层函数一致的 `argument N type mismatch` 诊断。
     *
     * 什么时候用：重载已选中后；普通类型不匹配或 vararg 尾部元素不匹配时记录错误。
     * 前置条件：call.varargCall 已由决议结果写入（无 vararg 时为 null）。
     * 失败模式：不抛异常；只在诊断列表追加错误。
     */
    private fun validateMemberArguments(
        call: JasCall,
        memberName: String,
        fn: JasFunction,
        parameterTypes: List<JasType>,
        argTypes: List<JasType?>
    ) {
        val vararg = call.varargCall
        if (vararg != null) {
            for (i in 0 until vararg.fixedCount) {
                val actual = argTypes.getOrNull(i) ?: continue
                val expected = parameterTypes[i]
                if (!parameterAccepts(fn, expected, actual)) {
                    error(
                        "Function '$memberName' argument ${i + 1} type mismatch: " +
                            "expected ${typeDisplayName(expected)}, got ${typeDisplayName(actual)}",
                        call
                    )
                }
            }
            if (!vararg.passThrough) {
                for (i in vararg.fixedCount until argTypes.size) {
                    val actual = argTypes[i] ?: continue
                    if (!parameterAccepts(fn, vararg.arrayType.inner, actual)) {
                        error(
                            "Function '$memberName' argument ${i + 1} type mismatch: " +
                                "expected ${typeDisplayName(vararg.arrayType.inner)}, got ${typeDisplayName(actual)}",
                            call
                        )
                    }
                }
            }
            return
        }
        for (i in argTypes.indices) {
            if (i >= parameterTypes.size) break
            val actual = argTypes[i] ?: continue
            val expected = parameterTypes[i]
            if (!parameterAccepts(fn, expected, actual)) {
                error(
                    "Function '$memberName' argument ${i + 1} type mismatch: " +
                        "expected ${typeDisplayName(expected)}, got ${typeDisplayName(actual)}",
                    call
                )
            }
        }
    }

    /**
     * 取选中重载的形参类型，并把接收者泛型实参代入声明类型的类型参数。
     *
     * 什么时候用：[resolveMemberOverload] 匹配实参与 [validateMemberArguments] 报错时。
     * 前置条件：receiverType 是具名类型；fn 来自该接收者可见的成员。
     * 失败模式：找不到声明类型或接收者无实参时返回原始形参类型（缺失类型按 any）。
     * 为什么需要：`Repo<Person>.save(x: T)` 在调用点必须按 `Person` 校验，否则合法的
     *   具体实参会被误报为 `expected T, got Person`（Q28 回归防线）。
     */
    private fun memberParameterTypes(fn: JasFunction, receiverType: JasNamedType): List<JasType> {
        val declared = fn.parameters.map { it.type ?: JasAnyType }
        val mapping = receiverTypeArgumentMapping(receiverType)
        if (mapping.isEmpty()) return declared
        return declared.map { substitute(it, mapping) }
    }

    /**
     * 构造「接收者声明类型的类型参数 -> 接收者类型实参」的映射。
     *
     * 失败模式：类型未声明、无类型参数、接收者无实参时返回空映射（调用方原样返回）。
     * 为什么单列：形参校验与返回类型替换必须使用同一份映射，避免两处推导规则漂移。
     */
    private fun receiverTypeArgumentMapping(receiverType: JasNamedType): Map<String, JasType> {
        val declarationTypeParams = symbolTable?.findInterface(receiverType.name)?.typeParameters
            ?: symbolTable?.findClass(receiverType.name)?.typeParameters
            ?: return emptyMap()
        if (declarationTypeParams.isEmpty() || receiverType.typeArguments.isEmpty()) return emptyMap()
        return declarationTypeParams.map { it.name }.zip(receiverType.typeArguments).toMap()
    }

    /**
     * 取选中重载的返回类型，并按接收者的泛型实参替换声明类型的类型参数。
     *
     * 什么时候用：[inferInstanceMemberCall] 选中候选后。
     * 前置条件：receiverType 是具名类型；候选来自该接收者可见的成员。
     * 失败模式：找不到声明类型、类型参数为空或接收者没有实参时原样返回（退回擦除类型）。
     * 为什么只按接收者自身类型参数替换：与旧 [findMemberType] 的替换范围保持一致；
     *   跨继承链的类型实参推导不在本任务范围。
     */
    private fun memberReturnType(fn: JasFunction, receiverType: JasNamedType): JasType {
        val base = fn.returnType ?: JasUnitType
        val mapping = receiverTypeArgumentMapping(receiverType)
        if (mapping.isEmpty()) return base
        return substitute(base, mapping)
    }

    /**
     * 判断简单名 [name] 是否对应符号表中已声明的类型（类/接口/枚举/注解）。
     *
     * 做什么：作为「未解析成员是否升级为错误」的判据——只有已知成员集合的具名类型才升级。
     * 什么时候用：[inferExpression] 的 `JasPropertyAccess` 分支。
     * 前置条件：无（symbolTable 可空）。
     * 失败模式：symbolTable 为空时返回 false（退化为宽松警告）。
     */
    private fun isDeclaredTypeName(name: String): Boolean =
        symbolTable?.findClass(name) != null ||
            symbolTable?.findInterface(name) != null ||
            symbolTable?.findEnum(name) != null ||
            symbolTable?.findAnnotation(name) != null

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

    /**
     * 判断一个属性访问的接收者是否是「类型名」而不是「值」。
     *
     * 做什么：当接收者是标识符、未被局部变量/形参/类型参数遮蔽，且符号表中确有同名
     *   类/接口/枚举/注解声明时返回 true。
     * 什么时候用：[inferExpression] 的 `JasPropertyAccess` 分支区分静态访问与实例访问。
     * 前置条件：symbolTable 已执行 collectDeclarations；env 是当前作用域。
     * 失败模式：不抛异常；符号表为 null 或名字未声明时返回 false（按普通值处理）。
     * 为什么这样设计：名字解析优先级必须与 [inferExpression] 的 `JasIdentifier` 分支一致
     *   （局部值优先于类型名），否则局部变量恰好与类同名时会误判成静态访问。
     */
    private fun isTypeNameReference(target: JasExpression, env: TypeEnvironment): Boolean {
        if (target !is JasIdentifier) return false
        if (env.contains(target.name) || env.hasTypeParameter(target.name)) return false
        return symbolTable?.resolveType(target.name) != null
    }

    /**
     * 在类型 [typeName] 的成员中查找名为 [memberName] 的属性声明。
     *
     * 做什么：优先查符号表的成员索引（含注解合成成员），未命中再线性遍历类/接口成员。
     * 什么时候用：需要判断 `类名.成员` 是静态字段还是实例字段时。
     * 前置条件：typeName 是已声明类型的简单名。
     * 失败模式：未命中或该名字是函数等非属性成员时返回 null。
     * 为什么这样设计：与 [rawMemberType] 的查找顺序保持一致，避免「类型能解析、静态性
     *   却解析失败」造成误报；索引缺失时的线性兜底覆盖旧符号表未建索引的场景。
     */
    private fun findPropertyDeclaration(typeName: String, memberName: String): JasProperty? {
        (symbolTable?.findMemberSignature(typeName, memberName) as? JasProperty)?.let { return it }
        symbolTable?.findClass(typeName)?.members?.forEach { member ->
            if (member is JasProperty && member.name == memberName) return member
        }
        symbolTable?.findInterface(typeName)?.members?.forEach { member ->
            if (member is JasProperty && member.name == memberName) return member
        }
        return null
    }

    /**
     * 判断属性 [prop] 在类型 [typeName] 上是否按 JVM 静态字段处理。
     *
     * 做什么：`static` 修饰符直接判真；接口里的 `const` 字段按 JVM 规则恒为 static final，
     *   也判真（当前语言只允许接口声明 const 字段）。
     * 什么时候用：`类名.字段` 访问的静态性校验。
     * 前置条件：prop 已确认属于 typeName。
     * 失败模式：无（纯判定，不抛异常）。
     * 为什么这样设计：接口常量在 AST 上没有显式 `static` 修饰符，若只看修饰符会把它误判成
     *   实例字段并报错，从而破坏既有的 `Limits.MAX_ROWS` 访问。
     */
    private fun isStaticProperty(typeName: String, prop: JasProperty): Boolean {
        if (prop.modifiers.any { it is String && it == "static" }) return true
        return prop.isConst && symbolTable?.findInterface(typeName) != null
    }

    private fun rawMemberType(typeName: String, memberName: String): JasType? {
        // 先查本类型自身的成员；未命中再沿继承链（父类 + 接口，传递闭包）查找。
        // 为什么必须查继承链：把「具名类型未解析成员」升级为编译错误后，若只认本类型成员，
        //   合法的「子类实例调用父类/接口方法」会被误报；继承成员本就是可见的。
        directMemberType(typeName, memberName)?.let { return it }
        for (ancestor in ancestorTypeNames(typeName)) {
            directMemberType(ancestor, memberName)?.let { return it }
        }
        return null
    }

    // 单个类型自身的成员查找（不展开继承链）。
    // 优先走符号表成员索引（含注解合成成员），再退回线性遍历 AST 成员。
    private fun directMemberType(typeName: String, memberName: String): JasType? {
        when (val indexed = symbolTable?.findMemberSignature(typeName, memberName)) {
            is JasProperty -> return indexed.type
            is JasFunction -> return JasFunctionType(
                indexed.parameters.map { it.type ?: JasAnyType },
                indexed.returnType ?: JasUnitType,
                indexed.parameters.lastOrNull()?.vararg == true
            )
            else -> {}
        }

        // 索引未命中时的兜底：线性遍历类成员（与旧实现保持一致的首次匹配）。
        val cls = symbolTable?.findClass(typeName)
        if (cls != null) {
            for (member in cls.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType, member.parameters.lastOrNull()?.vararg == true)
                }
            }
        }

        val iface = symbolTable?.findInterface(typeName)
        if (iface != null) {
            for (member in iface.members) {
                if (member is JasProperty && member.name == memberName) return member.type
                if (member is JasFunction && member.name == memberName) {
                    return JasFunctionType(member.parameters.map { it.type ?: JasAnyType }, member.returnType ?: JasUnitType, member.parameters.lastOrNull()?.vararg == true)
                }
            }
        }

        return null
    }

    /**
     * 收集类型 [start] 的全部祖先类型名（父类 + 实现的接口 + 父接口，传递闭包）。
     *
     * 做什么：BFS 遍历继承链，返回简单名列表（不含 start 自身），已访问集合去重避免环。
     * 什么时候用：[rawMemberType] 展开继承成员查找。
     * 前置条件：symbolTable 已执行 collectDeclarations；classImplements/interfaceExtends 已在
     *   [check] 第一遍建立。
     * 失败模式：不抛异常；未声明的祖先（如 `java.lang.Throwable`）及其成员不进入结果，
     *   因此其成员无法解析——这是「只依赖已知声明」的已知边界。
     * 为什么这样设计：成员可见性与继承链是语言级规则，收敛到唯一函数可避免各调用点各自
     *   实现导致行为漂移（隔离的变化点：继承关系来源）。
     */
    private fun ancestorTypeNames(start: String): List<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        val startName = canonicalTypeName(start)
        // seen 初始为空，起点由 enqueue 首次入队；result 用 `name != startName` 把起点自身排除。
        // 注意：不能预置 seen=start，否则 enqueue(start) 会被判重而丢弃，BFS 无法启动。
        // 用局部 lambda（而非局部 fun）：既保持单一定义，又不让代码风格护栏把局部函数
        // 误判成「缺少中文 KDoc 的公共 API」。
        val seen = mutableSetOf<String>()
        val enqueue: (String?) -> Unit = { name ->
            if (name != null) {
                val canonical = canonicalTypeName(name)
                if (seen.add(canonical)) queue.add(canonical)
            }
        }
        enqueue(startName)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name != startName) result.add(name)
            // 继承图已在 [check] 第一遍把当前文件与符号表中的所有类型合并进来，
            // 因此这里只读图，不再按简单名回查符号表，避免跨包同名时选错声明。
            classExtends[name]?.let { enqueue(it) }
            classImplements[name]?.forEach { enqueue(it) }
            interfaceExtends[name]?.forEach { enqueue(it) }
        }
        return result.toList()
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
                    JasFunctionType(newParams, newReturn, type.varargs)
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

    /**
     * 做什么：判断一次调用该如何走 varargs（真 varargs，语法裁决②）。
     * 返回 null：被调函数没有 varargs 形参，或实参个数不足以覆盖固定形参。
     * passThrough=true：实参个数恰好对齐且最后一个实参本身是兼容的数组 ⇒ 原样传入；
     *   例外：元素为 any、实参是原始类型数组（如 int32[]）时不能当 Object[] 用，改为打包。
     * 否则：把固定形参之后的全部实参（可以是 0 个）打包成新数组。
     */
    private fun resolveVarargCall(paramTypes: List<JasType>, isVarargs: Boolean, argTypes: List<JasType?>): JasVarargCall? {
        if (!isVarargs) return null
        val arrayType = paramTypes.lastOrNull() as? JasArrayType ?: return null
        val fixed = paramTypes.size - 1
        if (argTypes.size < fixed) return null
        val last = if (argTypes.size == paramTypes.size) argTypes.last() else null
        val passThrough = last is JasArrayType &&
            typesCompatible(last, arrayType) &&
            !(isAnyLike(arrayType.inner) && last.inner is JasPrimitiveType)
        return JasVarargCall(fixed, arrayType, passThrough)
    }

    private fun isAnyLike(type: JasType): Boolean =
        type is JasAnyType || (type is JasNamedType && type.name == "Any")

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
     * 做什么：推断裸数组字面量 `[a, b, ...]` 的类型，并把最终元素类型回填到
     *   [JasArrayLiteral.inferredElementType] 供翻译器使用。
     * 什么时候用：[inferExpression] 命中 `JasArrayLiteral` 时。
     * 前置条件：env 已建立；values 中的表达式可被正常推断。
     * 失败模式：不抛异常。显式期望类型为数组时逐元素比对，类型不兼容会记录 error；
     *   无期望类型且元素无法收敛时兜底 `any[]`（不生成 V 描述符）。
     * 为什么优先用 expected：`Base[] = [new A(), new B()]`、`I[] = [...]` 的目标元素类型
     *   是调用方已经知道的精确类型；直接采用它比「猜公共父类型」更稳定，也能保证
     *   JVM 数组描述符与目标声明一致（C12）。只有无上下文时才回退到公共类型推断。
     */
    private fun inferArrayLiteral(expr: JasArrayLiteral, env: TypeEnvironment, expected: JasType?): JasType {
        val expectedArray = when (expected) {
            is JasArrayType -> expected
            is JasNullableType -> expected.inner as? JasArrayType
            else -> null
        }
        val valueTypes = expr.values.map { inferExpression(it, env) }
        if (expectedArray != null) {
            val element = expectedArray.inner
            // 逐元素校验：不能只比较「数组整体类型」，否则公共类型退化成 any 时会静默放行
            // 不兼容元素（例如 Base[] = [new A(), new Unrelated()]）。
            for ((i, valueType) in valueTypes.withIndex()) {
                if (valueType != null && !typesCompatible(valueType, element)) {
                    error(
                        "Array literal element ${i + 1} has type ${typeDisplayName(valueType)}, " +
                            "incompatible with array element type ${typeDisplayName(element)}",
                        expr.values[i]
                    )
                }
            }
            expr.inferredElementType = element
            return JasArrayType(element)
        }
        val element = commonElementType(valueTypes) ?: JasAnyType
        expr.inferredElementType = element
        return JasArrayType(element)
    }

    /**
     * 做什么：求一组元素类型的公共类型，供无上下文的数组字面量推断使用。
     * 什么时候用：[inferArrayLiteral] 没有显式期望元素类型时。
     * 前置条件：types 可含 null（无法推断的元素）。
     * 失败模式：无法收敛时返回 null（调用方兜底 any）；不抛异常。
     * 为什么这样设计：先处理「全同 / 数值拓宽 / 部分 any」，再沿已知类/接口继承图求
     *   祖先交集。把「公共类型」收敛到一个函数，避免 translator 另写一份几乎相同的规则
     *   （隔离的变化点：元素类型收敛策略）。
     */
    private fun commonElementType(types: List<JasType?>): JasType? {
        val present = types.filterNotNull()
        if (present.isEmpty()) return null
        val firstKey = canonicalTypeKey(present.first())
        if (firstKey != null && present.all { canonicalTypeKey(it) == firstKey }) return present.first()

        // any 元素不参与收敛：它们可赋给任何元素类型；只有「全是 any」时才落为 any。
        val concrete = present.filterNot { isAnyLike(it) }
        if (concrete.isEmpty()) return JasAnyType
        if (concrete.size != present.size) return commonElementType(concrete)

        // 数值类型：选一个所有元素都能拓宽到的目标（如 int32 + int64 -> int64）。
        if (concrete.all { isNumericType(it) }) {
            return concrete.firstOrNull { candidate ->
                concrete.all { typesCompatible(it, candidate) }
            }
        }

        // 具名类型的祖先交集：A/B 都继承 Base 或实现 I 时得到 Base/I。
        val ancestorSets = concrete.map { namedTypeAncestors(it) ?: return JasAnyType }
        val common = ancestorSets.reduce { acc, set -> acc.intersect(set) }
        if (common.isEmpty()) return JasAnyType
        // 选最具体（最靠下）的公共祖先：它仍是所有元素类型的父类型，但能保留更多类型信息。
        val mostSpecific = common.filter { candidate ->
            common.all { other -> other == candidate || isSubtype(candidate, other) }
        }
        return JasNamedType(mostSpecific.firstOrNull() ?: common.first())
    }

    /**
     * 做什么：把任意 Jasper 类型落成「可比较的规范键」。
     * 什么时候用：[commonElementType] 判断元素类型是否相同、[isNumericType] 查数值表时。
     * 为什么单独抽：`canonicalTypeName(JasType)` 对具名类型只返回源码原名，不做包限定；
     *   跨包同名类型会被误判为同一类型。具名类型统一走字符串重载，才能拿到 `pkg.A`。
     * 失败模式：无法规范化的类型返回 null；调用方按「不可比较」处理。
     */
    private fun canonicalTypeKey(type: JasType): String? =
        if (type is JasNamedType) canonicalTypeName(type.name) else canonicalTypeName(type)

    /** 判断类型是否为可参与数值拓宽的内建数值类型。 */
    private fun isNumericType(type: JasType): Boolean {
        val name = canonicalTypeKey(type) ?: return false
        return name in setOf("byte", "short", "char", "int32", "int64", "float32", "float64")
    }

    /**
     * 做什么：收集一个具名类型自身及其所有已知父类/接口（传递闭包）。
     * 什么时候用：[commonElementType] 求公共祖先交集时。
     * 前置条件：继承图（classExtends/classImplements/interfaceExtends）已完成第一遍收集。
     * 失败模式：非具名类型或名字无法规范化时返回 null；visited 集合防止继承环导致死循环。
     * 为什么返回规范化名：继承图以 TypeKey 规范名为键，用规范名求交才能跨包正确匹配。
     */
    private fun namedTypeAncestors(type: JasType): Set<String>? {
        if (type !is JasNamedType) return null
        // 用「字符串重载」把简单名落到包限定名；类型重载对 JasNamedType 只返回原名，
        // 直接拿它查 classExtends 会因键是 `pkg.A` 而全部落空，导致公共类型退化成 any。
        val start = canonicalTypeName(type.name)
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!result.add(current)) continue
            classExtends[current]?.let { queue.add(it) }
            classImplements[current]?.forEach { queue.add(it) }
            interfaceExtends[current]?.forEach { queue.add(it) }
        }
        return result
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
        // 先把两边都规范化成「包 + 名」的限定名：继承图按限定名建键，
        // 而调用方传进来的通常是源码里的简单名。
        val sub = canonicalTypeName(subName)
        val sup = canonicalTypeName(superName)
        // visited 防止继承层次成环（或重复访问）导致无限递归。
        if (sub == sup) return true
        if (!visited.add(sub)) return false
        // Check direct class inherits superclass
        // 类继承方向：Child extends Base => Child 是 Base 的子类型；
        // 沿直接父类递归即得传递闭包（A <- B <- C 时 C 亦是 A 的子类型）。
        val superclass = classExtends[sub]
        if (superclass != null) {
            if (superclass == sup) return true
            if (isSubtype(superclass, sup, visited)) return true
        }
        // Check direct class implements interfaces
        val ifaces = classImplements[sub]
        if (ifaces != null) {
            if (sup in ifaces) return true
            // Transitively check each implemented interface's parent hierarchy
            // 递归检查每个已实现接口的父接口层次。
            for (iface in ifaces) {
                if (isSubtype(iface, sup, visited)) return true
            }
        }
        // Check interface extends hierarchy
        val parents = interfaceExtends[sub]
        if (parents != null) {
            for (parent in parents) {
                if (isSubtype(parent, sup, visited)) return true
            }
        }
        return false
    }

    /**
     * 把源码类型名规范化为符号表的限定名；无法解析时保留原名字。
     *
     * 什么时候用：isSubtype / ancestorTypeNames 等继承图查询。
     * 前置条件：当前文件作用域已通过 SymbolTable.beginFileScope 设置。
     * 失败模式：外部/JDK 类型或未声明类型保持原样（仍然可做相等比较），不抛异常。
     */
    private fun canonicalTypeName(name: String): String {
        return symbolTable?.resolveTypeKey(name)?.qualifiedName ?: name
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
