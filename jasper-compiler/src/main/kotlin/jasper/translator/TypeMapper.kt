// ============================================================================
// TypeMapper —— Jasper 类型 ↔ JVM 描述符 / 内部名 / Kotlin IR 类型的映射中枢
// 所属层次：translator（rank 2）
// 与谁协作：
//   上游：translator/JasperToIrTranslator 在构造 IR 时用本类造类型与字面量；
//         compiler/JvmIrBackend 与 compiler/archive/JvmBackend（ASM 字节码后端）
//         在写类/方法/字段/指令时用本类把类型翻成 JVM 描述符、内部名与泛型签名。
//   下游：只依赖 jasper.ast 的 JasType 家族与 Kotlin IR（org.jetbrains.kotlin.ir.*）
//         的数据结构，不反向依赖 checker/compiler，保证依赖单向、无环。
// 对外契约（稳定接口）：
//   - Jas→JVM：jvmDescriptorFromType / jvmInternalNameFromType / jvmReturnDescriptor；
//   - IR→JVM：jvmDescriptorFromIrType / jvmInternalNameFromIrType / jvmReturnDescriptorFromIrType；
//   - 造 IR 类型：type / typeFromSymbol / mapType / nullable / nonNull / arrayType 及 unitType..stringType；
//   - 造 IR 字面量：unitExpr / trueConst / falseConst / intConst..nullConst；
//   - 泛型签名：irTypeSignature / irTypeArgumentSignature / irTypeParameterSignature /
//               buildClassSignature / buildMethodSignature / buildFieldSignature / hasGenericParams。
// 扩展点（新增类型时只改一处）：
//   新增 Jasper 基本类型或其别名时，通常只在三张表各加一行即可：
//     builtinTypes（Jas 名 → Kotlin FQN）、jvmDescriptors（Kotlin FQN → 描述符）、
//     jvmInternalNames（Kotlin FQN → 内部名）；
//   新增 JasType 子类时，必须在 mapType / jvmDescriptorFromType / jvmInternalNameFromType
//   三处 when 里补齐分支，否则会落到各自兜底分支、静默丢失类型信息。
// 已知限制：
//   - 泛型形参识别：优先用 translator 登记的「已声明形参名」（knownTypeParamNames，支持 ID/KEY
//     等多字母形参），未登记时退回「单个大写字母且非已声明类型」的启发式（isLikelyTypeParam）；
//     仍不查作用域，同名类与形参冲突时形参优先，属已知近似；
//   - 函数类型 / 元组类型 / 引用类型当前统一退化为 java/lang/Object 或占位类名；
//   - IR 侧只识别 IrSimpleType，其它 IrType 子类一律兜底，不区分真实类型。
// ============================================================================

package jasper.translator

import jasper.ast.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.types.Variance

/**
 * 做什么：Jasper 类型系统与 JVM 类型系统之间的双向映射工具，同时充当 IR 类型/字面量的工厂。
 *
 * 什么时候用：翻译阶段（JasperToIrTranslator）需要把 JasType 变成 Kotlin IR 类型、或构造常量表达式时；
 *   后端阶段（JvmIrBackend / archive.JvmBackend）需要把 IR/Jas 类型落成 JVM 描述符、内部名或泛型签名时。
 *   什么时候不该用：本类**有状态**（[currentPkg] 决定命名类型的包归属），不要跨编译单元共享同一实例并随意改它。
 * 前置条件：把 JasNamedType 映射为具体包路径前，必须先设置 [currentPkg]；否则命名类型不补包前缀。
 * 失败模式：不抛异常。遇到未知类型 / 非 IrSimpleType 时，各函数按各自兜底规则返回
 *   `Ljava/lang/Object;`、`java/lang/Object`、`void` 或 kotlin.Unit；调用方若依赖精确类型，
 *   需自行用 [irTypeClassName] 等判定是否发生了兜底。
 * 为什么这样设计：所有「类型名 → 描述符/内部名」的规则集中到三张表，把「新增/改名一种类型」
 *   这一变化点收敛到一处，避免后端在多处硬编码 JVM 字符串。
 */
class TypeMapper {

    /**
     * 当前正在翻译的 Jasper 包名（形如 `a/b`）。mapType 把非内建的 JasNamedType 映射为 JVM 内部名时
     * 会前缀它；为空串时不补包归属，要求调用方给出的名字已完整。
     *
     * 兼容性说明：历史调用方（translator）传入的是**点分**包名（如 `a.b`），本类在
     * [resolveNamedTypeName] 内统一把 `.` 换成 `/` 后再拼内部名，因此两种写法都接受。
     */
    var currentPkg: String = ""

    /**
     * 当前源文件的**精确 import 表**：`简单名/别名 -> 被导入的全限定名（点分）`。
     *
     * 做什么：把 `import a.b.Thing;`（或 `import a.b.Thing as T;`）登记的映射交给类型解析，
     *   使后续代码生成把 `Thing` 链接到 `a/b/Thing` 而不是「当前包 + Thing」。
     * 什么时候用：翻译每个源文件前由 translator 安装（[JasperToIrTranslator] 负责装配）。
     * 前置条件：键既可能是简单名也可能是别名；值必须是点分全限定名。
     * 失败模式：未命中时退回「当前包 + 简单名」的旧规则；不抛异常。
     * 为什么这样设计：import 解析是「名字 → 内部名」这一变化点的一部分，集中到本类
     *   可让 mapType / jvmDescriptorFromType / jvmInternalNameFromType 三条路径共用同一规则，
     *   避免它们在各自实现里各写一份（正是 #14 的根因）。
     */
    var currentImports: Map<String, String> = emptyMap()

    /**
     * 当前源文件的**通配 import 包前缀**（点分，如 `a.b`）。
     *
     * 已知限制：通配导入无法在「不知道包内容」的前提下确定某个简单名到底属于哪个包，
     *   因此当前实现只记录、不在解析时自动展开（避免猜测出错误的全限定名，把
     *   「未解析」静默变成「链接到错误类」）。探针与标准写法均使用精确 import。
     */
    var currentWildcardPackages: List<String> = emptyList()

    /**
     * 本次编译中**已被声明过的用户类型简单名**集合（类/接口/枚举/注解）。
     *
     * 做什么：给 [isTypeParamLike] 一个判据，把「真实的单字母类名」（如用户定义的
     *   `class S`）与「泛型形参」（如 `Mapper<A,B>` 里的 `A`）区分开。
     * 什么时候用：翻译开始前由 translator 一次性登记；之后所有名字解析都查它。
     * 前置条件：登记的名字是**简单名**；不需要包信息（只用于「是不是类型参数」的判断）。
     * 失败模式：未登记时单字母名会被当成类型参数、不加包前缀（旧行为），可能链接失败；
     *   不抛异常。
     * 为什么这样设计：历史上用「单大写字母 == 类型参数」的启发式，会把用户定义的
     *   单字母类（如接口 `S`）误判为类型参数，导致接口表写出裸名 `S`、运行时
     *   NoClassDefFoundError（#7 的隐藏根因）。登记真实声明后，该启发式只在
     *   「确实不是已声明类型」时生效，兼顾泛型与真实单字母类型。
     */
    val knownTypeNames: MutableSet<String> = mutableSetOf()

    /**
     * 登记一个已声明的用户类型简单名，供 [isTypeParamLike] 判定使用。
     * 什么时候用：翻译开始前由 translator 扫描全部 AST 的顶层类型声明时调用。
     * 失败模式：无（重复登记是幂等的）。
     */
    fun registerKnownType(name: String) {
        knownTypeNames.add(name)
    }

    /**
     * 本次编译中**已声明的泛型形参简单名**集合（如 `T`、`E`、`K`、`V`、`ID`、`KEY`）。
     *
     * 做什么：给 [isTypeParamLike] 一个「来自声明」的权威判据，使其不再依赖
     *   「单大写字母」这一只能覆盖 `T/E/K/V` 的启发式——JPA 形状 `Repository<T, ID>`
     *   的 `ID` 是两个字母，旧启发式识别不到，会被当成真实类并写出 `L<包>/ID;`
     *   （FINDINGS #1 的根因之一），进而接口描述符与调用点实参不匹配、链接期 VerifyError。
     * 什么时候用：翻译开始前由 translator 扫描全部顶层类型声明时登记；之后所有名字解析都查它。
     * 前置条件：登记的是**形参简单名**（不含包信息）。
     * 失败模式：未登记时退回单字母启发式；不抛异常。
     * 为什么这样设计：把「哪些名字是泛型形参」从「猜」变成「登记」，与 [knownTypeNames]
     *   一正一反地收紧判定；依赖单向，translator 只写、本类只读。
     * 已知边界：登记是全编译单元作用域的（不区分声明作用域）；若同名类 `ID` 与形参 `ID`
     *   既存，形参判定优先，属 v1 已知近似（详见 [isTypeParamLike]）。
     */
    val knownTypeParamNames: MutableSet<String> = mutableSetOf()

    /**
     * 登记一个已声明的泛型形参名，供 [isTypeParamLike] 判定使用。
     * 什么时候用：翻译开始前由 translator 扫描 JasClass/JasInterface 的 `typeParameters` 时调用。
     * 失败模式：无（重复登记是幂等的）。
     */
    fun registerTypeParam(name: String) {
        knownTypeParamNames.add(name)
    }

    // 名字 → IR 类符号 的缓存：同一名字复用同一符号，保证 IR 中同名类型「身份相等」，
    // 后端才能凭 classifier 从 symbolToName 反查回名字。
    private val symbols = mutableMapOf<String, IrClassSymbol>()
    // 反向索引：IR 分类符符号 → 原始名字。irTypeClassName 靠它把 IR 类型还原成名字；
    // 只能识别由本类 type() 造出来（因而登记过）的类型，外部符号查不到会返回 null。
    private val symbolToName = mutableMapOf<IrClassifierSymbol, String>()

    /**
     * 做什么：把 IR 类型还原成它登记过的 Jasper/JVM 名字。
     *
     * 什么时候用：后端/翻译器需要知道某个 IR 类型「原来叫什么」时（例如判断是否为数组、迭代器）。
     * 失败模式：非 IrSimpleType，或 classifier 不在 symbolToName 里（不是本类 type() 造出来的），返回 null；
     *   调用方必须显式处理 null，不能把它当成空名字。
     */
    fun irTypeClassName(type: IrType): String? {
        if (type is IrSimpleType) {
            return symbolToName[type.classifier]
        }
        return null
    }

    // 把「名字」落到 JVM 描述符：数组名（以 '[' 开头）本身就是描述符，先做元素擦除；
    // 类型变量名（如 T）擦除为 Object（JVM 泛型擦除）；否则先经 builtinTypes 归一化
    // （识别 int32/string 等 Jas 别名），再转描述符。
    private fun classNameToJvmDescriptor(name: String): String {
        if (name.startsWith("[")) return eraseArrayDescriptor(name)
        if (isTypeVariableLike(name)) return "Ljava/lang/Object;"
        val mapped = builtinTypes[name.lowercase()] ?: name
        return jvmDescriptor(mapped)
    }

    /**
     * 做什么：判断一个「名字」是否应按 JVM 泛型擦除规则落到 `java/lang/Object`。
     * 判据：直接委托 [isTypeParamLike]——已登记的形参名（含多字母 `ID`/`KEY`）优先，
     *   否则「单个大写字母且未被登记为已声明类型」的启发式（避免用户定义的单字母类
     *   `class S` 被误擦除）。
     * 什么时候用：所有「名字 → 描述符/内部名」的落点（[classNameToJvmDescriptor] /
     *   [jvmInternalNameFromIrType] / [jvmDescriptorFromType] / [jvmInternalNameFromType]）。
     * 前置条件：若希望「真实单字母类型」不被误擦，调用方须先通过
     *   [registerKnownType] 登记过它；若希望多字母形参被擦除，须先经 [registerTypeParam]
     *   登记（translator 预登记阶段两者都会做）。
     * 失败模式：不抛异常；只做名字判定，不查作用域/上界。
     * 为什么这样设计：JVM 泛型擦除要求无界类型变量最终落到 Object。把该判定集中到唯一
     *   函数，避免后端/翻译器各处各自拼 `LT;`（FINDINGS #1 的根因）。
     * 已知限制：v1 只支持无界类型变量；有界（`<T extends Foo>`）仍落到 Object，属任务书边界。
     */
    private fun isTypeVariableLike(name: String): Boolean {
        return isTypeParamLike(name)
    }

    /**
     * 做什么：把一个数组描述符中的「类型变量元素」擦除为 Object，例如 `[LT;` →
     *   `[Ljava/lang/Object;`、`[[LT;` → `[[Ljava/lang/Object;`。
     * 什么时候用：[classNameToJvmDescriptor] 与 [jvmInternalNameFromIrType] 遇到以 '[' 开头
     *   的名字时；因为 `T[]` 在构造数组类型时就已把元素拼成了 `LT;`。
     * 前置条件：desc 是以一个或多个 '[' 开头的合法数组描述符。
     * 失败模式：不抛异常；非数组/无法识别的元素原样保留。
     * 为什么需要：数组元素是类型变量时，数组整体描述符也必须擦除，否则接口方法
     *   （如 `findAll(): T[]`）会写出 `[LT;` 这种非法签名。
     */
    private fun eraseArrayDescriptor(desc: String): String {
        var dims = 0
        while (dims < desc.length && desc[dims] == '[') dims++
        val element = desc.substring(dims)
        val erasedElement = if (element.startsWith("L") && element.endsWith(";")) {
            val internal = element.substring(1, element.length - 1)
            if (isTypeVariableLike(internal)) "Ljava/lang/Object;" else element
        } else {
            element
        }
        return "[".repeat(dims) + erasedElement
    }

    /**
     * 做什么：IR 类型 → JVM 字段/参数描述符（如 `I`、`Ljava/lang/String;`、`[I`）。
     *
     * 什么时候用：JvmIrBackend 生成字节码指令/字段类型时高频调用。
     * 失败模式：无法还原名字的 IR 类型返回 `Ljava/lang/Object;`（引用类型兜底，**不会**返回 `V`）；
     *   因此返回值可能并非真实类型，调用方需容忍。
     */
    fun jvmDescriptorFromIrType(type: IrType): String {
        val name = irTypeClassName(type)
        if (name != null) {
            return classNameToJvmDescriptor(name)
        }
        return "Ljava/lang/Object;"
    }

    /**
     * 做什么：IR 类型 → 方法**返回**描述符；与 [jvmDescriptorFromIrType] 相同，语义上强调返回位。
     *
     * 什么时候用：生成方法签名/调用指令的返回类型时。
     * 失败模式：同 jvmDescriptorFromIrType；注意 Unit 类型描述符为 `V`。
     */
    fun jvmReturnDescriptorFromIrType(type: IrType): String {
        val d = jvmDescriptorFromIrType(type)
        // 已知边界：当前 jvmDescriptorFromIrType 不会产生 V（无名字时兜底为对象），
        // 这里的 `if (d == "V")` 属防御式写法，保留以便未来引入 Unit 识别。
        return if (d == "V") "V" else d
    }

    /**
     * 做什么：IR 类型 → JVM 内部名（如 `java/lang/String`、`int`；数组描述符原样返回）。
     *
     * 什么时候用：ASM 访问类/方法/字段时需要「内部名」而非描述符时。
     * 失败模式：无法还原名字时返回 `java/lang/Object`。
     */
    fun jvmInternalNameFromIrType(type: IrType): String {
        val name = irTypeClassName(type)
        if (name != null) {
            if (isTypeVariableLike(name)) return "java/lang/Object"
            return if (name.startsWith("[")) eraseArrayDescriptor(name)
            else jvmInternalName(builtinTypes[name.lowercase()] ?: name)
        }
        return "java/lang/Object"
    }

    // Jasper 类型名（含别名，统一小写）→ Kotlin 全限定名。
    // 这是「新增/改名一种基本类型」的唯一入口：别名都在这张表里归一。
    // 键必须小写，查表方一律使用 name.lowercase()。
    private val builtinTypes = mapOf(
        // 规范名；注意表里没有别名 "int"，不要臆造。
        "int32" to "kotlin.Int",
        "int64" to "kotlin.Long",
        "float32" to "kotlin.Float",
        "float64" to "kotlin.Double",
        // bool / boolean 是同一类型的一对别名，两行目标必须始终一致。
        "bool" to "kotlin.Boolean",
        "boolean" to "kotlin.Boolean",
        "string" to "kotlin.String",
        // void 在 IR 侧落到 kotlin.Unit，其 JVM 描述符为 V、内部名为 void。
        "void" to "kotlin.Unit",
        "byte" to "kotlin.Byte",
        "short" to "kotlin.Short",
        "char" to "kotlin.Char",
        // 集合族：语言层名字统一落到 JDK 集合类型，成员调用再由 BuiltinCollections
        // 决定语义。dict 是 Map 的具体实现；List/MutableList 共用 java.util.List
        // 接口，具体实例由 translator 的 `new` 路径落到 ArrayList。
        "dict" to "java.util.HashMap",
        "map" to "java.util.Map",
        "hashmap" to "java.util.HashMap",
        "list" to "java.util.List",
        "mutablelist" to "java.util.List",
        "arraylist" to "java.util.ArrayList"
    )

    // Kotlin 全限定名 → JVM 字段/参数描述符。
    // 关键边界：J 与 D（long/double）在 JVM 操作数栈上各占**两个**槽位，
    // 但描述符本身仍只是一个字符；「占两槽」是字节码指令层面的约定，
    // 由后端（JvmIrBackend/JvmBackend）负责槽位计算，本表不表达槽位。
    private val jvmDescriptors = mapOf(
        "kotlin.Int" to "I",
        "kotlin.Long" to "J",
        "kotlin.Float" to "F",
        "kotlin.Double" to "D",
        "kotlin.Boolean" to "Z",
        "kotlin.Byte" to "B",
        "kotlin.Short" to "S",
        "kotlin.Char" to "C",
        "kotlin.String" to "Ljava/lang/String;",
        "kotlin.Unit" to "V",
        "kotlin.Any" to "Ljava/lang/Object;",
        // 集合类型的描述符必须显式登记：这些名字走 builtinTypes 归一后已是
        // java.util.* 全限定名，若只依赖兜底拼装也能工作，但集中登记能让后端
        // 的 JDK 探测、泛型签名和测试断言的字符串来源保持一致。
        "java.util.List" to "Ljava/util/List;",
        "java.util.ArrayList" to "Ljava/util/ArrayList;",
        "java.util.Map" to "Ljava/util/Map;",
        "java.util.HashMap" to "Ljava/util/HashMap;"
    )

    // Kotlin 全限定名 → JVM 内部名。基本类型用单关键字（int/long/...），
    // 引用类型用斜杠分隔的内部名；Unit→void 用于方法返回位。
    private val jvmInternalNames = mapOf(
        "kotlin.Int" to "int",
        "kotlin.Long" to "long",
        "kotlin.Float" to "float",
        "kotlin.Double" to "double",
        "kotlin.Boolean" to "boolean",
        "kotlin.Byte" to "byte",
        "kotlin.Short" to "short",
        "kotlin.Char" to "char",
        "kotlin.String" to "java/lang/String",
        "kotlin.Unit" to "void",
        "kotlin.Any" to "java/lang/Object",
        "java.util.List" to "java/util/List",
        "java.util.ArrayList" to "java/util/ArrayList",
        "java.util.Map" to "java/util/Map",
        "java.util.HashMap" to "java/util/HashMap"
    )

    /**
     * 做什么：按「名字」造一个非空、无泛型实参、无注解的 IrSimpleType，并登记名字供反查。
     *
     * 什么时候用：需要构造引用/基本类型 IR 时（含 mapType 的兜底路径）。
     * 前置条件：name 应是 JVM 描述符/内部名或 Kotlin FQN，且与后续查表口径一致。
     * 为什么这样设计：符号带缓存，保证同一名字多次得到「同一身份」的类型，
     *   这样 symbolToName 才能按 classifier 反查回名字；每次都新建会导致 IR 中同名类型互不相等。
     * 失败模式：不校验名字合法性，未知名字也会被原样登记（可能在后端产生无效描述符）。
     */
    fun type(name: String): IrType {
        val symbol = symbols.getOrPut(name) { IrClassSymbolImpl().also { symbolToName[it] = name } }
        return IrSimpleTypeImpl(symbol, false, emptyList(), emptyList(), null)
    }

    /**
     * 做什么：用已有的分类符符号（如类型参数符号）造一个非空 IrSimpleType。
     *
     * 什么时候用：调用方已持有符号（例如泛型的 IrTypeParameterSymbol）时，
     *   避开 type(name) 的名字缓存，直接复用符号身份。
     * 失败模式：不校验符号有效性；构造期符号的使用受 IR 构造期 API 约束。
     */
    fun typeFromSymbol(symbol: IrClassifierSymbol): IrType {
        return IrSimpleTypeImpl(symbol, false, emptyList(), emptyList(), null)
    }

    /**
     * 做什么：把一个 IrType 变成同 classifier/实参的可空类型。
     *
     * 什么时候用：JasNullableType 映射为 IR 时（mapType 内部）。
     * 为什么这样设计：直接翻转 isNullable 标志并重建 IrSimpleTypeImpl，保持实参与注解不变，
     *   因此 `Foo<T>?` 不会丢掉 T。
     * 失败模式：入参不是 IrSimpleTypeImpl 时原样返回（无法表达可空，静默降级）。
     */
    fun nullable(inner: IrType): IrType {
        if (inner is IrSimpleTypeImpl) {
            return IrSimpleTypeImpl(
                inner.classifier, true,
                inner.arguments, inner.annotations, inner.abbreviation
            )
        }
        return inner
    }

    /**
     * 做什么：[nullable] 的逆操作，把 IrType 变成同一类型的非空版本。
     *
     * 什么时候用：JasNonNullType 映射为 IR 时（mapType 内部）。
     * 失败模式：入参不是 IrSimpleTypeImpl 时原样返回。
     */
    fun nonNull(inner: IrType): IrType {
        if (inner is IrSimpleTypeImpl) {
            return IrSimpleTypeImpl(
                inner.classifier, false,
                inner.arguments, inner.annotations, inner.abbreviation
            )
        }
        return inner
    }

    /**
     * 做什么：把一个**用户类型名**解析为 JVM 内部名（`a/b/Thing` 形式），统一 import / 包归属规则。
     *
     * 解析优先级（顺序即语义，勿随意调整）：
     *   1. 已含 `/` 的名字视为已是内部名，原样返回；
     *   2. 已含 `.` 的名字视为已限定的全限定名，只把 `.` 换成 `/`；
     *   3. 命中精确 import（简单名或别名），用被导入的全限定名；
     *   4. 有当前包且不是类型参数（单个大写字母），前缀当前包；
     *   5. 兜底：原样返回（交给调用方的下游规则处理）。
     *
     * 什么时候用：所有「命名类型 → 内部名」的路径（mapType / jvmDescriptorFromType /
     *   jvmInternalNameFromType）以及 translator 计算接口表/父类时都应经过这里。
     * 前置条件：若希望规则 3/4 生效，调用方须先设置 [currentPkg] 与 [currentImports]。
     * 失败模式：不校验名字是否真实存在；无法解析时按规则 5 原样返回（不抛异常），
     *   因此调用方若拿到可疑名字应自行通过后端验证器或符号表兜底。
     * 为什么这样设计：把「名字 → 内部名」这一变化点收敛到唯一函数，依赖单向，
     *   任何一条代码生成路径都不会再各自拼包名（#14/#12 的统一修法）。
     */
    fun resolveNamedTypeName(name: String): String {
        if (name.contains('/')) return name
        if (name.contains('.')) return name.replace('.', '/')
        currentImports[name]?.let { return it.replace('.', '/') }
        if (currentPkg.isNotEmpty() && !isTypeParamLike(name)) {
            return "${currentPkg.replace('.', '/')}/$name"
        }
        return name
    }

    /**
     * 做什么：JasType → IrType 的总入口，覆盖 Jasper 全部类型种类。
     *
     * 什么时候用：翻译器把声明/表达式的 Jas 类型翻成 IR 类型时。
     * 前置条件：命名类型若需要包归属，调用方应已设置 [currentPkg]。
     * 失败模式：type 为 null 或命中未知子类时返回 unitType()（kotlin.Unit），即**静默降级**而非报错；
     *   依赖精确类型的调用方需提前自检。
     */
    fun mapType(type: JasType?): IrType {
        // 缺失类型统一按 Unit 处理，避免调用方到处判空。
        if (type == null) return unitType()
        return when (type) {
            is JasPrimitiveType -> {
                // 基本类型名先经 builtinTypes 归一（大小写不敏感）；未知名字原样当类名用。
                val jvmName = builtinTypes[type.name.lowercase()] ?: type.name
                type(jvmName)
            }
            is JasNamedType -> {
                val rawName = type.name
                val builtin = builtinTypes[rawName.lowercase()]
                // 命名类型解析优先级：内建名 > resolveNamedTypeName（import > 包归属 > 原样）。
                // 单大写字母（如 T）视为类型参数，不补包前缀，否则会破坏泛型引用。
                val jvmName = builtin ?: resolveNamedTypeName(rawName)
                val base = type(jvmName)
                if (type.typeArguments.isEmpty()) {
                    base
                } else {
                    // 带实参时用实参版本重建，避免误用无实参的缓存符号。
                    IrSimpleTypeImpl(
                        (base as IrSimpleType).classifier,
                        false,
                        mapTypeArguments(type.typeArguments),
                        emptyList(),
                        null
                    )
                }
            }
            is JasUnitType -> unitType()
            is JasStringType -> stringType()
            is JasAnyType -> type("kotlin.Any")
            is JasBytesType -> type("kotlin.ByteArray")
            is JasRegexType -> type("kotlin.text.Regex")
            // 可空/非空只改 Kotlin 类型标志，不改变底层 JVM 类型。
            is JasNullableType -> nullable(mapType(type.inner))
            is JasNonNullType -> nonNull(mapType(type.inner))
            is JasArrayType -> arrayType(mapType(type.inner))
            // 函数类型暂以占位类名 "FunctionType" 表示（非可解析的 JVM 名，见文件头已知限制）。
            is JasFunctionType -> type("FunctionType")
            is JasWildcardType -> {
                // 通配符有上界时用上界类型，无界时退回 Any。
                if (type.bound != null) mapType(type.bound) else type("kotlin.Any")
            }
            is JasPointerType -> type("kotlin.Long")
            is JasReferenceType -> type("kotlin.Any")
            is JasTupleType -> type("TupleType")
            else -> unitType()
        }
    }

    // 粗略判定「这是不是一个泛型参数名」：只认单个大写字母（T、E、K、V…）。
    // 这是启发式，不查作用域；因此名字恰好是单大写字母的普通类型会被误判为类型参数。
    private fun isLikelyTypeParam(name: String): Boolean {
        return name.length == 1 && name[0].isUpperCase()
    }

    /**
     * 做什么：判断名字应当被当作「泛型形参」（不加包前缀 / 擦除为 Object）还是「真实类型」。
     * 判据（顺序即语义，勿随意调整）：
     *   1. 已通过 [registerTypeParam] 登记为形参名 → 形参（覆盖 `ID/KEY/NAME` 等多字母形参）；
     *   2. 否则退回「单大写字母且未被登记为用户类型」的启发式（覆盖未登记的 `T/E/K/V`，
     *      同时避免把用户定义的单字母类 `class S` 误判，即 #7 的修法）。
     * 为什么需要：单看名字长度会把用户定义的单字母类误判为形参（#7）；只靠启发式又认不出
     *   多字母形参（#1）。先信「声明过的形参名」，再退回启发式，才能两者兼顾。
     * 已知边界：形参名集合是全编译单元作用域的；若同一编译里既声明了类 `ID`、又把它用作形参，
     *   形参判定优先——这是名字驱动近似的固有取舍，v1 接受并不阻塞常见写法。
     */
    private fun isTypeParamLike(name: String): Boolean {
        if (name in knownTypeParamNames) return true
        return isLikelyTypeParam(name) && name !in knownTypeNames
    }

    /**
     * 做什么：把一个 IrType 包成带变型（in/out/invariant）的类型实参投影。
     *
     * 什么时候用：为泛型实参构造 IrTypeArgument（mapTypeArguments 内部）。
     * 为什么这样设计：Kotlin 2.1.0 起 IrTypeProjectionImpl 变为 internal，只能用匿名对象实现
     *   IrTypeProjection；显式实现 equals/hashCode 以保留值语义（IR 结构比较依赖它）。
     * 失败模式：不校验入参；调用方负责传入合法的 variance 与 type。
     */
    fun makeTypeProjection(type: IrType, variance: Variance = Variance.INVARIANT): IrTypeArgument {
        // Kotlin 2.1.0 made IrTypeProjectionImpl internal; use anonymous object instead
        return object : IrTypeProjection {
            override val variance: Variance get() = variance
            override val type: IrType get() = type
            /**
             * 做什么：两个投影「变型与类型都相同」即相等，供 IR 结构比较使用。
             * 为什么这样设计：匿名对象默认是引用相等，会让同名同型的投影互不相等。
             */
            override fun equals(other: Any?): Boolean =
                other is IrTypeProjection && other.variance == variance && other.type == type
            /**
             * 做什么：与 equals 一致的散列，保证相等对象散列相同。
             */
            override fun hashCode(): Int = variance.hashCode() * 31 + type.hashCode()
        }
    }

    // 把 Jas 泛型实参列表转成 IR 类型实参：通配符按 extends 决定 out/in 变型，
    // 其余具体类型（具名类型 / 基本类型 / string / 数组 / 函数 / 元组）一律不变型投影。
    private fun mapTypeArguments(args: List<JasType>): List<IrTypeArgument> {
        return args.map { arg ->
            when (arg) {
                is JasWildcardType -> {
                    val boundType = if (arg.bound != null) mapType(arg.bound) else type("kotlin.Any")
                    makeTypeProjection(
                        boundType,
                        if (arg.extends) Variance.OUT_VARIANCE else Variance.IN_VARIANCE
                    )
                }
                // 为什么不再对未知/基本类型退化为星投影：接收者的泛型实参要参与
                // 「调用点返回类型替换」（如 `Box<int32>.get()` 的 T → int32）。
                // 星投影会丢掉基本类型，使调用点不知道该拆箱，从而在 `r = box.get()`
                // 处生成非法字节码（VerifyError）。具体类型全部按不变型投影即可保留信息；
                // 需要引用的签名位（`<...>`）由 irTypeArgumentSignature 负责装箱。
                else -> makeTypeProjection(mapType(arg), Variance.INVARIANT)
            }
        }
    }

    /**
     * 做什么：由元素类型造 JVM 数组类型（描述符形态，如 `[I`、`[Ljava/lang/String;`）。
     *
     * 什么时候用：JasArrayType 映射（mapType 内部）或显式需要数组类型时。
     * 为什么这样设计：数组类型的 classifier 名直接存「描述符」而非类名，
     *   后端凭 '[' 前缀即可识别，无需再查表。
     * 失败模式：元素类型无法还原名字时按对象数组 `[Ljava/lang/Object;` 兜底。
     */
    fun arrayType(inner: IrType): IrType {
        // 元素描述符经 jvmDescriptorFromIrType 得到，类型变量元素会擦除为 Object
        // （如 `T[]` → `[Ljava/lang/Object;`），避免写出 `[LT;` 这种非法签名。
        val innerDesc = jvmDescriptorFromIrType(inner)
        // 已知边界：两个分支目前表达式相同（统一为 `[` + 描述符），
        // 分开写是为将来区分基本类型数组与对象数组留出落点。
        val arrayJvmName = if (innerDesc.length == 1) "[$innerDesc" else "[$innerDesc"
        return type(arrayJvmName)
    }

    /**
     * 做什么：给一个 Jasper 类型名，返回对应 IR 类型（经 builtinTypes 归一）。
     *
     * 什么时候用：翻译器从数组描述符反推元素类型时（如 `[B` → byte）。
     * 注意：查表**不做** lowercase，键必须已是小写规范名（与 [katakana] 的区别在于此处按名直查）。
     * 失败模式：未知名字原样当类型名使用。
     */
    fun mapTypeName(name: String): IrType {
        val jvmName = builtinTypes[name] ?: name
        return type(jvmName)
    }

    /**
     * 做什么：由一个**数组类型的 IR 类型**反推出它元素类型的 IR 类型。
     *
     * 什么时候用：翻译数组读 `a[i]`、for-in 遍历数组等需要给「元素」一个精确 IR 类型的场景。
     *   后端据此选 xALOAD 指令，翻译器据此确定 `var x = a[i]` 的推断类型。
     * 前置条件：入参应当是数组 IR 类型（其 descriptor 以 '[' 开头）；否则按 [type]("kotlin.Any") 兜底。
     * 失败模式：入参不是数组、或元素描述符无法识别时返回 `kotlin.Any`（**不会**返回 Unit/V）。
     * 为什么放在 TypeMapper：这是「类型 ↔ 描述符」映射的一部分，所有后端/翻译器共用同一份规则；
     *   若在翻译器里各写一份，基本类型数组与对象数组很容易漏掉引用元素（#11 的根因之一）。
     * 关键边界：
     *   - 一维基本类型数组（`[I`/`[J`/`[F`/`[D`/`[Z`/`[B`/`[S`/`[C`）逐一对到 Jar 基本类型；
     *   - 引用元素 `Lfoo/Bar;` 去掉 `L`/`;` 后交给 [type]（它按内部名生成引用描述符）；
     *   - 多维数组的元素仍是数组描述符（如 `[[I` 的元素是 `[I`），原样交给 [type]。
     */
    fun irArrayElementType(arrayType: IrType): IrType {
        val desc = jvmDescriptorFromIrType(arrayType)
        if (!desc.startsWith("[")) return type("kotlin.Any")
        val elemDesc = desc.substring(1)
        return when (elemDesc) {
            "I" -> intType()
            "J" -> longType()
            "F" -> floatType()
            "D" -> doubleType()
            "Z" -> boolType()
            "B" -> type("kotlin.Byte")
            "S" -> type("kotlin.Short")
            "C" -> charType()
            // 元素本身还是数组（多维）或其它描述符形态：原样交给 type()（它只对 'L...;' 需要剥壳）。
            else -> if (elemDesc.startsWith("L") && elemDesc.endsWith(";")) {
                type(elemDesc.substring(1, elemDesc.length - 1))
            } else {
                type(elemDesc)
            }
        }
    }

    /**
     * 做什么：由类型名得到 JVM 字段/参数描述符。
     *
     * 什么时候用：已知名字（JVM 内部名或 Kotlin FQN）要转描述符时。
     * 前置条件：数组名须以 '[' 开头（原样返回）；类名用 '.' 分隔。
     * 失败模式：表中没有且不是数组时，按 `L<名字把.换成/>;` 生成引用描述符；不校验名字合法性。
     */
    fun jvmDescriptor(typeName: String): String {
        if (typeName.startsWith("[")) return typeName
        return jvmDescriptors[typeName] ?: "L${typeName.replace('.', '/')};"
    }

    /**
     * 做什么：JasType → JVM 字段/参数描述符（archive.JvmBackend 的主入口）。
     *
     * 什么时候用：直接持有 JasType（非 IR）时。
     * 关键边界：
     *   - 可空/非空只影响 Kotlin 语义，**不影响** JVM 描述符（都取内部类型）；
     *   - 数组描述符以 '[' 后直接拼元素描述符；
     *   - Unit → V；函数/元组类型退化为 Ljava/lang/Object;。
     * 失败模式：type 为 null 返回 `V`；未知子类返回 `Ljava/lang/Object;`。
     */
    fun jvmDescriptorFromType(type: JasType?): String {
        if (type == null) return "V"
        return when (type) {
            is JasNamedType -> {
                if (isTypeVariableLike(type.name)) return "Ljava/lang/Object;"
                val mapped = builtinTypes[type.name.lowercase()] ?: resolveNamedTypeName(type.name)
                jvmDescriptor(mapped)
            }
            is JasPrimitiveType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmDescriptor(mapped)
            }
            is JasUnitType -> "V"
            is JasStringType -> "Ljava/lang/String;"
            is JasAnyType -> "Ljava/lang/Object;"
            is JasArrayType -> "[" + jvmDescriptorFromType(type.inner)
            is JasNullableType -> jvmDescriptorFromType(type.inner)
            is JasNonNullType -> jvmDescriptorFromType(type.inner)
            is JasFunctionType -> "Ljava/lang/Object;"
            is JasTupleType -> "Ljava/lang/Object;"
            is JasWildcardType -> {
                if (type.bound != null) jvmDescriptorFromType(type.bound) else "Ljava/lang/Object;"
            }
            else -> "Ljava/lang/Object;"
        }
    }

    /**
     * 做什么：由类型名得到 JVM 内部名（`java/lang/String`、`int`）。
     *
     * 什么时候用：ASM 需要内部名时。
     * 失败模式：数组名原样返回；表中没有的把 '.' 换成 '/'；不校验合法性。
     */
    fun jvmInternalName(typeName: String): String {
        if (typeName.startsWith("[")) return typeName
        return jvmInternalNames[typeName] ?: typeName.replace('.', '/')
    }

    /**
     * 做什么：JasType → JVM 内部名（archive.JvmBackend 的主入口）。
     *
     * 什么时候用：需要类/超类/接口内部名时。
     * 关键边界：
     *   - 可空/非空同样不影响内部名；
     *   - 数组类型统一返回 java/lang/Object（数组内部名在别处按描述符处理）；
     *   - Unit → void。
     * 失败模式：null → "void"；未知子类 → "java/lang/Object"。
     */
    fun jvmInternalNameFromType(type: JasType?): String {
        if (type == null) return "void"
        return when (type) {
            is JasNamedType -> {
                if (isTypeVariableLike(type.name)) return "java/lang/Object"
                val mapped = builtinTypes[type.name.lowercase()] ?: resolveNamedTypeName(type.name)
                jvmInternalName(mapped)
            }
            is JasPrimitiveType -> {
                val mapped = builtinTypes[type.name.lowercase()] ?: type.name
                jvmInternalName(mapped)
            }
            is JasUnitType -> "void"
            is JasStringType -> "java/lang/String"
            is JasAnyType -> "java/lang/Object"
            is JasArrayType -> "java/lang/Object"
            is JasNullableType -> jvmInternalNameFromType(type.inner)
            is JasNonNullType -> jvmInternalNameFromType(type.inner)
            is JasFunctionType -> "java/lang/Object"
            is JasTupleType -> "java/lang/Object"
            is JasWildcardType -> {
                if (type.bound != null) jvmInternalNameFromType(type.bound) else "java/lang/Object"
            }
            else -> "java/lang/Object"
        }
    }

    /**
     * 做什么：JasType → 方法**返回**描述符；与 [jvmDescriptorFromType] 同义，语义上强调返回位。
     *
     * 什么时候用：生成方法签名/调用返回类型时（archive.JvmBackend）。
     * 失败模式：同 jvmDescriptorFromType；Unit/null → V。
     */
    fun jvmReturnDescriptor(type: JasType?): String {
        val d = jvmDescriptorFromType(type)
        return if (d == "V") "V" else d
    }

    /**
     * 做什么：把 Jasper 类型名（**区分大小写、按原样**）翻译成 Kotlin FQN。
     *
     * 什么时候用：需要按调用方给定的精确键查内建表时。
     * 注意：与 [mapTypeName] / [jvmDescriptorFromType] 不同，这里**不做** lowercase，
     *   键必须是表内原样的小写规范名（如 "int32"），大小写不符会原样返回。
     * 失败模式：查不到时原样返回入参。
     */
    fun katakana(jasName: String): String {
        return builtinTypes[jasName] ?: jasName
    }

    /** 做什么：造 kotlin.Unit 类型（映射 Jasper void / 缺失类型的默认结果）。 */
    fun unitType(): IrType = type("kotlin.Unit")
    /** 做什么：造 kotlin.Int 类型（Jasper int32）。 */
    fun intType(): IrType = type("kotlin.Int")
    /** 做什么：造 kotlin.Long 类型（Jasper int64）。 */
    fun longType(): IrType = type("kotlin.Long")
    /** 做什么：造 kotlin.Float 类型（Jasper float32）。 */
    fun floatType(): IrType = type("kotlin.Float")
    /** 做什么：造 kotlin.Double 类型（Jasper float64）。 */
    fun doubleType(): IrType = type("kotlin.Double")
    /** 做什么：造 kotlin.Boolean 类型（Jasper bool/boolean）。 */
    fun boolType(): IrType = type("kotlin.Boolean")
    /** 做什么：造 kotlin.Char 类型。 */
    fun charType(): IrType = type("kotlin.Char")
    /** 做什么：造 kotlin.String 类型。 */
    fun stringType(): IrType = type("kotlin.String")

    /**
     * 做什么：读取集合 IR 类型的第 [index] 个类型实参（如 `HashMap<string,int32>` 的
     *   string/int32）；缺失、星投影或非 IrSimpleType 时返回 null。
     *
     * 什么时候用：translator 降低集合调用、for-in 与嵌套泛型返回类型时。
     * 前置条件：receiver 是 TypeMapper 造出的 IR 类型（集合名已登记）。
     * 失败模式：返回 null 表示「类型信息缺失」，调用方应退回 any，而不是猜一个
     *   具体类型；不抛异常。
     * 为什么需要：集合成员的返回类型由类型实参决定（`get` 返回元素/值），不能像
     *   `size` 那样写死；把读取规则集中到本类，避免 translator 到处拆 IrSimpleType。
     */
    fun irCollectionTypeArgument(receiver: IrType, index: Int): IrType? {
        val simple = receiver as? IrSimpleType ?: return null
        val argument = simple.arguments.getOrNull(index) as? IrTypeProjection ?: return null
        return argument.type
    }

    /** 取 LIST 元素类型；类型信息缺失时退回 kotlin.Any。 */
    fun irCollectionElementType(receiver: IrType): IrType =
        irCollectionTypeArgument(receiver, 0) ?: type("kotlin.Any")

    /** 取 MAP 键类型；类型信息缺失时退回 kotlin.Any。 */
    fun irCollectionKeyType(receiver: IrType): IrType =
        irCollectionTypeArgument(receiver, 0) ?: type("kotlin.Any")

    /** 取 MAP 值类型；类型信息缺失时退回 kotlin.Any。 */
    fun irCollectionValueType(receiver: IrType): IrType =
        irCollectionTypeArgument(receiver, 1) ?: type("kotlin.Any")

    /**
     * 做什么：按集合成员声明 [spec] 与接收者 IR 类型计算调用点的返回 IR 类型。
     *
     * 什么时候用：translator 生成 `c:` 集合调用时；后端据此决定拆箱/CHECKCAST 的
     *   目标类型。
     * 前置条件：spec 来自 BuiltinCollections.member，且 receiver 是集合 IR 类型。
     * 失败模式：缺失泛型实参时返回 kotlin.Any；不抛异常。
     * 为什么不在 translator 里写 when：返回种类属于集合元数据的一部分，写在这里
     *   可与参数/返回规则集中维护。
     */
    fun irCollectionMemberReturnType(receiver: IrType, spec: CollectionMemberSpec): IrType =
        when (spec.returnKind) {
            CollectionReturnKind.INT -> intType()
            CollectionReturnKind.BOOL -> boolType()
            CollectionReturnKind.ELEMENT -> irCollectionElementType(receiver)
            CollectionReturnKind.VALUE -> irCollectionValueType(receiver)
            CollectionReturnKind.ANY -> type("kotlin.Any")
        }

    /** 做什么：造 Unit 常量表达式（IR 常量 null、类型 Unit）；用于补空返回占位。 */
    fun unitExpr(): IrExpression = IrConstImpl.constNull(0, 0, unitType())
    /** 做什么：造布尔真常量。 */
    fun trueConst(): IrExpression = IrConstImpl.constTrue(0, 0, boolType())
    /** 做什么：造布尔假常量。 */
    fun falseConst(): IrExpression = IrConstImpl.constFalse(0, 0, boolType())

    /** 做什么：造 Int 字面量（Jasper int32）。 */
    fun intConst(value: Int): IrExpression = IrConstImpl.int(0, 0, intType(), value)
    /** 做什么：造 Long 字面量（Jasper int64）。 */
    fun longConst(value: Long): IrExpression = IrConstImpl.long(0, 0, longType(), value)
    /** 做什么：造 Float 字面量（Jasper float32）。 */
    fun floatConst(value: Float): IrExpression = IrConstImpl.float(0, 0, floatType(), value)
    /** 做什么：造 Double 字面量（Jasper float64）。 */
    fun doubleConst(value: Double): IrExpression = IrConstImpl.double(0, 0, doubleType(), value)
    /** 做什么：造 Char 字面量。 */
    fun charConst(value: Char): IrExpression = IrConstImpl.char(0, 0, charType(), value)
    /** 做什么：造 String 字面量。 */
    fun stringConst(value: String): IrExpression = IrConstImpl.string(0, 0, stringType(), value)
    /** 做什么：把 Kotlin Boolean 翻成真/假常量表达式。 */
    fun boolConst(value: Boolean): IrExpression = if (value) trueConst() else falseConst()
    /** 做什么：造可空 Any 的 null 字面量；类型为 Any? 以兼容引用类型赋值。 */
    fun nullConst(): IrExpression = IrConstImpl.constNull(0, 0, nullable(type("kotlin.Any")))

    // ── JVM Signature utilities ──

    /**
     * 做什么：IR 类型 → JVM 泛型签名（Signature）里的类型片段，如 `Ljava/util/List<Ljava/lang/String;>;`。
     *
     * 什么时候用：构造类/方法/字段的泛型签名时（各 build* 函数与 JvmIrBackend 调用）。
     * 前置条件：类型须由本类登记过名字，否则退化为 Ljava/lang/Object;。
     * 关键边界：
     *   - 类型参数符号（IrTypeParameterSymbol）产生 `T名字;`，不含上界（上界出现在形参声明处）；
     *   - 数组描述符以 '[' 开头时原样返回，泛型签名里数组不展开实参；
     *   - 基本类型（I/J/F/D/Z/B/S/C）没有 <> 形式，直接返回单字符描述符；
     *     注意 J/D 在栈上占两槽，但签名文本里仍只是单个字符。
     * 失败模式：非 IrSimpleType / 未登记名字 → `Ljava/lang/Object;`。
     */
    @OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
    fun irTypeSignature(type: IrType): String {
        if (type is IrSimpleType) {
            val classifier = type.classifier
            if (classifier is IrTypeParameterSymbol) {
                // 泛型参数在签名里写作 T<名字>;，不含上界。
                val name = classifier.owner.name.toString()
                return "T$name;"
            }
            val name = symbolToName[classifier]
            if (name != null) {
                // 类型变量在泛型签名里写作 `T<名字>;`（如 T → TT;），而不是引用描述符；
                // 否则 `List<T>` 会被拼成非法的 `Ljava/util/List<LT;>;`。
                if (isTypeVariableLike(name)) return "T$name;"
                val mapped = builtinTypes[name.lowercase()] ?: name
                // 数组名已是描述符，泛型签名里数组不参与实参展开。
                if (mapped.startsWith("[")) return mapped
                val desc = jvmDescriptor(mapped)
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    // 只对引用类型展开泛型实参；基本类型描述符没有 <> 形式。
                    val internal = desc.substring(1, desc.length - 1)
                    if (type.arguments.isEmpty()) {
                        return "L$internal;"
                    } else {
                        val args = type.arguments.joinToString("") { irTypeArgumentSignature(it) }
                        return "L$internal<$args>;"
                    }
                }
                return desc
            }
        }
        return "Ljava/lang/Object;"
    }

    /**
     * 做什么：IR 类型实参 → JVM 签名片段，带变型前缀（`+` out / `-` in / 空）。
     *
     * 什么时候用：[irTypeSignature] 展开泛型实参时。
     * 关键边界：泛型签名里的类型实参**必须是引用类型**，因此基本类型描述符
     *   （I/J/F/D/Z/B/S/C）会先装箱为对应包装类（如 `I` → `Ljava/lang/Integer;`），
     *   否则会写出非法的 `<I>` 签名（`Box<int32>` 的实参即此情形）。
     * 失败模式：星投影 → `*`；其它未知 IrTypeArgument → `*`。
     */
    fun irTypeArgumentSignature(arg: IrTypeArgument): String {
        return when (arg) {
            is IrStarProjection -> "*"
            is IrTypeProjection -> {
                val prefix = when (arg.variance) {
                    Variance.OUT_VARIANCE -> "+"
                    Variance.IN_VARIANCE -> "-"
                    else -> ""
                }
                val raw = irTypeSignature(arg.type)
                // 基本类型实参在签名位必须装箱为引用类型，否则签名非法。
                val sig = primitiveBoxedInternalNames[raw]?.let { "L$it;" } ?: raw
                "$prefix$sig"
            }
            else -> "*"
        }
    }

    // 基本类型描述符 → 对应 JVM 包装类内部名。仅用于泛型签名位（`<...>`)：
    // 那里必须是引用类型，故把基本类型装箱后再写。与后端 emitBoxingIfNeeded 的
    // 映射语义一致，但这里是**签名文本**层面的映射，不产生字节码。
    private val primitiveBoxedInternalNames = mapOf(
        "I" to "java/lang/Integer",
        "J" to "java/lang/Long",
        "F" to "java/lang/Float",
        "D" to "java/lang/Double",
        "Z" to "java/lang/Boolean",
        "B" to "java/lang/Byte",
        "S" to "java/lang/Short",
        "C" to "java/lang/Character"
    )

    /**
     * 做什么：IR 类型参数 → JVM 签名里的「类型参数声明」片段（`名字:上界签名`）。
     *
     * 什么时候用：[buildClassSignature] / [buildMethodSignature] 声明形参时。
     * 关键边界：取 superTypes.first() 作为上界；无上界时用 Ljava/lang/Object;。
     * 失败模式：不校验上界个数（多上界只取第一个，属已知近似）。
     */
    fun irTypeParameterSignature(tp: IrTypeParameter): String {
        val sb = StringBuilder()
        sb.append(tp.name.toString())
        sb.append(':')
        if (tp.superTypes.isNotEmpty()) {
            sb.append(irTypeSignature(tp.superTypes.first()))
        } else {
            sb.append("Ljava/lang/Object;")
        }
        return sb.toString()
    }

    /**
     * 做什么：为泛型类生成 JVM 类签名 `<形参...>L父类内部名;`。
     *
     * 什么时候用：JvmIrBackend 写类头时，仅当类带类型参数。
     * 失败模式：类无类型参数时返回 null（调用方据此不写 Signature 属性，属正常路径）。
     */
    fun buildClassSignature(cls: IrClass, superClassInternal: String): String? {
        if (cls.typeParameters.isEmpty()) return null
        val sb = StringBuilder()
        sb.append('<')
        for (tp in cls.typeParameters) {
            sb.append(irTypeParameterSignature(tp))
        }
        sb.append('>')
        sb.append('L')
        sb.append(superClassInternal)
        sb.append(';')
        return sb.toString()
    }

    /**
     * 做什么：为泛型方法生成 JVM 方法签名 `<形参...>(参数...)返回`。
     *
     * 什么时候用：JvmIrBackend 写方法时，方法自身带类型参数或参数/返回含泛型实参。
     * 失败模式：方法无类型参数时返回 null（此时若仅参数含泛型，由调用方按别的判据决定是否写签名）。
     */
    fun buildMethodSignature(func: IrSimpleFunction): String? {
        if (func.typeParameters.isEmpty()) return null
        val sb = StringBuilder()
        sb.append('<')
        for (tp in func.typeParameters) {
            sb.append(irTypeParameterSignature(tp))
        }
        sb.append('>')
        sb.append('(')
        for (p in func.valueParameters) {
            sb.append(irTypeSignature(p.type))
        }
        sb.append(')')
        sb.append(irTypeSignature(func.returnType))
        return sb.toString()
    }

    /**
     * 做什么：为字段生成 JVM 字段签名，仅当字段类型是带实参的引用类型。
     *
     * 什么时候用：JvmIrBackend 写字段时。
     * 失败模式：字段类型非 IrSimpleType 或无泛型实参时返回 null（表示无需签名）。
     */
    fun buildFieldSignature(field: IrField): String? {
        val type = field.type
        if (type is IrSimpleType && type.arguments.isNotEmpty()) {
            return irTypeSignature(type)
        }
        return null
    }

    /**
     * 做什么：判断方法的返回类型或任一声参类型是否「带泛型实参」，用于决定是否写方法签名。
     *
     * 什么时候用：JvmIrBackend 决定是否为方法生成 Signature 属性时。
     * 注意：只检查是否有实参，不检查方法自身的 typeParameters（后者由调用方另行判断）。
     * 失败模式：无（纯查询），不匹配即为 false。
     */
    fun hasGenericParams(func: IrSimpleFunction): Boolean {
        val retType = func.returnType
        if (retType is IrSimpleType && retType.arguments.isNotEmpty()) return true
        for (p in func.valueParameters) {
            val paramType = p.type
            if (paramType is IrSimpleType && paramType.arguments.isNotEmpty()) return true
        }
        return false
    }

}
