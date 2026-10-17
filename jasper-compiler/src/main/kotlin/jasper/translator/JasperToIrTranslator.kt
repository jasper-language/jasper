// ============================================================================
// JasperToIrTranslator —— 把 JasSourceFile（前端 AST）翻译成 Kotlin IR
//   （TranslationResult：irFiles + 后端所需符号登记表）。
// 所属层次：translator(2)。
// 与谁协作：上游 compiler（JasperCompiler）调用 translate / translateAll；
//   本文件依赖 ast（AST 节点）与 compiler.SymbolTable；产出 IR 交给后端
//   （JVM：JvmIrBackend/archive.JvmBackend；native/实验：jasper-llvm.LlvmBackend）消费。
// 对外契约：translate(ast, symbolTable?) / translateAll(asts, symbolTable?) -> TranslationResult。
// 扩展点 / 已知限制：新增一种「派生成员」只放宽到两段式合成的 late body 生成入口
//   （syntheticAccessorBody / syntheticEqualityBody / syntheticMapperBody）；
//   当前把泛型按 TypeMapper 近似映射、闭包降级为匿名类 + invoke 方法。
// ============================================================================

@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.translator

import jasper.ast.*
import jasper.compiler.SymbolTable
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.declarations.StageController
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.types.Variance

/**
 * Method names of the standard-library function-object protocols
 * (`Mapper.apply`, `Predicate.test`, `Reducer.reduce`, `Comparator.compare`,
 * `Action.accept`). They are semantic protocols: a call to one of these names on
 * a closure variable is routed to the closure's generated `invoke` method rather
 * than dispatched through the (abstract) protocol interface.
 *
 * 函数对象协议的方法名集合：对闭包变量调用这些名字时，路由到其生成的 invoke，而非协议接口分派。
 */
private val PROTOCOL_METHODS = setOf("apply", "test", "reduce", "compare", "accept")

/**
 * 做什么：一次翻译的完整产出——IR 文件列表 + 后端渲染所需的各种「符号 -> 名字/类型」登记表。
 * 什么时候用：translate / translateAll 返回；由后端（JVM/LLVM）消费以决定调用/字段的最终形态。
 * 前置条件：调用方应对同一批 AST 只取用一次结果。
 * 失败模式：本身不抛异常；翻译期的语义问题应在前端（checker/annotations）拦截。
 * 为什么这样设计：IR 的符号没有内建「这个名字是哪种调用/哪个类型」的信息，
 *   故在翻译期把后端需要的名字/类型旁路登记下来（见各字段注释），避免后端重新遍历 AST。
 */
data class TranslationResult(
    val irFiles: List<IrFile>,
    // 调用点符号 -> 调用名：后端据此区分实例/静态/特殊调用与运算符。
    val callNames: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    // 构造器符号 -> 构造种类（"object"/"array:<elemDesc>"）：后端据此选择 new/数组创建指令。
    val constructorTypes: Map<IrConstructorSymbol, String> = emptyMap(),
    val typeMapper: TypeMapper? = null,
    val functionDescriptors: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    val parameterDescriptors: Map<IrValueSymbol, String> = emptyMap(),
    val classSuperclassNames: Map<String, String> = emptyMap(), // className -> jvmInternalName
    val classInterfaceNames: Map<String, List<String>> = emptyMap(), // className -> jvmInternalName list
    val interfaceDeclNames: Set<String> = emptySet(), // names of types declared as interfaces
    // Field symbols that must be emitted/accessed as JVM statics (interface
    // constants). Backend uses this for `GETSTATIC`/`PUTSTATIC` and for the
    // `public static final` field flags.
    // 必须以 JVM 静态方式读写/发射的字段符号（接口常量）；后端据此发 GETSTATIC/PUTSTATIC 与 static final 标志。
    val staticFieldSymbols: Set<IrFieldSymbol> = emptySet()
)

/**
 * 做什么：单次翻译的共享可变状态（符号表、类型环境、当前类/函数/包、循环栈、defer 栈等）。
 * 什么时候用：translate/translateAll 内创建并贯穿整个翻译过程；不跨调用复用。
 * 前置条件：翻译开始时 pkg/currentPkg 已设；进入类/函数时更新 currentClassName/currentFunction。
 * 失败模式：不抛异常；查找失败时各 translate* 会回退到 Unit 类型/新符号（可能掩盖前端漏检，见各处注释）。
 * 为什么这样设计：翻译需要「先声明后使用」的全局可见性，故用一张可变上下文而非纯函数递归传递，
 *   以便成员/字段/函数符号在声明时登记、在后续表达式里查得到。
 */
data class TranslationContext(
    // 局部变量/参数名 -> IR 值符号。翻译语句时写入，标识符求值时读出。
    val symbols: MutableMap<String, IrValueSymbol> = mutableMapOf(),
    // 局部变量/参数名 -> IR 类型。与 symbols 平行维护，供取址/运算推导类型（缺失会退化为 Unit）。
    val varTypes: MutableMap<String, IrType> = mutableMapOf(),
    // 顶层/成员函数名 -> 函数符号。顶层函数在 predeclareTopLevelFunctions 预登记（先声明后调用）。
    val functionSymbols: MutableMap<String, IrSimpleFunctionSymbol> = mutableMapOf(),
    // 函数名 -> 返回类型；last-writer-wins，跨同名函数时被后者覆盖（故另有按类型索引的 memberReturnTypesByType）。
    val functionReturnTypes: MutableMap<String, IrType> = mutableMapOf(),
    // 函数名 -> 形参类型列表；成员调用实参加宽时按此对齐声明类型。
    val functionParamTypes: MutableMap<String, List<IrType>> = mutableMapOf(),
    // 调用点符号 -> 调用名（与 TranslationResult.callNames 同义，translate 完成时拷出）。
    val callNames: MutableMap<IrSimpleFunctionSymbol, String> = mutableMapOf(),
    // 构造器符号 -> 构造种类（"object"/"array:<desc>"）。
    val constructorTypes: MutableMap<IrConstructorSymbol, String> = mutableMapOf(),
    val typeMapper: TypeMapper? = null,
    // 仍在作用域内的 lambda 变量名 -> 其 AST。用于兼容旧的「内联展开 lambda」路径。
    val lambdas: MutableMap<String, JasLambdaExpr> = mutableMapOf(),
    // 被当作闭包对象处理的变量名集合（lambda 或方法引用初始化）。
    val lambdaVarNames: MutableSet<String> = mutableSetOf(),
    // Closure variable name -> its generated `invoke` return type. Keyed per
    // variable (not the global `functionReturnTypes["invoke"]`) so two closures
    // with different shapes in the same scope do not clobber each other.
    // 闭包变量名 -> 其 invoke 返回类型。按变量名索引（而非全局 invoke），
    // 否则同作用域里两个不同形状的闭包会互相覆盖（last-writer-wins 冲突）。
    val lambdaInvokeReturnTypes: MutableMap<String, IrType> = mutableMapOf(),
    // Member-return index: JVM-style class/interface name -> member name ->
    // return type. Used by the iteration protocol lowering to type the loop
    // variable from the concrete iterable's `next()` member.
    // 成员返回类型索引：JVM 风格类/接口名 -> 成员名 -> 返回类型。
    // 供迭代协议 lowering 依据具体可迭代类型的 next() 推断循环变量类型（避免全局映射被覆盖）。
    val memberReturnTypesByType: MutableMap<String, MutableMap<String, IrType>> = mutableMapOf(),
    // 类名 -> 类符号。
    val classSymbols: MutableMap<String, IrClassSymbol> = mutableMapOf(),
    // 字段名 -> 字段符号（全局按名索引，作为按类索引失败时的回退）。
    val fieldSymbols: MutableMap<String, IrFieldSymbol> = mutableMapOf(),
    // 字段名 -> 字段类型（全局回退）。
    val fieldTypes: MutableMap<String, IrType> = mutableMapOf(),
    // Per-class field indexes (JVM-style class name -> field name -> symbol/type).
    // Needed so a property read/write resolves the field owner from the
    // *receiver's* declared type rather than globally by name; without this two
    // classes that share a property name (e.g. a mapper's source and target)
    // would collide on the last-registered symbol.
    // 按类的字段索引：类名 -> 字段名 -> 符号/类型。属性读写据此从「接收者声明类型」定位字段宿主，
    // 否则两个类共用同一属性名（如 mapper 的源/目标）会在全局映射上撞成最后登记的那个。
    val fieldSymbolsByClass: MutableMap<String, MutableMap<String, IrFieldSymbol>> = mutableMapOf(),
    val fieldTypesByClass: MutableMap<String, MutableMap<String, IrType>> = mutableMapOf(),
    // 当前正在翻译的类（JVM 风格限定名）；this/super 的接收者类型与按类字段索引都依赖它。
    var currentClassName: String = "",
    // 循环栈：break/continue 指向栈顶最近循环。
    val loopStack: MutableList<IrLoop> = mutableListOf(),
    // 当前函数内累积的 defer 体；return/yield 时逆序展开。由 scopedDefers 保证不泄漏到相邻函数。
    val deferredBodies: MutableList<IrBlock> = mutableListOf(),
    // 当前函数；return 的目标符号与 defer 作用域依赖它。
    var currentFunction: IrSimpleFunction? = null,
    // 当前 IR 文件；匿名类（lambda/方法引用）在翻译中即时追加到这里。
    var currentFile: IrFile? = null,
    // 匿名闭包类名自增计数器（Lambda$N）。
    var lambdaIdCounter: Int = 0,
    // 当前类的父类（JVM 内部名）；构造器 super() 委托需要。
    var currentSuperclassName: String = "",
    // 当前包名；限定字段/类型内部名时用。
    var currentPkg: String = "",
    // 类名 -> 父类 JVM 内部名（导出到 TranslationResult）。
    val classSuperclassNames: MutableMap<String, String> = mutableMapOf(),
    val interfaceNames: MutableMap<String, MutableList<String>> = mutableMapOf(), // class/interface -> implemented/extends interface names
    val interfaceDeclNames: MutableSet<String> = mutableSetOf(), // names of types declared as interfaces
    // Class names that provide a `hash()` member (handwritten or synthesized
    // from @EqualsAndHashCode); used by the late @EqualsAndHashCode body
    // generator to decide between `.hash()` and `.hashCode()` for nested
    // object fields.
    // 提供 hash() 成员的类名集合（手写或 @EqualsAndHashCode 合成）；供 equals/hash 的 late body
    // 决定嵌套对象字段用 .hash()（值语义）还是 .hashCode()（默认/身份语义）。
    val hashableClassNames: MutableSet<String> = mutableSetOf(),
    // Field symbols for interface constants (`public static final` fields).
    // 接口常量（public static final 字段）的字段符号集合，导出给后端发静态指令。
    val staticFieldSymbols: MutableSet<IrFieldSymbol> = mutableSetOf()
)

/**
 * 做什么：Jasper 前端 AST 到 Kotlin IR 的翻译器入口（translator 层核心）。
 * 什么时候用：compiler 在前端检查通过后调用 translate（单文件）或 translateAll（多文件）。
 * 前置条件：传入的 AST 已经过注解阶段（合成成员签名已在位）与类型检查；symbolTable 可选，当前实现主要靠自身预登记。
 * 失败模式：不抛异常；查不到的符号/类型会回退到 Unit 或新建符号（可能把前端漏检掩盖成后端错误，见各方法注释）。
 * 为什么这样设计：翻译分两遍——先登记顶层函数/类型/字段符号，再翻译声明体；
 *   这样「先声明后使用」与跨文件引用都能解析，同时保持每个符号在后端有一致的名字/类型。
 */
class JasperToIrTranslator {
    private val typeMapper = TypeMapper()
    private val factory = IrFactory(StageController())

    /**
     * 做什么：翻译单个源文件为一份 IR 文件，返回 [TranslationResult]。
     * 什么时候用：单文件编译路径（CompilePipeline）。
     * 前置条件：ast 已过注解/类型检查；symbolTable 设了也暂不深入使用（保留扩展位）。
     * 失败模式：不抛异常；未识别的顶层声明被跳过（translateTopLevelDeclaration 返回 null）。
     * 为什么这样设计：先做「预登记」（内建函数/可哈希类/成员返回类型/顶层函数）再逐声明翻译，
     *   保证方法体里引用到尚未翻译到的声明时也有符号可用。
     */
    fun translate(ast: JasSourceFile, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        registerDeclaredTypeNames(listOf(ast))
        installFileContext(ast, ctx)
        // 第一遍（预登记）：不翻译任何声明体，先把「翻译体时会用到的信息」就位：
        //   - 内建函数 hashCode/equals 的签名（合成体要按正确描述符调用）
        //   - 可哈希类名（决定嵌套字段用 .hash() 还是 .hashCode()）
        //   - 按类型的成员返回类型（迭代协议据此推断循环变量）
        //   - 顶层函数符号（先声明后调用）
        predeclareRuntimeBuiltins(ctx)
        collectHashableClassNames(listOf(ast), ctx)
        collectMemberReturnTypes(ast, ctx)
        predeclareTopLevelFunctions(ast.declarations, ctx)
        val fileEntry = NaiveSourceBasedFileEntryImpl("<main>", intArrayOf(), 0)
        val fileSymbol = IrFileSymbolImpl()
        val file = IrFileImpl(fileEntry, fileSymbol, FqName(ctx.currentPkg))
        ctx.currentFile = file
        // 第二遍（翻译声明）：逐条顶层声明翻译成 IR，追加进文件。
        // 跳过返回 null 的声明（注解成员/构造器/属性/导入等在顶层不直接产出 IR）。
        for (decl in ast.declarations) {
            val irDecl = translateTopLevelDeclaration(decl, ctx)
            if (irDecl != null) {
                file.declarations.add(irDecl)
            }
        }
        // 把上下文里累积的符号登记表拷成不可变快照交给后端。
        return TranslationResult(listOf(file), ctx.callNames.toMap(), ctx.constructorTypes.toMap(), typeMapper,
            classSuperclassNames = ctx.classSuperclassNames.toMap(),
            classInterfaceNames = ctx.interfaceNames.mapValues { it.value.toList() }.toMap(),
            interfaceDeclNames = ctx.interfaceDeclNames.toSet(),
            staticFieldSymbols = ctx.staticFieldSymbols.toSet())
    }

    /**
     * 做什么：把多份源文件翻译成一组 IR 文件（多文件编译路径）。
     * 什么时候用：compileAll 这类批量编译；跨文件同名引用依赖同一 ctx 的「全局可见」。
     * 前置条件：asts 已全部过注解与类型检查；文件顺序即输出顺序。
     * 失败模式：不抛异常；未识别声明被跳过；同名符号仍是 last-writer-wins（后遍历者覆盖）。
     * 为什么这样设计：所有 AST 共享一个 [TranslationContext]，先对全部文件做预登记再逐文件翻译，
     *   使文件 A 的方法体能引用文件 B 里后声明的类型/函数。
     */
    fun translateAll(asts: List<JasSourceFile>, symbolTable: SymbolTable? = null): TranslationResult {
        val ctx = TranslationContext(typeMapper = typeMapper)
        registerDeclaredTypeNames(asts)
        predeclareRuntimeBuiltins(ctx)
        // 第一遍：对全部文件做预登记（保证跨文件的先声明后使用）。
        for (ast in asts) {
            installFileContext(ast, ctx)
            collectHashableClassNames(listOf(ast), ctx)
            collectMemberReturnTypes(ast, ctx)
            predeclareTopLevelFunctions(ast.declarations, ctx)
        }

        val files = mutableListOf<IrFile>()
        // 第二遍：逐文件逐声明翻译；每个文件单独一个 IrFile，currentFile 指向当前文件。
        for ((index, ast) in asts.withIndex()) {
            installFileContext(ast, ctx)
            val pkg = ctx.currentPkg
            val fileEntry = NaiveSourceBasedFileEntryImpl("<file$index>", intArrayOf(), 0)
            val fileSymbol = IrFileSymbolImpl()
            val file = IrFileImpl(fileEntry, fileSymbol, FqName(pkg))
            ctx.currentFile = file
            for (decl in ast.declarations) {
                val irDecl = translateTopLevelDeclaration(decl, ctx)
                if (irDecl != null) {
                    file.declarations.add(irDecl)
                }
            }
            files.add(file)
        }

        return TranslationResult(files, ctx.callNames.toMap(), ctx.constructorTypes.toMap(), typeMapper,
            classSuperclassNames = ctx.classSuperclassNames.toMap(),
            classInterfaceNames = ctx.interfaceNames.mapValues { it.value.toList() }.toMap(),
            interfaceDeclNames = ctx.interfaceDeclNames.toSet(),
            staticFieldSymbols = ctx.staticFieldSymbols.toSet())
    }

    /**
     * 做什么：扫描一组源文件的顶层类型声明，把它们的**简单名**登记进 [TypeMapper]，
     *   供名字解析区分「真实单字母类型」与「泛型形参」。
     *
     * 什么时候用：translate / translateAll 开始翻译前调用一次（必须早于任何 mapType，
     *   否则解析已经用错判据）。
     * 前置条件：asts 已完成解析。
     * 失败模式：不抛异常；只登记 class/interface/enum/annotation 四类顶层声明，
     *   嵌套类型暂不登记（当前语言与探针均不依赖）。
     * 为什么这样设计：`isLikelyTypeParam` 的单字母启发式无法自行分辨用户类型与形参，
     *   在翻译前把「有哪些真实类型」一次性告诉 TypeMapper，是最小改动且依赖单向的做法。
     */
    private fun registerDeclaredTypeNames(asts: List<JasSourceFile>) {
        for (ast in asts) {
            for (decl in ast.declarations) {
                when (decl) {
                    is JasClass -> typeMapper.registerKnownType(decl.name)
                    is JasInterface -> typeMapper.registerKnownType(decl.name)
                    is JasEnum -> typeMapper.registerKnownType(decl.name)
                    is JasAnnotationType -> typeMapper.registerKnownType(decl.name)
                    else -> {}
                }
            }
        }
    }

    /**
     * 做什么：为「正在翻译的某个源文件」安装包与 import 上下文，供 translator 与
     *   [TypeMapper] 共用，使后续所有名字解析都在正确的命名空间里发生。
     *
     * 什么时候用：translate / translateAll 处理每个文件前调用一次（第一遍预登记与
     *   第二遍翻译各调用一次，保证两遍看到相同的包/import 视图）。
     * 前置条件：ast 已完成解析；import 列表来自 [JasSourceFile.imports]（与
     *   `declarations` 分开存放，故必须单独遍历，否则 import 会被整体忽略——#14 的根因之一）。
     * 失败模式：不抛异常。无法识别的 import 形态被跳过；未安装时退回「当前包 + 简单名」。
     * 为什么这样设计：包名与 import 表都是**每个文件各自**的命名空间，而 translator 在
     *   多文件路径下共享一个 ctx/typeMapper，因此必须在切换文件时显式重装，不能只在
     *   构造时设置一次——否则跨文件编译会串包。
     */
    private fun installFileContext(ast: JasSourceFile, ctx: TranslationContext) {
        val pkg = ast.packageName ?: ""
        ctx.currentPkg = pkg
        typeMapper.currentPkg = pkg
        // 精确 import：简单名/别名 -> 点分全限定名。
        val exact = mutableMapOf<String, String>()
        // 通配 import 的包前缀（当前仅记录，不在解析时猜测展开，见 TypeMapper 注释）。
        val wildcard = mutableListOf<String>()
        for (decl in ast.imports) {
            if (decl !is JasImport) continue
            if (decl.isOnDemand) {
                // `import a.b.*`（name="a.b.*"）与 `from a.b { * }`（name="*", fromSource="a.b"）两种形态。
                val wildcardPkg = if (decl.name != "*") decl.name.removeSuffix(".*") else decl.fromSource
                if (!wildcardPkg.isNullOrEmpty()) wildcard.add(wildcardPkg)
            } else {
                // 单条 import 的 name 已是全限定名；from-import 的 name 是简单名、fromSource 是包前缀。
                val fqn = if (decl.fromSource != null) "${decl.fromSource}.${decl.name}" else decl.name
                val simple = decl.alias ?: fqn.substringAfterLast('.')
                exact[simple] = fqn
                // 别名与简单名并存：两种写法都解析到同一全限定名。
                if (decl.alias != null) exact[decl.alias!!] = fqn
            }
        }
        typeMapper.currentImports = exact
        typeMapper.currentWildcardPackages = wildcard
    }

    // 预登记顶层函数的符号与签名：先建符号、登记调用名/返回/形参类型。
    // 用 getOrPut/putIfAbsent 保证重复预登记（如多文件路径）不会覆盖已有信息（first-writer-wins，等价于声明顺序优先）。
    private fun predeclareTopLevelFunctions(declarations: List<JasDeclaration>, ctx: TranslationContext) {
        for (decl in declarations) {
            if (decl is JasFunction) {
                val symbol = ctx.functionSymbols.getOrPut(decl.name) { IrSimpleFunctionSymbolImpl() }
                ctx.callNames[symbol] = decl.name
                ctx.functionReturnTypes.putIfAbsent(decl.name, typeMapper.mapType(decl.returnType))
                ctx.functionParamTypes.putIfAbsent(decl.name, decl.parameters.map { typeMapper.mapType(it.type) })
            }
        }
    }

    /**
     * Declares the signatures of runtime helpers the lowering of synthesized
     * member bodies relies on. `hashCode()` is the universal JVM object-hash
     * entry point (inherited by every class); its return type must be known so
     * member calls to it lower with an `I` return descriptor instead of `V`.
     *
     * 做什么：预登记合成成员体所依赖的运行时内建函数签名（hashCode / equals）。
     * 什么时候用：翻译开始前（translate/translateAll 的第一遍）。
     * 前置条件：无；对同一 ctx 重复调用是幂等的（直接赋值覆盖为同一形状）。
     * 为什么这样设计：合成体的 .hashCode()/.equals() 调用若没有已知签名，后端会退化成错误的描述符（如 V 或 (String)V）。
     */
    private fun predeclareRuntimeBuiltins(ctx: TranslationContext) {
        ctx.functionReturnTypes["hashCode"] = typeMapper.intType()
        ctx.functionParamTypes["hashCode"] = emptyList()
        // `equals` is the universal value-equality entry point (`String.equals`,
        // user-class `equals(other: any)` synthesized by @EqualsAndHashCode, and
        // any class that overrides Object.equals). Declaring its shape up front
        // lets a member call `s.equals(o)` lower with an `Object` parameter and a
        // `bool` (Z) return instead of falling back to `(String)V`.
        ctx.functionReturnTypes["equals"] = typeMapper.boolType()
        ctx.functionParamTypes["equals"] = listOf(typeMapper.type("kotlin.Any"))
    }

    /**
     * Records the class names that provide a `hash()` member — handwritten or
     * synthesized from `@EqualsAndHashCode`. The late equality body generator
     * calls `.hash()` on nested fields of such classes (value-based) and
     * `.hashCode()` on everything else (identity/default).
     *
     * 做什么：收集「提供 hash() 成员」的类名（手写 hash 或带 @EqualsAndHashCode）。
     * 什么时候用：第一遍预登记（translate/translateAll）。
     * 前置条件：AST 已过注解阶段（@EqualsAndHashCode 合成的 hash() 已在成员里）。
     * 为什么这样设计：equals/hash 的 late body 生成嵌套对象字段时需在 .hash()（值语义）与
     *   .hashCode()（身份/默认语义）间选择，此集合即该决策依据。
     */
    private fun collectHashableClassNames(asts: List<JasSourceFile>, ctx: TranslationContext) {
        for (ast in asts) {
            for (decl in ast.declarations) {
                if (decl !is JasClass) continue
                val hasHashFunction = decl.members.any { it is JasFunction && it.name == "hash" }
                val hasEqualityAnnotation =
                    decl.modifiers.filterIsInstance<JasAnnotationUse>().any { simpleAnnotationName(it.name) == "EqualsAndHashCode" } ||
                        decl.members.filterIsInstance<JasProperty>().any { prop ->
                            prop.modifiers.filterIsInstance<JasAnnotationUse>().any { simpleAnnotationName(it.name) == "EqualsAndHashCode" }
                        }
                if (hasHashFunction || hasEqualityAnnotation) {
                    ctx.hashableClassNames.add(decl.name)
                }
            }
        }
    }

    private fun simpleAnnotationName(qualified: String): String = qualified.substringAfterLast('.')

    /**
     * Indexes member return types per declaring type (JVM-style name). The
     * iteration protocol lowering reads `next()` from this index so the loop
     * variable is typed from the *concrete* iterable rather than the global,
     * last-writer-wins `functionReturnTypes` map.
     *
     * 做什么：按「声明类型」索引成员返回类型（JVM 风格限定名）。
     * 什么时候用：第一遍预登记。
     * 前置条件：pkg 取自 AST 的包名（null 视为空包）。
     * 为什么这样设计：迭代协议 lowering 需要从「具体可迭代类型」的 next() 取类型，
     *   而全局 functionReturnTypes 是 last-writer-wins 的，同名成员会互相覆盖。
     */
    private fun collectMemberReturnTypes(ast: JasSourceFile, ctx: TranslationContext) {
        /**
         * 做什么：把一个类型的成员(name->返回类型)写入按类型的成员返回索引。
         * 什么时候用：本函数遍历到 JasClass/JasInterface 时各调用一次。
         * 前置条件：调用前已通过 installFileContext 安装当前文件的包/import 上下文，
         *   否则 resolveNamedTypeName 无法得到正确的限定名。
         * 失败模式：无；index 用 putIfAbsent，重复名字保留先登记者（first-writer-wins）。
         */
        fun record(typeName: String, members: List<JasDeclaration>) {
            // 用中央解析得到与运行时一致的内部分名（处理点分包名与 import），
            // 否则键会与 irTypeClassName 反查出的名字对不上（如 "a.b/T" vs "a/b/T"）。
            val qualified = typeMapper.resolveNamedTypeName(typeName)
            val index = ctx.memberReturnTypesByType.getOrPut(qualified) { mutableMapOf() }
            for (member in members) {
                if (member is JasFunction) {
                    index.putIfAbsent(member.name, typeMapper.mapType(member.returnType))
                }
            }
        }
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> record(decl.name, decl.members)
                is JasInterface -> record(decl.name, decl.members)
                else -> {}
            }
        }
    }

    private fun translateTopLevelDeclaration(decl: JasDeclaration, ctx: TranslationContext): IrDeclaration? {
        return when (decl) {
            is JasFunction -> translateFunction(decl, null, ctx)
            is JasClass -> translateClass(decl, ctx)
            is JasInterface -> translateInterface(decl, ctx)
            is JasEnum -> translateEnum(decl, ctx)
            is JasAnnotationType -> translateAnnotationType(decl, ctx)
            is JasAnnotationMember -> null
            is JasConstructor -> null
            is JasProperty -> null
            is JasImport -> null
        }
    }

    private fun translateClassMember(decl: JasDeclaration, ctx: TranslationContext, classSymbol: IrClassSymbol): IrDeclaration? {
        return when (decl) {
            is JasFunction -> translateFunction(decl, classSymbol, ctx)
            is JasConstructor -> translateConstructor(decl, classSymbol, ctx)
            is JasProperty -> translateProperty(decl, classSymbol, ctx)
            else -> null
        }
    }

    // ── function ──

    private fun translateFunction(
        func: JasFunction, parentClass: IrClassSymbol?, ctx: TranslationContext
    ): IrSimpleFunction {
        // Create type parameters first so they can be referenced by parameter/return types
        val irTypeParams = func.typeParameters.map { tp ->
            val irTp = translateTypeParameter(tp, ctx)
            irTp
        }
        // Helper to resolve JasNamedType referring to a type parameter to an IrTypeParameterSymbol-based type
        // 泛型作用域内的类型解析：名字命中本函数类型形参的 JasNamedType 要解析成「形参符号类型」，
        // 否则后续按普通命名类型映射会丢失去参数化信息。
        /**
         * 做什么：解析函数内的类型，把引用本函数类型形参的 JasNamedType 映射为对应 IR 形参类型。
         * 什么时候用：翻译返回类型、形参类型以及体内类型时。
         * 前置条件：irTypeParams 已创建（类型形参在被引用前先建好）。
         * 失败模式：无匹配形参时退回 typeMapper.mapType（普通映射；可能被近似/擦除）。
         */
        fun resolveTypeParam(type: JasType?): IrType {
            if (type is JasNamedType && type.typeArguments.isEmpty()) {
                val matching = irTypeParams.find { it.name.toString() == type.name }
                if (matching != null) {
                    return typeMapper.typeFromSymbol(matching.symbol)
                }
            }
            return typeMapper.mapType(type)
        }
        val returnType = resolveTypeParam(func.returnType)
        val symbol = if (parentClass == null) {
            ctx.functionSymbols[func.name] ?: IrSimpleFunctionSymbolImpl()
        } else {
            IrSimpleFunctionSymbolImpl()
        }
        val irFunc = factory.createSimpleFunction(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(func.name),
            if (func.modifiers.contains("public")) DescriptorVisibilities.PUBLIC else DescriptorVisibilities.INTERNAL,
            false, false,
            returnType,
            if (func.modifiers.contains("abstract") || func.modifiers.contains("open")) Modality.ABSTRACT else Modality.FINAL,
            symbol,
            false, false, false, false, false,
            null, false
        )
        for (irTp in irTypeParams) {
            irFunc.typeParameters = irFunc.typeParameters + irTp
        }
        for ((i, param) in func.parameters.withIndex()) {
            val paramType = resolveTypeParam(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name),
                paramType,
                param.vararg,
                paramSymbol,
                i,
                null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }
        val bodyStatements: List<JasStatement>? = when {
            func.syntheticAccessor != null -> syntheticAccessorBody(func.syntheticAccessor!!, func)
            func.syntheticEquality != null -> syntheticEqualityBody(func.syntheticEquality!!, ctx)
            func.syntheticMapper != null -> syntheticMapperBody(func.syntheticMapper!!)
            func.body != null -> func.body!!.statements
            else -> null
        }
        if (bodyStatements != null) {
            val prevFunc = ctx.currentFunction
            ctx.currentFunction = irFunc
            val body = irFunc.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
            scopedDefers(ctx) {
                for (stmt in bodyStatements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
                }
            }
            irFunc.body = body
            ctx.currentFunction = prevFunc
        }
        if (parentClass == null) {
            ctx.functionSymbols[func.name] = symbol
        }
        ctx.functionReturnTypes[func.name] = returnType
        ctx.functionParamTypes[func.name] = func.parameters.map { resolveTypeParam(it.type) }
        ctx.callNames[symbol] = func.name
        return irFunc
    }

    /**
     * Late body generation for annotation-synthesized accessors.
     *
     * The *signature* (`getFoo()` / `setFoo(value)`) was synthesized by the
     * annotation processing phase before type checking. By the time lowering runs,
     * the property's backing field symbol (`ctx.fieldSymbols`) and the setter
     * parameter symbol are available, so the body is lowered through the normal
     * statement pipeline (`return this.prop` / `this.prop = value`).
     *
     * 做什么：为注解合成的 getter/setter 生成「方法体」（两段式合成的第二段）。
     * 什么时候用：translateFunction 发现 func.syntheticAccessor != null 时。
     * 前置条件：属性后端字段符号已登记进 ctx（方法体经正常语句管线翻译成 this.prop 读写）。
     * 失败模式：无；即使没有参数（setter 异常输入）也退回参数名 "value"。
     * 为什么这样设计：注解阶段只出签名（否则生成的方法在类型检查时不可见）；
     *   体推迟到这里，是因为只有 lowering 时才知道属性字段的真实 IR 形态。
     */
    private fun syntheticAccessorBody(
        accessor: JasSyntheticAccessor,
        func: JasFunction
    ): List<JasStatement> {
        val thisField = JasPropertyAccess(JasIdentifier("this"), accessor.propertyName)
        // getter：return this.prop；setter：this.prop = value（形参名取自签名，缺省 "value"）。
        return if (accessor.isGetter) {
            listOf(JasReturn(thisField))
        } else {
            val paramName = func.parameters.firstOrNull()?.name ?: "value"
            listOf(JasExpressionStatement(JasAssignment(thisField, JasIdentifier(paramName))))
        }
    }

    /**
     * Late body generation for `@EqualsAndHashCode`-synthesized members
     * ([JasSyntheticEquality]). The signature (`equals(other: any): bool` /
     * `hash(): int32`) was synthesized by the annotation processing phase
     * before type checking; these bodies are lowered here directly because the
     * source syntax has no type-test expression (`other is Foo`), so the
     * instanceof guard can only be expressed at the AST→IR boundary.
     *
     * 做什么：为 @EqualsAndHashCode 合成的 equals/hash 生成方法体（两段式合成的第二段）。
     * 什么时候用：translateFunction 发现 func.syntheticEquality != null 时。
     * 前置条件：JasSyntheticEquality 携带 className 与参与字段清单。
     * 为什么这样设计：源码语法没有「类型测试表达式」（如 other is Foo），instanceof 守卫
     *   只能在 AST→IR 边界表达，故必须在 lowering 阶段直接构造。
     */
    private fun syntheticEqualityBody(eq: JasSyntheticEquality, ctx: TranslationContext): List<JasStatement> =
        if (eq.isEquals) syntheticEqualsBody(eq) else syntheticHashBody(eq, ctx)

    /**
     * `equals` body:
     * ```
     * if (!(other is Foo)) { return false; }
     * var other$ = cast other as Foo;
     * return this.f1 == o.f1 && ...;
     * ```
     * The instanceof guard also rejects `null` (instanceof null is false), so
     * different-class comparisons return false without throwing.
     *
     * 做什么：生成 equals 体：先 instanceof 守卫，再逐字段 == 相与。
     * 什么时候用：syntheticEqualityBody 判定 isEquals 时。
     * 前置条件：fields 已按资格判定筛选。
     * 失败模式：无字段时返回 true（空类 equals）。
     * 为什么这样设计：instanceof 对 null 也为 false，故守卫天然拒绝 null 与异类，无需额外判空/判型。
     */
    private fun syntheticEqualsBody(eq: JasSyntheticEquality): List<JasStatement> {
        val clsType = JasNamedType(eq.className)
        val other = JasIdentifier("other")
        val guard = JasIf(
            condition = JasUnaryOp("!", JasInstanceOfExpr(other, clsType), prefix = true),
            thenBody = JasBlock(listOf(JasReturn(JasBoolLiteral(false)))),
            elseBody = null
        )
        val local = JasVariableStatement("other$", clsType, JasCastExpr(other, clsType))
        val comparisons = eq.fields.map { field ->
            JasBinaryOp(
                JasPropertyAccess(JasIdentifier("this"), field.name),
                "==",
                JasPropertyAccess(JasIdentifier("other$"), field.name)
            )
        }
        val result = comparisons.reduceOrNull { acc, next -> JasBinaryOp(acc, "&&", next) }
            ?: JasBoolLiteral(true)
        return listOf(guard, local, JasReturn(result))
    }

    /**
     * `hash` body: seed and mix every participating field with
     * `h = h * 31 + fieldHash`, then `return h`. Deterministic, no randomness
     * and no object-identity dependence beyond what `equals` itself treats as
     * value (e.g. nested objects that only have identity equality).
     *
     * 做什么：生成 hash 体：h 初值 17，逐字段 h = h*31 + fieldHash，返回 h。
     * 什么时候用：syntheticEqualityBody 判定非 equals 时。
     * 前置条件：字段哈希由 fieldHashExpr 依字段类型给出确定的 int32 贡献。
     * 为什么这样设计：31 乘子与初值 17 是确定性约定，避免随机性与对象身份依赖，使等价对象哈希一致。
     */
    private fun syntheticHashBody(eq: JasSyntheticEquality, ctx: TranslationContext): List<JasStatement> {
        val stmts = mutableListOf<JasStatement>()
        stmts.add(JasVariableStatement("h", JasPrimitiveType("int32"), JasIntLiteral(17, "17")))
        for (field in eq.fields) {
            val mixed = JasBinaryOp(
                JasBinaryOp(JasIdentifier("h"), "*", JasIntLiteral(31, "31")),
                "+",
                fieldHashExpr(field, ctx)
            )
            stmts.add(JasExpressionStatement(JasAssignment(JasIdentifier("h"), mixed)))
        }
        stmts.add(JasReturn(JasIdentifier("h")))
        return stmts
    }

    /** int32 contribution of one field to the `hash` accumulator. */
    // 单字段的 int32 哈希贡献：按声明类型选择混合方式，保证等价字段贡献一致（不依赖身份/随机）。
    private fun fieldHashExpr(field: JasEqualityField, ctx: TranslationContext): JasExpression {
        val thisField = JasPropertyAccess(JasIdentifier("this"), field.name)
        return when (val type = field.type) {
            is JasPrimitiveType -> when (type.name.lowercase()) {
                "bool" -> JasTernaryExpr(thisField, JasIntLiteral(1, "1"), JasIntLiteral(0, "0"))
                "int64" -> {
                    // XOR-fold the high 32 bits into the low 32 bits.
                    val hi = JasCastExpr(
                        JasBinaryOp(thisField, ">>>", JasIntLiteral(32, "32")),
                        JasPrimitiveType("int32")
                    )
                    val lo = JasCastExpr(thisField, JasPrimitiveType("int32"))
                    JasBinaryOp(hi, "^", lo)
                }
                "float32", "float64" -> JasCastExpr(thisField, JasPrimitiveType("int32"))
                else -> thisField // int32 / char / byte / short mix as ints
            }
            is JasStringType -> nullGuardedMemberHash(thisField, "hashCode")
            is JasArrayType -> {
                // Arrays compare by identity in `equals`; their length is a
                // deterministic, identity-free contribution.
                // 数组在 equals 里按身份比较，故这里只用其长度作为确定性、无身份的哈希贡献。
                nullGuardedMemberHash(thisField, "length")
            }
            is JasNamedType -> {
                // 命名类型：若该类带 hash()（手写或 @EqualsAndHashCode 合成）用 .hash()（值语义），
                // 否则用 .hashCode()（身份/默认语义）。判定依据是预登记的可哈希类名集合。
                val hashMethod = if (type.name.substringAfterLast('.') in ctx.hashableClassNames) "hash" else "hashCode"
                nullGuardedMemberHash(thisField, hashMethod)
            }
            else -> nullGuardedMemberHash(thisField, "hashCode")
        }
    }

    /**
     * `field == null ? 0 : field.member()` — null fields hash to 0 instead of
     * throwing. `length` is lowered as ARRAYLENGTH by the property-access path.
     *
     * 做什么：生成「字段为 null 时贡献 0，否则调用 member 取哈希」的三元表达式。
     * 什么时候用：fieldHashExpr 对引用类型字段（string/数组/命名类型/其它）使用。
     * 前置条件：member 为 "hashCode"/"hash"/"length"。
     * 为什么这样设计：null 字段必须贡献固定值而非抛出，才能让 equals 认为相等但字段为 null 的对象哈希一致。
     */
    private fun nullGuardedMemberHash(fieldAccess: JasPropertyAccess, member: String): JasExpression {
        // length 走属性访问（后端降为 ARRAYLENGTH）；其余走成员调用 field.member()。
        val useExpr: JasExpression = if (member == "length") {
            JasPropertyAccess(fieldAccess, "length")
        } else {
            JasCall(JasPropertyAccess(fieldAccess, member), emptyList())
        }
        return JasTernaryExpr(
            JasBinaryOp(fieldAccess, "==", JasNullLiteral),
            JasIntLiteral(0, "0"),
            useExpr
        )
    }

    /**
     * Late body generation for annotation-synthesized mappers
     * ([JasSyntheticMapper]). The mapper method signature was synthesized by the
     * annotation processing phase; here the simple property-to-property body is
     * lowered through the normal statement pipeline:
     *
     * ```
     * var result = new Target();
     * result.t1 = src.s1;
     * ...
     * return result;
     * ```
     *
     * 做什么：为 @Mapper 合成的方法生成体：新建目标对象、逐条属性赋值、返回它（两段式合成的第二段）。
     * 什么时候用：translateFunction 发现 func.syntheticMapper != null 时。
     * 前置条件：assignments 已在注解阶段校验通过（源属性存在、类型一致）。
     * 为什么这样设计：注解阶段只出签名与映射计划；体推迟到这里，经正常语句管线翻译，
     *   从而复用既有的 new/属性读写/返回 lowering，无需为 Mapper 另设一套后端逻辑。
     */
    private fun syntheticMapperBody(mapper: JasSyntheticMapper): List<JasStatement> {
        val resultName = "result$"
        val stmts = mutableListOf<JasStatement>()
        stmts.add(JasVariableStatement(resultName, mapper.targetType, JasNew(mapper.targetType, emptyList())))
        for (assignment in mapper.assignments) {
            stmts.add(JasExpressionStatement(
                JasAssignment(
                    JasPropertyAccess(JasIdentifier(resultName), assignment.targetProperty),
                    JasPropertyAccess(JasIdentifier(mapper.parameterName), assignment.sourceProperty)
                )
            ))
        }
        stmts.add(JasReturn(JasIdentifier(resultName)))
        return stmts
    }

    // ── class ──

    private fun translateClass(cls: JasClass, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val kind = if (cls.modifiers.contains("annotation")) ClassKind.ANNOTATION_CLASS
            else if (cls.modifiers.contains("data")) ClassKind.CLASS
            else if (cls.modifiers.contains("sealed")) ClassKind.CLASS
            else ClassKind.CLASS
        val visibility = if (cls.modifiers.contains("public")) DescriptorVisibilities.PUBLIC
            else DescriptorVisibilities.INTERNAL
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(cls.name),
            visibility,
            symbol,
            kind,
            if (cls.modifiers.contains("abstract") || cls.modifiers.contains("open")) Modality.OPEN else Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        // 登记类符号（按简单名），供后续类型映射/成员翻译查找。
        ctx.classSymbols[cls.name] = symbol
        // Track the enclosing class so `this.prop` and receiver-typed property
        // accesses resolve their field owner during member translation.
        // 记录当前类（JVM 风格限定名），使翻译类成员时 this.prop 与接收者类型属性访问能定位字段宿主。
        // 用 typeMapper.resolveNamedTypeName 统一解析：它会把点分包名换成 '/' 并尊重 import，
        // 因此多段包名（如 a.b.c）不会再生成 "a.b.c/Impl" 这种非法内部名（#12）。
        val qualifiedClassName = typeMapper.resolveNamedTypeName(cls.name)
        val previousClassName = ctx.currentClassName
        ctx.currentClassName = qualifiedClassName
        // Record implemented interfaces
        // 记录实现的接口（补全为 JVM 风格内部名）；导出给后端决定接口表/校验。
        // 接口名同样走中央解析（import/点分包名），否则会写出 "pkg/Sink" 非法名。
        val ifaceNames = cls.interfaces.mapNotNull { iface ->
            if (iface is JasNamedType) typeMapper.resolveNamedTypeName(iface.name) else null
        }
        ctx.interfaceNames[cls.name] = ifaceNames.toMutableList()
        // 泛型形参先建 IR 形参，再翻成员——保证成员签名里能引用它们。
        for (tp in cls.typeParameters) {
            val irTp = translateTypeParameter(tp, ctx)
            irCls.typeParameters = irCls.typeParameters + irTp
        }
        // 逐成员翻译；属性额外把其后备字段一并加入类声明（IR 要求字段独立于属性节点出现在类体里）。
        for (member in cls.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
                if (irMember is IrProperty && irMember.backingField != null) {
                    irCls.declarations.add(irMember.backingField!!)
                }
            }
        }
        // 解析父类内部名（无显式父类则 Object），补全为限定名后登记，
        // 供构造器 super() 委托与后端决定继承/描述符使用。
        // jvmInternalNameFromType 现已走中央解析（import/点分包名），返回的已是完整内部名。
        val qualifiedSuper = if (cls.superclass != null) typeMapper.jvmInternalNameFromType(cls.superclass) else "java/lang/Object"
        ctx.currentSuperclassName = qualifiedSuper
        ctx.classSuperclassNames[cls.name] = qualifiedSuper
        if (cls.constructors.isEmpty()) {
            // Generate a default no-arg constructor
            // 无显式构造器时补一个默认无参 <init>；否则类无法被 new（后端要求至少一个可调用构造器）。
            val defaultCtorSymbol = IrConstructorSymbolImpl()
            val defaultCtor = factory.createConstructor(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.special("<init>"),
                DescriptorVisibilities.PUBLIC,
                false, false, typeMapper.unitType(),
                defaultCtorSymbol, true, false, null
            )
            val defaultCtorBody = factory.createBlockBody(0, 0)
            defaultCtor.body = defaultCtorBody
            irCls.declarations.add(defaultCtor)
        } else {
            for (ctor in cls.constructors) {
                val irCtor = translateConstructor(ctor, symbol, ctx)
                irCls.declarations.add(irCtor)
            }
        }
        // 恢复外层上下文（支持嵌套类/匿名类），保证翻译兄弟声明时状态不串。
        ctx.currentSuperclassName = ""
        ctx.currentClassName = previousClassName
        return irCls
    }

    private fun translateConstructor(ctor: JasConstructor, classSymbol: IrClassSymbol, ctx: TranslationContext): IrConstructor {
        val ctorSymbol = IrConstructorSymbolImpl()
        val returnType = typeMapper.unitType()
        val irCtor = factory.createConstructor(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false,
            returnType,
            ctorSymbol,
            true, false, null
        )
        for ((i, param) in ctor.parameters.withIndex()) {
            val paramType = typeMapper.mapType(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name),
                paramType,
                param.vararg,
                paramSymbol,
                i,
                null, false, false, false
            )
            irCtor.valueParameters = irCtor.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }
        val body = irCtor.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
        // Emit delegate call (super() or this()) if present
        // 显式委托调用（super(...) / this(...)）必须在构造器体最前发射；这里把 this 作为第 0 实参，
        // 以便后端生成 ALOAD 0 作为接收者。
        val delegateCall = ctor.delegateCall
        if (delegateCall != null) {
            // 用特殊调用名标注委托目标：super:<父类内部名> 或 this；后端据此选择 invokespecial 目标。
            val delegateTarget = if (delegateCall.isSuper) "super:${ctx.currentSuperclassName}" else "this"
            val delegateSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[delegateSymbol] = "ctor_delegate:$delegateTarget"
            // Include `this` as first arg so backend emits ALOAD 0
            val allArgs = mutableListOf<IrExpression>()
            val thisGet = IrNodeFactory.createGetValue(0, 0, typeMapper.unitType(), IrValueParameterSymbolImpl(), null)
            allArgs.add(thisGet)
            allArgs.addAll(delegateCall.args.map { translateExpression(it, ctx) })
            val delegateCallExpr = IrNodeFactory.createCall(
                0, 0, typeMapper.unitType(), null,
                allArgs.toTypedArray(),
                emptyArray(), delegateSymbol, null
            )
            (body.statements as MutableList<IrStatement>).add(delegateCallExpr)
        }
        if (ctor.body != null) {
            scopedDefers(ctx) {
                for (stmt in ctor.body!!.statements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
                }
            }
        }
        irCtor.body = body
        return irCtor
    }

    // ── interface ──

    private fun translateInterface(iface: JasInterface, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(iface.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.INTERFACE,
            Modality.ABSTRACT,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[iface.name] = symbol
        // 登记「这是接口」：后端据此决定接口标志、默认方法/抽象方法处理，以及类型引用用接口描述符。
        ctx.interfaceDeclNames.add(iface.name)
        // Record extended interfaces
        // 记录继承的父接口（补全为 JVM 风格内部名），导出给后端生成接口表。
        // 父接口名走中央解析（import/点分包名），避免非法内部名。
        val ifaceNames = iface.extends.mapNotNull { ext ->
            if (ext is JasNamedType) typeMapper.resolveNamedTypeName(ext.name) else null
        }
        ctx.interfaceNames[iface.name] = ifaceNames.toMutableList()
        for (tp in iface.typeParameters) {
            val irTp = translateTypeParameter(tp, ctx)
            irCls.typeParameters = irCls.typeParameters + irTp
        }
        for (member in iface.members) {
            // An interface constant (`const MAX_ROWS: int32 = 100;`) becomes a
            // `public static final` field carrying its declared value. It must
            // not be silently dropped (the previous behavior), because a missing
            // declaration is exactly the kind of failure that hides a compiler
            // bug as a user error.
            // 接口常量降为 public static final 字段并携带声明初值；绝不能静默丢弃——
            // 缺失声明会把「编译器 bug」伪装成「用户错误」，正是要避免的失败模式。
            if (member is JasProperty && member.isConst) {
                irCls.declarations.add(translateInterfaceConstant(member, ctx))
                continue
            }
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    /**
     * Lowers an interface constant to a JVM `public static final` field.
     *
     * The declaration-site initializer is preserved on the [IrField] so the
     * backend can emit a `ConstantValue` attribute (for literals) or an
     * `<clinit>` assignment. The field symbol is registered globally *and* in
     * the static-field set so member reads (`Statement.MAX_ROWS`) lower to
     * `GETSTATIC` rather than `GETFIELD`.
     *
     * 做什么：把接口常量降为 JVM public static final 字段，并把符号登记进全局字段表与静态字段集合。
     * 什么时候用：translateInterface 遇到 const 属性时。
     * 前置条件：属性带声明类型；初值可有可无。
     * 为什么这样设计：把符号同时放进全局表与 staticFieldSymbols，后端读到 Statement.MAX_ROWS
     *   才会发 GETSTATIC 而非 GETFIELD；初值留在 IrField 上供后端发 ConstantValue 或 clinit 赋值。
     */
    private fun translateInterfaceConstant(prop: JasProperty, ctx: TranslationContext): IrField {
        val propType = typeMapper.mapType(prop.type)
        val fieldSymbol = IrFieldSymbolImpl()
        val irField = factory.createField(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PUBLIC,
            fieldSymbol,
            propType,
            true,   // isFinal
            true,   // isStatic
            false   // isLateinit
        )
        if (prop.initializer != null) {
            irField.initializer = factory.createExpressionBody(0, 0, translateExpression(prop.initializer!!, ctx))
        }
        ctx.fieldSymbols[prop.name] = fieldSymbol
        ctx.fieldTypes[prop.name] = propType
        if (ctx.currentClassName.isNotEmpty()) {
            ctx.fieldSymbolsByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = fieldSymbol
            ctx.fieldTypesByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = propType
        }
        ctx.staticFieldSymbols.add(fieldSymbol)
        return irField
    }

    // ── enum ──

    private fun translateEnum(enum: JasEnum, ctx: TranslationContext): IrClass {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(enum.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.ENUM_CLASS,
            Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[enum.name] = symbol
        for (const in enum.constants) {
            val irConst = translateEnumConstant(const, ctx)
            irCls.declarations.add(irConst)
        }
        for (member in enum.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    private fun translateEnumConstant(const: JasEnumConstant, ctx: TranslationContext): IrEnumEntry {
        val entrySymbol = IrEnumEntrySymbolImpl()
        return factory.createEnumEntry(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(const.name), entrySymbol)
    }

    private fun translateEnumEntry(decl: JasEnumConstant, ctx: TranslationContext): IrDeclaration {
        val entrySymbol = IrEnumEntrySymbolImpl()
        return factory.createEnumEntry(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(decl.name), entrySymbol)
    }

    // ── annotation type ──

    private fun translateAnnotationType(ann: JasAnnotationType, ctx: TranslationContext): IrClass? {
        val symbol = IrClassSymbolImpl()
        val irCls = factory.createClass(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(ann.name),
            DescriptorVisibilities.PUBLIC,
            symbol,
            ClassKind.ANNOTATION_CLASS,
            Modality.OPEN,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.classSymbols[ann.name] = symbol
        for (member in ann.members) {
            val irMember = translateAnnotationMember(member, ctx)
            if (irMember != null) {
                irCls.declarations.add(irMember)
            }
        }
        return irCls
    }

    private fun translateAnnotationMember(member: JasAnnotationMember, ctx: TranslationContext): IrDeclaration? {
        return null
    }

    // ── property ──

    private fun translateProperty(prop: JasProperty, classSymbol: IrClassSymbol, ctx: TranslationContext): IrProperty? {
        val propSymbol = IrPropertySymbolImpl()
        val propType = typeMapper.mapType(prop.type)
        val irProp = factory.createProperty(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PRIVATE,
            Modality.FINAL,
            propSymbol,
            true, false, false, false, false,
            null, false, false
        )
        val fieldSymbol = IrFieldSymbolImpl()
        val irField = factory.createField(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            DescriptorVisibilities.PRIVATE,
            fieldSymbol,
            propType,
            false,
            true,
            false
        )
        irProp.backingField = irField
        // 属性字段同时登记到「全局按名」和「按类」两张表：按类表用于从接收者类型定位字段宿主，
        // 避免两个类共用属性名时在后端撞成同一个字段（全局表仅作回退）。
        ctx.fieldSymbols[prop.name] = fieldSymbol
        ctx.fieldTypes[prop.name] = propType
        if (ctx.currentClassName.isNotEmpty()) {
            ctx.fieldSymbolsByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = fieldSymbol
            ctx.fieldTypesByClass.getOrPut(ctx.currentClassName) { mutableMapOf() }[prop.name] = propType
        }
        if (prop.getter != null) {
            val getterFunc = translatePropertyAccessor(prop.getter!!, prop.name, propType, isGetter = true, ctx)
            irProp.getter = getterFunc
        }
        if (prop.setter != null) {
            val setterFunc = translatePropertyAccessor(prop.setter!!, prop.name, propType, isGetter = false, ctx)
            irProp.setter = setterFunc
        }
        return irProp
    }

    private fun translatePropertyAccessor(
        accessor: JasPropertyAccessor,
        propName: String,
        propType: IrType,
        isGetter: Boolean,
        ctx: TranslationContext
    ): IrSimpleFunction {
        val symbol = IrSimpleFunctionSymbolImpl()
        val returnType = if (isGetter) propType else typeMapper.unitType()
        val accessorKind = if (isGetter) "getter" else "setter"
        val irFunc = factory.createSimpleFunction(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("<$accessorKind-$propName>"),
            DescriptorVisibilities.PUBLIC,
            false, false,
            returnType,
            Modality.FINAL,
            symbol,
            false, false, false, false, false,
            null, false
        )
        if (!isGetter) {
            val paramType = propType
            val paramSymbol = IrValueParameterSymbolImpl()
            val paramName = accessor.parameterName ?: "value"
            val irParam = factory.createValueParameter(
                0, 0,
                IrDeclarationOrigin.DEFINED,
                Name.identifier(paramName),
                paramType,
                false,
                paramSymbol,
                0,
                null, false, false, false
            )
            irFunc.valueParameters = listOf(irParam)
            ctx.symbols[paramName] = paramSymbol
            ctx.varTypes[paramName] = paramType
        }
        if (accessor.body != null) {
            val prevFunc = ctx.currentFunction
            ctx.currentFunction = irFunc
            val body = irFunc.body as? IrBlockBody ?: factory.createBlockBody(0, 0)
            scopedDefers(ctx) {
                for (stmt in accessor.body!!.statements) {
                    val irStmt = translateStatement(stmt, ctx)
                    if (irStmt != null) {
                        (body.statements as MutableList<IrStatement>).add(irStmt)
                    }
                }
            }
            irFunc.body = body
            ctx.currentFunction = prevFunc
        }
        ctx.functionSymbols["<$accessorKind-$propName>"] = symbol
        return irFunc
    }

    // ── type parameter ──

    private fun translateTypeParameter(tp: JasTypeParameter, ctx: TranslationContext): IrTypeParameter {
        val tpSymbol = IrTypeParameterSymbolImpl()
        val irTp = factory.createTypeParameter(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(tp.name),
            tpSymbol,
            Variance.INVARIANT,
            0, false
        )
        if (tp.bound != null) {
            irTp.superTypes = listOf(typeMapper.mapType(tp.bound))
        }
        return irTp
    }

    // ── statements ──

    // 语句分派：按 AST 语句节点类型路由到对应 translate*；未识别的返回 null（上层会跳过）。
    // 这一段等价于对 JasStatement sealed 层次的穷举式分派（Visitor 的集中版）。
    private fun translateStatement(stmt: JasStatement, ctx: TranslationContext): IrStatement? {
        return when (stmt) {
            is JasBlock -> translateBlock(stmt, ctx)
            is JasVariableStatement -> translateVariableStatement(stmt, ctx)
            is JasExpressionStatement -> translateExpressionStatement(stmt, ctx)
            is JasReturn -> translateReturn(stmt, ctx)
            is JasIf -> translateIf(stmt, ctx)
            is JasWhile -> translateWhile(stmt, ctx)
            is JasDoWhile -> translateDoWhile(stmt, ctx)
            is JasForStatement -> translateFor(stmt, ctx)
            is JasForInStatement -> translateForIn(stmt, ctx)
            is JasSwitch -> translateSwitch(stmt, ctx)
            is JasBreakStatement -> translateBreak(stmt, ctx)
            is JasContinueStatement -> translateContinue(stmt, ctx)
            is JasThrow -> translateThrow(stmt, ctx)
            is JasTry -> translateTry(stmt, ctx)
            is JasLabeledStatement -> translateStatement(stmt.statement, ctx)
            is JasDefer -> translateDefer(stmt, ctx)
            is JasLock -> translateLock(stmt, ctx)
            is JasAssert -> translateAssert(stmt, ctx)
            is JasYield -> translateYield(stmt, ctx)
            is JasDestructuringDeclaration -> translateDestructuringDeclaration(stmt, ctx)
            is JasMatch -> translateMatch(stmt, ctx)
            is JasStatementIncrement -> translateStatementIncrement(stmt, ctx)
            else -> null
        }
    }

    private fun translateBlock(block: JasBlock, ctx: TranslationContext): IrBlock {
        val body = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        for (stmt in block.statements) {
            val irStmt = translateStatement(stmt, ctx)
            if (irStmt != null) {
                (body.statements as MutableList<IrStatement>).add(irStmt)
            }
        }
        return body
    }

    private fun translateVariableStatement(stmt: JasVariableStatement, ctx: TranslationContext): IrVariable {
        // 若是 lambda / 方法引用初始化，先登记变量名到 lambda 表（供旧的「内联展开」路径识别）。
        // 前置条件：这些名字在变量作用域内唯一；越界/重名由前端保证。
        if (stmt.initializer is JasLambdaExpr) {
            ctx.lambdas[stmt.name] = stmt.initializer
            ctx.lambdaVarNames.add(stmt.name)
        }
        if (stmt.initializer is JasMethodReference) {
            ctx.lambdaVarNames.add(stmt.name)
        }
        val varSymbol = IrVariableSymbolImpl()
        // Translate the initializer once to avoid double-creating anonymous classes
        // 初始化表达式只翻译一次：否则 lambda 字面量会被求值两次，生成两个匿名类（符号不一致）。
        val translatedInit = if (stmt.initializer != null) translateExpression(stmt.initializer!!, ctx) else null
        // A closure assigned to a protocol-typed variable is lowered to an
        // anonymous class; the variable must carry that concrete class type so a
        // later protocol call routes to the generated `invoke` method instead of
        // dispatching on the (abstract) declared protocol interface.
        // 赋给协议类型变量的闭包会降级为匿名类；变量必须带「具体匿名类」类型，
        // 这样后续协议调用才会路由到生成的 invoke，而不是按（抽象）协议接口分派。
        val isClosureInit = stmt.initializer is JasLambdaExpr || stmt.initializer is JasMethodReference
        if (isClosureInit) {
            // Snapshot the just-generated closure's `invoke` return type under the
            // variable's name; the global `functionReturnTypes["invoke"]` entry is
            // overwritten by every subsequent lambda.
            // 把刚生成闭包的 invoke 返回类型按「变量名」快照下来；全局 invoke 条目会被后续每个 lambda 覆盖。
            ctx.functionReturnTypes["invoke"]?.let { ctx.lambdaInvokeReturnTypes[stmt.name] = it }
        }
        // 变量类型优先级：闭包初值用其具体类型 > 显式声明类型 > 初值类型 > Unit（兜底）。
        val varType = if (isClosureInit && translatedInit != null) translatedInit.type
            else if (stmt.type != null) typeMapper.mapType(stmt.type)
            else translatedInit?.type ?: typeMapper.unitType()
        val irVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.name),
            varType, varSymbol, !stmt.isConst, stmt.isConst, false
        )
        irVar.initializer = translatedInit
        ctx.symbols[stmt.name] = varSymbol
        ctx.varTypes[stmt.name] = varType
        return irVar
    }

    private fun translateExpressionStatement(stmt: JasExpressionStatement, ctx: TranslationContext): IrStatement {
        return translateExpression(stmt.expression, ctx)
    }

    private fun translateStatementIncrement(stmt: JasStatementIncrement, ctx: TranslationContext): IrStatement {
        return translatePostfixIncDecAsStatement(stmt.increment, ctx)
    }

    private fun translatePostfixIncDecAsStatement(expr: JasPostfixIncDec, ctx: TranslationContext): IrStatement {
        return translatePostfixIncDec(expr, ctx, needsResult = false)
    }

    private fun translateIf(stmt: JasIf, ctx: TranslationContext): IrWhen {
        val condition = translateExpression(stmt.condition, ctx)
        val thenResult = translateBlock(stmt.thenBody, ctx)
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, condition, thenResult))
        if (stmt.elseBody != null) {
            val elseResult = translateBlock(stmt.elseBody!!, ctx)
            irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseResult))
        }
        return irWhen
    }

    private fun translateWhile(stmt: JasWhile, ctx: TranslationContext): IrWhileLoop {
        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.condition = translateExpression(stmt.condition, ctx)
        irLoop.body = translateBlock(stmt.body, ctx)
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        return irLoop
    }

    private fun translateDoWhile(stmt: JasDoWhile, ctx: TranslationContext): IrDoWhileLoop {
        val irLoop = IrNodeFactory.createDoWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.DO_WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.body = translateBlock(stmt.body, ctx)
        irLoop.condition = translateExpression(stmt.condition, ctx)
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        return irLoop
    }

    private fun translateFor(stmt: JasForStatement, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)
        if (stmt.init != null) {
            val irInit = translateStatement(stmt.init!!, ctx)
            if (irInit != null) {
                (block.statements as MutableList<IrStatement>).add(irInit)
            }
        }
        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)
        irLoop.condition = if (stmt.condition != null) translateExpression(stmt.condition!!, ctx) else typeMapper.trueConst()
        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }
        if (stmt.update != null) {
            val updateExpr = stmt.update!!
            val updateStmt = if (updateExpr is JasPostfixIncDec) {
                translatePostfixIncDecAsStatement(updateExpr, ctx)
            } else {
                translateExpression(updateExpr, ctx)
            }
            (loopBody.statements as MutableList<IrStatement>).add(updateStmt)
        }
        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)
        return block
    }

    private fun translateForIn(stmt: JasForInStatement, ctx: TranslationContext): IrStatement {
        val iterableExpr = translateExpression(stmt.iterable, ctx)
        val desc = typeMapper.jvmDescriptorFromIrType(iterableExpr.type)
        // Arrays keep the index-based lowering. A declared object type is treated
        // as an instance of the iteration protocol (`hasNext()` / `next()`); any
        // other/unknown shape falls back to the array lowering to preserve the
        // previous behaviour.
        // 分派规则：数组描述符以 "[ " 开头 → 按下标循环；对象描述符以 "L" 开头 → 按迭代协议（hasNext/next）；
        // 其余/未知形态回退到数组 lowering，保持既有行为（不引入新的语法）。
        return when {
            desc.startsWith("[") -> translateForInArray(stmt, iterableExpr, ctx)
            desc.startsWith("L") -> translateForInIterator(stmt, iterableExpr, ctx)
            else -> translateForInArray(stmt, iterableExpr, ctx)
        }
    }

    private fun translateForInArray(stmt: JasForInStatement, iterableExpr: IrExpression, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

        val arrSymbol = IrVariableSymbolImpl()
        val arrVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("arr$"),
            iterableExpr.type, arrSymbol, true, false, false
        )
        arrVar.initializer = iterableExpr
        (block.statements as MutableList<IrStatement>).add(arrVar)

        val idxSymbol = IrVariableSymbolImpl()
        val idxVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("i$"),
            typeMapper.intType(), idxSymbol, true, false, false
        )
        idxVar.initializer = typeMapper.intConst(0)
        (block.statements as MutableList<IrStatement>).add(idxVar)

        val didRunSymbol = IrVariableSymbolImpl()
        val didRunVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("didRun$"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)

        val elemSymbol = IrVariableSymbolImpl()
        val elemType = arrayElementType(iterableExpr.type)
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)

        val sizeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[sizeSymbol] = "size"
        val sizeCall = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, arrSymbol, null)),
            emptyArray(), sizeSymbol, null
        )
        val lessSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[lessSymbol] = "<"
        irLoop.condition = IrNodeFactory.createCall(
            0, 0, typeMapper.boolType(), null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null),
                sizeCall
            ), emptyArray(), lessSymbol, null
        )

        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), didRunSymbol, IrStatementOrigin.EQ, typeMapper.trueConst())
        )

        val getSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = "get"
        elemVar.initializer = IrNodeFactory.createCall(
            0, 0, elemType, null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, iterableExpr.type, arrSymbol, null),
                IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null)
            ), emptyArray(), getSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(elemVar)

        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }

        val incSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[incSymbol] = "inc"
        val incCall = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null)),
            emptyArray(), incSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.intType(), idxSymbol, IrStatementOrigin.EQ, incCall)
        )

        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)
        if (stmt.thenBody != null || stmt.elseBody != null) {
            val afterLoop = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
            val didRun = IrNodeFactory.createGetValue(0, 0, typeMapper.boolType(), didRunSymbol, null)
            if (stmt.thenBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, didRun, translateBlock(stmt.thenBody, ctx)))
            }
            if (stmt.elseBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), translateBlock(stmt.elseBody, ctx)))
            }
            (block.statements as MutableList<IrStatement>).add(afterLoop)
        }
        return block
    }

    /**
     * Lowers `for (x in iterable)` over an object type using the standard-library
     * iteration protocol:
     *
     * ```
     * var it$ = iterable;
     * var didRun$ = false;
     * while (it$.hasNext()) {
     *     didRun$ = true;
     *     var x = it$.next();
     *     <body>
     * }
     * if (didRun$) { <then> } else { <else> }
     * ```
     *
     * The element type comes from the `next()` member discovered while lowering
     * the concrete iterable type. This consumes the protocol through the existing
     * `for ... in` syntax; no new grammar is introduced.
     *
     * 做什么：把「对象类型的 for-in」降为迭代协议（hasNext/next）循环，并支持 then/else（至少执行一次）。
     * 什么时候用：translateForIn 判定可迭代类型是对象（描述符以 L 开头）时。
     * 前置条件：具体可迭代类型的 next() 返回类型已进 memberReturnTypesByType（否则回退 Any）。
     * 为什么这样设计：不改语法即可消费标准库迭代协议；循环变量类型取自「具体类型」的 next()，
     *   避免全局 functionReturnTypes 的 last-writer-wins 冲突。
     */
    private fun translateForInIterator(stmt: JasForInStatement, iterableExpr: IrExpression, ctx: TranslationContext): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

        val itSymbol = IrVariableSymbolImpl()
        val itVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("it$"),
            iterableExpr.type, itSymbol, true, false, false
        )
        itVar.initializer = iterableExpr
        (block.statements as MutableList<IrStatement>).add(itVar)

        val didRunSymbol = IrVariableSymbolImpl()
        val didRunVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("didRun$"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)

        // Type the loop variable from the concrete iterable's `next()` member.
        val iterableClass = typeMapper.irTypeClassName(iterableExpr.type)
        val elemType = (iterableClass?.let { ctx.memberReturnTypesByType[it]?.get("next") })
            ?: ctx.functionReturnTypes["next"]
            ?: typeMapper.type("kotlin.Any")
        val elemSymbol = IrVariableSymbolImpl()
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        ctx.loopStack.add(irLoop)

        val hasNextSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[hasNextSymbol] = "v:hasNext"
        irLoop.condition = IrNodeFactory.createCall(
            0, 0, typeMapper.boolType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, itSymbol, null)),
            emptyArray(), hasNextSymbol, null
        )

        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), didRunSymbol, IrStatementOrigin.EQ, typeMapper.trueConst())
        )

        val nextSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[nextSymbol] = "v:next"
        elemVar.initializer = IrNodeFactory.createCall(
            0, 0, elemType, null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, itSymbol, null)),
            emptyArray(), nextSymbol, null
        )
        (loopBody.statements as MutableList<IrStatement>).add(elemVar)

        for (bodyStmt in stmt.body.statements) {
            val irBodyStmt = translateStatement(bodyStmt, ctx)
            if (irBodyStmt != null) {
                (loopBody.statements as MutableList<IrStatement>).add(irBodyStmt)
            }
        }

        irLoop.body = loopBody
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        (block.statements as MutableList<IrStatement>).add(irLoop)

        if (stmt.thenBody != null || stmt.elseBody != null) {
            val afterLoop = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
            val didRun = IrNodeFactory.createGetValue(0, 0, typeMapper.boolType(), didRunSymbol, null)
            if (stmt.thenBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, didRun, translateBlock(stmt.thenBody, ctx)))
            }
            if (stmt.elseBody != null) {
                afterLoop.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), translateBlock(stmt.elseBody, ctx)))
            }
            (block.statements as MutableList<IrStatement>).add(afterLoop)
        }
        return block
    }

    private fun arrayElementType(arrayType: IrType): IrType {
        return when (typeMapper.jvmDescriptorFromIrType(arrayType)) {
            "[I" -> typeMapper.intType()
            "[J" -> typeMapper.longType()
            "[F" -> typeMapper.floatType()
            "[D" -> typeMapper.doubleType()
            "[Z" -> typeMapper.boolType()
            "[B" -> typeMapper.mapTypeName("byte")
            "[S" -> typeMapper.mapTypeName("short")
            "[C" -> typeMapper.mapTypeName("char")
            else -> typeMapper.type("kotlin.Any")
        }
    }

    private fun translateSwitch(stmt: JasSwitch, ctx: TranslationContext): IrWhen {
        // switch 降为 when：每个 case 值生成一条 subject == value 分支，用相等调用实现（无跳转表优化）。
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.WHEN)
        val subject = translateExpression(stmt.expression, ctx)
        for (case in stmt.cases) {
            for (value in case.values) {
                val valExpr = translateExpression(value, ctx)
                val equalsSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[equalsSymbol] = "=="
                val cond = IrNodeFactory.createCall(
                    0, 0, typeMapper.boolType(), null,
                    arrayOf(subject, valExpr), emptyArray(), equalsSymbol, null
                )
                val result = translateBlock(case.body, ctx)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, cond, result))
            }
            if (case.values.isEmpty()) {
                val result = translateBlock(case.body, ctx)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), result))
            }
        }
        val hasDefault = stmt.cases.any { it.values.isEmpty() }
        // 无 default 时补一个「恒真且返回 Unit」的兜底分支，保证 when 总有分支命中（IR 需要一个最终 true 分支）。
        if (!hasDefault) {
            irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), typeMapper.unitExpr()))
        }
        return irWhen
    }

    private fun translateReturn(stmt: JasReturn, ctx: TranslationContext): IrReturn {
        var value = if (stmt.value != null) translateExpression(stmt.value!!, ctx) else typeMapper.unitExpr()
        // defer 展开：按声明的逆序（后声明先执行，LIFO）把已累积的 defer 体塞进一个块，
        // 最后再放返回值，使 defer 在 return 求值之后、真正返回之前执行。
        if (ctx.deferredBodies.isNotEmpty()) {
            val block = IrNodeFactory.createBlock(0, 0, value.type, null)
            for (body in ctx.deferredBodies.reversed()) {
                for (s in body.statements) {
                    (block.statements as MutableList<IrStatement>).add(s)
                }
            }
            (block.statements as MutableList<IrStatement>).add(value)
            value = block
        }
        val target = ctx.currentFunction?.symbol ?: IrSimpleFunctionSymbolImpl()
        return IrNodeFactory.createReturn(0, 0, value.type, value, target)
    }

    private fun translateBreak(stmt: JasBreakStatement, ctx: TranslationContext): IrStatement {
        // break 绑定到最近的循环（loopStack 栈顶）；无循环时退化为 no-op（防越界）。
        if (ctx.loopStack.isNotEmpty()) {
            val loop = ctx.loopStack.last()
            return IrNodeFactory.createBreak(0, 0, typeMapper.unitType(), loop)
        }
        return typeMapper.unitExpr()
    }

    private fun translateContinue(stmt: JasContinueStatement, ctx: TranslationContext): IrStatement {
        // continue 同理绑定最近循环；无循环时 no-op。
        if (ctx.loopStack.isNotEmpty()) {
            val loop = ctx.loopStack.last()
            return IrNodeFactory.createContinue(0, 0, typeMapper.unitType(), loop)
        }
        return typeMapper.unitExpr()
    }

    private fun translateThrow(stmt: JasThrow, ctx: TranslationContext): IrThrow {
        val value = translateExpression(stmt.expression, ctx)
        return IrNodeFactory.createThrow(0, 0, typeMapper.unitType(), value)
    }

    private fun translateTry(stmt: JasTry, ctx: TranslationContext): IrTry {
        val irTry = IrNodeFactory.createTry(0, 0, typeMapper.unitType())
        irTry.tryResult = translateBlock(stmt.body, ctx)
        for (catchClause in stmt.catches) {
            val paramType = typeMapper.mapType(catchClause.parameter.type)
            val paramSymbol = IrVariableSymbolImpl()
            val catchParam = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier(catchClause.parameter.name),
                paramType, paramSymbol, true, false, false
            )
            val irCatch = IrNodeFactory.createCatch(0, 0, catchParam, null)
            ctx.symbols[catchClause.parameter.name] = paramSymbol
            // Register the catch variable's declared type so `e.message()` and
            // other member accesses on the caught value resolve their receiver
            // type. Without this the receiver falls back to `Unit` and the
            // backend emits `L<package>/void;` as the owner (NoClassDefFoundError).
            // 必须登记 catch 变量的声明类型：否则 e.message() 等成员访问的接收者退化为 Unit，
            // 后端会发出 `L<包>/void;` 作为宿主（NoClassDefFoundError）。
            ctx.varTypes[catchClause.parameter.name] = paramType
            irCatch.result = translateBlock(catchClause.body, ctx)
            irTry.catches.add(irCatch)
        }
        if (stmt.finallyBody != null) {
            irTry.finallyExpression = translateBlock(stmt.finallyBody!!, ctx)
        }
        return irTry
    }

    private fun translateAssignmentAsStatement(stmt: JasAssignment, ctx: TranslationContext): IrStatement {
        return translateAssignment(stmt, ctx)
    }

    private fun translateDefer(stmt: JasDefer, ctx: TranslationContext): IrBlock {
        // defer 体先翻译成 IR 块并压栈；自身不产生运行时代码（返回空块），只在 return/yield 处逆序展开。
        val defBlock = translateBlock(stmt.body, ctx)
        ctx.deferredBodies.add(defBlock)
        return IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
    }

    /**
     * Runs [body] with a fresh `defer` scope, restoring the enclosing scope
     * afterwards. `defer` belongs strictly to the function that declares it;
     * without this the deferred bodies accumulate in the shared translation
     * context and leak into every later function's `return`.
     *
     * 做什么：在一个「全新 defer 作用域」里执行 body，结束后恢复外层 defer 栈。
     * 什么时候用：翻译函数/构造函数/lambda/属性访问器的体时（进入前调用）。
     * 前置条件：进入体之前 ctx.deferredBodies 保存的是外层的 defer。
     * 为什么这样设计：defer 只属于声明它的那个函数；不隔离的话延迟体会滞留在共享 ctx 里，
     *   泄漏到之后每个函数的 return 中（跨函数污染）。
     */
    private inline fun <T> scopedDefers(ctx: TranslationContext, body: () -> T): T {
        val enclosing = ctx.deferredBodies.toMutableList()
        ctx.deferredBodies.clear()
        try {
            return body()
        } finally {
            ctx.deferredBodies.clear()
            ctx.deferredBodies.addAll(enclosing)
        }
    }

    private fun translateLock(stmt: JasLock, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val monitorExpr = translateExpression(stmt.expression, ctx)
        val monitorSymbol = IrVariableSymbolImpl()
        val monitorVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("lock$"),
            monitorExpr.type, monitorSymbol, true, false, false
        )
        monitorVar.initializer = monitorExpr
        (block.statements as MutableList<IrStatement>).add(monitorVar)
        val tryBlock = IrNodeFactory.createTry(0, 0, typeMapper.unitType())
        tryBlock.tryResult = translateBlock(stmt.body, ctx)
        tryBlock.finallyExpression = typeMapper.unitExpr()
        (block.statements as MutableList<IrStatement>).add(tryBlock)
        return block
    }

    private fun translateAssert(stmt: JasAssert, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val cond = translateExpression(stmt.condition, ctx)
        val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
        val thenBlock = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val assertErrorCtor = IrNodeFactory.createConstructorCall(
            0, 0, typeMapper.type("java.lang.AssertionError"), null,
            if (stmt.message != null) arrayOf(translateExpression(stmt.message!!, ctx)) else emptyArray(),
            emptyArray(), IrConstructorSymbolImpl(), SourceElement.NO_SOURCE, 0
        )
        val throwStmt = IrNodeFactory.createThrow(0, 0, typeMapper.unitType(), assertErrorCtor)
        (thenBlock.statements as MutableList<IrStatement>).add(throwStmt)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.falseConst(), thenBlock))
        (block.statements as MutableList<IrStatement>).add(irWhen)
        return block
    }

    private fun translateYield(stmt: JasYield, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val value = translateExpression(stmt.expression, ctx)
        val yieldSymbol = IrVariableSymbolImpl()
        val yieldVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("yield$"),
            value.type, yieldSymbol, true, false, false
        )
        yieldVar.initializer = value
        (block.statements as MutableList<IrStatement>).add(yieldVar)
        var retVal: IrExpression = IrNodeFactory.createGetValue(0, 0, value.type, yieldSymbol, null)
        if (ctx.deferredBodies.isNotEmpty()) {
            val retBlock = IrNodeFactory.createBlock(0, 0, value.type, null)
            for (body in ctx.deferredBodies.reversed()) {
                for (s in body.statements) {
                    (retBlock.statements as MutableList<IrStatement>).add(s)
                }
            }
            (retBlock.statements as MutableList<IrStatement>).add(retVal)
            retVal = retBlock
        }
        val target = ctx.currentFunction?.symbol ?: IrSimpleFunctionSymbolImpl()
        (block.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createReturn(0, 0, retVal.type, retVal, target)
        )
        return block
    }

    private fun translateDestructuringDeclaration(stmt: JasDestructuringDeclaration, ctx: TranslationContext): IrBlock {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        val initializer = translateExpression(stmt.initializer, ctx)
        val arrSymbol = IrVariableSymbolImpl()
        val arrVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("destr$"),
            typeMapper.unitType(), arrSymbol, true, false, false
        )
        arrVar.initializer = initializer
        (block.statements as MutableList<IrStatement>).add(arrVar)
        for ((i, binding) in stmt.bindings.withIndex()) {
            val varType = typeMapper.mapType(binding.type)
            val varSymbol = IrVariableSymbolImpl()
            val getSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[getSymbol] = "get"
            val getCall = IrNodeFactory.createCall(
                0, 0, varType, null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, typeMapper.unitType(), arrSymbol, null),
                    typeMapper.intConst(i)
                ), emptyArray(), getSymbol, null
            )
            val irVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(binding.name),
                varType, varSymbol, true, false, false
            )
            irVar.initializer = getCall
            ctx.symbols[binding.name] = varSymbol
            (block.statements as MutableList<IrStatement>).add(irVar)
        }
        return block
    }

    private fun translateMatch(stmt: JasMatch, ctx: TranslationContext): IrWhen {
        val subject = translateExpression(stmt.expression, ctx)
        val resultType = typeMapper.unitType()
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.WHEN)
        for (case in stmt.cases) {
            when (case.pattern) {
                is JasWildcardPattern -> {
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
                is JasIntLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    if (case.guard != null) {
                        val guardExpr = translateExpression(case.guard!!, ctx)
                        val andSymbol = IrSimpleFunctionSymbolImpl()
                        ctx.callNames[andSymbol] = "&&"
                        val combinedCond = IrNodeFactory.createCall(
                            0, 0, typeMapper.boolType(), null,
                            arrayOf(equalsCall, guardExpr), emptyArray(),
                            andSymbol, null
                        )
                        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, combinedCond, body))
                    } else {
                        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                    }
                }
                is JasStringLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                }
                is JasBoolLiteral -> {
                    val patternExpr = translateExpression(case.pattern as JasExpression, ctx)
                    val equalsSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[equalsSymbol] = "=="
                    val equalsCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.boolType(), null,
                        arrayOf(subject, patternExpr), emptyArray(), equalsSymbol, null
                    )
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, equalsCall, body))
                }
                is JasIdentifier -> {
                    val body = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                    val idName = (case.pattern as JasIdentifier).name
                    val bindingSymbol = IrVariableSymbolImpl()
                    val bindingVar = IrNodeFactory.createVariable(
                        0, 0, IrDeclarationOrigin.DEFINED,
                        Name.identifier(idName),
                        typeMapper.unitType(), bindingSymbol, true, false, false
                    )
                    bindingVar.initializer = subject
                    (body.statements as MutableList<IrStatement>).add(bindingVar)
                    ctx.symbols[idName] = bindingSymbol
                    val caseBody = translateBlock(case.body, ctx)
                    for (s in caseBody.statements) {
                        (body.statements as MutableList<IrStatement>).add(s)
                    }
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
                else -> {
                    val body = translateBlock(case.body, ctx)
                    irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), body))
                }
            }
        }
        return irWhen
    }

    // ── expressions ──

    private fun translateExpression(expr: JasExpression, ctx: TranslationContext): IrExpression {
        // JasMatch extends JasStatement, not JasExpression, so handled in translateStatement
        return when (expr) {
            is JasIntLiteral -> {
                val raw = expr.raw
                if (raw.endsWith("L") || raw.endsWith("l")) {
                    typeMapper.longConst(expr.value)
                } else {
                    typeMapper.intConst(expr.value.toInt())
                }
            }
            is JasFloatLiteral -> when {
                expr.raw.contains('.') || expr.raw.contains('e') || expr.raw.contains('E') -> typeMapper.doubleConst(expr.value)
                else -> typeMapper.floatConst(expr.value.toFloat())
            }
            is JasStringLiteral -> typeMapper.stringConst(expr.text)
            is JasCharLiteral -> typeMapper.charConst(expr.value.toChar())
            is JasBoolLiteral -> typeMapper.boolConst(expr.value)
            is JasNullLiteral -> typeMapper.nullConst()
            is JasIdentifier -> translateIdentifier(expr, ctx)
            is JasBinaryOp -> translateBinaryOp(expr, ctx)
            is JasUnaryOp -> translateUnaryOp(expr, ctx)
            is JasCall -> translateCall(expr, ctx)
            is JasPropertyAccess -> translatePropertyAccess(expr, ctx)
            is JasArrayAccess -> translateArrayAccess(expr, ctx)
            is JasNew -> translateNew(expr, ctx)
            is JasArrayCreation -> translateArrayCreation(expr, ctx)
            is JasLambdaExpr -> translateLambda(expr, ctx)
            is JasMethodReference -> translateMethodReference(expr, ctx)
            is JasInstanceOfExpr -> translateInstanceOf(expr, ctx)
            is JasCastExpr -> translateCast(expr, ctx)
            is JasNullCoalescing -> translateNullCoalescing(expr, ctx)
            is JasTernaryExpr -> translateTernary(expr, ctx)
            is JasStringTemplate -> translateStringTemplate(expr, ctx)
            is JasDictLiteral -> {
                val resultType = typeMapper.type("java.util.HashMap")
                val block = IrNodeFactory.createBlock(0, 0, resultType, null)
                val ctorSymbol = IrConstructorSymbolImpl()
                val irCtor = IrNodeFactory.createConstructorCall(
                    0, 0, resultType, null,
                    emptyArray(), emptyArray(), ctorSymbol,
                    SourceElement.NO_SOURCE, 0
                )
                val mapSymbol = IrVariableSymbolImpl()
                val mapVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("map$"),
                    resultType, mapSymbol, true, false, false
                )
                mapVar.initializer = irCtor
                (block.statements as MutableList<IrStatement>).add(mapVar)
                for (entry in expr.entries) {
                    val key = translateExpression(entry.key, ctx)
                    val value = translateExpression(entry.value, ctx)
                    val putSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[putSymbol] = "put"
                    val putCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.unitType(), null,
                        arrayOf(
                            IrNodeFactory.createGetValue(0, 0, resultType, mapSymbol, null),
                            key, value
                        ), emptyArray(), putSymbol, null
                    )
                    (block.statements as MutableList<IrStatement>).add(putCall)
                }
                (block.statements as MutableList<IrStatement>).add(
                    IrNodeFactory.createGetValue(0, 0, resultType, mapSymbol, null)
                )
                block
            }
            is JasAssignment -> translateAssignment(expr, ctx)
            is JasAssignmentIncrement -> translateAssignmentIncrement(expr, ctx)
            is JasPostfixIncDec -> translatePostfixIncDec(expr, ctx, needsResult = true)
            else -> typeMapper.unitExpr()
        }
    }

    private fun translateIdentifier(expr: JasIdentifier, ctx: TranslationContext): IrGetValue {
        val foundSymbol = ctx.symbols[expr.name]
        val symbol = foundSymbol ?: IrValueParameterSymbolImpl()
        val type = ctx.varTypes[expr.name]
            // `this` (and `super`) is not a declared local; its receiver type is
            // the enclosing class. Without this the receiver defaults to `Unit`
            // and a member call `this.m()` lowers to `invokevirtual <pkg>/void.m`.
            ?: if ((expr.name == "this" || expr.name == "super") && ctx.currentClassName.isNotEmpty()) {
                typeMapper.type(ctx.currentClassName)
            } else {
                typeMapper.unitType()
            }
        return IrNodeFactory.createGetValue(0, 0, type, symbol, IrStatementOrigin.GET_PROPERTY)
    }

    private fun translateBinaryOp(expr: JasBinaryOp, ctx: TranslationContext): IrExpression {
        when (expr.op) {
            "&&" -> {
                val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.boolType(), IrStatementOrigin.WHEN)
                val leftExpr = translateExpression(expr.left, ctx)
                val rightExpr = translateExpression(expr.right, ctx)
                val thenBlock = IrNodeFactory.createBlock(0, 0, typeMapper.boolType(), null)
                (thenBlock.statements as MutableList<IrStatement>).add(rightExpr)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, leftExpr, thenBlock))
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), typeMapper.falseConst()))
                return irWhen
            }
            "||" -> {
                val irWhen = IrNodeFactory.createWhen(0, 0, typeMapper.boolType(), IrStatementOrigin.WHEN)
                val leftExpr = translateExpression(expr.left, ctx)
                val rightExpr = translateExpression(expr.right, ctx)
                val elseBlock = IrNodeFactory.createBlock(0, 0, typeMapper.boolType(), null)
                (elseBlock.statements as MutableList<IrStatement>).add(rightExpr)
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, leftExpr, typeMapper.trueConst()))
                irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseBlock))
                return irWhen
            }
            else -> {
                val left = translateExpression(expr.left, ctx)
                val right = translateExpression(expr.right, ctx)
                val resultType = when (expr.op) {
                    "==", "!=", "<", ">", "<=", ">=" -> typeMapper.boolType()
                    else -> left.type
                }
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = expr.op
                val call = IrNodeFactory.createCall(
                    0, 0, resultType, IrStatementOrigin.PLUS,
                    arrayOf(left, right), emptyArray(),
                    stubSymbol, null
                )
                return call
            }
        }
    }

    private fun translateUnaryOp(expr: JasUnaryOp, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.operand, ctx)
        val funcName = when (expr.op) {
            "-" -> "negate"
            "!" -> "not"
            "~" -> "inv"
            "+" -> "unaryPlus"
            "++" -> "inc"
            "--" -> "dec"
            else -> "unknown"
        }
        val symbol = ctx.functionSymbols[funcName] ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = funcName
        return IrNodeFactory.createCall(
            0, 0, operand.type, null,
            arrayOf(operand), emptyArray(), symbol, null
        )
    }

    /**
     * Emits a virtual `invoke` call on a closure variable (the anonymous class
     * produced for a lambda or method reference). Shared by direct lambda calls
     * (`f(1)`) and function-object protocol calls (`f.apply(1)`).
     */
    private fun emitClosureInvoke(
        receiverName: String,
        args: List<JasExpression>,
        ctx: TranslationContext
    ): IrExpression {
        val receiverType = ctx.varTypes[receiverName] ?: typeMapper.unitType()
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[invokeSymbol] = "invoke_lambda"
        val receiver = IrNodeFactory.createGetValue(
            0, 0, receiverType, ctx.symbols[receiverName] ?: IrValueParameterSymbolImpl(),
            IrStatementOrigin.GET_PROPERTY
        )
        val argExprs = args.map { translateExpression(it, ctx) }.toTypedArray()
        val returnType = ctx.lambdaInvokeReturnTypes[receiverName]
            ?: ctx.functionReturnTypes["invoke"]
            ?: typeMapper.unitType()
        val call = IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            argExprs, emptyArray(), invokeSymbol, null
        )
        (call as? org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl)?.dispatchReceiver = receiver
        return call
    }

    private fun translateCall(expr: JasCall, ctx: TranslationContext): IrExpression {
        val name = when (expr.target) {
            is JasIdentifier -> expr.target.name
            is JasPropertyAccess -> expr.target.property
            else -> "invoke"
        }
        if (expr.target is JasIdentifier && name in ctx.lambdaVarNames) {
            // Lambda variable call: generate virtual invoke on the closure object
            return emitClosureInvoke(name, expr.args, ctx)
        }
        // Function-object protocol call: a lambda stored in a variable is lowered
        // to an anonymous class. A call to the protocol's method name on that
        // variable (`m.apply`, `p.test`, `r.reduce`, `c.compare`, `a.accept`) is
        // routed to the closure's generated `invoke` method. The protocol itself
        // is semantic, not a JVM interface dispatch.
        if (expr.target is JasPropertyAccess && expr.target.property in PROTOCOL_METHODS) {
            val receiverTarget = expr.target.target
            if (receiverTarget is JasIdentifier && receiverTarget.name in ctx.lambdaVarNames) {
                return emitClosureInvoke(receiverTarget.name, expr.args, ctx)
            }
        }
        // Legacy inline path for lambdas (backward compat during transition)
        if (expr.target is JasIdentifier) {
            val lambda = ctx.lambdas[name]
            if (lambda != null) {
                return translateLambdaInvocation(lambda, expr.args, ctx)
            }
        }
        val symbol = ctx.functionSymbols[name]
            ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = if (expr.target is JasPropertyAccess) "v:$name" else name
        val args = mutableListOf<IrExpression>()
        if (expr.target is JasPropertyAccess) {
            args.add(translateExpression(expr.target.target, ctx))
        }
        args.addAll(expr.args.map { translateExpression(it, ctx) }.toTypedArray())
        // Member-call arguments are widened to the declared parameter type when
        // the declared parameter is `any` (java/lang/Object) but the argument
        // has a more specific type. Without this the backend derives the call
        // descriptor from the *argument* type and misses the `Object`-typed
        // parameter (e.g. synthesized `equals(other: any)`).
        if (expr.target is JasPropertyAccess) {
            val declaredParams = ctx.functionParamTypes[name]
            if (declaredParams != null && declaredParams.size == expr.args.size) {
                for (i in expr.args.indices) {
                    val declared = declaredParams[i]
                    val argExpr = args[i + 1]
                    if (typeMapper.jvmDescriptorFromIrType(declared) == "Ljava/lang/Object;" &&
                        typeMapper.jvmDescriptorFromIrType(argExpr.type) != "Ljava/lang/Object;"
                    ) {
                        args[i + 1] = IrTypeOperatorCallImpl(
                            0, 0, declared, IrTypeOperator.CAST, declared, argExpr
                        )
                    }
                }
            }
        }
        val irArgs = args.toTypedArray()
        // Compute the call's return type. Prefer the *receiver-typed* member index
        // (`memberReturnTypesByType`) so a member call is typed by the declared type
        // of its receiver, rather than by whichever same-named function was
        // translated globally last. Without this, splitting an implementation into
        // its own file can let an unrelated declaration win (e.g. `Iterator<T>.next()`
        // clobbering `ResultSet.next()` when they are in different files), silently
        // producing a wrong call descriptor and a VerifyError.
        // 中文：优先用「接收者声明类型」索引取返回类型，避免全局同名 last-writer-wins
        // 把无关签名（如 Iterator.next）串到 ResultSet.next 上；接收者类型未知时才回退全局表。
        val memberReturnType = if (expr.target is JasPropertyAccess) {
            args.firstOrNull()?.type?.let { receiverType ->
                typeMapper.irTypeClassName(receiverType)?.let { owner ->
                    ctx.memberReturnTypesByType[owner]?.get(name)
                }
            }
        } else null
        val baseReturnType = memberReturnType ?: ctx.functionReturnTypes[name] ?: typeMapper.unitType()
        val returnType = if (expr.inferredTypeArgs != null && expr.inferredTypeArgs!!.isNotEmpty()) {
            substituteIrType(baseReturnType, expr.inferredTypeArgs!!)
        } else {
            baseReturnType
        }
        return IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            irArgs, emptyArray<IrType>(), symbol, null
        )
    }

    private fun substituteIrType(
        type: IrType,
        inferredArgs: Map<String, JasType>
    ): IrType {
        if (type is IrSimpleType) {
            val classifier = type.classifier
            if (classifier is IrTypeParameterSymbol) {
                val tpName = try { classifier.owner.name.toString() } catch (e: Exception) { null }
                if (tpName != null) {
                    val jasArg = inferredArgs[tpName]
                    if (jasArg != null) {
                        return typeMapper.mapType(jasArg)
                    }
                }
            }
        }
        return type
    }

    private fun translateLambdaInvocation(lambda: JasLambdaExpr, args: List<JasExpression>, ctx: TranslationContext): IrExpression {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.INVOKE)
        val previousSymbols = mutableMapOf<String, IrValueSymbol?>()
        val previousTypes = mutableMapOf<String, IrType?>()

        for ((index, param) in lambda.parameters.withIndex()) {
            val argExpr = args.getOrNull(index)?.let { translateExpression(it, ctx) } ?: typeMapper.unitExpr()
            val paramType = param.type?.let { typeMapper.mapType(it) } ?: argExpr.type
            val paramSymbol = IrVariableSymbolImpl()
            val paramVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(param.name),
                paramType, paramSymbol, true, false, false
            )
            paramVar.initializer = argExpr
            (block.statements as MutableList<IrStatement>).add(paramVar)
            previousSymbols[param.name] = ctx.symbols[param.name]
            previousTypes[param.name] = ctx.varTypes[param.name]
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }

        val result = when (val last = lambda.body.statements.lastOrNull()) {
            is JasExpressionStatement -> translateExpression(last.expression, ctx)
            is JasReturn -> last.value?.let { translateExpression(it, ctx) } ?: typeMapper.unitExpr()
            else -> typeMapper.unitExpr()
        }

        for (param in lambda.parameters) {
            val oldSymbol = previousSymbols[param.name]
            val oldType = previousTypes[param.name]
            if (oldSymbol != null) ctx.symbols[param.name] = oldSymbol else ctx.symbols.remove(param.name)
            if (oldType != null) ctx.varTypes[param.name] = oldType else ctx.varTypes.remove(param.name)
        }

        (block.statements as MutableList<IrStatement>).add(result)
        return IrNodeFactory.createBlock(0, 0, result.type, block.origin).also {
            (it.statements as MutableList<IrStatement>).addAll(block.statements)
        }
    }

    private fun translatePropertyAccess(expr: JasPropertyAccess, ctx: TranslationContext): IrExpression {
        val target = translateExpression(expr.target, ctx)
        val fieldSymbol = resolveFieldSymbol(expr.property, expr.target, target, ctx)
        val fieldType = resolveFieldType(expr.property, expr.target, target, ctx) ?: typeMapper.unitType()
        val irGetField = IrNodeFactory.createGetField(
            0, 0, fieldType, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY
        )
        irGetField.receiver = target
        return irGetField
    }

    /**
     * JVM-style class key identifying the field owner for a property receiver.
     * `this` / `super` map to the enclosing class; everything else uses the
     * receiver's declared IR type.
     */
    private fun receiverClassKey(target: JasExpression, irReceiver: IrExpression, ctx: TranslationContext): String? {
        if (target is JasIdentifier && (target.name == "this" || target.name == "super")) {
            return ctx.currentClassName.ifEmpty { null }
        }
        return typeMapper.irTypeClassName(irReceiver.type)
    }

    /**
     * Resolves the field symbol for a property access from the receiver's type,
     * falling back to the global by-name index when the receiver type cannot be
     * mapped to a declared class. This keeps two classes that share a property
     * name (e.g. a mapper source and target) from colliding.
     */
    private fun resolveFieldSymbol(
        property: String,
        target: JasExpression,
        irReceiver: IrExpression,
        ctx: TranslationContext
    ): IrFieldSymbol {
        val key = receiverClassKey(target, irReceiver, ctx)
        if (key != null) {
            ctx.fieldSymbolsByClass[key]?.get(property)?.let { return it }
        }
        return ctx.fieldSymbols[property] ?: IrFieldSymbolImpl()
    }

    private fun resolveFieldType(
        property: String,
        target: JasExpression,
        irReceiver: IrExpression,
        ctx: TranslationContext
    ): IrType? {
        if (property == "length" && isArrayIrType(irReceiver.type)) return typeMapper.intType()
        val key = receiverClassKey(target, irReceiver, ctx)
        if (key != null) {
            ctx.fieldTypesByClass[key]?.get(property)?.let { return it }
        }
        return ctx.fieldTypes[property]
    }

    private fun isArrayIrType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type).startsWith("[")

    private fun translateArrayAccess(expr: JasArrayAccess, ctx: TranslationContext): IrExpression {
        val target = translateExpression(expr.target, ctx)
        val index = translateExpression(expr.index, ctx)
        val getSymbol = ctx.functionSymbols["get"] ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = "get"
        return IrNodeFactory.createCall(
            0, 0, typeMapper.unitType(), null,
            arrayOf(target, index), emptyArray(), getSymbol, null
        )
    }

    private fun translateNew(expr: JasNew, ctx: TranslationContext): IrExpression {
        val ctorSymbol = IrConstructorSymbolImpl()
        val type = typeMapper.mapType(expr.type)
        val args = expr.args.map { translateExpression(it, ctx) }.toTypedArray()
        ctx.constructorTypes[ctorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, type, null,
            args, emptyArray(), ctorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateArrayCreation(expr: JasArrayCreation, ctx: TranslationContext): IrExpression {
        val resultType = typeMapper.mapType(JasArrayType(expr.type))
        // 数组元素描述符从已解析的数组类型反推（去掉前导 '['），复用 mapType 的包/import 解析；
        // 若直接用 jvmDescriptorFromType(expr.type) 会丢掉包名，导致用户类对象数组
        // 生成无包名的 ANEWARRAY（#1）。
        val arrayDesc = typeMapper.jvmDescriptorFromIrType(resultType)
        val elemDesc = if (arrayDesc.startsWith("[")) arrayDesc.substring(1)
            else typeMapper.jvmDescriptorFromType(expr.type)
        if (expr.init is JasArrayInitValues) {
            val values = expr.init.values
            val block = IrNodeFactory.createBlock(0, 0, resultType, null)
            val ctorSymbol = IrConstructorSymbolImpl()
            ctx.constructorTypes[ctorSymbol] = "array:$elemDesc"
            val dimExpr = typeMapper.intConst(values.size)
            val createArray = IrNodeFactory.createConstructorCall(
                0, 0, resultType, null,
                arrayOf(dimExpr), emptyArray(), ctorSymbol,
                SourceElement.NO_SOURCE, 0
            )
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(
                0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("arr$"),
                resultType, arrSymbol, true, false, false
            )
            arrVar.initializer = createArray
            (block.statements as MutableList<IrStatement>).add(arrVar)
            for ((i, v) in values.withIndex()) {
                val valExpr = translateExpression(v, ctx)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, resultType, arrSymbol, null),
                        typeMapper.intConst(i),
                        valExpr
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
            }
            (block.statements as MutableList<IrStatement>).add(
                IrNodeFactory.createGetValue(0, 0, resultType, arrSymbol, null)
            )
            return block
        } else {
            val ctorSymbol = IrConstructorSymbolImpl()
            ctx.constructorTypes[ctorSymbol] = "array:$elemDesc"
            val dims = expr.dims.map { translateExpression(it, ctx) }
            return IrNodeFactory.createConstructorCall(
                0, 0, resultType, null,
                dims.toTypedArray(), emptyArray(), ctorSymbol,
                SourceElement.NO_SOURCE, 0
            )
        }
    }

    private fun translateLambda(expr: JasLambdaExpr, ctx: TranslationContext): IrExpression {
        val lambdaId = ctx.lambdaIdCounter++
        val className = "Lambda\$$lambdaId"
        val classSymbol = IrClassSymbolImpl()
        val irClass = factory.createClass(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(className),
            DescriptorVisibilities.PUBLIC, classSymbol,
            ClassKind.CLASS, Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.currentFile?.declarations?.add(irClass)

        // Add a default constructor for the anonymous class
        val ctorSymbol = IrConstructorSymbolImpl()
        val irCtor = factory.createConstructor(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false, typeMapper.unitType(),
            ctorSymbol, true, false, null
        )
        val ctorBody = factory.createBlockBody(0, 0)
        irCtor.body = ctorBody
        irClass.declarations.add(irCtor)

        // Determine return type from the last expression
        val inferredReturnType = when (val last = expr.body.statements.lastOrNull()) {
            is JasExpressionStatement -> typeMapper.mapType(expr.parameters.firstOrNull()?.type)
            is JasReturn -> last.value?.let { typeMapper.mapType(expr.parameters.firstOrNull()?.type) }
            else -> typeMapper.unitType()
        }
        val returnType = typeMapper.mapType(expr.parameters.firstOrNull()?.type) ?: typeMapper.unitType()

        // Determine the lambda return type from the body
        val lambdaReturnType = when (val last = expr.body.statements.lastOrNull()) {
            is JasReturn -> {
                val returnVal = last.value
                if (returnVal != null) {
                    // Infer from literal types
                    when (returnVal) {
                        is JasIntLiteral -> typeMapper.intType()
                        is JasFloatLiteral -> typeMapper.doubleType()
                        is JasStringLiteral -> typeMapper.stringType()
                        is JasBoolLiteral -> typeMapper.boolType()
                        is JasBinaryOp -> typeMapper.intType()
                        else -> typeMapper.intType()
                    }
                } else typeMapper.unitType()
            }
            // A lambda block whose last statement is a plain expression yields no
            // value (block expressions are not implicit returns in Jasper); its
            // `invoke` is void. This is what makes `Action.accept` lambdas valid.
            is JasExpressionStatement -> typeMapper.unitType()
            else -> typeMapper.unitType()
        }
        
        // Create the invoke method
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        val irFunc = factory.createSimpleFunction(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier("invoke"),
            DescriptorVisibilities.PUBLIC, false, false, lambdaReturnType,
            Modality.FINAL, invokeSymbol,
            false, false, false, false, false, null, false
        )
        ctx.functionSymbols["invoke"] = invokeSymbol
        ctx.functionReturnTypes["invoke"] = lambdaReturnType

        // Add parameters to invoke method
        for ((i, param) in expr.parameters.withIndex()) {
            val paramType = typeMapper.mapType(param.type)
            val paramSymbol = IrValueParameterSymbolImpl()
            val irParam = factory.createValueParameter(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier(param.name), paramType,
                param.vararg, paramSymbol, i, null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols[param.name] = paramSymbol
            ctx.varTypes[param.name] = paramType
        }

        // Translate lambda body
        val body = factory.createBlockBody(0, 0)
        val prevFunc = ctx.currentFunction
        ctx.currentFunction = irFunc
        scopedDefers(ctx) {
            for (stmt in expr.body.statements) {
                val irStmt = translateStatement(stmt, ctx)
                if (irStmt != null) {
                    (body.statements as MutableList<IrStatement>).add(irStmt)
                }
            }
        }
        // Add return if needed
        val lastStmt = expr.body.statements.lastOrNull()
        if (lastStmt !is JasReturn && !body.statements.any { it is IrReturn }) {
            val retVal = when (lastStmt) {
                is JasExpressionStatement -> translateExpression(lastStmt.expression, ctx)
                else -> typeMapper.unitExpr()
            }
            if (retVal !is IrConstImpl || retVal.kind != IrConstKind.Null) {
                // For expression-style lambdas, return the last expression
            }
        }
        ctx.currentFunction = prevFunc
        irFunc.body = body
        irClass.declarations.add(irFunc)

        // Create a constructor call to instantiate the anonymous class
        val instanceCtorSymbol = IrConstructorSymbolImpl()
        val ctorReturnType = typeMapper.mapType(JasNamedType(className))
        ctx.constructorTypes[instanceCtorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, ctorReturnType, null,
            emptyArray(), emptyArray(), instanceCtorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateMethodReference(expr: JasMethodReference, ctx: TranslationContext): IrExpression {
        val lambdaId = ctx.lambdaIdCounter++
        val className = "Lambda\$$lambdaId"
        val classSymbol = IrClassSymbolImpl()
        val irClass = factory.createClass(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier(className),
            DescriptorVisibilities.PUBLIC, classSymbol,
            ClassKind.CLASS, Modality.FINAL,
            false, false, false, false, false, false, false, false,
            SourceElement.NO_SOURCE
        )
        ctx.currentFile?.declarations?.add(irClass)

        val ctorSymbol = IrConstructorSymbolImpl()
        val irCtor = factory.createConstructor(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.special("<init>"),
            DescriptorVisibilities.PUBLIC,
            false, false, typeMapper.unitType(),
            ctorSymbol, true, false, null
        )
        val ctorBody = factory.createBlockBody(0, 0)
        irCtor.body = ctorBody
        irClass.declarations.add(irCtor)

        val paramTypes = ctx.functionParamTypes[expr.method] ?: emptyList()
        val returnType = ctx.functionReturnTypes[expr.method] ?: typeMapper.unitType()
        val invokeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.functionSymbols["invoke"] = invokeSymbol
        ctx.functionReturnTypes["invoke"] = returnType

        val irFunc = factory.createSimpleFunction(
            0, 0, IrDeclarationOrigin.DEFINED,
            Name.identifier("invoke"),
            DescriptorVisibilities.PUBLIC, false, false, returnType,
            Modality.FINAL, invokeSymbol,
            false, false, false, false, false, null, false
        )

        val funcSymbol = ctx.functionSymbols[expr.method] ?: IrSimpleFunctionSymbolImpl()
        val paramSymbols = mutableListOf<IrValueParameterSymbol>()
        for ((i, pt) in paramTypes.withIndex()) {
            val paramSymbol = IrValueParameterSymbolImpl()
            paramSymbols.add(paramSymbol)
            val irParam = factory.createValueParameter(
                0, 0, IrDeclarationOrigin.DEFINED,
                Name.identifier("p$i"), pt,
                false, paramSymbol, i, null, false, false, false
            )
            irFunc.valueParameters = irFunc.valueParameters + irParam
            ctx.symbols["p$i"] = paramSymbol
            ctx.varTypes["p$i"] = pt
        }

        val body = factory.createBlockBody(0, 0)
        val bodyStatements = body.statements as MutableList<IrStatement>

        val callResult: IrExpression
        // If target is a function name identifier (not a variable/receiver), treat as static reference
        val isStaticFunctionRef = ctx.functionSymbols.containsKey(expr.method)
        if (expr.target != null && !isStaticFunctionRef) {
            val receiver = translateExpression(expr.target!!, ctx)
            val argExprs = paramSymbols.mapIndexed { i, _ ->
                val ps = ctx.symbols["p$i"] ?: IrValueParameterSymbolImpl()
                IrNodeFactory.createGetValue(0, 0, paramTypes.getOrElse(i) { typeMapper.unitType() }, ps, null)
            }.toTypedArray()
            val callArgs = arrayOfNulls<IrExpression>(argExprs.size + 1)
            callArgs[0] = receiver
            argExprs.copyInto(callArgs, 1)
            val call = IrNodeFactory.createCall(
                0, 0, returnType, IrStatementOrigin.INVOKE,
                callArgs.filterNotNull().toTypedArray(), emptyArray(), funcSymbol, null
            )
            ctx.callNames[funcSymbol] = "v:${expr.method}"
            callResult = call
        } else {
            val call = IrNodeFactory.createCall(
                0, 0, returnType, IrStatementOrigin.INVOKE,
                paramSymbols.mapIndexed { i, _ ->
                    val ps = ctx.symbols["p$i"] ?: IrValueParameterSymbolImpl()
                    IrNodeFactory.createGetValue(0, 0, paramTypes.getOrElse(i) { typeMapper.unitType() }, ps, null)
                }.toTypedArray(), emptyArray(), funcSymbol, null
            )
            ctx.callNames[funcSymbol] = expr.method
            callResult = call
        }

        // Wrap call in IrReturn for non-unit return types so the backend emits a return instruction
        if (returnType != typeMapper.unitType()) {
            bodyStatements.add(IrNodeFactory.createReturn(0, 0, returnType, callResult, irFunc.symbol))
        } else {
            bodyStatements.add(callResult)
        }
        irFunc.body = body
        irClass.declarations.add(irFunc)

        for (i in paramTypes.indices) {
            ctx.symbols.remove("p$i")
            ctx.varTypes.remove("p$i")
        }

        val instanceCtorSymbol = IrConstructorSymbolImpl()
        val ctorReturnType = typeMapper.mapType(JasNamedType(className))
        ctx.constructorTypes[instanceCtorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, ctorReturnType, null,
            emptyArray(), emptyArray(), instanceCtorSymbol,
            SourceElement.NO_SOURCE, 0
        )
    }

    private fun translateInstanceOf(expr: JasInstanceOfExpr, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.expression, ctx)
        val targetType = typeMapper.mapType(expr.type)
        return IrTypeOperatorCallImpl(
            0, 0, typeMapper.boolType(),
            IrTypeOperator.INSTANCEOF,
            targetType, operand
        )
    }

    private fun translateCast(expr: JasCastExpr, ctx: TranslationContext): IrExpression {
        val operand = translateExpression(expr.expression, ctx)
        val targetType = typeMapper.mapType(expr.type)
        return IrTypeOperatorCallImpl(
            0, 0, targetType,
            IrTypeOperator.CAST,
            targetType, operand
        )
    }

    private fun translateNullCoalescing(expr: JasNullCoalescing, ctx: TranslationContext): IrExpression {
        val leftExpr = translateExpression(expr.left, ctx)
        val rightExpr = translateExpression(expr.right, ctx)
        val resultType = rightExpr.type
        val block = IrNodeFactory.createBlock(0, 0, resultType, null)
        val tempSymbol = IrVariableSymbolImpl()
        val tempVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("tmp$"),
            leftExpr.type, tempSymbol, true, false, false
        )
        tempVar.initializer = leftExpr
        (block.statements as MutableList<IrStatement>).add(tempVar)
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.ELVIS)
        val notNullCheck = IrTypeOperatorCallImpl(
            0, 0, typeMapper.boolType(),
            IrTypeOperator.IMPLICIT_NOTNULL,
            leftExpr.type,
            IrNodeFactory.createGetValue(0, 0, leftExpr.type, tempSymbol, null)
        )
        irWhen.branches.add(IrNodeFactory.createBranch(
            0, 0, notNullCheck,
            IrNodeFactory.createGetValue(0, 0, leftExpr.type, tempSymbol, null)
        ))
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), rightExpr))
        (block.statements as MutableList<IrStatement>).add(irWhen)
        return block
    }

    private fun translateTernary(expr: JasTernaryExpr, ctx: TranslationContext): IrExpression {
        val cond = translateExpression(expr.condition, ctx)
        val thenExpr = translateExpression(expr.thenExpr, ctx)
        val elseExpr = translateExpression(expr.elseExpr, ctx)
        val resultType = thenExpr.type
        val irWhen = IrNodeFactory.createWhen(0, 0, resultType, IrStatementOrigin.WHEN)
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, cond, thenExpr))
        irWhen.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), elseExpr))
        return irWhen
    }

    private fun translateStringTemplate(expr: JasStringTemplate, ctx: TranslationContext): IrExpression {
        val parts = mutableListOf<IrExpression>()
        for (part in expr.parts) {
            when (part) {
                is JasTemplateLiteral -> parts.add(typeMapper.stringConst(part.text))
                is JasTemplateExpr -> parts.add(translateExpression(part.expr, ctx))
            }
        }
        if (parts.isEmpty()) return typeMapper.stringConst("")
        // Desugar f"Hello {name}!" → "Hello + name + "!"
        var result: IrExpression = parts.first()
        for (i in 1 until parts.size) {
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = "string_plus"
            result = IrNodeFactory.createCall(
                0, 0, typeMapper.stringType(), IrStatementOrigin.PLUS,
                arrayOf(result, parts[i]), emptyArray(), stubSymbol, null
            )
        }
        return result
    }

    private fun translateAssignment(expr: JasAssignment, ctx: TranslationContext): IrExpression {
        val value = translateExpression(expr.value, ctx)
        if (expr.target is JasIdentifier) {
            val foundSymbol = ctx.symbols[expr.target.name]
            if (foundSymbol != null) {
                if (expr.op != "=") {
                    val op = expr.op.removeSuffix("=")
                    val getX = IrNodeFactory.createGetValue(0, 0, value.type, foundSymbol, IrStatementOrigin.GET_PROPERTY)
                    val stubSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[stubSymbol] = op
                    val binOp = IrNodeFactory.createCall(
                        0, 0, value.type, IrStatementOrigin.PLUS,
                        arrayOf(getX, value), emptyArray(), stubSymbol, null
                    )
                    return IrNodeFactory.createSetValue(
                        0, 0, value.type, foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                }
                return IrNodeFactory.createSetValue(
                    0, 0, typeMapper.unitType(), foundSymbol,
                    IrStatementOrigin.EQ, value
                )
            }
        } else if (expr.target is JasPropertyAccess) {
            val pa = expr.target as JasPropertyAccess
            val targetExpr = translateExpression(pa.target, ctx)
            val fieldSymbol = resolveFieldSymbol(pa.property, pa.target, targetExpr, ctx)
            if (expr.op != "=") {
                val op = expr.op.removeSuffix("=")
                val getField = IrNodeFactory.createGetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
                getField.receiver = targetExpr
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = op
                val binOp = IrNodeFactory.createCall(
                    0, 0, value.type, IrStatementOrigin.PLUS,
                    arrayOf(getField, value), emptyArray(), stubSymbol, null
                )
                val targetExpr2 = translateExpression(pa.target, ctx)
                val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
                setField.receiver = targetExpr2
                setField.value = binOp
                return setField
            }
            val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
            setField.receiver = targetExpr
            setField.value = value
            return setField
        } else if (expr.target is JasArrayAccess) {
            val aa = expr.target as JasArrayAccess
            val arrayExpr = translateExpression(aa.target, ctx)
            val indexExpr = translateExpression(aa.index, ctx)
            val resultType = value.type
            val block = IrNodeFactory.createBlock(0, 0, resultType, null)
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("aa$"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            (block.statements as MutableList<IrStatement>).add(arrVar)
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("ai$"), indexExpr.type, idxSymbol, true, false, false)
            idxVar.initializer = indexExpr
            (block.statements as MutableList<IrStatement>).add(idxVar)
            if (expr.op != "=") {
                val op = expr.op.removeSuffix("=")
                val getSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[getSymbol] = "get"
                val getCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null)
                    ), emptyArray(), getSymbol, null
                )
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = op
                val binOp = IrNodeFactory.createCall(
                    0, 0, resultType, IrStatementOrigin.PLUS,
                    arrayOf(getCall, value), emptyArray(), stubSymbol, null
                )
                val valSymbol = IrVariableSymbolImpl()
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("av$"), resultType, valSymbol, true, false, false)
                valVar.initializer = binOp
                (block.statements as MutableList<IrStatement>).add(valVar)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null)
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
                (block.statements as MutableList<IrStatement>).add(IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null))
            } else {
                val valSymbol = IrVariableSymbolImpl()
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("av$"), resultType, valSymbol, true, false, false)
                valVar.initializer = value
                (block.statements as MutableList<IrStatement>).add(valVar)
                val setSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[setSymbol] = "arraySet"
                val setCall = IrNodeFactory.createCall(
                    0, 0, resultType, null,
                    arrayOf(
                        IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                        IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null)
                    ), emptyArray(), setSymbol, null
                )
                (block.statements as MutableList<IrStatement>).add(setCall)
                (block.statements as MutableList<IrStatement>).add(IrNodeFactory.createGetValue(0, 0, resultType, valSymbol, null))
            }
            return block
        }
        return value
    }

    private fun translatePostfixIncDec(expr: JasPostfixIncDec, ctx: TranslationContext, needsResult: Boolean): IrExpression {
        val target = expr.target
        val op = expr.op
        val one = typeMapper.intConst(1)
        if (target is JasIdentifier) {
            val foundSymbol = ctx.symbols[target.name]
            if (foundSymbol != null) {
                val getX = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), foundSymbol, IrStatementOrigin.GET_PROPERTY)
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
                if (needsResult) {
                    val resultSymbol = IrVariableSymbolImpl()
                    val resultVar = IrNodeFactory.createVariable(
                        0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                        typeMapper.intType(), resultSymbol, true, false, false
                    )
                    resultVar.initializer = getX
                    val outerBlock = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                    (outerBlock.statements as MutableList<IrStatement>).add(resultVar)
                    val binOp = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                        arrayOf(getX, one), emptyArray(), stubSymbol, null
                    )
                    val setX = IrNodeFactory.createSetValue(
                        0, 0, typeMapper.unitType(), foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                    val innerBlock = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                    (innerBlock.statements as MutableList<IrStatement>).add(setX)
                    val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                    (innerBlock.statements as MutableList<IrStatement>).add(getResult)
                    (outerBlock.statements as MutableList<IrStatement>).add(innerBlock)
                    return outerBlock
                } else {
                    val binOp = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                        arrayOf(getX, one), emptyArray(), stubSymbol, null
                    )
                    val setX = IrNodeFactory.createSetValue(
                        0, 0, typeMapper.unitType(), foundSymbol,
                        IrStatementOrigin.EQ, binOp
                    )
                    val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                    (block.statements as MutableList<IrStatement>).add(setX)
                    return block
                }
            }
        } else if (target is JasPropertyAccess) {
            val pa = target
            val targetExpr = translateExpression(pa.target, ctx)
            val fieldSymbol = resolveFieldSymbol(pa.property, pa.target, targetExpr, ctx)
            val getField = IrNodeFactory.createGetField(0, 0, typeMapper.intType(), fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
            getField.receiver = targetExpr
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
            val binOp = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                arrayOf(getField, one), emptyArray(), stubSymbol, null
            )
            val targetExpr2 = translateExpression(pa.target, ctx)
            val setField = IrNodeFactory.createSetField(0, 0, typeMapper.unitType(), fieldSymbol, null, IrStatementOrigin.EQ)
            setField.receiver = targetExpr2
            setField.value = binOp
            if (needsResult) {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                val resultSymbol = IrVariableSymbolImpl()
                val resultVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                    typeMapper.intType(), resultSymbol, true, false, false
                )
                if (op == "++") {
                    resultVar.initializer = getField
                } else {
                    resultVar.initializer = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.MINUS,
                        arrayOf(getField, one), emptyArray(), stubSymbol, null
                    )
                }
                (block.statements as MutableList<IrStatement>).add(resultVar)
                (block.statements as MutableList<IrStatement>).add(setField)
                val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                (block.statements as MutableList<IrStatement>).add(getResult)
                return block
            } else {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                (block.statements as MutableList<IrStatement>).add(setField)
                return block
            }
        } else if (target is JasArrayAccess) {
            val aa = target
            val arrayExpr = translateExpression(aa.target, ctx)
            val indexExpr = translateExpression(aa.index, ctx)
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("aa$"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("ai$"), indexExpr.type, idxSymbol, true, false, false)
            idxVar.initializer = indexExpr
            val getSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[getSymbol] = "get"
            val getCall = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                    IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null)
                ), emptyArray(), getSymbol, null
            )
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
            val binOp = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                arrayOf(getCall, one), emptyArray(), stubSymbol, null
            )
            val setSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[setSymbol] = "arraySet"
            val setCall = IrNodeFactory.createCall(
                0, 0, typeMapper.unitType(), null,
                arrayOf(
                    IrNodeFactory.createGetValue(0, 0, arrayExpr.type, arrSymbol, null),
                    IrNodeFactory.createGetValue(0, 0, indexExpr.type, idxSymbol, null),
                    binOp
                ), emptyArray(), setSymbol, null
            )
            if (needsResult) {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                (block.statements as MutableList<IrStatement>).add(arrVar)
                (block.statements as MutableList<IrStatement>).add(idxVar)
                val resultSymbol = IrVariableSymbolImpl()
                val resultVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("inc$"),
                    typeMapper.intType(), resultSymbol, true, false, false
                )
                if (op == "++") {
                    resultVar.initializer = getCall
                } else {
                    resultVar.initializer = IrNodeFactory.createCall(
                        0, 0, typeMapper.intType(), IrStatementOrigin.MINUS,
                        arrayOf(getCall, one), emptyArray(), stubSymbol, null
                    )
                }
                (block.statements as MutableList<IrStatement>).add(resultVar)
                (block.statements as MutableList<IrStatement>).add(setCall)
                val getResult = IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), resultSymbol, IrStatementOrigin.GET_PROPERTY)
                (block.statements as MutableList<IrStatement>).add(getResult)
                return block
            } else {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                (block.statements as MutableList<IrStatement>).add(arrVar)
                (block.statements as MutableList<IrStatement>).add(idxVar)
                (block.statements as MutableList<IrStatement>).add(setCall)
                return block
            }
        }
        return typeMapper.unitExpr()
    }

    private fun translateAssignmentIncrement(expr: JasAssignmentIncrement, ctx: TranslationContext): IrExpression {
        return translateAssignment(JasAssignment(expr.target, expr.increment, "="), ctx)
    }
}
