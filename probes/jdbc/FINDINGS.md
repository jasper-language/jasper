# Jasper JDBC 探针发现报告

本报告记录在 `probes/jdbc/` 中用 Jasper 实现一个 **JDBC 形状 API + 内存假实现** 时
遇到的语法/标准库/编译器摩擦点。所有结论都来自真实编译管线
（ANTLR → AST → 类型检查 → Kotlin IR → JVM 字节码）的实测，条目中的代码片段均为
可独立复现的最小用例。

## 一、探针覆盖范围

`probes/jdbc/` 描述了一个完整的 JDBC 形状：

| 文件 | 内容 |
| --- | --- |
| `DataSource.jas` | `DataSource` 接口（工厂） |
| `Connection.jas` | `Connection` 接口（事务 + 语句工厂） |
| `Statement.jas` | `Statement` 接口：`executeQuery/executeUpdate`、接口常量 `MAX_ROWS`、`throws SQLException`、`args: any...` |
| `PreparedStatement.jas` | `PreparedStatement extends Statement`（接口继承 + 重载 `executeQuery()`） |
| `ResultSet.jas` | `ResultSet` 接口（游标 + `wasNull()` 侧信道） |
| `SQLException.jas` | 用户异常 `extends std.Error`（独立多段包 `probes.jdbc` + `import std.Error`） |
| `ProbeApi.jas` | 注解类型 `@interface ProbeApi` |
| `MemoryResultSet.jas` | `ResultSet` 的具体实现类（独立文件，验证跨文件接口实现） |
| `MemoryJdbc.jas` | 其余具体实现类 + `main()` + `buildMeta()` + `buildNested()` |

覆盖到的语言特性：接口与接口继承、多实现、类继承、构造器、方法重载、注解、
`defer`、`try/catch`、`throw`、自定义 `Error` 子类、`dict{...}` 字面量、
`any...` 可变参数、`throws` 子句、嵌套泛型、`java.util.HashMap` 返回类型。

**验收结果**：`jasper.JdbcProbeTest` 通过——
`std.main.main()` 返回 `21`，`std.buildMeta.buildMeta()` 返回 2 项 `HashMap`，
`std.Statement` 的 `declaredFields` 为 `[MAX_ROWS]`（接口常量现已发射，见 #11）。

## 二、严重度与类别

- **严重度**
  - `[表达不了]`：该 JDBC 形状无法用当前语言直接写出等价代码，必须改设计或大幅规避。
  - `[很啰嗦]`：能写，但需要显著的变形、冗余或人为排序。
  - `[略有不便]`：小问题，有简单的绕过写法。
- **类别**
  - `①语法层`：解析器/语法规则不接受或错误吞掉。
  - `②标准库缺失`：缺少可直接使用的类型或方法。
  - `③编译器缺陷`：能通过语法与类型检查，但生成错误字节码或错误语义。

## 三、统计

| 严重度 | 数量 | 其中 ①语法层 | ②标准库 | ③编译器缺陷 |
| --- | --- | --- | --- | --- |
| 表达不了 | 12 | 3 | 0 | 9 |
| 很啰嗦 | 3 | 0 | 0 | 3 |
| 略有不便 | 3 | 1 | 2 | 0 |
| **合计** | **18** | **4** | **2** | **12** |

> 修复进度：**第一批（commit `dbe8f17`）** 已修复 5 条局部缺陷——`③编译器缺陷`
> #4/#5/#15、`①语法层` #11、`②标准库缺失` #17；
> **第二批（commit `e544e5a`）** 已修复 4 条「跨包/链接」缺陷——`③编译器缺陷`
> #14/#12/#1/#7；**本提交** 已修复 `③编译器缺陷` #6（枚举常量比较 / `switch` / `match`）。
> 剩余未修复 8 条。修复详情见第五节各条目的「状态」行。

## 四、探针为通过验收而采用的规避（原 5 处；第 1、2 条已随本批修复移除）

> 说明：下列规避是探针源码**编写时**为绕开缺陷所采用的写法。
> 第 1、2 条已在**第二批（commit `e544e5a`）** 修复后从探针中**移除**（见下）；
> 第 5 条与 #4、#15 相关，二者已在第一批（`dbe8f17`）修复，该规避不再必要，
> 但探针仍保留其原始写法。

1. ~~**全部探针文件放在 `package std;`**~~ —— **已移除**：`SQLException.jas` 现位于
   `package probes.jdbc;` 并通过 `import std.Error;` 扩展 `std.Error`，
   `Statement.jas` / `MemoryJdbc.jas` 用 `import probes.jdbc.SQLException;` 引用它。
   这同时验证了 #14（跨包链接）与 #12 的包名规范化。
2. ~~**全部具体实现放在同一个文件 `MemoryJdbc.jas`**~~ —— **已移除**：
   `MemoryResultSet`（`implements ResultSet`）已拆到独立文件 `MemoryResultSet.jas`，
   而接口类型的赋值点仍在 `MemoryJdbc.jas`，跨文件多态赋值成立（#7）。
3. **存储用 `int32[]`/`string[]`/`bool[]` 三组并行数组，而不是 `Row[]`**：
   这是该假实现的**设计选择**，不再是缺陷规避——用户类对象数组已可正确读写
   （#1）；探针保留并行数组仅为可读性。
4. **数组填充拆成「每个函数只处理一种元素类型」的辅助函数**：仍需要，
   同方法内多元素类型数组赋值会产生非法字节码（见 #3，未修）。
5. **不写 `this.方法()`，并把含 `defer` 的 `main` 放到文件最后**：
   `this.m()` 的接收者类型错误（见 #4），`defer` 会泄漏到后续函数（见 #15）。
   ——两者均已在 `dbe8f17` 修复，此规避不再必要，但探针保留原始写法。


---

## 五、发现明细

### 表达不了

#### 1. 用户类对象数组丢失包名
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `e544e5a`）**——数组元素描述符改从 `mapType(JasArrayType)`
  的结果反推（去掉前导 `[`），不再用会丢包名的 `jvmDescriptorFromType(元素类型)`。
  `jasper.CrossPackageLinkageTest.user class object array keeps package and round-trips fields` 覆盖。
- 现象：`new Row[n]`（`Row` 是包内用户类）运行期找不到类；数组元素描述符丢失包名。
- 触发代码：
```jasper
package jdbcprobe;

public class Row { public var v: int32; public constructor(v: int32) { this.v = v; } }

public function main(): int32 {
    var rows: Row[] = new Row[2];
    rows[0] = new Row(1);
    rows[1] = new Row(2);
    return rows[0].v + rows[1].v;
}
```
- 期望：正常构造并读写 `Row[]`。
- 实测：`ClassNotFoundException: Row`（字段形式下为 `NoClassDefFoundError: Row`）。
- 探针规避：用三组基本类型/字符串并行数组代替 `Row[]`。

#### 2. 数组元素读取的 IR 类型是 `void`，只能用于 `return`
- 类别：`③编译器缺陷`
- 现象：`a[i]` 的表达式类型被记为 `unit`（JVM 描述符 `V`）。一旦把它当
  参数、字段赋值右值或比较操作数，就会生成 `V` 描述符的指令。
- 触发代码：
```jasper
package jdbcprobe;

public class T {
    private var a: bool[];
    private var b: bool;
    public constructor() { this.a = new bool[1]; this.a[0] = true; this.b = false; }
    public function f(): bool { this.b = this.a[0]; return this.b; }   // 字段赋值
}

public function main(): int32 { var t = new T(); if (t.f()) { return 1; } return 0; }
```
  以及作为比较操作数：
```jasper
public function main(): int32 {
    var a: string[] = new string[1];
    a[0] = "x";
    if (a[0] == "x") { return 1; }   // 数组读参与 ==
    return 0;
}
```
- 期望：`this.b = this.a[0];` 与 `a[0] == "x"` 正常。
- 实测：前者 `ClassFormatError: Field "b" ... illegal signature "V"`；
  后者 `VerifyError: Bad type on operand stack`。
- 探针规避：读取后先赋给显式类型的局部变量，再使用
  （`var isNull: bool = this.nullCells[i]; this.lastNull = isNull;`）。

#### 3. 同一方法内「多元素类型数组赋值」生成非法字节码
- 类别：`③编译器缺陷`
- 现象：数组赋值的临时局部变量名固定为 `aa$`/`ai$`/`av$`，后端用
  `locals.find { it.name == name }` 取**第一个**同名局部变量的类型。当同一方法里先给
  `int32[]` 再给 `string[]` 赋值时，字符串赋值复用了 int 临时变量的描述符。
- 触发代码：
```jasper
package jdbcprobe;

public class T {
    private var ic: int32[];
    private var sc: string[];
    public constructor() {
        this.ic = new int32[1]; this.sc = new string[1];
        this.ic[0] = 1; this.sc[0] = "ada";   // 两种元素类型
    }
    public function v(): int32 { return this.ic[0]; }
}

public function main(): int32 { var t = new T(); return t.v(); }
```
- 期望：两条赋值都正确。
- 实测：`VerifyError: Bad local variable type`（`MemoryConnection.<init>` 中
  `iload` 加载了 `String` 槽位）。反汇编可见 `astore 10` 之后紧接 `iload 10`。
- 探针规避：把每种元素类型的填充拆到独立函数中（`makeIntCells`/`makeStrCells`/
  `makeNullCells` 各自只做一种类型）。

#### 4. `this.方法()` 不可调用
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `dbe8f17`）**——`translateIdentifier` 现按当前类登记
  `this`/`super` 的接收者类型，`invokevirtual` 宿主类正确。
- 现象：成员调用（`v:` 分支）从**接收者表达式的类型**推导 `invokevirtual` 的宿主类。
  `this` 这个标识符没有登记类型，回退为 `unit`，于是宿主类变成 `std/void`。
- 触发代码：
```jasper
package jdbcprobe;

public class T {
    private function val(): int32 { return 7; }
    public function run(): int32 { return this.val() + 1; }   // 自调用
}

public function main(): int32 { var t = new T(); return t.run(); }
```
- 期望：`invokevirtual jdbcprobe/T.val()I`。
- 实测：`NoClassDefFoundError: jdbcprobe/void`；反汇编为
  `invokevirtual std/void.val:()I`（返回任意类型都如此）。
- 探针规避：删掉私有辅助方法改成内联表达式；必要时用
  `var self: T = this; self.m();` 绕过。

#### 5. 返回 `void` 的自由函数不能作语句调用
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `dbe8f17`）**——静态调用分支在 `)V` 之后补
  `ACONST_NULL`，语句位置不再发生操作数栈下溢。
- 现象：语句位置的自由函数调用在 `invokestatic ...)V` 之后多出一条 `POP`
  （成员调用会补一个 `aconst_null`，静态调用没有）。
- 触发代码：
```jasper
package jdbcprobe;

function sink(v: int32): void { }

public function main(): int32 { sink(1); return 0; }
```
- 期望：`invokestatic sink(I)V`，不产生多余 `POP`。
- 实测：`VerifyError: Operand stack underflow`。
- 探针规避：辅助函数改为返回数组（`oneIntCell(...) : int32[]`），避免 void 自由函数。

#### 6. 枚举的比较 / `switch` / `match` 全部触发编译器内部错误
- 类别：`③编译器缺陷`
- 状态：**已修复（本提交）**——根因是「`EnumType.Constant` 被当成实例字段读取」：
  translator 把 `Types.Integer` 翻译成「对类名求值后 `GETFIELD`」，ASM 计算栈帧时报
  `INTERNAL: Index -1 out of bounds for length 0`。现改为：translator 预登记枚举常量符号
  （键为 JVM 内部类名，声明处与使用处共享同一身份），常量声明保持 `IrEnumEntry`，读取
  表达式走新增的 `IrGetEnumValue`；后端据 `IrEnumEntry` 发射
  `public static final ACC_ENUM` 字段、在 `<clinit>` 用 `PUTSTATIC` 按声明顺序初始化，
  `IrGetEnumValue` 发 `GETSTATIC`（不再用「字段名首字母大写」启发式）。另修正
  `values()` 中把字段描述符当类名用的 `CHECKCAST`（原写法会 `ClassFormatError`）。
  回归守门：`jasper-compiler/src/test/kotlin/jasper/EnumTest.kt`（同常量/异常量比较、
  `if` 分支、`switch` 三分支 + default、`match`、枚举形参/返回值、`values()`/`valueOf`、
  javap 字段标志与 `<clinit>` 证据，共 7 个用例）。
- 现象：枚举**声明**本身可用，但任何比较或在 `switch`/`match` 中使用枚举常量都会
  让编译器崩溃。
- 触发代码：
```jasper
package jdbcprobe;

public enum Types { Integer, Varchar, Null }

public function main(): int32 {
    var t: Types = Types.Integer;
    if (t == Types.Integer) { return 1; }
    return 0;
}
```
  （`switch (t) { case Types.Integer => {...} default => {...} }` 与
  `match (t) { ... }` 同样失败。）
- 期望：枚举可比较、可在 `switch`/`match` 分支。
- 实测：`INTERNAL: Index -1 out of bounds for length 0`（编译期）。
- 探针规避：探针不使用枚举，按 JDBC 的列类型改用 `int32` 常量代替。

#### 7. 跨文件接口子类型赋值失败
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `e544e5a`）**——`TypeChecker` 在收集层次关系时除当前文件外，
  还从共享 `SymbolTable` 汇总本次编译全部文件的 `implements`/`extends`；同时修正
  「单大写字母名 = 类型参数」的启发式：只有在名字不是已声明用户类型时才按形参处理，
  于是接口 `S` 不再被误判。`jasper.CrossPackageLinkageTest.interface implementation in a separate file is assignable` 覆盖。
- 现象：`implements` 关系按**文件**记录。若「接口类型赋值点」与「实现类」不在同一
  文件，类型检查报错。
- 触发代码（`S.jas` 声明接口与实现，`main.jas` 使用）：
```jasper
// S.jas
package jdbcprobe;
public interface S { public function k(): string; }
public class MyS implements S { public function k(): string { return "x"; } }
```
```jasper
// main.jas
package jdbcprobe;
public function main(): int32 { var s: S = new MyS(); return 1; }
```
- 期望：跨文件多态赋值。
- 实测：`Type mismatch: cannot assign MyS to variable of type S`
  （若「实现类」与「赋值点」同文件、仅接口分文件，则可编译）。
- 探针规避：全部实现类与 `main` 放在同一文件。

#### 8. 静态字段不可用
- 类别：`③编译器缺陷`
- 状态：**已修复（本提交）**——`translateProperty` 现按 `static` 修饰符创建静态 `IrField` 并
  登记进 `staticFieldSymbols`；`类名.字段` 的读写不再生成接收者，分别发射
  `GETSTATIC`/`PUTSTATIC`；非 final 静态字段的声明初值由 `<clinit>` 执行；类名访问实例字段
  改为明确的编译错误。回归见 `jasper.StaticFieldTest`（含 `javap` 指令/字段标志断言）。
  以下「实测」为修复前证据，保留作历史记录。
- 触发代码：
```jasper
package jdbcprobe;

public class Counter { public static var total: int32 = 0; }

public function main(): int32 { Counter.total = 5; return Counter.total; }
```
- 期望：读写类静态字段。
- 实测：`NullPointerException: Cannot assign field "total" because "null" is null`
  （生成了 `GETFIELD` 而非 `GETSTATIC`）。
- 探针规避：探针不用静态字段，全部状态放在实例字段。

#### 9. 嵌套泛型 `>>` 不能连写
- 类别：`①语法层`
- 状态：**已修复（本提交）**——编译器在 Kotlin 侧的 token 流/解析装配上做拆分：在类型实参语境
  把 `>>`(RSHIFT) 拆成两个 `>`(GT)、`>>>`(URSHIFT) 拆成三个 GT，非类型语境保持移位语义
  （`a >> b`、`a >>> b` 不变），`> >` 空格写法继续可用；不改任何 `.g4`。
  实现见 `jasper-compiler/src/main/kotlin/jasper/compiler/NestedGenericTokenSupport.kt`
  （先全拆、再以「解析错误数」为判据裁剪，最后取错误最少且拆分最少的配置）。
  回归见 `jasper.NestedGenericsTest`（局部变量/字段/形参/返回值/三层/`new` 实例化/空格写法/
  移位不受影响/比较不受影响/缺 `>` 仍报错）。以下「实测」证据已过期。
- 现象：词法器把 `>>` 当作移位运算符，`List<List<int32>>` 解析失败。
- 触发代码：
```jasper
package jdbcprobe;
public class M {
    public var v: List<List<int32>>;
    public constructor() { this.v = new List<List<int32>>(); }
}
```
- 期望：接受 `>>` 作为两个泛型右尖括号。
- 实测：`Syntax error ... no viable alternative at input '...List<List<int32>>'`。
- 绕过：写成 `List<List<int32> >`（中间加空格）即可。
- 探针规避：`buildNested()` 使用 `List<List<int32> >`。

#### 10. 可变参数不能按展开实参调用，且形参不是数组
- 类别：`①语法层`
- 状态：**已修复（语法裁决②，真 varargs）**——`xs: T...` 在函数体内类型为 `T[]`
  （支持 `.length`/下标/for-in）；调用点把固定形参之后的实参打包成数组（0 个 ⇒ 空数组）；
  实参个数对齐且最后一个实参本身是兼容数组时原样传入；`...` 只允许在最后一个形参上。
  回归见 `jasper.VarargsTest`（含本条原始形状 `s.u("x", 1, "two", 3)`）。以下「实测」证据已过期。
- 现象：`args: any...` 声明被接受，但（a）按 `f("x", 1, "two", 3)` 展开调用报参数
  个数错误；（b）形参 `args` 的类型是元素类型 `any` 而非 `any[]`，无法 `.length` 或下标。
- 触发代码：
```jasper
package jdbcprobe;
public class S {
    public function u(sql: string, args: any...): int32 { return args.length; }
}
public function main(): int32 {
    var s = new S();
    var a: any[] = new any[2];
    return s.u("x", a);          // 只能传单个数组
}
```
- 期望：`s.u("x", 1, "two", 3)` 合法，且 `args` 可索引/取长度。
- 实测：展开调用 → `Function value expects 2 argument(s), got 4`；
  `args.length` → `ClassFormatError: Field "field" ... illegal signature "V"`。
- 探针规避：`Statement.executeUpdate(sql, args: any...)` 在实现里忽略 `args`，
  调用时传单个 `any[]`。

#### 11. 接口常量被静默丢弃
- 类别：`①语法层`
- 状态：**已修复（commit `dbe8f17`）**——接口常量现构建为 `JasProperty` 并发射
  `public static final` 字段（字面量走 `ConstantValue`，否则 `<clinit>`），
  读取降级为 `GETSTATIC`。`jasper.JdbcProbeTest` 现观测到
  `std.Statement.declaredFields=[MAX_ROWS]`，本条目「实测」中的空数组证据已过期。
- 现象：接口中的 `public const MAX_ROWS: int32 = 100;` 被解析但未生成任何字段。
- 触发代码：
```jasper
package std;
public interface Statement {
    public const MAX_ROWS: int32 = 100;
    public function close(): void;
}
```
- 期望：接口常量在字节码中可见并可用（`Statement.MAX_ROWS`）。
- 实测：反射 `std.Statement.declaredFields` 为空数组；
  若引用该常量则 `ClassFormatError: Field "field" ... signature "V"`。
- 探针规避：保留声明以暴露问题，但不引用它。

#### 12. 点分包名 + 接口实现 → 非法类名
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `e544e5a`）**——类的内部名、实现的接口名、父接口名、父类名
  统一走 `TypeMapper.resolveNamedTypeName`，点分包名一律换成 `/`；探针的
  `SQLException` 已改放多段包 `probes.jdbc`。`jasper.CrossPackageLinkageTest.dotted package name with interface implementation is legal` 覆盖。
- 现象：`classInterfaceNames` 里的接口名没有把 `.` 换成 `/`，生成非法内部名。
- 触发代码：
```jasper
package probes.jdbc;

public interface Sink { public function send(): int32; }
public class Impl implements Sink { public function send(): int32 { return 7; } }
public function main(): int32 { var p: Sink = new Impl(); return p.send(); }
```
- 期望：点分包名与接口实现正常共存。
- 实测：`ClassFormatError: Illegal class name "probes.jdbc/Sink" in class file probes/jdbc/Impl`。
- 绕过：使用单段包名（`package probes;`）即可正常运行。
- 探针规避：所有探针文件使用单段包 `std`。

### 很啰嗦

#### 13. 成员方法重载（不同元数）不可用
- 类别：`③编译器缺陷`
- 现象：`memberSignatures` 用 `putIfAbsent` 记录，只有第一个重载生效，之后按元数
  不同的重载调用一律解析到第一个签名。
- 触发代码：
```jasper
package jdbcprobe;

public class P {
    public function q(): int32 { return 1; }
    public function q(sql: string): int32 { return 2; }
}

public function main(): int32 { var p = new P(); return p.q() + p.q("x"); }
```
- 期望：按元数选择重载。
- 实测：`Function value expects 0 argument(s), got 1`。
- 绕过：避免同名不同元数；`PreparedStatement` 的重载 `executeQuery()`（无参）与继承的
  `executeQuery(sql)` 因调用点分别落在不同静态类型上而侥幸可用。
- 探针规避：`MemoryPreparedStatement.executeQuery(sql)` 直接复制无参版本的方法体，
  避免在实现内部调用重载。

#### 14. `import` 被忽略，跨包类型不可链接
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `e544e5a`）**——`TypeMapper` 新增 `currentImports` 与
  `resolveNamedTypeName`，translator 逐文件安装 import 上下文；`SymbolTable` 改为
  同时采集 `ast.imports`（此前只遍历 `declarations`，整体漏掉 import）。
  探针的 `SQLException` 现位于 `probes.jdbc` 并 `import std.Error`。
  `jasper.CrossPackageLinkageTest.imported type from another package links and runs` 覆盖。
- 现象：代码生成不使用导入信息，跨包类型引用被解析成「当前包 + 类名」。
- 触发代码：
```jasper
// other/MyErr.jas
package other;
public class MyErr extends std.Error { public constructor(m: string) { super(m); } }
```
```jasper
// main.jas
package jdbcprobe;
import other.MyErr;
public function main(): int32 {
    try { throw new MyErr("x"); } catch (e: Error) { return 1; }
}
```
- 期望：`import` 生效，正确链接 `other.MyErr` 与 `std.Error`。
- 实测：`ClassNotFoundException: jdbcprobe.Error`（`Error` 被解析为 `jdbcprobe.Error`）。
- 绕过：所有相关类型放在同一个包里。
- 探针规避：`SQLException` 与 `Error` 同在 `std` 包。

#### 15. `defer` 泄漏到后续函数
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `dbe8f17`）**——每个函数/构造器/访问器/lambda 现通过
  `scopedDefers` 建立独立的 `defer` 作用域，退出时恢复外层作用域。
- 现象：`defer` 体被加入翻译上下文的 `deferredBodies` 列表，但**从不按函数清空**，
  于是一个函数的 `defer` 会被前置到其后每个函数的 `return` 里。
- 触发代码：
```jasper
package jdbcprobe;

public class R { public function close(): void { } }

public function first(): int32 { var r: R = new R(); defer { r.close(); } return 1; }
public function second(): int32 { return 2; }          // 会被注入 r.close()
public function main(): int32 { return first() + second(); }
```
- 期望：`defer` 只属于声明它的函数。
- 实测：`NullPointerException: Cannot invoke "jdbcprobe.R.close()" because "null" is null`
  （`second` 里出现了对未定义 `r` 的调用）。
- 绕过：把含 `defer` 的函数放到文件最后。
- 探针规避：把 `main`（唯一使用 `defer` 的函数）放在 `MemoryJdbc.jas` 末尾。

### 略有不便

#### 16. 泛型集合类型与常用方法缺失
- 类别：`②标准库缺失`
- 现象：`dict{...}` 字面量能用（降级为 `java.util.HashMap`），但
  `dict<K,V>` 不能作**类型**，且集合的 `.size()`/`.get()` 会被后端的
  `size→ARRAYLENGTH`、`get→数组读` 特判误伤。
- 触发代码：
```jasper
package jdbcprobe;

public class Table { public var cols: dict<string, int32>;      // 类型位置
    public constructor() { this.cols = dict{ "a": 1 }; } }

public function main(): int32 {
    var m = dict{ "a": 1, "b": 2 };
    return m.size();                                            // 集合 size
}
```
- 期望：`dict<K,V>` 可作类型；`m.size()`/`m.get(k)` 正常。
- 实测：类型声明虽能编译，但元素类型解析到不存在的类；`m.size()`
  → `VerifyError: Bad type on operand stack`；`m.put(...)`
  → `NoSuchMethodError: 'void java.util.HashMap.put(java.lang.String, int)'`。
- 绕过：像探针那样用并行数组 + 手工维护标签映射。
- 探针规避：`MemoryResultSet` 只支持按列号读取；`getStringByLabel` 固定取第 2 列，
  并以 `dict{...}` 字面量在 `buildMeta()` 中证明字面量本身可用。

#### 17. `string` 缺少 `equals` 等标准方法
- 类别：`②标准库缺失`
- 状态：**已修复（commit `dbe8f17`）**——编译器预声明 `equals(kotlin.Any): bool`，
  `s.equals(o)` 现降级为 `(Object)Z` 调用，而非此前的 `(String)V`。
  注：这是把 `equals` 作为语言级值相等原语登记，并非补齐整套标准库——
  `compareTo` 等仍未提供。
- 触发代码：
```jasper
package jdbcprobe;
public function main(): int32 {
    var s: string = "ada";
    if (s.equals("ada")) { return 1; }
    return 0;
}
```
- 期望：`string` 上可调用 `equals`/`compareTo` 等。
- 实测：`VerifyError: Bad type on operand stack`。
  注：运算符 `==` 在字符串上**可用**（降级为 `java.util.Objects.equals`），
  故探针改用 `e.message() == "deadlock"`。
- 探针规避：字符串比较全部使用 `==`。

#### 18. `throws` 被解析但被忽略，无检查
- 类别：`①语法层`
- 触发代码：
```jasper
package jdbcprobe;

public class E { }
public function f(): int32 throws E { return 1; }
public function main(): int32 { return f(); }      // 未 try/catch，仍可编译
```
- 期望：`throws` 至少体现为调用方的受检异常约束；或明确不支持并报错。
- 实测：正常编译（`throws` 子句被丢弃，不影响调用方，也不进入字节码）。
- 探针规避：使用语言级 `Error` 层次 + `throw`/`try`/`catch` 作为真实错误通道。

---

## 六、探针「想写但写不出」的 JDBC 形状清单

以下 JDBC 惯用写法在当前语言下无法直接表达，探针只能降级：

1. **`Row[]` 结果集缓存** —— 用户类对象数组已修复（#1，`e544e5a`），可用 `Row[]`；
   探针沿用并行数组只是设计选择。
2. **`Map<String,Integer>` 列名 → 列号表** —— 泛型 map 类型缺失（#16），
   `getStringByLabel` 固定列号。
3. **`ResultSet` 内部私有辅助方法 `cellIndex(col)`** —— `this.方法()` 曾不可用
   （#4，已修复 `dbe8f17`），探针仍保留内联算术写法。
4. **`void` 形态的私有 setter 辅助函数** —— void 自由函数曾不可语句调用
   （#5，已修复 `dbe8f17`），探针仍保留返回数组写法。
5. **接口常量 `Statement.MAX_ROWS`** —— 曾被静默丢弃（#11，已修复 `dbe8f17`）。
6. ~~**`if (rs.getType() == Types.Integer)` 列类型分发**~~ —— 枚举比较曾崩溃（#6），
   **本提交已修复**，可直接使用枚举；探针保留 `int32` 常量写法仅为未回归改写。
7. **`throws SQLException` 声明** —— 被忽略（#18），用 `Error` 层次代替。
8. **`executeUpdate(sql, a, b, c)` 展开调用、`args.length`** —— varargs 不可用（#10），
   只传单个数组并忽略 `args`。
9. **跨文件的接口实现（如 `PreparedStatement` 实现在独立文件）** —— 已修复（#7，`e544e5a`）：
   实现类可与赋值点分处不同文件，探针已把 `MemoryResultSet` 拆到独立文件。

## 七、结论

- **修复更新**：
  - 第一批（commit `dbe8f17`）：#4、#5、#11、#15、#17 五个缺陷已修复，并新增
    `jasper.ProbeDefectRegressionTest`（5 个测试，每缺陷 1 个）逐一回归。
  - 第二批（commit `e544e5a`）：#14、#12、#1、#7 四个「跨包/链接」缺陷已修复
    （统一「类型名 → JVM 内部名」解析 + 跨文件层次汇总），并新增
    `jasper.CrossPackageLinkageTest`（4 个测试，每缺陷 1 个）回归；
    探针据此**去掉两处规避**（`SQLException` 独立成包 + `import`、`MemoryResultSet`
    拆到独立文件）。
  - **本提交**：`③编译器缺陷` #6 已修复——枚举常量改为 `IrEnumEntry` 表示、读取走
    `IrGetEnumValue`，后端据 `IrEnumEntry` 发射静态 `ACC_ENUM` 字段并在 `<clinit>` 中
    初始化；新增 `jasper.EnumTest`（7 个测试）回归。`jasper.JdbcProbeTest` 始终通过。
    剩余 8 条仍未修复。
- 探针可编译并运行，说明 Jasper 已具备「接口契约 + 类实现 + 内存状态 + 异常 + 资源
  清理 + 字面量集合」的表达能力，足以拼出一个可运行的 JDBC 假实现。
- 但 18 个摩擦点里有 12 个是 `③编译器缺陷`，且多为**静默错误字节码**
  （`VerifyError`/`ClassFormatError`/`NoSuchMethodError`/`NPE`），而不是清晰的编译期报错；
  这会让用户把编译器 bug 误当成自己代码的问题。
- 最影响「写不出 JDBC 形状」的是前 8 条：用户类对象数组（#1，已修复）、数组读类型（#2）、
  多类型数组赋值（#3）、`this.方法()`（#4，已修复）、void 自由函数（#5，已修复）、
  枚举（#6，本提交已修复）、跨文件接口赋值（#7，已修复）、静态字段（#8）。
