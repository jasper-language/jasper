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
    val functions: MutableMap<String, MutableList<JasFunction>> = mutableMapOf()
    val classes: MutableMap<String, JasClass> = mutableMapOf()
    val interfaces: MutableMap<String, JasInterface> = mutableMapOf()
    val enums: MutableMap<String, JasEnum> = mutableMapOf()
    val annotations: MutableMap<String, JasAnnotationType> = mutableMapOf()
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
     * 为什么用集合而非单值：符号表以简单名为键、last-writer-wins，跨包同名时单值会
     *   丢掉其中一个包；集合保留全部候选，避免误判「不可见」。
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
     *   - 索引粒度是「类型名 -> 成员名 -> 单个声明」，采用 first-declaration-wins
     *     （见 indexMembers 的 putIfAbsent），与旧的线性首次匹配扫描语义一致，
     *     保证重构前后行为不变。
     *   - 已知限制：同名重载只保留第一个；不做继承链查找；merge 时按整个类型索引
     *     覆盖而非逐成员合并（详见 merge）。
     */
    val memberSignatures: MutableMap<String, MutableMap<String, JasDeclaration>> = mutableMapOf()
    var currentPackage: String? = null
    var errors: MutableList<SymbolError> = mutableListOf()

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
        // 记录包名占位，便于后续按包聚合；值本身当前不承载额外信息。
        ast.packageName?.let { pkg ->
            packages.getOrPut(pkg) { mutableListOf() }
        }
        // seenTypes 用于检测“同一文件内、跨类别”的重名（如 class X 与 interface X 同名）。
        // 注意它与下面的 classes.containsKey 检查是两条独立路径：前者覆盖跨 kind 冲突，
        // 后者覆盖同 kind 的重复；两条都可能命中，因此重复声明可能产生两条诊断。
        val seenTypes = mutableMapOf<String, String>() // name -> kind
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
                    val prev = seenTypes.put(decl.name, "class")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate class declaration '$decl.name' (previous: $prev)", "class", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (classes.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate class declaration '$decl.name'", "class", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    // 后写覆盖：重复声明时保留最后一次出现的类。
                    classes[decl.name] = decl
                    indexMembers(decl.name, decl.members)
                    recordTypePackage(decl.name, ast.packageName)
                }
                is JasInterface -> {
                    val prev = seenTypes.put(decl.name, "interface")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate interface declaration '$decl.name' (previous: $prev)", "interface", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (interfaces.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate interface declaration '$decl.name'", "interface", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    interfaces[decl.name] = decl
                    indexMembers(decl.name, decl.members)
                    recordTypePackage(decl.name, ast.packageName)
                }
                is JasEnum -> {
                    val prev = seenTypes.put(decl.name, "enum")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate enum declaration '$decl.name' (previous: $prev)", "enum", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (enums.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate enum declaration '$decl.name'", "enum", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    enums[decl.name] = decl
                    recordTypePackage(decl.name, ast.packageName)
                }
                is JasAnnotationType -> {
                    val prev = seenTypes.put(decl.name, "annotation")
                    if (prev != null) {
                        errors.add(SymbolError("Duplicate annotation declaration '$decl.name' (previous: $prev)", "annotation", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    if (annotations.containsKey(decl.name)) {
                        errors.add(SymbolError("Duplicate annotation declaration '$decl.name'", "annotation", decl.name, line = decl.sourceLine, column = decl.sourceColumn))
                    }
                    annotations[decl.name] = decl
                    recordTypePackage(decl.name, ast.packageName)
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
     * 记录一个类型简单名所属的包（供 [typePackages] 可见性判定使用）。
     *
     * 什么时候用：[collectDeclarations] 每登记一个类型声明时调用一次。
     * 前置条件：pkg 为源文件声明包；null（无 package 声明）归一为空串。
     * 失败模式：无（幂等，重复记录同一 (name, pkg) 不产生副作用）。
     * 为什么：把「声明在哪个包」这一信息随声明一起保存，类型检查阶段才能判断
     *   一个简单名是否与当前文件同包、可否直接引用。
     */
    private fun recordTypePackage(name: String, pkg: String?) {
        typePackages.getOrPut(name) { mutableSetOf() }.add(pkg ?: "")
    }

    /**
     * 按简单名查找已声明的注解类型。
     *
     * 什么时候用：注解处理阶段判断某个注解名是否有对应声明。
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null（不报错）。
     */
    fun findAnnotation(name: String): JasAnnotationType? {
        return annotations[name]
    }

    /**
     * Indexes the members of a type by simple name so the TypeChecker can resolve
     * member accesses (including annotation-synthesized accessors) uniformly.
     * First declaration wins, matching the previous linear first-match scan.
     *
     * 中文说明：
     *   - 只索引 JasProperty 与 JasFunction；其它成员（构造器、嵌套类型等）不进索引。
     *   - putIfAbsent 保证 first-declaration-wins：同名成员（含重载）只保留第一次
     *     出现的声明。这是刻意为之——旧的成员解析是线性首次匹配，此处必须保持等价，
     *     重构才不会改变类型检查结果。
     */
    private fun indexMembers(typeName: String, members: List<JasDeclaration>) {
        val index = memberSignatures.getOrPut(typeName) { mutableMapOf() }
        for (member in members) {
            when (member) {
                is JasProperty -> index.putIfAbsent(member.name, member)
                is JasFunction -> index.putIfAbsent(member.name, member)
                else -> {}
            }
        }
    }

    /**
     * 按「类型名 + 成员名」查找成员签名，未命中返回 null。
     *
     * 什么时候用：TypeChecker 解析 `obj.field` / `obj.method()` 时优先走这里。
     * 前置条件：对应类型已被 collectDeclarations 索引；注解合成的成员也已写入索引。
     * 失败模式：未命中返回 null；不区分重载，返回的是第一次声明的成员。
     * 为什么：让注解阶段合成的签名在类型检查时可见，无需方法体存在。
     */
    fun findMemberSignature(typeName: String, memberName: String): JasDeclaration? {
        return memberSignatures[typeName]?.get(memberName)
    }

    /**
     * 把类型简单名解析为 JasNamedType，仅当它确实是本符号表中已声明的类/接口/枚举/注解。
     *
     * 什么时候用：需要判断一个名字是否代表某个已声明类型时。
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null；不做继承链查找，也不做包路径/import 解析。
     */
    fun resolveType(name: String): JasType? {
        if (classes.containsKey(name)) return JasNamedType(name)
        if (interfaces.containsKey(name)) return JasNamedType(name)
        if (enums.containsKey(name)) return JasNamedType(name)
        if (annotations.containsKey(name)) return JasNamedType(name)
        return null
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
     * 按名字返回类声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null。
     */
    fun findClass(name: String): JasClass? {
        return classes[name]
    }

    /**
     * 按名字返回接口声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null。
     */
    fun findInterface(name: String): JasInterface? {
        return interfaces[name]
    }

    /**
     * 按名字返回枚举声明；未命中返回 null。
     *
     * 前置条件：需先执行 collectDeclarations。
     * 失败模式：未命中返回 null。
     */
    fun findEnum(name: String): JasEnum? {
        return enums[name]
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
        for ((name, cls) in other.classes) {
            if (classes.containsKey(name)) {
                errors.add(SymbolError("Duplicate class declaration '$name' during merge", "class", name, line = cls.sourceLine, column = cls.sourceColumn))
            }
            // 冲突时后合并者覆盖（last-wins）；已在上面记录诊断。
            classes[name] = cls
        }
        for ((name, iface) in other.interfaces) {
            if (interfaces.containsKey(name)) {
                errors.add(SymbolError("Duplicate interface declaration '$name' during merge", "interface", name, line = iface.sourceLine, column = iface.sourceColumn))
            }
            interfaces[name] = iface
        }
        for ((name, enumDecl) in other.enums) {
            if (enums.containsKey(name)) {
                errors.add(SymbolError("Duplicate enum declaration '$name' during merge", "enum", name, line = enumDecl.sourceLine, column = enumDecl.sourceColumn))
            }
            enums[name] = enumDecl
        }
        for ((name, ann) in other.annotations) {
            if (annotations.containsKey(name)) {
                errors.add(SymbolError("Duplicate annotation declaration '$name' during merge", "annotation", name, line = ann.sourceLine, column = ann.sourceColumn))
            }
            annotations[name] = ann
        }
        importDeclarations.putAll(other.importDeclarations)
        imports.putAll(other.imports)
        packages.putAll(other.packages)
        // 可见性索引按简单名合并包集合（并集，不覆盖），避免跨包同名类型丢失候选包。
        for ((name, pkgs) in other.typePackages) {
            typePackages.getOrPut(name) { mutableSetOf() }.addAll(pkgs)
        }
        // 已知限制：memberSignatures 按“整个类型索引”做 putAll 覆盖——同名类型的成员
        // 索引会整体替换为 other 的版本，而非逐成员合并。若两文件的同名类型各有不同成员，
        // 合并后 this 侧部分成员索引会丢失（类型本身也已被覆盖，故影响有限）。
        memberSignatures.putAll(other.memberSignatures)
    }
}
