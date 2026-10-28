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
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
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
 * 一次静态字段访问的解析结果。
 *
 * 做什么：把「字段符号」与「字段声明类型」成对带出，避免调用方在两个并行索引里重复查找。
 * 什么时候用：translator 判定 `类名.字段`（或通过实例访问静态字段）时。
 * 前置条件：符号必须已登记进 [TranslationContext.staticFieldSymbols]。
 * 失败模式：解析失败时调用方拿到 null，应回退到实例字段路径。
 * 为什么用数据类：两个值同生同灭，绑定后不会出现「拿到符号却拿错类型」的半成品状态。
 */
private data class StaticFieldRef(val symbol: IrFieldSymbol, val type: IrType)

/**
 * 做什么：一个用户类型「成员方法的声明擦除签名」——擦除后的形参描述符列表 + 返回描述符。
 * 什么时候用：后端在经接口/泛型接收者发 `INVOKEINTERFACE`/`INVOKEVIRTUAL` 时，据此得到
 *   被调方法在 JVM 里的真实描述符（类型变量按擦除规则落到 `Object`）。
 * 前置条件：由 translator 预登记阶段从 AST 声明计算；不做类型变量上界解析（v1 无界→Object）。
 * 失败模式：仅承载数据；更复杂的泛型形状（通配符/F-bounded/基本类型实参装箱）不在此表达，
 *   由后端在选择描述符时按「参数个数不匹配则回退」处理。
 * 已知限制：索引键是「类型名 + 方法名 + 元数」（见 [memberArityKey]）；同名同元数但
 *   形参类型不同的重载仍无法在此区分（检查器会将其报告为同元数歧义，不会走到后端）。
 *   不同元数的重载各自独立登记，互不覆盖。
 * 为什么这样设计：后端不在翻译期持有 AST，且成员调用点的 IR 符号不与声明符号同身份，
 *   因此把「声明签名」这一变化点收敛到 translator 预登记，后端只按「名字 + 元数」查询，
 *   不重新推断。
 */
data class MemberMethodSignature(
    val paramDescriptors: List<String>,
    val returnDescriptor: String
)

/**
 * 做什么：把「成员方法名 + 元数」编码成成员索引的键。
 * 什么时候用：translator 预登记成员返回类型/声明签名，以及 backend 在调用点反查时，
 *   必须使用同一个键格式，才能让不同参数个数的重载各自命中自己的签名。
 * 前置条件：arity 是 JVM 调用点的**实际形参个数**（vararg 已在调用点打包成数组，
 *   因此等于声明形参个数；默认参数未省略时同样等于声明形参个数）。
 * 失败模式：纯字符串拼接，不会失败。方法名是语言标识符（不含 `/`），用 `/` 分隔安全。
 * 为什么这样设计：此前索引是「方法名 -> 单个签名」，不同元数重载会互相覆盖；把元数
 *   纳入键是最小、无歧义的修复，调用点与 backend 无需共享 AST，只需共享键格式。
 */
internal fun memberArityKey(name: String, arity: Int): String = "$name/$arity"

/**
 * 做什么：vararg 成员方法在索引中的「通配键」。
 * 什么时候用：vararg 调用在调用点会把尾部实参打包成数组，JVM 描述符上的形参个数
 *   与源码实参个数无关；因此预登记时为 vararg 方法额外写一份通配键，backend 在
 *   调用点优先按 [memberArityKey] 精确查找，未命中再回退到本通配键。
 * 前置条件：与 [memberArityKey] 使用同一套索引。
 * 失败模式：纯字符串拼接，不会失败。
 * 为什么需要：`q(x: string)` 与 `q(xs: int32...)` 在 JVM 上都是 1 个形参，但源码
 *   调用 `q(1,2,3)` 需要命中后者；只按「打包后的实参个数」查会误命中前者。
 */
internal fun memberVarargKey(name: String): String = "$name/*"

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
    val staticFieldSymbols: Set<IrFieldSymbol> = emptySet(),
    // Member functions declared `static`: backend emits them with ACC_STATIC and a
    // receiver-less parameter slot base, so `A.f()` lowers to INVOKESTATIC.
    // 声明为 `static` 的成员函数符号集合：后端据此给成员方法加 ACC_STATIC 并用
    // 「无接收者」的参数槽起点，使 `A.f()` 正确降为 INVOKESTATIC。
    val staticFunctionSymbols: Set<IrSimpleFunctionSymbol> = emptySet(),
    // Synthetic static calls (enum `values()` / `valueOf(String)`): symbol -> JVM
    // internal owner name. The method name comes from `callNames`; the descriptor
    // is derived by the backend from the argument types and the call's IR type.
    // 合成静态调用（枚举 values()/valueOf(String)）：符号 -> JVM 内部宿主名。
    // 方法名取自 callNames；描述符由后端据实参类型与调用 IR 类型推导。
    val syntheticStaticCallOwners: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    // 全限定平台静态调用：符号 -> 平台类 JVM 内部名。方法名取自 callNames；
    // 后端必须用 PlatformMethodResolver.resolveStatic 取得真实描述符，而不是按调用点 Unit 猜。
    val platformStaticCallOwners: Map<IrSimpleFunctionSymbol, String> = emptyMap(),
    // 每个用户类型的成员方法「声明擦除签名」：JVM 风格类型名 -> 方法名 -> (形参描述符列表, 返回描述符)。
    // 后端据此在经接口/泛型接收者调用时写出被调方法的真实（擦除后）描述符。
    // 中文：成员方法声明擦除签名索引；键/值规则见 MemberMethodSignature 文档。
    val memberSignaturesByType: Map<String, Map<String, MemberMethodSignature>> = emptyMap(),
    // 成员调用点符号 -> 本次调用应使用的成员索引键（[memberArityKey] / [memberVarargKey]）。
    // 由 translator 在调用点依据检查器选中的重载写入，backend 据此取**同一个**签名；
    // 避免 backend 重新按「打包后实参个数」猜测而在 vararg/定长重载间选错。
    val memberCallKeys: Map<IrSimpleFunctionSymbol, String> = emptyMap()
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
    // Member declared (erased) signatures: JVM-style type name -> member name -> signature.
    // Populated in the predeclare pass; exported to the backend so an interface-typed
    // call site can emit the *declared* (erased) descriptor instead of guessing from args.
    // 成员「声明擦除签名」索引：JVM 风格类型名 -> 成员名 -> 擦除签名。预登记阶段填充，
    // 导出给后端，使接口类型接收者的调用点能发出「声明的、擦除后的」描述符，而非按实参猜。
    val memberSignaturesByType: MutableMap<String, MutableMap<String, MemberMethodSignature>> = mutableMapOf(),
    // 成员调用点符号 -> 成员索引键（元数键或 vararg 通配键），translate 完成时拷出给 backend。
    val memberCallKeys: MutableMap<IrSimpleFunctionSymbol, String> = mutableMapOf(),
    // 构造器声明形参类型索引：JVM 风格类型名 -> memberArityKey("<init>", 形参数) -> 形参 IR 类型。
    // 为什么需要：`new Holder(new MemRepo())` 的 JVM 描述符由实参类型决定（IrConstructorCall
    //   没有与声明共享的符号），若不先把实参宽化到声明的接口/父类类型，就会写出
    //   `<init>(LMemRepo;)V`，而类里只有 `<init>(LRepo;)V`，运行期 NoSuchMethodError。
    // 变化点隔离：把「构造器声明签名」在预登记阶段集中一次，translateNew 只查表并插 CAST，
    //   后端继续按 IR 实参类型拼描述符，无需理解构造器重载。
    val constructorParamTypesByType: MutableMap<String, MutableMap<String, List<IrType>>> = mutableMapOf(),
    // Declared type-parameter names per user type (JVM-style name -> [T, ID, ...]).
    // Used to bind a receiver's type arguments onto the declared return type so the call
    // site's IR type is the *source* type (e.g. Repo<Person>.get -> Person).
    // 每个用户类型声明的类型形参名（JVM 风格名 -> [T, ID, ...]）。用于把接收者的类型实参
    // 绑定到声明返回类型上，使调用点的 IR 类型是「源码视角的具体类型」（如 Repo<Person>.get -> Person）。
    val typeParameterNamesByType: MutableMap<String, List<String>> = mutableMapOf(),
    // 直接父类型索引：JVM 风格类型名 -> 直接父类型 JVM 风格名列表（父类在前，实现的接口/父接口在后）。
    // 为什么需要：成员/字段解析要沿继承链向上走（子类接收者调用继承来的方法、读继承来的字段），
    //   而「谁是谁的父」这一信息此前只在翻译类的第二遍才写入 classSuperclassNames，预登记阶段拿不到。
    //   把继承关系来源收敛到这一张预登记表，成员/字段解析不必各自重新解析 AST（隔离的变化点）。
    // 已知限制：不含外部类型（java/lang/Object 等）——它们在表外自然终止。
    val superTypeNamesByType: MutableMap<String, MutableList<String>> = mutableMapOf(),
    // 类名 -> 类符号。
    val classSymbols: MutableMap<String, IrClassSymbol> = mutableMapOf(),
    // 本次翻译中声明的全部类型简单名（翻译前预登记，跨文件可见）。
    // 用途：识别 `Type.field` 里的 `Type` 是「类名」而不是「值为 null 的变量」，
    // 从而走静态字段路径；不能用 classSymbols 代替，因为后者要等对应声明被翻译后才写入，
    // 跨文件「先用后声明」时会漏判。
    val declaredTypeNames: MutableSet<String> = mutableSetOf(),
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
    // 带标签循环：label -> 目标 IR 循环（break label / continue label 使用）。
    val loopLabels: MutableMap<String, IrLoop> = mutableMapOf(),
    // 需要 onBreak 的循环：loop -> broke$ 标志符号；break 指向该循环时先置位。
    val loopBreakFlags: MutableMap<IrLoop, IrVariableSymbolImpl> = java.util.IdentityHashMap(),
    // labeledStatement 翻译期间暂存标签，由被包裹的第一个循环消费。
    var pendingLoopLabel: String? = null,
    // 当前函数；return 的目标符号与 defer 作用域依赖它。
    var currentFunction: IrSimpleFunction? = null,
    // 当前 IR 文件；匿名类（lambda/方法引用）在翻译中即时追加到这里。
    var currentFile: IrFile? = null,
    // 匿名闭包类名自增计数器（Lambda$N）。
    var lambdaIdCounter: Int = 0,
    // 编译器生成临时局部变量的自增计数器（aa$/ai$/av$/arr$/inc$ 等）。
    // 为什么必须唯一：后端按「变量名」登记槽位（variables/locals 都以 name 为键），
    //   同一函数里若两次使用同名临时变量，第二次读取会命中第一个槽位的类型，
    //   在「先 int32[] 后 string[]」等多元素类型赋值场景直接生成非法字节码（C12）。
    //   用符号而非名字做键是更大的重构；先在源头保证名字唯一，改动小且风险可控。
    var tempIdCounter: Int = 0,
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
    val staticFieldSymbols: MutableSet<IrFieldSymbol> = mutableSetOf(),
    // Static member function index: JVM-style class name -> function name -> symbol.
    // `A.f()` call sites look up the *declaration's* symbol here so the call and the
    // method share one identity; the backend then resolves the owner via the symbol.
    // 静态成员函数索引：JVM 风格类名 -> 函数名 -> 符号。`A.f()` 调用点据此取到与声明
    // 同一身份的符号，后端便能由符号反查 owner（INVOKESTATIC 的目标类）。
    val staticMemberFunctionSymbolsByType: MutableMap<String, MutableMap<String, IrSimpleFunctionSymbol>> = mutableMapOf(),
    // Member function symbols declared `static` (exported to the backend for ACC_STATIC).
    // 声明为 `static` 的成员函数符号集合（导出后端以加 ACC_STATIC）。
    val staticFunctionSymbols: MutableSet<IrSimpleFunctionSymbol> = mutableSetOf(),
    // Enum class keys (JVM-style names): `Types.values()`/`valueOf` are synthesized
    // static calls, recognized by this set.
    // 枚举类名（JVM 风格）集合：`Types.values()`/`valueOf` 被识别为合成静态调用。
    val enumClassKeys: MutableSet<String> = mutableSetOf(),
    // Synthetic static call owner index: symbol -> owner internal name (enum synth methods).
    // 合成静态调用的宿主索引：符号 -> 宿主内部名（枚举合成方法）。
    val syntheticStaticCallOwners: MutableMap<IrSimpleFunctionSymbol, String> = mutableMapOf(),
    // 检查器确认的平台静态调用：符号 -> 平台类 JVM 内部名。
    val platformStaticCallOwners: MutableMap<IrSimpleFunctionSymbol, String> = mutableMapOf(),
    // Enum constants: JVM internal class name -> constant name -> IR enum-entry symbol.
    // 枚举常量索引：JVM 内部类名 -> 常量名 -> IR 枚举项符号。
    // 为什么必须预登记：`Color.Red` 可能出现在枚举声明之前（同文件靠后声明或跨文件），
    //   表达式翻译必须拿到与声明处**同一个**符号，后端才能把 IR 读取映射回同名字段。
    // 键用内部名而非简单名：同一简单名可能出现在不同包，内部名是字段宿主的唯一标识。
    val enumConstantSymbolsByType: MutableMap<String, MutableMap<String, IrEnumEntrySymbol>> = mutableMapOf()
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
        registerDeclaredTypeNames(listOf(ast), ctx)
        installFileContext(ast, ctx)
        // 第一遍（预登记）：不翻译任何声明体，先把「翻译体时会用到的信息」就位：
        //   - 内建函数 hashCode/equals 的签名（合成体要按正确描述符调用）
        //   - 可哈希类名（决定嵌套字段用 .hash() 还是 .hashCode()）
        //   - 按类型的成员返回类型（迭代协议据此推断循环变量）
        //   - 顶层函数符号（先声明后调用）
        predeclareRuntimeBuiltins(ctx)
        collectHashableClassNames(listOf(ast), ctx)
        collectMemberReturnTypes(ast, ctx)
        collectMemberSignatures(ast, ctx)
        collectSuperTypeNames(ast, ctx)
        collectConstructorParamTypes(ast, ctx)
        predeclareFields(ast, ctx)
        predeclareTopLevelFunctions(ast.declarations, ctx)
        predeclareStaticMemberFunctions(ast, ctx)
        predeclareEnumConstants(listOf(ast), ctx)
        // 预登记全部完成后，把祖先成员签名/返回类型补进子类型索引（继承成员可见性）。
        propagateInheritedMemberIndexes(ctx)
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
            staticFieldSymbols = ctx.staticFieldSymbols.toSet(),
            staticFunctionSymbols = ctx.staticFunctionSymbols.toSet(),
            syntheticStaticCallOwners = ctx.syntheticStaticCallOwners.toMap(),
            platformStaticCallOwners = ctx.platformStaticCallOwners.toMap(),
            memberSignaturesByType = ctx.memberSignaturesByType.mapValues { it.value.toMap() }.toMap(),
            memberCallKeys = ctx.memberCallKeys.toMap())
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
        registerDeclaredTypeNames(asts, ctx)
        predeclareRuntimeBuiltins(ctx)
        // 第一遍：对全部文件做预登记（保证跨文件的先声明后使用）。
        for (ast in asts) {
            installFileContext(ast, ctx)
            collectHashableClassNames(listOf(ast), ctx)
            collectMemberReturnTypes(ast, ctx)
            collectMemberSignatures(ast, ctx)
            collectSuperTypeNames(ast, ctx)
            collectConstructorParamTypes(ast, ctx)
            predeclareFields(ast, ctx)
            predeclareTopLevelFunctions(ast.declarations, ctx)
            predeclareStaticMemberFunctions(ast, ctx)
            predeclareEnumConstants(listOf(ast), ctx)
        }
        // 全部文件预登记完成后，再统一传播继承成员索引：跨文件的父类可能排在子类之后，
        // 必须等所有类型自身的成员都登记完，父->子的补全才完整。
        propagateInheritedMemberIndexes(ctx)

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
            staticFieldSymbols = ctx.staticFieldSymbols.toSet(),
            staticFunctionSymbols = ctx.staticFunctionSymbols.toSet(),
            syntheticStaticCallOwners = ctx.syntheticStaticCallOwners.toMap(),
            platformStaticCallOwners = ctx.platformStaticCallOwners.toMap(),
            memberSignaturesByType = ctx.memberSignaturesByType.mapValues { it.value.toMap() }.toMap(),
            memberCallKeys = ctx.memberCallKeys.toMap())
    }

    /**
     * 做什么：扫描一组源文件的顶层类型声明，把它们的**简单名**登记进 [TypeMapper]，
     *   供名字解析区分「真实单字母类型」与「泛型形参」；同时登记各类型的**泛型形参名**。
     *
     * 什么时候用：translate / translateAll 开始翻译前调用一次（必须早于任何 mapType，
     *   否则解析已经用错判据）。
     * 前置条件：asts 已完成解析。
     * 失败模式：不抛异常；只登记 class/interface/enum/annotation 四类顶层声明，
     *   嵌套类型与方法级形参暂不登记（当前语言与探针均不依赖；单字母形参仍由启发式兜底）。
     * 为什么这样设计：`isLikelyTypeParam` 的单字母启发式无法自行分辨用户类型与形参，
     *   也无法识别 `ID`/`KEY` 等多字母形参（`Repository<T, ID>` 是 JPA 探针的标准形状）。
     *   在翻译前把「有哪些真实类型」与「有哪些形参名」一次性告诉 TypeMapper，
     *   是最小改动且依赖单向的做法。
     */
    private fun registerDeclaredTypeNames(asts: List<JasSourceFile>, ctx: TranslationContext) {
        for (ast in asts) {
            for (decl in ast.declarations) {
                when (decl) {
                    is JasClass -> {
                        registerDeclaredTypeName(decl.name, ctx)
                        registerTypeParams(decl.typeParameters)
                    }
                    is JasInterface -> {
                        registerDeclaredTypeName(decl.name, ctx)
                        registerTypeParams(decl.typeParameters)
                    }
                    is JasEnum -> registerDeclaredTypeName(decl.name, ctx)
                    is JasAnnotationType -> registerDeclaredTypeName(decl.name, ctx)
                    else -> {}
                }
            }
        }
    }

    /**
     * 做什么：把一组泛型形参名登记进 [TypeMapper]，使其按泛型擦除规则落到 `Object`。
     * 什么时候用：[registerDeclaredTypeNames] 遍历到带 `typeParameters` 的类型声明时。
     * 前置条件：形参来自 JasClass/JasInterface 的 `typeParameters`；名字是简单名。
     * 失败模式：无（幂等登记）。
     * 为什么这样设计：擦除判定属于 TypeMapper 的职责，translator 只负责把「语言层的声明事实」
     *   （哪些名字是形参）喂给它，保证「类型名 → 描述符」的规则仍集中在唯一入口。
     */
    private fun registerTypeParams(typeParameters: List<JasTypeParameter>) {
        for (tp in typeParameters) {
            typeMapper.registerTypeParam(tp.name)
        }
    }

    /**
     * 做什么：把一个已声明类型的简单名同时登记进 [TypeMapper]（泛型形参判定用）和
     *   翻译上下文的 [TranslationContext.declaredTypeNames]（静态访问判定用）。
     * 什么时候用：translate / translateAll 预登记阶段。
     * 前置条件：name 是顶层类型的简单名。
     * 失败模式：无；重复登记是幂等的。
     * 为什么这样设计：两处都需要「本次编译有哪些类型」，但用途不同，集中在同一处登记可
     *   避免只更新一处导致静态字段识别与泛型解析结论不一致。
     */
    private fun registerDeclaredTypeName(name: String, ctx: TranslationContext) {
        typeMapper.registerKnownType(name)
        ctx.declaredTypeNames.add(name)
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

    /**
     * 做什么：在翻译任何声明体之前，为全部枚举常量预先建立 IR 枚举项符号。
     * 什么时候用：translate / translateAll 的第一遍（与顶层函数预登记同一阶段）。
     * 前置条件：调用前已 [installFileContext]（需要 currentPkg/currentImports 才能把
     *   枚举简单名解析成唯一内部名）。
     * 失败模式：不抛异常；重复调用用 putIfAbsent 去重，先登记者优先。
     * 为什么需要：`Color.Red` 可能早于枚举声明被翻译（同文件后置声明、跨文件引用）。
     *   枚举项符号必须只有一份身份：声明处与使用处若各建一个符号，后端就无法把读取点
     *   映射回同一个字段（症状正是本任务修复的 GETFIELD 帧错误）。
     * 为什么用内部名作键：字段宿主在 JVM 层按内部名唯一确定；简单名可能跨包重名。
     */
    private fun predeclareEnumConstants(asts: List<JasSourceFile>, ctx: TranslationContext) {
        for (ast in asts) {
            for (decl in ast.declarations) {
                if (decl !is JasEnum) continue
                val owner = typeMapper.resolveNamedTypeName(decl.name)
                val byName = ctx.enumConstantSymbolsByType.getOrPut(owner) { mutableMapOf() }
                for (constant in decl.constants) {
                    byName.putIfAbsent(constant.name, IrEnumEntrySymbolImpl())
                }
            }
        }
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
                    // 用内部名登记：跨包同名类可能一个带 hash()、一个不带，简单名会串。
                    ctx.hashableClassNames.add(typeMapper.resolveNamedTypeName(decl.name))
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
         * 失败模式：无；键包含元数，不同元数重载各自登记；同元数保留先登记者。
         */
        fun record(typeName: String, members: List<JasDeclaration>) {
            // 用中央解析得到与运行时一致的内部分名（处理点分包名与 import），
            // 否则键会与 irTypeClassName 反查出的名字对不上（如 "a.b/T" vs "a/b/T"）。
            val qualified = typeMapper.resolveNamedTypeName(typeName)
            val index = ctx.memberReturnTypesByType.getOrPut(qualified) { mutableMapOf() }
            for (member in members) {
                if (member is JasFunction) {
                    // 返回类型也必须按元数区分：q() 与 q(string) 可以返回不同类型。
                    index.putIfAbsent(
                        memberArityKey(member.name, member.parameters.size),
                        typeMapper.mapType(member.returnType)
                    )
                    // vararg 调用的源码实参个数不固定，额外登记通配键供调用点回退。
                    if (member.parameters.lastOrNull()?.vararg == true) {
                        index.putIfAbsent(memberVarargKey(member.name), typeMapper.mapType(member.returnType))
                    }
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

    /**
     * 做什么：预登记每个用户类型成员方法的「声明擦除签名」与类型形参名，供后端确定调用描述符。
     * 什么时候用：translate / translateAll 的第一遍，紧跟 [collectMemberReturnTypes] 之后。
     * 前置条件：调用前已通过 installFileContext 安装当前文件的包/import 上下文，否则
     *   [TypeMapper.resolveNamedTypeName] 得到的键与运行时内部名对不上。
     * 失败模式：不抛异常；形参类型缺失的成员整条跳过（宁可回退旧行为，也不登记半成品签名）。
     *   同名成员用 putIfAbsent 保留先登记者（first-writer-wins，与 collectMemberReturnTypes 一致）。
     * 为什么这样设计：JVM 泛型擦除后，接口方法（如 `save(T)`）的描述符是 `(Object)V`，而调用点
     *   的实参类型可能是具体类型（`Person`）。成员调用的 IR 符号不与声明同身份，后端无法自行推断，
     *   故把「声明签名」这一变化点收敛到此处预登记；描述符擦除规则复用 TypeMapper 的唯一入口。
     */
    private fun collectMemberSignatures(ast: JasSourceFile, ctx: TranslationContext) {
        /**
         * 做什么：把一个类型的成员签名与类型形参名写入按类型的索引。
         * 什么时候用：本函数遍历到 JasClass/JasInterface/JasEnum 时各调用一次。
         * 前置条件：已安装当前文件的包/import 上下文。
         * 失败模式：无；键包含元数，不同元数重载各自登记；同元数保留先登记者。
         */
        fun record(typeName: String, typeParams: List<JasTypeParameter>, members: List<JasDeclaration>) {
            val qualified = typeMapper.resolveNamedTypeName(typeName)
            // 类型形参名按声明顺序登记：调用点据此把接收者实参绑定到声明返回类型。
            if (typeParams.isNotEmpty()) {
                ctx.typeParameterNamesByType.putIfAbsent(qualified, typeParams.map { it.name })
            }
            val index = ctx.memberSignaturesByType.getOrPut(qualified) { mutableMapOf() }
            for (member in members) {
                if (member !is JasFunction) continue
                // 形参类型必须全部已知，否则无法得到可靠描述符——整条跳过。
                if (member.parameters.any { it.type == null }) continue
                val paramDescs = member.parameters.map { typeMapper.jvmDescriptorFromType(it.type) }
                val retDesc = typeMapper.jvmReturnDescriptor(member.returnType)
                // 键含元数：后端在调用点按「实际实参个数」反查，必须命中对应的那个重载。
                val signature = MemberMethodSignature(paramDescs, retDesc)
                index.putIfAbsent(memberArityKey(member.name, member.parameters.size), signature)
                // vararg 的 JVM 形参个数固定为「固定形参 + 1 个数组」，但源码实参个数可变；
                // 另登记通配键，调用点按源码元数精确键未命中时回退到这里。
                if (member.parameters.lastOrNull()?.vararg == true) {
                    index.putIfAbsent(memberVarargKey(member.name), signature)
                }
            }
        }
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> record(decl.name, decl.typeParameters, decl.members)
                is JasInterface -> record(decl.name, decl.typeParameters, decl.members)
                else -> {}
            }
        }
    }

    /**
     * 做什么：预登记每个类**构造器声明的形参类型**，按「类型内部名 + 元数」索引。
     * 什么时候用：translate / translateAll 的第一遍（[collectSuperTypeNames] 之后、翻译声明之前），
     *   每个文件调用一次；调用前必须先 [installFileContext]。
     * 前置条件：形参类型已由类型检查器补全（本项目构造器形参必须显式声明类型）。
     * 失败模式：不抛异常；有未声明类型的形参时整条构造器跳过（[translateNew] 退回旧行为，
     *   只按实参类型拼描述符）。无显式构造器的类登记一个空形参列表，对应默认 `<init>()V`。
     * 为什么这样设计：`IrConstructorCall` 的符号与声明符号不同身份，后端拼描述符时只能看实参；
     *   把「构造器声明签名」提前收敛到一张表，调用点才能把具体实参宽化到声明类型
     *   （接口/父类），使描述符与类里真实存在的构造器一致。该表只记录、不改声明。
     * 已知限制：重载仅按元数区分（与成员索引口径一致）；同元数不同类型形参的重载在 v1
     *   类型检查阶段已不可用，故不会走到这里的歧义选择。
     */
    private fun collectConstructorParamTypes(ast: JasSourceFile, ctx: TranslationContext) {
        for (decl in ast.declarations) {
            if (decl !is JasClass) continue
            val owner = typeMapper.resolveNamedTypeName(decl.name)
            val byKey = ctx.constructorParamTypesByType.getOrPut(owner) { mutableMapOf() }
            if (decl.constructors.isEmpty()) {
                // 默认构造器：`new C()` 的声明签名是空形参。
                byKey.putIfAbsent(memberArityKey("<init>", 0), emptyList())
                continue
            }
            for (ctor in decl.constructors) {
                // 形参类型缺失时无法得到可靠描述符——整条跳过，避免登记半个签名。
                if (ctor.parameters.any { it.type == null }) continue
                byKey.putIfAbsent(
                    memberArityKey("<init>", ctor.parameters.size),
                    ctor.parameters.map { typeMapper.mapType(it.type) }
                )
            }
        }
    }

    /**
     * 做什么：预登记每个用户类型的**直接父类型**内部名（父类 + 实现的接口 / 父接口）。
     * 什么时候用：translate / translateAll 的第一遍，紧跟 [collectMemberSignatures] 之后调用。
     * 前置条件：已安装当前文件的包/import 上下文（否则 resolveNamedTypeName 得到错误键）。
     * 失败模式：不抛异常；非 JasNamedType 的父引用被忽略（静态不可判定）。
     * 为什么这样设计：成员/字段解析必须沿继承链向上，而父关系此前只在翻译类第二遍才可得；
     *   提前收敛到一张表，使 [propagateInheritedMemberIndexes] 与字段解析都能复用。
     */
    private fun collectSuperTypeNames(ast: JasSourceFile, ctx: TranslationContext) {
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> {
                    val self = typeMapper.resolveNamedTypeName(decl.name)
                    val parents = mutableListOf<String>()
                    // 父类在前：成员解析时近亲优先（子类自己的条目已存在，这里只补缺失）。
                    (decl.superclass as? JasNamedType)?.let { parents.add(typeMapper.resolveNamedTypeName(it.name)) }
                    decl.interfaces.filterIsInstance<JasNamedType>().forEach {
                        parents.add(typeMapper.resolveNamedTypeName(it.name))
                    }
                    ctx.superTypeNamesByType[self] = parents
                }
                is JasInterface -> {
                    val self = typeMapper.resolveNamedTypeName(decl.name)
                    ctx.superTypeNamesByType[self] = decl.extends
                        .filterIsInstance<JasNamedType>()
                        .map { typeMapper.resolveNamedTypeName(it.name) }
                        .toMutableList()
                }
                else -> {}
            }
        }
    }

    /**
     * 做什么：把祖先类型的成员返回类型 / 声明擦除签名沿继承链补进子类型的索引（子类型自己的条目优先）。
     * 什么时候用：所有文件的预登记完成后（translate / translateAll 第一遍末尾）。
     * 前置条件：[collectSuperTypeNames] 与 [collectMemberReturnTypes]/[collectMemberSignatures] 已对全部文件执行。
     * 失败模式：不抛异常；环状继承由「轮数上界 = 类型数」保证在有限轮内停止，不会无限循环。
     * 为什么这样设计：经**子类类型接收者**调用继承方法时，调用点与后端都按「接收者类型」查索引；
     *   若索引里没有继承来的成员，返回类型会退化成全局 last-writer-wins（跨文件时甚至退化为 void），
     *   最终写出错误的调用描述符（R2DBC #6：`invokevirtual std/D.s:()V`）。把「继承成员可见性」
     *   收敛到一次预登记传播，调用点与后端都无需理解继承链（隔离的变化点）。
     * 注意：这里复制的是**签名索引条目（查找表）**，不是方法体；子类 IR 仍不包含父类方法。
     */
    private fun propagateInheritedMemberIndexes(ctx: TranslationContext) {
        // 迭代到不动点：父类型的继承成员可能在同一轮里才补进父索引，需再跑一轮才能传给子类型。
        // 轮数上界为继承深度（不超过类型数），无环图下收敛；环状继承也能在有限轮内停止。
        val types = ctx.superTypeNamesByType.keys.toList()
        var round = 0
        var changed = true
        while (changed && round <= types.size) {
            changed = false
            for (type in types) {
                for (parent in ctx.superTypeNamesByType[type] ?: emptyList()) {
                    val parentReturns = ctx.memberReturnTypesByType[parent] ?: continue
                    val childReturns = ctx.memberReturnTypesByType.getOrPut(type) { mutableMapOf() }
                    for ((member, ret) in parentReturns) {
                        if (childReturns.putIfAbsent(member, ret) == null) changed = true
                    }
                    val parentSigs = ctx.memberSignaturesByType[parent]
                    if (parentSigs != null) {
                        val childSigs = ctx.memberSignaturesByType.getOrPut(type) { mutableMapOf() }
                        for ((member, sig) in parentSigs) {
                            if (childSigs.putIfAbsent(member, sig) == null) changed = true
                        }
                    }
                }
            }
            round++
        }
    }

    /**
     * 做什么：预登记「声明为 static 的成员函数」的符号，并记录本次编译中出现的枚举类名。
     * 什么时候用：translate / translateAll 的第一遍（必须在 installFileContext 之后，
     *   以便 resolveNamedTypeName 得到与运行时一致的 JVM 风格类名）。
     * 前置条件：asts 已完成解析；当前文件上下文已安装。
     * 失败模式：不抛异常；非类型声明被跳过。重复预登记用 putIfAbsent 保持首登记者（声明顺序优先）。
     * 为什么这样设计：`A.f()` 的调用点必须拿到与声明**同一身份**的函数符号，后端才能由符号
     *   反查 owner（INVOKESTATIC 的目标类）；同时把「哪些函数是 static」旁路给后端以发出
     *   ACC_STATIC。枚举类名集合则用于识别 `Types.values()`/`valueOf` 这两个合成静态方法。
     *   （隔离的变化点：静态性来源只在声明侧判定，调用/后端不再各自猜测。）
     */
    private fun predeclareStaticMemberFunctions(ast: JasSourceFile, ctx: TranslationContext) {
        for (decl in ast.declarations) {
            val owner = when (decl) {
                is JasClass -> typeMapper.resolveNamedTypeName(decl.name)
                is JasInterface -> typeMapper.resolveNamedTypeName(decl.name)
                is JasEnum -> typeMapper.resolveNamedTypeName(decl.name)
                else -> null
            } ?: continue
            if (decl is JasEnum) ctx.enumClassKeys.add(owner)
            val members = when (decl) {
                is JasClass -> decl.members
                is JasInterface -> decl.members
                is JasEnum -> decl.members
                else -> emptyList()
            }
            for (member in members) {
                if (member is JasFunction && hasModifier(member.modifiers, "static")) {
                    val byName = ctx.staticMemberFunctionSymbolsByType.getOrPut(owner) { mutableMapOf() }
                    val symbol = byName.getOrPut(member.name) { IrSimpleFunctionSymbolImpl() }
                    ctx.staticFunctionSymbols.add(symbol)
                }
            }
        }
    }

    /**
     * 做什么：在翻译任何声明体之前，把每个 class/interface/enum 的字段（实例字段、静态字段、
     *   接口常量）的符号与类型统一登记进按类索引 [TranslationContext.fieldSymbolsByClass] /
     *   [TranslationContext.fieldTypesByClass]；静态字段（含接口常量）的符号同时进
     *   [TranslationContext.staticFieldSymbols]。
     * 什么时候用：translate / translateAll 的第一遍，紧跟 [collectSuperTypeNames] 之后、
     *   第二遍翻译之前；每个文件调用一次，调用前须先 [installFileContext]。
     * 前置条件：AST 已过注解阶段；当前文件上下文已安装——需要 currentPkg/currentImports 才能把
     *   类型名解析成与运行时一致的 JVM 风格内部名（否则键与访问点对不上，等于没登记）。
     * 失败模式：不抛异常；字段类型缺失时 [TypeMapper.mapType] 退化为 Unit（与既有 translateProperty
     *   行为一致）。重复登记用 getOrPut/putIfAbsent，保留首登记者（先声明优先）。
     * 为什么需要：字段符号此前只在「声明所在文件被翻译时」才登记（translateProperty /
     *   translateInterfaceConstant），于是访问点若排在声明文件之前，[resolveFieldSymbol] 只能拿到
     *   一个临时/空符号；该符号从未作为 IrField 出现在任何类体里，后端第一遍建立「符号 → 名字/owner」
     *   索引时自然查不到它，最终抛 `INTERNAL: unresolved field symbol (no name registered)`
     *   （R2DBC #5 / JPA #3 的根因）。把「有哪些字段、属于哪个类」提前收敛到一次预登记，
     *   使字段解析与文件顺序彻底无关（隔离的变化点：字段身份的建立时机）。
     * 为什么用内部名作键：同一简单名可能出现在不同包，JVM 内部名（如 `p/A`）才是字段宿主的唯一
     *   标识；该口径与 [resolveFieldSymbol] 的查表口径一致。
     * 为什么把接口常量也纳入：接口常量就是 `public static final` 字段，预登记后
     *   `Statement.MAX_ROWS` 的读取点在声明文件之前也能正确落到 GETSTATIC。
     */
    private fun predeclareFields(ast: JasSourceFile, ctx: TranslationContext) {
        /**
         * 做什么：为一个类型的全部属性成员登记字段符号/类型/静态性。
         * 什么时候用：本函数遍历到 JasClass/JasInterface/JasEnum 时各调用一次。
         * 前置条件：已安装当前文件的包/import 上下文。
         * 失败模式：无；重复登记保留先登记者。
         */
        fun record(typeName: String, members: List<JasDeclaration>, isInterface: Boolean) {
            val owner = typeMapper.resolveNamedTypeName(typeName)
            val symbols = ctx.fieldSymbolsByClass.getOrPut(owner) { mutableMapOf() }
            val types = ctx.fieldTypesByClass.getOrPut(owner) { mutableMapOf() }
            for (member in members) {
                if (member !is JasProperty) continue
                val symbol = symbols.getOrPut(member.name) { IrFieldSymbolImpl() }
                types.putIfAbsent(member.name, typeMapper.mapType(member.type))
                // 静态性判定必须与声明处（translateProperty / translateInterfaceConstant）完全一致，
                // 否则访问点会发错指令：
                //   - 接口 `const` 走 translateInterfaceConstant，恒为 static；
                //   - 其余（含类里的 `const`）走 translateProperty，静态性只看 `static` 修饰符。
                val isStatic = (isInterface && member.isConst) || hasModifier(member.modifiers, "static")
                if (isStatic) ctx.staticFieldSymbols.add(symbol)
            }
        }
        for (decl in ast.declarations) {
            when (decl) {
                is JasClass -> record(decl.name, decl.members, isInterface = false)
                is JasInterface -> record(decl.name, decl.members, isInterface = true)
                is JasEnum -> record(decl.name, decl.members, isInterface = false)
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
        // 标签/循环标志是函数内局部状态，不能泄漏到下一个函数。
        ctx.loopLabels.clear()
        ctx.loopBreakFlags.clear()
        ctx.pendingLoopLabel = null
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
        } else if (hasModifier(func.modifiers, "static")) {
            // 静态成员函数：复用预登记符号，保证声明处与 `A.f()` 调用点共享同一符号身份，
            // 后端才能由符号反查 owner 并发出 ACC_STATIC（否则调用与声明会指向两个符号）。
            ctx.staticMemberFunctionSymbolsByType[ctx.currentClassName]?.get(func.name)
                ?: IrSimpleFunctionSymbolImpl()
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
            (body.statements as MutableList<IrStatement>).addAll(translateStatementSequence(bodyStatements, ctx))
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
                // 与登记口径一致：先把类型名解析成 JVM 内部名，再查「是否提供 hash()」。
                val hashMethod = if (typeMapper.resolveNamedTypeName(type.name) in ctx.hashableClassNames) "hash" else "hashCode"
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
        // 键必须是 JVM 内部名：跨包同名类（p1.Impl / p2.Impl）若按简单名存表会互相覆盖，
        // 导致后端把其中一个类错误地实现成另一个包的接口。
        ctx.interfaceNames[qualifiedClassName] = ifaceNames.toMutableList()
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
        // 同样按 JVM 内部名建键，避免跨包同名类的父类信息互相覆盖。
        ctx.classSuperclassNames[qualifiedClassName] = qualifiedSuper
        if (cls.constructors.isEmpty()) {
            // 无显式构造器时补一个默认无参 <init>；否则类无法被 new（后端要求至少一个可调用构造器）。
            addDefaultConstructor(irCls)
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

    /**
     * 做什么：为没有显式构造器的类/枚举补一个 public 无参构造器。
     * 什么时候用：translateClass / translateEnum 在成员翻译完成后调用。
     * 前置条件：irCls 已创建并已挂到当前 IR 文件；默认体为空，不引用任何成员。
     * 失败模式：不抛异常；调用方负责判断「确实没有显式构造器」。
     * 为什么集中：普通类与枚举的默认构造器语义一致（空体；枚举的 (name,ordinal)
     *   前导参数由后端 `generateEnumConstructor` 统一补齐），两份实现会逐渐漂移。
     */
    private fun addDefaultConstructor(irCls: IrClass) {
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
            // 委托调用的实参同样按目标构造器的声明形参宽化（C13）。后端用实参 IR 类型
            // 拼 `<init>` 描述符，若不在此处插 CAST，`super(new MemRepo())` 会写出
            // 父类不存在的 `(LMemRepo;)V`。this(...) 的 owner 是当前类，super(...)
            // 是当前父类；外部父类查不到签名时保持原样（旧行为）。
            val delegateOwner = if (delegateCall.isSuper) ctx.currentSuperclassName else ctx.currentClassName
            val declaredParams = ctx.constructorParamTypesByType[delegateOwner]
                ?.get(memberArityKey("<init>", delegateCall.args.size))
            val translatedArgs = delegateCall.args.map { translateExpression(it, ctx) }
            // Include `this` as first arg so backend emits ALOAD 0
            val allArgs = mutableListOf<IrExpression>()
            val thisGet = IrNodeFactory.createGetValue(0, 0, typeMapper.unitType(), IrValueParameterSymbolImpl(), null)
            allArgs.add(thisGet)
            allArgs.addAll(translatedArgs.mapIndexed { i, arg ->
                if (declaredParams != null && i < declaredParams.size) {
                    castToDeclared(declaredParams[i], arg)
                } else {
                    arg
                }
            })
            val delegateCallExpr = IrNodeFactory.createCall(
                0, 0, typeMapper.unitType(), null,
                allArgs.toTypedArray(),
                emptyArray(), delegateSymbol, null
            )
            (body.statements as MutableList<IrStatement>).add(delegateCallExpr)
        }
        if (ctor.body != null) {
            (body.statements as MutableList<IrStatement>).addAll(translateStatementSequence(ctor.body!!.statements, ctx))
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
        // 接口身份同样按 JVM 内部名记录：跨包同名接口（p1.Thing / p2.Thing）必须可区分，
        // 后端据此决定接口标志、默认方法/抽象方法处理，以及调用点用 INVOKEINTERFACE。
        val qualifiedInterfaceName = typeMapper.resolveNamedTypeName(iface.name)
        ctx.interfaceDeclNames.add(qualifiedInterfaceName)
        // 接口常量也按类名建索引（`fieldSymbolsByClass[接口内部名]`），
        // 这样 `Limits.MAX_ROWS` 的静态访问可以通过类名直接解析到字段符号，
        // 而不必退化成「全局按名查找」这一可能与其他类同名常量冲突的兜底路径。
        val previousClassName = ctx.currentClassName
        ctx.currentClassName = qualifiedInterfaceName
        // Record extended interfaces
        // 记录继承的父接口（补全为 JVM 风格内部名），导出给后端生成接口表。
        // 父接口名走中央解析（import/点分包名），避免非法内部名。
        val ifaceNames = iface.extends.mapNotNull { ext ->
            if (ext is JasNamedType) typeMapper.resolveNamedTypeName(ext.name) else null
        }
        ctx.interfaceNames[qualifiedInterfaceName] = ifaceNames.toMutableList()
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
        // 恢复外层上下文，避免接口常量/默认方法体里的字段归属泄漏到后续声明。
        ctx.currentClassName = previousClassName
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
        // 复用预登记符号/类型：接口常量的读取点（Statement.MAX_ROWS）可能在声明文件之前翻译，
        // 必须与声明处共享同一 IrFieldSymbol 身份，否则后端按符号反查不到字段名。
        val predeclaredSymbol = ctx.fieldSymbolsByClass[ctx.currentClassName]?.get(prop.name)
        val propType = ctx.fieldTypesByClass[ctx.currentClassName]?.get(prop.name) ?: typeMapper.mapType(prop.type)
        val fieldSymbol = predeclaredSymbol ?: IrFieldSymbolImpl()
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

    /**
     * 做什么：把一个 `JasEnum` 翻译成 IR 枚举类（ENUM_CLASS）：枚举项 + 成员 + 默认构造器。
     * 什么时候用：translateTopLevelDeclaration 遇到顶层枚举声明时。
     * 前置条件：枚举常量符号已由 [predeclareEnumConstants] 预登记；ctx 处于该枚举所在文件/包。
     * 失败模式：不抛异常；成员翻译失败时按通用翻译规则静默跳过/降级。
     * 为什么这样设计：
     *   - 枚举项保持为 IrEnumEntry（Kotlin IR 对枚举常量的原生表示），后端据此发出
     *     `public static final ACC_ENUM` 字段并在 `<clinit>` 里实例化；不再用「字段名首字母
     *     大写」的启发式猜测常量，语义与表示一致（隔离的变化点：常量识别规则只存在于后端
     *     对 IrEnumEntry 的处理）。
     *   - 枚举成员与普通类共用 [translateClassMember]，避免两套成员翻译逻辑。
     *   - 设置 currentClassName：枚举体内的属性/静态字段与 `this` 访问都依赖它定位宿主。
     */
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
        // 枚举和普通类一样需要「当前类」上下文；否则枚举体里的成员字段会落到错误宿主，
        // 后端访问时拿不到 owner。
        val qualifiedClassName = typeMapper.resolveNamedTypeName(enum.name)
        val previousClassName = ctx.currentClassName
        ctx.currentClassName = qualifiedClassName
        // 常量符号必须复用预登记的那一份（同一声明/使用共享身份）；缺失时补建，兼容直接
        // 调用 translateEnum 而没走预登记的内部路径。
        val constantSymbols = ctx.enumConstantSymbolsByType.getOrPut(qualifiedClassName) { mutableMapOf() }
        for (const in enum.constants) {
            irCls.declarations.add(translateEnumConstant(const, constantSymbols))
        }
        for (member in enum.members) {
            val irMember = translateClassMember(member, ctx, symbol)
            if (irMember != null) {
                irCls.declarations.add(irMember)
                // 属性背后的字段要作为独立 IrField 出现在类体里（IR 约定），与 translateClass 一致。
                if (irMember is IrProperty && irMember.backingField != null) {
                    irCls.declarations.add(irMember.backingField!!)
                }
            }
        }
        // 枚举的 JVM 构造器必须存在：后端会在 <clinit> 中用 (String,int[,用户参数]) 调用它。
        // 没有显式构造器时补默认无参构造器（后端自动补 (name,ordinal) 前导参数）。
        if (enum.members.none { it is JasConstructor }) {
            addDefaultConstructor(irCls)
        }
        ctx.currentClassName = previousClassName
        return irCls
    }

    /**
     * 做什么：把一个枚举常量翻译成 IR 枚举项（IrEnumEntry）。
     * 什么时候用：[translateEnum] 遍历 constants 时。
     * 前置条件：constantSymbols 是当前枚举「常量名 -> 符号」的索引（由预登记阶段建立）。
     * 失败模式：无；符号缺失时补建并写回索引，保证声明处与使用处仍共享同一身份。
     * 为什么复用符号：后端 pass 1 按 IrEnumEntry.symbol 建立「符号 -> owner/名字」索引，
     *   使用处（IrGetEnumValue）引用同一符号才能解析到同一字段。
     */
    private fun translateEnumConstant(
        const: JasEnumConstant,
        constantSymbols: MutableMap<String, IrEnumEntrySymbol>
    ): IrEnumEntry {
        val entrySymbol = constantSymbols.getOrPut(const.name) { IrEnumEntrySymbolImpl() }
        return factory.createEnumEntry(0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(const.name), entrySymbol)
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

    /**
     * 做什么：判断修饰符列表（`List<Any>`：字符串关键字 + 注解节点）里是否含某个关键字。
     * 什么时候用：翻译 `static` / `final` / `private` 等声明修饰符时。
     * 前置条件：modifiers 来自 AST builder 的 doExtractMods，普通关键字均为小写 String。
     * 失败模式：无；非字符串元素（注解）一律不匹配。
     * 为什么这样设计：AST 用 `List<Any>` 混存注解与关键字，集中成一个判据能避免各调用点
     *   对元素类型做不一致的假设。
     */
    private fun hasModifier(modifiers: List<Any>, keyword: String): Boolean =
        modifiers.any { it is String && it == keyword }

    /**
     * 做什么：把 Jasper 字段声明的访问修饰符映射为 IR 可见性。
     * 什么时候用：创建 [IrField] 时。
     * 前置条件：modifiers 已由 AST builder 归一为小写关键字。
     * 失败模式：无显式 private/protected 时返回 PUBLIC——这是本语言既有的默认可见性，
     *   保持它可避免把历史代码从「字段实际 public」改成包私有。
     * 为什么这样设计：只收敛到一个函数，后端无需理解 Jasper 修饰符文本。
     */
    private fun declaredFieldVisibility(modifiers: List<Any>): DescriptorVisibility = when {
        hasModifier(modifiers, "private") -> DescriptorVisibilities.PRIVATE
        hasModifier(modifiers, "protected") -> DescriptorVisibilities.PROTECTED
        else -> DescriptorVisibilities.PUBLIC
    }

    private fun translateProperty(prop: JasProperty, classSymbol: IrClassSymbol, ctx: TranslationContext): IrProperty? {
        val propSymbol = IrPropertySymbolImpl()
        // 复用预登记阶段登记的类型与字段符号（若存在）。这是「调用点与声明点共享同一符号身份」
        // 的关键：访问点（resolveFieldSymbol）在声明文件之前翻译时拿到的就是这个预登记符号，
        // 后端第一遍按 IrField 建立「符号 → 名字/owner」索引时登记的也是它；若此处另建一个新符号，
        // 访问点的符号就永远查不到名字（INTERNAL: unresolved field symbol）。
        // 没有预登记值时才新建（防御：兼容直接调用 translateProperty、未走预登记的内部路径）。
        val predeclaredSymbol = ctx.fieldSymbolsByClass[ctx.currentClassName]?.get(prop.name)
        val propType = ctx.fieldTypesByClass[ctx.currentClassName]?.get(prop.name) ?: typeMapper.mapType(prop.type)
        // 静态性只由声明修饰符决定（接口常量走独立入口）。它同时影响三处：
        // 字段的 ACC_STATIC、字段符号是否进入 staticFieldSymbols（决定 GETSTATIC/PUTSTATIC），
        // 以及声明初值是进 <clinit> 还是按实例字段忽略。
        val isStatic = hasModifier(prop.modifiers, "static")
        val isFinal = prop.isConst || hasModifier(prop.modifiers, "final")
        val fieldVisibility = declaredFieldVisibility(prop.modifiers)
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
        val fieldSymbol = predeclaredSymbol ?: IrFieldSymbolImpl()
        val irField = factory.createField(
            0, 0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(prop.name),
            fieldVisibility,
            fieldSymbol,
            propType,
            isFinal,
            isStatic,
            false
        )
        irProp.backingField = irField
        if (isStatic) {
            // 静态字段的符号必须进入该集合：后端据此发 GETSTATIC/PUTSTATIC、加 ACC_STATIC，
            // 并把声明初值放进 <clinit>。漏登记正是 Q17 三个探针 NPE 的根因。
            ctx.staticFieldSymbols.add(fieldSymbol)
            // 声明初值只对静态字段生效；实例字段仍按既有语义在构造器里赋值。
            // 后端会对 final 字面量写 ConstantValue，对非 final / 非字面量写 <clinit>。
            if (prop.initializer != null) {
                irField.initializer = factory.createExpressionBody(
                    0, 0, translateExpression(prop.initializer!!, ctx)
                )
            }
        }
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
            (body.statements as MutableList<IrStatement>).addAll(translateStatementSequence(accessor.body!!.statements, ctx))
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
            is JasLabeledStatement -> {
                val previous = ctx.pendingLoopLabel
                ctx.pendingLoopLabel = stmt.label
                val translated = translateStatement(stmt.statement, ctx)
                ctx.pendingLoopLabel = previous
                translated
            }
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
        (body.statements as MutableList<IrStatement>).addAll(translateStatementSequence(block.statements, ctx))
        return body
    }

    /**
     * 翻译一个作用域（源码块 / 函数体）的语句序列，并按规范 10.8 降级其中的 defer。
     *
     * 做什么：遇到 `defer D` 时，把同一序列中 D 之后的全部语句翻译为 try 体、D 为 finally 体，
     *   即 `defer D; S…` ≡ `try { S… } finally { D }`；其后的 defer 在递归中嵌套到内层。
     * 什么时候用：translateBlock 与函数 / 构造函数 / 访问器 / lambda 体的翻译。
     * 为什么这样设计：defer「在当前作用域结束时执行」——作用域的每条离开路径（正常结束、return、
     *   break / continue、异常）恰好执行一次，只执行已到达的 defer，同块后声明的先执行，return 值
     *   先于 defer 固定。这些正是 try/finally 的语义，后端已为 finally 生成三条路径，无需另设展开表；
     *   协程帧（CoroutineBlockPlanner）采用同一改写，两条路径语义一致。
     */
    private fun translateStatementSequence(statements: List<JasStatement>, ctx: TranslationContext): List<IrStatement> {
        val result = mutableListOf<IrStatement>()
        for ((index, stmt) in statements.withIndex()) {
            if (stmt is JasDefer) {
                val irTry = IrNodeFactory.createTry(0, 0, typeMapper.unitType())
                val tryBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
                (tryBody.statements as MutableList<IrStatement>).addAll(
                    translateStatementSequence(statements.subList(index + 1, statements.size), ctx)
                )
                irTry.tryResult = tryBody
                irTry.finallyExpression = translateBlock(stmt.body, ctx)
                result.add(irTry)
                return result
            }
            val irStmt = translateStatement(stmt, ctx)
            if (irStmt != null) result.add(irStmt)
        }
        return result
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

    private fun translateWhile(stmt: JasWhile, ctx: TranslationContext): IrStatement {
        if (stmt.suffix != null) {
            val outer = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
            val entered = declareBoolLocal(outer, nextTempName(ctx, "entered").asString(), typeMapper.falseConst())
            val broke = declareBoolLocal(outer, nextTempName(ctx, "broke").asString(), typeMapper.falseConst())
            val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
            consumeLoopLabel(irLoop, ctx)
            ctx.loopBreakFlags[irLoop] = broke
            ctx.loopStack.add(irLoop)
            irLoop.condition = translateExpression(stmt.condition, ctx)
            val body = translateBlock(stmt.body, ctx)
            val bodyWithEntered = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
            (bodyWithEntered.statements as MutableList<IrStatement>).add(setBoolLocal(entered))
            (bodyWithEntered.statements as MutableList<IrStatement>).addAll(body.statements)
            irLoop.body = bodyWithEntered
            ctx.loopStack.removeAt(ctx.loopStack.size - 1)
            (outer.statements as MutableList<IrStatement>).add(irLoop)
            appendLoopSuffix(outer, stmt.suffix, entered, broke, ctx)
            return outer
        }
        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        consumeLoopLabel(irLoop, ctx)
        ctx.loopStack.add(irLoop)
        irLoop.condition = translateExpression(stmt.condition, ctx)
        irLoop.body = translateBlock(stmt.body, ctx)
        ctx.loopStack.removeAt(ctx.loopStack.size - 1)
        return irLoop
    }

    /** 在块开头声明一个布尔临时变量。 */
    private fun declareBoolLocal(
        block: IrBlock,
        name: String,
        initial: IrExpression
    ): IrVariableSymbolImpl {
        val symbol = IrVariableSymbolImpl()
        val variable = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(name),
            typeMapper.boolType(), symbol, true, false, false
        )
        variable.initializer = initial
        (block.statements as MutableList<IrStatement>).add(variable)
        return symbol
    }

    private fun boolGet(symbol: IrVariableSymbolImpl): IrExpression =
        IrNodeFactory.createGetValue(0, 0, typeMapper.boolType(), symbol, null)

    private fun setBoolLocal(symbol: IrVariableSymbolImpl): IrStatement =
        IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), symbol, IrStatementOrigin.EQ, typeMapper.trueConst())

    /** 在循环后按 broke → entered → zero 的顺序生成互斥子句。 */
    private fun appendLoopSuffix(
        block: IrBlock,
        suffix: LoopSuffix?,
        entered: IrVariableSymbolImpl,
        broke: IrVariableSymbolImpl,
        ctx: TranslationContext
    ) {
        if (suffix == null) return
        val whenNode = IrNodeFactory.createWhen(0, 0, typeMapper.unitType(), IrStatementOrigin.IF)
        suffix.onBreak?.let {
            whenNode.branches.add(IrNodeFactory.createBranch(0, 0, boolGet(broke), translateBlock(it, ctx)))
        }
        suffix.onComplete?.let {
            whenNode.branches.add(IrNodeFactory.createBranch(0, 0, boolGet(entered), translateBlock(it, ctx)))
        }
        suffix.onZeroRuns?.let {
            whenNode.branches.add(IrNodeFactory.createBranch(0, 0, typeMapper.trueConst(), translateBlock(it, ctx)))
        }
        if (whenNode.branches.isNotEmpty()) {
            (block.statements as MutableList<IrStatement>).add(whenNode)
        }
    }

    private fun translateDoWhile(stmt: JasDoWhile, ctx: TranslationContext): IrDoWhileLoop {
        val irLoop = IrNodeFactory.createDoWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.DO_WHILE_LOOP)
        consumeLoopLabel(irLoop, ctx)
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
        consumeLoopLabel(irLoop, ctx)
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
        // 集合的迭代语义由语言内建元数据决定，必须先于通用的「描述符前缀」分派：
        //   - List/MutableList/ArrayList → 下标循环（c:size / c:get），元素类型取类型实参；
        //   - Map/dict/HashMap → keySet().iterator() 循环，循环变量是键类型。
        // 如果落到下面的 "L" 分支，List 会被误当成实现了 hasNext/next 的迭代协议对象，
        // 生成对 java/util/List.hasNext 的调用并在链接期失败。
        val iterableClassName = typeMapper.irTypeClassName(iterableExpr.type)
        return when (iterableClassName?.let(BuiltinCollections::familyOf)) {
            CollectionFamily.LIST -> translateForInIndexed(
                stmt,
                iterableExpr,
                ctx,
                typeMapper.irCollectionElementType(iterableExpr.type),
                sizeCallName = "c:size",
                getCallName = "c:get"
            )
            CollectionFamily.MAP -> translateForInMapKeys(stmt, iterableExpr, ctx)
            null -> when {
                desc.startsWith("[") -> translateForInIndexed(
                    stmt,
                    iterableExpr,
                    ctx,
                    arrayElementType(iterableExpr.type),
                    sizeCallName = "size",
                    getCallName = "get"
                )
                // Arrays keep the index-based lowering. A declared object type is treated
                // as an instance of the iteration protocol (`hasNext()` / `next()`); any
                // other/unknown shape falls back to the array lowering to preserve the
                // previous behaviour.
                // 分派规则：数组描述符以 "[ " 开头 → 按下标循环；对象描述符以 "L" 开头 → 按迭代协议（hasNext/next）；
                // 其余/未知形态回退到数组 lowering，保持既有行为（不引入新的语法）。
                desc.startsWith("L") -> translateForInIterator(stmt, iterableExpr, ctx)
                else -> translateForInIndexed(
                    stmt,
                    iterableExpr,
                    ctx,
                    arrayElementType(iterableExpr.type),
                    sizeCallName = "size",
                    getCallName = "get"
                )
            }
        }
    }

    /**
     * 做什么：把「下标可寻址序列」的 for-in 降为 `while (i < seq.size()) { elem = seq.get(i); ... }`。
     *
     * 什么时候用：数组、List/MutableList 的 for-in；前者的 size/get 由后端数组指令特判，
     *   后者走 `c:` 集合调用。
     * 前置条件：iterableExpr 已翻译且恰好求值一次；elemType 已按接收者解析。
     * 失败模式：不抛异常；size/get 的真实描述符由后端按接收者平台类型解析，解析失败时
     *   由验证器暴露（属于前端/后端协议破坏）。
     * 为什么用参数而不是复制两份循环：数组与集合的下标循环在控制流、break/continue、
     *   then/else 语义上完全一致，差异只在 size/get 的调用名与元素类型。把差异参数化，
     *   避免两套循环各自演化（隔离的变化点：序列种类）。
     */
    private fun translateForInIndexed(
        stmt: JasForInStatement,
        iterableExpr: IrExpression,
        ctx: TranslationContext,
        elemType: IrType,
        sizeCallName: String,
        getCallName: String
    ): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

        val arrSymbol = IrVariableSymbolImpl()
        val arrVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "arr"),
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
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "entered"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)
        val brokeSymbol = if (stmt.suffix != null) {
            declareBoolLocal(block, nextTempName(ctx, "broke").asString(), typeMapper.falseConst())
        } else {
            null
        }

        val elemSymbol = IrVariableSymbolImpl()
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        consumeLoopLabel(irLoop, ctx)
        if (brokeSymbol != null) {
            ctx.loopBreakFlags[irLoop] = brokeSymbol
        }
        ctx.loopStack.add(irLoop)

        val sizeSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[sizeSymbol] = sizeCallName
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

        // 下标在循环体开头自增、元素读取用 i-1：这样 `continue` 跳到条件时
        // 不会跳过自增，避免 for-in 数组循环无限执行。
        val incSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[incSymbol] = "inc"
        val incCall = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null)),
            emptyArray(), incSymbol, null
        )
        val loopBody = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.intType(), idxSymbol, IrStatementOrigin.EQ, incCall)
        )
        (loopBody.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), didRunSymbol, IrStatementOrigin.EQ, typeMapper.trueConst())
        )

        val getSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = getCallName
        val minusSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[minusSymbol] = "-"
        val readIndex = IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, typeMapper.intType(), idxSymbol, null),
                typeMapper.intConst(1)
            ), emptyArray(), minusSymbol, null
        )
        elemVar.initializer = IrNodeFactory.createCall(
            0, 0, elemType, null,
            arrayOf(
                IrNodeFactory.createGetValue(0, 0, iterableExpr.type, arrSymbol, null),
                readIndex
            ), emptyArray(), getSymbol, null
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
        if (stmt.suffix != null && brokeSymbol != null) {
            appendLoopSuffix(block, stmt.suffix, didRunSymbol, brokeSymbol, ctx)
        }
        return block
    }

    /**
     * 做什么：把 Map/dict 的 for-in 降为「遍历键集合」的循环：
     *   `val it$ = map.keySet().iterator(); while (it$.hasNext()) { key = it$.next(); ... }`。
     *
     * 什么时候用：translateForIn 判定可迭代类型属于 MAP 族时。
     * 前置条件：iterableExpr 已翻译且只求值一次；键类型取 Map 的第 0 个类型实参。
     * 失败模式：keySet/iterator/hasNext/next 的真实描述符由后端平台解析器给出；
     *   任何一环失败都会在链接期暴露，而不是静默生成错误的下标循环。
     * 为什么遍历键而不是值：语言层 `for (k in map)` 的直觉是迭代「条目视图」；v1
     *   选择键语义，既能让 `dict<string,int32>` 的循环变量保持 `string`，也不需要
     *   引入新的 `Map.Entry` 类型。把该语义固定在翻译层，检查器与 for-in 类型推断
     *   引用同一个 [BuiltinCollections.keyType]，两边不会漂移。
     */
    private fun translateForInMapKeys(
        stmt: JasForInStatement,
        iterableExpr: IrExpression,
        ctx: TranslationContext
    ): IrStatement {
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), IrStatementOrigin.FOR_LOOP)

        val mapSymbol = IrVariableSymbolImpl()
        val mapVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "map"),
            iterableExpr.type, mapSymbol, true, false, false
        )
        mapVar.initializer = iterableExpr
        (block.statements as MutableList<IrStatement>).add(mapVar)

        // keySet()：HashMap/Map 上的平台方法，返回 java.util.Set。
        // IR 类型显式写 java.util.Set，后端才能据其得到真实返回描述符并补 CHECKCAST。
        val keySetSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[keySetSymbol] = "v:keySet"
        val keySetType = typeMapper.type("java.util.Set")
        val keySetCall = IrNodeFactory.createCall(
            0, 0, keySetType, null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, iterableExpr.type, mapSymbol, null)),
            emptyArray(), keySetSymbol, null
        )

        val itSymbol = IrVariableSymbolImpl()
        val itVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier("it$"),
            typeMapper.type("java.util.Iterator"), itSymbol, true, false, false
        )
        val iteratorSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[iteratorSymbol] = "v:iterator"
        itVar.initializer = IrNodeFactory.createCall(
            0, 0, itVar.type, null,
            arrayOf(keySetCall), emptyArray(), iteratorSymbol, null
        )
        (block.statements as MutableList<IrStatement>).add(itVar)

        val didRunSymbol = IrVariableSymbolImpl()
        val didRunVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "entered"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)
        val brokeSymbol = if (stmt.suffix != null) {
            declareBoolLocal(block, nextTempName(ctx, "broke").asString(), typeMapper.falseConst())
        } else {
            null
        }

        val elemType = typeMapper.irCollectionKeyType(iterableExpr.type)
        val elemSymbol = IrVariableSymbolImpl()
        val elemVar = IrNodeFactory.createVariable(
            0, 0, IrDeclarationOrigin.DEFINED, Name.identifier(stmt.varName),
            elemType, elemSymbol, true, false, false
        )
        ctx.symbols[stmt.varName] = elemSymbol
        ctx.varTypes[stmt.varName] = elemType

        val irLoop = IrNodeFactory.createWhileLoop(0, 0, typeMapper.unitType(), IrStatementOrigin.WHILE_LOOP)
        consumeLoopLabel(irLoop, ctx)
        if (brokeSymbol != null) ctx.loopBreakFlags[irLoop] = brokeSymbol
        ctx.loopStack.add(irLoop)

        val hasNextSymbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[hasNextSymbol] = "v:hasNext"
        irLoop.condition = IrNodeFactory.createCall(
            0, 0, typeMapper.boolType(), null,
            arrayOf(IrNodeFactory.createGetValue(0, 0, itVar.type, itSymbol, null)),
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
            arrayOf(IrNodeFactory.createGetValue(0, 0, itVar.type, itSymbol, null)),
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
        if (stmt.suffix != null && brokeSymbol != null) {
            appendLoopSuffix(block, stmt.suffix, didRunSymbol, brokeSymbol, ctx)
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
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "entered"),
            typeMapper.boolType(), didRunSymbol, true, false, false
        )
        didRunVar.initializer = typeMapper.falseConst()
        (block.statements as MutableList<IrStatement>).add(didRunVar)
        val brokeSymbol = if (stmt.suffix != null) {
            declareBoolLocal(block, nextTempName(ctx, "broke").asString(), typeMapper.falseConst())
        } else {
            null
        }

        // Type the loop variable from the concrete iterable's `next()` member.
        val iterableClass = typeMapper.irTypeClassName(iterableExpr.type)
        val elemType = (iterableClass?.let { ctx.memberReturnTypesByType[it]?.get(memberArityKey("next", 0)) })
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
        consumeLoopLabel(irLoop, ctx)
        if (brokeSymbol != null) ctx.loopBreakFlags[irLoop] = brokeSymbol
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
        if (stmt.suffix != null && brokeSymbol != null) {
            appendLoopSuffix(block, stmt.suffix, didRunSymbol, brokeSymbol, ctx)
        }
        return block
    }

    private fun arrayElementType(arrayType: IrType): IrType {
        // 统一委托 TypeMapper：元素类型解析是「类型 ↔ 描述符」映射的一部分，
        // 放在类型中枢可同时服务数组读、for-in 与后端指令选择，避免各处重写。
        return typeMapper.irArrayElementType(arrayType)
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
        // defer 已降级为包住其后语句的 try/finally（见 translateStatementSequence），
        // 后端在返回值固定之后、真正返回之前执行外层 finally。
        val value = if (stmt.value != null) translateExpression(stmt.value!!, ctx) else typeMapper.unitExpr()
        val target = ctx.currentFunction?.symbol ?: IrSimpleFunctionSymbolImpl()
        return IrNodeFactory.createReturn(0, 0, value.type, value, target)
    }

    private fun translateBreak(stmt: JasBreakStatement, ctx: TranslationContext): IrStatement {
        // 带标签的 break 指向命名循环；否则指向最近循环。无目标时退化为 no-op。
        val loop = stmt.label?.let { ctx.loopLabels[it] } ?: ctx.loopStack.lastOrNull()
            ?: return typeMapper.unitExpr()
        val breakStmt = IrNodeFactory.createBreak(0, 0, typeMapper.unitType(), loop)
        val flag = ctx.loopBreakFlags[loop] ?: return breakStmt
        val block = IrNodeFactory.createBlock(0, 0, typeMapper.unitType(), null)
        (block.statements as MutableList<IrStatement>).add(
            IrNodeFactory.createSetValue(0, 0, typeMapper.boolType(), flag, IrStatementOrigin.EQ, typeMapper.trueConst())
        )
        (block.statements as MutableList<IrStatement>).add(breakStmt)
        return block
    }

    private fun translateContinue(stmt: JasContinueStatement, ctx: TranslationContext): IrStatement {
        val loop = stmt.label?.let { ctx.loopLabels[it] } ?: ctx.loopStack.lastOrNull()
            ?: return typeMapper.unitExpr()
        return IrNodeFactory.createContinue(0, 0, typeMapper.unitType(), loop)
    }

    /** 让被 labeledStatement 包裹的第一个循环消费标签。 */
    private fun consumeLoopLabel(loop: IrLoop, ctx: TranslationContext) {
        val label = ctx.pendingLoopLabel ?: return
        ctx.loopLabels[label] = loop
        ctx.pendingLoopLabel = null
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
        val retVal: IrExpression = IrNodeFactory.createGetValue(0, 0, value.type, yieldSymbol, null)
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
            is JasArrayLiteral -> translateArrayLiteral(expr, ctx)
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
                // 字面量的静态类型优先使用检查器回填的具体 `dict<K,V>`：它保留了
                // 键/值类型，决定 get/put 的调用点 IR 类型与后续拆箱/CHECKCAST。
                // 没有回填时退回裸 HashMap，保持旧行为（独立构造 AST 的调用方）。
                val resultType = expr.inferredType?.let(typeMapper::mapType)
                    ?: typeMapper.type("java.util.HashMap")
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
                    // `c:` 前缀让后端强制使用平台真实签名 `Map.put(Object,Object)Object`，
                    // 而不是旧的 JDK 容器「返回接收者」流畅 API 兜底。字面量中的 put 结果
                    // 永远被丢弃，因此调用点类型记为 any（=Object）：绝不能记成值类型，
                    // 否则后端会对 HashMap.put 返回的 null（新键没有旧值）做拆箱并抛 NPE。
                    ctx.callNames[putSymbol] = "c:put"
                    val putCall = IrNodeFactory.createCall(
                        0, 0, typeMapper.type("kotlin.Any"), null,
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
        // await 的兼容占位路径先于通用一元运算分派；它尚未实现协程挂起。
        when (expr.op) {
            // await：当前没有真实的挂起运行时，等待语义按「操作数同步求值完成」实现——
            // 直接返回操作数的求值结果，其 IR 类型即操作数类型。
            // 旧实现把 await 当成未知一元运算，降级为对名为 "unknown" 的调用，
            // 生成的描述符/宿主都不对（运行期 NoSuchMethodError: ...unknown(...)）。
            // S4-C 必须在函数级生成 continuation/状态机，并把完成通知接回调度队列。
            // 不可只在此调用 Future.get()：阻塞工作线程不满足非阻塞挂起契约。
            "await" -> return translateExpression(expr.operand, ctx)
            // 注意：`go` 前缀运算符已于 2026-10-17 从语言中移除（用户裁决 A）。
            // 当前任务创建由标准库 TaskGroup.spawn 提供并返回 Future；await 挂起仍待 S4-C。
            // 这样任务有句柄 ⇒ 可取消、可跟踪模块归属（.jaso 卸载依赖活跃引用）。
        }
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
        // 检查器已确认的全限定平台静态调用：生成无接收者 IR，并把 owner/方法名作为
        // 显式协议交给后端。不能落入成员调用路径，否则 `java.lang.Integer` 会被当成值链。
        expr.platformStaticOwner?.let { owner ->
            return translatePlatformStaticCall(expr, owner, ctx)
        }
        // 类名静态方法调用：`A.f()` 与枚举合成方法 `Types.values()`/`valueOf(...)`。
        // 必须在实例成员调用路径之前分流：静态调用不生成接收者实参，后端走 INVOKESTATIC。
        val staticCall = resolveStaticCallTarget(expr, ctx)
        if (staticCall != null) {
            return translateStaticCall(expr, staticCall, ctx)
        }
        // 成员调用的接收者只翻译一次：既用于 string 内建方法分流，也用于后续实参列表。
        // 这样 `next().byteLength()` 这类接收者有副作用的表达式在字节码里也只求值一次。
        val memberReceiver = if (expr.target is JasPropertyAccess) {
            translateExpression(expr.target.target, ctx)
        } else {
            null
        }
        // string 的 `byteLength()` 是语言内建方法，JVM 的 String 没有同名方法。
        // 必须在通用成员调用路径之前分流，否则后端会拼出 `String.byteLength()`
        // 的调用描述符，运行期抛 NoSuchMethodError。
        if (memberReceiver != null && expr.target is JasPropertyAccess &&
            expr.target.property == "byteLength" && isStringIrType(memberReceiver.type)
        ) {
            // 参数个数错误由 TypeChecker 负责诊断；这里仅在没有实参时生成字节码。
            if (expr.args.isEmpty()) return emitStringByteLength(memberReceiver, ctx)
        }
        // `string.codePointAt(i)` 的索引单位是 Unicode 码点序号，而 JVM 的
        // `String.codePointAt(int)` 使用 UTF-16 码元索引；两者同名不同义，绝不能
        // 落入通用成员调用路径。这里把「码点索引 → UTF-16 偏移 → 取码点」的转换
        // 交给后端，并保证接收者与索引各只求值一次（顺序：接收者 → 索引）。
        if (memberReceiver != null && expr.target is JasPropertyAccess &&
            expr.target.property == "codePointAt" && isStringIrType(memberReceiver.type)
        ) {
            // 参数个数/类型错误由 TypeChecker 负责诊断；这里只在签名正确时生成字节码。
            if (expr.args.size == 1) {
                val index = translateExpression(expr.args[0], ctx)
                return emitStringCodePointAt(memberReceiver, index, ctx)
            }
        }
        // 语言内建集合成员：按接收者的真实集合族生成 `c:` 调用。必须在通用 v: 成员
        // 路径之前分流，否则后端会命中旧 JDK 容器兜底（get/set/remove 返回接收者），
        // 与语言层声明的 get/size/containsKey/put 返回类型不一致。
        val collectionReturn = translateCollectionCallIfApplicable(expr, memberReceiver, ctx)
        if (collectionReturn != null) return collectionReturn
        // 成员调用必须使用**独立的**调用符号：后端用符号身份查 [TranslationContext.callNames]，
        // 若复用同名顶层函数的符号，`subtract(10, 3)` 与 `big.subtract(other)` 会互相覆盖
        // 调用名（一个写成 `subtract`，另一个写成 `v:subtract`），最终把成员调用发成
        // INVOKESTATIC 并触发 VerifyError。顶层函数符号只服务于不带接收者的调用。
        val symbol = if (expr.target is JasPropertyAccess) {
            IrSimpleFunctionSymbolImpl()
        } else {
            ctx.functionSymbols[name] ?: IrSimpleFunctionSymbolImpl()
        }
        ctx.callNames[symbol] = if (expr.target is JasPropertyAccess) "v:$name" else name
        // 成员调用：记录本次调用应使用的索引键（源码元数或 vararg 通配）。
        // backend 只看到打包后的 IR 实参，无法自行区分 `q(string)` 与 `q(int32...)`；
        // 由 translator 把「检查器选中的那个重载」所需的键带上（隔离的变化点：描述符来源）。
        val memberIndexKey = if (expr.target is JasPropertyAccess) {
            if (expr.varargCall != null) memberVarargKey(name) else memberArityKey(name, expr.args.size)
        } else null
        if (memberIndexKey != null) ctx.memberCallKeys[symbol] = memberIndexKey
        val args = mutableListOf<IrExpression>()
        if (memberReceiver != null) {
            args.add(memberReceiver)
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
        // 真 varargs（语法裁决②）：按检查器回填的解析结果降级——尾部实参打包成数组，
        // 或最后一个实参本身就是数组时原样传入（转成声明的数组类型，保证调用描述符对齐）。
        expr.varargCall?.let { vararg ->
            val arrayIrType = typeMapper.mapType(vararg.arrayType)
            val start = (if (expr.target is JasPropertyAccess) 1 else 0) + vararg.fixedCount
            if (vararg.passThrough) {
                args[start] = castToDeclared(arrayIrType, args[start])
            } else {
                val tail = args.subList(start, args.size).toList()
                args.subList(start, args.size).clear()
                args.add(buildArrayFromValues(arrayIrType, tail, ctx))
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
        val receiverType = if (expr.target is JasPropertyAccess) args.firstOrNull()?.type else null
        val receiverOwner = receiverType?.let { typeMapper.irTypeClassName(it) }
        val memberReturnType = receiverOwner?.let { owner ->
            memberIndexKey?.let { key -> ctx.memberReturnTypesByType[owner]?.get(key) }
        }
        // 成员调用的返回类型只能来自「接收者的声明类型」；接收者不是本次编译单元
        // 声明的类型时（JDK 类型、未知类型），绝不能回退到全局 functionReturnTypes：
        // 那张表按名字 last-writer-wins，用户恰好声明了同名顶层函数（如 subtract）
        // 时会把平台成员调用的 IR 类型污染成顶层函数的返回类型，进而让后端拼出
        // 错误的 owner/描述符（C27 第二步的同名回归）。类型未知就保持 Unit，交给
        // 后端用 PlatformMethodResolver 按真实签名回填（见 receiverJvmDescriptor）。
        // 判定必须同时看简单名：枚举没有进 memberReturnTypesByType/memberSignaturesByType
        // 索引，但它的成员仍由 translator 的声明信息决定，不能被当成平台类型。
        val receiverSimpleName = receiverOwner?.substringAfterLast('/')
        val receiverHasDeclaredType = receiverOwner != null && (
            receiverSimpleName in ctx.declaredTypeNames ||
                ctx.memberReturnTypesByType.containsKey(receiverOwner) ||
                ctx.memberSignaturesByType.containsKey(receiverOwner)
            )
        val baseReturnType = when {
            memberReturnType != null -> memberReturnType
            expr.target is JasPropertyAccess && !receiverHasDeclaredType -> typeMapper.unitType()
            else -> ctx.functionReturnTypes[name] ?: typeMapper.unitType()
        }
        var returnType = if (expr.inferredTypeArgs != null && expr.inferredTypeArgs!!.isNotEmpty()) {
            substituteIrType(baseReturnType, expr.inferredTypeArgs!!)
        } else {
            baseReturnType
        }
        // 泛型接收者（如 `Repo<Person>`）的返回类型里仍可能是接口声明的类型变量 T；
        // 用接收者实参把它替换成源码视角的具体类型，调用点才能据此补 CHECKCAST。
        returnType = substituteByReceiverTypeArgs(returnType, receiverOwner, receiverType, ctx)
        return IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            irArgs, emptyArray<IrType>(), symbol, null
        )
    }

    /**
     * 做什么：识别「接收者是语言内建集合」的成员调用，按 [BuiltinCollections] 的成员
     *   元数据生成 `c:<method>` 调用，并计算调用点的 IR 返回类型。
     *
     * 什么时候用：[translateCall] 在通用 `v:` 成员路径之前调用。
     * 前置条件：memberReceiver 已由调用方翻译且只翻译一次；expr.target 是属性访问。
     * 失败模式：接收者不是集合、或方法不在集合元数据中时返回 null，调用方继续走通用
     *   成员路径；不抛异常。参数个数/类型已由检查器诊断，本函数不重复报错。
     * 为什么返回 null 而不是抛异常：集合成员与用户类型成员共享同一套调用语法，翻译层
     *   必须能把「不是集合调用」原样交还给既有路径；用 null 表达「不适用」比用异常
     *   表达更符合分派语义。
     * 为什么要 `c:` 前缀：后端对裸 `get/set/remove/put/size` 保留了历史 JDK 容器
     *   「返回接收者」的流畅兜底；集合语义要求真实 JDK 签名（`get→元素/值`、
     *   `put→旧值`、`size→int`）。前缀把「必须走平台真实签名解析」这一事实显式传给
     *   后端，而不是让后端按方法名猜（隔离的变化点：调用点签名来源）。
     */
    private fun translateCollectionCallIfApplicable(
        expr: JasCall,
        memberReceiver: IrExpression?,
        ctx: TranslationContext
    ): IrExpression? {
        val access = expr.target as? JasPropertyAccess ?: return null
        val receiver = memberReceiver ?: return null
        // IR 类型已经过 TypeMapper 归一（MutableList → java.util.List、dict → java.util.HashMap）；
        // 用归一后的类型名判族，避免按源码拼写维护第二套分支。
        val receiverName = typeMapper.irTypeClassName(receiver.type) ?: return null
        if (BuiltinCollections.familyOf(receiverName) == null) return null
        val spec = BuiltinCollections.loweringMember(receiverName, access.property) ?: return null
        val args = ArrayList<IrExpression>(expr.args.size + 1)
        args.add(receiver)
        args.addAll(expr.args.map { translateExpression(it, ctx) })
        // `Map.put` / `Map.remove` 的返回是「旧值」，键不存在时为 null；若把调用点
        // IR 类型记成值类型，后端会在调用后立即拆箱，**即使调用结果被丢弃**（语句
        // 位置）也会对 null 抛 NPE。语言 v1 没有把「旧值」建模为可空，因此这里把
        // IR 调用点类型保守地记为 any；真正把结果赋给具体类型的调用点仍会在赋值/
        // 返回处按目标类型拆箱（语义与 JDK 的 null 一致，不会静默伪造值）。
        val returnType = if (spec.name in COLLECTION_NULLABLE_RESULT_MEMBERS &&
            spec.returnKind == CollectionReturnKind.VALUE
        ) {
            typeMapper.type("kotlin.Any")
        } else {
            typeMapper.irCollectionMemberReturnType(receiver.type, spec)
        }
        val symbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = "c:${spec.name}"
        return IrNodeFactory.createCall(
            0, 0, returnType, IrStatementOrigin.INVOKE,
            args.toTypedArray(), emptyArray(), symbol, null
        )
    }

    /**
     * 做什么：集合中返回「旧值/缺失即 null」的成员名集合。
     * 什么时候用：[translateCollectionCallIfApplicable] 计算调用点 IR 类型时。
     * 为什么单独列出：这些成员的返回值不能安全地按值类型立即拆箱；把「哪些成员
     *   可能返回 null」集中成一个常量，避免在翻译逻辑里散落字符串判断。
     */
    private val COLLECTION_NULLABLE_RESULT_MEMBERS = setOf("put", "remove")

    /**
     * 做什么：用接收者的泛型实参替换声明返回类型里的类型变量（如 `Repo<Person>.get` 的
     *   `T` → `Person`）。
     * 什么时候用：[translateCall] 计算成员调用表达式的静态类型时（经接口/泛型接收者调用）。
     * 前置条件：owner 是接收者的静态类型名；receiverType 的实参顺序与类型声明形参顺序一致
     *   （[TranslationContext.typeParameterNamesByType] 已按声明顺序登记）。
     * 失败模式：owner 未登记类型形参、receiverType 不是简单类型、或实参缺失/为星投影时，
     *   原样返回（退回擦除后的类型），不抛异常。
     * 为什么这样设计：JVM 调用点走「擦除后的接口描述符 + 结果 CHECKCAST」，因此 IR 调用类型
     *   必须是源码视角的具体类型；把「接收者实参绑定到类型变量」收敛到唯一函数，后端不必理解泛型。
     */
    private fun substituteByReceiverTypeArgs(
        type: IrType,
        owner: String?,
        receiverType: IrType?,
        ctx: TranslationContext
    ): IrType {
        if (owner == null || receiverType !is IrSimpleType) return type
        val names = ctx.typeParameterNamesByType[owner] ?: return type
        if (names.isEmpty()) return type
        val bindings = mutableMapOf<String, IrType>()
        for ((i, arg) in receiverType.arguments.withIndex()) {
            if (i >= names.size) break
            val argType = (arg as? IrTypeProjection)?.type
            if (argType != null) bindings[names[i]] = argType
        }
        if (bindings.isEmpty()) return type
        return substituteTypeVariableNames(type, bindings)
    }

    /**
     * 做什么：把一个 IR 类型里「按名字识别出的类型变量」替换为绑定类型（浅替换，只处理顶层）。
     * 什么时候用：[substituteByReceiverTypeArgs] 内部。
     * 前置条件：bindings 的键是类型变量名（如 "T"），值是具体 IR 类型。
     * 失败模式：非类型变量、或未命中的名字原样返回；不做深层（嵌套泛型实参）替换，属 v1 边界。
     * 为什么单独成函数：方法级类型变量（真 IrTypeParameterSymbol）已由 [substituteIrType] 处理，
     *   这里补的是「按名字识别的类级类型变量」这一既存近似表示。
     */
    private fun substituteTypeVariableNames(type: IrType, bindings: Map<String, IrType>): IrType {
        if (type is IrSimpleType) {
            val className = typeMapper.irTypeClassName(type)
            if (className != null) {
                bindings[className]?.let { return it }
            }
        }
        return type
    }

    /**
     * 一次静态方法调用的解析结果。
     *
     * 做什么：把「函数符号」「宿主类内部名」「返回类型」「是否合成方法」成组带出。
     * 什么时候用：[resolveStaticCallTarget] 的返回值、[translateStaticCall] 的输入。
     * 失败模式：仅承载数据，无副作用；字段在解析成功时必定非空。
     * 为什么用数据类：四个值同生同灭，避免调用方在多张索引表里重复查找或拿到半成品状态。
     */
    private data class StaticCallRef(
        val symbol: IrSimpleFunctionSymbol,
        val owner: String,
        val returnType: IrType
    )

    /**
     * 做什么：识别 `A.f(...)` 形式的类名静态调用，返回其符号/宿主/返回类型；不是静态调用时返回 null。
     * 什么时候用：[translateCall] 在实例成员调用路径之前调用。
     * 前置条件：target 的基名未被局部变量遮蔽，且是本次编译声明的类型名（[declaredClassName]）。
     * 失败模式：返回 null，调用方继续走实例成员/顶层函数路径（不抛异常）。
     * 为什么这样设计：把「是不是类名静态调用」收敛到唯一入口，`translateCall` 无需理解
     *   枚举合成方法（values/valueOf）与用户 static 方法在来源上的差异（隔离的变化点）。
     *   只有静态符号存在时才认领：否则可能是 `obj.field` 之类的实例访问，交回原路径。
     */
    private fun resolveStaticCallTarget(expr: JasCall, ctx: TranslationContext): StaticCallRef? {
        val target = expr.target as? JasPropertyAccess ?: return null
        val classKey = declaredClassName(target.target, ctx) ?: return null
        val memberName = target.property
        // 枚举的 values()/valueOf(String) 由后端合成：没有 AST 声明，用新符号 + 宿主索引登记。
        if (classKey in ctx.enumClassKeys) {
            when (memberName) {
                "values" -> {
                    val enumType = typeMapper.type(classKey)
                    val symbol = IrSimpleFunctionSymbolImpl()
                    ctx.syntheticStaticCallOwners[symbol] = classKey
                    ctx.callNames[symbol] = memberName
                    return StaticCallRef(symbol, classKey, typeMapper.arrayType(enumType))
                }
                "valueOf" -> {
                    val enumType = typeMapper.type(classKey)
                    val symbol = IrSimpleFunctionSymbolImpl()
                    ctx.syntheticStaticCallOwners[symbol] = classKey
                    ctx.callNames[symbol] = memberName
                    return StaticCallRef(symbol, classKey, enumType)
                }
            }
        }
        val symbol = ctx.staticMemberFunctionSymbolsByType[classKey]?.get(memberName) ?: return null
        val returnType = ctx.memberReturnTypesByType[classKey]?.get(memberArityKey(memberName, expr.args.size))
            ?: ctx.functionReturnTypes[memberName]
            ?: typeMapper.unitType()
        return StaticCallRef(symbol, classKey, returnType)
    }

    /**
     * 做什么：把已识别的静态调用翻译成无接收者的 IrCall（后端据此发 INVOKESTATIC）。
     * 什么时候用：[resolveStaticCallTarget] 命中后由 [translateCall] 调用。
     * 前置条件：ref 由 [resolveStaticCallTarget] 产出；expr.args 为调用实参（不含接收者）。
     * 失败模式：不抛异常；未识别为 static 的方法不会走到这里（见 resolveStaticCallTarget 的前置判定）。
     * 为什么这样设计：静态调用与实例调用的唯一 IR 差异就是「有没有接收者实参」以及调用名
     *   （静态用普通名字，实例用 "v:" 前缀）。把它独立成函数，调用名协议的变化点只在这里。
     */
    private fun translateStaticCall(expr: JasCall, ref: StaticCallRef, ctx: TranslationContext): IrExpression {
        // 静态调用名必须是普通函数名：后端以 "v:" 前缀区分实例调用，其余一律按 INVOKESTATIC。
        ctx.callNames[ref.symbol] = (expr.target as JasPropertyAccess).property
        val irArgs = expr.args.map { translateExpression(it, ctx) }.toTypedArray()
        return IrNodeFactory.createCall(
            0, 0, ref.returnType, IrStatementOrigin.INVOKE,
            irArgs, emptyArray<IrType>(), ref.symbol, null
        )
    }

    /**
     * 做什么：把检查器确认的平台全限定静态调用翻译成无接收者 IrCall，并登记真实宿主。
     *
     * 什么时候用：[translateCall] 发现 expr.platformStaticOwner 非空时。
     * 前置条件：检查器已通过 PlatformMethodResolver 验证类、静态方法和实参可匹配。
     * 失败模式：不抛异常；缺失方法名等结构错误由上游 AST/checker 保证不会到达。
     * 为什么返回 Unit：平台调用的真实返回类型由后端解析；沿用平台实例调用「调用点未知，
     *   后端回填」的策略，可避免把真实 int 过早装箱成 any，破坏算术调用点。
     */
    private fun translatePlatformStaticCall(
        expr: JasCall,
        owner: String,
        ctx: TranslationContext
    ): IrExpression {
        val target = expr.target as JasPropertyAccess
        val symbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = target.property
        ctx.platformStaticCallOwners[symbol] = owner
        val args = expr.args.map { translateExpression(it, ctx) }.toTypedArray()
        return IrNodeFactory.createCall(
            0, 0, typeMapper.unitType(), IrStatementOrigin.INVOKE,
            args, emptyArray<IrType>(), symbol, null
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

    /**
     * 做什么：识别并翻译「枚举类型名.常量名」（如 `Color.Red`），返回读取枚举项的 IR 表达式。
     * 什么时候用：[translatePropertyAccess] 的第一道分流。
     * 前置条件：target 是未被局部值遮蔽的已声明类型名；枚举常量已在预登记阶段建立符号。
     * 失败模式：target 不是类型名、类型不是枚举、或属性不是该枚举的常量时返回 null，
     *   调用方继续按普通静态/实例字段路径处理。
     * 为什么这样设计：枚举常量在 IR 里是 IrEnumEntry 而非 IrField，读取语义也不同
     *   （JVM 上都是 GETSTATIC，但符号身份必须指向枚举项，后端才能发出正确的字段
     *   描述符与 `<clinit>` 初始化顺序）。把「是不是枚举常量」收敛到本函数，属性路径
     *   无需理解枚举表示（隔离的变化点）。
     */
    private fun translateEnumConstantAccess(expr: JasPropertyAccess, ctx: TranslationContext): IrExpression? {
        val classKey = declaredClassName(expr.target, ctx) ?: return null
        val entrySymbol = ctx.enumConstantSymbolsByType[classKey]?.get(expr.property) ?: return null
        // 枚举值的静态类型就是枚举类自身（读取点的 IR 类型决定后续比较/赋值/参数描述符）。
        val enumType = typeMapper.type(classKey)
        return IrNodeFactory.createGetEnumValue(0, 0, enumType, entrySymbol)
    }

    /**
     * 做什么：把 `target.property` 翻译成字段读取；静态字段与实例字段走两条形态。
     * 什么时候用：表达式位置的属性读取（`Counter.total`、`a.total`、`this.x`）。
     * 前置条件：静态字段的接收者必须是已声明类型名（或已知类型的值，见 [resolveStaticFieldRef]）。
     * 失败模式：字段符号/类型查不到时回退到实例路径（全局按名索引与 Unit 类型兜底）；
     *   真正的「类名访问实例字段」错误已由 TypeChecker 在更早阶段拦截。
     * 为什么这样设计：静态字段在 JVM 指令里**没有接收者**。若仍把类名当值翻译，
     *   就会生成「对一个值为 null 的变量做 GETFIELD」——正是 Q17 探针 NPE 的来源。
     */
    private fun translatePropertyAccess(expr: JasPropertyAccess, ctx: TranslationContext): IrExpression {
        // 枚举常量优先识别：`Color.Red` 的形态与「类名.静态字段」完全相同，但 IR 表示不同
        // （IrGetEnumValue 对应 IrEnumEntry）。若不先分流，会落入字段路径并生成对类名
        // 求值的 GETFIELD——正是 `INTERNAL: Index -1 out of bounds` 的来源。
        translateEnumConstantAccess(expr, ctx)?.let { return it }
        val staticRef = resolveStaticFieldRef(expr.property, expr.target, ctx)
        if (staticRef != null) {
            // 静态读取：不设置 receiver，后端据符号集合发 GETSTATIC。
            return IrNodeFactory.createGetField(
                0, 0, staticRef.type, staticRef.symbol, null, IrStatementOrigin.GET_PROPERTY
            )
        }
        val target = translateExpression(expr.target, ctx)
        // string.length 的语义是 Unicode 码点数，JVM 没有直接对应的字段/指令；
        // 在翻译层分流成合成调用，接收者已在这里求值一次（副作用不会重复）。
        if (isStringIrType(target.type) && expr.property == "length") {
            return emitStringCodePointLength(target, ctx)
        }
        val fieldSymbol = resolveFieldSymbol(expr.property, expr.target, target, ctx)
        val fieldType = resolveFieldType(expr.property, expr.target, target, ctx) ?: typeMapper.unitType()
        val irGetField = IrNodeFactory.createGetField(
            0, 0, fieldType, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY
        )
        // 通过实例访问静态字段（`a.total`）时同样不能生成接收者：JVM 的 GETSTATIC
        // 不消费对象引用。其余情况保持实例字段语义。
        if (fieldSymbol !in ctx.staticFieldSymbols) {
            irGetField.receiver = target
        }
        return irGetField
    }

    /**
     * 做什么：把 `string.length` 降级成一个后端可识别的合成调用（Unicode 码点数）。
     * 什么时候用：[translatePropertyAccess] 判定接收者静态类型是 string 且属性为 `length` 时。
     * 前置条件：receiver 已翻译且只翻译一次——调用方必须复用它，不能再求值原表达式，
     *   否则 `next().length` 会把接收者的副作用执行两次。
     * 失败模式：运行期 receiver 为 null 时与 JVM 语义一致抛 NPE（语言 string 类型不可空，
     *   只有 any/平台边界才可能出现 null）。
     * 为什么这样设计：IR 没有「码点计数」专用节点，复用 translator 与后端既有的
     *   `callNames` 契约（与 `__jasper_pow__` 同款），把 JVM 指令细节留在后端。
     *   这样「是不是 string」的类型判定只发生在本层，后端无需按字段名猜语义。
     */
    private fun emitStringCodePointLength(receiver: IrExpression, ctx: TranslationContext): IrExpression {
        val symbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = "__jasper_string_length__"
        return IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(receiver), emptyArray(), symbol, null
        )
    }

    /**
     * 做什么：把 `string.byteLength()` 降级成后端可识别的合成调用（UTF-8 字节数）。
     * 什么时候用：[translateCall] 判定接收者静态类型是 string、方法名 `byteLength` 且无实参时。
     * 前置条件：receiver 由调用方翻译一次并复用；实参个数已由 TypeChecker 校验为 0。
     * 失败模式：同 [emitStringCodePointLength]（null 接收者运行期 NPE）。
     * 为什么这样设计：JVM 的 String 没有 byteLength()，且「UTF-8」是语言层决定，
     *   不能依赖平台默认字符集；把「用哪个字符集/怎么取长度」封装到后端一处，
     *   将来换目标平台时只改该实现（隔离的变化点：编码与计数实现）。
     */
    private fun emitStringByteLength(receiver: IrExpression, ctx: TranslationContext): IrExpression {
        val symbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = "__jasper_string_byte_length__"
        return IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(receiver), emptyArray(), symbol, null
        )
    }

    /**
     * 做什么：把 `string.codePointAt(i)` 降级成后端可识别的合成调用，返回 Unicode
     *   scalar value（int32）。
     * 什么时候用：[translateCall] 判定接收者静态类型是 string、方法名 `codePointAt`
     *   且恰好有一个实参时。
     * 前置条件：receiver 由调用方翻译一次并复用；index 也必须由调用方翻译一次后传入。
     *   顺序固定为「接收者 → 索引」，与语言级求值顺序一致。
     * 失败模式：运行期 receiver 为 null 时与 JVM 语义一致抛 NPE（语言 string 类型不可空）；
     *   索引越界/孤立代理项由后端抛结构化异常，本层不吞错、不返回哨兵值。
     * 为什么这样设计：语言层的索引单位是「码点序号」，JVM 的 `String.codePointAt`
     *   用的是「UTF-16 码元偏移」；若让翻译层直接发平台调用，会把同名不同义的语义
     *   泄漏给用户。把转换细节收口到后端（隔离的变化点：字符编码与索引单位），
     *   未来原生 UTF-8 后端只需实现同一个合成调用契约，不改检查器/翻译器。
     */
    private fun emitStringCodePointAt(
        receiver: IrExpression,
        index: IrExpression,
        ctx: TranslationContext
    ): IrExpression {
        val symbol = IrSimpleFunctionSymbolImpl()
        ctx.callNames[symbol] = "__jasper_string_code_point_at__"
        return IrNodeFactory.createCall(
            0, 0, typeMapper.intType(), null,
            arrayOf(receiver, index), emptyArray(), symbol, null
        )
    }

    /**
     * 做什么：解析一次静态字段访问，返回字段符号与声明类型；不是静态字段时返回 null。
     * 什么时候用：属性读取、赋值、自增自减都要在「是否生成接收者」上做同一判断。
     * 前置条件：target 要么是已声明类型名（`Counter.total`），要么是已知类型的简单值
     *   （`a.total`，且 `total` 是静态字段）。
     * 失败模式：类型名未登记、字段不存在、字段不是 static 时返回 null（调用方回退实例路径）。
     * 为什么这样设计：把「类名识别 + 按类字段索引 + 静态性判定」收敛到唯一函数，
     *   避免三处访问路径各写一套判据而再次出现 GETFIELD/PUTSTATIC 不一致。
     */
    private fun resolveStaticFieldRef(
        property: String,
        target: JasExpression,
        ctx: TranslationContext
    ): StaticFieldRef? {
        val classKey = declaredClassName(target, ctx) ?: valueReceiverClassName(target, ctx) ?: return null
        val symbol = ctx.fieldSymbolsByClass[classKey]?.get(property) ?: return null
        if (symbol !in ctx.staticFieldSymbols) return null
        val type = ctx.fieldTypesByClass[classKey]?.get(property) ?: typeMapper.unitType()
        return StaticFieldRef(symbol, type)
    }

    /**
     * 做什么：若 target 是「未被局部值遮蔽的已声明类型简单名」，返回其 JVM 风格类名。
     * 什么时候用：识别 `Counter.total` 中的 `Counter`。
     * 前置条件：ctx.declaredTypeNames 已在翻译前预登记（跨文件可见）。
     * 失败模式：不是标识符、名字被局部变量/参数遮蔽、或不是本程序声明的类型时返回 null。
     * 为什么这样设计：局部变量优先于类型名是语言的名字解析规则；不先查 ctx.symbols 会把
     *   `var Counter = ...; Counter.total` 误判成静态访问。
     */
    private fun declaredClassName(target: JasExpression, ctx: TranslationContext): String? {
        if (target !is JasIdentifier) return null
        if (ctx.symbols.containsKey(target.name)) return null
        if (target.name !in ctx.declaredTypeNames) return null
        return typeMapper.resolveNamedTypeName(target.name)
    }

    /**
     * 做什么：若 target 是已知类型的简单值（局部变量/形参），返回其类名，供静态字段判定。
     * 什么时候用：`a.total` 且 `total` 是静态字段时，提前判定以免求值接收者。
     * 前置条件：ctx.varTypes 已登记该名字的 IR 类型。
     * 失败模式：名字不是简单标识符、类型无法还原成类名时返回 null（回退正常实例路径）。
     * 为什么这样设计：静态字段访问不应求值接收者；提前按变量声明类型定位字段，
     *   可避免为 `a.total` 生成一个随后被后端丢弃的接收者求值。
     */
    private fun valueReceiverClassName(target: JasExpression, ctx: TranslationContext): String? {
        if (target !is JasIdentifier) return null
        val receiverType = ctx.varTypes[target.name] ?: return null
        return typeMapper.irTypeClassName(receiverType)
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
            lookupFieldAlongHierarchy(key, property, ctx.fieldSymbolsByClass, ctx)?.let { return it }
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
            lookupFieldAlongHierarchy(key, property, ctx.fieldTypesByClass, ctx)?.let { return it }
        }
        return ctx.fieldTypes[property]
    }

    /**
     * 做什么：在「类型名 -> 字段名 -> 值」的索引里，从 [startKey] 出发沿继承链 BFS 查找字段。
     * 什么时候用：属性读写经子类类型接收者访问继承字段时（[resolveFieldSymbol]/[resolveFieldType]）。
     * 前置条件：[TranslationContext.superTypeNamesByType] 已由预登记建立。
     * 失败模式：未命中返回 null，不抛异常；环状继承由 visited 去重保护。
     * 为什么这样设计：继承字段与继承方法一样，必须沿父链可见；把「沿链查找」收敛到一个泛型函数，
     *   符号表与类型表两处复用，避免两份几乎相同的遍历逻辑漂移（隔离的变化点：字段解析范围）。
     */
    private fun <T> lookupFieldAlongHierarchy(
        startKey: String,
        property: String,
        index: Map<String, Map<String, T>>,
        ctx: TranslationContext
    ): T? {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(startKey)
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!visited.add(key)) continue
            index[key]?.get(property)?.let { return it }
            ctx.superTypeNamesByType[key]?.forEach { queue.add(it) }
        }
        return null
    }

    private fun isArrayIrType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type).startsWith("[")

    /**
     * 做什么：判断一个 IR 类型是否就是语言内建的 `string`。
     * 什么时候用：属性/方法翻译时识别 string 内建语义（`.length`、`.byteLength()`）。
     * 前置条件：type 已由 typeMapper 映射（任意 IR 类型均可）。
     * 失败模式：无法解析的引用类型会退化成 Object 描述符，因此返回 false（不误判）。
     * 为什么用描述符比较：`string` 在 IR 里是 kotlin.String，与 JVM 的 java.lang.String
     *   一一对应；复用 TypeMapper 的既有映射，避免再维护一份类型名到描述符的对照表。
     */
    private fun isStringIrType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type) == "Ljava/lang/String;"

    /**
     * 做什么：为编译器生成的临时局部变量分配一个函数内唯一的名字。
     * 什么时候用：数组读/写、自增、数组构造等 lowering 需要临时槽时。
     * 前置条件：ctx 属于当前正在翻译的函数；调用次数单调递增。
     * 失败模式：纯字符串生成，不会失败。
     * 为什么需要：后端用「变量名」作为局部槽的键（见 [TranslationContext.tempIdCounter] 注释）；
     *   固定名字会让同函数内不同元素类型的临时变量互相污染，生成 C12 的非法字节码。
     */
    private fun nextTempName(ctx: TranslationContext, prefix: String): Name =
        Name.identifier("$prefix\$${ctx.tempIdCounter++}")

    private fun translateArrayAccess(expr: JasArrayAccess, ctx: TranslationContext): IrExpression {
        val target = translateExpression(expr.target, ctx)
        val index = translateExpression(expr.index, ctx)
        val getSymbol = ctx.functionSymbols["get"] ?: IrSimpleFunctionSymbolImpl()
        ctx.callNames[getSymbol] = "get"
        // 元素读的 IR 结果类型必须是**元素类型**，而不是 Unit：旧实现硬编码 unitType()，
        // 于是 `var x = a[i]` 推断出 void 描述符，任何后续使用都会生成 V 指令
        // （VerifyError / ClassFormatError，FINDINGS #11）。元素类型从数组目标类型反推。
        val elementType = arrayElementType(target.type)
        return IrNodeFactory.createCall(
            0, 0, elementType, null,
            arrayOf(target, index), emptyArray(), getSymbol, null
        )
    }

    /**
     * 做什么：把裸数组字面量 `[a, b, ...]` 降级为「新建数组 + 逐元素 arraySet」的块表达式。
     * 什么时候用：翻译器处理 [JasArrayLiteral] 时（变量初始化、赋值右侧、return 等）。
     * 前置条件：checker 已尽量回填 [JasArrayLiteral.inferredElementType]；缺失时按 `any` 兜底。
     * 失败模式：不抛异常；元素类型与数组描述符不一致时由 [buildArrayFromValues] 插 CAST，
     *   避免把元素按错误类型 astore（C12 的非法字节码来源之一）。
     * 为什么复用 [buildArrayFromValues]：它与 `new T[]{...}`、varargs 打包共享同一套
     *   「创建 + 填充」逻辑，元素宽化/转型策略只维护一份（隔离的变化点：数组构造方式）。
     */
    private fun translateArrayLiteral(expr: JasArrayLiteral, ctx: TranslationContext): IrExpression {
        val elementType = expr.inferredElementType ?: JasAnyType
        val resultType = typeMapper.mapType(JasArrayType(elementType))
        val arrayDesc = typeMapper.jvmDescriptorFromIrType(resultType)
        val elemDesc = if (arrayDesc.startsWith("[")) arrayDesc.substring(1)
            else typeMapper.jvmDescriptorFromIrType(typeMapper.mapType(elementType))
        val values = expr.values.map { translateExpression(it, ctx) }
        return buildArrayFromValues(resultType, values, ctx, elemDesc)
    }

    private fun translateNew(expr: JasNew, ctx: TranslationContext): IrExpression {
        val ctorSymbol = IrConstructorSymbolImpl()
        // 接口不能直接 NEW：`new List`/`new MutableList` 必须落到具体实现 ArrayList，
        // `new Map`/`new dict` 落到 HashMap。实例化策略由 BuiltinCollections 统一给出，
        // 翻译器只负责把「声明的源码类型」替换成「实际要 NEW 的 JVM 类型」并保留泛型
        // 实参，保证 `new List<string>` 的 IR 类型仍是 List<string> 的等价描述符来源。
        val declaredType = expr.type
        val allocationName = (declaredType as? JasNamedType)
            ?.let { BuiltinCollections.allocationTypeName(it.name) }
        val type = if (allocationName != null) {
            typeMapper.mapType(JasNamedType(allocationName, (declaredType as JasNamedType).typeArguments))
        } else {
            typeMapper.mapType(declaredType)
        }
        val args = expr.args.map { translateExpression(it, ctx) }.toMutableList()
        // 构造器实参按**声明形参类型**宽化：`new Holder(new MemRepo())` 必须生成
        // `<init>(LRepo;)V` 并在实参处补 CHECKCAST。IrConstructorCall 的符号不与声明共享
        // 身份，后端只能按实参类型拼描述符；因此把「声明目标类型」这一变化点放在翻译层，
        // 后端继续只读 IR 类型。查不到签名（外部/合成构造器）时保持原样，维持旧行为。
        val owner = typeMapper.irTypeClassName(type)
        val declaredParams = owner?.let {
            ctx.constructorParamTypesByType[it]?.get(memberArityKey("<init>", expr.args.size))
        }
        if (declaredParams != null && declaredParams.size == args.size) {
            for (i in args.indices) {
                args[i] = castToDeclared(declaredParams[i], args[i])
            }
        }
        ctx.constructorTypes[ctorSymbol] = "object"
        return IrNodeFactory.createConstructorCall(
            0, 0, type, null,
            args.toTypedArray(), emptyArray(), ctorSymbol,
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
            return buildArrayFromValues(resultType, expr.init.values.map { translateExpression(it, ctx) }, ctx, elemDesc)
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

    /**
     * 做什么：用已翻译好的元素值构造一维数组（`new T[n]` + 逐个 arraySet），整体是一个块表达式。
     * 什么时候用：数组初始化器 `new T[] { ... }`，以及 varargs 调用点打包尾部实参。
     * 前置条件：resultType 是数组 IR 类型；values 已按求值顺序翻译。
     * 为什么接受 IR 而不是 AST：varargs 打包时实参已被翻译过，重复翻译会重复登记 lambda 等副作用。
     */
    private fun buildArrayFromValues(
        resultType: IrType,
        values: List<IrExpression>,
        ctx: TranslationContext,
        elemDesc: String = typeMapper.jvmDescriptorFromIrType(resultType).removePrefix("["),
    ): IrExpression {
        // 只有元素 IR 类型与数组描述符一致时才做转型，避免把值转成退化的 Unit 类型。
        val elementType = arrayElementType(resultType).takeIf { typeMapper.jvmDescriptorFromIrType(it) == elemDesc }
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
            0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "arr"),
            resultType, arrSymbol, true, false, false
        )
        arrVar.initializer = createArray
        (block.statements as MutableList<IrStatement>).add(arrVar)
        for ((i, v) in values.withIndex()) {
            // 引用元素数组（如 any[] = Object[]）装入原始值时先转型（后端据此装箱）。
            val valExpr = if (elementType != null && (elemDesc.startsWith("L") || elemDesc.startsWith("["))) castToDeclared(elementType, v) else v
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
    }

    /** 实参描述符与声明类型不同时包一层 CAST（后端据此装箱 / checkcast）；相同则原样返回。 */
    private fun castToDeclared(declared: IrType, value: IrExpression): IrExpression =
        if (typeMapper.jvmDescriptorFromIrType(declared) == typeMapper.jvmDescriptorFromIrType(value.type)) value
        else IrTypeOperatorCallImpl(0, 0, declared, IrTypeOperator.CAST, declared, value)

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
        (body.statements as MutableList<IrStatement>).addAll(translateStatementSequence(expr.body.statements, ctx))
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
            // 静态字段：不生成接收者，读写分别是 GETSTATIC/PUTSTATIC。
            // 若先按实例路径翻译 `Counter`，等于把一个类名当变量求值，运行期 NPE。
            val staticRef = resolveStaticFieldRef(pa.property, pa.target, ctx)
            if (staticRef != null) {
                if (expr.op != "=") {
                    val getField = IrNodeFactory.createGetField(
                        0, 0, staticRef.type, staticRef.symbol, null, IrStatementOrigin.GET_PROPERTY
                    )
                    val stubSymbol = IrSimpleFunctionSymbolImpl()
                    ctx.callNames[stubSymbol] = expr.op.removeSuffix("=")
                    val binOp = IrNodeFactory.createCall(
                        0, 0, staticRef.type, IrStatementOrigin.PLUS,
                        arrayOf(getField, value), emptyArray(), stubSymbol, null
                    )
                    val setField = IrNodeFactory.createSetField(
                        0, 0, staticRef.type, staticRef.symbol, null, IrStatementOrigin.EQ
                    )
                    setField.value = binOp
                    return setField
                }
                val setField = IrNodeFactory.createSetField(
                    0, 0, staticRef.type, staticRef.symbol, null, IrStatementOrigin.EQ
                )
                setField.value = value
                return setField
            }
            val targetExpr = translateExpression(pa.target, ctx)
            val fieldSymbol = resolveFieldSymbol(pa.property, pa.target, targetExpr, ctx)
            // 实例接收者也可能解析到静态字段（`a.total`）；后端按符号发 GETSTATIC/PUTSTATIC，
            // 因此这里同样不能挂接收者，否则会多压一个不会被消费的对象引用。
            val isStatic = fieldSymbol in ctx.staticFieldSymbols
            if (expr.op != "=") {
                val op = expr.op.removeSuffix("=")
                val getField = IrNodeFactory.createGetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
                if (!isStatic) getField.receiver = targetExpr
                val stubSymbol = IrSimpleFunctionSymbolImpl()
                ctx.callNames[stubSymbol] = op
                val binOp = IrNodeFactory.createCall(
                    0, 0, value.type, IrStatementOrigin.PLUS,
                    arrayOf(getField, value), emptyArray(), stubSymbol, null
                )
                val targetExpr2 = if (isStatic) null else translateExpression(pa.target, ctx)
                val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
                if (targetExpr2 != null) setField.receiver = targetExpr2
                setField.value = binOp
                return setField
            }
            val setField = IrNodeFactory.createSetField(0, 0, value.type, fieldSymbol, null, IrStatementOrigin.EQ)
            if (!isStatic) setField.receiver = targetExpr
            setField.value = value
            return setField
        } else if (expr.target is JasArrayAccess) {
            val aa = expr.target as JasArrayAccess
            val arrayExpr = translateExpression(aa.target, ctx)
            val indexExpr = translateExpression(aa.index, ctx)
            val resultType = value.type
            val block = IrNodeFactory.createBlock(0, 0, resultType, null)
            val arrSymbol = IrVariableSymbolImpl()
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "aa"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            (block.statements as MutableList<IrStatement>).add(arrVar)
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "ai"), indexExpr.type, idxSymbol, true, false, false)
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
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "av"), resultType, valSymbol, true, false, false)
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
                val valVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "av"), resultType, valSymbol, true, false, false)
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
                        0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "inc"),
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
            // 与赋值路径共用同一判据：静态字段既不求值接收者，也不挂 receiver。
            val staticRef = resolveStaticFieldRef(pa.property, pa.target, ctx)
            val targetExpr = if (staticRef != null) null else translateExpression(pa.target, ctx)
            val fieldSymbol = staticRef?.symbol
                ?: resolveFieldSymbol(pa.property, pa.target, targetExpr!!, ctx)
            val isStatic = staticRef != null || fieldSymbol in ctx.staticFieldSymbols
            val getField = IrNodeFactory.createGetField(0, 0, typeMapper.intType(), fieldSymbol, null, IrStatementOrigin.GET_PROPERTY)
            if (!isStatic && targetExpr != null) getField.receiver = targetExpr
            val stubSymbol = IrSimpleFunctionSymbolImpl()
            ctx.callNames[stubSymbol] = if (op == "++") "+" else "-"
            val binOp = IrNodeFactory.createCall(
                0, 0, typeMapper.intType(), IrStatementOrigin.PLUS,
                arrayOf(getField, one), emptyArray(), stubSymbol, null
            )
            val targetExpr2 = if (isStatic) null else translateExpression(pa.target, ctx)
            val setField = IrNodeFactory.createSetField(0, 0, typeMapper.unitType(), fieldSymbol, null, IrStatementOrigin.EQ)
            if (targetExpr2 != null) setField.receiver = targetExpr2
            setField.value = binOp
            if (needsResult) {
                val block = IrNodeFactory.createBlock(0, 0, typeMapper.intType(), null)
                val resultSymbol = IrVariableSymbolImpl()
                val resultVar = IrNodeFactory.createVariable(
                    0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "inc"),
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
            val arrVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "aa"), arrayExpr.type, arrSymbol, true, false, false)
            arrVar.initializer = arrayExpr
            val idxSymbol = IrVariableSymbolImpl()
            val idxVar = IrNodeFactory.createVariable(0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "ai"), indexExpr.type, idxSymbol, true, false, false)
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
                    0, 0, IrDeclarationOrigin.DEFINED, nextTempName(ctx, "inc"),
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
