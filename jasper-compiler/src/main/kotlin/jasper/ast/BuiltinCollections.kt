// ============================================================================
// BuiltinCollections.kt —— 语言内建集合族的「唯一事实来源」
// ============================================================================
// 职责：
//   集中描述 List/MutableList/ArrayList 与 Map/dict/HashMap 这两类集合的：
//     1) 源码类型名到集合族（LIST/MAP）的归属；
//     2) 各集合族可用的成员（名字、形参种类、返回种类）；
//     3) 读取接收者泛型实参（元素/键/值）的规则；
//     4) `new` 表达式落到哪个 JVM 具体实现类。
//   本文件只做纯查询，不依赖 checker/translator/backend，也不生成任何代码。
//
// 所属层次：
//   ast 层（最低层之一）。checker 与 translator 都依赖它，因此放在 ast 可保证
//   「checker → ast」「translator → ast」两条依赖都单向、无环；绝不能把它放进
//   checker 再让 translator 反向依赖 checker。
//
// 与谁协作：
//   - TypeChecker：按「真实接收者类型」分派集合成员、校验参数、推断返回类型，
//     并拒绝集合上不存在的属性（例如把数组的 length 误用到 dict）；
//   - JasperToIrTranslator：识别集合调用并打上 `c:` 调用前缀、为 dict 字面量
//     计算具体实现类型、读取集合成员返回类型所需的键/值/元素类型；
//   - TypeMapper：把源码集合名映射到 JVM 的 java/util/List、java/util/Map、
//     java/util/HashMap 等（映射表在 TypeMapper，本文件只给语义事实）。
//
// 对外契约（稳定接口）：
//   - familyOf(name): CollectionFamily? —— 名字属于哪个集合族；
//   - member(receiverName, methodName): CollectionMemberSpec? —— 该接收者上是否
//     存在该方法（区分只读 List 与可变 MutableList）；
//   - elementType/keyType/valueType(receiver): JasType —— 取泛型实参；
//   - parameterTypes/returnType(spec, receiver): JasType —— 成员签名；
//   - allocationTypeName(name): String? —— `new` 的 JVM 具体实现类。
//
// 失败模式：
//   全部为纯查表/纯函数，不抛异常。未知名返回 null；缺失泛型实参时返回 any，
//   由调用方在「缺少类型实参」时保持宽松，而不是伪造一个错误的具体类型。
//
// 为什么这样设计：
//   「哪些名字是集合」「集合有哪些成员」是 checker 与 translator 必须共享的事实。
//   如果两边各写一份，新增一个成员或改名时必然漂移，出现「检查器放行、翻译器按
//   旧名字生成」的非法字节码。把这一变化点收敛到 ast 层的唯一表，既消除重复，
//   又避免为了共享而制造 checker ↔ translator 的循环依赖。
//
// 已知边界：
//   - v1 只建模 List/MutableList/ArrayList 与 Map/dict/HashMap；
//   - Set、Queue、Deque 等尚未进入语言集合族；
//   - Java 的 List.remove(int) 与 List.remove(Object) 重载在本层按「移除元素」
//     建模（参数种类 ELEMENT，返回 bool）；索引删除留待后续 API 设计明确；
//   - 不带泛型实参的集合（如裸 `dict`）成员返回 any，不做运行时类型恢复。
// ============================================================================

package jasper.ast

/**
 * 语言内建集合族。
 *
 * 做什么：把源码中的多个名字（接口名/实现名/别名）归一到两种语义族之一，
 *   供成员分派按「族」而不是按字符串前缀判断。
 * 什么时候用：checker 解析成员调用、translator 降低成员调用时。
 * 为什么只有两种：v1 的容器语义只有序列（LIST）与键值映射（MAP）；Set 等
 *   尚未纳入语言 API，提前建模会增加无测试覆盖的分支。
 */
enum class CollectionFamily {
    /** 有序序列：List / MutableList / ArrayList 及其 java.util 全限定写法。 */
    LIST,

    /** 键值映射：Map / dict / HashMap 及其 java.util 全限定写法。 */
    MAP
}

/**
 * 集合成员的形参「种类」。
 *
 * 做什么：不直接存具体 JasType，而是存「相对于接收者类型实参的角色」，
 *   这样同一个成员描述可以服务 `dict<string,int32>` 与 `List<Row>` 等所有实例。
 * 什么时候用：[BuiltinCollections.parameterTypes] 把角色解析成具体类型时。
 * 失败模式：纯枚举，无失败路径。
 */
enum class CollectionParameterKind {
    /** 序列下标，语言层固定为 int32。 */
    INDEX,

    /** 序列元素，取 LIST 的第 0 个类型实参。 */
    ELEMENT,

    /** 映射键，取 MAP 的第 0 个类型实参。 */
    KEY,

    /** 映射值，取 MAP 的第 1 个类型实参。 */
    VALUE
}

/**
 * 集合成员的返回「种类」。
 *
 * 做什么：与形参种类同理，用角色描述返回类型，避免为每个泛型实例复制签名。
 * 什么时候用：[BuiltinCollections.returnType] 解析实际返回类型时。
 * 失败模式：纯枚举，无失败路径。
 */
enum class CollectionReturnKind {
    /** int32（size 等）。 */
    INT,

    /** bool（containsKey/add/contains/remove 等）。 */
    BOOL,

    /** 序列元素。 */
    ELEMENT,

    /** 映射值。 */
    VALUE,

    /** 无法在语言层精确表达时退回 any（裸泛型集合）。 */
    ANY
}

/**
 * 一条集合成员声明。
 *
 * 做什么：把「方法名 + 形参角色列表 + 返回角色」绑定成一个不可变值。
 * 什么时候用：[BuiltinCollections.member] 命中后由 checker/translator 消费。
 * 失败模式：仅承载数据，不抛异常。
 * 为什么用数据类：三者同生同灭；分开查表容易出现「命中方法名却拿到别的签名」。
 */
data class CollectionMemberSpec(
    val name: String,
    val parameterKinds: List<CollectionParameterKind>,
    val returnKind: CollectionReturnKind
)

/**
 * 内建集合元数据查询表（无状态单例）。
 *
 * 做什么：回答「这个类型名是不是集合」「这个集合能不能调用这个方法」「参数/返回
 *   是什么类型」以及「new 应该实例化哪个 JVM 类」。
 * 什么时候用：类型检查与 IR 翻译；后端只消费 translator 已经编码好的 `c:` 调用，
 *   不直接依赖本对象（避免把语言语义泄漏到字节码层）。
 * 前置条件：无。
 * 失败模式：未知名字/未知成员返回 null；不抛异常。
 * 为什么用 object：元数据是只读的，单例可避免每个编译单元重复构造表。
 */
object BuiltinCollections {

    /**
     * 源码类型名 → 集合族。
     *
     * 为什么同时收录简单名与 java.util 全限定名：用户既可能写语言内建 `List<T>`，
     * 也可能写互操作类型 `java.util.ArrayList`。两者在 JVM 上是同一族，成员分派
     * 不应因为写法不同而失效。
     */
    private val families: Map<String, CollectionFamily> = mapOf(
        "List" to CollectionFamily.LIST,
        "MutableList" to CollectionFamily.LIST,
        "ArrayList" to CollectionFamily.LIST,
        "java.util.List" to CollectionFamily.LIST,
        "java.util.ArrayList" to CollectionFamily.LIST,
        "java.util.LinkedList" to CollectionFamily.LIST,

        "dict" to CollectionFamily.MAP,
        "Map" to CollectionFamily.MAP,
        "HashMap" to CollectionFamily.MAP,
        "java.util.Map" to CollectionFamily.MAP,
        "java.util.HashMap" to CollectionFamily.MAP,
        "java.util.LinkedHashMap" to CollectionFamily.MAP
    )

    /**
     * 只读序列名集合。
     *
     * 做什么：区分语言层的 `List<T>`（只读视图）与 `MutableList<T>`/JDK 实现（可写）。
     * 什么时候用：[member] 过滤 add/set/remove 等可变操作时。
     * 为什么只列 `List` 而不列 `java.util.List`：`java.util.List` 是**显式 JDK
     *   互操作类型**，Java 的 List 本身就提供 add/set/remove；把只读契约强加给它
     *   会让既有的跨语言调用（以及 `PlatformMethodDescriptorRegressionTest` 这类
     *   以 JDK 接口为目标回归 INVOKEINTERFACE 的用例）无法编译。只读是 Jasper
     *   语言 `List<T>` 的 API 契约，不是 JDK 接口的事实，二者必须区分。
     */
    private val readOnlyListNames: Set<String> = setOf(
        "List"
    )

    /** LIST 的成员表。只读操作放在这里；可变操作在 [member] 里按名字放行。 */
    private val listMembers: Map<String, CollectionMemberSpec> = mapOf(
        "size" to CollectionMemberSpec("size", emptyList(), CollectionReturnKind.INT),
        "get" to CollectionMemberSpec("get", listOf(CollectionParameterKind.INDEX), CollectionReturnKind.ELEMENT),
        "contains" to CollectionMemberSpec("contains", listOf(CollectionParameterKind.ELEMENT), CollectionReturnKind.BOOL)
    )

    /** MutableList 额外提供的成员；同样只描述语言可见 API。 */
    private val mutableListMembers: Map<String, CollectionMemberSpec> = mapOf(
        "add" to CollectionMemberSpec("add", listOf(CollectionParameterKind.ELEMENT), CollectionReturnKind.BOOL),
        "set" to CollectionMemberSpec(
            "set",
            listOf(CollectionParameterKind.INDEX, CollectionParameterKind.ELEMENT),
            CollectionReturnKind.ELEMENT
        ),
        "remove" to CollectionMemberSpec("remove", listOf(CollectionParameterKind.ELEMENT), CollectionReturnKind.BOOL)
    )

    /** MAP 的成员表。Map 在语言约定中是可变接口，故读写成员同表。 */
    private val mapMembers: Map<String, CollectionMemberSpec> = mapOf(
        "size" to CollectionMemberSpec("size", emptyList(), CollectionReturnKind.INT),
        "get" to CollectionMemberSpec("get", listOf(CollectionParameterKind.KEY), CollectionReturnKind.VALUE),
        "containsKey" to CollectionMemberSpec(
            "containsKey",
            listOf(CollectionParameterKind.KEY),
            CollectionReturnKind.BOOL
        ),
        "put" to CollectionMemberSpec(
            "put",
            listOf(CollectionParameterKind.KEY, CollectionParameterKind.VALUE),
            CollectionReturnKind.VALUE
        ),
        "remove" to CollectionMemberSpec("remove", listOf(CollectionParameterKind.KEY), CollectionReturnKind.VALUE)
    )

    /**
     * 做什么：判断一个源码类型名属于哪个集合族。
     * 什么时候用：checker/translator 在通用成员路径之前分流集合调用时。
     * 前置条件：name 是源码原样写出的类型名（区分大小写）。
     * 失败模式：不是内建集合返回 null；不抛异常。
     */
    fun familyOf(name: String): CollectionFamily? = families[name.replace('/', '.')]

    /**
     * 做什么：按接收者类型名与方法名查成员；只读 List 上不会返回 add/set/remove。
     * 什么时候用：checker 校验调用、translator 为合法调用生成 `c:` 调用名时。
     * 前置条件：receiverName 是集合名（可由 [familyOf] 先判定）。
     * 失败模式：未知成员/只读集合上的可变成员返回 null；不抛异常。
     */
    fun member(receiverName: String, methodName: String): CollectionMemberSpec? {
        val family = familyOf(receiverName) ?: return null
        return when (family) {
            CollectionFamily.LIST -> {
                listMembers[methodName]
                    ?: if (receiverName.replace('/', '.') !in readOnlyListNames) mutableListMembers[methodName] else null
            }
            CollectionFamily.MAP -> mapMembers[methodName]
        }
    }

    /**
     * 做什么：供**翻译阶段**查询成员签名，合并只读 List 与可变 List 的成员表。
     *
     * 什么时候用：translator 已经收到检查器放行的调用、只需要按 spec 生成 `c:` 调用时。
     * 前置条件：调用点已通过类型检查（可变性/参数类型均已校验）。
     * 失败模式：未知集合名/未知成员返回 null；不抛异常。
     * 为什么与 [member] 分开：IR 类型已经擦除了 `List` 与 `MutableList` 的区别（两者都
     *   落到 `java.util.List`），翻译器无法仅凭 IR 判断接收者是否为可变视图。若在翻译
     *   阶段再次用只读规则过滤，合法的 `MutableList.add` 会被误判为未知成员。把
     *   「可变性校验」留在检查器、把「签名查询」留给翻译器，两个阶段各自只做一次判断，
     *   避免同一规则在两处出现不一致的实现。
     */
    fun loweringMember(receiverName: String, methodName: String): CollectionMemberSpec? {
        return when (familyOf(receiverName)) {
            CollectionFamily.LIST -> listMembers[methodName] ?: mutableListMembers[methodName]
            CollectionFamily.MAP -> mapMembers[methodName]
            null -> null
        }
    }

    /**
     * 做什么：判断一个名字是否是任一集合族暴露的成员名（不区分接收者族）。
     * 什么时候用：检查器在数组接收者上拦截「把集合 API 用到数组」的误用。
     * 前置条件：name 是源码中的方法名。
     * 失败模式：未知名字返回 false；不抛异常。
     * 为什么需要：数组与集合都表达「序列」，用户很容易写 `arr.size()` / `arr.get(i)`。
     *   数组在 JVM 上没有这些方法；若不在这里拦截，生成期会拼出不存在的方法并在
     *   运行期抛 NoSuchMethodError。数组的正确写法是 `arr.length` 与 `arr[i]`。
     */
    fun isCollectionMemberName(name: String): Boolean =
        listMembers.containsKey(name) ||
            mutableListMembers.containsKey(name) ||
            mapMembers.containsKey(name)

    /**
     * 做什么：取接收者的第 [index] 个类型实参；缺失或越界时返回 any。
     * 什么时候用：[elementType]/[keyType]/[valueType] 与 checker 的签名解析。
     * 前置条件：receiver 是集合类型（调用方应先用 [familyOf] 判定）。
     * 失败模式：缺失实参时返回 [JasAnyType]，保持裸泛型集合的宽松语义。
     */
    fun typeArgument(receiver: JasNamedType, index: Int): JasType =
        receiver.typeArguments.getOrNull(index) ?: JasAnyType

    /** 取 LIST 的元素类型；裸 `List` 返回 any。 */
    fun elementType(receiver: JasNamedType): JasType = typeArgument(receiver, 0)

    /** 取 MAP 的键类型；裸 `Map`/`dict` 返回 any。 */
    fun keyType(receiver: JasNamedType): JasType = typeArgument(receiver, 0)

    /** 取 MAP 的值类型；裸 `Map`/`dict` 返回 any。 */
    fun valueType(receiver: JasNamedType): JasType = typeArgument(receiver, 1)

    /**
     * 做什么：把成员声明的形参角色解析成针对具体接收者的 JasType 列表。
     * 什么时候用：checker 校验实参类型。
     * 前置条件：spec 来自 [member]；receiver 是集合类型。
     * 失败模式：角色缺少对应类型实参时得到 any；不抛异常。
     */
    fun parameterTypes(spec: CollectionMemberSpec, receiver: JasNamedType): List<JasType> =
        spec.parameterKinds.map { kind ->
            when (kind) {
                CollectionParameterKind.INDEX -> JasPrimitiveType("int32")
                CollectionParameterKind.ELEMENT -> elementType(receiver)
                CollectionParameterKind.KEY -> keyType(receiver)
                CollectionParameterKind.VALUE -> valueType(receiver)
            }
        }

    /**
     * 做什么：把成员声明的返回角色解析成针对具体接收者的 JasType。
     * 什么时候用：checker 推断表达式类型、translator 计算 IR 调用点类型。
     * 前置条件：spec 来自 [member]；receiver 是集合类型。
     * 失败模式：角色缺少对应类型实参时得到 any；不抛异常。
     */
    fun returnType(spec: CollectionMemberSpec, receiver: JasNamedType): JasType =
        when (spec.returnKind) {
            CollectionReturnKind.INT -> JasPrimitiveType("int32")
            CollectionReturnKind.BOOL -> JasPrimitiveType("bool")
            CollectionReturnKind.ELEMENT -> elementType(receiver)
            CollectionReturnKind.VALUE -> valueType(receiver)
            CollectionReturnKind.ANY -> JasAnyType
        }

    /**
     * 做什么：给出 `new` 表达式应实例化的 JVM 具体类型名；不需要改写的类型返回 null。
     *
     * 什么时候用：translator 处理 `new` 时。接口不能直接 NEW，因此 `new List` /
     *   `new MutableList` 必须落到 ArrayList，`new Map` / `new dict` 落到 HashMap。
     * 前置条件：name 是源码类型名。
     * 失败模式：不是需要改写的集合名返回 null，调用方保持原类型；不抛异常。
     * 为什么不让翻译器写死：实例化策略与集合族元数据同源，后续调整默认实现只改这里。
     */
    fun allocationTypeName(name: String): String? = when (name) {
        "List", "MutableList", "java.util.List" -> "java.util.ArrayList"
        "Map", "dict", "java.util.Map" -> "java.util.HashMap"
        else -> null
    }
}
