# Jasper R2DBC 探针发现报告

本报告记录在 `probes/r2dbc/` 中用 Jasper 实现一个 **R2DBC 形状 API + 内存假实现** 时
遇到的语法 / 标准库 / 编译器摩擦点。所有结论都来自真实编译管线
（ANTLR → AST → 注解 → 符号表 → 类型检查 → Kotlin IR → JVM 字节码）的实测：
每条都给出可独立复现的最小用例，并用 `URLClassLoader` 真加载、真执行得到「实测」一栏的
异常/结果，而不是只看编译是否通过。

探针要压的核心是**异步 / 挂起模型的形状**：`await` / `go` / `Task`、订阅协议
（`onSubscribe/onNext/onError/onComplete`）、取消（`cancel` + 资源释放）、
错误传播（`onError` 通道 + `Error` 层次）、资源释放（`defer`）。

## 一、探针覆盖范围

`probes/r2dbc/` 描述了一个完整的 R2DBC 形状：

| 文件 | 内容 |
| --- | --- |
| `Connection.jas` | `Connection`/`Statement`/`ResultSet` 三条契约（事务返回 `Mono`、查询返回 `Flux`） |
| `Publisher.jas` | `Publisher`；`Mono extends Publisher`（单值流 + `block()`）；`Flux extends Publisher`（多值流 + 接口常量 `FETCH_SIZE`、`collect()`） |
| `Subscriber.jas` | `Subscriber` 四段式协议（`onNext(Row)`，`onError(R2dbcException)` 跨包形参） |
| `Subscription.jas` | `Subscription`：`cancel()`/`isCancelled()`/`isReleased()`（取消即释放） |
| `Row.jas` | 行载荷具体类型（`id()`/`name()` 取值方法，代替泛型 `Row<T>`） |
| `MemoryResultSet.jas` | `ResultSet` 内存实现（独立文件，跨文件接口实现回归守门） |
| `R2dbcException.jas` | 用户错误层次，独立多段包 `probes.r2dbc` + `import std.Error` |
| `ProbeApi.jas` | 注解类型 `@interface ProbeApi` |
| `ReactiveStreams.jas` | 「理想泛型形状」`RxPublisher<T>`/`RxSubscriber<T>`/`RxMono<T>`/`RxFlux<T>`/`RxSubscription`（只声明、不运行，见 #3） |
| `MemoryR2dbc.jas` | 其余具体实现（`ArrayFlux`/`SingleMono`/`TakeFlux`/`GatedSubscriber`/`CollectingSubscriber`/`CancellingSubscriber`/`FailingPublisher`/`Task`）+ `main()` 驱动器 |

覆盖到的语言特性：接口与接口继承、多实现、跨文件实现、类继承、构造器、注解、
`defer`、`try/catch`、`throw`、自定义 `Error` 子类、跨包 `import`、接口常量、
对象数组、嵌套泛型、`await`/`go`/`Task` 形状、状态机式的订阅回调顺序。

### 验收结果（真实运行输出）

`jasper.R2dbcProbeTest` 通过。探针运行后写入 `jasper-compiler/build/scratch/r2dbc-probe-report.txt`：

```
probe-files=Connection.jas,MemoryR2dbc.jas,MemoryResultSet.jas,ProbeApi.jas,Publisher.jas,R2dbcException.jas,ReactiveStreams.jas,Row.jas,Subscriber.jas,Subscription.jas
main-result=35
task-value=7
error-state=40001
transient-message=deadlock
Flux.declaredFields=[FETCH_SIZE]
```

`main-result=35` 是端到端链路的整数聚合，逐项对应一个已覆盖的异步形状：

| 分项 | 值 | 覆盖的形状 |
| --- | --- | --- |
| `collector.sum` | 6 | 逐行投递 `onNext`（1+2+3） |
| `collector.count` | 3 | 投递次数 |
| `collector.completed` | 1 | 正常结束 `onComplete` |
| `limitedCollector.count` | 2 | `TakeFlux` 只取前 N 个（Decorator 算子） |
| `limitedCollector.completed` | 1 | 达到上限后向下游 `onComplete` |
| `cancelling.seen` | 0 | 订阅即取消 → 不再收到元素 |
| `cancelling.released` | +10 | 取消触发底层资源释放（`isReleased()`） |
| `errorCollector.errorMessage=="deadlock"` | +1 | 错误走 `onError` 通道 |
| `memoryConn.rollbacks()` | 1 | 事务回滚（`Mono` 订阅） |
| `resultRows` | 3 | `ResultSet` 前向游标逐行读取 |
| `taskValue()` | 7 | `Task` 同步占位（`await` 的替代形状，见 #1） |

## 二、严重度与类别

- **严重度**
  - `[表达不了]`：该异步形状无法用当前语言直接写出等价代码，必须改设计或大幅规避。
  - `[很啰嗦]`：能写，但需要显著的变形、冗余或人为排序。
  - `[略有不便]`：小问题，有简单的绕过写法。
- **类别**
  - `①语法层`：解析器/语法规则不接受或错误吞掉。
  - `②标准库缺失`：缺少可直接使用的类型或方法。
  - `③编译器缺陷`：能通过语法与类型检查，但生成错误字节码或错误语义。

## 三、统计

| 严重度 | 数量 | 其中 ①语法层 | ②标准库 | ③编译器缺陷 |
| --- | --- | --- | --- | --- |
| 表达不了 | 6 | 1 | 1 | 4 |
| 很啰嗦 | 10 | 0 | 0 | 10 |
| 略有不便 | 2 | 2 | 0 | 0 |
| **合计** | **18** | **3** | **1** | **14** |

> 另有 2 条在既有批次中**已修复/已可用**，作为回归守门保留在探针里，不计入上表摩擦点：
> #7 跨文件接口实现赋值、#15 `defer` 泄漏到后续函数。

## 四、探针为通过验收而采用的规避

> 说明：下列规避是探针源码**编写时**为绕开缺陷所采用的写法，每条都对应第五节的一个编号。
> 所有规避都只影响**探针自身写法**，不改动任何 `.g4` 或编译器实现。

1. **元素类型固定为具体 `Row`，而不是泛型 `T`**（#3）。泛型形状保留在
   `ReactiveStreams.jas`（`Rx*` 前缀）中只作声明；运行路径全部走 `std` 包的
   `Publisher`/`Subscriber`/`Mono`/`Flux`（元素类型 `Row`）。
2. **具体表达式先赋给接口类型局部变量，再传给接口形参/存入接口字段**（#8）。
   例：`var seedSub: Subscription = new MemorySubscription(new Resource()); this.seed = seedSub;`
   以及 `var gateSubscriber: Subscriber = gate; this.source.subscribe(gateSubscriber);`。
3. **字段读取改用取值方法**（#5）：`Row` 的字段私有，暴露 `id()`/`name()`；
   这样跨文件读取不再依赖「声明字段的文件先被翻译」的排序。
4. **继承来的方法用声明该方法的类型作接收者**（#6）：
   `var error: R2dbcException = new R2dbcException(...); return error.sqlState();`。
5. **多态一律走接口，不做「子类赋给基类变量」**（#9）：`MemoryConnection` 先赋给
   `Connection` 接口再使用；`Task` 等不依赖类层次赋值。
6. **订阅者不保存可空的接口字段**（#10）：取消需要的 `Subscription` 句柄通过参数即取即用。
7. **资源释放统一用 `defer`**（#4），不使用 `finally`。
8. **多顶层类型文件把 `package` 放在文件首个非空白位置**（#12）：文件头注释若有，
   必须放在 `package` 之后或省略。

## 五、发现明细

### 表达不了

#### 1. `await` 生成对 `unknown` 的调用
- 类别：`③编译器缺陷`
- 现象：`await e` 能通过语法与类型检查，但降级为对名为 `unknown` 的方法的调用。
- 触发代码：
```jasper
package std;
public function compute(): int32 { return 5; }
public function main(): int32 { var x: int32 = await compute(); return x; }
```
- 期望：挂起等待 `compute()` 的结果，或至少报「不支持 await」的编译错误。
- 实测：运行期 `NoSuchMethodError: 'int std.main.unknown(int)'`。
- 探针规避：用同步占位 `Task`（`complete()`/`isDone()`/`join()`）表达「任务/挂起」形状，
  `taskValue()` 断言其闭环；见 `MemoryR2dbc.jas` 的 `Task`。

#### 2. `go` 被保留为关键字且被执行语义破坏
- 类别：`①语法层`
- 现象：`go` 不能作为标识符/方法名；`go f()` 能编译但生成的代码在加载时校验失败。
- 触发代码：
```jasper
package std;
public var side: int32 = 0;
public function bump(): void { side = side + 1; }
public function main(): int32 { go bump(); return side; }      // 触发 VerifyError
```
```jasper
package std;
public class T { public function go(): int32 { return 1; } }   // 语法错误
```
- 期望：`go` 作为异步调度原语（分离执行），或明确不支持并给编译错误；
  `go` 不应是保留字以至于不能当方法名。
- 实测：前者 `VerifyError: Bad type on operand stack`；
  后者 `Syntax error: mismatched input 'go' expecting Identifier`。
- 探针规避：不使用 `go`，异步形状统一用订阅协议（`subscribe`/`onNext`）表达。

#### 3. 泛型类型变量作形参/返回值 → 描述符为字面量 `LT;`
- 类别：`③编译器缺陷`
- 现象：`T` 直接出现在方法形参/返回值时，JVM 描述符被写成字面量 `LT;`，而不是擦除后的
  `Ljava/lang/Object;`。接口声明的方法与实现类的方法因此对不上，调用点分派失败。
- 触发代码：
```jasper
package std;
public interface S<T> { public function onNext(item: T): void; }
public class SImpl implements S<int32> { public function onNext(item: int32): void { } }
public function main(): int32 {
    var s: S<int32> = new SImpl();
    s.onNext(3);
    return 1;
}
```
- 期望：泛型方法按擦除后的 `Object` 描述符分派。
- 实测：运行期 `NoSuchMethodError: 'void std.S.onNext(int)'`
  （`javap` 可见 `S.onNext` 的描述符为 `(LT;)V`）。
- 探针规避：可运行形状的元素类型固定为具体 `Row`；泛型「理想形状」放在
  `ReactiveStreams.jas`（`RxPublisher<T>` 等）只声明不运行。

#### 4. `finally` 语义不可靠
- 类别：`③编译器缺陷`
- 现象：`try { ... } finally { ... }` 生成的字节码在加载时校验失败；即使不崩，
  `finally` 也不会在 `return`/异常路径执行。
- 触发代码：
```jasper
package std;
public var flag: int32 = 0;
public function f(): int32 { try { return 1; } finally { flag = flag + 1; } }
public function main(): int32 { var r: int32 = f(); return r + flag; }
```
- 期望：无论正常返回还是抛异常，`finally` 都执行（`flag==1`）。
- 实测：`VerifyError: Bad type on operand stack`。
- 探针规避：全部资源释放改用 `defer`（`conn.close()`/`statement.close()` 等）。

#### 14. 枚举比较触发编译器内部错误
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package std;
public enum Color { Red, Green }
public function main(): int32 {
    var c: Color = Color.Red;
    if (c == Color.Red) { return 1; }
    return 0;
}
```
- 期望：枚举可比较。
- 实测：`INTERNAL: Index -1 out of bounds for length 0`（编译期）。
- 探针规避：不使用枚举；需要区分种类时用 `int32`/`string` 常量。

#### 16. 泛型集合缺失：`dict<K,V>` 不能作类型、`.size()` 不可用
- 类别：`②标准库缺失`
- 触发代码：
```jasper
package std;
public function main(): int32 {
    var m: dict<string, int32> = dict{ "a": 1 };
    return m.size();
}
```
- 期望：`dict<K,V>` 可作类型，`m.size()`/`m.get(k)` 可用。
- 实测：`Type mismatch: cannot assign Map to variable of type dict`。
- 探针规避：`Row` 用固定的两个强类型字段（`id`/`name`），不依赖 map；
  `dict{...}` 字面量本身可用（JDBC 探针已验证），本探针不依赖。

### 很啰嗦

#### 5. 跨文件字段读取依赖文件翻译顺序
- 类别：`③编译器缺陷`
- 现象：读取**另一个文件**里声明的类的**公有字段**时，字段符号与类型解析都只在
  「声明字段的文件已经被翻译过」时成立。翻译顺序按源文件名排序；一旦读取者排在前面，
  IR 里字段名退化为兜底名 `field`、类型退化为 `void`。
- 触发代码（`Main.jas` 排在 `Pt.jas` 之前）：
```jasper
// Main.jas
package std;
public function main(): int32 { var p: Pt = new Pt(5); return p.x; }
```
```jasper
// Pt.jas
package std;
public class Pt { public var x: int32; public constructor(v: int32) { this.x = v; } }
```
- 期望：按声明类型正确生成 `getfield std/Pt.x:I`。
- 实测：`ClassFormatError: Field "field" in class std/main has illegal signature "V"`。
- 绕过：把声明文件改成排序在前的名字，或改用取值方法。
- 探针规避：`Row` 字段私有、暴露 `id()`/`name()` 方法（成员返回类型在第一遍预登记，
  与文件顺序无关）。

#### 6. 继承来的方法：返回描述符退化为 `void`
- 类别：`③编译器缺陷`
- 现象：在**子类**类型接收者上调用从基类继承的方法时，若该方法不在子类自己的成员索引里，
  返回类型会回退到全局表；若声明该方法的文件还没被翻译，则退化为 `void`。
- 触发代码（`Main.jas` 排在 `Base.jas` 之前）：
```jasper
// Main.jas
package std;
public class D extends B { }
public function main(): int32 {
    var d: D = new D();
    var x: string = d.s();
    if (x == "x") { return 1; }
    return 0;
}
```
```jasper
// Base.jas
package std;
public class B { public function s(): string { return "x"; } }
```
- 期望：`s()` 返回 `String`。
- 实测：`NoSuchMethodError: 'void std.D.s()'`。
- 探针规避：用**声明该方法的类型**作接收者（`errorState()` 里用 `R2dbcException`）。

#### 8. 具体类型的实参/右值未宽化到声明的接口类型
- 类别：`③编译器缺陷`
- 现象：
  - **成员方法调用**的形参描述符取自**实参的静态类型**，而不是被调方法的声明形参类型；
  - **字段赋值**的描述符取自**右值类型**，而不是字段声明类型。
- 触发代码（①②为成员调用，③为字段赋值）：
```jasper
package std;
public interface I { public function f(): int32; }
public class C implements I { public function f(): int32 { return 1; } }
public class H {
    private var slot: I;
    private var c: C;
    public constructor() { this.slot = new C(); this.c = new C(); }
    public function consume(i: I): int32 { return i.f(); }
    public function a(): int32 { return this.consume(new C()); }   // ①
    public function b(): int32 { var c2: C = new C(); return this.consume(c2); }  // ②
    public function c(): int32 { return this.consume(this.c); }    // ③
}
```
```jasper
package std;
public interface I { public function f(): int32; }
public class C implements I { public function f(): int32 { return 1; } }
public class H2 { private var slot: I; public constructor(c: C) { this.slot = c; } }
// ④ this.slot = c;（字段声明 I，右值 C）→ VerifyError
```
- 期望：`this.consume(...)` 生成 `consume(LI;)I`；`this.slot = c` 生成 `PUTFIELD slot:LI;`。
- 实测：① ② ③ 均 `NoSuchMethodError: 'int std.H.consume(std.C)'`；
  ④ `VerifyError: Bad type on operand stack`（探针里 `TakeFlux.seed` 的同类写法实测为
  `NoSuchFieldError: Class std.TakeFlux does not have member field 'std.MemorySubscription seed'`）。
- 绕过：先把具体值赋给接口类型局部变量再传/存。
- 探针规避：见第四节第 2 条（`seedSub`、`gateSubscriber` 等接口类型局部变量）。

#### 9. 类继承不参与赋值子类型
- 类别：`③编译器缺陷`
- 现象：`class D extends B` 之后，`var b: B = d;` 被类型检查拒绝（同文件与跨文件都拒绝）。
  接口实现（`implements`）的多态赋值正常工作，唯独类继承的赋值无法识别。
- 触发代码：
```jasper
package std;
public class B { public function v(): int32 { return 1; } }
public class D extends B { public function w(): int32 { return 2; } }
public function main(): int32 { var d: D = new D(); var b: B = d; return b.v(); }
```
- 期望：子类实例可赋给基类类型变量。
- 实测：`Type mismatch: cannot assign D to variable of type B`。
- 探针规避：多态一律走接口（`Connection`/`Publisher`/`Subscriber` 等），
  不使用「子类赋给基类变量」；错误层次只做「构造子类 + 调继承自 `std.Error` 的 `message()`」。

#### 10. 可空引用字段描述符错误
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package std;
public class C { public function v(): int32 { return 1; } }
public class H {
    private var slot: C?;
    public constructor() { this.slot = new C(); }
    public function get(): int32 { return this.slot.v(); }
}
public function main(): int32 { return new H().get(); }
```
- 期望：`C?` 字段按 `LC;` 存引用。
- 实测：`VerifyError: Bad type on operand stack`（字段被当成 `java.lang.Object`，
  与读取点的 `C` 不匹配）。
- 探针规避：订阅者不保存可空的接口字段，取消句柄通过参数即取即用。

#### 11. 数组元素读取的类型是 `void`，不能直接使用
- 类别：`③编译器缺陷`
- 现象：`a[i]` 的 IR 类型被记为 `unit`（描述符 `V`）。直接把它当比较操作数、
  字段赋值右值或成员访问接收者时，生成 `V` 描述符的指令。
- 触发代码：
```jasper
package std;
public function main(): int32 {
    var a: string[] = new string[1];
    a[0] = "x";
    if (a[0] == "x") { return 1; }        // VerifyError
    return 0;
}
```
```jasper
package std;
public class T {
    private var a: bool[]; private var b: bool;
    public constructor() { this.a = new bool[1]; this.a[0] = true; this.b = false; }
    public function f(): bool { this.b = this.a[0]; return this.b; }   // ClassFormatError
}
```
- 期望：数组读表达式类型为元素类型。
- 实测：前者 `VerifyError: Bad type on operand stack`；
  后者 `ClassFormatError: Field "b" in class std/T has illegal signature "V"`。
- 绕过：先赋给显式类型的局部变量再使用（`var x: string = a[0];`，实测可用）。
- 探针规避：`MemoryResultSet.getInt/getString` 先把 `this.rows[cursor]` 读进 `Row` 局部变量。

#### 13. 跨包同名类型不可共存
- 类别：`③编译器缺陷`
- 现象：符号表按**简单名**登记类型，两个不同包里同名类型触发重复声明。
- 触发代码：
```jasper
// P1.jas
package p1;
public interface Thing { public function v(): int32; }
```
```jasper
// P2.jas
package p2;
public interface Thing { public function v(): int32; }
```
- 期望：`p1.Thing` 与 `p2.Thing` 互不冲突。
- 实测：`Duplicate interface declaration 'JasInterface(name=Thing, ...)'`。
- 探针规避：泛型「理想形状」加 `Rx` 前缀（`RxPublisher<T>` 等），
  避免与可运行的 `Publisher`/`Subscriber`/`Mono`/`Flux`/`Subscription` 同名。

#### 17. 成员方法重载（不同元数）不可用
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package std;
public class P {
    public function q(): int32 { return 1; }
    public function q(x: string): int32 { return 2; }
}
public function main(): int32 { var p: P = new P(); return p.q() + p.q("x"); }
```
- 期望：按元数选择重载。
- 实测：`Function value expects 0 argument(s), got 1`。
- 探针规避：`Statement.executeQuery(sql)` 与 `Connection.createStatement(sql)` 等一律给出
  带参版本，不声明同名不同元数的重载。

#### 18. 静态字段不可用
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package std;
public class Counter { public static var total: int32 = 0; }
public function main(): int32 { Counter.total = 5; return Counter.total; }
```
- 期望：读写类静态字段。
- 实测：`NullPointerException: Cannot assign field "total" because "null" is null`
  （生成 `GETFIELD` 而非 `GETSTATIC`）。
- 探针规避：状态一律放实例字段（`MemoryConnection.commitCount` 等）。

#### 19. 可变参数不能按展开实参调用
- 类别：`③编译器缺陷`
- 触发代码：
```jasper
package std;
public class S { public function u(sql: string, args: any...): int32 { return 1; } }
public function main(): int32 { var s: S = new S(); return s.u("x", 1, "two"); }
```
- 期望：展开调用合法。
- 实测：`Function value expects 2 argument(s), got 3`。
- 探针规避：不使用 `...`；需要变长参数时改为显式数组或固定参数列表。

### 略有不便

#### 12. 多顶层类型文件必须 `package` 开头
- 类别：`①语法层`
- 现象：严格语法只接受「一个顶层类型」；多类型文件依赖兼容包装路径，而该路径的头部
  正则不跳过 `package` 之前的注释。于是「文件头注释 + `package` + 多类型」解析失败。
- 触发代码：
```jasper
// 文件头注释
package std;
public class A { }
public class B { }
```
- 期望：注释在 `package` 之前也应被接受。
- 实测：`Syntax error at line 3: mismatched input 'package' expecting {'var', 'const', 'function', 'class', 'enum', ...}`。
- 绕过：把 `package` 放到文件首个非空白位置（注释移到 `package` 之后，或文件只含一个顶层类型）。
- 探针规避：`Connection.jas`/`Publisher.jas`/`ProbeApi.jas` 等把 `package` 放第一行；
  单类型文件（`Subscriber.jas`/`Subscription.jas`/`Row.jas`）才允许注释在前。

#### 20. `throws` 被解析但被忽略
- 类别：`①语法层`
- 触发代码：
```jasper
package std;
public class E { }
public function f(): int32 throws E { return 1; }
public function main(): int32 { return f(); }      // 未 try/catch，仍可编译
```
- 期望：`throws` 至少体现为调用方的受检异常约束，或明确不支持并报错。
- 实测：正常编译（`throws` 子句被丢弃，不影响调用方，也不进入字节码）。
- 探针规避：真实的错误通道用语言级 `Error` 层次 + `throw`/`onError`，
  `throws` 只作声明性元信息。

### 已修复 / 已可用（回归守门）

#### 7. 跨文件接口实现赋值
- 类别：`③编译器缺陷`
- 状态：**现已可用**。
- `MemoryResultSet`（`implements ResultSet`）单独成文件，而接口类型的赋值点
  （`var resultSet: ResultSet = new MemoryResultSet(...)`）在 `MemoryR2dbc.jas`。
  两文件编译、加载、运行均成功，`main-result` 中的 `resultRows=3` 即来自该路径。
- 意义：把「接口实现类」与「接口赋值点」拆到不同文件是常见分层手段；本探针保留该拆分
  作为回归守门。

#### 15. `defer` 泄漏到后续函数
- 类别：`③编译器缺陷`
- 状态：**已修复**。
- `main()` 使用 `defer { conn.close(); }` / `defer { statement.close(); }`，且 `main` 之后无
  其它函数；若 `defer` 仍跨函数泄漏，运行期会 NPE。探针运行成功即证明作用域已正确隔离。
- 探针处置：保持 `main` 位于文件最后的原始写法作为对照。

## 六、探针「想写但写不出」的 R2DBC 形状清单

以下 R2DBC 惯用写法在当前语言下无法直接表达，探针只能降级或改用同步占位：

1. **`Publisher<T>` / `Mono<T>` / `Flux<T>` 泛型响应式协议** —— 类型变量描述符缺陷（#3），
   改用 `Row` 具体元素类型；泛型形状仅作声明（`ReactiveStreams.jas`）。
2. **`await publisher` / 挂起取值** —— `await` 生成 `unknown` 调用（#1），
   改用同步占位 `Task.join()`。
3. **`go task` 分离调度** —— `go` 保留字且语义破坏（#2），不使用。
4. **背压窗口 `request(n)`** —— 见第七节「未做」；当前用「订阅即尽力投递 + `cancel`」。
5. **`Flux<T>.collect(): T[]` 物化结果集** —— 泛型返回（#3），改为 `collect(): Row[]`。
6. **`Row.get(0, Int::class)` 之类的按列名/类型取值** —— 泛型 map 缺失（#16），
   改为强类型字段 + `id()`/`name()` 方法。
7. **`finally { connection.close(); }` 资源兜底** —— `finally` 不可靠（#4），改用 `defer`。
8. **`Schedulers.parallel()` 切换执行线程** —— 无调度器标准库；见第七节。
9. **用类层次表达错误分类并向上转型捕获/赋值** —— 子类赋给基类变量被拒（#9），
   多态走接口，错误只用 `Error` 的 `message()` 观测。
10. **`static final` 常量与静态工厂** —— 静态字段不可用（#18），改用实例字段；
    接口常量可用（`Flux.FETCH_SIZE` 已被发射，见验收结果）。

## 七、明确未做的部分

本探针刻意**不**覆盖以下内容，它们超出「语言形状」本身、需要真实运行时/调度设施支持：

- **真实网络 IO**：没有任何 socket / 连接池；`MemoryR2dbc` 是纯内存假实现。
  真实 R2DBC 驱动的 connect/query/fetch 往返不在探针范围。
- **背压窗口与 `request(n)`**：未实现 Reactive Streams 的显式需求模型。
  探针只体现「订阅即投递 + `cancel` 停止」；没有 `request`/`Subscription` 信用计数。
- **调度器 / 事件循环**：没有 `Schedulers`、线程切换或 `publishOn/subscribeOn`；
  所有回调都在订阅调用的同一线程上同步发生。
- **`await`/`go` 的真异步实现**：仅验证它们是缺陷（#1/#2），未构造可运行的异步运行时。
- **真实 `Row`/`Column` 元数据**：不实现按列名索引、类型协商、`null` 位图等。

## 八、结论

- 探针可**真编译、真加载、真运行**，说明 Jasper 已具备「接口契约 + 类实现 + 跨文件实现 +
  内存状态 + 错误层次 + 资源释放 + 接口常量 + 对象数组」的表达能力，足以拼出一个可运行的
  R2DBC 假实现，并压出订阅/取消/错误传播/资源释放的可观察行为（`main-result=35`）。
- 但 18 个摩擦点里有 14 个是 `③编译器缺陷`，且大多是**静默的错误字节码**
  （`VerifyError`/`ClassFormatError`/`NoSuchMethodError`），而不是清晰的编译期报错；
  这会诱导用户把编译器 bug 误当成自己代码的问题。
- 与「异步 / 挂起」最相关的三条（`await` #1、`go` #2、泛型协议 #3）都属 `[表达不了]`：
  当前语言**尚未具备真正写异步响应的能力**，探针只能用「同步占位 + 回调协议」逼近其形状。
