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
| `taskValue()` | 7 | `go` 启动任务 + `await` 取值（#1/#2 已修，`7e66eca`） |

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
>
> 修复进度：**本批（commit `7e66eca`）** 修复 3 条 `③编译器缺陷`——#1（`await`）、
> #4（`finally`）、#11（数组元素读类型）；并修复 `①语法层` #2 的**执行语义**
> （`go` 任务体真的执行、副作用可观察）。#2 的另一半「`go` 可作普通标识符/方法名」
> 受 `JasperLexer.g4` 硬关键字阻塞，**仍未修复**（需把 `Go` 改为软关键字，见第五节 #2）。
> **本提交** 另修复 `③编译器缺陷` #14（枚举比较 / `switch` / `match`），**#3**
> （泛型类型变量擦除为 `Object` + 实现类补桥 + 接口分派），并**部分修复 #8**
> （「成员方法调用实参宽化」已修——调用点改用声明的擦除签名；「字段赋值宽化」仍开放）。
> 修复详情见第五节各条目的「状态」行；回归守门见
> `jasper.R2dbcDefectRegressionTest`（#1/#2/#4/#11）与
> `jasper.GenericDispatchTest`（#3，及 #8 的成员调用部分）。
> **本提交（Q21 / C3+C7）** 另修复 `③编译器缺陷` #6（继承方法描述符退化 `void`）与
> #9（类继承不参与赋值子类型）；回归守门见 `jasper.InheritanceTest`。

## 四、探针为通过验收而采用的规避

> 说明：下列规避是探针源码**编写时**为绕开缺陷所采用的写法，每条都对应第五节的一个编号。
> 所有规避都只影响**探针自身写法**，不改动任何 `.g4` 或编译器实现。
> 第 7 条（`finally` #4）已在**本批（commit `7e66eca`）** 修复后从探针中**移除**；
> #1（`await`）、#2（`go` 执行语义）、#11（数组读）对应的规避（见第五节各条目）同样已移除。

1. **元素类型固定为具体 `Row`，而不是泛型 `T`**（#3）。泛型形状保留在
   `ReactiveStreams.jas`（`Rx*` 前缀）中只作声明；运行路径全部走 `std` 包的
   `Publisher`/`Subscriber`/`Mono`/`Flux`（元素类型 `Row`）。
   （**#3 已修复（本提交）**：该规避现已不再必要，泛型接口可经接口变量分派；探针源保留
   原写法以保留历史证据，未在本轮改写。）
2. **具体表达式先赋给接口类型局部变量，再传给接口形参/存入接口字段**（#8）。
   例：`var seedSub: Subscription = new MemorySubscription(new Resource()); this.seed = seedSub;`
   以及 `var gateSubscriber: Subscriber = gate; this.source.subscribe(gateSubscriber);`。
   （**#8 部分修复（本提交）**：「传给接口形参」已可直接传具体类型；「存入接口字段」仍需
   接口类型局部变量兜底。）
3. **字段读取改用取值方法**（#5）：`Row` 的字段私有，暴露 `id()`/`name()`；
   这样跨文件读取不再依赖「声明字段的文件先被翻译」的排序。
4. **继承来的方法用声明该方法的类型作接收者**（#6）：
   `var error: R2dbcException = new R2dbcException(...); return error.sqlState();`。
5. **多态一律走接口，不做「子类赋给基类变量」**（#9）：`MemoryConnection` 先赋给
   `Connection` 接口再使用；`Task` 等不依赖类层次赋值。
6. **订阅者不保存可空的接口字段**（#10）：取消需要的 `Subscription` 句柄通过参数即取即用。
7. ~~**资源释放统一用 `defer`**（#4），不使用 `finally`。~~ —— **已移除**：
   `main()` 现用标准 `try/finally` 兜底释放 `statement`/`conn`，
   `MemoryResultSet.getInt/getString` 用 `try/finally` 复位 `wasNull`（#4 已固定回归）。
8. **多顶层类型文件把 `package` 放在文件首个非空白位置**（#12）：文件头注释若有，
   必须放在 `package` 之后或省略。

## 五、发现明细

### 表达不了

#### 1. `await` 生成对 `unknown` 的调用
- 类别：`③编译器缺陷`
- 状态：**已修复（commit `7e66eca`）**——`await e` 现等价于「操作数同步求值完成」，
  直接返回操作数的值，IR 类型即操作数类型；变化点隔离在
  `JasperToIrTranslator.translateUnaryOp` 的 `"await"` 分支。
  `jasper.R2dbcDefectRegressionTest.await value participates in arithmetic and string concatenation` 覆盖。
- 现象：`await e` 能通过语法与类型检查，但降级为对名为 `unknown` 的方法的调用。
- 触发代码：
```jasper
package std;
public function compute(): int32 { return 5; }
public function main(): int32 { var x: int32 = await compute(); return x; }
```
- 期望：挂起等待 `compute()` 的结果，或至少报「不支持 await」的编译错误。
- 实测：运行期 `NoSuchMethodError: 'int std.main.unknown(int)'`。
- 探针规避（**已移除**）：原用同步占位 `Task`（`complete()`/`isDone()`/`join()`）
  表达「任务/挂起」形状。现 `taskValue()` 直接用 `await task.join()` 取值
  （`go task.complete(...)` 启动任务），`main-result` 中 `taskValue()=7` 保持不变。

#### 2. `go` 被保留为关键字且被执行语义破坏
- 类别：`①语法层`
- 状态：**执行语义已修复（commit `7e66eca`）**——AST 新增显式 `JasUnaryOp("go")`，
  翻译器把语义编码进 `callNames`，后端 `"go"` 分支同步触发任务体的副作用并把结果
  丢弃为 `Unit`，不再生成加载期校验失败的字节码。
  `jasper.R2dbcDefectRegressionTest.go executes the task body and its side effect is observable` 覆盖。
- 状态（后半）**仍未修复**：`go` 可作普通标识符/方法名——`JasperLexer.g4` 把 `Go`
  定义为**硬关键字**，`t.go()` 在解析阶段即被拒绝。真实原因：软关键字需要改动 `.g4`
  （`Go` 只能在语句起始处生效，或改为 `{_input.LA(1)==...}?` 谓词的软关键字）。
  **本批任务硬约束禁止改动任何 `.g4`，故此处只作记录、不伪装修复**；回归测试
  `go as an ordinary identifier is blocked by the hard keyword` 以「断言当前被拒绝」
  的形式把该阻塞固定下来，一旦语法放开该用例会失败并提醒更新为正向断言。
- 现象：`go` 不能作为标识符/方法名；`go f()` 能编译但生成的代码在加载时校验失败。
- 触发代码：
```jasper
package std;
public var side: int32 = 0;
public function bump(): void { side = side + 1; }
public function main(): int32 { go bump(); return side; }      // 修复前触发 VerifyError
```
```jasper
package std;
public class T { public function go(): int32 { return 1; } }   // 仍语法错误（阻塞）
```
- 期望：`go` 作为异步调度原语（分离执行），或明确不支持并给编译错误；
  `go` 不应是保留字以至于不能当方法名。
- 实测（修复前）：前者 `VerifyError: Bad type on operand stack`；
  后者 `Syntax error: mismatched input 'go' expecting Identifier`。
- 实测（修复后）：`go bump();` 正常执行、副作用可观察；
  `t.go()` 仍报 `mismatched input 'go' expecting Identifier`。
- 探针规避（**执行语义部分已移除**）：`taskValue()` 现直接用 `go task.complete(...)`
  启动任务；`go` 作方法名的写法仍不可用，异步形状依旧统一用订阅协议表达。

#### 3. 泛型类型变量作形参/返回值 → 描述符为字面量 `LT;`
- 类别：`③编译器缺陷`
- 状态：**已修复（本提交）**——JVM 泛型擦除与桥接方法修复：类型变量（含 `ID` 这类多字母
  形参）现按声明登记并擦除为 `java/lang/Object`（不再产出 `LT;`），编译期为泛型接口实现类
  自动补 `ACC_PUBLIC|ACC_BRIDGE|ACC_SYNTHETIC` 桥接（含 `S<int32>` 的装箱/拆箱），调用点
  改用接口声明的擦除后描述符。触发代码现可正常运行。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/GenericDispatchTest.kt`（真编译 + `URLClassLoader`
  加载执行 + `javap` 形状断言）。以下「实测」为修复前证据，保留作历史记录。v1 边界同 JPA #1
  （有界形参仍擦除为 `Object`；通配符 / F-bounded / 多级泛型继承未覆盖）。
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
- 状态：**已修复（commit `7e66eca`）**——`JvmIrBackend` 对 try/catch/finally 统一生成
  ①正常收尾、②异常兜底处理器、③`IrReturn` 返回前补跑（`MethodContext.finallyStack`
  记录尚未执行的 finally），三条路径各执行一次；嵌套 try 按「内层处理器先登记」保证
  匹配优先级。`jasper.R2dbcDefectRegressionTest.finally runs exactly once on normal
  exception and early return paths` 覆盖。
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
- 探针规避（**已移除**）：原把全部资源释放改用 `defer`；`main()` 现改回标准
  `try/finally` 释放 `statement`/`conn`，`MemoryResultSet` 亦用 `try/finally` 复位 wasNull。

#### 14. 枚举比较触发编译器内部错误
- 类别：`③编译器缺陷`
- 状态：**已修复（本提交）**——根因与 JDBC #6 同源：`Color.Red` 被翻译成「对类名求值的
  `GETFIELD`」。现改为 translator 预登记枚举常量符号并保持 `IrEnumEntry` 表示，读取走
  新增 `IrGetEnumValue`（后端发 `GETSTATIC`），后端据 `IrEnumEntry` 发射
  `public static final ACC_ENUM` 字段并在 `<clinit>` 中 `PUTSTATIC` 初始化；此前会
  `ClassFormatError` 的 `values()` `CHECKCAST` 也一并修正。回归守门：
  `jasper-compiler/src/test/kotlin/jasper/EnumTest.kt`。
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
- 状态：**已修复（本提交）**——`JasperToIrTranslator` 预登记阶段新增继承成员索引传播
  （`collectSuperTypeNames` + `propagateInheritedMemberIndexes`）：祖先类型的成员返回类型与
  声明擦除签名沿父类/接口链补进子类型索引，故「子类接收者调用继承方法」不再回退全局
  last-writer-wins，描述符也不再退化为 `void`。回归守门见 `InheritanceTest` 的
  `cross file inherited method keeps correct return descriptor`（跨文件、使用方排前）与
  `method descriptors are correct and never degenerate to void`。
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
- 状态：**部分修复（本提交）**——「**成员方法调用**」那一半已修复：调用点对已登记成员
  改用「声明的擦除签名」发描述符，故 `this.consume(new C())` / `consume(c2)` /
  `consume(this.c)` 现生成 `consume(Lstd/I;)I` 而非 `consume(Lstd/C;)I`
  （实测三处均通过，返回 `3`；见 `GenericDispatchTest` 的仓库/接口分派用例同源机制）。
  **仍未修复**：字段赋值位 `this.slot = c`（字段声明 `I`、右值 `C`）仍按右值类型写
  `PUTFIELD slot:Lstd/C;`，运行期 `NoSuchFieldError`——本轮任务（Q20 泛型擦除/桥接）
  未覆盖字段赋值的宽化，保留待后续。
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
- 状态：**已修复（本提交）**——`TypeChecker` 新增 `classExtends` 层次表并让 `isSubtype` 沿
  类继承链走传递闭包（`A <- B <- C` 时 C 亦是 A 的子类型），同时补了类/接口循环继承诊断与
  override 返回类型协变校验。子类实例现可赋给基类变量、作形参/返回值，并经基类类型虚分派
  到 override、经继承解析调到未 override 的方法与继承字段。回归守门见 `InheritanceTest`
  （13 个用例：虚分派/继承方法/形参返回值/继承字段/三层继承/接口+继承组合/描述符/负向/跨文件）。
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
- 状态：**已修复（commit `7e66eca`）**——`TypeMapper` 新增 `irArrayElementType`，
  翻译器 `translateArrayAccess` 用它确定 `a[i]` 的元素类型（不再硬编码 `unit`），
  使 `var x = a[i]` 立即推断出正确类型、数组读可直接做成员访问/比较操作数。
  `jasper.R2dbcDefectRegressionTest.array element read is immediately usable` 覆盖。
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
- 探针规避（**已移除**）：原 `MemoryResultSet.getInt/getString` 先把 `this.rows[cursor]`
  读进 `Row` 局部变量再访问方法；现直接对 `this.rows[this.cursor]` 做成员访问。

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
- 状态：**已修复（本提交）**——用户声明的 `static` 字段现按 JVM 静态字段发射：字段带
  `ACC_STATIC`，读写使用 `GETSTATIC`/`PUTSTATIC`，非 final 静态初值在 `<clinit>` 中执行，
  实例访问与类名访问共享同一份静态值；`类名.实例字段` 给出明确编译错误。
  回归见 `jasper.StaticFieldTest`。以下「实测」为修复前证据，保留作历史记录。
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
- 探针处置（本批更新）：原 `main()` 曾用 `defer { conn.close(); }` / `defer { statement.close(); }`
  作为对照；为移除 #4 规避，`main()` 已改用标准 `try/finally`，R2DBC 探针**不再使用 `defer`**。
  该缺陷的回归守门改由 `jasper.ProbeDefectRegressionTest.defer does not leak into later functions`
  承担（`defer` 作用域仍被单测覆盖），故此处不再保留探针对照。

## 六、探针「想写但写不出」的 R2DBC 形状清单

以下 R2DBC 惯用写法在当前语言下无法直接表达，探针只能降级或改用同步占位：

1. ~~**`Publisher<T>` / `Mono<T>` / `Flux<T>` 泛型响应式协议** —— 类型变量描述符缺陷（#3）~~：
   **本提交已修复**，泛型接口可经接口类型变量分派；探针运行路径仍用 `Row` 具体元素类型，
   泛型形状保留在 `ReactiveStreams.jas`（只声明）。
2. ~~**`await publisher` / 挂起取值**~~ —— `await` 已修复（#1，`7e66eca`）：
   `await e` 返回 `e` 的值，可参与算术/拼接；探针 `taskValue()` 已使用 `await`。
   注意：当前仍**没有真正的挂起运行时**，`await` 只是「同步求值完成」。
3. ~~**`go task` 分离调度**~~ —— `go` 的执行语义已修复（#2，`7e66eca`）：任务体同步
   触发、副作用可观察；但**没有真正的调度器/线程切换**，且 `go` 作方法名仍被
   硬关键字阻塞（未修）。
4. **背压窗口 `request(n)`** —— 见第七节「未做」；当前用「订阅即尽力投递 + `cancel`」。
5. **`Flux<T>.collect(): T[]` 物化结果集** —— 泛型返回（#3，**本提交已修复**）：类型变量
   返回不再写出 `LT;`/`[LT;`；探针为保留历史改用 `collect(): Row[]`。
6. **`Row.get(0, Int::class)` 之类的按列名/类型取值** —— 泛型 map 缺失（#16），
   改为强类型字段 + `id()`/`name()` 方法。
7. ~~**`finally { connection.close(); }` 资源兜底**~~ —— `finally` 已修复（#4，`7e66eca`），
   探针 `main()` 已改用标准 `try/finally` 释放资源。
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
- **`await`/`go` 的真异步实现**：#1/#2 的**语言语义**已修复（`7e66eca`），但只做到
  「同步求值 / 同步触发副作用」，仍**没有真正的挂起运行时、调度器或线程切换**；
  本探针未构造可运行的异步运行时。
- **真实 `Row`/`Column` 元数据**：不实现按列名索引、类型协商、`null` 位图等。

## 八、结论

- 探针可**真编译、真加载、真运行**，说明 Jasper 已具备「接口契约 + 类实现 + 跨文件实现 +
  内存状态 + 错误层次 + 资源释放 + 接口常量 + 对象数组」的表达能力，足以拼出一个可运行的
  R2DBC 假实现，并压出订阅/取消/错误传播/资源释放的可观察行为（`main-result=35`）。
- 但 18 个摩擦点里有 13 个是 `③编译器缺陷`，且大多是**静默的错误字节码**
  （`VerifyError`/`ClassFormatError`/`NoSuchMethodError`），而不是清晰的编译期报错；
  这会诱导用户把编译器 bug 误当成自己代码的问题。本批已修复其中 3 条
  （#1/#4/#11，commit `7e66eca`）并修复 #2 的执行语义；**本提交**又修复 #14
  （枚举比较 / `switch` / `match`）与 #3（泛型擦除 / 桥接 / 接口分派），并部分修复 #8
  （成员调用实参宽化）。
- 与「异步 / 挂起」最相关的 `await`（#1）、`go`（#2）现已可用其**语言语义**
  （`await` 取值、`go` 触发副作用）；但**仍不具备真正的异步运行时**（无挂起、
  无线程切换）。泛型响应式协议（#3）的**分派障碍已修复（本提交）**——泛型接口可经接口
  变量分派；本探针运行路径仍以 `Row` 具体元素类型 + 「同步语义 + 回调协议」逼近真实
  异步响应形状。
