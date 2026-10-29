// ============================================================================
// JvmIrBackend —— 把 Kotlin IR 翻译成 JVM 字节码并写出 .class 文件
// 所属层次：compiler（编排层）内的 backend 组件；物理位置在 jasper.compiler 包，
//           职责只做「IR → 字节码」，不参与语法/语义分析，也不编排流水线。
// 与谁协作：上游 = jasper.translator 产出的 TranslationResult（IR 文件列表、
//           调用名映射、构造器类型映射、超类/接口表、接口名集合、静态字段符号集）；
//           下游 = ASM 库（org.jetbrains.org.objectweb.asm 的 ClassWriter/MethodVisitor）
//           与文件系统（java.io.File / java.nio.file.Path 落盘）。
//           类型 → JVM 描述符/内部名/泛型签名统一委托给 jasper.translator.TypeMapper。
// 对外契约：JvmIrBackend(typeMapper).generate(translationResult, outputDir)：
//           按包名建立目录，为每个顶层函数/类写出 <Name>.class；无返回值，失败以
//           异常向上抛（由 JasperCompiler 捕获为 CompileError.Internal）。
//           另有只读旁路 getGraalvmWarnings()，回报扫描到的 GraalVM 风险点。
// 扩展点 / 已知限制：
//   - 所有类统一用 V1_8（class 文件版本 52）生成，取 Java 8 作为下限以兼容
//     Android/D8；不使用 invokedynamic（没有 lambda metafactory，也没有字符串
//     concat factory），因此 GraalVM native-image 的配置负担较小。
//   - 使用 ClassWriter.COMPUTE_FRAMES：栈帧与 max_locals/max_stack 交给 ASM 计算，
//     代价是 ASM 必须能解析公共父类；因此不手工维护栈映射帧。
//   - 局部变量复用非常保守：每次声明或临时值都顺延 nextSlot、不回收作用域，用
//     局部变量表体积换「绝不会踩到仍在使用的槽位」这一字节码验证器安全性。
//   - 常量池、泛型 Signature、字段/方法描述符尽量交给 ASM 与 TypeMapper；本文件
//     只决定「是否需要写签名」以及拼出描述符字符串。
// ============================================================================
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jasper.compiler

import jasper.translator.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.org.objectweb.asm.*
import java.io.File
import java.nio.file.Path

/**
 * 做什么：JVM 字节码后端（Strategy 模式里「JVM 目标」的实现）。把 translator 产出的
 *   Kotlin IR 逐声明翻译成 ASM 可写出的字节码，并按包名落盘为 .class 文件。
 *
 * 什么时候用：`JasperCompiler.compileToDirWithIr` / `compileAllToDir` 在翻译阶段之后
 *   调用一次。什么时候不该用：不要拿孤立/手工拼的 IR 文件调用——generate() 依赖先
 *   建立好的全局符号表（符号→名字/owner、超类、接口），缺了会退化成默认值。
 *
 * 前置条件：typeMapper 必须已按当前编译单元配置好类型名映射；outputDir 必须可写，
 *   包目录不存在时会 mkdirs()。本类持有可变全局状态，**不可并发/重入**调用。
 *
 * 失败模式：不做诊断恢复——出现不支持的 IR 形态或拼不出描述符时会抛异常（内部含
 *   `!!`、强制类型转换、下标越界等），由 JasperCompiler 包成 CompileError.Internal；
 *   本类自己不抛 CompileException，也不返回错误码。
 *
 * 为什么这样设计：只依赖 TypeMapper 这一「类型→描述符」转换器，把 JVM 细节收口在
 *   本文件；换后端（archive/JvmBackend、jasper-llvm）只需替换本类，不动翻译层。
 *   IR 节点用 when 穷举分派而非 Visitor，是因为节点集合由 translator 固定产出，希望
 *   在一处读全「哪种 IR 形态对应哪些指令」的映射。
 */
class JvmIrBackend(private val typeMapper: TypeMapper) {
    // GraalVM native-image 兼容性告警的累积区：generate() 开头扫描 IR 时填充，
    // 外部通过 getGraalvmWarnings() 只读取走；不参与 class 生成。
    private val graalvmWarnings = mutableListOf<String>()

    /**
     * 做什么：返回上一次 generate() 扫描到的 GraalVM 告警（例如 IR 中出现了
     *   invokedynamic，native-image 可能需要在配置里反射/动态代理注册）。
     *
     * 什么时候用：编译结束后由上层读取，用于提示用户补 native-image 配置。
     *   前置条件：需在 generate() 之后调用，generate() 每次会先 clear 再重扫。
     * 失败模式：不抛异常；从未 generate 过时返回空列表。返回值是副本，调用方修改
     *   不会影响内部状态。
     */
    fun getGraalvmWarnings(): List<String> = graalvmWarnings.toList()

    // 只读预扫描：遍历所有 IR 声明，把风险点累积进 graalvmWarnings。
    // 放在 generate() 最前面，保证告警反映本次输入；不改变任何生成结果。
    private fun checkGraalvmHostilePatterns(translationResult: TranslationResult) {
        for (file in translationResult.irFiles) {
            for (decl in file.declarations) {
                checkIrDeclarationForGraalvm(decl)
            }
        }
    }

    // 声明级递归：函数只看 body 的语句；类递归进成员。其它声明（字段等）忽略——
    // 字段初始化器不可能含 invokedynamic，属性体由 getter/setter 覆盖。
    private fun checkIrDeclarationForGraalvm(decl: IrDeclaration) {
        when (decl) {
            is IrSimpleFunction -> {
                val body = decl.body
                if (body is IrBlockBody) {
                    for (stmt in body.statements) {
                        checkIrStatementForGraalvm(stmt)
                    }
                }
            }
            is IrClass -> {
                for (classDecl in decl.declarations) {
                    checkIrDeclarationForGraalvm(classDecl)
                }
            }
            else -> {}
        }
    }

    // 语句级分派：块展开成语句序列，表达式交给表达式扫描，其它（如变量声明）跳过——
    // 变量的初始化器即便含 invoke 也会经由表达式的 IrCall 分支被看到。
    private fun checkIrStatementForGraalvm(stmt: IrStatement) {
        when (stmt) {
            is IrBlock -> stmt.statements.forEach { checkIrStatementForGraalvm(it) }
            is IrExpression -> checkIrExpressionForGraalvm(stmt)
            else -> {}
        }
    }

    // 表达式级扫描入口：重点看 IrCall 的「调用名」——translator 把语义写进 callNames，
    // 只有名字为 "invokedynamic" 才是 native-image 风险点。其它表达式类型做结构下降，
    // 覆盖 when 的分支与 lambda 体，避免漏掉嵌在里面的调用。
    private fun checkIrExpressionForGraalvm(expr: IrExpression) {
        when (expr) {
            is IrCall -> {
                // 先用 translator 的语义名（callNames），退化到函数符号名。
                val name = callNames[expr.symbol] ?: functionSymbolToName[expr.symbol]
                if (name == "invokedynamic") {
                    graalvmWarnings.add("invokedynamic detected in IR call — GraalVM native-image may require additional configuration")
                }
            }
            is IrBlock -> expr.statements.forEach { checkIrStatementForGraalvm(it) }
            is IrWhen -> {
                for (branch in expr.branches) {
                    checkIrExpressionForGraalvm(branch.condition)
                    when (val r = branch.result) {
                        is IrBlock -> r.statements.forEach { checkIrStatementForGraalvm(it) }
                        else -> checkIrExpressionForGraalvm(r)
                    }
                }
            }
            is IrFunctionExpression -> {
                val func = expr.function
                val body = func.body
                if (body is IrBlockBody) {
                    for (stmt in body.statements) {
                        checkIrStatementForGraalvm(stmt)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * 做什么：一个局部变量槽位的登记项：源码名、IR 类型、以及它占用的 JVM 槽号。
     *
     * 什么时候用：生成函数/构造器时把参数和声明出的变量登记进 MethodContext.locals，
     *   供 load/store 时反查「这个名字的描述符是什么」。
     * 前置条件：slot 必须遵守 JVM 槽位规则——long/double 占 2 个槽，其余占 1 个。
     * 失败模式：无（纯数据）；irType 允许为 null（例如 "this"，不需要再取描述符）。
     */
    data class LocalInfo(val name: String, val irType: IrType?, val slot: Int)
    /**
     * 做什么：单个方法/构造器体的生成上下文，聚合「写字节码的 MethodVisitor」与
     *   局部变量状态（槽位分配、名字→槽、符号→名字、循环标签栈）。
     *
     * 什么时候用：每个函数/构造器/<clinit> 各建一个，交给 generateIrStatement /
     *   generateIrExpression 递归传递。什么时候不该用：不可跨方法复用——它描述的是
     *   一个 Code 属性的局部状态。
     * 前置条件：locals/variables/symbolToName 至少含参数；nextSlot 必须已是「未使用
     *   的最小槽号」（实例方法从 1 起、静态方法从 0 起）。
     * 失败模式：无；查不到名字时生成侧会退化（如 ALOAD 0 / 丢值），因此登记必须完整。
     * 为什么这样设计：把可变状态集中到一个对象，避免在递归签名里穿 5 个参数。
     */
    data class MethodContext(
        val mv: MethodVisitor,
        // 已分配槽位的扁平列表，用于按名字反查 IR 类型（描述符可能被拓宽/装箱）。
        val locals: MutableList<LocalInfo>,
        // 变量名 → 槽号；IR 里 IrGetValue/IrSetValue 通过 symbolToName 归一化到名字。
        val variables: MutableMap<String, Int>,
        // 值符号 → 生成用名字，弥合「IR 用 Symbol 标识、字面量用名字标识」的差异。
        val symbolToName: MutableMap<IrValueSymbol, String>,
        // 下一个可用槽号；每次新声明/临时值都顺延，不回收，换取验证器安全。
        var nextSlot: Int,
        // 当前方法的返回类型，用于决定 return 指令与返回前的拓宽/装箱。
        val returnType: IrType?,
        // 是否静态方法：决定参数槽起点，以及「无接收者的 IrGetValue」是否回退到 this。
        val isStatic: Boolean,
        // 循环标签栈，元素为 (continue 目标, break 目标)；IrBreak/IrContinue 取栈顶。
        val loopStack: MutableList<Pair<Label, Label>> = mutableListOf(),
        // 精确到 IR 循环节点的标签：带标签 break/continue 必须跳到指定循环，而不是栈顶。
        val loopTargets: MutableMap<IrLoop, Pair<Label, Label>> = java.util.IdentityHashMap(),
        // finally 上下文栈：进入 try/finally 的函数体时压栈、离开时弹栈。
        // 生成 IrReturn 时据此在返回前按「内层到外层」顺序执行所有尚未执行的 finally，
        // 这样「提前 return 也执行 finally」才能成立，且每个 finally 只执行一次（FINDINGS #4）。
        val finallyStack: MutableList<FinallyContext> = mutableListOf(),
        // 每个循环进入时 finallyStack 的深度：break/continue 只执行比它更内层的 finally。
        val loopFinallyDepth: MutableMap<Pair<Label, Label>, Int> = java.util.IdentityHashMap(),
        // 当前仍在生成中的受保护区间（try 体 / catch 体），由外到内。
        val openRanges: MutableList<ProtectedRange> = mutableListOf(),
        // 标签生成序号：受保护区间的空隙按它判定先后与嵌套。
        var labelSeq: Int = 0
    )

    /**
     * 一个 try 体或 catch 体的受保护区间，以及其中需要排除的「空隙」。
     * 为什么需要：return / break / continue 在受保护区间内就地内联 finally 体；这段内联代码若仍在
     *   本 try（及其内层 try）的异常表范围内，finally 自身抛出时会再次进入同一 finally 处理器
     *   （finally 执行两次），或被内层 catch 截获。与 javac 一致，登记异常表时把这些空隙挖掉。
     */
    class ProtectedRange {
        // 空隙按生成顺序排列；嵌套在后加空隙内部的旧空隙在加入时被并掉。
        val gaps: MutableList<Gap> = mutableListOf()
    }

    /** 受保护区间中的一段内联 finally 代码；seq 为起点标签的生成序号。 */
    class Gap(val seq: Int, val start: Label, val end: Label)

    /**
     * 一个 try/finally 的 finally 体（IR 表达式）在生成期的上下文。
     * 什么时候用：生成 try 体 / catch 体时压入 [MethodContext.finallyStack]；
     *   生成该 try 的正常收尾与异常处理器时弹出，保证 finally 自身里的 return 不会递归执行自己。
     * 为什么需要：JVM 没有结构化 finally 指令，异常/正常/return 三条路径都要各自补上
     *   「执行 finally」的代码；用一个显式上下文栈把「哪些 finally 还没跑」记录下来，
     *   才能让 return 走对路径且只跑一次。
     */
    data class FinallyContext(val block: IrExpression, val rangeIndex: Int)
    // 当前正在生成的包名（以 '/' 分隔的内部名形式），决定 class 内部名与字段 owner 兜底。
    private var currentPkg: String = ""
    // —— 以下 5 张表在 generate() 第一遍里按符号填入，第二遍生成时只读 ——
    // 函数符号 → 方法名（跨文件引用时用来写 INVOKESTATIC 的名字）。
    private val functionSymbolToName = mutableMapOf<IrSimpleFunctionSymbol, String>()
    // 函数符号 → owner 内部名（顶层函数是 "<pkg>/<fun>"，成员函数是其类的内部名）。
    private val functionSymbolToOwner = mutableMapOf<IrSimpleFunctionSymbol, String>()
    // 字段符号 → 字段名（GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC 的名字部分）。
    private val fieldSymbolToName = mutableMapOf<IrFieldSymbol, String>()
    // 字段符号 → owner 内部名（声明该字段的类）。
    private val fieldOwnerMap = mutableMapOf<IrFieldSymbol, String>()
    // 字段符号 → 声明处的 JVM 字段描述符。
    // 为什么必须单独记：JVM 要求字段指令里的描述符与 `visitField` 写出的声明描述符
    // 逐字符一致。若像旧实现那样取「被写值」的类型，`this.slot = null` 会拿到
    // `null` 字面量的 `Ljava/lang/Object;`，与声明的 `Lp/C;` 不一致，运行期直接
    // `NoSuchFieldError: ... member field 'java.lang.Object slot'`（C14 的写路径）。
    // 该表与 fieldSymbolToName/fieldOwnerMap 一起在第一遍扫描里填充，第二遍只读。
    private val fieldSymbolToDescriptor = mutableMapOf<IrFieldSymbol, String>()
    // 枚举项符号 → 常量字段名 / owner 内部名。枚举常量不是 IrField，不能复用上面的
    // 字段表；IrGetEnumValue 只携带枚举项符号，必须在这里查回 JVM 字段坐标。
    private val enumEntrySymbolToName = mutableMapOf<IrEnumEntrySymbol, String>()
    private val enumEntrySymbolToOwner = mutableMapOf<IrEnumEntrySymbol, String>()
    // 类名（简单名）→ 超类内部名；来自 translator，未登记时默认 java/lang/Object。
    private var classSuperclassNames: Map<String, String> = emptyMap()
    // 类名（简单名）→ 直接实现的接口内部名列表；注解类强制实现 Annotation。
    private var classInterfaceNames: Map<String, List<String>> = emptyMap()
    // 被声明为 interface 的类型名集合（含简单名与内部名两种写法），用于选 INVOKEINTERFACE。
    private var interfaceDeclNames: Set<String> = emptySet()
    // 需按 JVM 静态字段发射/访问的字段符号（接口常量 public static final）。
    private var staticFieldSymbols: Set<IrFieldSymbol> = emptySet()
    // 声明为 static 的成员函数符号：决定成员方法 access 是否加 ACC_STATIC，以及参数槽起点。
    private var staticFunctionSymbols: Set<IrSimpleFunctionSymbol> = emptySet()
    // 合成静态调用的宿主：符号 -> JVM 内部类名（枚举 values/valueOf）。方法名取自 callNames，
    // 描述符由实参类型 + 调用 IR 类型推导；它们没有 IR 声明，故 owner 不能靠 functionSymbolToOwner。
    private var syntheticStaticCallOwners: Map<IrSimpleFunctionSymbol, String> = emptyMap()
    // 全限定平台静态调用：符号 -> 平台类内部名。方法名取自 callNames；描述符必须由
    // PlatformMethodResolver.resolveStatic 按真实 public static 候选选出。
    private var platformStaticCallOwners: Map<IrSimpleFunctionSymbol, String> = emptyMap()
    // 函数符号 → translator 赋予的语义调用名（"+"、"v:foo"、"ctor_delegate:" 等）；
    // 这是后端与 translator 之间的核心协议，决定走哪条专用生成路径。
    private var callNames: Map<IrSimpleFunctionSymbol, String> = emptyMap()
    // 构造器符号 → 构造器类型标记（如 "array:<elemDesc>" 表示数组新建而非对象新建）。
    private var constructorTypes: Map<IrConstructorSymbol, String> = emptyMap()
    // 用户类型的成员方法「声明擦除签名」：JVM 风格类型名 → 方法名 → 擦除签名。
    // 经接口/泛型接收者的调用点据此写出被调方法的真实（擦除后）描述符。
    private var memberSignaturesByType: Map<String, Map<String, MemberMethodSignature>> = emptyMap()
    // 调用点符号 -> 成员索引键：translator 已按检查器选中的重载算好，backend 直接复用。
    private var memberCallKeys: Map<IrSimpleFunctionSymbol, String> = emptyMap()
    // 接口 JVM 内部名 → 其声明的方法（不含 <init>/合成访问器）。生成桥接方法时用来
    // 找到「本类实现的接口要求哪些擦除后签名」。
    private var interfaceMethods: Map<String, List<IrSimpleFunction>> = emptyMap()
    // 平台（JDK）实例方法签名解析器：只在接收者不是本次编译单元声明的类型时使用。
    // 为什么放在后端实例上：解析结果缓存可以跨 generate() 复用（平台类的签名不随
    // 编译单元变化）；JvmIrBackend 明确不可并发/重入，解析器因此也无需加锁。
    private val platformMethodResolver = PlatformMethodResolver()

    // 由字段符号反查 owner 内部名；查不到时退化为当前包，避免写出空 owner 导致
    // ClassWriter 抛异常（宁可生成一个可能 NoSuchFieldError 的引用，也不崩在生成期）。
    private fun resolveFieldOwnerFromSymbol(symbol: IrFieldSymbol): String {
        return fieldOwnerMap[symbol] ?: currentPkg.replace('.', '/')
    }

    // 语义化重载：保留独立入口，便于「按符号解析」与「按接收者解析」两条路径分开演进。
    private fun resolveFieldOwner(symbol: IrFieldSymbol): String {
        return resolveFieldOwnerFromSymbol(symbol)
    }

    // 按接收者表达式推断字段 owner 的兜底策略：当字段符号未登记时，若接收者是
    // IrGetValue 且其静态类型可解析为一个具体类，就用它当 owner；否则退回当前包。
    // 注意：本文件内目前无人调用（字段访问都用符号重载），属于待接线/待清理的
    // 兜底实现；保留仅为不改变行为。
    private fun resolveFieldOwner(receiver: IrExpression): String {
        // If receiver is a GetValue for a local variable, try to determine its type
        if (receiver is IrGetValue) {
            val recvType = receiver.type
            val typeName = typeMapper.jvmInternalNameFromIrType(recvType)
            if (typeName.isNotEmpty() && typeName != "java/lang/Object") {
                return typeName
            }
        }
        return currentPkg
    }

    // 数组类型判定：委托 TypeMapper 求描述符，凡以 '[' 开头的即为数组。
    // 用在 IrGetField 上——数组的 .length 要生成 ARRAYLENGTH 而不是 GETFIELD。
    private fun isArrayType(type: IrType): Boolean =
        typeMapper.jvmDescriptorFromIrType(type).startsWith("[")

    /**
     * 做什么：解析字段符号对应的 JVM 字段名；查不到时**立即抛内部错误**（fail fast）。
     * 什么时候用：IrGetField / IrSetField 在真正发射 GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC 之前。
     * 前置条件：调用点已排除数组 `.length` 特例（它不查字段符号，走 ARRAYLENGTH）。
     * 失败模式：符号未登记时抛 IllegalStateException，终止本次编译。
     * 为什么这样设计：字段符号查不到意味着 IR 引用了不存在/未登记的字段。旧实现退化成
     *   名字 `field`、描述符 `V`，产出的是**非法字节码**（ClassFormatError），把编译期
     *   错误推迟成晦涩的运行期崩溃（Q19 C23）。此处改为断言，宁可编译期大声失败，也不写出
     *   非法 class——「禁止静默兜底」是后端对前端的硬契约。
     */
    private fun requireFieldName(symbol: IrFieldSymbol): String {
        return fieldSymbolToName[symbol]
            ?: throw IllegalStateException(
                "INTERNAL: unresolved field symbol (no name registered); refusing to emit illegal bytecode"
            )
    }

    /**
     * 做什么：把字段访问的描述符做合法性断言（不得为 `V`/Unit）。
     * 什么时候用：IrGetField / IrSetField 发射字段指令前。
     * 前置条件：desc 由 typeMapper 从字段/值的 IR 类型得到。
     * 失败模式：desc == "V" 时抛 IllegalStateException。
     * 为什么这样设计：字段不能是 void；`V` 描述符只可能来自「类型解析失败退化成 Unit」，
     *   正是静默兜底的特征信号。断言可把这类问题在编译期暴露（Q19 C23）。
     */
    private fun requireFieldDescriptor(desc: String): String {
        check(desc != "V") {
            "INTERNAL: field access with unit descriptor 'V'; refusing to emit illegal bytecode"
        }
        return desc
    }

    /**
     * 做什么：取字段**声明处**的 JVM 描述符，供 GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC 使用。
     *
     * 什么时候用：所有字段指令发射前。字段读/写都必须用同一个声明描述符，
     *   否则 class 文件里「声明的字段」与「指令引用的字段」对不上，运行期
     *   `NoSuchFieldError`。
     * 前置条件：symbol 来自第一遍扫描过的 IrField 声明。
     * 失败模式：符号未登记时退回 [fallbackType] 的描述符（保持旧行为，避免对合成/
     *   外部符号过度收紧），但 `V` 描述符仍 fail fast。
     * 为什么这样设计：`IrSetField.type` 是 translator 用「被写值的类型」填的，`null`
     *   字面量会给出 `java/lang/Object`；而字段声明可能是 `Lp/C;`。把「声明描述符」
     *   收敛到这张索引表，读写两条路径就不会再各自猜测类型（隔离了变化点）。
     */
    private fun resolveFieldDescriptor(symbol: IrFieldSymbol, fallbackType: IrType): String {
        val desc = fieldSymbolToDescriptor[symbol]
            ?: typeMapper.jvmDescriptorFromIrType(fallbackType)
        return requireFieldDescriptor(desc)
    }

    /**
     * 做什么：把栈顶值从 [fromDesc] 转换成字段声明类型 [toDesc]，供 PUTFIELD/PUTSTATIC 使用。
     *
     * 什么时候用：字段写指令发射前、值已经在栈顶时。
     * 前置条件：栈顶恰好是 [fromDesc] 类型的一个值；接收者（实例写）在它下面。
     * 失败模式：不支持或类型检查本应拒绝的组合可能留下验证器错误；本函数不兜底，
     *   也不抛业务异常。
     * 为什么需要：字段描述符改用声明类型后，值本身仍可能来自更宽/擦除的类型，
     *   例如 `null`（IR 类型 `Any?` → Object）、`any` 赋给具体引用、`int` 赋给
     *   `int64`/`any` 字段。这里复用既有 widen/box/unbox 原语，把「值转换」与
     *   「描述符选择」两个变化点分开，避免在各调用点复制转换逻辑。
     */
    private fun emitStoreConversion(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (fromDesc == toDesc) return
        val fromPrimitive = isPrimitiveDesc(fromDesc)
        val toPrimitive = isPrimitiveDesc(toDesc)
        when {
            // 基本类型之间：只做数值拓宽（收窄不在类型检查允许范围内）。
            fromPrimitive && toPrimitive -> widenIfNeeded(mv, fromDesc, toDesc)
            // 基本类型 → 引用：装箱（目前只支持 Object/Serializable 目标）。
            fromPrimitive -> emitBoxingIfNeeded(mv, fromDesc, toDesc)
            // 引用 → 基本类型：先 checkcast 到包装类再 unbox。
            toPrimitive -> emitUnboxingFromRef(mv, toDesc)
            // 引用 → 引用：目标是 Object/Serializable 时无需指令（任何引用都可赋值）；
            // 更具体的引用目标（含数组）补 CHECKCAST，满足验证器对精确类型的要求。
            // `null` 常量走这条分支时 CHECKCAST 也是合法的（结果仍是 null）。
            toDesc == "Ljava/lang/Object;" || toDesc == "Ljava/io/Serializable;" -> Unit
            else -> emitReferenceWidening(mv, fromDesc, toDesc)
        }
    }

    /**
     * 做什么：只处理「引用 → 引用」的声明类型宽化：目标更具体（接口/父类/数组）时发 CHECKCAST。
     *
     * 什么时候用：变量初始化、赋值、返回值、实参等「已知声明目标类型」的落点；
     *   这些位置此前只做 widen/box，引用类型的具体实现会原样入栈，虽然 JVM 验证器
     *   因赋值兼容性放行，但字节码里缺少「按声明类型宽化」的显式证据（C13）。
     * 前置条件：源/目标描述符均已由 [TypeMapper] 得到；栈顶恰好一个 [fromDesc] 值。
     * 失败模式：描述符相同、或任一为基本类型时直接返回——基本类型转换仍由调用方
     *   原有的 widen/box/unbox 原语负责，避免把「值类型与 IR 描述符不一致」的旧路径
     *   误判成需要拆箱（例如幂运算 `**` 的 IR 类型与栈上 int 的既有近似）。
     * 为什么单独成函数：它与 [emitStoreConversion] 的差别只在「不处理基本类型互转」，
     *   把该差异隔离出来，可以让新调用点获得 CHECKCAST 而不改变既有数值/拆箱语义。
     */
    private fun emitReferenceWidening(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (fromDesc == toDesc) return
        if (isPrimitiveDesc(fromDesc) || isPrimitiveDesc(toDesc)) return
        // 目标为 Object/Serializable 时任何引用都可赋值，不需要 CHECKCAST。
        if (toDesc == "Ljava/lang/Object;" || toDesc == "Ljava/io/Serializable;") return
        val targetInternal = if (toDesc.startsWith("[")) toDesc
        else toDesc.removePrefix("L").removeSuffix(";")
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetInternal)
    }


    /**
     * 做什么：后端唯一入口。先扫描 GraalVM 风险点，再用一遍遍历建立「符号→名字/owner」
     *   全局索引，最后按包目录逐声明生成并写出 class 文件。
     *
     * 什么时候用：翻译阶段完成后调用一次；同一实例可重复调用（会先 clear 旧索引并
     *   重新装载 callNames 等映射）。什么时候不该用：不要并发调用——全局状态保存在
     *   可变字段里，本类不可重入。
     *
     * 前置条件：translationResult.irFiles 的每个 packageFqName 已就绪；outputDir 可写。
     * 失败模式：不支持的 IR 形态会抛异常；写盘失败向上抛 IO 异常且不回滚，已写出的
     *   class 会残留（编译失败时上层会丢弃整个输出目录）。
     *
     * 为什么这样设计：两遍遍历是必需的——字节码里方法调用/字段访问必须写死 owner 的
     *   内部名，而 owner 往往定义在别的 IR 文件里；先全量收集索引才能让跨文件与前向
     *   引用都解析成功。
     */
    fun generate(translationResult: TranslationResult, outputDir: Path) {
        // 清空上一次运行留下的索引，保证重复调用/多编译单元时不串味。
        functionSymbolToName.clear()
        functionSymbolToOwner.clear()
        fieldSymbolToName.clear()
        fieldOwnerMap.clear()
        fieldSymbolToDescriptor.clear()
        enumEntrySymbolToName.clear()
        enumEntrySymbolToOwner.clear()
        graalvmWarnings.clear()
        // 装载 translator 侧提供的映射；这些是只读输入，后端不回写。
        callNames = translationResult.callNames
        constructorTypes = translationResult.constructorTypes
        classSuperclassNames = translationResult.classSuperclassNames
        classInterfaceNames = translationResult.classInterfaceNames
        interfaceDeclNames = translationResult.interfaceDeclNames
        staticFieldSymbols = translationResult.staticFieldSymbols
        staticFunctionSymbols = translationResult.staticFunctionSymbols
        syntheticStaticCallOwners = translationResult.syntheticStaticCallOwners
        platformStaticCallOwners = translationResult.platformStaticCallOwners
        memberSignaturesByType = translationResult.memberSignaturesByType
        memberCallKeys = translationResult.memberCallKeys
        checkGraalvmHostilePatterns(translationResult)
        // 第 1 遍：建立符号 → 名字 / owner 索引。owner 用「包名内部名/类名」的 JVM 约定，
        // 顶层函数则以其自身为 owner（等价于文件名即类名，makeClass 时补建 main 桥）。
        val interfaceMethodIndex = mutableMapOf<String, MutableList<IrSimpleFunction>>()
        for (file in translationResult.irFiles) {
            for (decl in file.declarations) {
                when (decl) {
                    is IrSimpleFunction -> {
                        functionSymbolToName[decl.symbol] = decl.name.toString()
                        val ownerName = "${file.packageFqName?.toString()?.replace('.', '/') ?: ""}/${decl.name.toString()}"
                        functionSymbolToOwner[decl.symbol] = ownerName
                    }
                    is IrClass -> {
                        val internalName = "${file.packageFqName?.toString()?.replace('.', '/') ?: ""}/${decl.name.toString()}"
                        // 接口方法索引：供桥接方法生成查「本类实现的接口要求哪些擦除后签名」。
                        if (decl.kind == ClassKind.INTERFACE) {
                            val methods = interfaceMethodIndex.getOrPut(internalName) { mutableListOf() }
                            for (classDecl in decl.declarations) {
                                if (classDecl is IrSimpleFunction && !classDecl.name.toString().startsWith("<")) {
                                    methods.add(classDecl)
                                }
                            }
                        }
                        for (classDecl in decl.declarations) {
                            when (classDecl) {
                                is IrSimpleFunction -> {
                                    functionSymbolToName[classDecl.symbol] = classDecl.name.toString()
                                    // 成员函数的 owner 是其所属类，而不是包。
                                    functionSymbolToOwner[classDecl.symbol] = internalName
                                }
                                is IrField -> {
                                    fieldSymbolToName[classDecl.symbol] = classDecl.name.toString()
                                    fieldOwnerMap[classDecl.symbol] = internalName
                                    // 声明描述符在此固化：字段读/写指令都必须复用它，
                                    // 不能退回到「值/读取表达式的类型」（见字段声明处注释）。
                                    fieldSymbolToDescriptor[classDecl.symbol] =
                                        typeMapper.jvmDescriptorFromIrType(classDecl.type)
                                }
                                // 枚举项同样需要「符号 → 字段坐标」索引：读取点
                                // （IrGetEnumValue）引用的是枚举项符号，不是字段符号。
                                is IrEnumEntry -> {
                                    enumEntrySymbolToName[classDecl.symbol] = classDecl.name.toString()
                                    enumEntrySymbolToOwner[classDecl.symbol] = internalName
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        // 第一遍结束：固化接口方法索引（第二遍生成桥接方法时只读）。
        interfaceMethods = interfaceMethodIndex
        // 第 2 遍：真正生成。每个 IR 文件按包名建目录，逐顶层声明写出 <Name>.class。
        for (file in translationResult.irFiles) {
            currentPkg = file.packageFqName?.toString()?.replace('.', '/') ?: ""
            val dir = outputDir.resolve(currentPkg).toFile()
            dir.mkdirs()
            for (decl in file.declarations) {
                generateTopLevelDeclaration(decl, dir)
            }
        }
    }

    // 顶层声明的分派：函数与类各自生成一个 class 文件，其它声明（字段等）在顶层无意义，忽略。
    private fun generateTopLevelDeclaration(decl: IrDeclaration, dir: File) {
        when (decl) {
            is IrSimpleFunction -> generateTopLevelFunction(decl, dir)
            is IrClass -> generateIrClass(decl, dir)
            else -> {}
        }
    }

    // 顶层函数没有宿主类，按 JVM 约定为它单独生成一个「以函数名命名」的类，
    // 函数本身作为静态方法放进去，于是 `foo()` 变成 `foo.foo()`。
    private fun generateTopLevelFunction(func: IrSimpleFunction, dir: File) {
        val name = func.name.toString()
        val internalName = "$currentPkg/$name"
         val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        // V1_8：class 版本 52，兼容 Android/D8；ACC_SUPER 是所有现代 JVM 类的约定标志。
        // 超类是 Object，签名传 null（顶层函数所在类没泛型参数）。
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", emptyArray())
        generateIrFunction(cw, func, internalName, true)
        // 若函数名是 main 且无参，补一个标准入口桥 `main(String[])`（JVM 启动器只认
        // 这个签名），内部转调无参 main。参数为空是必要条件，避免与用户显式声明的
        // `main(String[])` 冲突而生成重复方法。
        if (name == "main" && func.valueParameters.isEmpty()) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
            mv.visitCode()
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "main", irMethodDescriptor(func), false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        val bytes = cw.toByteArray()
        dir.resolve("$name.class").writeBytes(bytes)
    }

    // 生成一个类/接口/枚举/注解的 ClassFile：决定 access 标志、超类、直接接口与
    // 泛型 Signature，然后分派成员，最后按需补 <clinit> 与枚举基础设施。
    // 注意：类名只用简单名 + currentPkg 拼内部名，因此不支持嵌套类的外部名（Outer$Inner）。
    private fun generateIrClass(cls: IrClass, dir: File) {
        val name = cls.name.toString()
        val internalName = "$currentPkg/$name"
        // COMPUTE_FRAMES：让 ASM 依据控制流计算 StackMapTable/帧与 max_locals、max_stack。
        // 因为分支（when/短路/循环）都是裸跳转拼出来的，手写帧极易出错，交给 ASM 更稳。
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val isInterface = cls.kind == ClassKind.INTERFACE
        val isEnum = cls.kind == ClassKind.ENUM_CLASS
        val isAnnotation = cls.kind == ClassKind.ANNOTATION_CLASS
        // access 标志必须与超类一致：接口必须 ABSTRACT 且不能有 ACC_SUPER；枚举要 ACC_ENUM；
        // 注解类型在 classfile 里是「implements Annotation 的接口」。标志写错验证器/加载器会拒绝。
        val classFlags = when {
            isInterface -> Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            isEnum -> Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM
            isAnnotation -> Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
            else -> Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
        }
        // 超类：枚举一律继承 java/lang/Enum（枚举常量的 name/ordinal 由它提供）；普通类查
        // translator 交来的超类表（值已是内部名）；查不到按 Object。接口的 super 在 classfile
        // 里仍写 java/lang/Object（JVM 规范要求）。
        // 映射表以 JVM 内部名（含包）为键：p1.Impl / p2.Impl 的父类与接口表不能按简单名
        // 共享，否则后写者会覆盖前者，生成错误的 class 文件。
        val superName = when {
            isEnum -> "java/lang/Enum"
            classSuperclassNames.containsKey(internalName) -> classSuperclassNames[internalName]!!
            else -> "java/lang/Object"
        }
        // 直接接口列表：注解类型强制实现 java/lang/annotation/Annotation；其余查映射表。
        val interfaceNames = when {
            isAnnotation -> arrayOf("java/lang/annotation/Annotation")
            classInterfaceNames.containsKey(internalName) -> classInterfaceNames[internalName]!!.toTypedArray()
            else -> emptyArray()
        }
        // 泛型 Signature（类级 <T:...>LSuper;...）：只有类有类型参数时才非 null；
        // 无泛型时写 null，避免 ASM 发出多余但合法的 Signature 属性。
        val classSig = typeMapper.buildClassSignature(cls, superName)
        cw.visit(Opcodes.V1_8, classFlags, internalName, classSig, superName, interfaceNames)

        // 成员分派：方法、构造器、字段、属性；其余（如伴生对象等未支持形态）忽略。
        for (decl in cls.declarations) {
            when (decl) {
                is IrSimpleFunction -> {
                    // 接口方法只能是 public abstract（没有 Code 属性）；普通类方法默认 public，
                    // 由 generateIrFunction 按 isStatic 决定是否补 ACC_STATIC（此处传 -1 表示
                    // 「不接管 access」）。声明为 static 的成员函数参数槽从 0 起（无 this）。
                    val isStaticMember = !isInterface && decl.symbol in staticFunctionSymbols
                    val methodFlags = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT else -1
                    generateIrFunction(cw, decl, internalName, isStaticMember, methodFlags)
                }
                is IrConstructor -> {
                    // Handle enum constructors: they need synthetic (String, int) prefix
                    // 枚举构造器多两个隐式前导参数 (name, ordinal)，必须走专用分支。
                    if (isEnum) {
                        generateEnumConstructor(cw, decl, internalName)
                    } else {
                        generateIrConstructor(cw, decl, internalName)
                    }
                }
                is IrField -> {
                    if (decl.symbol in staticFieldSymbols) {
                        // Static field (interface constant or user `static var` / `static final`):
                        // visibility follows the declaration, mutable fields must NOT be final.
                        // ConstantValue is only emitted for `final` fields; a non-final static
                        // initializer is executed in `<clinit>` instead (see needsRuntimeStaticInit).
                        // 中文：静态字段（接口常量或用户 `static var`/`static final`）：
                        // 访问标志按声明决定；可变静态字段绝不能加 ACC_FINAL（否则跨类 PUTSTATIC
                        // 会抛 IllegalAccessError）。只有 final 字段才用 ConstantValue，非 final
                        // 的初值统一由 <clinit> 执行。
                        val fieldFlags = fieldVisibilityFlags(decl.visibility) or
                            Opcodes.ACC_STATIC or
                            (if (decl.isFinal) Opcodes.ACC_FINAL else 0)
                        cw.visitField(
                            fieldFlags,
                            decl.name.toString(),
                            typeMapper.jvmDescriptorFromIrType(decl.type),
                            null,
                            if (decl.isFinal) staticFieldConstantValue(decl) else null
                        )
                    } else {
                        // 能走到这里的 IrField 是「非 static 字段」：枚举常量现在统一由
                        // IrEnumEntry 分支发射（不再是带大写名字的 IrField），因此枚举体里的
                        // 普通实例字段必须按实例字段发射，不能误加 ACC_STATIC/ACC_ENUM。
                        // 泛型信息（如 List<T>）作为字段 Signature 单独写出，描述符仍走擦除。
                        val fieldFlags = Opcodes.ACC_PUBLIC
                        val fieldDesc = typeMapper.jvmDescriptorFromIrType(decl.type)
                        val fieldSig = typeMapper.buildFieldSignature(decl)
                        cw.visitField(fieldFlags, decl.name.toString(), fieldDesc, fieldSig, null)
                    }
                }
                is IrEnumEntry -> {
                    // 枚举常量字段：JVM 枚举协议要求 public static final 且带 ACC_ENUM，
                    // 描述符就是枚举类自身；初值由 <clinit> 通过 PUTSTATIC 写入（见
                    // generateEnumInfrastructure），因此这里不写 ConstantValue。
                    cw.visitField(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                        decl.name.toString(),
                        "L$internalName;",
                        null,
                        null
                    )
                }
                is IrProperty -> {
                    // Property access is handled directly via IrGetField/IrSetField at the IR level.
                    // Accessor functions (getter/setter) are for custom accessor bodies only.
                    // Skip if body is null to avoid generating illegal JVM names like <getter-foo>.
                    // 中文：属性读写一般在 IR 层直接编译成 IrGetField/IrSetField（后端不生成
                    // getter/setter 调用）；只有自定义了访问器体的属性才需要发射对应方法。
                    // getter/setter 体为 null 时必须跳过——否则会生成 `<getter-foo>` 这类非法 JVM 名。
                    val getter = decl.getter
                    if (getter != null && getter.body != null) {
                        generateIrFunction(cw, getter, internalName, false)
                    }
                    val setter = decl.setter
                    if (setter != null && setter.body != null) {
                        generateIrFunction(cw, setter, internalName, false)
                    }
                }
                else -> {}
            }
        }
        // 桥接方法：泛型接口方法擦除后与实现类方法描述符不同，须由编译器补桥
        // （ACC_BRIDGE|ACC_SYNTHETIC）。只对「非接口、非抽象」的普通类生成。
        if (!isInterface && !isEnum && !isAnnotation && cls.modality != Modality.ABSTRACT) {
            generateBridgeMethods(cw, cls, internalName)
        }
        // 只有当存在「需要运行期初始化的静态字段」时才生成 <clinit>，避免发出空的、
        // 但合法的 <clinit> 属性（多余代码、也有害于 class 体积与工具分析）。
        val staticFields = cls.declarations.filterIsInstance<IrField>().filter { it.symbol in staticFieldSymbols }
        if (isEnum) {
            // 枚举基础设施自己也要写 <clinit>；把静态字段交给它合并，避免一个类出现两个
            // <clinit>（JVM 规范不允许，ASM 也不会替你合并）。
            generateEnumInfrastructure(cw, cls, internalName, staticFields)
        } else if (staticFields.any { needsRuntimeStaticInit(it) }) {
            generateStaticFieldInitializer(cw, staticFields, internalName)
        }
        cw.visitEnd()
        val bytes = cw.toByteArray()
        dir.resolve("$name.class").writeBytes(bytes)
    }

    /**
     * Compile-time constant value of a static field initializer, if any.
     * 中文：取静态字段初始化器的「编译期常量值」。只有初始化器恰好是一个字面量
     * （IrConstImpl）时才返回非 null，用于把它写成 field 上的 ConstantValue 属性。
     * 边界：Char 转成 int 码点（ConstantValue 里 char 也以 int 表示）；Null/其它
     * 常量种类返回 null，交由 <clinit> 在运行期赋值。前置条件：field.initializer
     * 允许为 null（无初始化器 → 返回 null）。
     */
    private fun staticFieldConstantValue(field: IrField): Any? {
        val expr = field.initializer?.expression as? IrConstImpl ?: return null
        return when (expr.kind) {
            IrConstKind.Int -> (expr.value as Number).toInt()
            IrConstKind.Long -> expr.value as Long
            IrConstKind.Float -> expr.value as Float
            IrConstKind.Double -> expr.value as Double
            IrConstKind.Boolean -> expr.value as Boolean
            IrConstKind.Char -> (expr.value as Char).code
            IrConstKind.String -> expr.value as String
            else -> null
        }
    }

    /**
     * 做什么：判断某个静态字段的声明初值是否必须由 `<clinit>` 在运行期执行。
     * 什么时候用：决定是否生成 `<clinit>`，以及 `<clinit>` 内是否跳过该字段。
     * 前置条件：field 已在 staticFieldSymbols 中。
     * 失败模式：无；字段无初始化器时返回 false（保持 JVM 默认值）。
     * 为什么这样设计：`static var` 是可变字段，即使初值是字面量也不能写成 ConstantValue——
     *   需求要求可变静态字段的初值在 `<clinit>` 中执行；只有 `static final` 常量才走
     *   ConstantValue 快路径。
     */
    private fun needsRuntimeStaticInit(field: IrField): Boolean {
        if (field.initializer == null) return false
        return !field.isFinal || staticFieldConstantValue(field) == null
    }

    /**
     * 做什么：把 IR 字段可见性映射为 JVM 字段访问标志（ACC_PUBLIC/PRIVATE/PROTECTED）。
     * 什么时候用：发射静态字段时。
     * 前置条件：visibility 来自 translator（已按声明归一）。
     * 失败模式：未知可见性返回 0（包私有），不会抛异常。
     * 为什么这样设计：ACC_* 是字节码层的唯一真相；集中映射可避免字段/方法各写一遍。
     */
    private fun fieldVisibilityFlags(visibility: DescriptorVisibility): Int = when (visibility) {
        DescriptorVisibilities.PUBLIC -> Opcodes.ACC_PUBLIC
        DescriptorVisibilities.PRIVATE -> Opcodes.ACC_PRIVATE
        DescriptorVisibilities.PROTECTED -> Opcodes.ACC_PROTECTED
        else -> 0
    }

    /**
     * Emits a `<clinit>` that initializes static fields requiring runtime evaluation.
     * Final fields with a compile-time literal use a ConstantValue attribute instead.
     * 中文：为一个类发射 <clinit>，按声明顺序把「需要运行期初始化」的静态字段逐个
     * 求值并 PUTSTATIC。需要运行期初始化的判定见 [needsRuntimeStaticInit]：非 final
     * 字段即使初值是字面量也走这里；final 字面量则已作为 ConstantValue 直接写在字段上。
     *
     * 边界/前置条件：<clinit> 必须无参、返回 void、ACC_STATIC；调用方保证至少有一个
     * 需要运行期初始化的字段（否则不该生成它）。失败模式：字段初始化表达式求值出错
     * 时由 generateIrExpression 抛出，<clinit> 会处于半写状态并被上层整体丢弃。
     */
    private fun generateStaticFieldInitializer(cw: ClassWriter, fields: List<IrField>, owner: String) {
        val mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        mv.visitCode()
        // <clinit> 是静态的，所以参数槽从 0 开始；返回值类型为 void（null）也不需要接收者。
        val ctx = MethodContext(mv, mutableListOf(), mutableMapOf(), mutableMapOf(), 0, null, true)
        emitStaticFieldInitializers(mv, fields, owner, ctx)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 做什么：把需要运行期初始化的静态字段逐个求值并 PUTSTATIC 到 [owner]。
     * 什么时候用：普通类的 `<clinit>`，以及枚举类 `<clinit>` 的尾部（避免生成两个 `<clinit>`）。
     * 前置条件：mv 已 visitCode；ctx 对应同一个静态方法（参数槽从 0 开始）。
     * 失败模式：初始化表达式生成失败时异常向上抛，整个类生成失败。
     * 为什么单独抽出：枚举基础设施也要写 `<clinit>`；共用同一段赋值逻辑可保证
     *   「枚举常量先初始化、用户静态字段后初始化」的顺序只有一处定义。
     */
    private fun emitStaticFieldInitializers(
        mv: MethodVisitor,
        fields: List<IrField>,
        owner: String,
        ctx: MethodContext
    ) {
        for (field in fields) {
            val body = field.initializer ?: continue
            if (!needsRuntimeStaticInit(field)) continue
            generateIrExpression(mv, body.expression, ctx, owner)
            mv.visitFieldInsn(
                Opcodes.PUTSTATIC, owner, field.name.toString(),
                typeMapper.jvmDescriptorFromIrType(field.type)
            )
        }
    }

    // 为一个枚举类补齐 JVM 层面的枚举基础设施：<clinit> 里实例化各常量并填
    // `__jasper_values` 缓存数组，再生成合成的 `values()` 与 `valueOf(String)`。
    // 为什么需要：语言层只声明了常量与构造器；values()/valueOf() 是 Java 枚举协议
    // 要求的方法，用户代码可能调用，必须由后端合成。
    // 常量识别：直接收集 cls.declarations 里的 IrEnumEntry（Kotlin IR 对枚举常量的原生
    // 表示），声明顺序即 ordinal 顺序。不再使用「字段名首字母大写」启发式——它会把
    // 普通静态字段误当常量，也会漏掉小写开头的常量，且与 translator 的 IrEnumEntry
    // 表示对不上（Q18 崩溃的根因之一）。
    // 已知限制：当前只支持无用户参数的枚举常量的 <clinit> 实例化；带实参的常量
    // （`A(1)`）仍按 (name, ordinal) 调用构造器，属任务书明确的非目标。
    private fun generateEnumInfrastructure(
        cw: ClassWriter,
        cls: IrClass,
        internalName: String,
        staticFields: List<IrField> = emptyList()
    ) {
        val enumConstNames = cls.declarations.filterIsInstance<IrEnumEntry>().map { it.name.toString() }
        if (enumConstNames.isEmpty()) {
            // 没有枚举常量时不创建枚举基础设施；但用户静态字段仍需初始化。
            if (staticFields.any { needsRuntimeStaticInit(it) }) {
                generateStaticFieldInitializer(cw, staticFields, internalName)
            }
            return
        }
        val n = enumConstNames.size
        // values() 返回的数组元素类型：枚举类自身的引用数组描述符。
        val valuesDesc = "[L$internalName;"
        // 合成缓存字段：private static final synthetic，避免与用户字段/继承冲突。
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            "__jasper_values", valuesDesc, null, null)
        // <clinit> 里先实例化常量，再装填缓存数组——顺序不能反，数组元素引用的是常量字段。
        val clMv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clMv.visitCode()
        for ((i, constName) in enumConstNames.withIndex()) {
            // NEW + DUP 保留一份引用供 <init> 消耗；枚举构造器的前两个隐式参数是
            // (name, ordinal)，这里用 LDC 常量名与 SIPUSH 序号压栈。
            clMv.visitTypeInsn(Opcodes.NEW, internalName)
            clMv.visitInsn(Opcodes.DUP)
            clMv.visitLdcInsn(constName)
            clMv.visitIntInsn(Opcodes.SIPUSH, i)
            clMv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "(Ljava/lang/String;I)V", false)
            clMv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constName, "L$internalName;")
        }
        // 构造长度为 n 的数组，然后逐元素 DUP-压索引-GETSTATIC-AASTORE 填充。
        clMv.visitIntInsn(Opcodes.BIPUSH, n)
        clMv.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
        for ((i, constName) in enumConstNames.withIndex()) {
            clMv.visitInsn(Opcodes.DUP)
            clMv.visitIntInsn(Opcodes.BIPUSH, i)
            clMv.visitFieldInsn(Opcodes.GETSTATIC, internalName, constName, "L$internalName;")
            clMv.visitInsn(Opcodes.AASTORE)
        }
        clMv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "__jasper_values", valuesDesc)
        // 用户静态字段排在枚举常量/缓存数组之后初始化：此时常量已可用，且同类只有一个 <clinit>。
        if (staticFields.any { needsRuntimeStaticInit(it) }) {
            val ctx = MethodContext(clMv, mutableListOf(), mutableMapOf(), mutableMapOf(), 0, null, true)
            emitStaticFieldInitializers(clMv, staticFields, internalName, ctx)
        }
        clMv.visitInsn(Opcodes.RETURN)
        clMv.visitMaxs(0, 0)
        clMv.visitEnd()
        // values()：返回缓存数组的 clone()，保证调用方改不到内部数组（与 Java 语义一致）。
        val vMv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "values", "()[L$internalName;", null, null)
        vMv.visitCode()
        vMv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "__jasper_values", valuesDesc)
        vMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L$internalName;", "clone", "()Ljava/lang/Object;", false)
        // CHECKCAST 的目标必须是「类型名或数组描述符」：这里 clone() 返回 Object，
        // 需要转回数组类型，因此应传数组描述符 `[Lpkg/Enum;`（去掉前导 '[' 会得到
        // `Lpkg/Enum;`，那是字段描述符，不是合法类名，加载期直接 ClassFormatError）。
        vMv.visitTypeInsn(Opcodes.CHECKCAST, valuesDesc)
        vMv.visitInsn(Opcodes.ARETURN)
        vMv.visitMaxs(0, 0)
        vMv.visitEnd()
        // valueOf(String)：直接转调 java/lang/Enum.valueOf(Class,String)，由 JDK 做
        // 常量表查找与 IllegalArgumentException 语义，避免自己维护名字表。
        val vfMv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)L$internalName;", null, null)
        vfMv.visitCode()
        vfMv.visitLdcInsn(org.jetbrains.org.objectweb.asm.Type.getType("L$internalName;"))
        vfMv.visitVarInsn(Opcodes.ALOAD, 0)
        vfMv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false)
        vfMv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
        vfMv.visitInsn(Opcodes.ARETURN)
        vfMv.visitMaxs(0, 0)
        vfMv.visitEnd()
    }

    // 枚举构造器：JVM 要求枚举类的 <init> 前两个参数固定是 (String name, int ordinal)，
    // 且必须先把它们转交给 java/lang/Enum.<init> 完成常量注册。用户声明的参数排在
    // 这两个之后，因此局部槽从 3 起（this=0, name=1, ordinal=2）。
    // 注意：构造器固定为 ACC_PRIVATE（枚举常量的构造只能由类自身触发）。
    private fun generateEnumConstructor(cw: ClassWriter, ctor: IrConstructor, owner: String) {
        // Enum constructors need synthetic (String name, int ordinal) prefix before user params
        val paramDescs = ctor.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        val desc = "(Ljava/lang/String;I$paramDescs)V"
        val mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", desc, null, null)
        mv.visitCode()
        // this.name / this.ordinal 由 <clinit> 在 NEW 时压栈传入，这里原样透传给超类。
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 2)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false)
        // Translate constructor body
        val body = ctor.body
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            locals.add(LocalInfo("this", null, 0))
            var slot = 3 // this(0), name(1), ordinal(2) are synthetic
            for (p in ctor.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                // long/double 参数占两个槽位，必须按类型宽度顺延。
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, null, false)
            for (stmt in body.statements) {
                generateIrStatement(mv, stmt, ctx, owner)
            }
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 生成一个方法（含顶层函数、成员函数、getter/setter）。isStatic 决定参数槽起点与
    // access 里的 ACC_STATIC；methodFlags >= 0 时完全接管 access（接口方法用
    // public abstract）。返回类型决定 return 指令并在缺 return 时补一条。
    // 抽象方法（interface 方法）按 JVM 规则**不写 Code 属性**，只 visitMethod 后 visitEnd。
    private fun generateIrFunction(cw: ClassWriter, func: IrSimpleFunction, owner: String, isStatic: Boolean, methodFlags: Int = -1) {
        val desc = irMethodDescriptor(func)
        val access = if (methodFlags >= 0) methodFlags else (Opcodes.ACC_PUBLIC or (if (isStatic) Opcodes.ACC_STATIC else 0))
        // 泛型签名：类型参数或有泛型参数/返回时才写 Signature，否则保持 null（擦除即可）。
        val methodSig = if (func.typeParameters.isNotEmpty() || typeMapper.hasGenericParams(func)) {
            typeMapper.buildMethodSignature(func)
        } else null
        val mv = cw.visitMethod(access, func.name.toString(), desc, methodSig, null)
        // For abstract methods (e.g., interface methods), don't emit a Code attribute
        // 中文：抽象方法（如接口方法）没有方法体，绝不能调用 visitCode，否则生成的
        // class 会被校验器拒绝（抽象方法不得带 Code）。
        if ((access and Opcodes.ACC_ABSTRACT) != 0) {
            mv.visitEnd()
            return
        }
        mv.visitCode()
        val body = func.body
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            // 实例方法的槽 0 是 this，参数从 1 起；静态方法参数从 0 起。
            var slot = if (isStatic) 0 else 1
            for (p in func.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                // long/double 参数占两个槽，必须按宽度顺延后续参数槽号。
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, func.returnType, isStatic)
            generateIrBlockBody(mv, body, ctx, owner)
            // IR 体若没有显式 return（例如函数末尾是表达式语句），补齐一条符合返回类型的
            // return；否则字节码会「走到方法末尾却没有返回指令」，验证时直接报错。
            if (!hasIrReturn(body)) {
                generateIrReturn(mv, func.returnType)
            }
        } else {
            // 没有 body（理论上不该出现；抽象方法已在上面提前返回）时，生成裸 return
            // 保持方法结构完整，避免 visitMaxs/visitEnd 之间没有指令。
            generateIrReturn(mv, func.returnType)
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 做什么：为一个实现了泛型接口的普通类生成桥接方法（`ACC_PUBLIC|ACC_BRIDGE|ACC_SYNTHETIC`）。
     *
     * 什么时候用：[generateIrClass] 发射完本类成员后，且本类是非抽象、非接口、非枚举的普通类。
     * 前置条件：[interfaceMethods] 已由 [generate] 第一遍建好；[classInterfaceNames] 给出本类
     *   直接实现的接口内部名列表。
     * 失败模式：无法桥接的形状（见 [canBridge]）会**跳过**该方法且不抛异常——运行期若真被调用
     *   会得到 `AbstractMethodError`，属明确的 v1 边界（例如接口基本类型形参 ↔ 实现引用形参）；
     *   「无界类型变量 ↔ 基本类型实参」（如 `Box<int32>`）通过装箱/拆箱已支持。
     * 为什么这样设计：JVM 上泛型接口方法擦除后（如 `save(T)` → `(Object)V`）与实现类方法
     *   （`save(Person)V`）描述符不同，必须由编译器补桥才能经接口变量分派。把「哪些
     *   (接口方法, 实现方法) 需要桥」收敛到本函数，主生成路径无须理解泛型（隔离的变化点）。
     */
    private fun generateBridgeMethods(cw: ClassWriter, cls: IrClass, internalName: String) {
        val ifaceNames = classInterfaceNames[internalName] ?: return
        val ownMethods = cls.declarations.filterIsInstance<IrSimpleFunction>()
        if (ownMethods.isEmpty() || ifaceNames.isEmpty()) return
        val ownByName = ownMethods.groupBy { it.name.toString() }
        // 已存在的 (名字, 描述符) 集合：实现类若已用擦除后签名声明，就无需桥。
        val existing = ownMethods.map { it.name.toString() to irMethodDescriptor(it) }.toMutableSet()
        for (ifaceInternal in ifaceNames) {
            val methods = interfaceMethods[ifaceInternal] ?: continue
            for (ifaceFunc in methods) {
                val mName = ifaceFunc.name.toString()
                val ifaceDesc = irMethodDescriptor(ifaceFunc)
                if ((mName to ifaceDesc) in existing) continue
                val candidates = ownByName[mName] ?: continue
                val target = candidates.firstOrNull { canBridge(ifaceFunc, it) } ?: continue
                emitBridgeMethod(cw, internalName, mName, ifaceFunc, target)
                existing.add(mName to ifaceDesc)
            }
        }
    }

    /**
     * 做什么：判断实现类方法 [target] 能否作为接口方法 [ifaceFunc] 擦除后签名的桥接目标。
     * 什么时候用：[generateBridgeMethods] 选取目标方法时。
     * 前置条件：二者同名（调用方已保证）；参数/返回类型描述符可解析。
     * 失败模式：形状不支持时返回 false（调用方跳过，不生成非法桥）。
     * 为什么这样设计：v1 只支持「参数/返回都是引用类型（含数组）」的桥——此时 Object 与具体
     *   引用之间可用 CHECKCAST/ARETURN 安全转换；一旦涉及基本类型与 Object 的互转（需要
     *   装箱/拆箱）就拒绝，避免生成错误字节码（宁可留 AbstractMethodError 也不留 VerifyError）。
     */
    private fun canBridge(ifaceFunc: IrSimpleFunction, target: IrSimpleFunction): Boolean {
        if (ifaceFunc.valueParameters.size != target.valueParameters.size) return false
        val ifaceRet = typeMapper.jvmReturnDescriptorFromIrType(ifaceFunc.returnType)
        val targetRet = typeMapper.jvmReturnDescriptorFromIrType(target.returnType)
        if (!isBridgeableReturn(ifaceRet, targetRet)) return false
        for (i in ifaceFunc.valueParameters.indices) {
            val ifaceDesc = typeMapper.jvmDescriptorFromIrType(ifaceFunc.valueParameters[i].type)
            val targetDesc = typeMapper.jvmDescriptorFromIrType(target.valueParameters[i].type)
            if (!isBridgeableParam(ifaceDesc, targetDesc)) return false
        }
        return true
    }

    /**
     * 做什么：单个形参位能否桥接（接口擦除描述符 [ifaceDesc] → 实现描述符 [targetDesc]）。
     * 什么时候用：[canBridge] 校验形参时。
     * 前置条件：无。
     * 失败模式：无（纯判定）。
     * 为什么这样设计：三种可桥情形——① 描述符相同；② 两侧都是引用（CHECKCAST 收窄）；
     *   ③ 接口擦除为引用（Object）、实现为基本类型（拆箱，如 `Box<int32>` 的 `put`）。
     *   其余（基本类型↔引用方向不明）拒绝，避免生成错误字节码。
     */
    private fun isBridgeableParam(ifaceDesc: String, targetDesc: String): Boolean {
        if (ifaceDesc == targetDesc) return true
        val ifaceIsRef = referenceInternalName(ifaceDesc) != null
        val targetIsRef = referenceInternalName(targetDesc) != null
        if (ifaceIsRef && targetIsRef) return true
        // 引用（擦除后的 Object）→ 基本类型：桥内拆箱。
        return ifaceIsRef && targetDesc != "V"
    }

    /**
     * 做什么：返回位能否桥接（接口擦除返回描述符 [ifaceDesc] → 实现返回描述符 [targetDesc]）。
     * 什么时候用：[canBridge] 校验返回位时。
     * 前置条件：无。
     * 失败模式：无（纯判定）。
     * 为什么这样设计：① 相同；② 两侧都是引用（ARETURN 向上转型）；③ 实现为基本类型、
     *   接口擦除为引用（装箱，如 `Box<int32>` 的 `get`）——但 [emitBoxingIfNeeded] 目前
     *   只覆盖 I/J/F/D/Z，故基本类型限这几类（B/S/C 拒绝，避免写出错误字节码）。
     */
    private fun isBridgeableReturn(ifaceDesc: String, targetDesc: String): Boolean {
        if (ifaceDesc == targetDesc) return true
        val ifaceIsRef = referenceInternalName(ifaceDesc) != null
        val targetIsRef = referenceInternalName(targetDesc) != null
        if (ifaceIsRef && targetIsRef) return true
        return ifaceIsRef && targetDesc in setOf("I", "J", "F", "D", "Z")
    }

    /**
     * 做什么：把引用/数组描述符还原为 `CHECKCAST` 可用的「类内部名 / 数组描述符」。
     * 什么时候用：桥接方法的 CHECKCAST 目标、以及桥接可行性判定。
     * 前置条件：desc 是合法描述符。
     * 失败模式：基本类型（单字符）返回 null；调用方须据此判定「不可桥」。
     * 为什么这样设计：JVM 的 CHECKCAST 对引用类传内部名（`p/Q`）、对数组传数组描述符（`[I`），
     *   两者形态不同，集中到一个函数避免调用点各写一套转换。
     */
    private fun referenceInternalName(desc: String): String? = when {
        desc.startsWith("L") && desc.endsWith(";") -> desc.substring(1, desc.length - 1)
        desc.startsWith("[") -> desc
        else -> null
    }

    /**
     * 做什么：发射一个桥接方法：按接口擦除描述符收参数 → 逐参 CHECKCAST 到实现类型 →
     *   `INVOKEVIRTUAL` 转发到实现方法 → 按返回类型返回。
     * 什么时候用：[generateBridgeMethods] 选定 (接口方法, 实现方法) 后。
     * 前置条件：[canBridge] 已判定可桥（引用↔引用用 CHECKCAST；Object↔基本类型用装箱/拆箱）。
     * 失败模式：不抛异常；若中途发现无法转换会写出不完整方法——因此调用前必须经 canBridge 校验。
     * 为什么这样设计：桥接体的唯一变化点是「参数收窄/拆箱 → 转发 → 返回装箱」三步，集中在一处
     *   可读性最好；返回指令统一按接口擦除返回类型选，保证适配后栈类型与声明一致。
     */
    private fun emitBridgeMethod(
        cw: ClassWriter,
        internalName: String,
        name: String,
        ifaceFunc: IrSimpleFunction,
        target: IrSimpleFunction
    ) {
        val ifaceDesc = irMethodDescriptor(ifaceFunc)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC,
            name, ifaceDesc, null, null
        )
        mv.visitCode()
        // 桥是实例方法：槽 0 是 this，参数从 1 起（与普通实例方法一致）。
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        var slot = 1
        for (i in ifaceFunc.valueParameters.indices) {
            val ifaceParam = typeMapper.jvmDescriptorFromIrType(ifaceFunc.valueParameters[i].type)
            loadLocal(mv, ifaceParam, slot)
            val targetParam = typeMapper.jvmDescriptorFromIrType(target.valueParameters[i].type)
            if (ifaceParam != targetParam) {
                if (referenceInternalName(targetParam) != null) {
                    // 引用 → 引用：CHECKCAST 收窄到实现形参类型。
                    mv.visitTypeInsn(Opcodes.CHECKCAST, referenceInternalName(targetParam)!!)
                } else {
                    // 引用（擦除后的 Object）→ 基本类型：拆箱。
                    emitUnboxingFromRef(mv, targetParam)
                }
            }
            // 槽位按「接口擦除后的参数」宽度推进（宽类型 J/D 占两槽）。
            slot += irTypeSlots(ifaceFunc.valueParameters[i].type)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, name, irMethodDescriptor(target), false)
        // 返回适配：实现是基本类型、接口擦除为引用时补装箱（如 int → Object）。
        val ifaceRet = typeMapper.jvmReturnDescriptorFromIrType(ifaceFunc.returnType)
        val targetRet = typeMapper.jvmReturnDescriptorFromIrType(target.returnType)
        if (ifaceRet != targetRet && referenceInternalName(ifaceRet) != null && referenceInternalName(targetRet) == null) {
            emitBoxingIfNeeded(mv, targetRet, ifaceRet)
        }
        // 返回指令以「接口擦除后的返回」为准：适配后栈上值的类型与它一致。
        generateIrReturn(mv, ifaceFunc.returnType)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 普通类构造器：访问标志固定 ACC_PUBLIC，返回 void，名字固定 <init>。
    // 如果 IR 体的第一条语句不是 ctor_delegate 调用（即用户没写 this(...)/super(...)），
    // 就自动补一条对超类无参 <init> 的调用——JVM 要求构造器必须先初始化超类。
    private fun generateIrConstructor(cw: ClassWriter, ctor: IrConstructor, owner: String) {
        val desc = irConstructorDescriptor(ctor)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
        mv.visitCode()
        val body = ctor.body
        // Check if constructor has delegation (first statement is a ctor_delegate call)
        // 中文：translator 把显式 this(...)/super(...) 编码成名为 "ctor_delegate:..." 的
        // 调用且放在第一条；据此判断是否需要合成隐式 super()。
        val hasDelegate = body is IrBlockBody && body.statements.isNotEmpty() &&
            body.statements[0] is IrCall &&
            callNames[(body.statements[0] as IrCall).symbol]?.startsWith("ctor_delegate:") == true
        if (!hasDelegate) {
            // 隐式 super()：ALOAD 0 + INVOKESPECIAL Super.<init>()V。owner 就是类的
            // JVM 内部名，映射表也以内部名为键，直接查表即可；查不到回落 Object。
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            val superName = classSuperclassNames[owner] ?: "java/lang/Object"
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        }
        if (body is IrBlockBody) {
            val locals = mutableListOf<LocalInfo>()
            val variables = mutableMapOf<String, Int>()
            val symbolToName = mutableMapOf<IrValueSymbol, String>()
            // 构造器一定是实例方法：槽 0 是 this，参数从 1 起。
            locals.add(LocalInfo("this", null, 0))
            var slot = 1
            for (p in ctor.valueParameters) {
                val pName = p.name.toString()
                locals.add(LocalInfo(pName, p.type, slot))
                variables[pName] = slot
                symbolToName[p.symbol] = pName
                slot += irTypeSlots(p.type)
            }
            val ctx = MethodContext(mv, locals, variables, symbolToName, slot, null, false)
            generateIrBlockBody(mv, body, ctx, owner)
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // 顺序发射块体里所有语句；异常/控制流语义由各语句自己的生成逻辑负责。
    private fun generateIrBlockBody(mv: MethodVisitor, body: IrBlockBody, ctx: MethodContext, owner: String) {
        for (stmt in body.statements) {
            generateIrStatement(mv, stmt, ctx, owner)
        }
    }

    // 语句生成分派：每种 IR 语句对应一组字节码指令，末尾必须保持「操作数栈为空且
    // 控制流合法」的不变量——这也是下面大量「表达式语句后补 POP」存在的原因。
    // 与 translator 的协议：语义化的调用名（callNames）在这里决定走哪条专用路径。
    private fun generateIrStatement(mv: MethodVisitor, stmt: IrStatement, ctx: MethodContext, owner: String) {
        when (stmt) {
            is IrVariable -> {
                val name = stmt.name.toString()
                val desc = typeMapper.jvmDescriptorFromIrType(stmt.type)
                // 槽位只增不减：不复用已离开作用域的槽，避免与仍存活的值别名。
                val slot = ctx.nextSlot
                // long/double 占两个槽；这里按描述符而非 IR 类型判断，与 loadLocal 一致。
                ctx.nextSlot += if (desc == "J" || desc == "D") 2 else 1
                ctx.variables[name] = slot
                ctx.locals.add(LocalInfo(name, stmt.type, slot))
                ctx.symbolToName[stmt.symbol] = name
                val init = stmt.initializer
                if (init != null) {
                    generateIrExpression(mv, init, ctx, owner)
                    // 初始化器类型可能与变量声明类型不同（如 int 字面量赋给 long/double、
                    // 具体实现赋给接口/父类变量）：按**声明目标类型**做数值拓宽、装箱与
                    // 引用宽化（CHECKCAST），最后才 store，保证槽里的值类型与描述符一致，
                    // 也让 javap 能在赋值处看到接口/父类的 CHECKCAST（C13）。
                    val initDesc = typeMapper.jvmDescriptorFromIrType(init.type)
                    widenIfNeeded(mv, initDesc, desc)
                    emitBoxingIfNeeded(mv, initDesc, desc)
                    emitReferenceWidening(mv, initDesc, desc)
                    storeLocal(mv, desc, slot)
                }
            }
            is IrReturn -> {
                val value = stmt.value
                val retType = ctx.returnType
                val returnDesc = if (retType != null) typeMapper.jvmReturnDescriptorFromIrType(retType) else "V"
                val hasValue = !isIrVoidExpr(value)
                // translator 用「Unit 类型的 IrConst(Null)」表示无返回值；此时不能再压栈。
                // 注意：源码的 `null` 字面量同样是 IrConst(Null)，但类型是 Any?，必须压
                // ACONST_NULL（由 isIrVoidExpr 按返回描述符区分，见其 KDoc）。
                if (hasValue) {
                    generateIrExpression(mv, value, ctx, owner)
                    val valueDesc = typeMapper.jvmDescriptorFromIrType(value.type)
                    if (retType != null) {
                        // 返回值同样要转换到方法声明的返回类型：数值拓宽/装箱之外，引用类型
                        // 还要补 CHECKCAST（C13：`function make(): Repo { return new MemRepo(); }`
                        // 返回处应显式宽化到 Repo）。
                        // 例外：`return null` 必须保持 `aconst_null; areturn` 的既有形状
                        // （NULL 常量对接口/类目标再做 CHECKCAST 虽合法，但会破坏可空返回
                        // 的字节码契约，且没有语义收益）。
                        widenIfNeeded(mv, valueDesc, returnDesc)
                        emitBoxingIfNeeded(mv, valueDesc, returnDesc)
                        val isNullLiteral = value is IrConstImpl && value.kind == IrConstKind.Null
                        if (!isNullLiteral) {
                            emitReferenceWidening(mv, valueDesc, returnDesc)
                        }
                    }
                }
                // ③ 提前 return 路径（FINDINGS #4）：若当前处于 try/finally 体内，必须在真正返回前
                // 执行所有外层 finally。做法：先把已拓宽/装箱好的返回值存进临时槽，按「内层到外层」
                // 顺序执行 finally，再取回值返回。
                // 生成 finally 期间临时清空 finallyStack，避免 finally 自身里的 return 递归执行自身
                // （Java 语义：finally 里的 return 覆盖外层 return）。
                if (ctx.finallyStack.isNotEmpty()) {
                    var savedSlot = -1
                    if (hasValue) {
                        savedSlot = ctx.nextSlot
                        ctx.nextSlot += if (returnDesc == "J" || returnDesc == "D") 2 else 1
                        storeLocal(mv, returnDesc, savedSlot)
                    }
                    emitPendingFinally(mv, ctx, owner, 0)
                    if (hasValue) loadLocal(mv, returnDesc, savedSlot)
                }
                generateIrReturn(mv, ctx.returnType)
            }
            is IrBlock -> {
                // 语句位置的块：顺序展开，不做值语义（其最后一条表达式的值是丢弃的）。
                for (s in stmt.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            }
            is IrWhen -> generateIrWhen(mv, stmt, ctx, owner)
            is IrWhileLoop -> {
                // while(cond) body 的模板（先判条件、后跳回）：
                //   loop: cond; IFEQ end; body; GOTO loop; end:
                // continue 跳 loopLabel（重新判条件），break 跳 endLabel。
                val loopLabel = Label()
                val endLabel = Label()
                val targets = Pair(loopLabel, endLabel)
                ctx.loopStack.add(targets)
                ctx.loopTargets[stmt] = targets
                ctx.loopFinallyDepth[targets] = ctx.finallyStack.size
                mv.visitLabel(loopLabel)
                val cond = stmt.condition
                generateIrExpression(mv, cond, ctx, owner)
                // 条件为「非 0/true」才进体，故 false 时 IFEQ 直接跳出。
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel)
                val loopBody = stmt.body
                if (loopBody is IrBlock) {
                    for (s in loopBody.statements) {
                        generateIrStatement(mv, s, ctx, owner)
                    }
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabel)
                mv.visitLabel(endLabel)
                // 出循环后弹栈，恢复上层的 break/continue 目标。
                ctx.loopStack.removeLast()
                ctx.loopTargets.remove(stmt)
                ctx.loopFinallyDepth.remove(targets)
            }
            is IrDoWhileLoop -> {
                // do body while(cond)：体至少执行一次，条件在末尾判断。
                //   loop: body; cond; IFNE loop; end:
                // continue 跳 loopLabel 会**跳过条件**直接回体，这是与 while 的已知差异
                // （由 translator 生成 IR 的方式决定，后端照此翻译）。
                val loopLabel = Label()
                val endLabel = Label()
                val targets = Pair(loopLabel, endLabel)
                ctx.loopStack.add(targets)
                ctx.loopTargets[stmt] = targets
                ctx.loopFinallyDepth[targets] = ctx.finallyStack.size
                mv.visitLabel(loopLabel)
                val loopBody = stmt.body
                if (loopBody is IrBlock) {
                    for (s in loopBody.statements) {
                        generateIrStatement(mv, s, ctx, owner)
                    }
                }
                val cond = stmt.condition
                generateIrExpression(mv, cond, ctx, owner)
                // 条件非 0/true 则回到循环头（IFNE）。
                mv.visitJumpInsn(Opcodes.IFNE, loopLabel)
                mv.visitLabel(endLabel)
                ctx.loopStack.removeLast()
                ctx.loopTargets.remove(stmt)
                ctx.loopFinallyDepth.remove(targets)
            }
            is IrTry -> {
                // try/catch/finally 的统一生成。JVM 没有结构化 finally 指令，必须让三条路径都补上
                // 「执行 finally」的代码，且同一条路径只执行一次：
                //   ① 正常结束：try 体之后顺序执行 finally，再跳到 endLabel；
                //   ② 异常路径（try 体或 catch 体抛出）：异常表兜底到 finallyHandler，
                //      执行 finally 后 ATHROW 重新抛出；
                //   ③ 提前 return：见下方 IrReturn 分支，借 finallyStack 在返回前按逆序执行 finally。
                // 旧实现只支持 ①（且异常/return 路径完全不执行 finally），是 FINDINGS #4 的根因。
                val finallyExpr = stmt.finallyExpression
                val hasFinally = finallyExpr != null
                // 本 try 体的受保护区间；外层 finally 内联进来的代码会在这里留下空隙。
                val tryRange = ProtectedRange()
                val fctx = if (hasFinally) FinallyContext(finallyExpr!!, ctx.openRanges.size) else null

                val tryStart = Label()
                val tryEnd = Label()
                val finallyHandler = Label()
                val endLabel = Label()
                // 每个 catch 子句一个处理器入口：异常表按子句书写顺序逐一登记各自的捕获类型，
                // JVM 按登记顺序匹配，于是只有第一个类型匹配的子句执行（LANG-SYNC-CATCH-01）。
                val catchHandlers = stmt.catches.map { Label() }

                // 注意：本 try 的异常表项**延迟到生成完 try 体之后**才登记（见下）。
                // JVM 按登记顺序匹配异常表项，嵌套 try 的内层处理器必须先于外层登记，
                // 否则外层 catch 会「抢先」吞掉本该由内层 finally 处理的异常。
                // （ASM 允许 visitTryCatchBlock 晚于对应 label 调用，输出顺序即调用顺序。）

                // ---- ① try 体 ----
                // 生成 try 体期间把 finally 上下文压栈，使体内任何 return 都能在返回前执行 finally。
                if (fctx != null) ctx.finallyStack.add(fctx)
                ctx.openRanges.add(tryRange)
                mv.visitLabel(tryStart)
                // try 体可能为空（如块末尾的 defer 降级出的 try）：一条 NOP 使受保护区间非空。
                mv.visitInsn(Opcodes.NOP)
                val tryBody = stmt.tryResult
                if (tryBody is IrBlock) {
                    for (s in tryBody.statements) generateIrStatement(mv, s, ctx, owner)
                } else {
                    generateIrStatement(mv, tryBody as IrStatement, ctx, owner)
                }
                mv.visitLabel(tryEnd)
                if (fctx != null) ctx.finallyStack.removeLast()
                ctx.openRanges.removeLast()
                // 生成完 try 体后登记本 try 范围的处理器：内层（体里嵌套 try）已先登记；
                // 各 catch 子句按书写顺序在前，finally 兜底（type=null）在后。
                stmt.catches.forEachIndexed { i, catchClause ->
                    val catchTypeInternal = typeMapper.jvmInternalNameFromIrType(catchClause.catchParameter.type)
                    registerProtectedRange(mv, tryStart, tryEnd, tryRange, catchHandlers[i], catchTypeInternal)
                }
                if (hasFinally) {
                    // try 体内未被 catch 命中的异常 → finallyHandler（兜底，type=null）。
                    registerProtectedRange(mv, tryStart, tryEnd, tryRange, finallyHandler, null)
                }
                // 正常结束：执行 finally 后跳过 catch / finally 处理器。
                if (hasFinally) generateFinallyBody(mv, finallyExpr!!, ctx, owner)
                mv.visitJumpInsn(Opcodes.GOTO, endLabel)

                // ---- ② catch 分支：每个子句一段独立处理器 ----
                // 子句的受保护区间只覆盖它自己的体：体内抛出的异常只交给本 try 的 finally（及外层），
                // 不会被同一 try 的兄弟子句截获；体末尾内联的 finally 与 GOTO 在区间之外。
                stmt.catches.forEachIndexed { i, catchClause ->
                    val catchStart = catchHandlers[i]
                    val catchEnd = Label()
                    mv.visitLabel(catchStart)
                    // catch 体是另一段受 finally 兜底保护的区间，内联其 finally 时从这里挖空隙。
                    val catchRange = ProtectedRange()
                    val catchFctx = if (hasFinally) FinallyContext(finallyExpr!!, ctx.openRanges.size) else null
                    // 进入处理器时操作数栈顶恰是抛出的异常对象：存入本子句的参数槽。
                    // 异常表已按声明类型匹配，栈顶静态类型即该类型，CHECKCAST 只为让校验器按声明类型取值。
                    val param = catchClause.catchParameter
                    val pName = param.name.toString()
                    val paramDesc = typeMapper.jvmDescriptorFromIrType(param.type)
                    val paramInternal = typeMapper.jvmInternalNameFromIrType(param.type)
                    val catchSlot = ctx.nextSlot
                    // 异常对象是引用，占 1 个槽。
                    ctx.nextSlot += 1
                    if (paramInternal != "java/lang/Throwable" && paramInternal != "throwable") {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, paramInternal)
                        storeLocal(mv, paramDesc, catchSlot)
                    } else {
                        storeLocal(mv, "Ljava/lang/Throwable;", catchSlot)
                    }
                    ctx.variables[pName] = catchSlot
                    ctx.locals.add(LocalInfo(pName, param.type, catchSlot))
                    ctx.symbolToName[param.symbol] = pName
                    if (catchFctx != null) ctx.finallyStack.add(catchFctx)
                    ctx.openRanges.add(catchRange)
                    val catchBody = catchClause.result
                    if (catchBody is IrBlock) {
                        for (s in catchBody.statements) generateIrStatement(mv, s, ctx, owner)
                    } else {
                        generateIrStatement(mv, catchBody as IrStatement, ctx, owner)
                    }
                    mv.visitLabel(catchEnd)
                    if (catchFctx != null) ctx.finallyStack.removeLast()
                    ctx.openRanges.removeLast()
                    // catch 体自身抛出的异常同样要跑 finally；登记时机同样晚于 catch 体内层处理器。
                    // 区间以存参数槽的指令开头，因而非空。
                    if (hasFinally) {
                        registerProtectedRange(mv, catchStart, catchEnd, catchRange, finallyHandler, null)
                    }
                    // catch 正常结束同样要执行 finally。
                    if (hasFinally) generateFinallyBody(mv, finallyExpr!!, ctx, owner)
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                }

                // ---- ③ finally 兜底处理器：任何未捕获异常都在执行 finally 后重抛 ----
                // 先把异常存入临时槽，跑完 finally 再取回 ATHROW；finally 里若抛出则新异常向外传播
                // （原异常被丢弃），与 Java 语义一致。
                if (hasFinally) {
                    mv.visitLabel(finallyHandler)
                    val pendingSlot = ctx.nextSlot
                    ctx.nextSlot += 1
                    storeLocal(mv, "Ljava/lang/Throwable;", pendingSlot)
                    generateFinallyBody(mv, finallyExpr!!, ctx, owner)
                    loadLocal(mv, "Ljava/lang/Throwable;", pendingSlot)
                    mv.visitInsn(Opcodes.ATHROW)
                }

                mv.visitLabel(endLabel)
            }
            is IrThrow -> {
                // 先把异常对象压栈，再 ATHROW；之后代码不可达，无需清理栈。
                generateIrExpression(mv, stmt.value, ctx, owner)
                mv.visitInsn(Opcodes.ATHROW)
            }
            is IrBreak -> {
                // 优先跳到 IR 指定的循环；旧 IR/无目标时退回最内层。
                // 跳出位于循环内的 try/finally（含 defer 降级）时，先由内到外执行这些 finally。
                val target = ctx.loopTargets[stmt.loop] ?: ctx.loopStack.lastOrNull()
                if (target != null) {
                    emitPendingFinally(mv, ctx, owner, ctx.loopFinallyDepth[target] ?: ctx.finallyStack.size)
                    mv.visitJumpInsn(Opcodes.GOTO, target.second)
                }
            }
            is IrContinue -> {
                val target = ctx.loopTargets[stmt.loop] ?: ctx.loopStack.lastOrNull()
                if (target != null) {
                    emitPendingFinally(mv, ctx, owner, ctx.loopFinallyDepth[target] ?: ctx.finallyStack.size)
                    mv.visitJumpInsn(Opcodes.GOTO, target.first)
                }
            }
            is IrSetValue -> {
                // 赋值语句形态：符号 → 名字 → 槽，找不到槽就什么都不生成（例如
                // 该符号是字段而非局部变量，真正的写走 IrSetField 路径）。
                val name = ctx.symbolToName[stmt.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    // 以登记的 IR 类型为准（可能已拓宽），退化到语句自身的类型。
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: stmt.type)
                    generateIrExpression(mv, stmt.value, ctx, owner)
                    // 赋值目标可能是接口/父类局部变量：按声明的槽类型补 CHECKCAST，
                    // 而不是把具体实现原样存进声明为接口的槽（C13）。
                    val valueDesc = typeMapper.jvmDescriptorFromIrType(stmt.value.type)
                    widenIfNeeded(mv, valueDesc, desc)
                    emitBoxingIfNeeded(mv, valueDesc, desc)
                    emitReferenceWidening(mv, valueDesc, desc)
                    storeLocal(mv, desc, slot)
                }
            }
            is IrSetField -> {
                // 字段写：复用表达式的 IrSetField 生成逻辑（它会完成 receiver/值/putfield），
                // 但这里作为「语句」使用，无需保留结果值。
                generateIrExpression(mv, stmt as IrExpression, ctx, owner)
            }
            else -> {
                // 兜底：表达式语句——生成后弹掉它留给栈的值，维持语句边界栈为空。
                // 前提是各生成器都遵守「表达式恰好留一个值」的约定（void 调用用 ACONST_NULL
                // 占位），否则这里会栈下溢。弹栈宽度由 [discardExpressionValue] 按描述符决定（C26）。
                if (stmt is IrExpression) {
                    generateIrExpression(mv, stmt as IrExpression, ctx, owner)
                    discardExpressionValue(mv, stmt as IrExpression)
                }
            }
        }
    }

    /**
     * 做什么：在语句边界弹掉一个「只取副作用、结果被丢弃」的表达式留在操作数栈上的值，
     *   并按该表达式的 JVM 描述符选择**宽度正确**的弹栈指令。
     *
     * 什么时候用：所有「求值后丢弃结果」的生成点——表达式语句（缺陷 C26）、`when` 分支体
     *   是单个表达式、`finally` 里只作副作用的表达式。只要表达式恰好留一个值，就应走这里。
     * 前置条件：调用前栈顶正好是 [expr] 求值留下的那一个值；[expr] 的 IR 类型可被
     *   [TypeMapper] 还原为 JVM 描述符（这是本函数判断宽度的唯一依据）。
     * 失败模式：若 IR 类型与栈上实际宽度不符（上游类型推断错误），这里会发出宽度不匹配的
     *   弹栈指令，错误由 JVM 验证器在类加载时报出——本函数不猜测、不兜底，避免把真实
     *   缺陷掩盖成更隐蔽的栈错位。
     * 为什么这样设计：JVM 的 `pop` 只弹一个「category 1」槽位（int/float 及其子宽度与引用），
     *   而 `long`/`double`（描述符 `J`/`D`）占两个槽位，必须用 `pop2`；对它们发 `pop` 会
     *   让验证器报 `VerifyError: Type double_2nd ... not assignable to category1 type`
     *   （缺陷 C26 的现场）。描述符 → 指令的映射收敛到本函数这**一处**，四个丢弃点共用，
     *   隔离的变化点是「JVM 值宽度规则」：将来若新增宽类型或改占位约定，只改这里。
     *   void 表达式（描述符 `V`）走 `POP`：按本后端的约定，void 调用会补一个 ACONST_NULL
     *   占位值（category 1），弹一个槽位才是对的。
     */
    private fun discardExpressionValue(mv: MethodVisitor, expr: IrExpression) {
        // 平台调用的 IR 调用点常被记为 Unit，但实际返回可能是 long/double。丢弃宽度必须
        // 按平台感知描述符判断：若静态/实例平台调用留下 D/J，这里必须 POP2；不能只看 Unit
        // 占位。非平台表达式仍按自身 IR 类型得到原宽度。
        val desc = receiverJvmDescriptor(expr)
        mv.visitInsn(if (desc == "D" || desc == "J") Opcodes.POP2 else Opcodes.POP)
    }

    // while/if 链的语句位置生成：条件求值 → IFEQ 到 nextLabel → 分支体 → GOTO end。
    // 识别「恒真条件」作为 else 分支（translator 用 Bool 字面量 true 表示 else），
    // 避免把 else 当成普通条件再加一次判断。
    private fun generateIrWhen(mv: MethodVisitor, irWhen: IrWhen, ctx: MethodContext, owner: String) {
        val endLabel = Label()
        val nonElseBranches = irWhen.branches.filter { !isAlwaysTrue(it.condition) }
        val elseBranch = irWhen.branches.firstOrNull { isAlwaysTrue(it.condition) }
        for (branch in nonElseBranches) {
            val nextLabel = Label()
            generateIrExpression(mv, branch.condition, ctx, owner)
            // 条件为假跳到下一个分支的判定点。
            mv.visitJumpInsn(Opcodes.IFEQ, nextLabel)
            val result = branch.result
            if (result is IrBlock) {
                for (s in result.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            } else {
                // 分支体是单个表达式：它作为一个值留下，语句位置必须弹掉（宽度按描述符，C26）。
                generateIrExpression(mv, result, ctx, owner)
                discardExpressionValue(mv, result)
            }
            // 命中分支后跳过其余分支。
            mv.visitJumpInsn(Opcodes.GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        if (elseBranch != null) {
            val result = elseBranch.result
            if (result is IrBlock) {
                for (s in result.statements) {
                    generateIrStatement(mv, s, ctx, owner)
                }
            } else {
                generateIrExpression(mv, result, ctx, owner)
                discardExpressionValue(mv, result)
            }
        }
        mv.visitLabel(endLabel)
    }

    // 「恒真条件」判定：translator 用布尔字面量 true 表示 when 的 else/默认分支。
    // 只认 Bool(true)，不认常量折叠后的表达式——IR 由 translator 规范化保证。
    private fun isAlwaysTrue(cond: IrExpression?): Boolean {
        return cond is IrConstImpl && cond.kind == IrConstKind.Boolean && cond.value == true
    }

    // 表达式生成分派：调用方约定「生成结束后操作数栈顶恰好是表达式的一个值」，
    // 语句位置由调用方负责 POP。这里集中定义 IR 形态 → 指令序列的映射；
    // 关键变化点是 irName（translator 的语义调用名），它把后端与翻译策略解耦。
    private fun generateIrExpression(mv: MethodVisitor, expr: IrExpression, ctx: MethodContext, owner: String) {
        when (expr) {
            is IrConstImpl -> {
                // 常量：负数优先用 ICONST/BIPUSH/SIPUSH/LDC 的最短编码（pushInt 内部判断）；
                // boolean 压 0/1，char 压其 int 码点，null 压 ACONST_NULL。
                // 未知常量种类（如 IrConstKind 的新增项）按 null 处理，保证栈上仍有一个值。
                when (expr.kind) {
                    IrConstKind.Int -> pushInt(mv, (expr.value as Number).toInt())
                    IrConstKind.Long -> mv.visitLdcInsn(expr.value as Long)
                    IrConstKind.Float -> mv.visitLdcInsn(expr.value as Float)
                    IrConstKind.Double -> mv.visitLdcInsn(expr.value as Double)
                    IrConstKind.String -> mv.visitLdcInsn(expr.value as String)
                    IrConstKind.Boolean -> mv.visitInsn(if (expr.value as Boolean) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                    IrConstKind.Char -> pushInt(mv, (expr.value as Char).code)
                    IrConstKind.Null -> mv.visitInsn(Opcodes.ACONST_NULL)
                    else -> mv.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            is IrGetValue -> {
                // 读值：符号 → 名字 → 槽。查得到就按登记类型 LLOAD/ILOAD/...；
                // 查不到时做保守兜底——实例方法压 this（ALOAD 0，对应隐式接收者/this 引用），
                // 静态方法压 null（避免读到未定义槽导致验证失败）。
                val name = ctx.symbolToName[expr.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: expr.type)
                    loadLocal(mv, desc, slot)
                } else if (!ctx.isStatic) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                } else {
                    mv.visitInsn(Opcodes.ACONST_NULL)
                }
            }
            is IrSetValue -> {
                // 赋值表达式：与语句版不同，赋值本身也产生一个值，因此 store 后再 load
                // 一份留在栈上（Java 的 a = b 也是表达式）。
                val name = ctx.symbolToName[expr.symbol] ?: ""
                val slot = ctx.variables[name]
                if (slot != null) {
                    val local = ctx.locals.find { it.name == name }
                    val desc = typeMapper.jvmDescriptorFromIrType(local?.irType ?: expr.type)
                    generateIrExpression(mv, expr.value, ctx, owner)
                    // 按目标槽的声明描述符转换：数值拓宽、装箱，以及引用类型到
                    // 接口/父类目标的 CHECKCAST（C13）。随后 store+load 保持表达式语义。
                    val valueDesc = typeMapper.jvmDescriptorFromIrType(expr.value.type)
                    widenIfNeeded(mv, valueDesc, desc)
                    emitBoxingIfNeeded(mv, valueDesc, desc)
                    emitReferenceWidening(mv, valueDesc, desc)
                    storeLocal(mv, desc, slot)
                    loadLocal(mv, desc, slot)
                } else if (!ctx.isStatic) {
                    // 符号没登记：这通常意味着它是 this 的字段写入，真正的路径是 IrSetField。
                    // 这里仅求值 value 并压回栈，保持「表达式留一个值」的约定。
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    generateIrExpression(mv, expr.value, ctx, owner)
                    // This case handles this.x = v via IrSetField, not IrSetValue
                    // For IrSetValue with this, just evaluate the value
                } else {
                    generateIrExpression(mv, expr.value, ctx, owner)
                }
            }
            is IrCall -> {
                // 调用分派的核心：translator 把「这次调用在语义上是什么」编码进 callNames，
                // 于是同一棵 IR 能落到不同指令序列上。这是后端与翻译层之间最关键、
                // 也最容易受影响的契约（新增语义名要在此处补分支，否则落到 else 的静态调用）。
                val irName = callNames[expr.symbol] ?: functionSymbolToName[expr.symbol] ?: "invoke"
                val platformOwner = platformStaticCallOwners[expr.symbol]
                if (platformOwner != null) {
                    // 平台静态调用必须先于语义名分派：普通 `toString` 既有内建路径会改成
                    // String.valueOf，但全限定 `Integer.toString(int)` 是真实静态方法。
                    emitPlatformStaticCall(mv, expr, platformOwner, irName, ctx, owner)
                } else when (irName) {
                    "invoke_lambda" -> {
                        // 闭包调用：translator 把 lambda 编译成一个带 invoke 方法的匿名类，
                        // 这里退化为对接收者的 INVOKEVIRTUAL invoke(...)。接收者类型的
                        // 内部名可能已是全限定名（含 '/'）或只是简单名，后者按当前包补全。
                        val receiver = expr.dispatchReceiver
                        if (receiver != null) {
                            generateIrExpression(mv, receiver, ctx, owner)
                            val rawReceiverInternal = typeMapper.jvmInternalNameFromIrType(receiver.type)
                            val receiverInternal = if (rawReceiverInternal.contains("/")) rawReceiverInternal else "$currentPkg/$rawReceiverInternal"
                            val paramSb = StringBuilder()
                            for (i in 0 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                paramSb.append(typeMapper.jvmDescriptorFromIrType(arg.type))
                            }
                            val returnDesc = typeMapper.jvmReturnDescriptorFromIrType(expr.type)
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiverInternal, "invoke", "($paramSb)$returnDesc", false)
                            // Void closures (e.g. `Action.accept`) leave no value on
                            // the stack, but the statement emitter always expects one
                            // to POP. Mirror the member-call contract with a dummy null.
                            // 中文：void 闭包（如 Action.accept）调用后栈上没有值，但语句
                            // 生成器总期望有一个值可 POP；用 ACONST_NULL 占位是全部调用
                            // 分支共同遵守的约定。
                            if (returnDesc == "V") {
                                mv.visitInsn(Opcodes.ACONST_NULL)
                            }
                        }
                    }
                    "println", "print" -> {
                        // 打印：GETSTATIC System.out，把参数统一转成 String 后调用
                        // PrintStream.print/println(String)。int-like 只需一种 valueOf 重载
                        // （JVM 上 boolean/byte/short/char 都是 int）；long/float/double 各自
                        // 用对应重载。无参时 arg==null，直接调用 out.println()... 语法要求
                        // 至少一个参数，故实际是 println("") —— 由 translator 保证有实参。
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
                        val arg = expr.getValueArgument(0)
                        if (arg != null) {
                            val desc = typeMapper.jvmDescriptorFromIrType(arg.type)
                            generateIrExpression(mv, arg, ctx, owner)
                            when (desc) {
                                "I", "Z", "B", "S", "C" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($desc)Ljava/lang/String;", false)
                                "J" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                                "F" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                                "D" ->
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
                            }
                        }
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", irName, "(Ljava/lang/String;)V", false)
                        // PrintStream.print/println 返回 void，补 null 维持「留一个值」约定。
                        mv.visitInsn(Opcodes.ACONST_NULL)
                    }
                    "toString" -> {
                        emitToString(mv, expr, ctx, owner)
                    }
                    "__jasper_pow__" -> {
                        // 幂运算：JVM 没有整数幂指令，统一转 double 走 Math.pow 再转回 int。
                        // 因此 Jasper 的整数幂语义是「先转 double 计算再截断」，大数会丢精度。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.I2D)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitInsn(Opcodes.I2D)
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false)
                        mv.visitInsn(Opcodes.D2I)
                    }
                    "__jasper_string_length__" -> {
                        // string.length = Unicode 码点数（不是 UTF-16 单元数）：
                        //   s.codePointCount(0, s.length())
                        // 必须先 DUP 留住接收者再求 UTF-16 长度，且接收者表达式只求值一次
                        // （翻译层已保证只把接收者放进 IR 一次，这里不能重新 generate 原表达式）。
                        // 栈序：s -> s,s -> s,len -> s,len,0 -> s,0,len -> codePointCount。
                        // 用 SWAP 而不是临时局部槽，避免额外分配槽位、也不影响后续槽号。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitInsn(Opcodes.SWAP)
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL, "java/lang/String", "codePointCount", "(II)I", false
                        )
                    }
                    "__jasper_string_byte_length__" -> {
                        // string.byteLength() = UTF-8 编码后的字节数：
                        //   s.getBytes(StandardCharsets.UTF_8).length
                        // 不能退回无参 getBytes()（那是平台默认字符集，结果不稳定），
                        // 也不能用 String.length()（那是 UTF-16 单元数）。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            "java/nio/charset/StandardCharsets",
                            "UTF_8",
                            "Ljava/nio/charset/Charset;"
                        )
                        mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/lang/String",
                            "getBytes",
                            "(Ljava/nio/charset/Charset;)[B",
                            false
                        )
                        mv.visitInsn(Opcodes.ARRAYLENGTH)
                    }
                    "__jasper_string_code_point_at__" -> {
                        // string.codePointAt(i) 的语言语义：i 是**码点序号**，返回该处
                        // Unicode scalar value。JVM 字符串是 UTF-16，不能把 i 直接传给
                        // java/lang/String.codePointAt(I)I（那会按码元索引解释，emoji 错位）。
                        // 具体转换/校验见 emitStringCodePointAtCall。
                        emitStringCodePointAtCall(mv, expr, ctx, owner)
                    }
                    "negate" -> {
                        // 一元取负按操作数槽型选择 NEG 指令。平台静态调用的实参可能是
                        // double（如 Math.abs(-2.5)）；统一发 INEG 会在类加载时抛 VerifyError。
                        val operand = expr.getValueArgument(0)!!
                        val operandDesc = receiverJvmDescriptor(operand)
                        generateIrExpression(mv, operand, ctx, owner)
                        val opcode = when (operandDesc) {
                            "J" -> Opcodes.LNEG
                            "F" -> Opcodes.FNEG
                            "D" -> Opcodes.DNEG
                            else -> Opcodes.INEG
                        }
                        mv.visitInsn(opcode)
                    }
                    "not" -> {
                        // 逻辑非：用「IFEQ 为真」分支把非 0 归 1、0 归 0，避免依赖
                        // 具体数值，只关心零/非零，符合布尔语义。
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    "size" -> {
                        // 数组长度：数组引用压栈后 ARRAYLENGTH（不是字段读取）。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitInsn(Opcodes.ARRAYLENGTH)
                    }
                    "get" -> {
                        // 数组取元素：arr、idx 依次压栈，再按「元素描述符」选 xALOAD。
                        // 元素类型从数组自身的描述符（形如 "[I"）去掉前导 '[' 得到；
                        // 解析不出时按引用元素处理（AALOAD）。
                        val arr = expr.getValueArgument(0)!!
                        val idx = expr.getValueArgument(1)!!
                        generateIrExpression(mv, arr, ctx, owner)
                        generateIrExpression(mv, idx, ctx, owner)
                        val arrDesc = typeMapper.jvmDescriptorFromIrType(arr.type)
                        val elemDesc = if (arrDesc.startsWith("[")) arrDesc.substring(1) else "Ljava/lang/Object;"
                        mv.visitInsn(arrayLoadOpcode(elemDesc))
                    }
                    "arraySet" -> {
                        // 数组写元素且表达式有值（a[i] = v 的值是 v）：先算 rhs 存进临时槽，
                        // 再压 arr、idx，取出 rhs 做 xASTORE，最后再 load 一份 rhs 留栈上。
                        // 为什么绕一圈：xASTORE 会消耗栈上的 rhs，但表达式语义要求结果仍是 v。
                        val arr = expr.getValueArgument(0)!!
                        val idx = expr.getValueArgument(1)!!
                        val rhs = expr.getValueArgument(2)!!
                        val rhsDesc = typeMapper.jvmDescriptorFromIrType(rhs.type)
                        val rhsSlot = ctx.nextSlot
                        ctx.nextSlot += if (rhsDesc == "J" || rhsDesc == "D") 2 else 1
                        // Save rhs value so we can push it back after array store
                        generateIrExpression(mv, rhs, ctx, owner)
                        storeLocal(mv, rhsDesc, rhsSlot)
                        generateIrExpression(mv, arr, ctx, owner)
                        generateIrExpression(mv, idx, ctx, owner)
                        loadLocal(mv, rhsDesc, rhsSlot)
                        mv.visitInsn(arrayStoreOpcode(rhsDesc))
                        loadLocal(mv, rhsDesc, rhsSlot)
                    }
                    "put" -> {
                        // Map.put：receiver/key/rhs 依次压栈，key 与 value 都先装箱成
                        // Object（HashMap 只接受引用），最后调用 put 返回旧值。
                        val receiver = expr.getValueArgument(0)!!
                        val key = expr.getValueArgument(1)!!
                        val rhs = expr.getValueArgument(2)!!
                        generateIrExpression(mv, receiver, ctx, owner)
                        generateIrExpression(mv, key, ctx, owner)
                        boxTopOfStack(mv, typeMapper.jvmDescriptorFromIrType(key.type))
                        generateIrExpression(mv, rhs, ctx, owner)
                        boxTopOfStack(mv, typeMapper.jvmDescriptorFromIrType(rhs.type))
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
                    }
                    "inc" -> {
                        // 自增：long 用 LADD（常量 1 用 LCONST_1），其余用 IADD。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitInsn(Opcodes.LCONST_1)
                            mv.visitInsn(Opcodes.LADD)
                        } else {
                            pushInt(mv, 1)
                            mv.visitInsn(Opcodes.IADD)
                        }
                    }
                    "dec" -> {
                        // 自减：与 inc 对称，long 用 LSUB，其余用 ISUB。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitInsn(Opcodes.LCONST_1)
                            mv.visitInsn(Opcodes.LSUB)
                        } else {
                            pushInt(mv, 1)
                            mv.visitInsn(Opcodes.ISUB)
                        }
                    }
                    "inv" -> {
                        // 按位取反：JVM 没有 NOT 指令，等价于 x XOR -1（全 1 掩码），
                        // long 用 LC 常量 -1L + LXOR，int 用 ICONST_M1 + IXOR。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        val arg0 = expr.getValueArgument(0)!!
                        val argDesc0 = typeMapper.jvmDescriptorFromIrType(arg0.type)
                        if (argDesc0 == "J") {
                            mv.visitLdcInsn(-1L)
                            mv.visitInsn(Opcodes.LXOR)
                        } else {
                            mv.visitInsn(Opcodes.ICONST_M1)
                            mv.visitInsn(Opcodes.IXOR)
                        }
                    }
                    "unaryPlus" -> {
                        // 一元正号是纯 no-op：只求值并保留在栈上，不生成任何指令。
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        // No-op: unary plus just returns the value
                    }
                    "string_plus" -> {
                        emitStringConcatCall(mv, expr, ctx, owner)
                    }
                    "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>" -> {
                        // 二元算术/位/移位。先把两侧拓宽到同一「更宽」描述符（int/float/long/double
                        // 的选择见 binaryOpWiderDesc），再按操作符选具体 opcode。
                        val arg0 = expr.getValueArgument(0)!!
                        val arg1 = expr.getValueArgument(1)!!
                        // 操作数是平台调用时 IR 类型记为 Unit，按真实返回描述符回填（见 receiverJvmDescriptor），
                        // 否则 `System.nanoTime() - 1L` 会选 ISUB 而产出无法校验的字节码。
                        val desc0 = receiverJvmDescriptor(arg0)
                        val desc1 = receiverJvmDescriptor(arg1)
                        // 左操作数是字符串时整条表达式退化为字符串拼接（+ 的重载解析结果）。
                        if (desc0 == "Ljava/lang/String;" || desc0 == "Ljava/lang/String") {
                            emitStringConcatCall(mv, expr, ctx, owner)
                        } else {
                            // JVM shift instructions take (long, int): the shift
                            // count must stay int even when the value is long.
                            // 中文：JVM 移位指令的操作数类型是 (long, int)——即便被移位值
                            // 是 long，移位位数也必须是 int，所以左侧按自身类型拓宽、
                            // 右侧保持原样，不能一起提升到 wider。
                            val isShift = irName == "<<" || irName == ">>" || irName == ">>>"
                            val wider = if (isShift) desc0 else binaryOpWiderDesc(desc0, desc1)
                            generateIrExpression(mv, arg0, ctx, owner)
                            widenIfNeeded(mv, desc0, wider)
                            generateIrExpression(mv, arg1, ctx, owner)
                            if (!isShift) widenIfNeeded(mv, desc1, wider)
                            emitBinaryArithmeticOp(mv, irName, wider)
                        }
                    }
                    "==", "!=", "<", ">", "<=", ">=" -> {
                        // 比较：两侧先拓宽到同一描述符，再交给 emitCompare。引用类型走
                        // Objects.equals（值相等语义），基本类型走 xCMP + 条件跳转。
                        val arg0 = expr.getValueArgument(0)!!
                        val arg1 = expr.getValueArgument(1)!!
                        // 操作数是平台调用时 IR 类型记为 Unit，按真实返回描述符回填（见 receiverJvmDescriptor），
                        // 否则 `System.nanoTime() - 1L` 会选 ISUB 而产出无法校验的字节码。
                        val desc0 = receiverJvmDescriptor(arg0)
                        val desc1 = receiverJvmDescriptor(arg1)
                        val wider = binaryOpWiderDesc(desc0, desc1)
                        generateIrExpression(mv, arg0, ctx, owner)
                        widenIfNeeded(mv, desc0, wider)
                        generateIrExpression(mv, arg1, ctx, owner)
                        widenIfNeeded(mv, desc1, wider)
                        emitCompare(mv, irName, wider)
                    }
                    "&&" -> {
                        // 短路与：第一个为假立即得 0（不再求值右侧，副作用也被跳过）；
                        // 否则看第二个，都为真得 1。用跳转而非位运算以保持短路语义。
                        val falseLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(falseLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitLabel(endLabel)
                    }
                    "||" -> {
                        // 短路或：第一个为真立即得 1（右侧不求值）；否则看第二个。
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNE, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    else -> {
                        // 非语义化名字的调用，按「谁调谁」再分三类：
                        //   1) ctor_delegate: 构造器委托（this(...)/super(...)）；
                        //   2) v: 成员虚调用（含接口/类两种派发）；
                        //   3) 其它 = 静态函数调用（含跨文件）。
                        if (irName.startsWith("ctor_delegate:")) {
                            // 形如 "ctor_delegate:super:<Owner>" 或 "ctor_delegate:<...>"。
                            // super: 前缀显式给出目标类；否则是 this(...) 委托，目标就是本类。
                            val remainder = irName.substring("ctor_delegate:".length)
                            val targetInternal = if (remainder.startsWith("super:")) {
                                remainder.substring("super:".length)
                            } else {
                                owner
                            }
                            // Emit ALOAD 0 (this) first
                            // 构造器委托是实例调用，接收者固定为槽 0 的 this。
                            mv.visitVarInsn(Opcodes.ALOAD, 0)
                            // Build descriptor from arg types (skip first arg which is `this`)
                            // 第 0 个实参是 this（translator 约定），真正参数从 1 开始。
                            val paramSb = StringBuilder()
                            for (i in 1 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                paramSb.append(typeMapper.jvmDescriptorFromIrType(arg.type))
                            }
                            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternal, "<init>", "($paramSb)V", false)
                            // Leave a dummy null on stack so POP (from IrExpression statement handler) works
                            // 中文：<init> 返回 void，但语句生成器会 POP，补 null 占位。
                            mv.visitInsn(Opcodes.ACONST_NULL)
                        } else if (irName.startsWith("v:") || irName.startsWith("c:")) {
                            // 成员调用：真实方法名是去掉 "v:"/"c:" 前缀后的部分。第 0 个实参
                            // 是接收者，其余是参数。`c:` 是集合调用的显式协议：语言层集合成员
                            // 必须使用 JDK 真实签名（get/put/remove/size 等），不能命中下面的
                            // 历史容器流畅兜底（它假设 get/set/remove 返回接收者）。
                            val forcedPlatform = irName.startsWith("c:")
                            val realName = irName.substring(2)
                            val receiver = expr.getValueArgument(0)!!
                            // 平台调用链的 IR 类型可能缺失（Unit/`V`）：先尝试从内层
                            // 平台调用恢复真实返回描述符，再据此得到 receiver owner。
                            val receiverDesc = receiverJvmDescriptor(receiver)
                            val rawReceiverInternal = when {
                                receiverDesc.startsWith("L") ->
                                    receiverDesc.removePrefix("L").removeSuffix(";")
                                receiverDesc.startsWith("[") -> receiverDesc
                                else -> typeMapper.jvmInternalNameFromIrType(receiver.type)
                            }
                            val receiverInternal = if (rawReceiverInternal.contains("/")) rawReceiverInternal else "$currentPkg/$rawReceiverInternal"
                            // Determine if receiver is an interface
                            // 接口方法必须用 INVOKEINTERFACE（且参数 isInterface=true，
                            // 末尾 4 字节要求 count 参数），用错会导致 IncompatibleClassChangeError。
                            // 只按完整内部名判定：translator 现在把接口声明登记为「包 + 名」。
                            // 若再回退到简单名，跨包「同名 interface + 同名 class」会把 class
                            // 误判成接口，发出 INVOKEINTERFACE 并在链接期报 IncompatibleClassChangeError。
                            val isInterfaceTarget = interfaceDeclNames.contains(rawReceiverInternal)
                            // 声明擦除签名：泛型接口方法（如 Repo.save(T)）在 JVM 里是
                            // (Ljava/lang/Object;)V，不能按实参类型（Person）拼描述符。
                            // 键必须带元数（实参个数），否则不同参数个数的重载会互相覆盖，
                            // 调用点可能拿到另一个重载的描述符并触发 NoSuchMethodError。
                            val declaredKey = memberCallKeys[expr.symbol]
                                ?: memberArityKey(realName, expr.valueArgumentsCount - 1)
                            // `c:` 调用不接受声明签名：集合成员的语义签名由 BuiltinCollections
                            // 定义，但 JVM 描述符必须来自 JDK 反射；若误用用户类型的声明签名，
                            // `dict<string,int32>` 的 get/put 会拿到错误的擦除描述符。
                            val declared = if (forcedPlatform) {
                                null
                            } else {
                                memberSignaturesByType[rawReceiverInternal]?.get(declaredKey)
                            }
                            val useDeclared = !forcedPlatform && declared != null &&
                                declared.paramDescriptors.size == expr.valueArgumentsCount - 1
                            // 先把实参表达式与它们的 IR 描述符收集起来：平台签名解析必须
                            // 在发射任何实参字节码之前完成，因为「用哪个描述符」会反过来
                            // 决定每个实参要不要装箱/宽化。
                            val argExpressions = (1 until expr.valueArgumentsCount)
                                .mapNotNull { expr.getValueArgument(it) }
                            // 实参的描述符同样要按「平台感知」方式取：实参本身可能是
                            // 平台调用链（如 `a.add(b.pow(2))`），其 IR 类型是 Unit，
                            // 直接取会得到 `V` 并把重载决议带偏。
                            val argDescriptors = argExpressions.map { receiverJvmDescriptor(it) }
                            // 平台方法真实签名：仅在「没有声明签名」且接收者不是本次编译
                            // 单元声明的类型时尝试。toString 与 isKnownJdkFluentCall 是既有
                            // 契约（RemoveMethodTest 锁死 StringBuilder.append 的流畅形状）；
                            // 语言内建集合已改走 `c:` 前缀强制真实签名，不再命中该兜底。
                            val platformSignature = if (!useDeclared &&
                                (forcedPlatform ||
                                    (realName != "toString" &&
                                        !isKnownJdkFluentCall(receiverInternal, realName) &&
                                        !isCompilationUnitDeclaredType(rawReceiverInternal, receiverInternal)))
                            ) {
                                platformMethodResolver.resolve(receiverInternal, realName, argDescriptors)
                            } else {
                                null
                            }
                            // `c:` 是编译期契约：检查器只放行 BuiltinCollections 声明的成员，
                            // 而这些成员在 JDK 集合接口上必然存在。解析失败说明前后端对集合族
                            // 或类型实参的认知已不一致，继续拼描述符只会产出运行期才暴露的
                            // NoSuchMethodError；这里 fail fast 并给出可定位的调用信息。
                            if (forcedPlatform && platformSignature == null) {
                                error(
                                    "collection call was not resolved by platform resolver: " +
                                        "$receiverInternal.$realName($argDescriptors)"
                                )
                            }
                            // 形参描述符来源：声明签名优先，其次平台真实签名；都没有时
                            // 沿用旧行为（按实参 IR 类型拼描述符）。
                            val targetParamDescriptors = when {
                                useDeclared -> declared!!.paramDescriptors
                                platformSignature != null -> platformSignature.parameterDescriptors
                                else -> null
                            }
                            // Generate receiver
                            generateIrExpression(mv, receiver, ctx, owner)
                            // Generate remaining args and build descriptor
                            val paramSb = StringBuilder()
                            for ((i, arg) in argExpressions.withIndex()) {
                                generateIrExpression(mv, arg, ctx, owner)
                                val argDesc = argDescriptors[i]
                                if (targetParamDescriptors != null) {
                                    val targetDesc = targetParamDescriptors[i]
                                    // 实参按目标形参类型转换：基本类型装箱（int → Object），
                                    // 具体实现 → 接口/父类时补 CHECKCAST（C13）。这既让栈上
                                    // 类型与调用描述符一致，也把「传参宽化」显式落在字节码里。
                                    emitCallArgumentConversion(mv, argDesc, targetDesc)
                                    paramSb.append(targetDesc)
                                } else {
                                    paramSb.append(argDesc)
                                }
                            }
                            // 返回描述符：优先用声明的擦除签名；其次平台真实签名；否则只对
                            // **已知 JDK 接收者**保留流畅 API 特例（StringBuilder.append* 等），
                            // 其余按 IR 调用点类型擦除。绝不能按方法名全局特判：用户类型的
                            // remove/get/set 会因此拿到接收者描述符（JPA #7 的 VerifyError 根因）。
                            val returnDesc = when {
                                useDeclared -> declared!!.returnDescriptor
                                platformSignature != null -> platformSignature.returnDescriptor
                                realName == "toString" -> "Ljava/lang/String;"
                                isKnownJdkFluentCall(receiverInternal, realName) -> receiverDesc
                                else -> typeMapper.jvmReturnDescriptorFromIrType(expr.type)
                            }
                            // 接口方法必须用 INVOKEINTERFACE：用户接口由 interfaceDeclNames
                            // 判定，平台接口（如 java.util.List）由解析器回报的声明类判定。
                            val invokeInterface = isInterfaceTarget ||
                                platformSignature?.declaringClassIsInterface == true
                            if (invokeInterface) {
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, receiverInternal, realName, "($paramSb)$returnDesc", true)
                            } else {
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, receiverInternal, realName, "($paramSb)$returnDesc", false)
                            }
                            // 真实返回（声明擦除或平台签名）与调用点静态类型不一致时，
                            // 在调用点补 CHECKCAST / 拆箱-装箱，使栈上值的类型与 IR 视角一致。
                            if (useDeclared || platformSignature != null) {
                                emitCallReturnAdaptation(
                                    mv,
                                    returnDesc,
                                    typeMapper.jvmDescriptorFromIrType(expr.type)
                                )
                            }
                            // Void member calls (e.g. generated setters) leave no value on
                            // the stack, but the statement emitter always expects one to POP.
                            // Mirror the print/println contract by leaving a dummy null.
                            // 中文：void 成员调用（如生成的 setter）不留值，补 null 维持约定。
                            if (returnDesc == "V") {
                                mv.visitInsn(Opcodes.ACONST_NULL)
                            }
                        } else {
                            // 静态函数调用（含跨文件顶层函数与用户声明的静态方法）。
                            val func = try { expr.symbol.owner } catch (e: Exception) { null }
                            // Generate arguments with boxing
                            // 参数按 IR 类型求值后，若与目标形参描述符不一致则补装箱
                            // （例如调用参数声明为 Object 但实参是 int）。
                            for (i in 0 until expr.valueArgumentsCount) {
                                val arg = expr.getValueArgument(i) ?: continue
                                generateIrExpression(mv, arg, ctx, owner)
                                if (func != null && i < func.valueParameters.size) {
                                    val argDesc = typeMapper.jvmDescriptorFromIrType(arg.type)
                                    val paramDesc = typeMapper.jvmDescriptorFromIrType(func.valueParameters[i].type)
                                    // 静态调用同样按声明形参转换：装箱 + 接口/父类 CHECKCAST（C13）。
                                    emitBoxingIfNeeded(mv, argDesc, paramDesc)
                                    emitReferenceWidening(mv, argDesc, paramDesc)
                                }
                            }
                            // 描述符用被调函数的声明类型（擦除后），拿不到符号时退回实参类型；
                            // owner 同理退回当前文件（顶层函数同文件调用的情况）。
                            // 合成静态调用（枚举 values/valueOf）没有 IR 声明，owner 取自 translator
                            // 旁路登记的宿主表。
                            val desc = irCallDescriptor(expr)
                            val targetOwner = functionSymbolToOwner[expr.symbol]
                                ?: syntheticStaticCallOwners[expr.symbol]
                                ?: owner
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, irName, desc, false)
                            // Handle return type mismatch between erasure and call-site IR type.
                            // 中文：调用点 IR 类型与被调函数真实返回类型可能不同（泛型擦除：
                            // 被调返回 Object，调用点静态类型是具体引用类型），需补 CHECKCAST；
                            // 或基本类型 ↔ Object 之间补拆箱/装箱，保证栈上值类型与 IR 一致。
                            val callReturnDesc = typeMapper.jvmDescriptorFromIrType(expr.type)
                            val funcReturnDesc = typeMapper.jvmReturnDescriptorFromIrType(
                                try { func?.returnType } catch (e: Exception) { null } ?: expr.type
                            )
                            if (callReturnDesc != funcReturnDesc) {
                                if (callReturnDesc.startsWith("L") && funcReturnDesc.startsWith("L")) {
                                    val internalName = callReturnDesc.substring(1, callReturnDesc.length - 1)
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                                } else {
                                    emitUnboxingIfNeeded(mv, funcReturnDesc, callReturnDesc)
                                    emitBoxingIfNeeded(mv, funcReturnDesc, callReturnDesc)
                                }
                            }
                            // A void static call leaves no value on the stack, but
                            // the statement emitter always expects one to POP. Mirror
                            // the member-call / println contract with a dummy null
                            // (`invokestatic ...)V` followed by an unconditional POP
                            // otherwise underflows the operand stack).
                            // 中文：void 静态调用同样补 null，否则语句位置的 POP 会栈下溢。
                            if (funcReturnDesc == "V") {
                                mv.visitInsn(Opcodes.ACONST_NULL)
                            }
                        }
                    }
                }
            }
            is IrConstructorCall -> {
                // 构造器调用有两种形态，用 translator 给的 constructorTypes 标记区分：
                //   "array:<elemDesc>" = 数组新建；否则 = 对象新建（NEW+DUP+<init>）。
                val ctorType = constructorTypes[expr.symbol]
                if (ctorType != null && ctorType.startsWith("array:")) {
                    val elemDesc = ctorType.substring("array:".length)
                    // 单参数 = 只给长度：NEWARRAY/ANEWARRAY。多维数组由 translator 编码成
                    // 多参数（每维一个长度），走 MULTIANEWARRAY。
                    if (expr.valueArgumentsCount == 1) {
                        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
                        emitNewArray(mv, elemDesc)
                    } else {
                        for (i in 0 until expr.valueArgumentsCount) {
                            val arg = expr.getValueArgument(i) ?: continue
                            generateIrExpression(mv, arg, ctx, owner)
                        }
                        // 描述符形如 "[[I"，维数即参数个数；MULTIANEWARRAY 要求维数 ≤ 255。
                        mv.visitMultiANewArrayInsn("[".repeat(expr.valueArgumentsCount) + elemDesc, expr.valueArgumentsCount)
                    }
                } else {
                    // 对象新建的标准序列：NEW 压入未初始化引用 → DUP 留一份给 <init> 消耗
                    // → 求值各实参 → INVOKESPECIAL <init>。漏掉 DUP 会导致没引用可用。
                    val rawName = typeMapper.jvmInternalNameFromIrType(expr.type)
                    val internalName = if (rawName.contains("/")) rawName else "$currentPkg/$rawName"
                    val desc = irNewDescriptor(expr)
                    mv.visitTypeInsn(Opcodes.NEW, internalName)
                    mv.visitInsn(Opcodes.DUP)
                    for (i in 0 until expr.valueArgumentsCount) {
                        val arg = expr.getValueArgument(i) ?: continue
                        generateIrExpression(mv, arg, ctx, owner)
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", desc, false)
                }
            }
            is IrGetEnumValue -> {
                // 枚举常量读取：JVM 上就是读取枚举类的静态字段（GETSTATIC）。
                // 符号 -> owner/名字 由第一遍扫描 IrEnumEntry 时建立；缺索引时退回当前包，
                // 保持与字段读取一致的「宁可生成可疑引用也不在生成期崩溃」契约。
                val entryName = enumEntrySymbolToName[expr.symbol] ?: "value"
                val entryOwner = enumEntrySymbolToOwner[expr.symbol] ?: currentPkg
                mv.visitFieldInsn(Opcodes.GETSTATIC, entryOwner, entryName, "L$entryOwner;")
            }
            is IrGetField -> {
                // 字段读取：先看是否为接口常量（static final），走 GETSTATIC；
                // 否则求接收者后 GETFIELD。符号/类型缺失一律 fail fast（禁止静默兜底）。
                if (expr.symbol in staticFieldSymbols) {
                    val fieldName = requireFieldName(expr.symbol)
                    val fieldOwner = resolveFieldOwner(expr.symbol)
                    val fieldDesc = resolveFieldDescriptor(expr.symbol, expr.type)
                    mv.visitFieldInsn(Opcodes.GETSTATIC, fieldOwner, fieldName, fieldDesc)
                    return
                }
                val receiver = expr.receiver
                if (receiver != null) generateIrExpression(mv, receiver, ctx, owner)
                // Handle array.length: emit ARRAYLENGTH instead of GETFIELD
                // 中文：`arr.length` 在 JVM 里是 ARRAYLENGTH 指令，不是字段访问——IR 把它
                // 表达成对数组的字段读取，这里必须改写指令，否则会写出不存在的 length 字段。
                // 注意：必须在解析字段名之前返回，因为数组没有真实的字段符号（假符号无名字）。
                if (receiver != null && isArrayType(receiver.type)) {
                    mv.visitInsn(Opcodes.ARRAYLENGTH)
                    return
                }
                val fieldName = requireFieldName(expr.symbol)
                // 读也以声明描述符为准：compound assignment 等路径可能把 IrGetField 的
                // type 填成值类型，若直接使用会与声明不一致。
                val fieldDesc = resolveFieldDescriptor(expr.symbol, expr.type)
                val fieldOwner = resolveFieldOwner(expr.symbol)
                mv.visitFieldInsn(Opcodes.GETFIELD, fieldOwner, fieldName, fieldDesc)
            }
            is IrSetField -> {
                // 字段写：静态走 PUTSTATIC（无接收者）；实例先压接收者再压值，最后 PUTFIELD。
                // 描述符必须取字段**声明处**的类型：translator 把 IrSetField.type 填成「值」
                // 的类型，`this.slot = null` 时会得到 Object，与声明 Lp/C; 不一致
                // （运行期 NoSuchFieldError）。值本身再按声明类型做 widen/box/unbox/checkcast。
                if (expr.symbol in staticFieldSymbols) {
                    val fieldName = requireFieldName(expr.symbol)
                    val fieldDesc = resolveFieldDescriptor(expr.symbol, expr.value.type)
                    generateIrExpression(mv, expr.value, ctx, owner)
                    emitStoreConversion(
                        mv,
                        typeMapper.jvmDescriptorFromIrType(expr.value.type),
                        fieldDesc
                    )
                    val fieldOwner = resolveFieldOwner(expr.symbol)
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, fieldOwner, fieldName, fieldDesc)
                    return
                }
                val receiver = expr.receiver
                if (receiver != null) generateIrExpression(mv, receiver, ctx, owner)
                val fieldName = requireFieldName(expr.symbol)
                val fieldDesc = resolveFieldDescriptor(expr.symbol, expr.value.type)
                generateIrExpression(mv, expr.value, ctx, owner)
                emitStoreConversion(
                    mv,
                    typeMapper.jvmDescriptorFromIrType(expr.value.type),
                    fieldDesc
                )
                val fieldOwner = resolveFieldOwner(expr.symbol)
                mv.visitFieldInsn(Opcodes.PUTFIELD, fieldOwner, fieldName, fieldDesc)
            }
            is IrBlock -> {
                // 表达式位置的块：前 n-1 条按语句生成（丢弃值），最后一条决定整块的
                // 值——表达式则保留在栈上，否则按语句生成（此时块本身无值）。
                val stmts = expr.statements
                for (i in 0 until stmts.size - 1) {
                    generateIrStatement(mv, stmts[i], ctx, owner)
                }
                if (stmts.isNotEmpty()) {
                    val last = stmts.last()
                    if (last is IrExpression) {
                        generateIrExpression(mv, last, ctx, owner)
                    } else {
                        generateIrStatement(mv, last, ctx, owner)
                    }
                }
            }
            is IrWhen -> {
                // 表达式位置的 when：每个非 else 分支命中后把结果值留在栈上并 GOTO end，
                // 未命中则落到下一个分支判定点（branchEnd）；else 分支不跳转，直接产出值。
                // 与语句位置版本的区别：这里不 POP 结果，也不允许分支体丢值。
                val endLabel = Label()
                val nonElseBranches = expr.branches.filter { !isAlwaysTrue(it.condition) }
                val elseBranch = expr.branches.firstOrNull { isAlwaysTrue(it.condition) }
                for (branch in nonElseBranches) {
                    val branchEnd = Label()
                    generateIrExpression(mv, branch.condition, ctx, owner)
                    mv.visitJumpInsn(Opcodes.IFEQ, branchEnd)
                    val result = branch.result
                    if (result is IrBlock) {
                        val stmts = result.statements
                        for (i in 0 until stmts.size - 1) {
                            generateIrStatement(mv, stmts[i], ctx, owner)
                        }
                        if (stmts.isNotEmpty()) {
                            val last = stmts.last()
                            if (last is IrExpression) {
                                generateIrExpression(mv, last, ctx, owner)
                            } else {
                                generateIrStatement(mv, last, ctx, owner)
                            }
                        }
                    } else {
                        generateIrExpression(mv, result, ctx, owner)
                    }
                    mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                    mv.visitLabel(branchEnd)
                }
                if (elseBranch != null) {
                    val result = elseBranch.result
                    if (result is IrBlock) {
                        val stmts = result.statements
                        for (i in 0 until stmts.size - 1) {
                            generateIrStatement(mv, stmts[i], ctx, owner)
                        }
                        if (stmts.isNotEmpty()) {
                            val last = stmts.last()
                            if (last is IrExpression) {
                                generateIrExpression(mv, last, ctx, owner)
                            } else {
                                generateIrStatement(mv, last, ctx, owner)
                            }
                        }
                    } else {
                        generateIrExpression(mv, result, ctx, owner)
                    }
                }
                mv.visitLabel(endLabel)
            }
            is IrFunctionExpression -> {
                // 已废弃路径：lambda 现在由 translator 翻译成匿名 IrClass，不再用
                // IrFunctionExpression。这里只做兜底（压 null），保证不崩且栈平衡。
                emitLambda(mv, expr, ctx, owner)
            }
            is IrTypeOperatorCall -> {
                // 类型算子：非空断言、instanceof、cast。其它算子（IMPLICIT_CAST 等）
                // 一律退化为「原样求值」——它们的语义在 JVM 层没有指令，只影响静态类型。
                when (expr.operator) {
                    IrTypeOperator.IMPLICIT_NOTNULL -> {
                        // 非空断言：JVM 无专用指令，这里生成「非 null → 1，否则 0」的布尔值。
                        // 注意：这不是抛异常的断言，只是把非空性表达成一个布尔结果。
                        val trueLabel = Label()
                        val endLabel = Label()
                        generateIrExpression(mv, expr.argument, ctx, owner)
                        mv.visitJumpInsn(Opcodes.IFNONNULL, trueLabel)
                        mv.visitInsn(Opcodes.ICONST_0)
                        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                        mv.visitLabel(trueLabel)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitLabel(endLabel)
                    }
                    IrTypeOperator.INSTANCEOF -> {
                        // x is T：压值后直接 INSTANCEOF T，结果天然是 0/1。
                        generateIrExpression(mv, expr.argument, ctx, owner)
                        val internalName = typeMapper.jvmInternalNameFromIrType(expr.typeOperand)
                        mv.visitTypeInsn(Opcodes.INSTANCEOF, internalName)
                    }
                    IrTypeOperator.CAST -> {
                        // 类型转换矩阵（源类型 × 目标类型）：
                        //   目标引用 + 源基本类型 → 先装箱再 CHECKCAST（验证器要求栈上是引用）；
                        //                            目标是 Object/Serializable 时无需 checkcast；
                        //   目标引用 + 源引用     → 直接 CHECKCAST；
                        //   目标基本 + 源基本     → 数值转换（int-like 之间是 no-op）；
                        //   目标基本 + 源引用     → CHECKCAST 到包装类再调 xxxValue 拆箱。
                        val argument = expr.argument
                        generateIrExpression(mv, argument, ctx, owner)
                        val srcDesc = typeMapper.jvmDescriptorFromIrType(argument.type)
                        val dstDesc = typeMapper.jvmDescriptorFromIrType(expr.typeOperand)
                        if (dstDesc.startsWith("L") || dstDesc.startsWith("[")) {
                            if (isPrimitiveDesc(srcDesc)) {
                                // Primitive -> reference: box first so the
                                // verifier sees a reference value.
                                emitBoxingIfNeeded(mv, srcDesc, dstDesc)
                                if (dstDesc != "Ljava/lang/Object;" && dstDesc != "Ljava/io/Serializable;") {
                                    val internalName = typeMapper.jvmInternalNameFromIrType(expr.typeOperand)
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                                }
                            } else {
                                val internalName = typeMapper.jvmInternalNameFromIrType(expr.typeOperand)
                                mv.visitTypeInsn(Opcodes.CHECKCAST, internalName)
                            }
                        } else if (isPrimitiveDesc(srcDesc)) {
                            emitNumericConversion(mv, srcDesc, dstDesc)
                        } else {
                            // Reference -> primitive: checkcast to the box
                            // type, then unbox.
                            // 中文：引用转基本类型——先 checkcast 到包装类，再调 xxxValue。
                            emitUnboxingFromRef(mv, dstDesc)
                        }
                    }
                    else -> generateIrExpression(mv, expr.argument, ctx, owner)
                }
            }
            else -> mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    /**
     * 做什么：判断一个 IR 表达式是否只是 translator 用来占位的「无值表达式」。
     *
     * 什么时候用：[IrReturn] 生成时决定要不要压返回值，以及 [generateFinallyBody]
     *   跳过空 finally 占位。
     * 前置条件：expr 已由 translator 构造完成，[TypeMapper] 能还原其类型。
     * 失败模式：不抛异常；类型无法还原时按「有值」处理（保守地压栈，宁可让验证器
     *   报错也不静默吞掉一个真实值）。
     * 为什么这样设计：translator 复用 `IrConst(Null)` 表达两种完全不同的语义——
     *   1) `typeMapper.unitExpr()`：类型是 `kotlin.Unit`（JVM 描述符 `V`），表示
     *      「这里没有值」（裸 `return`、空 finally、无返回值的 when 分支）；
     *   2) `typeMapper.nullConst()`：类型是 `kotlin.Any?`（JVM 描述符
     *      `Ljava/lang/Object;`），表示源码里的 `null` 字面量，**必须**在栈上压
     *      `ACONST_NULL`。
     *   早期实现只看 `kind == Null`，把第 2 种也当成无值，导致 `return null` 只发
     *   `ARETURN` 而没有压 null，触发 `VerifyError: Operand stack underflow`。
     *   因此这里以「JVM 返回描述符是否为 V」作为唯一判据：只有 Unit/void 才是无值，
     *   可空引用（含 `any`）一律有值。判据收敛到本函数，避免各调用点各自判断。
     */
    private fun isIrVoidExpr(expr: IrExpression): Boolean {
        return expr is IrConstImpl &&
            expr.kind == IrConstKind.Null &&
            typeMapper.jvmReturnDescriptorFromIrType(expr.type) == "V"
    }

    /**
     * 做什么：判断「没有命中声明签名」的成员调用是否可以走 JDK 流畅 API 的
     *   「返回接收者自身」兜底。
     *
     * 什么时候用：生成 `v:<name>` 成员调用、且 [memberSignaturesByType] 未给出
     *   该接收者上的声明擦除签名时（`useDeclared == false`）。
     * 前置条件：receiverInternal 是 JVM 内部名（如 `java/lang/StringBuilder`），
     *   methodName 是去掉 `v:` 前缀后的真实方法名。
     * 失败模式：纯判定，不抛异常。白名单外的类型/方法一律返回 false，调用方回退到
     *   「按 IR 调用点类型推导返回描述符」，不会再被方法名误伤。
     * 为什么这样设计：旧实现按方法名（append 前缀、get、set、remove）**全局**特判，任何
     *   用户类型只要方法叫 `remove` 就会把返回值当成接收者（JPA #7：`remove(...)`
     *   的返回描述符变成 `LStore;`，`ifeq` 收到引用而 VerifyError）。JDK 流畅 API
     *   的知识属于「外部类型事实」，不应污染用户类型的调用生成，因此把它收敛到
     *   本白名单（隔离的变化点）：这里只登记**确实返回 this** 或**旧行为必须保留**
     *   的 JDK 接收者，用户声明签名永远优先。
     *
     * 边界：`java.util` 容器的 `get/set/remove` 在 JDK 里并不返回接收者；当前语言
     *   尚无 JDK 容器声明签名层，保留它们是为了不改变旧行为（容器 API 的完整兼容
     *   不在本任务范围）。StringBuilder/StringBuffer 的 `append*` 是真正的链式 API。
     */
    private fun isKnownJdkFluentCall(receiverInternal: String, methodName: String): Boolean {
        return when (receiverInternal) {
            // 真正的流畅 API：append* 返回 this，链式调用依赖该描述符。
            "java/lang/StringBuilder", "java/lang/StringBuffer" ->
                methodName.startsWith("append")
            // 旧兜底覆盖过的 JDK 容器形状：保留原行为，避免容器调用回归。
            // 只按**接收者类型**放行，用户自定义类型不再命中。
            "java/util/Collection",
            "java/util/List", "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector",
            "java/util/Set", "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet",
            "java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap" ->
                methodName == "get" || methodName == "set" || methodName == "remove"
            else -> false
        }
    }

    /**
     * 做什么：判断接收者是不是**本次编译单元声明**的类型（用户类/接口/枚举）。
     *
     * 什么时候用：决定是否允许 [PlatformMethodResolver] 介入之前。用户类型的成员方法
     *   必须继续走 translator 预登记的声明擦除签名，平台解析器不能越权改写它们。
     * 前置条件：rawReceiverInternal 是 TypeMapper 反查出的名字（可能是简单名），
     *   receiverInternal 是后端补齐包名后的内部名；两张表由 generate() 从
     *   TranslationResult 装入。
     * 失败模式：纯查表，不抛异常。两张表都查不到就返回 false（视为平台类型候选），
     *   真正的「加载不到」由解析器自己收敛为 null。
     * 为什么这样设计：任务书把「本次编译单元声明」定义为 memberSignaturesByType 与
     *   接口表的命中；把判定集中在本函数，调用点不必重复理解两张表的键格式差异
     *   （简单名 vs 内部名），也让「是否允许平台解析」只有一个变化点。
     */
    private fun isCompilationUnitDeclaredType(rawReceiverInternal: String, receiverInternal: String): Boolean {
        return memberSignaturesByType.containsKey(rawReceiverInternal) ||
            memberSignaturesByType.containsKey(receiverInternal) ||
            interfaceDeclNames.contains(rawReceiverInternal) ||
            interfaceDeclNames.contains(receiverInternal)
    }

    /**
     * 做什么：发射一次全限定平台静态调用，并按真实签名适配实参和返回值。
     * 什么时候用：JvmIrBackend 的分派层发现调用点符号登记在 platformStaticCallOwners。
     * 前置条件：检查器已确认候选；实参表达式均已翻译，owner 是 JVM 内部名。
     * 失败模式：解析器返回 null 说明前端/后端协议被破坏，抛出的异常由编译入口归为 Internal；
     *   正常缺类/缺方法必须在检查器阶段先被拦截，不能到达这里。
     * 为什么集中：INVOKESTATIC 的 owner/name/descriptor/itf 与参数转换必须成组生成；
     *   返回值沿用 emitCallReturnAdaptation，避免复制平台实例调用的宽类型规则。
     */
    private fun emitPlatformStaticCall(
        mv: MethodVisitor,
        expr: IrCall,
        ownerInternalName: String,
        methodName: String,
        ctx: MethodContext,
        currentOwner: String
    ) {
        val argExpressions = (0 until expr.valueArgumentsCount).mapNotNull { expr.getValueArgument(it) }
        val argDescriptors = argExpressions.map { receiverJvmDescriptor(it) }
        val signature = platformMethodResolver.resolveStatic(
            ownerInternalName,
            methodName,
            argDescriptors
        ) ?: error("platform static call was not resolved by checker: $ownerInternalName.$methodName($argDescriptors)")
        for ((i, arg) in argExpressions.withIndex()) {
            generateIrExpression(mv, arg, ctx, currentOwner)
            emitCallArgumentConversion(mv, argDescriptors[i], signature.parameterDescriptors[i])
        }
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ownerInternalName,
            methodName,
            signature.descriptor,
            signature.declaringClassIsInterface
        )
        emitCallReturnAdaptation(
            mv,
            signature.returnDescriptor,
            typeMapper.jvmDescriptorFromIrType(expr.type)
        )
        if (signature.returnDescriptor == "V") {
            mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    /**
     * 做什么：把平台调用实参从当前栈描述符转换到目标形参描述符。
     * 什么时候用：[emitPlatformStaticCall] 以及后续平台实例调用需要真实形参转换时。
     * 前置条件：src/dst 是合法 JVM 描述符，栈顶恰有一个 src 值。
     * 失败模式：不支持的组合不额外发指令，最终由验证器暴露；不静默丢弃实参。
     * 为什么需要：int 实参可匹配 long 形参（BigInteger.valueOf(5)），但 JVM 不会自动
     *   把 int 压栈值变成 long；必须显式 I2L/I2F/I2D，之后的装箱/引用检查才有正确输入。
     */
    private fun emitCallArgumentConversion(mv: MethodVisitor, src: String, dst: String) {
        if (src == dst) return
        if (isPrimitiveDesc(src) && isPrimitiveDesc(dst)) {
            // byte/short/char 在 JVM 栈上就是 int；先归一到 I 再执行 JVM 数值宽化。
            val normalizedSrc = if (src in "BSC") "I" else src
            val normalizedDst = if (dst in "BSC") "I" else dst
            widenIfNeeded(mv, normalizedSrc, normalizedDst)
            return
        }
        emitBoxingIfNeeded(mv, src, dst)
        emitReferenceWidening(mv, src, dst)
    }

    /**
     * 做什么：推断一个 IR 表达式的 JVM 描述符；当 IR 调用点类型缺失（`V`）且表达式
     *   本身是一次平台成员调用时，递归地用平台解析器找回真实返回描述符。
     *
     * 什么时候用：生成 `v:<name>` 调用时取 receiver 的描述符/内部名；也用于判断
     *   平台方法的实参描述符（实参本身可能又是一层平台调用）。
     * 前置条件：expression 是已由 translator 翻译好的 IR 表达式；resolver 已就绪；
     *   [callNames] 已由 translator 填好（`v:` 前缀表示实例成员调用）。
     * 失败模式：任何一步无法确定都原样返回 `V`（调用方随后会得到 `void` 内部名并
     *   退回旧行为），不抛异常。
     * 为什么需要：translator 对没有声明签名的平台调用把 IR 类型记为 Unit（描述符
     *   `V`）。`new BigInteger("2").pow(10).subtract(...)` 里 subtract 的接收者是
     *   pow 调用，而 pow 的 IR 类型同样是 `V`；若只看接收者自身的 IR 类型，owner
     *   会退化成不存在的 `test/void`，直到运行期才以 NoClassDefFoundError 暴露
     *   （C27 第二步的链式表现）。
     * 为什么递归：链的深度由 IR 表达式树天然限定（每次递归都下降到 receiver/
     *   argument 子树，树无环），无需深度上限；只回填一层会在三层及以上的链上
     *   再次退化为 `void`。这里复用同一个解析器，把内层平台调用的真实返回描述符
     *   当作外层的静态类型，不改检查器/translator 的类型推断（任务书边界）。
     * 边界：只在**后端描述符选择**范围内做「类型信息缺失时的事实回填」，不把
     *   链式调用改写成顶层函数；用户声明类型的成员签名由 translator 预登记，
     *   这里遇到本次编译单元声明的类型时保持原行为（返回 `V`），不越权猜测。
     */
    private fun receiverJvmDescriptor(receiver: IrExpression): String {
        val direct = typeMapper.jvmDescriptorFromIrType(receiver.type)
        if (receiver !is IrCall) return direct
        val innerCallName = callNames[receiver.symbol] ?: return direct
        // 平台静态调用的 IR 类型同样在调用点记为 Unit；真实返回类型由同一解析器回填。
        // 这是「BigInteger.valueOf(5).pow(3)」能把对象返回继续作为实例接收者的关键。
        val staticOwner = platformStaticCallOwners[receiver.symbol]
        if (staticOwner != null) {
            val args = (0 until receiver.valueArgumentsCount)
                .mapNotNull { receiver.getValueArgument(it) }
                .map { receiverJvmDescriptor(it) }
            return platformMethodResolver.resolveStatic(staticOwner, innerCallName, args)
                ?.returnDescriptor
                ?: direct
        }
        // 运算表达式的 IR 类型可能仍是 Unit（左操作数是未知平台调用），但栈上结果类型
        // 可由操作数真实描述符恢复。例如 `parseInt("7") * 6` 作为静态方法实参时必须是 I，
        // 不能把 V 送给重载解析器。
        if (innerCallName == "string_plus") return "Ljava/lang/String;"
        if (innerCallName in setOf("==", "!=", "<", ">", "<=", ">=", "!")) return "Z"
        if (innerCallName in setOf("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>")) {
            val left = receiver.getValueArgument(0)?.let { receiverJvmDescriptor(it) } ?: return direct
            if (innerCallName == "<<") return left
            if (innerCallName in setOf(">>", ">>>")) return left
            val right = receiver.getValueArgument(1)?.let { receiverJvmDescriptor(it) } ?: return left
            return binaryOpWiderDesc(left, right)
        }
        if (innerCallName in setOf("negate", "unaryPlus", "inc", "dec", "inv")) {
            return receiver.getValueArgument(0)?.let { receiverJvmDescriptor(it) } ?: direct
        }
        // 运算表达式的调用点 IR 类型既可能是 Unit，也可能因操作数 any 被推成 Object；
        // 只要语义仍是数值运算，就按操作数真实描述符恢复。必须在下面的 Unit 提前返回
        // 之前处理，否则 parseInt(...) * 6 会错误选择 Object 重载。
        if (direct != "V") return direct
        // 只处理 translator 标记为实例成员调用的内层表达式；其它形态保持旧行为。
        if (!innerCallName.startsWith("v:")) return direct
        val innerRealName = innerCallName.substring(2)
        val innerReceiver = receiver.getValueArgument(0) ?: return direct
        // 关键点：innerReceiver 自己的 IR 类型也可能是 `V`（它就是更内一层的平台
        // 调用），所以 owner 必须递归地按「平台感知描述符」取，而不能只看 IR 类型。
        val innerReceiverDesc = receiverJvmDescriptor(innerReceiver)
        val innerRawOwner = when {
            innerReceiverDesc.startsWith("L") ->
                innerReceiverDesc.removePrefix("L").removeSuffix(";")
            innerReceiverDesc.startsWith("[") -> innerReceiverDesc
            else -> typeMapper.jvmInternalNameFromIrType(innerReceiver.type)
        }
        val innerOwner = if (innerRawOwner.contains("/")) innerRawOwner else "$currentPkg/$innerRawOwner"
        // 用户类型不在平台解析范围内：它们的调用点类型应由声明签名给出，缺失时
        // 不在这里猜测，继续走旧路径。
        if (isCompilationUnitDeclaredType(innerRawOwner, innerOwner)) return direct
        val innerArgs = (1 until receiver.valueArgumentsCount)
            .mapNotNull { receiver.getValueArgument(it) }
        // 实参同样可能是更内一层的平台调用（如 `a.add(b.pow(2)).subtract(c)`），
        // 用同一个函数递归取描述符，避免把 `V` 当作形参类型去解析签名。
        val innerArgDescriptors = innerArgs.map { receiverJvmDescriptor(it) }
        return platformMethodResolver.resolve(innerOwner, innerRealName, innerArgDescriptors)
            ?.returnDescriptor
            ?: direct
    }

    /**
     * 做什么：把栈顶的「方法真实返回类型」适配到调用点 IR 期望的类型。
     *
     * 什么时候用：成员调用使用「声明擦除签名」或「平台真实签名」生成之后。这两条路径
     *   都可能让真实返回描述符与 IR 调用点类型不同（泛型擦除成 Object、平台方法返回
     *   int 而 IR 只知道 Unit/any 等）。
     * 前置条件：栈顶恰好有一个 [actualReturnDesc] 类型的值；[callSiteReturnDesc] 是
     *   IR 调用点类型的 JVM 描述符（可能是 `V`，表示类型信息缺失）。
     * 失败模式：不支持/不改变栈型的组合静默返回；若上游类型信息与真实描述符冲突到
     *   无法用 CHECKCAST/装箱消解，错误留给 JVM 验证器，本函数不猜。
     * 为什么单独成函数：声明签名与平台签名是两条不同来源，但「返回值对齐」是同一个
     *   变化点；抽出来可避免复制 CHECKCAST/拆箱-装箱逻辑，也保证两条路径行为一致。
     */
    private fun emitCallReturnAdaptation(
        mv: MethodVisitor,
        actualReturnDesc: String,
        callSiteReturnDesc: String
    ) {
        // void 没有栈上值可适配；描述符一致时也无需任何指令。
        if (actualReturnDesc == "V" || callSiteReturnDesc == actualReturnDesc) return
        if (callSiteReturnDesc == "V") {
            // 调用点类型缺失（Unit）时保留真实栈型。值位置（返回、实参、链式接收者）
            // 需要真实 D/J；丢弃位置由 discardExpressionValue 按同一平台感知描述符选 POP2。
            return
        }
        if (callSiteReturnDesc.startsWith("L") && actualReturnDesc.startsWith("L")) {
            // 引用 → 更具体的引用：补 CHECKCAST，让栈上类型与 IR 视角一致（C13）。
            mv.visitTypeInsn(
                Opcodes.CHECKCAST,
                callSiteReturnDesc.substring(1, callSiteReturnDesc.length - 1)
            )
        } else {
            // 其余组合：Object/Serializable → 基本类型走拆箱；基本类型 → Object 走装箱。
            emitUnboxingIfNeeded(mv, actualReturnDesc, callSiteReturnDesc)
            emitBoxingIfNeeded(mv, actualReturnDesc, callSiteReturnDesc)
        }
    }

    // 方法体顶层是否已有 return 语句：没有则由 generateIrFunction 补一条。
    // 只检查顶层（不递归进 if/loop）——嵌套分支的 return 由各分支自己发射。
    private fun hasIrReturn(body: IrBlockBody): Boolean {
        return body.statements.any { it is IrReturn }
    }

    /**
     * 在 return / break / continue 处，由内到外执行 finallyStack 中下标 ≥ [downTo] 的 finally 体。
     *
     * 做什么：对每个待执行的 finally（下标 k），生成期间把 finallyStack 截到前 k 项——其中的
     *   return / break / continue 只再执行更外层的 finally；生成的代码作为空隙从该 finally 所属区间
     *   及其内层区间的异常表中挖掉，使它自身抛出时交给更外层处理器，而不是重进自己或被内层 catch 截获。
     * 前置条件：调用方已把返回值存入临时槽（return 路径），操作数栈为空。
     */
    private fun emitPendingFinally(mv: MethodVisitor, ctx: MethodContext, owner: String, downTo: Int) {
        val pending = ctx.finallyStack.toList()
        for (k in pending.indices.reversed()) {
            if (k < downTo) break
            val f = pending[k]
            val gapStart = Label()
            val seq = ctx.labelSeq++
            // 保证空隙之前的受保护段非空（见 registerProtectedRange）。
            mv.visitInsn(Opcodes.NOP)
            mv.visitLabel(gapStart)
            ctx.finallyStack.subList(k, ctx.finallyStack.size).clear()
            generateFinallyBody(mv, f.block, ctx, owner)
            ctx.finallyStack.clear()
            ctx.finallyStack.addAll(pending)
            val gapEnd = Label()
            mv.visitLabel(gapEnd)
            for (r in f.rangeIndex until ctx.openRanges.size) {
                val gaps = ctx.openRanges[r].gaps
                gaps.removeAll { it.seq > seq }
                gaps.add(Gap(seq, gapStart, gapEnd))
            }
        }
    }

    /**
     * 登记 [start, end) 去掉 [range] 空隙后的各段异常表项。
     * 各段不会为空（JVM 要求 start_pc < end_pc）：try 体起点与每个空隙之前各有一条 NOP，
     * 每个空隙之后紧跟 return / GOTO 指令。
     */
    private fun registerProtectedRange(
        mv: MethodVisitor, start: Label, end: Label, range: ProtectedRange, handler: Label, type: String?
    ) {
        var segStart = start
        for (gap in range.gaps) {
            mv.visitTryCatchBlock(segStart, gap.start, handler, type)
            segStart = gap.end
        }
        mv.visitTryCatchBlock(segStart, end, handler, type)
    }

    /**
     * 生成一个 finally 体的字节码，并保证生成结束后操作数栈上「不残留值」。
     *
     * 做什么：finally 体在 Jasper 里是一个块（[IrBlock]）；本函数把它的语句逐条生成。
     *   空 finally（translator 用 `IrConst(Null)` 占位，如 `lock` 的占位）视为 no-op，
     *   不生成任何指令——因为该 null 常量语义上是「无值」，若照常压栈会让调用点的
     *   栈平衡被破坏（例如 IrReturn 之后多出一个 null）。
     * 什么时候用：try 正常收尾、catch 正常收尾、异常兜底处理器、以及 return 前补跑 finally。
     * 前置条件：调用方需自行决定 finallyStack 的压栈/弹栈时机（见各调用点）。
     * 失败模式：块内的语句若自身破坏「表达式恰留一个值」的约定，错误会传递到验证器；本函数不兜底。
     * 为什么这样设计：finally 会在多条路径上重复生成，集中到一个函数可保证四条路径的
     *   生成规则完全一致，避免某条路径漏 POP 或漏处理空 finally。
     */
    private fun generateFinallyBody(mv: MethodVisitor, expr: IrExpression, ctx: MethodContext, owner: String) {
        when {
            expr is IrBlock -> {
                for (s in expr.statements) generateIrStatement(mv, s, ctx, owner)
            }
            isIrVoidExpr(expr) -> {
                // 空 finally（Unit/null 占位）：没有副作用，也没有值需要压栈或弹掉。
            }
            else -> {
                generateIrExpression(mv, expr, ctx, owner)
                // finally 表达式只作副作用时，留在栈上的值必须弹掉，保持栈平衡；
                // 弹栈宽度同样按描述符选（宽类型要 POP2，见 C26），V 保持原有的「不弹」行为。
                if (typeMapper.jvmDescriptorFromIrType(expr.type) != "V") {
                    discardExpressionValue(mv, expr)
                }
            }
        }
    }

    // 按返回类型选 return 指令：void→RETURN、int-like→IRETURN、long→LRETURN、
    // float/double→FRETURN/DRETURN、引用/数组→ARETURN。returnType 为 null 视作 void。
    private fun generateIrReturn(mv: MethodVisitor, returnType: IrType?) {
        val d = if (returnType != null) typeMapper.jvmReturnDescriptorFromIrType(returnType) else "V"
        when {
            d == "V" -> mv.visitInsn(Opcodes.RETURN)
            d == "I" || d == "Z" || d == "B" || d == "S" || d == "C" -> mv.visitInsn(Opcodes.IRETURN)
            d == "J" -> mv.visitInsn(Opcodes.LRETURN)
            d == "F" -> mv.visitInsn(Opcodes.FRETURN)
            d == "D" -> mv.visitInsn(Opcodes.DRETURN)
            else -> mv.visitInsn(Opcodes.ARETURN)
        }
    }

    // 方法描述符：形如 (参数描述符...)返回描述符。参数按声明顺序拼接（擦除后），
    // 不使用泛型信息——这正是 JVM 方法签名的定义。
    private fun irMethodDescriptor(func: IrSimpleFunction): String {
        val paramDescs = func.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        val rd = typeMapper.jvmReturnDescriptorFromIrType(func.returnType)
        return "($paramDescs)$rd"
    }

    // 构造器描述符：参数同方法，返回固定 V（<init> 永远返回 void）。
    private fun irConstructorDescriptor(ctor: IrConstructor): String {
        val paramDescs = ctor.valueParameters.joinToString("") {
            typeMapper.jvmDescriptorFromIrType(it.type)
        }
        return "($paramDescs)V"
    }

    private fun irCallDescriptor(call: IrCall): String {
        // Use the function's declared parameter types for the descriptor (erasure),
        // falling back to argument types if the target function is not found.
        // 中文：优先用被调函数的声明参数类型（擦除后）——这才是 JVM 里的真实签名；
        // 符号解析不到（跨模块未登记）时退回实参类型，保证至少能拼出可用的描述符。
        val func = try { call.symbol.owner } catch (e: Exception) { null }
        if (func != null) {
            return irMethodDescriptor(func)
        }
        val paramDescs = (0 until call.valueArgumentsCount).joinToString("") {
            val arg = call.getValueArgument(it)
            if (arg != null) typeMapper.jvmDescriptorFromIrType(arg.type) else "Ljava/lang/Object;"
        }
        val returnDesc = typeMapper.jvmReturnDescriptorFromIrType(call.type)
        return "($paramDescs)$returnDesc"
    }

    // NEW 之后的构造器描述符：只能从实参类型推断（构造器没有可直接取用的描述符表），
    // 返回固定 V。实参缺失时按 Object 兜底。
    private fun irNewDescriptor(ctor: IrConstructorCall): String {
        val paramDescs = (0 until ctor.valueArgumentsCount).joinToString("") {
            val arg = ctor.getValueArgument(it)
            if (arg != null) typeMapper.jvmDescriptorFromIrType(arg.type) else "Ljava/lang/Object;"
        }
        return "($paramDescs)V"
    }

    // 局部变量占用的槽位数：long/double 是宽类型，各占 2 个槽，其余 1 个。
    // 这是所有槽位推进（参数、变量、临时值）的唯一依据，写错会导致变量互相覆盖。
    private fun irTypeSlots(type: IrType): Int {
        val desc = typeMapper.jvmDescriptorFromIrType(type)
        return if (desc == "J" || desc == "D") 2 else 1
    }

    // 按描述符选 load 指令：int-like（含 boolean/byte/short/char，JVM 上都以 int 存）
    // 用 ILOAD，long/float/double 各自 LLOAD/FLOAD/DLOAD，其余（引用/数组）ALOAD。
    private fun loadLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ILOAD, slot)
            "J" -> mv.visitVarInsn(Opcodes.LLOAD, slot)
            "F" -> mv.visitVarInsn(Opcodes.FLOAD, slot)
            "D" -> mv.visitVarInsn(Opcodes.DLOAD, slot)
            else -> mv.visitVarInsn(Opcodes.ALOAD, slot)
        }
    }

    // 与 loadLocal 对称的 store 指令选择。
    private fun storeLocal(mv: MethodVisitor, desc: String, slot: Int) {
        when (desc) {
            "I", "Z", "B", "S", "C" -> mv.visitVarInsn(Opcodes.ISTORE, slot)
            "J" -> mv.visitVarInsn(Opcodes.LSTORE, slot)
            "F" -> mv.visitVarInsn(Opcodes.FSTORE, slot)
            "D" -> mv.visitVarInsn(Opcodes.DSTORE, slot)
            else -> mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
    }

    // 压入 int 常量并按编码宽度选最短指令：-1..5 用 ICONST_*，byte 范围用 BIPUSH，
    // short 范围用 SIPUSH，更大用 LDC（常量池）。纯体积优化，语义等价。
    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    // 数值拓宽：只做 JVM 支持的「向上」转换（I→J/F/D、J→F/D、F→D），不做窄化。
    // 用途是让二元运算两侧类型一致；窄化必须走 emitNumericConversion（cast 语义）。
    private fun widenIfNeeded(mv: MethodVisitor, sourceDesc: String, targetDesc: String) {
        if (sourceDesc == targetDesc) return
        when (sourceDesc) {
            "I" -> when (targetDesc) {
                "J" -> mv.visitInsn(Opcodes.I2L)
                "F" -> mv.visitInsn(Opcodes.I2F)
                "D" -> mv.visitInsn(Opcodes.I2D)
            }
            "J" -> when (targetDesc) {
                "F" -> mv.visitInsn(Opcodes.L2F)
                "D" -> mv.visitInsn(Opcodes.L2D)
            }
            "F" -> when (targetDesc) {
                "D" -> mv.visitInsn(Opcodes.F2D)
            }
        }
    }

    // 基本类型描述符判定：单字符且在 IJFDBSCZ 之内（I=int, J=long, F=float, D=double,
    // B=byte, S=short, C=char, Z=boolean）。注意 'V' 不在其中（void 不是值类型）。
    private fun isPrimitiveDesc(desc: String): Boolean =
        desc.length == 1 && desc[0] in "IJFDBSCZ"

    /**
     * Primitive-to-primitive numeric conversion used by `cast` lowering.
     * Boolean/byte/short/char all live on the JVM stack as ints, so
     * conversions among the int-like descriptors are no-ops.
     * 中文：cast 降低所需的基本类型互转。boolean/byte/short/char 在 JVM 栈上都以
     * int 存在，因此 int-like 之间的转换是空操作（直接 return）；其余按 12 种
     * 组合查表选指令。边界：不支持的组合 opcode 为 -1，静默不发指令。
     */
    private fun emitNumericConversion(mv: MethodVisitor, srcDesc: String, dstDesc: String) {
        if (srcDesc == dstDesc) return
        val intLike = "IZBSC"
        if (srcDesc in intLike && dstDesc in intLike) return
        val opcode = when {
            srcDesc == "I" && dstDesc == "J" -> Opcodes.I2L
            srcDesc == "I" && dstDesc == "F" -> Opcodes.I2F
            srcDesc == "I" && dstDesc == "D" -> Opcodes.I2D
            srcDesc == "J" && dstDesc == "I" -> Opcodes.L2I
            srcDesc == "J" && dstDesc == "F" -> Opcodes.L2F
            srcDesc == "J" && dstDesc == "D" -> Opcodes.L2D
            srcDesc == "F" && dstDesc == "I" -> Opcodes.F2I
            srcDesc == "F" && dstDesc == "J" -> Opcodes.F2L
            srcDesc == "F" && dstDesc == "D" -> Opcodes.F2D
            srcDesc == "D" && dstDesc == "I" -> Opcodes.D2I
            srcDesc == "D" && dstDesc == "J" -> Opcodes.D2L
            srcDesc == "D" && dstDesc == "F" -> Opcodes.D2F
            else -> -1
        }
        if (opcode >= 0) mv.visitInsn(opcode)
    }

    /** Reference -> primitive: checkcast to the box type, then unbox. */
    // 中文：引用 → 基本类型：先 CHECKCAST 到对应包装类（栈上是 Object，验证器需要具体类型），
    // 再 INVOKEVIRTUAL xxxValue 取标量。目标描述符不在表内（非基本类型）时静默返回。
    private fun emitUnboxingFromRef(mv: MethodVisitor, dstDesc: String) {
        val (boxInternal, getter) = when (dstDesc) {
            "I" -> "java/lang/Integer" to "intValue"
            "J" -> "java/lang/Long" to "longValue"
            "F" -> "java/lang/Float" to "floatValue"
            "D" -> "java/lang/Double" to "doubleValue"
            "Z" -> "java/lang/Boolean" to "booleanValue"
            "B" -> "java/lang/Byte" to "byteValue"
            "S" -> "java/lang/Short" to "shortValue"
            "C" -> "java/lang/Character" to "charValue"
            else -> return
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, boxInternal)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxInternal, getter, "()$dstDesc", false)
    }

    // 只在「目标是 Object/Serializable」时装箱：这两个是调用/赋值中最常见的
    // 「擦除到引用」目标；更具体的引用目标（如 List）不在这里处理，由调用点的
    // 描述符与 IR 类型对齐保证。byte/short/char 不在表内（它们以 int 形式装箱）。
    private fun emitBoxingIfNeeded(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (toDesc == "Ljava/lang/Object;" || toDesc == "Ljava/io/Serializable;") {
            when (fromDesc) {
                "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
                "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
                "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
                "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            }
        }
    }

    // 拆箱：用于「返回值从 Object（擦除）回到具体基本类型」的调用点修正。
    // 与 emitBoxingIfNeeded 方向相反；同样只处理 Object/Serializable 这一种来源。
    private fun emitUnboxingIfNeeded(mv: MethodVisitor, fromDesc: String, toDesc: String) {
        if (fromDesc == "Ljava/lang/Object;" || fromDesc == "Ljava/io/Serializable;") {
            when (toDesc) {
                "I" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
                }
                "J" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
                }
                "F" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false)
                }
                "D" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
                }
                "Z" -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                }
            }
        }
    }

    // 二元运算的操作数提升：返回两者中「更宽」的描述符。优先级 I(0) < F/J(1) < D(2)，
    // 同优先级取 desc1（因此 long+float 会提升为 long，是既定的近似策略）。
    // 注意：表里没有的（引用类型）直接返回对方，调用方需自行保证两侧同类型。
    private fun binaryOpWiderDesc(desc1: String, desc2: String): String {
        if (desc1 == desc2) return desc1
        val priority = mapOf("I" to 0, "F" to 1, "J" to 1, "D" to 2)
        val p1 = priority[desc1] ?: return desc1
        val p2 = priority[desc2] ?: return desc2
        return if (p1 >= p2) desc1 else desc2
    }

    // 按「已提升后的描述符 + 操作符」选算术/位/移位指令。前面调用方已把两侧
    // 拓宽到同一 desc，因此这里只需按 desc 在 long/double/float/int 四套指令里选。
    private fun emitBinaryArithmeticOp(mv: MethodVisitor, op: String, desc: String) {
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        when (op) {
            "+" -> mv.visitInsn(if (isLong) Opcodes.LADD else if (isDouble) Opcodes.DADD else if (isFloat) Opcodes.FADD else Opcodes.IADD)
            "-" -> mv.visitInsn(if (isLong) Opcodes.LSUB else if (isDouble) Opcodes.DSUB else if (isFloat) Opcodes.FSUB else Opcodes.ISUB)
            "*" -> mv.visitInsn(if (isLong) Opcodes.LMUL else if (isDouble) Opcodes.DMUL else if (isFloat) Opcodes.FMUL else Opcodes.IMUL)
            "/" -> mv.visitInsn(if (isLong) Opcodes.LDIV else if (isDouble) Opcodes.DDIV else if (isFloat) Opcodes.FDIV else Opcodes.IDIV)
            "%" -> mv.visitInsn(if (isLong) Opcodes.LREM else if (isDouble) Opcodes.DREM else if (isFloat) Opcodes.FREM else Opcodes.IREM)
            "&" -> mv.visitInsn(if (isLong) Opcodes.LAND else Opcodes.IAND)
            "|" -> mv.visitInsn(if (isLong) Opcodes.LOR else Opcodes.IOR)
            "^" -> mv.visitInsn(if (isLong) Opcodes.LXOR else Opcodes.IXOR)
            "<<" -> mv.visitInsn(if (isLong) Opcodes.LSHL else Opcodes.ISHL)
            ">>" -> mv.visitInsn(if (isLong) Opcodes.LSHR else Opcodes.ISHR)
            ">>>" -> mv.visitInsn(if (isLong) Opcodes.LUSHR else Opcodes.IUSHR)
        }
    }

    // 比较生成布尔结果（0/1），栈上两个操作数已被拓宽到同一 desc。
    //   引用/数组：用 Objects.equals 做值相等——因此 <、> 等序比较对引用类型无意义，
    //             这里统一返回 0（源码层对引用类型本就不该出现序比较）。
    //   long/float/double：先 LCMP/DCMPG/FCMPG 压成一个 int 再条件跳转；
    //   int-like：直接 IF_ICMPxx 比较。
    // 三段结构：条件真 → trueLabel(压 1)；否则落 0 → GOTO end。
    private fun emitCompare(mv: MethodVisitor, op: String, desc: String) {
        if (desc.startsWith("L") || desc.startsWith("[")) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false)
            if (op == "==") {
                return
            } else if (op == "!=") {
                // equals 结果取反：1 XOR 结果（0→1, 1→0）。
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IXOR)
                return
            } else {
                mv.visitInsn(Opcodes.ICONST_0)
                return
            }
        }
        val trueLabel = Label()
        val endLabel = Label()
        val isLong = desc == "J"
        val isDouble = desc == "D"
        val isFloat = desc == "F"
        // 宽类型先归一化成 int（-1/0/1）再比较。用 xCMPG（NaN 当最大）使 NaN 比较
        // 结果偏「大」，与浮点比较规则中的 NaN 语义近似。
        if (isLong) {
            mv.visitInsn(Opcodes.LCMP)
        } else if (isDouble || isFloat) {
            mv.visitInsn(if (isDouble) Opcodes.DCMPG else Opcodes.FCMPG)
        }
        if (isLong || isDouble || isFloat) {
            val ifOp = when (op) {
                "==" -> Opcodes.IFEQ; "!=" -> Opcodes.IFNE
                "<" -> Opcodes.IFLT; ">" -> Opcodes.IFGT
                "<=" -> Opcodes.IFLE; ">=" -> Opcodes.IFGE
                else -> Opcodes.IFEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        } else {
            val ifOp = when (op) {
                "==" -> Opcodes.IF_ICMPEQ; "!=" -> Opcodes.IF_ICMPNE
                "<" -> Opcodes.IF_ICMPLT; ">" -> Opcodes.IF_ICMPGT
                "<=" -> Opcodes.IF_ICMPLE; ">=" -> Opcodes.IF_ICMPGE
                else -> Opcodes.IF_ICMPEQ
            }
            mv.visitJumpInsn(ifOp, trueLabel)
        }
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(endLabel)
    }

    // 字符串拼接（"string_plus"）：new StringBuilder → 逐参数 append → toString。
    // 不使用 invokedynamic（StringConcatFactory）以避开 class 版本与 native-image
    // 配置问题；代价是体积略大、性能略低于 JIT 优化后的 makeConcatWithConstants。
    // 每个参数先经 String.valueOf 归一成 String，再走 append(String)。
    private fun emitStringConcatCall(mv: MethodVisitor, expr: IrCall, ctx: MethodContext, owner: String) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (i in 0 until expr.valueArgumentsCount) {
            val arg = expr.getValueArgument(i) ?: continue
            generateIrExpression(mv, arg, ctx, owner)
            val argDesc = typeMapper.jvmDescriptorFromIrType(arg.type)
            when (argDesc) {
                "I", "Z", "B", "S", "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "($argDesc)Ljava/lang/String;", false)
                "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false)
                "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false)
                "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false)
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    // 单值 toString：基本类型走 String.valueOf 的对应重载，引用类型走
    // String.valueOf(Object)（内部对 null 返回 "null"，因此无需显式判空）。
    private fun emitToString(mv: MethodVisitor, expr: IrCall, ctx: MethodContext, owner: String) {
        val arg = expr.getValueArgument(0)!!
        val desc = typeMapper.jvmDescriptorFromIrType(arg.type)
        val valueOfMethod = when (desc) {
            "I", "Z", "B", "S", "C" -> "($desc)Ljava/lang/String;"
            "J" -> "(J)Ljava/lang/String;"
            "F" -> "(F)Ljava/lang/String;"
            "D" -> "(D)Ljava/lang/String;"
            else -> "(Ljava/lang/Object;)Ljava/lang/String;"
        }
        generateIrExpression(mv, arg, ctx, owner)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", valueOfMethod, false)
    }

    /**
     * 做什么：为 `string.codePointAt(i)` 生成字节码——把**码点序号** `i` 转成
     *   UTF-16 偏移，读取该处的 Unicode scalar value，并在越界/畸形输入时抛
     *   结构化异常。
     *
     * 什么时候用：仅由 [generateIrExpression] 的 `__jasper_string_code_point_at__`
     *   分支调用；IR 约定实参 0 是接收者（java.lang.String），实参 1 是 int32 索引。
     *
     * 前置条件：检查器已保证实参个数/类型正确；接收者与索引表达式在这里各只求值
     *   一次，顺序为「接收者 → 索引」。局部槽从 [MethodContext.nextSlot] 顺延，
     *   不回收，避免与仍在使用的槽位重叠。
     *
     * 失败模式（均为运行期、由生成代码主动抛出，不是编译器内部错误）：
     *   - `i < 0` 或 `i >= 码点数`：`IndexOutOfBoundsException`，消息含方法名、索引
     *     与码点长度；不返回 -1、不钳制、不回绕。
     *   - 扫描范围内出现未配对代理项：`IllegalArgumentException`，消息含方法名、
     *     请求索引与 UTF-16 偏移；不返回 U+FFFD 替换字符。
     *   - 接收者为 null：第一次调用 `String.length()` 时按 JVM 语义抛 NPE。
     *
     * 为什么这样设计：
     *   1. JVM 的 `String.codePointAt(int)` 参数是 UTF-16 偏移，而语言层 `i` 是码点
     *      序号；直接把 `i` 传进去会让 `"a😀b".codePointAt(1)` 取到代理项，语义错位。
     *   2. 手动扫描而不是 `offsetByCodePoints(0, i)`：后者不校验未配对代理项，会把
     *      孤立代理项当成一个“码点”静默返回，违反语言层「合法 string 是 scalar
     *      value 序列」的前提。扫描同时完成「码点序号 → 偏移」与代理对校验。
     *   3. 只在需要时扫描到目标位置（含目标），保持设计文档中的 O(i) 复杂度；
     *      目标之后的畸形代理项不会被本次读取触及，符合「在读取时校验」的裁决。
     *   4. 异常消息用 `StringBuilder` 拼装而不是 `String.format`，避免装箱与
     *      Locale/格式串依赖；生成的 class 仍是 Java 8（无 invokedynamic）。
     *
     * 栈/槽位说明：所有中间值先落到局部槽，保证异常分支处操作数栈为空；
     *   ASM 的 COMPUTE_FRAMES 可以据此安全计算栈帧。
     */
    private fun emitStringCodePointAtCall(
        mv: MethodVisitor,
        expr: IrCall,
        ctx: MethodContext,
        owner: String
    ) {
        // 槽位：s / index / utf16Len / offset / codePointIndex / char / message。
        // 每个槽只占 1（String 引用与 int 都不是宽类型），分配后不复用。
        val stringSlot = ctx.nextSlot++
        val indexSlot = ctx.nextSlot++
        val utf16LengthSlot = ctx.nextSlot++
        val offsetSlot = ctx.nextSlot++
        val codePointIndexSlot = ctx.nextSlot++
        val charSlot = ctx.nextSlot++
        val messageSlot = ctx.nextSlot++

        // 求值顺序：接收者 → 索引（与语言级求值顺序一致，且各只求值一次）。
        generateIrExpression(mv, expr.getValueArgument(0)!!, ctx, owner)
        storeLocal(mv, "Ljava/lang/String;", stringSlot)
        generateIrExpression(mv, expr.getValueArgument(1)!!, ctx, owner)
        storeLocal(mv, "I", indexSlot)

        // UTF-16 长度只取一次；后续循环与异常消息复用，避免重复调用。
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
        storeLocal(mv, "I", utf16LengthSlot)

        val nonNegative = Label()
        val loopStart = Label()
        val loopDone = Label()
        val notHighSurrogate = Label()
        val advanceIndex = Label()
        val targetNotHighSurrogate = Label()
        val resultReady = Label()
        val outOfBounds = Label()
        val invalidUnicode = Label()

        // 负索引直接拒绝：不做 Python 式“从末尾数”，避免与 s.length - 1 混淆。
        loadLocal(mv, "I", indexSlot)
        mv.visitJumpInsn(Opcodes.IFGE, nonNegative)
        emitThrowIndexOutOfBounds(mv, indexSlot, stringSlot, utf16LengthSlot, messageSlot)
        mv.visitLabel(nonNegative)

        // offset = 0（UTF-16 偏移）；codePointIndex = 0（已消费的码点数）。
        pushInt(mv, 0)
        storeLocal(mv, "I", offsetSlot)
        pushInt(mv, 0)
        storeLocal(mv, "I", codePointIndexSlot)

        mv.visitLabel(loopStart)
        // while (codePointIndex < index)：每次前进一个完整码点（代理对一次走 2 个码元）。
        loadLocal(mv, "I", codePointIndexSlot)
        loadLocal(mv, "I", indexSlot)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopDone)

        // 若还没走到 index 就已耗尽字符串，说明 index >= 码点数：越界。
        loadLocal(mv, "I", offsetSlot)
        loadLocal(mv, "I", utf16LengthSlot)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, outOfBounds)

        // char c = s.charAt(offset)
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        loadLocal(mv, "I", offsetSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
        storeLocal(mv, "I", charSlot)

        // 高代理项必须紧跟低代理项，否则是畸形输入。
        loadLocal(mv, "I", charSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isHighSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, notHighSurrogate)
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        loadLocal(mv, "I", utf16LengthSlot)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, invalidUnicode)
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isLowSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, invalidUnicode)
        // 合法代理对：一次消费 2 个 UTF-16 码元。
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 2)
        mv.visitInsn(Opcodes.IADD)
        storeLocal(mv, "I", offsetSlot)
        mv.visitJumpInsn(Opcodes.GOTO, advanceIndex)

        mv.visitLabel(notHighSurrogate)
        // 低代理项出现在非配对位置：同样是畸形输入（不能当作 scalar value 返回）。
        loadLocal(mv, "I", charSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isLowSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFNE, invalidUnicode)
        // 普通 BMP 码元：消费 1 个。
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        storeLocal(mv, "I", offsetSlot)

        mv.visitLabel(advanceIndex)
        loadLocal(mv, "I", codePointIndexSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        storeLocal(mv, "I", codePointIndexSlot)
        mv.visitJumpInsn(Opcodes.GOTO, loopStart)

        mv.visitLabel(loopDone)
        // index == 码点数（或更大）时，offset 已到末尾：统一按越界处理。
        loadLocal(mv, "I", offsetSlot)
        loadLocal(mv, "I", utf16LengthSlot)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, outOfBounds)

        // 读取目标位置的第一个码元。
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        loadLocal(mv, "I", offsetSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
        storeLocal(mv, "I", charSlot)

        // 目标若是高代理项，必须与后一个低代理项组成合法代理对，并合并为一个码点。
        loadLocal(mv, "I", charSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isHighSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, targetNotHighSurrogate)
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        loadLocal(mv, "I", utf16LengthSlot)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, invalidUnicode)
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isLowSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, invalidUnicode)
        // Character.toCodePoint(high, low) 把代理对合并成 U+10000..U+10FFFF。
        loadLocal(mv, "I", charSlot)
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        loadLocal(mv, "I", offsetSlot)
        pushInt(mv, 1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "toCodePoint", "(CC)I", false)
        mv.visitJumpInsn(Opcodes.GOTO, resultReady)

        mv.visitLabel(targetNotHighSurrogate)
        // 目标若是孤立低代理项：拒绝，不能把代理区数值当 scalar value 返回。
        loadLocal(mv, "I", charSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "isLowSurrogate", "(C)Z", false)
        mv.visitJumpInsn(Opcodes.IFNE, invalidUnicode)
        // 普通 BMP 码元直接就是码点。
        loadLocal(mv, "I", charSlot)
        mv.visitJumpInsn(Opcodes.GOTO, resultReady)

        // 异常块：都先构造消息再 ATHROW；此处操作数栈为空，符合 COMPUTE_FRAMES 的前提。
        mv.visitLabel(outOfBounds)
        emitThrowIndexOutOfBounds(mv, indexSlot, stringSlot, utf16LengthSlot, messageSlot)
        mv.visitLabel(invalidUnicode)
        emitThrowInvalidUnicode(mv, indexSlot, offsetSlot, messageSlot)

        // 正常结果（int32）留在栈顶，交给调用方。
        mv.visitLabel(resultReady)
    }

    /**
     * 做什么：生成并抛出 `IndexOutOfBoundsException`，消息形如
     *   `String.codePointAt: index -1 out of bounds for length 3`。
     * 什么时候用：[emitStringCodePointAtCall] 检测到负索引或索引 >= 码点数时。
     * 前置条件：三个槽分别存着 int32 索引、java.lang.String 接收者、UTF-16 长度；
     *   [messageSlot] 是空闲的引用槽。
     * 失败模式：无（生成代码必然 ATHROW；若槽内容被调用方破坏则属于编译器内部错误）。
     * 为什么消息含码点长度：调用方通常拿 `s.length`（码点数）比较，报错时显示同一
     *   单位才不会误导；UTF-16 长度只用于内部偏移，不暴露给语言层。
     */
    private fun emitThrowIndexOutOfBounds(
        mv: MethodVisitor,
        indexSlot: Int,
        stringSlot: Int,
        utf16LengthSlot: Int,
        messageSlot: Int
    ) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        appendStringBuilderString(mv, "String.codePointAt: index ")
        loadLocal(mv, "I", indexSlot)
        appendStringBuilderInt(mv)
        appendStringBuilderString(mv, " out of bounds for length ")
        // 码点数 = s.codePointCount(0, s.length())，与语言层 .length 完全一致。
        loadLocal(mv, "Ljava/lang/String;", stringSlot)
        pushInt(mv, 0)
        loadLocal(mv, "I", utf16LengthSlot)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "codePointCount", "(II)I", false)
        appendStringBuilderInt(mv)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        storeLocal(mv, "Ljava/lang/String;", messageSlot)
        throwWithMessage(mv, "java/lang/IndexOutOfBoundsException", messageSlot)
    }

    /**
     * 做什么：生成并抛出 `IllegalArgumentException`，消息形如
     *   `String.codePointAt: invalid Unicode at code point index 1:
     *    unpaired surrogate at UTF-16 offset 1`。
     * 什么时候用：[emitStringCodePointAtCall] 在扫描/目标位置遇到未配对代理项时。
     * 前置条件：indexSlot 是请求的码点索引，offsetSlot 是当前 UTF-16 偏移，
     *   [messageSlot] 是空闲引用槽。
     * 失败模式：无（生成代码必然 ATHROW）。
     * 为什么单独用 IllegalArgumentException：输入字符串不是合法 Unicode scalar
     *   value 序列，属于参数/数据非法而非索引越界；调用方可以按异常类型区分
     *   「索引写错」与「平台边界传入了畸形字符串」，无需解析消息文本。
     */
    private fun emitThrowInvalidUnicode(
        mv: MethodVisitor,
        indexSlot: Int,
        offsetSlot: Int,
        messageSlot: Int
    ) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        appendStringBuilderString(mv, "String.codePointAt: invalid Unicode at code point index ")
        loadLocal(mv, "I", indexSlot)
        appendStringBuilderInt(mv)
        appendStringBuilderString(mv, ": unpaired surrogate at UTF-16 offset ")
        loadLocal(mv, "I", offsetSlot)
        appendStringBuilderInt(mv)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        storeLocal(mv, "Ljava/lang/String;", messageSlot)
        throwWithMessage(mv, "java/lang/IllegalArgumentException", messageSlot)
    }

    /** 向栈顶的 `StringBuilder` 追加一个字符串常量（append(String)）。 */
    private fun appendStringBuilderString(mv: MethodVisitor, value: String) {
        mv.visitLdcInsn(value)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            false
        )
    }

    /** 向栈顶的 `StringBuilder` 追加一个 int（append(int)）。 */
    private fun appendStringBuilderInt(mv: MethodVisitor) {
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(I)Ljava/lang/StringBuilder;",
            false
        )
    }

    /**
     * 做什么：用 [messageSlot] 里的消息构造指定异常并 ATHROW。
     * 什么时候用：两个字符串内建错误分支共用，保证「NEW/DUP/<init>(String)/ATHROW」
     *   序列只维护一份。
     * 前置条件：messageSlot 已存非 null 的 java.lang.String；exceptionInternal 是
     *   具有 `(String)` 构造器的 JVM 异常内部名。
     * 失败模式：无（生成代码必然 ATHROW）。
     */
    private fun throwWithMessage(mv: MethodVisitor, exceptionInternal: String, messageSlot: Int) {
        mv.visitTypeInsn(Opcodes.NEW, exceptionInternal)
        mv.visitInsn(Opcodes.DUP)
        loadLocal(mv, "Ljava/lang/String;", messageSlot)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            exceptionInternal,
            "<init>",
            "(Ljava/lang/String;)V",
            false
        )
        mv.visitInsn(Opcodes.ATHROW)
    }

    // 把「栈顶那一个值」按描述符装箱成包装类（覆盖全部 8 种基本类型）。
    // 与 emitBoxingIfNeeded 的区别：这里不判断目标类型，只要基本类型就装箱，
    // 专用于 Map.put 这类「两个参数都必须是 Object」的场景。
    private fun boxTopOfStack(mv: MethodVisitor, desc: String) {
        when (desc) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            "B" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)
            "S" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)
            "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)
        }
    }

    // 从数组表达式的描述符（"[I" / "[Ljava/lang/String;"）取出元素描述符；
    // 不是数组时按 Object 兜底（调用方应先确认，否则会选错 xALOAD）。
    // 注意：本文件内目前无人调用——"get" 分支内联了同样的 substring 逻辑，
    // 属于重复实现，待清理；保留仅为不改变行为。
    private fun arrayElementDesc(arrExpr: IrExpression): String {
        val desc = typeMapper.jvmDescriptorFromIrType(arrExpr.type)
        return if (desc.startsWith("[")) desc.substring(1) else "Ljava/lang/Object;"
    }

    // 数组读指令选择：按元素描述符选 xALOAD。boolean/byte 共用 BALOAD（栈上都以 int 表示）。
    private fun arrayLoadOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IALOAD; "J" -> Opcodes.LALOAD; "F" -> Opcodes.FALOAD
            "D" -> Opcodes.DALOAD; "Z", "B" -> Opcodes.BALOAD; "C" -> Opcodes.CALOAD
            "S" -> Opcodes.SALOAD; else -> Opcodes.AALOAD
        }
    }

    // 数组写指令选择：与 arrayLoadOpcode 对称（boolean/byte 共用 BASTORE）。
    private fun arrayStoreOpcode(desc: String): Int {
        return when (desc) {
            "I" -> Opcodes.IASTORE; "J" -> Opcodes.LASTORE; "F" -> Opcodes.FASTORE
            "D" -> Opcodes.DASTORE; "Z", "B" -> Opcodes.BASTORE; "C" -> Opcodes.CASTORE
            "S" -> Opcodes.SASTORE; else -> Opcodes.AASTORE
        }
    }

    // 新建一维数组：基本类型用 NEWARRAY + 类型码，引用类型用 ANEWARRAY+内部名。
    // 调用前栈上必须已压好长度（int）。elemDesc 是元素描述符而非数组描述符。
    private fun emitNewArray(mv: MethodVisitor, elemDesc: String) {
        when (elemDesc) {
            "I" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            "J" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG)
            "F" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT)
            "D" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
            "Z" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
            "B" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
            "S" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT)
            "C" -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR)
            else -> {
                // 引用元素：把 "Ljava/lang/String;" 还原成 "java/lang/String" 内部名。
                val internalName = elemDesc.removePrefix("L").removeSuffix(";").replace('.', '/')
                mv.visitTypeInsn(Opcodes.ANEWARRAY, internalName)
            }
        }
    }

    // lambda 兜底：lambda 已改由 translator 生成匿名 IrClass，故此路径理论上不再
    // 出现；真出现时只压 null，保证不抛异常且栈平衡（缺方法体是静默退化）。
    private fun emitLambda(mv: MethodVisitor, expr: IrFunctionExpression, ctx: MethodContext, owner: String) {
        // Lambdas are now translated as anonymous IrClass declarations in the translator.
        // IrFunctionExpression is deprecated; use the anonymous class path instead.
        // Fallback: push null for any remaining IrFunctionExpression nodes
        mv.visitInsn(Opcodes.ACONST_NULL)
    }

    // 返回指令映射的「按描述符」版本：与 generateIrReturn 的 when 逻辑重复。
    // 注意：本文件内目前无人调用（generateIrReturn 直接内联了同样的映射），
    // 属于待清理的重复/死代码；保留仅为不改变行为，抽出时应与 generateIrReturn 合并。
    private fun returnOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.IRETURN
            desc == "J" -> Opcodes.LRETURN
            desc == "F" -> Opcodes.FRETURN
            desc == "D" -> Opcodes.DRETURN
            else -> Opcodes.ARETURN
        }
    }

    // load 指令映射的「返回 opcode」版本：与 loadLocal 重复。
    // 注意：本文件内目前无人调用（loadLocal 直接 visitVarInsn），同为待清理的死代码。
    private fun loadOpcode(desc: String): Int {
        return when {
            desc == "I" || desc == "Z" || desc == "B" || desc == "S" || desc == "C" -> Opcodes.ILOAD
            desc == "J" -> Opcodes.LLOAD
            desc == "F" -> Opcodes.FLOAD
            desc == "D" -> Opcodes.DLOAD
            else -> Opcodes.ALOAD
        }
    }
}
