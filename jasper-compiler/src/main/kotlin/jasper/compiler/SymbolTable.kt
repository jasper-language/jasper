// ============================================================================
// SymbolTable.kt —— Jasper 编译器「基础符号设施」
// ============================================================================
// 职责：
//   在整个编译流程中汇聚并查询顶层声明（函数/类/接口/枚举/注解）、包与 import
//   信息，并为每个类型维护「成员签名索引」，供类型检查阶段解析成员访问。
//
// 所属层次：
//   物理位置在 `jasper.compiler` 包（编排层），但语义上是位于 ast 与 checker 之间
//   的**基础符号设施**。checker、translator、compiler 都依赖它；没有它就无从解析
//   形如 `obj.field` / `obj.method()` 的成员访问。
//
// 【已知问题：包位置与语义层次不一致】
//   SymbolTable 被 checker 与 translator 依赖，却被放在 `jasper.compiler` 包。
//   若按包名把它归入编排层，会凭空制造 compiler<->checker、compiler<->translator
//   的依赖环。scripts/check-code-style.sh 已将 `jasper.compiler.SymbolTable` 显式
//   归入「symbol 层」以规避误报，但这**不掩盖**其物理位置不合理的事实。正确做法
//   是把它下沉为独立的基础层包（例如 `jasper.symbol`）；当前属于历史遗留。
//
// 对外契约：
//   - collectDeclarations 采集一个 JasSourceFile 的顶层声明；
//   - findXxx / resolveXxx / findMemberSignature 提供只读查询；
//   - merge 合并多个源文件的符号表；
//   - 采集/合并过程中发现重复声明时向 errors 追加 SymbolError，**不抛异常**。
//
// 扩展点与已知限制：
//   - memberSignatures 是「类型名 -> 成员名 -> 首个声明」的扁平索引，
//     不区分重载（同名成员只保留第一次声明），也不沿继承链查找；
//   - 不含作用域/词法层级信息，局部变量作用域由 TypeChecker.TypeEnvironment 维护；
//   - errors 只增不清，多文件复用时需由调用方决定何时归零。
// ============================================================================

package jasper.compiler

import jasper.ast.*
import java.util.IdentityHashMap

/**
 * 顶层类型的全局身份：**包名 + 简单名**。
 *
 * 做什么：作为 [SymbolTable] 中 classes/interfaces/enums/annotations 与成员签名索引的
 *   主键，使 `p1.Thing` 与 `p2.Thing` 成为两个互不冲突的类型。
 * 什么时候用：采集声明、按限定名查询、把声明类型解析成 JVM 内部名时。
 * 前置条件：simpleName 必须是最末一段名字（当前语言不支持嵌套类型）；packageName 用
 *   空串表示无 package 声明的默认包。
 * 失败模式：本身是纯数据，不抛异常；[fromQualifiedName] 对空串返回 null。
 * 为什么这样设计：类型身份是「包 + 名」而不是「名」，把这条规则收敛成值对象后，
 *   所有以类型为键的索引共享同一口径，避免各处各自拼字符串导致身份不一致。
 */
data class TypeKey(val packageName: String, val simpleName: String) {
    /**
     * 点分全限定名：无包时就是简单名。
     * 什么时候用：诊断、TypeChecker 的继承图表键、以及需要把类型身份落成人类可读名字时。
     */
    val qualifiedName: String
        get() = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"

    companion object {
        /**
         * 把点分限定名拆成 [TypeKey]；无点号时归入默认包。
         *
         * 失败模式：空名字返回 null（调用方应视为「无法解析」而不是默认包类型）。
         * 为什么不用 lastIndexOf('/')：源码层使用点分名；JVM 内部名在进入符号表前
         *   应已由 TypeMapper 处理，这里只负责语言层身份。
         */
        fun fromQualifiedName(name: String): TypeKey? {
            if (name.isEmpty()) return null
            val lastDot = name.lastIndexOf('.')
            return if (lastDot < 0) {
                TypeKey("", name)
            } else {
                TypeKey(name.substring(0, lastDot), name.substring(lastDot + 1))
            }
        }
    }
}

/**
 * 构造一个源文件的精确 import 表：`简单名/别名 -> 点分全限定名`。
 *
 * 做什么：同时支持 `import a.b.Thing;`、`import a.b.Thing as T;` 与
 *   `from a.b import Thing;` 三种精确导入形态；通配导入不在此展开（当前语言不支持
 *   安全展开，避免把未知名字猜成错误的全限定名）。
 * 什么时候用：SymbolTable 采集每个文件的解析上下文，以及 TypeChecker 判定显式类型
 *   可见性时。
 * 前置条件：imports 中非 [JasImport] 的节点会被忽略。
 * 失败模式：不抛异常；无法识别的形态跳过，未命中返回空映射。
 * 为什么集中成顶层函数：import 的「简单名/别名 + fromSource」解析规则被符号表与
 *   类型检查器共用，放在一处可避免两份实现随语法演进漂移。
 */
fun buildImportMap(imports: List<JasDeclaration>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (decl in imports) {
        if (decl !is JasImport || decl.isOnDemand) continue
        val fqn = if (decl.fromSource != null) "${decl.fromSource}.${decl.name}" else decl.name
        val simple = decl.alias ?: fqn.substringAfterLast('.')
        result[simple] = fqn
        // 别名与简单名并存：两种写法都解析到同一全限定名。
        decl.alias?.let { result[it] = fqn }
    }
    return result
}

/**
 * 符号/声明错误记录：由 SymbolTable 在采集与合并阶段产生。
 *
 * 做什么：承载一条重复声明类问题的描述、类别、名字与源码位置。
 * 什么时候用：只在符号表内部通过 errors 累积，不抛出异常；调用方读取后聚合展示。
 * 失败模式：本身无副作用，仅承载信息。
 * 为什么：把「重复声明」等问题与解析错误解耦，交由上层统一报告。
 */
data class SymbolError(val message: String, val kind: String, val name: String, val line: Int = 0, val column: Int = 0)

/**
 * 编译器基础符号表：汇聚顶层声明、包/import 与成员签名索引。
 *
 * 做什么：collectDeclarations/registerBuiltins 写入符号；findXxx/resolveXxx/
 *   findMemberSignature 提供只读查询；merge 合并其他源文件的符号表。
 * 什么时候用：解析出 AST 后、类型检查与代码生成之前构建；checker 依赖它解析
 *   类型名与成员访问，translator 依赖它做调用路由。
 * 前置条件：无强制前置；但未调用 collectDeclarations 时查询会返回空/null。
 * 失败模式：不抛异常；重复声明记录到 errors，查询未命中返回 null 或空列表。
 * 为什么：为检查器与翻译器提供统一、与 AST 解耦的符号视图，避免各自重复遍历。
 */
class SymbolTable {
    // 平台静态调用的类加载/重载事实查询。放在 SymbolTable 内供 checker 经现有允许的依赖方向
    // 查询，底层仍是同一个 PlatformMethodResolver，不复制反射入口。
    private val platformMethodResolver = PlatformMethodResolver()

    val functions: MutableMap<String, MutableList<JasFunction>> = mutableMapOf()
    /**
     * 已声明类，键是 [TypeKey]（包名 + 简单名）。
     *
     * 为什么不再以简单名为键：跨包同名类型（`p1.Thing` / `p2.Thing`）是不同身份；
     * 简单名只应作为查询期的解析输入，不能作为存储期的全局主键。
     */
    val classes: MutableMap<TypeKey, JasClass> = mutableMapOf()
    val interfaces: MutableMap<TypeKey, JasInterface> = mutableMapOf()
    val enums: MutableMap<TypeKey, JasEnum> = mutableMapOf()
    val annotations: MutableMap<TypeKey, JasAnnotationType> = mutableMapOf()
    val packages: MutableMap<String, MutableList<String>> = mutableMapOf()
    val imports: MutableMap<String, String> = mutableMapOf()
    val importDeclarations: MutableMap<String, JasImport> = mutableMapOf()

    /**
     * 「类型简单名 -> 该名字出现的包名集合」索引，供类型检查阶段判定可见性。
     *
     * 做什么：记录每个已声明类型（类/接口/枚举/注解）所在的包；同名类型若出现在
     *   多个包，则集合里会有多个包名（不丢失任何可见性信息）。
     * 什么时候用：`TypeChecker` 判断一个**显式写出的简单类型名**能否在当前文件的
     *   包里直接引用（同包可见），还是必须先 `import`。没有它就无法区分
     *   `std.Error`（别包）与本包同名类型——这正是「未知类型静默通过」的根因之一。
     * 前置条件：需要先 [collectDeclarations]；无包声明的文件记为空串。
     * 失败模式：未命中返回 null（集合不存在），不抛异常。
     * 为什么用集合而非单值：一个简单名可能在多个包各自声明；集合保留全部候选，
     *   既能判断「本包是否声明过」，也能在未知类型诊断里给出 import 提示。
     */
    val typePackages: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /**
     * Per-type member signature index: type name -> member name -> declaration.
     *
     * This is the symbol-table view the TypeChecker resolves member accesses
     * against. It contains both handwritten members and members synthesized by
     * the annotation processing phase (e.g. `@Getter` / `@Setter`), so synthesized
     * signatures are visible before any method body exists.
     *
     * 中文说明（为什么需要这个索引）：
     *   - 类型检查器解析 `obj.field` / `obj.method()` 时，需要一个「只看签名、不看
     *     方法体」的成员视图。手写成员当然在 AST 里，但 `@Getter`/`@Setter` 等注解
     *     合成的访问器在检查阶段还没有方法体，仅靠遍历 AST 会漏掉它们；把它们提前
     *     放进本索引后，合成签名在类型检查时即可见。
     *   - 索引粒度是「类型身份（[TypeKey]） -> 成员名 -> 单个声明」，采用
     *     first-declaration-wins（见 indexMembers 的 putIfAbsent），与旧的线性首次匹配
     *     扫描语义一致，保证重构前后行为不变。
     *   - [memberSignatures] 仍是「名字 -> 首个声明」的兼容索引，供属性与旧的单值查询
     *     路径使用；函数重载的完整候选集另存于 [memberFunctions]。
     *   - 已知限制：不做继承链查找（继承展开由 TypeChecker 的层次图负责）。
     */
    val memberSignatures: MutableMap<TypeKey, MutableMap<String, JasDeclaration>> = mutableMapOf()

    /**
     * 「类型身份 -> 成员名 -> 同名函数列表」的重载索引。
     *
     * 做什么：保留每个类型中**所有**同名函数声明（按声明顺序），使类型检查器能按
     *   实参个数/类型做重载决议，而不是只看见第一次出现的那个声明。
     * 什么时候用：[jasper.checker.TypeChecker] 解析实例成员调用 `obj.f(...)` 时通过
     *   [findMemberFunctions] 取候选；属性访问仍走 [findMemberSignature] 的单值索引。
     * 前置条件：需要先执行 [collectDeclarations]；注解阶段合成的成员也会经
     *   [indexMembers] 进入这里。
     * 失败模式：未命中返回空列表，不抛异常；不做继承链展开。
     * 为什么与 [memberSignatures] 并存：属性/字段是「一名一值」，函数是「一名多重载」，
     *   两者的基数不同。把它们拆成两张索引，既让重载路径拿到完整候选集，又让属性与
     *   兼容查询保持原有的单值语义（隔离的变化点：成员基数）。
     */
    val memberFunctions: MutableMap<TypeKey, MutableMap<String, MutableList<JasFunction>>> = mutableMapOf()
    var currentPackage: String? = null
    var errors: MutableList<SymbolError> = mutableListOf()

    /**
     * 做什么：查询全限定平台类上的真实 public static 方法签名。
     *
     * 什么时候用：TypeChecker 验证 `a.b.C.m(...)`，以及平台静态返回参与后续重载时。
     * 前置条件：ownerInternalName 为 JVM 内部名，argumentDescriptors 与源码实参一一对应。
     * 失败模式：类/方法不存在、无唯一候选或实参不匹配时返回 null，不抛内部异常。
     * 为什么放在 SymbolTable：checker 已依赖本设施，而直接依赖 compiler 包会形成环；
     *   这里只转发到唯一 PlatformMethodResolver，类加载与重载逻辑仍只有一份实现。
     */
    fun resolvePlatformStaticMethod(
        ownerInternalName: String,
        methodName: String,
        argumentDescriptors: List<String>
    ): PlatformMethodSignature? {
        return platformMethodResolver.resolveStatic(ownerInternalName, methodName, argumentDescriptors)
    }

    /**
     * 本次编译中**已出现过的类型身份**及类别（class/interface/enum/annotation）。
     *
     * 做什么：跨文件、跨类别检测同一 [TypeKey] 的重复声明；与各具体 Map 分开保存，
     *   才能在 `p1.Thing` 是 class、`p2.Thing` 是 interface 时仍按包区分互不冲突，
     *   而同一包内同名不同 kind 仍报冲突。
     * 什么时候用：collectDeclarations 与 merge 写入前查询/更新。
     * 失败模式：不抛异常，只作为诊断依据。
     */
    private val seenTypes: MutableMap<TypeKey, String> = mutableMapOf()

    /**
     * 每个类型声明所属文件的解析上下文（包名 + 精确 import 表）。
     *
     * 为什么需要：类型检查阶段会把符号表里**其它文件**的声明也并入继承图；此时仅凭
     *   当前文件的包/import 无法正确解析那份声明里的 `implements OtherThing`。
     *   用 IdentityHashMap 是因为 AST 是对象树，按对象身份记录比结构相等更可靠。
     */
    private val declarationScopes = IdentityHashMap<JasDeclaration, DeclarationScope>()

    /** 一个声明所在文件的解析上下文：只在 SymbolTable 内部使用。 */
    private data class DeclarationScope(
        val typeKey: TypeKey,
        val imports: Map<String, String>
    )

    /**
     * 当前**查询**使用的解析上下文（包名 + 精确 import 表）。
     *
     * 为什么与 [currentPackage] 分开：[currentPackage] 记录最后采集的文件，而多文件
     *   编译是「先采集全部文件、再逐文件检查」；查询上下文必须由调用方（TypeChecker）
     *   在检查每个文件前重置，否则会串包。
     */
    private var scopedPackage: String = ""
    private var scopedImports: Map<String, String> = emptyMap()

    /**
     * 进入某个源文件的解析作用域：后续简单名查询先按该文件的 import 与本包解析。
     *
     * 什么时候用：多文件编译中，每个文件做类型检查前调用（TypeChecker.check 入口）。
     * 前置条件：packageName 为该文件的 package 声明（null 表示默认包）；imports 是
     *   [buildImportMap] 的结果。
     * 失败模式：不抛异常；只影响后续 findXxx 的简单名解析，不改变已存声明。
     * 为什么显式方法而不是构造参数：SymbolTable 被多个阶段共享，文件作用域是「检查期
     *   临时状态」，显式重设比让每个查询调用方各自传包名/import 更不易漏改。
     */
    fun beginFileScope(packageName: String?, imports: Map<String, String> = emptyMap()) {
        scopedPackage = packageName ?: ""
        scopedImports = imports
    }

    /**
     * 注册编译器内置函数（println / print / toString），使它们在类型检查时可见。
     *
     * 什么时候用：构建符号表后、检查用户代码前调用一次（由编排层负责）。
     * 前置条件：无。
     * 失败模式：不会失败；但**不幂等**——重复调用会重复追加同名重载。
     * 为什么：内置函数没有用户可见的声明体，需要在此以合成 JasFunction 形式注入，
     *   否则对 `println(...)` 的调用会被当作未解析符号。
     */
    fun registerBuiltins() {
        // 三个内置函数都接受一个 any 参数；body=null 表示它们只有签名、没有 Jasper 方法体。
        val anyParam = listOf(JasParameter("value", JasAnyType))
        registerFunction("println", JasFunction(
            name = "println",
            parameters = anyParam,
            returnType = JasUnitType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
        registerFunction("print", JasFunction(
            name = "print",
            parameters = anyParam,
            returnType = JasUnitType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
        registerFunction("toString", JasFunction(
            name = "toString",
            parameters = anyParam,
            returnType = JasStringType,
            body = null,
            modifiers = listOf("public", "static"),
            typeParameters = emptyList(),
            whereConstraints = emptyList()
        ))
    }

    private fun registerFunction(name: String, func: JasFunction) {
        functions.getOrPut(name) { mutableListOf() }.add(func)
    }

    /**
     * 采集一个源文件的顶层声明到符号表（函数/类/接口/枚举/注解/import）。
     *
     * 什么时候用：每个 JasSourceFile 解析完成后调用；多文件时对每个文件分别调用，
     *   随后可用 merge 合并成全局视图。
     * 前置条件：ast 必须是语法合法、且声明名已填好的 AST。
     * 失败模式：不抛异常；遇到重复的类/接口/枚举/注解会向 errors 追加 SymbolError，
     *   并按「后写覆盖」或「追加重载」继续处理，不会中断采集。
     * 为什么：把 AST 一次性摊平成可 O(1) 查询的符号索引，并统一处理 import 别名。
     */
    fun collectDeclarations(ast: JasSourceFile) {
        currentPackage = ast.packageName
        val packageName = ast.packageName ?: ""
        // 记录包名占位，便于后续按包聚合；值本身当前不承载额外信息。
        ast.packageName?.let { pkg ->
            packages.getOrPut(pkg) { mutableListOf() }
        }
        // 本次文件的精确 import 表：既用于查询期作用域，也随声明一起保存，
        // 这样检查其它文件声明里的父类型引用时仍能使用那份文件自己的 import。
        val fileImports = buildImportMap(ast.imports + ast.declarations)
        beginFileScope(ast.packageName, fileImports)
        // 注意：`ast.imports` 与 `ast.declarations` 是**两个**独立列表（前者由 AST 构建
        // 器单独填充），只遍历 declarations 会整体漏掉 import —— 这正是「import 被忽略」
        // 的根因之一。这里把两者合并遍历，让 import 也进入符号索引。
        for (decl in ast.declarations + ast.imports) {
            when (decl) {
                is JasFunction -> {
                    // 函数按名字聚合为「重载列表」，同名不同参数个数是合法的。
                    functions.getOrPut(decl.name) { mutableListOf() }.add(decl)
                }
                is JasClass -> {
                    val key = registerTypeIdentity(decl, packageName, "class", fileImports)
                    classes[key] = decl
                    indexMembers(key, decl.members)
                }
                is JasInterface -> {
                    val key = registerTypeIdentity(decl, packageName, "interface", fileImports)
                    interfaces[key] = decl
                    indexMembers(key, decl.members)
                }
                is JasEnum -> {
                    val key = registerTypeIdentity(decl, packageName, "enum", fileImports)
                    enums[key] = decl
                }
                is JasAnnotationType -> {
                    val key = registerTypeIdentity(decl, packageName, "annotation", fileImports)
                    annotations[key] = decl
                }
                is JasImport -> {
                    // import 以「简单名」（点号最后一段）为键；若显式写了别名，则别名也映射到同一全限定名。
                    val simpleName = decl.name.split('.').lastOrNull() ?: decl.name
                    imports[simpleName] = decl.name
                    importDeclarations[simpleName] = decl
                    if (decl.alias != null) {
                        // 别名与简单名并存：两种写法都能解析到同一个全限定名。
                        imports[decl.alias!!] = decl.name
                        importDeclarations[decl.alias!!] = decl
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * 登记一个类型声明的身份与重复诊断，返回其 [TypeKey]。
     *
     * 做什么：按「包 + 简单名」查重；同 kind 重复与跨 kind 冲突都写一条 SymbolError。
     * 什么时候用：[collectDeclarations] 每遇到一个类型声明时。
     * 前置条件：packageName 为声明所在包（默认包用空串）；imports 为该文件精确 import 表。
     * 失败模式：不抛异常；即使重复也返回 key，调用方继续执行 last-wins 写入，以便
     *   后续阶段在错误集中报告中仍能看到完整符号视图。
     * 为什么把作用域一起记录：其它文件的声明在检查时也要解析父类型，不能只靠当前文件上下文。
     */
    private fun registerTypeIdentity(
        decl: JasDeclaration,
        packageName: String,
        kind: String,
        imports: Map<String, String>
    ): TypeKey {
        val key = TypeKey(packageName, declarationSimpleName(decl))
        declarationScopes[decl] = DeclarationScope(key, imports)
        val previous = seenTypes.put(key, kind)
        if (previous != null) {
            val qualified = key.qualifiedName
            val message = if (previous == kind) {
                "Duplicate $kind declaration '$qualified'"
            } else {
                "Duplicate $kind declaration '$qualified' (previous: $previous)"
            }
            errors.add(SymbolError(message, kind, qualified, line = decl.sourceLine, column = decl.sourceColumn))
        }
        recordTypePackage(key)
        return key
    }

    /**
     * 记录一个类型身份所属的包（供 [typePackages] 可见性判定使用）。
     *
     * 什么时候用：[registerTypeIdentity] 每登记一个类型声明时调用一次。
     * 前置条件：key 是「包 + 简单名」。
     * 失败模式：无（幂等，重复记录同一 key 不产生副作用）。
     * 为什么保留简单名索引：显式类型可见性检查按源码里写出的简单名工作，需要知道
     *   「这个名字在哪些包出现过」；它只是可见性索引，不再是类型身份。
     */
    private fun recordTypePackage(key: TypeKey) {
        typePackages.getOrPut(key.simpleName) { mutableSetOf() }.add(key.packageName)
    }

    /**
     * 取一个类型声明的简单名。
     *
     * 为什么需要：`JasDeclaration` 是 sealed 基类，没有统一的 `name` 属性；而类型身份
     *   登记只接受 class/interface/enum/annotation 四类。非类型声明返回空串，调用方
     *   本就不应传入。
     */
    private fun declarationSimpleName(decl: JasDeclaration): String = when (decl) {
        is JasClass -> decl.name
        is JasInterface -> decl.name
        is JasEnum -> decl.name
        is JasAnnotationType -> decl.name
        else -> ""
    }

    /**
     * 按简单名或限定名查找已声明的注解类型。
     *
     * 什么时候用：注解处理阶段判断某个注解名是否有对应声明。
     * 前置条件：需先执行 collectDeclarations；简单名按当前查询作用域（本包/import）解析。
     * 失败模式：未命中返回 null（不报错）。
     */
    fun findAnnotation(name: String): JasAnnotationType? {
        return resolveTypeKey(name)?.let { annotations[it] }
    }

    /**
     * Indexes the members of a type so the TypeChecker can resolve member accesses
     * (including annotation-synthesized accessors) uniformly.
     *
     * 中文说明：
     *   - 键是 [TypeKey]，因此 `p1.Thing.v` 与 `p2.Thing.v` 不会互相覆盖。
     *   - 只索引 JasProperty 与 JasFunction；其它成员（构造器、嵌套类型等）不进索引。
     *   - 属性保持 first-declaration-wins：同一类型内同名属性即重复声明，保留首个。
     *   - 函数**累积**到 [memberFunctions]：同名不同参数个数是合法的重载，不能再用
     *     putIfAbsent 丢掉后续声明（这正是 Q28 中「只看见第一个 q()」的根因）。
     *   - [memberSignatures] 同时保留首个函数声明，作为属性/兼容查询的单值视图。
     */
    private fun indexMembers(typeKey: TypeKey, members: List<JasDeclaration>) {
        val index = memberSignatures.getOrPut(typeKey) { mutableMapOf() }
        for (member in members) {
            when (member) {
                is JasProperty -> index.putIfAbsent(member.name, member)
                is JasFunction -> {
                    // 单值视图继续 first-writer-wins，保证属性/旧查询路径行为不变。
                    index.putIfAbsent(member.name, member)
                    // 重载视图累积全部同名函数；声明顺序即候选顺序。
                    memberFunctions
                        .getOrPut(typeKey) { mutableMapOf() }
                        .getOrPut(member.name) { mutableListOf() }
                        .add(member)
                }
                else -> {}
            }
        }
    }

    /**
     * 按「类型名 + 成员名」查找该类型**直接声明**的全部同名函数（重载候选）。
     *
     * 什么时候用：TypeChecker 解析 `obj.method(...)` 时先取候选，再按实参个数/类型过滤；
     *   调用方负责沿继承链合并祖先候选（见 TypeChecker 的层次遍历）。
     * 前置条件：对应类型已被 [collectDeclarations] 索引。
     * 失败模式：未命中返回空列表；不区分 static/instance，也不做继承展开。
     * 为什么返回列表：重载决议必须看到全部候选；返回单个值会让后续重载永远不可达。
     */
    fun findMemberFunctions(typeName: String, memberName: String): List<JasFunction> {
        return resolveTypeKey(typeName)?.let { memberFunctions[it]?.get(memberName) } ?: emptyList()
    }

    /**
     * 按「类型名 + 成员名」查找成员签名，未命中返回 null。
     *
     * 什么时候用：TypeChecker 解析 `obj.field` / `obj.method()` 时优先走这里。
     * 前置条件：对应类型已被 collectDeclarations 索引；注解合成的成员也已写入索引。
     * 失败模式：未命中返回 null；不区分重载，返回的是第一次声明的成员
     *   （函数重载请用 [findMemberFunctions]）。
     * 为什么：让注解阶段合成的签名在类型检查时可见，无需方法体存在；并为属性/字段
     *   这类「一名一值」成员保留稳定的单值查询。
     */
    fun findMemberSignature(typeName: String, memberName: String): JasDeclaration? {
        return resolveTypeKey(typeName)?.let { memberSignatures[it]?.get(memberName) }
    }

    /**
     * 把类型简单名/限定名解析为 JasNamedType，仅当它确实是本符号表中已声明的类型。
     *
     * 什么时候用：需要判断一个名字是否代表某个已声明类型时。
     * 前置条件：需先执行 collectDeclarations；简单名按当前查询作用域（本包/import）解析。
     * 失败模式：未命中返回 null；不做继承链查找。
     * 注意：返回的 [JasNamedType.name] 保留调用方写法（简单名或限定名），不在此处重写
     *   源 AST 名字；需要身份时请用 [resolveTypeKey]。
     */
    fun resolveType(name: String): JasType? {
        return if (resolveTypeKey(name) != null) JasNamedType(name) else null
    }

    /**
     * 按当前查询作用域把名字解析成唯一的 [TypeKey]；无法唯一解析时返回 null。
     *
     * 解析优先级（与语言可见性规则一致）：
     *   1. 已限定的名字（含点号）：直接按「包 + 简单名」查；
     *   2. 当前文件的精确 import（含别名）：命中且该类型已声明；
     *   3. 当前文件所在包：同包简单名优先；
     *   4. 兼容兜底：若整个符号表里只有一个同名类型，返回它（保持既有宽松行为）。
     *
     * 什么时候用：TypeChecker 的所有类型/成员查询、以及需要把名字落成类型身份的调用方。
     * 前置条件：调用前应通过 [beginFileScope] 设置当前文件上下文。
     * 失败模式：未命中或有多个同名候选且不在本包/import 中时返回 null，不抛异常。
     * 为什么 import 优先于本包：任务书明确要求 `import p2.Thing;` 后简单名解析到 p2；
     *   这样也让「显式导入」成为用户消除同包同名歧义的手段。
     */
    fun resolveTypeKey(name: String): TypeKey? {
        if (name.isEmpty()) return null
        val normalized = name.replace('/', '.')
        if (normalized.contains('.')) {
            val key = TypeKey.fromQualifiedName(normalized) ?: return null
            return key.takeIf(::hasType)
        }
        scopedImports[name]?.let { imported ->
            val key = TypeKey.fromQualifiedName(imported)
            if (key != null && hasType(key)) return key
        }
        val local = TypeKey(scopedPackage, name)
        if (hasType(local)) return local
        // 兼容兜底：全局唯一同名类型。跨包重名时故意返回 null，让可见性检查给出
        // 「需要 import / 未知类型」诊断，而不是静默选中某一个包。
        return simpleNameCandidates(name).singleOrNull()
    }

    /**
     * 在**指定声明所属文件**的上下文里解析类型名。
     *
     * 做什么：用声明记录下来的包名与 import 表做与 [resolveTypeKey] 相同的解析。
     * 什么时候用：TypeChecker 合并符号表中其它文件的继承关系时，解析 `implements`/`extends`。
     * 前置条件：owner 必须是经 [collectDeclarations] 登记过的类型声明。
     * 失败模式：owner 未登记时退回当前查询作用域；仍未命中返回 null。
     */
    fun resolveTypeKeyForDeclaration(owner: JasDeclaration, name: String): TypeKey? {
        val scope = declarationScopes[owner] ?: return resolveTypeKey(name)
        return resolveTypeKeyInScope(name, scope.typeKey.packageName, scope.imports)
    }

    /**
     * 在显式给定的包/import 上下文里解析类型名（不改动当前查询作用域）。
     *
     * 什么时候用：需要以「某个声明所在文件」的视角解析名字时（见 [resolveTypeKeyForDeclaration]）。
     * 前置条件：imports 为 [buildImportMap] 的结果。
     * 失败模式：同 [resolveTypeKey]。
     */
    fun resolveTypeKeyInScope(name: String, packageName: String?, imports: Map<String, String>): TypeKey? {
        if (name.isEmpty()) return null
        val normalized = name.replace('/', '.')
        if (normalized.contains('.')) {
            return TypeKey.fromQualifiedName(normalized)?.takeIf(::hasType)
        }
        imports[name]?.let { imported ->
            val key = TypeKey.fromQualifiedName(imported)
            if (key != null && hasType(key)) return key
        }
        val local = TypeKey(packageName ?: "", name)
        if (hasType(local)) return local
        return simpleNameCandidates(name).singleOrNull()
    }

    /**
     * 返回一个声明对应的类型身份；未登记的声明返回 null。
     *
     * 什么时候用：TypeChecker 建立继承图时需要稳定的限定键，而不是简单名。
     * 前置条件：声明已经过 [collectDeclarations]。
     */
    fun typeKeyOfDeclaration(decl: JasDeclaration): TypeKey? = declarationScopes[decl]?.typeKey

    /**
     * 判断某个类型身份是否已在符号表中声明（四类之一）。
     * 什么时候用：[resolveTypeKey] 内部与按限定名查询时。
     * 失败模式：无。
     */
    private fun hasType(key: TypeKey): Boolean =
        classes.containsKey(key) || interfaces.containsKey(key) ||
            enums.containsKey(key) || annotations.containsKey(key)

    /**
     * 按简单名返回所有已声明候选身份。
     *
     * 为什么从 [typePackages] 推导：该索引本就按简单名记录包集合，避免再造一份并行索引；
     *   过滤 [hasType] 可排除历史遗留/手工写入包集合但并无声明的条目。
     */
    private fun simpleNameCandidates(simpleName: String): List<TypeKey> {
        return typePackages[simpleName]
            ?.map { TypeKey(it, simpleName) }
            ?.filter(::hasType)
            ?: emptyList()
    }

    /**
     * 按名字返回所有同名函数重载；未命中返回空列表。
     *
     * 为什么返回列表：Jasper 允许同名不同参数个数的重载，调用推断需要看到全部候选。
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 emptyList()，绝不返回 null。
     */
    fun findFunctions(name: String): List<JasFunction> {
        return functions[name] ?: emptyList()
    }

    /**
     * 按名字返回第一个同名函数（重载集合中的首个声明）；未命中返回 null。
     *
     * 什么时候用：只需要一个代表签名、不关心重载集合时。
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null。
     */
    fun findFunction(name: String): JasFunction? {
        return functions[name]?.firstOrNull()
    }

    /**
     * 按简单名或限定名返回类声明；未命中返回 null。
     *
     * 什么时候用：大多数调用方只知道源码里的名字，由本兼容层按当前文件包/import 解析。
     * 前置条件：需先执行 collectDeclarations；简单名需先设置查询作用域。
     * 失败模式：未命中或同名候选无法唯一确定时返回 null。
     */
    fun findClass(name: String): JasClass? {
        return resolveTypeKey(name)?.let { classes[it] }
    }

    /**
     * 按已确定的类型身份返回类声明；未命中返回 null。
     *
     * 什么时候用：调用方已经持有 [TypeKey]（例如 TypeChecker 的继承图），避免再次按名字解析。
     * 失败模式：未命中返回 null。
     */
    fun findClass(key: TypeKey): JasClass? = classes[key]

    /**
     * 按简单名或限定名返回接口声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations；简单名需先设置查询作用域。
     * 失败模式：未命中或无法唯一解析时返回 null。
     */
    fun findInterface(name: String): JasInterface? {
        return resolveTypeKey(name)?.let { interfaces[it] }
    }

    /**
     * 按已确定的类型身份返回接口声明；未命中返回 null。
     * 什么时候用：已持有 [TypeKey] 的调用方（继承图、成员查询）。
     */
    fun findInterface(key: TypeKey): JasInterface? = interfaces[key]

    /**
     * 按简单名或限定名返回枚举声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations；简单名需先设置查询作用域。
     * 失败模式：未命中或无法唯一解析时返回 null。
     */
    fun findEnum(name: String): JasEnum? {
        return resolveTypeKey(name)?.let { enums[it] }
    }

    /**
     * 按显式包名 + 简单名返回类声明，不经过 import/当前作用域解析。
     *
     * 什么时候用：调用方明确知道目标类型的包（例如按 JVM 内部名反查）。
     * 前置条件：无；未命中返回 null。
     * 为什么单独提供：把「按身份精确查询」与「按源码名字兼容解析」分开，避免后者
     *   在未来规则变化时影响需要精确身份的调用点。
     */
    fun findClassQualified(packageName: String, name: String): JasClass? {
        return classes[TypeKey(packageName, name)]
    }

    /**
     * 按简单名（或别名）返回 import 声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null。
     */
    fun findImport(name: String): JasImport? {
        return importDeclarations[name]
    }

    /**
     * 把 import 的简单名/别名解析为被导入的全限定名；未命中返回 null。
     *
     * 什么时候用：解析 `Foo.bar` 时，把 `Foo` 还原成它被导入的全限定类型名。
     * 前置条件：需先执行 collectDeclarations（别名与简单名都已写入 imports 映射）。
     * 失败模式：未命中返回 null；不做通配 import（`a.b.*`）展开。
     */
    fun resolveImport(simpleName: String): String? {
        return imports[simpleName]
    }

    /**
     * 把另一个符号表的全部内容合并进当前实例（用于多源文件汇总）。
     *
     * 什么时候用：对每个文件分别 collectDeclarations 后，逐文件 merge 成全局符号表。
     * 前置条件：other 与 this 通常来自不同源文件。
     * 失败模式：不抛异常；同名类型已存在时向 errors 追加「during merge」的 SymbolError，
     *   并用 other 的声明覆盖当前值（函数则是追加为新重载）。
     * 为什么冲突这样处理：类型声明全局唯一，冲突必须报告；函数允许重载，故直接并集。
     */
    fun merge(other: SymbolTable) {
        for ((name, funcs) in other.functions) {
            // 函数：同名的重载集合直接并集追加（不判冲突）。
            functions.getOrPut(name) { mutableListOf() }.addAll(funcs)
        }
        // 类型冲突按 TypeKey 判定：只有「同包 + 同名」才冲突；跨包同名类型可以共存。
        // 用 seenTypes 统一检查，因此 class/interface/enum/annotation 之间的跨 kind
        // 冲突也会被报告，而不只是同一张 Map 内部的重复。
        for ((key, cls) in other.classes) {
            recordMergedType(key, "class", cls.sourceLine, cls.sourceColumn)
            classes[key] = cls
        }
        for ((key, iface) in other.interfaces) {
            recordMergedType(key, "interface", iface.sourceLine, iface.sourceColumn)
            interfaces[key] = iface
        }
        for ((key, enumDecl) in other.enums) {
            recordMergedType(key, "enum", enumDecl.sourceLine, enumDecl.sourceColumn)
            enums[key] = enumDecl
        }
        for ((key, ann) in other.annotations) {
            recordMergedType(key, "annotation", ann.sourceLine, ann.sourceColumn)
            annotations[key] = ann
        }
        importDeclarations.putAll(other.importDeclarations)
        imports.putAll(other.imports)
        packages.putAll(other.packages)
        declarationScopes.putAll(other.declarationScopes)
        // 可见性索引按简单名合并包集合（并集，不覆盖），避免跨包同名类型丢失候选包。
        for ((name, pkgs) in other.typePackages) {
            typePackages.getOrPut(name) { mutableSetOf() }.addAll(pkgs)
        }
        // 成员索引逐成员合并（first-writer-wins）：不同包的同名类型各有独立 TypeKey，
        // 不会互相覆盖；同一 TypeKey 的重复声明已在上面报错，此处保留已有成员即可。
        for ((key, index) in other.memberSignatures) {
            val target = memberSignatures.getOrPut(key) { mutableMapOf() }
            for ((memberName, declaration) in index) {
                target.putIfAbsent(memberName, declaration)
            }
        }
        // 重载索引按「类型 + 成员名」做并集：同名不同元数的函数必须全部保留。
        // 同一 TypeKey 的重复类型声明已在 recordMergedType 报错，这里仍保留候选，
        // 让后续检查能在错误集中同时看到冲突声明，而不是静默丢一半。
        for ((key, index) in other.memberFunctions) {
            val target = memberFunctions.getOrPut(key) { mutableMapOf() }
            for ((memberName, functions) in index) {
                target.getOrPut(memberName) { mutableListOf() }.addAll(functions)
            }
        }
    }

    /**
     * 合并时登记类型身份并诊断「同包同名」冲突。
     *
     * 什么时候用：[merge] 处理另一张符号表的四类类型声明时。
     * 前置条件：key 来自 other 的已登记声明。
     * 失败模式：不抛异常；冲突时追加 SymbolError，调用方仍按 last-wins 覆盖。
     */
    private fun recordMergedType(key: TypeKey, kind: String, line: Int, column: Int) {
        val previous = seenTypes.put(key, kind)
        if (previous != null) {
            val message = if (previous == kind) {
                "Duplicate $kind declaration '${key.qualifiedName}' during merge"
            } else {
                "Duplicate $kind declaration '${key.qualifiedName}' during merge (previous: $previous)"
            }
            errors.add(SymbolError(message, kind, key.qualifiedName, line = line, column = column))
        }
    }
}
