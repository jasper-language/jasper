// ============================================================================
// KnownTypeNames.kt —— 「类型引用可见性」的显式白名单（checker 层）
// ============================================================================
// 职责：
//   集中登记「用户显式写出的类型名」在**没有任何 Jasper 声明或 import** 时仍应
//   被视为可解析的名字。只包含两类：
//     1) 语言/类型系统内置别名：parser 会把它们建成 JasNamedType 而不是
//        JasPrimitiveType/JasStringType 等单例（例如大写 `String`、`Boolean`、
//        `Map`、`dict`），但类型系统本来就把它们当作内建类型处理。
//     2) 明确保留的 JDK 互操作类型白名单：确实需要按 JVM 类型直连、且不便用
//        Jasper 声明描述的名字（当前仅 `List`）。
//
// 所属层次：
//   checker 层。TypeChecker 依赖它做「名字是否内建/互操作」的判定；本文件不依赖
//   任何上层（translator/backend/compiler），保持依赖单向。
//
// 与谁协作：
//   - TypeChecker.isVisibleTypeName：先查本白名单，再查符号表声明 / import / 限定名。
//
// 对外契约：
//   - isBuiltinOrInterop(name): Boolean —— 大小写**敏感**，按源码原样匹配。
//
// 扩展点与纪律（重要）：
//   - 新增白名单条目必须给出「哪个真实程序/测试依赖它」的证据，并在下面注释里写清；
//   - 严禁把它当「未知就放行」的兜底：查不到就必须由 TypeChecker 报未知类型错误。
//     本白名单的存在恰恰是为了让「未知 → 报错」成为默认行为。
//
// 为什么单独成文件：把「哪些外部名字被显式认可」这一变化点收敛到唯一、可审计的
//   位置，避免散落在 TypeChecker 的多个分支里各写一份，也让审查者一眼看全。
// ============================================================================

package jasper.checker

/**
 * 类型引用可见性白名单（无状态单例）。
 *
 * 做什么：提供 [isBuiltinOrInterop]，判断一个**显式写出的简单类型名**是否属于
 *   语言内建别名或保留的 JDK 互操作类型。
 * 什么时候用：TypeChecker 校验显式类型引用、且该名字不是类型参数、没有本程序声明、
 *   没有显式 import、也不是限定名（不含 `.`）时调用。
 * 前置条件：无。
 * 失败模式：纯查询，不抛异常；不在表中即返回 false（交由 TypeChecker 报错）。
 * 为什么这样设计：白名单必须显式、可审计，且**不得**包含「未知就放行」的兜底语义。
 */
object KnownTypeNames {

    /**
     * 语言/类型系统内置别名。
     *
     * 为什么需要单独列出：以下名字在语法里被当作**标识符**解析，因此以
     * `JasNamedType` 形态进入类型检查（真正的内建基本类型走 `JasPrimitiveType`
     * 等单例，不会到这里）。但它们本就是类型系统承认的内建别名：
     *   - `Bool` / `Boolean` / `boolean`：归一为 bool（注意 lexer 只把小写 `bool`
     *     定义为关键字，首字母大写的写法是普通 Identifier，故会以 JasNamedType 出现）；
     *   - `Any`：顶类型别名（lexer 只把小写 `any` 定义为关键字）；
     *   - `Int`：归一为 int32（同 canonicalTypeName 的内建别名）；
     *   - `String`：TypeChecker.canonicalTypeName 归一为 string，翻译器映射到
     *     `java/lang/String`；
     *   - `Map` / `HashMap`：canonicalTypeName 与兼容性判定视为 map 家族；
     *   - `dict`：语法 `dict<K,V>` 的 AST 名（JasperAstBuilder 产出
     *     `JasNamedType("dict", ...)`）。
     *
     * 大小写敏感：`String` 是标识符，`string` 走 JasStringType，二者都不依赖本表。
     */
    private val LANGUAGE_ALIASES: Set<String> = setOf(
        "Bool",
        "Boolean",
        "boolean",
        "Int",
        "String",
        "Any",
        "Map",
        "HashMap",
        "dict"
    )

    /**
     * 保留的 JDK 互操作类型白名单（显式、可审计）。
     *
     * 证据（2026-10-18 全量排查 `probes/` 与测试用例）：
     *   - `List`：`probes/jdbc/MemoryJdbc.jas` 的 `buildNested(): List<List<int32>>`
     *     与 `probes/r2dbc/MemoryR2dbc.jas` 的 `buildNested(): List<List<Row>>`
     *     用未导入的 `List` 证明「嵌套泛型类型可解析」。注释已说明该函数**不在
     *     运行期构造**，因此保留它作为 `java.util.List` 的互操作引用，不改变探针
     *     行为，也不引入对不存在类的运行期引用（值不会被构造）。
     *   - `MutableList`：集合回归测试在**不装载 stdlib** 的独立编译模式下使用
     *     `var xs: MutableList<int32> = new MutableList<int32>()`，验证语言内建集合
     *     元数据不依赖标准库源码是否存在。本任务**不新增**标准库集合接口文件；
     *     `MutableList` 的成员表由 `BuiltinCollections` 提供，JVM 上落到
     *     `java.util.List`（`new` 落到 `java.util.ArrayList`）。
     *
     * 纪律：除已证实的 `List` / `MutableList` 外，**不**预先塞入其它 JDK 名字；
     *   后续若某个真实程序需要（例如 `Object`），必须在同一次改动中补证据并更新注释。
     */
    private val JDK_INTEROP: Set<String> = setOf(
        "List",
        "MutableList"
    )

    /**
     * 判断名字是否为语言内建别名或保留的 JDK 互操作类型。
     *
     * 什么时候用：TypeChecker 判定显式类型名可见性时，作为「内建/互操作」一步。
     * 前置条件：name 为源码原样写出的简单名（区分大小写）。
     * 失败模式：不在表中返回 false；调用方据此继续走符号表/import/限定名判定。
     * 为什么大小写敏感：语言里 `String`（别名）与 `string`（单例）、`Map` 与
     *   `map` 的语义不同，不能做大小写不敏感归一，否则会掩盖真正的拼写错误。
     */
    fun isBuiltinOrInterop(name: String): Boolean =
        name in LANGUAGE_ALIASES || name in JDK_INTEROP
}
