# Jasper JPA 探针发现报告

本报告记录在 `probes/jpa/` 中用 Jasper 实现一个 **JPA 形状 API + 内存假实现**
（实体映射、注解驱动元数据、泛型仓储、生命周期回调）时遇到的语法 / 标准库 /
编译器摩擦点。所有结论都来自真实编译管线
（ANTLR → AST → 注解处理 → 符号表 → 类型检查 → Kotlin IR → JVM 字节码）的实测，
条目中的代码片段均为可独立复现的最小用例。

本探针压的是 **注解体系与泛型**：JDBC 探针压同步 IO，R2DBC 探针压异步流，
本探针压「实体 / 标识 / 关系 / 仓储 / 回调」这一族。

## 一、探针覆盖范围

`probes/jpa/` 描述了一个自洽的 JPA 形状（全部 `package probes.jpa;`）：

| 文件 | 内容 |
| --- | --- |
| `Annotations.jas` | 注解族：`@Entity` `@Table` `@Id` `@Column` `@GeneratedValue` `@OneToMany` `@ManyToOne` `@PrePersist` `@PostLoad` |
| `Contracts.jas` | 运行期可用的实体契约：`Identifiable`（主键投影）、`Lifecycled`（回调投影） |
| `Person.jas` | `@OneToMany` 侧聚合根实体（`addresses: Address[]`） |
| `Address.jas` | `@ManyToOne` 侧实体（`personId: int32` 外键） |
| `EntityManager.jas` | `EntityManager` 接口 + `MemoryEntityManager`（persist/find/removeById/flush/managedCount） |
| `Repository.jas` | 泛型 `Repository<T, ID>` + 窄接口 `PersonRepository` + 两个内存实现 |
| `Mapping.jas` | 实体映射元数据 `EntityMetadata` + `MappingRegistry`（缺主键 → 明确报错） |
| `JpaException.jas` | 错误层次：`JpaException` / `JpaMappingException`（独立包 `probes.jpa.error`，`import std.Error`） |
| `Main.jas` | 驱动器 `main()` + 观测函数 `roundTripName()` / `addressTraversal()` / `missingIdError()` / `missingIdGuard()` |

覆盖到的语言特性：注解类型（`@interface`）与注解使用、注解命名实参、
运行时注解索引、泛型接口与具体类型实参实现、`any` + `cast`、接口多实现、
用户类对象数组、跨包 `import` 与继承 `std.Error`、`try`/`catch`/`throw`、
`and`、字符串 `==` 与拼接、数组长度与遍历、`while`。

**验收结果**：`jasper.JpaProbeTest` 通过——
`probes.jpa.main.main()` 返回 `1151`；`roundTripName()` 返回 `"ada"`；
`addressTraversal()` 返回 `22`；`missingIdError()` 返回
`Entity 'Legacy' declares no @Id mapping`；注解索引记录了全部实体注解。

## 二、严重度与类别

- **严重度**
  - `[表达不了]`：该 JPA 形状无法用当前语言直接写出等价代码，必须改设计或大幅规避。
  - `[很啰嗦]`：能写，但需要显著的变形、冗余或人为排序。
  - `[略有不便]`：小问题，有简单的绕过写法。
- **类别**
  - `①语法层`：解析器/语法规则不接受或错误吞掉。
  - `②标准库缺失`：缺少可直接使用的类型、方法或语言级能力。
  - `③编译器缺陷`：能通过语法与类型检查，但生成错误字节码或错误语义。

## 三、统计

| 严重度 | 数量 | 其中 ①语法层 | ②标准库 | ③编译器缺陷 |
| --- | --- | --- | --- | --- |
| 表达不了 | 5 | 0 | 1 | 4 |
| 很啰嗦 | 7 | 1 | 1 | 5 |
| 略有不便 | 1 | 1 | 0 | 0 |
| **合计** | **13** | **2** | **2** | **9** |

> 后续更新：**#1、#13 与 #11 已修复（本提交）**——#1：泛型擦除 / 桥接方法 / 接口分派修复
> （`Repository<T, ID>` 现可经接口类型变量分派）；#13：显式类型引用不可解析时改为编译期
> 报错（带名字与位置）；#11：枚举常量的比较 / `switch` / `match` 不再触发内部错误
> （枚举常量改为 `IrEnumEntry` + `<clinit>` 初始化）。因此当前仍开放的 `③编译器缺陷`
> 为 6 条。上表保留本次探针首次记录时的原始统计。
>
> **Q25 / C6+C16** 另修复 `③编译器缺陷` #3（跨文件读取公有字段生成非法字段描述符，
> 与 R2DBC #5 同源）：字段符号/类型改为在第二遍翻译前按类统一预登记，声明处复用同一
> `IrFieldSymbol` 身份，文件顺序不再影响字段解析。因此当前开放的 `③编译器缺陷` 由 6 条
> 降为 5 条；回归守门见 `jasper.CrossFileFieldTest`。

> 说明：本探针是新增探针，**未修改任何 `.g4`、未改编译器**。下列条目的大部分
> 与 JDBC/R2DBC 探针的既有发现同源（#6 枚举、#8 静态字段、#9 类继承、#16 dict），
> 本报告在「JPA 视角」下复核并给出 JPA 形状的具体后果；同源条目标注了引用。

## 四、探针为通过验收而采用的规避（13 处，均为 FINDINGS 的输入）

1. **泛型仓储拆成「理想泛型接口 + 可运行窄接口」两份**：`Repository<T, ID>` 只声明，
   运行期分派改用 `PersonRepository`；`MemoryGenericRepository`（`implements
   Repository<Person, int32>`）只经**具体类型接收者**调用。原因见 #1。
   （**#1 已修复（本提交）**：该规避现已不再必要，泛型接口可经接口变量分派；探针源保留
   原写法以保留历史证据，未在本轮改写。）
2. **删除方法命名 `removeById`**，不用 JPA 惯用的 `remove`。原因见 #7。
3. **不用 `return null`**：`find()` 查不到时返回数组首元素占位。原因见 #2。
4. **跨文件字段一律走访问器方法**（`fullName()`/`addressList()`/`ownerId()`/…），
   公有字段只声明不跨文件读取。原因见 #3。
5. **生命周期回调用 `Lifecycled` 接口承担**，`@PrePersist`/`@PostLoad` 仅作编译期
   标注。原因见 #5。
6. **缺 @Id 的校验下移到运行期** `MappingRegistry.register()`（手写元数据）。原因见 #6。
7. **元数据/表用显式类 + 对象数组**，不用 `dict<string, …>`。原因见 #8（同 JDBC #16）。
8. **自增计数器用实例字段**（`nextId`），不用静态字段。原因见 #10（同 JDBC #8）。
9. ~~**`@GeneratedValue.strategy` 用 `string`** 而非枚举~~ —— **本提交已修复**（#11，
   同 JDBC #6），可直接使用枚举；探针保留字符串写法仅为未回归改写。
10. **关系用「对象数组 + 主键引用」单向表达**，不写对象双向引用、不做实体继承多态。
    原因见 #4（同 R2DBC #9）。
11. **`Main.jas` 显式 `import std.Error;`**：非 `std` 包内 `Error` 不会自动可见，
    未 import 时现在会**在编译期**报 `Unknown type 'Error' (did you mean to import
    std.Error?)`（修复前是运行期 `NoClassDefFoundError`）。原因见 #13。
12. **`Person.jas`/`Address.jas` 所有字段配访问器**（规避 #3 的连带）。
13. **`missingIdError()` 用常量消息比对**，不依赖 `f"..."` 运行期插值
    （f-string 仅在编译测试中被覆盖，运行期未验证；本探针未采用）。

## 五、发现明细

### 表达不了

#### 1. 泛型接口方法经接口类型变量调用生成错误描述符
- 类别：`③编译器缺陷`（泛型 / 注解体系的核心摩擦）
- 状态：**已修复（本提交）**——根因是 JVM 泛型擦除与桥接方法缺失：类型变量（含 `ID` 这类
  多字母形参，按类型声明预登记）现按擦除规则落到 `java/lang/Object`（不再写出非法 `LT;`），
  编译期为泛型接口实现类自动补 `ACC_PUBLIC|ACC_BRIDGE|ACC_SYNTHETIC` 桥接方法，调用点改用
  「接口声明的擦除后描述符 + 返回处 `CHECKCAST` / 基本类型装箱拆箱」。故 `Repository<T, ID>`
  可经接口类型变量直接分派（原触发代码现可运行）。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/GenericDispatchTest.kt`（真编译 + `URLClassLoader`
  加载执行 + `javap` 字节码形状断言）。以下「实测」为修复前证据，保留作历史记录。
  v1 已知边界：有界类型变量仍擦除为 `Object`；通配符 / F-bounded / 多级泛型继承未覆盖；
  接口与实现之间「引用形参 ↔ 基本类型形参」以外的方向仍不生成桥（宁可留
  `AbstractMethodError` 也不生成非法字节码）。
- 现象：`Repository<T, ID>` 的实例赋给接口类型变量后调用其方法，运行期找不到方法；
  含 `T` 返回值时更严重（描述符里出现裸类型变量名 `T`）。
- 触发代码：
```jasper
package p;
public interface Repository<T, ID> {
    public function save(entity: T): void;
    public function findById(id: ID): T;
    public function count(): int32;
}
public class Person { public var id: int32; public constructor(id: int32) { this.id = id; } }
public class MemoryRepo implements Repository<Person, int32> {
    public function save(entity: Person): void { }
    public function findById(id: int32): Person { return new Person(id); }
    public function count(): int32 { return 1; }
}
public function main(): int32 {
    var repo: Repository<Person, int32> = new MemoryRepo();   // 接口类型变量
    repo.save(new Person(1));                                 // 运行期失败
    return repo.count();
}
```
- 期望：接口类型接收者上正常分派 `save(Person)` / `findById(int32): Person`。
- 实测：`NoSuchMethodError: 'void p.Repository.save(p.Person)'`；
  若接口方法返回 `T`，错误更早出现为 `ClassNotFoundException: T`
  （描述符残留类型变量 `LT;`，与 R2DBC 探针 #3 同源）。
- 补充实测（重要，决定规避方案）：把实现赋给**具体类型**变量再调用则**完全正常**——
  `var repo: MemoryRepo = new MemoryRepo(); repo.save(...)` 正常；
  `class MemoryRepo implements Repository<Person, int32>` 也可加载运行。
  即「泛型接口可声明、可实现，但不可经接口变量分派」。
- 探针规避：见第四节第 1 条（拆成 `Repository<T, ID>`（声明）+ `PersonRepository`
  （运行期分派）+ `MemoryGenericRepository`（具体类型调用））。

#### 2. `return null`（返回 `any` 的函数）生成非法字节码
- 类别：`③编译器缺陷`
- 状态：**已修复（Q26 / C9）**——根因在后端 `JvmIrBackend.isIrVoidExpr`：translator 把
  「无返回值占位」（`typeMapper.unitExpr()`，IR 类型 `kotlin.Unit`，JVM 描述符 `V`）和
  源码的 `null` 字面量（`typeMapper.nullConst()`，IR 类型 `kotlin.Any?`，描述符
  `Ljava/lang/Object;`）都编码成 `IrConstKind.Null`；旧判据只看 `kind == Null`，于是
  `return null` 被当成无值，只发裸 `areturn`。现判据改为「JVM 返回描述符是否为 `V`」：
  只有 Unit/void 占位算无值，可空引用（含 `any`）一律先压 `ACONST_NULL`。修复后
  `return null` 的 false 分支字节码为 `aconst_null; areturn`。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/NullReturnTest.kt`（真编译 + `URLClassLoader`
  执行 + `javap` 形状断言）。以下「实测」为修复前证据，保留作历史记录。
- 触发代码：
```jasper
package p;
public class Node { public var code: int32; public constructor(code: int32) { this.code = code; } }
function maybe(flag: bool): any {
    if (flag) { return new Node(1); }
    return null;                    // 关键行
}
public function main(): int32 { var x: any = maybe(false); return 0; }
```
- 期望：`return null;` 生成 `aconst_null; areturn`。
- 修复前实测：`VerifyError: Operand stack underflow`
  `at maybe.maybe(Z)Ljava/lang/Object; @16: areturn`（反汇编中该分支只有一个裸
  `areturn`，缺 `aconst_null`）。
- 修复后实测：`maybe(false)` 正常返回 `null`；`javap -c` 显示该分支
  `aconst_null` 后紧跟 `areturn`。
- 探针规避（**已移除**）：返回 `any` 的 `find` 可直接返回 `null`，不再需要数组首元素占位。

#### 3. 跨文件读取公有字段生成非法字段描述符
- 类别：`③编译器缺陷`（同 R2DBC #5 复核）
- 状态：**已修复（Q25 / C6+C16，与 R2DBC #5 同源）**——字段符号/类型此前只在声明文件被翻译
  时才登记，访问点先翻译时只能拿到临时/空符号，后端按符号反查不到名字，最终写出伪字段
  `field`（签名 `V`）。现由 `JasperToIrTranslator.predeclareFields` 在第二遍翻译前对所有文件的
  class/interface/enum 字段（实例、静态、接口常量）统一预登记，声明处复用同一
  `IrFieldSymbol`，故跨文件/跨包、任意文件顺序都能解析到正确的 owner/描述符。
  回归守门：`jasper.CrossFileFieldTest`。以下「实测」为修复前证据，保留作历史记录。
- 触发代码（`Person.jas` 与读取者分处两文件）：
```jasper
// Person.jas
package p;
public class Person {
    public var id: int32;
    public constructor(id: int32) { this.id = id; }
}
```
```jasper
// Main.jas（读取另一个文件里声明的公有字段）
package p;
public function main(): int32 {
    var p: Person = new Person(7);
    return p.id;                    // 关键行
}
```
- 期望：跨文件读取公有字段正常。
- 实测：`ClassFormatError: Field "field" in class p/main has illegal signature "V"`
  （生成名为 `field`、签名 `V` 的伪字段访问）。**实测 `Main.jas` 排在 `Person.jas`
  前后都失败**——本探针与 R2DBC 报告的「依赖文件顺序」不同，此处似乎恒坏；
  无论如何，JPA 实体风格（公有字段）跨文件读取不可用。
- 探针规避：见第四节第 4/12 条（所有实体字段配访问器方法）。
  （**已修复（Q25 / C6+C16）**：该规避现已不再必要；探针源保留原写法以保留历史证据，
  未在本轮改写。）

#### 4. 类继承不参与赋值子类型（实体继承形状不可用）
- 类别：`③编译器缺陷`（同 R2DBC #9 复核）
- 状态：**已修复（本提交）**——与 R2DBC #9 同源：`TypeChecker.classExtends` + `isSubtype`
  传递闭包让类继承进入子类型关系；子类实例可赋给基类变量并虚分派。回归守门见
  `InheritanceTest`（虚分派 / 三层继承 / 接口+继承组合 / 继承字段 / 描述符 / 跨文件）。
  实体继承（`@Inheritance`、`@MappedSuperclass`）的**元数据**部分（#5/#6 的运行期注解读取、
  缺 `@Id` 校验）仍未覆盖，属另两条独立缺陷，不在本次范围内。
- 触发代码：
```jasper
package p;
public class Base { public function tag(): int32 { return 1; } }
public class Derived extends Base { public function tag(): int32 { return 2; } }
public function main(): int32 { var b: Base = new Derived(); return b.tag(); }
```
- 期望：子类实例可赋给基类变量（JPA `@Inheritance`、`@MappedSuperclass` 的基础）。
- 实测：`Type mismatch: cannot assign Derived to variable of type Base`（编译期）。
- 探针规避：见第四节第 10 条（多态 / 关系只走接口或多态窄接口，不做实体继承）。

#### 5. 运行期注解反射缺失：注解无法驱动生命周期与映射
- 类别：`②标准库缺失`（语言级能力缺口）
- 现象：注解类型（`@interface`）可声明、可在类/属性/方法上使用、可被**编译期**
  `AnnotationIndex` 记录；但**运行期没有任何 API 能读取它们**。因此：
  `@PrePersist`/`@PostLoad` 不会自动触发；`@Id`/`@Column`/`@Table` 不会被自动
  推导成映射元数据。
- 期望：实体注册时按 `@Id`/`@PrePersist` 自动装配（JPA/Hibernate 的语义）。
- 实测：无对应能力（无反射 API、无 `.jaso` 运行期注解读取）。探针只能：
  注解作「编译期文档」，运行期行为改由 `Identifiable`/`Lifecycled` 接口 +
  手写 `EntityMetadata` 承担（注解与运行期行为必须**手工保持一致**）。
- 探针规避：见第四节第 5/6 条。

#### 6. 编译期不校验实体注解：缺 `@Id` 不会在编译期报错
- 类别：`③编译器缺陷`（注解处理阶段只识别它认识的四类注解）
- 现象：`AnnotationProcessor` 只对 `@Getter`/`@Setter`/`@EqualsAndHashCode`/`@Mapper`
  有语义分派；其余注解（含 `@Entity`/`@Id`）一律「只登记、不校验」。
  于是「实体缺 `@Id`」既不是编译错误、也不会产生任何警告。
- 触发代码：任意 `@Entity` 类，不写 `@Id` —— 正常编译通过（静默）。
- 期望：至少给出「实体缺少标识」的编译期（或注解处理阶段）诊断；
  否则应明确「注解不在扫描范围内」。
- 实测：静默通过。
- 探针规避：见第四节第 6 条（运行期 `MappingRegistry` 校验 `idField` 非空并抛
  `JpaMappingException`，即验收点 ③ 的落点）。

### 很啰嗦

#### 7. 名为 `remove` 的方法（接口接收者）生成非法字节码
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package p;
public interface Store { public function remove(name: string, id: int32): bool; }
public class MemoryStore implements Store {
    public function remove(name: string, id: int32): bool { return id > 0; }
}
public function main(): int32 {
    var s: Store = new MemoryStore();
    if (s.remove("Person", 1)) { return 100; }   // 关键行
    return 0;
}
```
- 期望：`remove` 作为普通方法名正常分派（且返回 `bool`）。
- 实测：`VerifyError: Bad type on operand stack`
  （`ifeq` 处操作数栈上是接收者 `p/Store` 而非 `bool`；方法描述符被按接收者类型
  生成）。**与返回类型无关**：返回 `int32` 的 `remove` 同样失败。
  对照：返回 `bool`/`int32` 的 `exists`/`empty`/`removeById`/`flush` 均正常。
- 探针规避：见第四节第 2 条（`EntityManager.removeById`）。

#### 8. `dict<K, V>` 不能作类型 → 注册表/映射表只能用数组
- 类别：`②标准库缺失`（同 JDBC #16 / R2DBC #16 复核）
- 现象：JPA 天然需要 `Map<实体名, 元数据>` / `Map<主键, 实体>`；本语言 `dict{...}`
  字面量可构造，但 `dict<K,V>` 不能作**类型**，集合的 `get/size` 也会被后端的
  数组特判误伤。
- 探针规避：见第四节第 7 条（`MappingRegistry` 用 `EntityMetadata[]` + 计数；
  `MemoryEntityManager` 用 `any[]` + `Identifiable.entityName()` 判别）。

#### 9. 注解语法细节：命名实参被解析为「位置参数里包着赋值」，且无元注解
- 类别：`①语法层`
- 状态：**解析部分已修复**——AST 构建层（`JasperAstBuilder.parseAnnotationArgs`）现把
  「位置参数里包着 `name = value`」归一化为 `JasAnnotationNamedArg`，覆盖纯位置、
  位置+命名混合、纯命名三种形态；嵌套注解（`@Outer(inner = @Inner(value = 1))`）经
  `parseAnnotationArgValue` 递归返回 `JasAnnotationLiteral`，其内部实参同样归一化。
  `AnnotationProcessor.annotationStringArgs` 的主路径现在只处理 `JasAnnotationNamedArg`，
  位置-赋值分支**仅作防御**（兼容以其它方式构造的 AST）。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/AnnotationArgumentsTest.kt`。
  **仍未做**：`@interface` 的 `@Target`/`@Retention` 元注解，以及「注解元素类型必须是常量」
  的强制校验（Q23 / C18 的另一半）。
- 现象（修复前，保留作历史记录）：
  - `@Table(name = "person")` 未走语法专有的 `annotationNamedArgument` 规则，
    而是被 ANTLR 判为位置参数分支，到 AST 里变成 `JasAnnotationPositionalArg(赋值)`。
    （编译器已有一个适配器 `annotationStringArgs` 吸收它，见
    `AnnotationProcessor.annotationStringArgs`；但读取注解实参的每个消费者都得
    复制这段适配逻辑。）
  - `@interface` 没有 `@Target`/`@Retention` 等元注解，也没有「注解元素类型必须是
    常量」的强制校验；注解参数的语义完全靠约定。
- 期望：命名实参走命名参数规则；有基本的元注解/取值校验。
- 实测：如上述。
- 探针规避：探针不读取注解实参（`JpaProbeTest` 只断言注解**名字**与目标）。

#### 10. 静态字段不可用 → 全局自增/注册中心只能实例化
- 类别：`③编译器缺陷`（同 JDBC #8 复核）
- 状态：**已修复（本提交）**——类静态字段现生成 `GETSTATIC`/`PUTSTATIC`，字段带
  `ACC_STATIC`；`static var` 的声明初值在 `<clinit>` 中执行，多个实例与类名访问共享同一份值。
  JPA 的全局自增序列/注册中心可直接用 `static var` 表达。回归见 `jasper.StaticFieldTest`。
  以下「现象」为修复前证据，保留作历史记录。
- 现象：`@GeneratedValue` 的全局自增序列、`MappingRegistry` 的全局注册表，在 JPA
  实现里天然是静态单例；当前静态字段读写生成 `GETFIELD` 而非 `GETSTATIC`，
  运行期 NPE。
- 探针规避：见第四节第 8 条（`nextId` 等全部做实例字段，由调用方持有单例）。

#### 11. 枚举不可比较 → `@GeneratedValue` 策略只能用 `string`
- 类别：`③编译器缺陷`（同 JDBC #6 复核）
- 状态：**已修复（本提交）**——根因与 JDBC #6 同源：枚举常量被当成实例字段读取，
  编译期崩于 `INTERNAL: Index -1 out of bounds for length 0`。现改为 translator 预登记
  枚举常量符号并保持 `IrEnumEntry` 表示、读取走新增 `IrGetEnumValue`（后端发 `GETSTATIC`），
  后端据 `IrEnumEntry` 发射 `public static final ACC_ENUM` 字段并在 `<clinit>` 中
  `PUTSTATIC` 初始化；`switch`/`match`/比较/形参/返回值均恢复可用，`@GeneratedValue`
  现在可以改用真正的枚举 `GenerationType`。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/EnumTest.kt`。
- 现象：JPA 用 `GenerationType { IDENTITY, SEQUENCE, TABLE, AUTO }`；本语言枚举一旦
  参与比较或在 `switch`/`match` 中使用即触发编译器内部错误。
- 探针规避：见第四节第 9 条（`@GeneratedValue(strategy = "identity")` 用字符串）。

#### 12. ~~`label` 是硬保留关键字~~（归入「略有不便」，见 #14）

#### 13. 非 `std` 包内 `Error` 不自动可见，且未知类型不报编译错
- 类别：`③编译器缺陷`
- 状态：**已修复（本提交）**——`TypeChecker` 现对**用户显式写出的类型引用**做可见性
  校验：名字必须来自「本程序声明且同包 / 显式 `import` / 语言内建别名 / 保留的 JDK
  互操作类型 / 限定名」之一，否则在编译期报
  `Unknown type 'Error' (did you mean to import std.Error?)`（带文件/行/列），
  并在翻译阶段之前中断，不再产出对不存在类的引用。为支持该判定，`SymbolTable` 新增
  `typePackages`（简单名 → 包集合）索引，区分「别包同名类型」与「本包可见类型」。
- 期望：标准库基类可自动导入，或未知名在编译期报错。
  - 裁决：采用后者——**显式类型引用未解析即编译报错**；不实现自动导入，保留
    `import std.Error;` 作为显式、可读的修复路径。
- 原现象（修复前）：在 `package probes.jpa;` 中写 `catch (e: Error)` 而**未**
  `import std.Error;`，编译器把 `Error` 解析为 `probes.jpa.Error`（一个根本不存在的
  类型）——没有报「未知类型」，而是照常生成引用，直到运行期反射 / 加载时才炸：
  `NoClassDefFoundError: probes/jpa/Error`。
- 实测（修复后）：同上代码在编译期即失败，且不产出任何 `.class`；显式
  `import std.Error;` 后正常编译。回归守门见
  `jasper-compiler/src/test/kotlin/jasper/UnknownTypeTest.kt`。
- 探针规避：`Main.jas` 显式 `import std.Error;`（现在也是编译通过的**必需**写法）。

### 略有不便

#### 14. `label` 是硬保留关键字，不能作方法/属性名
- 类别：`①语法层`
- 触发代码：
```jasper
package p;
public class Node { public function label(): string { return "n"; } }   // 语法错误
```
- 期望：`label` 只在与 `continue label` / `break label` 结合时是关键字。
- 实测：`Syntax error ... no viable alternative at input 'publicfunctionlabel'`。
- 规避：改名（本探针用 `tag()`；实体访问器同理避免 `label`）。

---

## 六、探针「想写但写不出」的 JPA 形状清单

以下 JPA 惯用写法在当前语言下无法直接表达，探针只能降级：

1. ~~**`Repository<T, ID>` 多态调用** —— 不可用（#1）~~：**本提交已修复**，泛型接口可经
   接口类型变量直接分派（原来只能声明、运行期走窄接口或具体类型）。
2. **`EntityManager.persist(Object)` 依赖运行期反射注解** —— 不可用（#5）：
   实体必须实现 `Identifiable`/`Lifecycled` 接口，元数据手写。
3. ~~**实体继承 / `@MappedSuperclass`** —— 不可用（#4）~~：**本提交已修复**类继承赋值子类型
   （子类实例可赋给基类变量并虚分派）；但实体继承的**元数据驱动**依赖运行期注解读取，仍
   受 #5/#6 阻塞（见下）。
4. **`Map<主键, 实体>` 一级缓存** —— 不可用（#8）：`dict<K,V>` 不能作类型。
5. **`@GeneratedValue` 全局序列（静态计数器）** —— 不可用（#10）：静态字段坏。
6. ~~**`GenerationType` 枚举** —— 不可用（#11）~~：**本提交已修复**，枚举已可比较、
   可在 `switch`/`match` 使用。
7. **实体公有字段的跨文件读取** —— 不可用（#3）：必须走访问器。
   （**#3 已修复（Q25 / C6+C16）**：该规避现已不再必要；探针源保留原写法以保留历史证据，
   未在本轮改写。）
8. **查询不到时返回 `null`** —— 不可用（#2）：`return null`（any）生成非法字节码。
9. **`remove` 作为方法名** —— 不可用（#7）：改名 `removeById`。
10. **注解实参的运行时读取** —— 不可用（#5）：注解只在编译期索引。

## 七、结论

- 探针可编译并运行，说明 Jasper 已具备「注解声明 + 注解索引 + 泛型接口声明 +
  具体类型实现 + `any`/`cast` + 关系数组 + 跨包错误层次 + 生命周期接口」的表达能力，
  足以拼出一个可运行的 JPA 假实现（验收点 ①②③ 全部真跑通过）。
- 最影响「写不出 JPA 形状」的是前 6 条：泛型接口分派（#1）、`return null`（#2）、
  跨文件字段（#3）、实体继承（#4）、运行期注解反射缺失（#5）、缺 `@Id` 无编译期
  校验（#6）。其中 #1/#5/#6 直接决定「注解驱动」能不能成立。（#1、#3 已修复。）
- 13 个摩擦点里有 5 个（原 9 个；#1、#13、#11 已随更早提交修复，#3 随 Q25 / C6+C16
  修复）是 `③编译器缺陷`，且多为
  **静默错误字节码**
  （`VerifyError`/`NoClassFormatError`/`NoSuchMethodError`/`ClassNotFoundException`），
  而不是清晰的编译期报错；这会让用户把编译器 bug 误当成自己代码的问题（与 JDBC/R2DBC
  报告同一结论）。
- 与另两个探针一致：**「理想形状」与「可运行形状」必须分开写**。本探针把这一点
  固化在 `Repository.jas`（泛型接口 vs 窄接口）与 `Contracts.jas`（注解 vs 接口）里。

## 八、与既有探针条目的对应关系

| 本报告 | 同源既有条目 | 关系 |
| --- | --- | --- |
| #3 跨文件字段 | R2DBC #5 | 复核；本探针观测到与文件顺序无关；**已修复（Q25 / C6+C16，同源）** |
| #4 类继承不参与子类型 | R2DBC #9 | 复核，JPA 视角（实体继承）；**已修复（本提交）** |
| #8 dict 不能作类型 | JDBC #16 / R2DBC #16 | 复核，JPA 视角（注册表/一级缓存） |
| #10 静态字段坏 | JDBC #8 | 复核，JPA 视角（全局序列） |
| #11 枚举不可比较 | JDBC #6 | 复核，JPA 视角（GenerationType）；**已修复（本提交）** |
| #1 泛型接口分派 | R2DBC #3（`LT;` 描述符） | 扩展：本条进一步区分「可声明/可实现/不可接口分派」；**已修复（本提交）** |
| #2 `return null` | 新增 | 本探针首次记录 |
| #5 运行期注解反射缺失 | 新增 | 本探针首次记录 |
| #6 缺 @Id 无编译期校验 | 新增 | 本探针首次记录 |
| #7 `remove` 方法名 | 新增 | 本探针首次记录（与后端名字特判相关，需进一步定位） |
| #9 注解命名实参/无元注解 | JDBC/R2DBC 注解用法 | 扩展：读取注解实参的适配成本 |
| #13 未知类型不报编译错 | 新增 | 本探针首次记录；**已修复（本提交）** |
| #14 `label` 关键字 | 新增 | 本探针首次记录 |
