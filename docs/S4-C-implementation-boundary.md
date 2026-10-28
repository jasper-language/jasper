# S4-C 实现切分与语言边界

初始记录基于 `77ac9e1`。用户后续已决定：显式标记与自动推导两种方式都提供；await 普通值现阶段保留兼容。显式标记名称与适用边界尚未确定，不新增关键字。

## 最初基线事实（后续实现进度见文末）

- 文法已有 `Await unaryExpression`，无需为了识别 await 修改 `.g4`。
- AST 使用 `JasUnaryOp("await", operand)`。
- `TypeChecker.inferExpression` 仍返回操作数类型，未将 Future<T> 解包成 T。
- `JasperToIrTranslator.translateUnaryOp` 直接翻译操作数，不挂起、不等待。
- `R2dbcDefectRegressionTest` 明确覆盖 await 普通 int/string 返回值；不能随意删除或改成拒绝测试。
- 运行时有 Future.onComplete 和 Executor.schedule，但尚无函数级 continuation 变换。

## 语言边界与已确认方向

### 1. 挂起函数如何识别

以下两种方向均须提供，尚未实施：

- 显式标记：利用现有注解语法声明允许挂起的函数（注解名称待定），检查调用边界。
  不增加关键字，但这是新的语言语义，需要用户决定。调用关系更易审查，仍需定义
  接口实现、重载、函数对象和 override 的一致性。
- 自动推导：从 await 及其调用关系传播挂起能力。源码更少，但跨模块公开签名与
  函数对象仍需明确记录该能力，不能只扫描当前函数体。

不应未经裁决把所有普通函数都改成异步返回，也不能让普通调用暗中阻塞取值。

### 2. await 普通值保留兼容（已决定）

- 保留兼容：Future<T> 走真正挂起并得到 T；其他值保留现有同步求值语义。
- 收紧规则：只接受 Future<T>（或以后明确的可等待协议）。这改变既有可运行程序，
  需要用户明确决定迁移，不可通过删除旧测试静默落地。

## 实现次序（不把部分实现称为完成）

1. 固定上述边界后，给 await 增加独立语义分析与可追踪的源码位置。
2. 第一片只支持明确声明范围内的直线控制流：函数级划分挂起段，保存跨段活跃局部，
   发出可恢复状态机；不支持的控制流明确诊断，不能同步透传。
3. 已完成 Future 与挂起登记竞争：完成恰好一次、恢复请求不丢失、同一 continuation
   不并发执行；恢复经 Executor 入队，不能在通知回调中递归执行整个后续函数。
4. 把入口接到结构化作用域的子任务登记、终态发布和 settled 回收。
5. 扩展条件、循环、嵌套调用、try/catch/finally/defer；取消检查与清理必须保留。
6. 补 Deadline/取消原因传播及端到端场景。不能用 Future.get 冒充 await。

## 首片验收

- 单工作线程：一个任务挂起后，另一个任务能执行并完成其等待的 Future。
- 同一操作数只求值一次；挂起前后局部值、执行顺序与返回值正确。
- Future 在注册前、注册中、注册后完成均不丢恢复；完成失败与取消走正确通道。
- 父作用域等待的是子任务真实退出；挂起不能提前标记 settled。
- JVM class 版本仍为 52；运行时逻辑使用 Jasper；不使用动态代码生成。
- 状态机与注册/唤醒竞争提供 TLA+ 模型及真实编译运行测试。

## 本轮改动

只修正翻译器注释中“未来调用阻塞式取值即可”的过时建议，并整理本记录。
没有改 `.g4`、await 行为、测试断言或公开 API。后续用户已作上述裁决，继续推进已确定部分。


## S4-C 第一片：前端结果类型与代码生成保护

识别 std.Future 的实际包身份，await Future<T> 的前端结果按 T 检查，一次只解包一层。
用户同名 Future 不作为标准库 Future，普通值 await 保持兼容。
在 continuation 降级接通前，对真实 Future 的 await 产生明确的未实现诊断并阻止生成
字节码，不能误把 Future 本身作为结果。此诊断是实现阶段限制，不是永久语言规则。
本片没有实现挂起函数能力传播、显式标记、continuation 或恢复调度。


### 第一片验收结果

通过指定 vm-run.sh 在 Jasper 构建虚拟机执行针对性测试与全量 Gradle test。
AwaitFrontendTest 5/5，通过固定种子 20261025 的 12 组类型组合；原有普通值 await
回归保持通过。全量 746 tests / 0 failures / 0 errors / 1 skipped（745 实际执行），
Gradle BUILD SUCCESSFUL，1 分 25 秒；部分未受影响任务使用 Gradle 缓存。

首轮结果类型负例暴露限定类型 std.Future<int32> 的泛型实参在 AST 中丢失，已修复
TypeSuffixDot 的实参读取并复测通过。没有修改 .g4。代码规范与 FV 产物检查通过。
LANG-AWAIT-01 登记为 tested；这不表示已实现或证明协程挂起。

后续接续：函数级挂起能力分析、状态机分段与活跃局部保存，再接 Future 完成通知和
Executor 恢复调度。显式标记与自动推导均为已确认方向，标记名字仍未裁决。


## S4-C 第二片：内部恢复调度门

新增 Jasper 实现 ContinuationDispatch（内部实现接口，不是稳定业务 API）。
负责合并恢复请求、串行运行执行段、保留运行中到达的唤醒、终态丢弃陈旧队列项。
执行段与 Executor 均在锁外调用。调度拒绝和执行段异常终止调度门并传播原异常，
后续生成的任务桥负责发布对应 Future 失败及回收。

要求执行器入队而不内联执行（JvmExecutor 满足；任意自定义 Executor 未必满足）。
每段必须自行在等待时返回，不能在段内阻塞 Future.get。此层没有生成程序计数器、
保存局部值或自动识别 await；测试中的两段程序是手写协议夹具，不是真协程语法示例。
ContinuationDispatch 的构造和 resume/finish 等签名目前只供编译器内部原型使用。


### 第二片验收结果

Jasper 构建虚拟机中 ContinuationDispatchTest 5/5；全量 Gradle test 成功（1 分 28 秒），
JUnit XML 总计 751 / failures 0 / errors 0 / skipped 1，750 实际执行；未受影响任务
按 Gradle 缓存判定。六组 TLA+ 配置均重跑通过，新增恢复调度门模型为 6 个可达状态。
FV 产物与代码规范检查通过，未修改文法。

覆盖：未完成 Future 的通知入队、已完成 Future 注册时唤醒保留、终止后陈旧任务、
拒绝调度、执行段异常禁止重放。尚无真实多线程压力测试，不声称模型验证了 JVM
内存模型；下一步需要补实际线程池单线程进展与多线程串行恢复测试，再接编译器生成桥。


## S4-C 第三片：真实线程池验证

新增 ContinuationThreadPoolTest，在构建虚拟机实际编译并运行 Jasper 测试执行段。
两个测试通过，Gradle 针对性验收 25 秒：

- 单个 JVM 工作线程：消费者注册 Future 完成通知后返回；同一线程继续运行生产者，
  完成后再执行消费者第二段，结果 42、执行次数 2。执行段没有调用阻塞 get。
- 四个 JVM 工作线程：首段停在测试屏障，八个测试驱动生产者各发 100 次恢复请求；
  800 次请求全部返回后首段仍只执行一次；释放屏障后仅执行一个后续段，重叠计数 0。

等待均有测试驱动超时，失败路径释放屏障、终止生产者并关闭 Jasper 线程池。
Kotlin 仅负责编译加载、调用 API 与有界等待；执行状态和恢复逻辑仍写在 Jasper。
本次只有测试与文档改动，针对性 2/2 通过后未重复跑全量和未修改的六组模型。
不把手写状态机夹具描述为源码 await 已实现，也不把一次并发场景称为穷尽证明。

接续点：编译器生成桥仍是下一步，需处理函数级挂起能力与活跃局部保存。


## S4-C 第四片：直线体分段与局部布局规划原型

新增编译期 LinearSuspensionPlanner，接收经过类型检查的 AST 与「是否真正挂起」谓词，
为直接变量初始化 await 和 return await 规划边界；普通值 await 不构成挂起。
每个边界记录源码节点、语句位置、结果接收绑定以及进入该位置前已存在且后续引用的
局部集合。await 结果尚未产生，不作为该边界的已保存局部；前一边界产生的结果可以
在后一边界保存。两个结构相同的 await 保留不同节点身份，避免恢复位置合并。

只接受明确列出的直线语句和表达式；分支、循环、闭包、嵌套挂起等返回不支持节点，
不提供部分计划。分析是保守引用集合，没有赋值杀死分析，可能多保存。
此规划器尚未接入编译器主流程，也没有字节码发射；不会因此声称源码 await 已运行。


第四片在构建虚拟机针对性验收 5/5 通过（18 秒），包括 64 组固定种子布局性质。
代码规范和 FV 产物检查通过。未重复运行未修改的运行时模型和全量套件；本片是隔离的
规划原型，不改变现有代码生成。接续应先把检查器的真实挂起信息提供给规划器，再实现
执行段发射和生成类字段布局，保留未实现诊断直到端到端桥真正可运行。


## S4-C 第五片：真实类型事实接线

JasUnaryOp 新增内部 awaitResultType；检查器每次推断先清除旧事实，仅对真正 std.Future
回写解包一层的结果类型。普通值与同名用户类型不设置挂起标记。直线规划器默认读取
该事实，规划点带结果类型，后续发射器无需重新猜测 Future 类型身份。

新增集成测试使用权威 ANTLR 解析源码、buildSourceFile、符号收集与实际类型检查，
验证同一函数中的普通 await 与 Future await 分离、跨段局部布局、源位置和结果类型。
第二例复用原 AST 函数体，将参数改为普通值后重新检查，验证清除过时标记。
这不是源语言显式挂起注解，不确定注解名称，也没有实现函数级能力传播或字节码发射。


第五片验收：构建虚拟机三组针对性测试 12/12 通过；全量 Gradle test 成功（1 分 26 秒），
JUnit XML 为 760 tests / 0 failures / 0 errors / 1 skipped，759 项实际执行。未受影响
任务按 Gradle 缓存判定。首轮集成测试误用 visitor 入口得到 null，已改为项目正式的
buildSourceFile 入口后复测通过；没有绕开真实解析或放宽断言。代码规范与 FV 检查通过。

当前剩余主链：函数挂起能力分析 -> 执行段与字段发射 -> 生成任务桥 -> 结构化异步 join，
之后扩展循环/分支/异常/清理及取消/Deadline。公开显式标记名称仍待用户确定，不自行新增。


## S4-C 第六片：直接调用图挂起效果推导

检查器给 JasCall 回填实际选择的源码函数声明，并在重查时清除旧调用信息。
SuspensionEffectAnalysis 按声明身份求不动点，不能用同名函数合并重载或跨包声明。
真实 Future await 是挂起种子；调用者沿已解析直接调用边获得 SUSPENDING。
无未知边的闭合无种子图为 SYNCHRONOUS；动态分派、外部声明和未覆盖结构为 UNKNOWN，
且未知性向上游传播。已确认挂起优先于未知，表示该函数已确定可能挂起。

CompilePipeline 现在带效果快照；多文件编译收集全部文件的顶层与类型成员函数，
共享一个分析结果，避免逐文件误把已知跨文件调用当外部未知。现有编译行为和错误
保护不变；UNKNOWN 只供后续阶段判断，并不是本片新增的源程序拒绝规则。

测试覆盖：同名声明身份、种子/无种子递归、未知边、64 个固定种子八节点调用图与
独立可达性 oracle、真实 ANTLR + 类型检查的间接挂起调用，以及实际多文件管线共享。
当前不实现显式标记名称、接口效果约束、挂起 ABI、生成状态机和任务桥。


静态重载补充：当前检查器该路径仍存在首候选限制，因此多个同名静态声明时不回填
可靠调用边，效果为 UNKNOWN；新增测试锁定这一保守边界，不借能力推导顺带修改
现有重载规则。实例虚分派同样不因选中基类签名就当作确定的实现。


第六片最终验收：构建虚拟机全量 Gradle test 成功（1 分 41 秒），JUnit XML 为
767 tests / failures 0 / errors 0 / skipped 1，766 实际执行；效果分析 4/4、前端集成
5/5。部分未受影响任务使用缓存。增加静态重载保守性测试后再次跑了全量，最终结果
对应当前源码。代码规范与 FV 检查通过；没有修改文法、公开挂起标记或函数 ABI。

接续优先：明确内部挂起入口与状态机发射接口，使用现有类型、效果快照和局部布局，
开始将生成类接入 ContinuationDispatch；在端到端可运行前继续保留 await 未实现诊断。


## S4-C 第七片：任务桥结果绑定与晚到调度失败

接入生成任务桥前发现一个必须补齐的路径：运行中请求恢复会延迟到调度门 finally
重新入队；如果此时执行器拒绝，异常位于生成执行段之外，仅靠该段自身 catch 无法
为结果 Future 发布失败。执行器可能只记录未捕获异常，等待者将一直看到 Pending。

ContinuationDispatch 现在允许内部桥在首次 resume 前调用 bindResult 绑定结果，
绑定只可一次，启动后或终止后拒绝改绑。调度拒绝发布 schedule 失败，段异常发布
platform 失败；Future 已终态时不覆盖。失败通知异常也在 finally 标记 settled。
未绑定时保留已有传播行为。成功和主动取消仍由未来生成桥负责，不能把 finish 当成功。

新增测试覆盖延迟重入队拒绝、执行段失败、绑定一次/启动后拒绝；失败结果通过
awaitTaskSettled 验证回收，执行测试有超时隔离。延迟拒绝用例还让失败通知回调抛错，
验证 finally 不被跳过。新增有限状态模型检查绑定冻结与失败结果可见性。

本片仍未发射源码 await 状态机，也未选择显式挂起标记名称。


第七片最终验收：构建虚拟机全量 Gradle test 成功（1 分 37 秒）；JUnit XML 为
770 tests / failures 0 / errors 0 / skipped 1，769 实际执行。调度门 8/8 与真实线程池
2/2 均通过；最终源码包含通知回调抛错和 awaitTaskSettled 回收验证。七组 TLA+ 配置
重跑全部通过，新增绑定模型 21 个可达状态。FV 与代码规范通过，未改文法或公开挂起 ABI。

后续仍需实际发射并运行生成状态机。本轮补齐的是该生成桥必须依赖的异常结果路径，
不能把内部 bindResult 接口或手写测试执行段称为自动 await 已完成。


## S4-C 第八片：编译器真实发射单挂起点直线 int32 状态机

本片把 `LinearCoroutineEmitter` 接入 `JasperCompiler` 的真实编译路径，而不再只由测试
直接调用发射器。类型检查通过后，`CoroutineLowering` 使用与第六片相同的共享挂起效果
快照挑选函数：只有直接调用图判定为 `SUSPENDING`、且形态落在下述支持集内的函数会生成
`<Owner>_<函数名>_Coroutine`（顶层为 `<函数名>_Coroutine`）内部帧类。生成帧复用既有
`ContinuationDispatch.bindResult` 桥、`Future.onComplete` 与 JVM 执行器；生成 AST 先登记
符号并用同一个 `TypeChecker` 复查，再交给既有 IR/JVM 后端，class 版本仍为 52，不使用
动态代码生成。原函数声明在降级结果中由生成类替代；这仍不是公开源码挂起调用 ABI。

### 已支持形态

- 类/枚举静态函数或顶层函数，非泛型、非重载；
- 返回 `int32`；参数为 `int32` 或 `std.Future<int32>`；
- 函数体是直线控制流：恰好一个 `await`，且只出现在一个局部变量初始化中；其余语句是
  带初始化器的 `int32` 局部声明；最后一句为 `return`；
- await 操作数与返回值可使用局部/参数、字面量、纯算术/比较/逻辑运算、属性访问、调用和
  显式 cast；自由标识符按类型名/静态函数名保留；含 `this` 的实例接收者明确拒绝；
- 生成帧构造参数为 `(Executor, Future<int32>, 原参数...)`，`start()` 只请求第一次恢复；
  输出 Future 由调用方提供，构造时经 `bindResult` 绑定。

### 明确诊断的形态（不写 class，不退化为同步求值）

- `return await`、多于一个 await、分支/循环/try/defer 中挂起、嵌套 await；
- 仅通过调用传播挂起而没有直接 await 的调用者；
- 自身形态合法、但在非 await 位置同步调用另一个挂起函数的函数（被调函数已由生成帧
  替代，同步调用既无链接目标也会退化为同步求值；验收时由独立探针发现并补拒绝规则）；
- 非 `int32` 结果、泛型/非静态/重载函数、生成类名冲突；
- await 操作数或返回值出现发射器未支持的表达式；
- 字段初始化器、构造器、lambda 等函数外位置的真实 await（`SuspendingAwaitScanner`
  作为降级后的残留安全网）；普通值 await 的兼容语义不变。
  以上诊断统一包含 `non-blocking await lowering is not implemented yet`，便于沿用既有
  前端断言，同时给出具体函数与原因。

### 验收结果

新增 `LinearCoroutinePipelineTest` 5 项，全部经真实 ANTLR 解析、标准库 + 用户源码编译、
生成 class 写出与 JVM 反射驱动执行：

1. 单工作线程：生成帧注册回调后返回，生产者任务完成输入，随后恢复并得到 26；
2. 输入在注册前已完成仍恢复，结果 26；
3. await 操作数与挂起前表达式各只执行一次（固定种子 20261027 的 8 组布局 oracle）；
4. 失败快照原样发布到结果 Future 的 Failed 通道，取消发布 Cancelled，二者均通过
   `awaitTaskSettled` 验证回收；
5. 六类不支持形态（含同步调用挂起函数）均产生明确诊断且输出目录没有 `.class`。

生成类单独断言 major version 52。`LinearCoroutineFrame.tla` 有限模型检查 47 个生成状态、
32 个可达状态、0 错误，覆盖“保存局部 → 注册 → 注册前/后回调 → 挂起返回 → 恢复”的
顺序及“运行中不重叠执行段”不变量；模型抽象掉锁、线程调度与 JVM 内存模型。

构建虚拟机内 `verification/run-s4-models.sh` 八组配置全部通过，日志写入
`evidence/s4-models/`；新增模型日志为 `evidence/s4-models/LinearCoroutineFrame.log`。
构建虚拟机全量 `./gradlew test --no-daemon` 成功（1 分 50 秒），JUnit XML 为
777 tests / failures 0 / errors 0 / skipped 1，776 实际执行。

复用外部 `SymbolTable` 的单文件 `compileToPipeline` 重载只看到当前文件，拿不到完整直接
调用图，因此仍保持失败关闭：真实 await 继续得到同一明确诊断，不会同步透传；需要自动发射
时应使用会运行共享挂起效果分析与降级的 `compileToPipeline` / `compileAll` 入口。

本片仍不提供公开挂起调用 ABI、显式挂起标记、多 await/分支/循环/清理后继、结构化作用域
自动登记、取消原因或 Deadline。Kotlin 编译器实现按 FV-B 交付：效果/降级链有真实端到端
契约测试、固定种子布局 oracle 与 TLA+ 模型；性质登记见 `LANG-CONT-LOWER-01`。


## S4-C 第九片：生成帧接入结构化作用域

本片完成「实现次序」第 4 条：编译器生成帧可作为 Jasper 运行时 `TaskGroup` 的子任务
启动，登记、终态发布、失败级联、取消唤醒与 settled 回收全部由 Jasper 代码完成，
Kotlin 侧没有运行时状态机。

### 运行时入口（Jasper）

- 新增内部协议 `std.CoroutineTask<T>`：`resultFuture()` 返回帧构造时已绑定的结果
  Future，`start()` 只请求首次恢复。它不是公开源码挂起调用 ABI。
- `TaskGroup.launch<T>(task)`：关闭后抛 `TaskGroupClosedError` 且不启动帧；否则先登记
  结果 Future，再在结果上挂 `ChildFailureRelay`（Failed 时调用 `onChildFailure`，普通组
  据此取消兄弟；成功与取消不级联），最后 `start()`。执行器拒绝首次恢复时返回已失败
  （kind=schedule）且已 settled 的结果，并照普通组语义级联。
- 登记时若作用域根令牌已取消（显式 `cancel` 或普通组失败级联之后），先把结果发布为
  Cancelled，帧不执行任何业务段，与 `spawn` 的令牌继承语义一致。该规则由新模型的
  `NormalCascade` 反例发现：缺少检查时，已失败普通组里新 launch 的帧会照常运行。

### 生成帧协议（编译器）

- 生成帧额外实现 `std.CoroutineTask<int32>`，新增 `resultFuture()`；
- `start()` 先订阅自身结果 Future 再请求恢复：作用域取消结果时帧被唤醒；
- `run()` 入口先检查结果是否已终结：是则不执行任何业务段，直接在 finally 中关闭调度门
  并标记 settled。挂起中的帧因此在被取消后无需输入完成也能回收；晚到输入只会命中已
  结束的调度门，不会复活帧。帧自己发布终态时引起的那次回调只会合并或被忽略。

### 验收结果

新增 `LinearCoroutineScopeTest` 8 项，全部经真实 ANTLR 解析、标准库 + 用户源码编译、
JVM 反射驱动执行（秒级，所有阻塞调用在有界线程中）：

1. 单工作线程作用域内两帧都挂起、逆序完成输入后各自恢复，close 正常返回且全部 settled；
2. 普通组一帧失败，挂起兄弟发布 Cancelled 并回收，晚到输入不复活，close 抛 TaskGroupFailure；
3. 监督组不级联，兄弟正常完成；确认失败后 close 正常返回，未确认时聚合（B 策略）；
4. 作用域取消唤醒两个挂起帧，结果 Cancelled 并回收，close 抛 CancellationException；
5. 已取消作用域（显式取消 / 失败级联后）中 launch 的帧直接 Cancelled，不进入任何段；
6. 关闭后 launch 被拒且帧不启动；执行器已关闭时 launch 返回 schedule 失败并已回收；
7. 固定种子 20261028 的 64 轮事件序列（3 帧、普通/监督、输入完成/失败/取消、随机作用域
   取消）与独立 Kotlin oracle 比对每帧终态、成功值与 close 结果；
8. 独立子 JVM 以 `-Xverify:all -Xcheck:jni` 加载并初始化本次输出的全部 class，生成帧与
   作用域运行时 class 版本均为 52。

另有 `LinearCoroutineScopeLincheckTest`（Lincheck 2.39 模型检查：2 线程 × 3 操作，
40 迭代 × 400 次调用，枚举 launch / 输入失败与完成 / 作用域取消 / 执行队列段的交错，
无死锁、无意外异常、@Validate 不变量成立）与 `LinearCoroutineScopeFuzzTest`（Jazzer
0.30.0：两次各 60 秒覆盖引导 fuzz，共 867,357 次执行无发现，139 条语料入库作为回归输入）。
二者使用确定性队列执行器（JDK 动态代理实现 `std.Executor`），不变量与新模型同名。

`CoroutineScopeLaunch.tla` 普通组 / 监督组两份配置均通过（1308 / 1342 个可达状态），
含 `TerminalEventuallySettled` 活性（弱公平）；模型把失败级联与作用域取消视为同一回调
批次内的原子动作，抽象掉锁、线程调度与 JVM 内存模型。

### 仍未支持

公开源码层「调用挂起函数」语法 / ABI 与显式挂起标记名称仍待裁决；测试经反射构造生成帧并
调用 `TaskGroup.launch`，源码层无法直接写出这一调用。多 await、分支、循环、try/清理后继、
实例分派、取消原因与 Deadline 仍不支持（实现次序第 5、6 条）。


## S4-C 第十片：多 await 与 if/else 分支内挂起

本片推进「实现次序」第 5 条的第一步（条件）：协程降级不再限于「恰好一个 await 的直线体」，
支持多个挂起点与 if/else 分支内挂起。循环仍不支持。

### 块规划与生成帧

- 新增 `CoroutineBlockPlanner`：把函数体切成前向基本块。每个 await 结束当前块并开启一个
  恢复块；if/else 结束当前块并开启两个分支块，未 return 的分支出口汇合到新块；return
  结束一条路径。块编号按分配顺序递增，出口目标严格大于所在块（规划器返回前自检）。
- 生成帧的 `__phase` 表示下一块编号，`run()` 中各段按编号顺序排列：一次运行从当前块开始
  顺序走到下一个挂起点或 return，已执行的块不会重放，await 操作数与挂起前表达式只求值一次。
- 恢复块入口消费完成快照：取消发布 Cancelled、失败原样发布 Failure、成功值写入绑定局部。
  挂起块先求值操作数，再检查结果是否已被外部终结（作用域取消），未终结才写下一块编号并
  注册回调；入口终态检查、调度门与 settled 回收沿用第八、九片。
- 分支内声明的局部同样成为帧字段（可能跨越分支内的挂起点），因此全函数局部名必须唯一。

### 已支持形态

- 类/枚举静态函数或顶层函数，非泛型、非重载，返回 `int32`；
- 参数为 `int32`、`bool` 或 `std.Future<int32>`；局部为显式 `int32` / `bool`（await 绑定可省略类型）；
- 语句：带初始化的局部声明、对局部的 `=` 赋值、if/else（含 else-if 链与嵌套）、return；
- 至少一个 await，每个 await 是局部初始化或局部赋值的整个右侧，结果为 `int32`；
- 表达式范围与第八片相同（局部/参数、字面量、纯算术/比较/逻辑、属性访问、调用、cast）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 循环、try/catch/finally、defer、throw、match、break/continue 中或旁的挂起；
- await 出现在条件、return 值（`return await`）或任何子表达式中；
- 存在不以 return 结束的路径、return 之后的不可达语句；
- 参数与局部重名（含两条分支各自声明同名局部）、未写类型的非 await 局部；
- 分支条件或分支体内对另一挂起函数的同步调用；
- 第八片已有的其余拒绝（传播挂起、非 int32、泛型/非静态/重载、生成名冲突、函数外 await）。

### 验收结果

- `CoroutineBlockPlannerTest` 3 项：else 全部 return 时不接汇合边（实现过程中由编译器
  「条件恒假」警告发现的真实缺陷：`elseBody?.let { … } ?: whenFalse` 会把「else 全部
  return」误当成「没有 else」）；10 种不支持形态给出原因且不返回部分计划；固定种子
  20261029 的 400 个随机程序 × 4 组输入，AST 直接解释与块状态机解释比对返回值与经过的
  挂起点数，并检查编号稠密、出口前向、全部可达。
- `BranchingCoroutinePipelineTest` 4 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM
  执行：then 分支单工作线程两次挂起后汇合得 40（挂起前表达式只执行一次，class major 52）；
  else 分支输入注册前完成得 9、else-if 提前 return 不挂起得 1202；第二挂起点失败 / 取消
  走各自通道，挂起中的帧被作用域取消后回收且晚到输入不复活；固定种子 20261029 的 48 轮
  （随机分支、输入值、输入在启动前或后完成）与 Kotlin oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：「两个 await」「分支内 await」现为支持
  形态，改由上面两组测试正向覆盖；清单新增循环内、try 内、条件中、子表达式中的 await 与
  分支内同步调用挂起函数五例，均诊断且不写 class。
- 变异检查：恢复 else 汇合缺陷后 6 个新用例失败。
- `BranchingCoroutineFrame.tla`：按端到端测试函数的 11 块控制流图建模入口检查、块原子执行、
  挂起块的终态检查与注册、共享 `__outcome` 字段上的输入回调与自身结果回调、调度门与回收；
  不变量 AtMostOnce / ResumeAfterDelivery / NoOverlap / SettledIsFinal，动作性质
  ForwardOnly / ResultStable，活性 EventuallySettled（弱公平）。1560 个可达状态无错；去掉
  入口终态检查的变异模型违反 ResumeAfterDelivery。`run-s4-models.sh` 十一组配置全部通过。

### 仍未支持

循环（需要回边与重入检查，下一片单独建模）、try/catch/finally/defer 的清理后继、await 出现在
条件或子表达式、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称。
（第十一片起 while 循环已支持，见下节。）

## S4-C 第十一片：while 循环内挂起

本片继续「实现次序」第 5 条（循环）：协程降级支持 `while` 循环，循环体内可以挂起，并支持
不带标签的 `break` / `continue`。其他循环形式仍诊断。

### 块规划与生成帧

- `CoroutineBlockPlanner` 为每个 while 开一个循环头块：进入循环的块前进到循环头，循环头只
  求值条件（为真进入循环体，为假前进到出口块），循环体末尾与 `continue` 跳回循环头（回边），
  `break` 前进到出口块。出口块在循环体之后分配，因此除回边以外的边仍是前向的；规划器返回前
  自检「每条出口要么前向、要么回到已登记的循环头」，计划里带出循环头集合。
- 生成帧的 `run()` 把按编号排列的段包进分派循环 `while (true)`：前向跳转在同一趟内顺序走到
  目标段；回边把 `__phase` 设为更小的循环头编号，本趟其余段都不匹配，于是开启新一趟。
- 每趟开头检查结果是否已被外部终结（作用域取消）：第一趟就是原来的入口检查，之后每趟对应
  一次回边。因此不挂起的循环也会在回边处响应作用域取消，帧返回后由 finally 关闭调度门并标记
  settled，不会一直占住工作线程。
- 循环内的 await 每次经过都重新求值操作数并注册一次回调；同一个已完成的 Future 在注册时同步
  回调，恢复请求由调度门合并到本段结束之后，恢复块入口消费的总是本次注册送达的快照。
- 循环体内声明的局部和分支内局部一样成为帧字段，每趟由其初始化重新写入；名字仍须全函数唯一。
- 挂起效果分析与「同步调用挂起函数」检查都覆盖了 while 条件与循环体；带后置块的 while
  视为效果未知。

### 已支持形态

在第十片的基础上增加：

- `while (条件) { ... }`，可嵌套，可与 if/else 互相嵌套；循环体内可以有 await（局部初始化或
  局部赋值的整个右侧）；
- 不带标签的 `break` / `continue`（作用于最内层 while）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- `do { } while (...)`、三段式 `for`、`for-in`；
- 带 `onComplete` / `onBreak` / `onZeroRuns` 后置块的 while；
- 带标签的 `break` / `continue`，以及循环外的 `break` / `continue`；
- await 出现在循环条件中；return / break / continue 之后的不可达语句；
- 循环条件或循环体内对另一挂起函数的同步调用；
- 第十片已有的其余拒绝（try/catch/finally、defer、throw、match、条件与子表达式中的 await 等）。

### 验收结果

- `CoroutineBlockPlannerTest` 4 项：新增 while 块形状检查（循环头 Branch、回边只指向循环头、
  break 前进到循环体之后分配的出口块）；拒绝清单换为 do-while、for、后置块、循环条件中的 await、
  带标签 break、循环外 break/continue、break 之后的不可达语句等 17 种形态；固定种子 20261029 的
  400 个随机程序（含有界 while、随机 break/continue、循环内挂起）× 4 组输入，AST 直接解释与
  块状态机解释比对返回值与经过的挂起点数，结构检查改为「前向或回到循环头」并要求全部可达。
- `LoopingCoroutinePipelineTest` 5 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行：
  单工作线程跨多趟回边逐个挂起、恢复得 36，循环之前的表达式只执行一次（class major 52）；
  输入已完成时 200 趟循环每趟同步恢复、break 提前离开、零趟循环不挂起；不挂起的自旋循环被
  作用域取消后在回边处结束并回收，工作线程随后可运行下一个帧；循环内 if 里挂起点的失败原样
  发布、循环内挂起中的帧被作用域取消后回收且晚到输入不复活；固定种子 20261030 的 64 轮
  （随机趟数、输入量级、输入在启动前或后完成）与 Kotlin oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：「循环内 await」现为支持形态，改为
  do-while 内 await、循环条件中的 await、循环体内同步调用挂起函数三例，均诊断且不写 class。
- 变异检查：分派循环只在入口做终态检查时，自旋循环取消测试失败；break 接到循环头时
  4 个用例失败（规划器形状、随机比对、端到端 break 与种子比对）。
- `LoopingCoroutineFrame.tla`：按端到端测试函数的 21 块控制流图建模（两个循环头、三条回边、
  break、循环内两个挂起点），主循环趟数上限 3，自旋循环由常量决定；每次经过挂起块重新注册
  回调，回调消耗注册并记下送达。不变量 PreludeOnce（回边不重放循环前的块）/
  ResumeAfterDelivery（恢复块消费本次送达；同一 Future 不会同时挂两个回调）/ NoOverlap /
  SettledIsFinal，动作性质 BackEdgesToHeaders / ResultStable，活性 TerminatesUnlessSpinning、
  CancelLeadsToSettled（弱公平）。4170 个可达状态无错；只在入口做终态检查的变异配置得到
  「自旋 + 已取消 + 永远在两个块之间循环」的反例。`run-s4-models.sh` 十二组配置全部通过。

### 仍未支持

do-while / for / for-in 与循环后置块、带标签的 break/continue、try/catch/finally/defer 的清理
后继、await 出现在条件或子表达式、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与
显式挂起标记名称。

（第十二片起 do-while 与三段式 for 已支持，见下节。）

## S4-C 第十二片：do-while 与三段式 for 内挂起

本片继续「实现次序」第 5 条（循环）：协程降级支持 `do { } while (...)` 与三段式
`for (初始化; 条件; 步进) { }`，循环体内可以挂起，不带标签的 `break` / `continue` 作用于
最内层循环。生成帧的分派循环与每趟终态检查沿用第十一片，不改帧协议。

### 块规划

- do-while：进入循环的块前进到一个不带工作的循环头块，循环头直接进入循环体（循环头只负责
  「每趟开始」，回边总是回到它）；循环体末尾与 `continue` 前进到循环体之后分配的条件块，
  条件块按条件回到循环头或前进到出口块，`break` 前进到出口块。循环体每条路径都 return 且
  没有跳到本循环的 break / continue 时，循环不会正常结束，其后的语句按不可达拒绝。
- 三段式 for：初始化段在当前块按普通局部声明 / 赋值降级（可以含 await，只执行一次；多个
  绑定的声明式初始化逐条降级）；循环头求值条件，省略条件等价于 `true`；循环体末尾与
  `continue` 前进到步进块，步进块执行步进段后回到循环头；`break` 前进到出口块。步进块与
  出口块都在循环体之后分配，除回边外所有边仍是前向的。
- 步进段只接受对局部的 `=` 赋值（右侧可以是直接 await）或 int32 局部的 `++` / `--`；
  `i++` / `i--` 作为语句（循环体内或步进段）改写为 `i = i + 1` / `i = i - 1`，生成帧随后经
  真实类型检查，int32 回绕语义与源码一致。
- for 初始化段声明的局部和其他局部一样成为帧字段，名字须全函数唯一：同一函数里两个 for
  都写 `var i` 会被拒绝（诊断，不写 class）。
- 挂起效果分析与「同步调用挂起函数」检查覆盖了 do-while 的条件与循环体、for 的四个部分
  （省略的段不算未知结构）以及赋值和自增表达式；带后置块的 for 视为效果未知。

### 已支持形态

在第十一片的基础上增加：

- `do { ... } while (条件);`，循环体内可以有 await；
- `for (初始化; 条件; 步进) { ... }`，三段都可省略；初始化可以是单个或多个 int32/bool
  局部声明或对局部的赋值，步进见上；循环体内可以有 await；
- 语句形式的 int32 局部 `++` / `--`。

### 明确诊断的形态（不写 class，不退化为同步求值）

- `for-in`；带 `onComplete` / `onBreak` / `onZeroRuns` 后置块的 for / while；
- await 出现在 for 条件、do-while 条件或步进段的子表达式中；
- 步进段是调用或其他非局部写入；对非 int32 局部的 `++` / `--`；
- 第十一片已有的其余拒绝（带标签或循环外的 break/continue、try/catch/finally、defer、throw、
  match、不可达语句、循环内对挂起函数的同步调用等）。

### 验收结果

- `CoroutineBlockPlannerTest` 6 项：新增 for / do-while 块形状检查（多绑定初始化在入口块、
  continue 与循环体末尾都到步进块、步进块的自增改写；do-while 空循环头直接进入循环体、
  continue 到循环体之后的条件块）；拒绝清单把已支持的 do-while、for 换成 for-in、for 后置块、
  调用作步进段、步进段子表达式中的 await、do-while 条件中的 await、bool 自增；新增固定种子
  20261031 的 400 个随机程序（while / do-while / for 混合，含随机 break/continue、循环内挂起）
  × 4 组输入，AST 直接解释与块状态机解释比对返回值与经过的挂起点数；结构检查允许 do-while
  的空循环头（出口为 Goto 到紧随的循环体）。
- `ForLoopCoroutinePipelineTest` 5 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行：
  单工作线程下 for 第 0 趟挂起、恢复后经步进回边同步跑完，随后 do-while 挂起、恢复得 37，
  循环之前的表达式只执行一次（class major 52）；输入已完成时 203 趟 for 与含 continue 的
  3 趟 do-while 同步跑完、break 提前离开 for、零趟 for 之后 do-while 仍跑一趟并挂起；空循环体
  的不挂起 for 被作用域取消后在步进回边处结束并回收；do-while 内挂起点失败原样发布、for 内
  挂起中的帧被作用域取消后回收且晚到输入不复活；固定种子 20261028 的 64 轮与 Kotlin oracle
  一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：do-while 内 await 现为支持形态，改为 for
  条件中的 await。
- 变异检查：for 的 continue 直接回循环头（跳过步进）时 5 个用例失败；do-while 的 continue
  直接回循环头（跳过条件）时 4 个用例失败。
- 帧协议未改：`run-s4-models.sh` 十二组配置复跑全部通过（`LoopingCoroutineFrame` 的回边模型
  对新增的步进块 / 条件块回边同样适用：回边只指向已登记的循环头，每趟开头检查终态）。

### 仍未支持

for-in 与循环后置块、带标签的 break/continue、try/catch/finally/defer 的清理后继、await 出现在
条件或子表达式、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称。
另：AST 构建器对多表达式的 for 初始化段（`for (a = 0, b = 0; ...)`）返回空初始化，这是协程
降级之外的既有限制，本片未改。
（第十三片起条件、return 值与算术子表达式中的 await 已支持，见下节。）

## S4-C 第十三片：条件、return 值与子表达式中的 await

本片继续「实现次序」第 5 条：await 不再只能是局部初始化 / 局部赋值的整个右侧，也可以出现
在 `if` / `while` / `do-while` / `for` 的条件、`return` 值、for 步进段以及局部写入右侧的算术
子表达式中。帧协议与发射器不变，变化全部在块规划器里。

### 提升规则

- 规划器按求值顺序（二元运算先左后右，一元运算先操作数）遍历表达式，每个 await 在当前块
  挂起，恢复块把结果绑定到一个合成临时局部（名字带 `$`，不会与源码标识符冲突；与其他局部
  一样成为 int32 帧字段），原位置改读该临时局部。每个 await 各占一个挂起点，`(await a) -
  (await b)` 先挂 a 再挂 b。
- 先于某个 await 求值、又不是被提升的 await 本身的部分，提升后会推迟到恢复之后求值，因此
  只允许字面量、局部 / 参数读取以及不会抛出、没有副作用的运算（一元 `+ - !`，二元 `+ - *`、
  比较、`&&` / `||`）。局部与参数只由本帧写入，await 操作数内的局部写入被拒绝，所以推迟
  读取不改变值。调用、`/`、`%`、属性 / 下标访问等出现在某个 await 之前时拒绝；出现在所有
  await 之后（如 `(await a) % d`）保持原位，顺序不变。
- 条件中的 await 先于分支 / 循环体的块分配：if 在条件块挂起、恢复块里分支；while / for 的
  循环头直接挂起，回边回到循环头后每趟重新挂起；do-while 的条件块挂起，最后一个恢复块按条件
  回到循环头或前进到出口块。除回边外所有边仍前向，回边仍只指向循环头。
- 循环体只经 break / return 离开的 do-while 从不求值条件（源码语义如此），条件不切块、不生成
  代码，其中的 await 不构成挂起点。

### 明确诊断的形态（不写 class，不退化为同步求值）

- `&&` / `||` 右操作数中的 await（短路求值，不能无条件提升）；
- 调用实参、属性 / 下标访问、类型转换、`new` 等位置中的 await；
- 在同一表达式里位于调用、除法 / 取余等可能抛出或有副作用的运算之后的 await；
- await 操作数内再有 await，或操作数内写局部；
- 第十二片已有的其余拒绝（for-in、循环后置块、带标签跳转、try/catch/finally、defer、throw、
  match、不可达语句、对挂起函数的同步调用等）。

### 验收结果

- `CoroutineBlockPlannerTest`：新增提升形状测试（while 循环头挂起、恢复块分支读临时局部、
  回边回到挂起的循环头、`!` 下的 if 条件、`base + await` 的 return 值、永不求值的 do-while
  条件不挂起）；新增固定种子 20261101 的 400 个随机程序，await 可落在条件两侧、return 值、
  局部写入的 `+` / `-` 子表达式与 while / do-while / for 条件中，× 4 组输入比对 AST 直接解释
  （按求值顺序消费输入）与块状态机的返回值和经过的挂起点数；拒绝清单换为上节的诊断形态。
  结构检查允许循环头的出口是挂起。
- `ExpressionAwaitCoroutinePipelineTest` 4 项，经真实 ANTLR 解析、标准库 + 用户源码编译与
  JVM 执行：单工作线程下先挂在 while 条件、再挂在循环体子表达式，恢复后跑完得 28，挂起前
  表达式只执行一次（class major 52）；输入已完成时 while 条件 150 趟、do-while 条件约 48 趟
  同步恢复，while 零趟；while 条件挂起点失败原样发布、挂在条件上的帧被作用域取消后回收且
  晚到输入不复活；固定种子 20261101 的 64 轮与 Kotlin oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：已支持的 return await / 条件 / 子表达式中的
  await 换成短路右操作数、调用实参、调用之后、除法之后的 await。
- `AwaitFrontendTest`：原用 int32 `return await` 作「未支持」载体，现改为要求 int32 形态编译
  成功并写出生成帧（结果类型解包错误会导致类型失配而编译失败），非 int32 结果仍须诊断。
- 变异检查：二元运算先提升右侧再提升左侧时，随机比对与拒绝清单各 1 项失败；去掉「await 之前
  不得有副作用运算」检查时，规划器与流水线的拒绝清单各 1 项失败。
- 帧协议未改：`run-s4-models.sh` 十二组配置复跑全部通过（循环头挂起只是回边目标块以挂起
  结束，回边与每趟终态检查不变）。

### 仍未支持

for-in 与循环后置块、带标签的 break/continue、try/catch/finally/defer 的清理后继、调用实参与
短路右操作数中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记
名称；AST 构建器丢弃多表达式 for 初始化段的既有限制同前。

## S4-C 第十四片：源码静态函数调用实参中的 await

本片继续「实现次序」第 5 条（嵌套调用）：await 可以出现在源码静态函数调用的实参中，包括嵌套调用
的实参。帧协议与发射器不变，变化只在块规划器的提升规则里。

### 提升规则

- 只接受检查器已解析（`resolvedFunction` 非空）的源码函数调用，且被调目标是函数名本身（`g(...)`）
  或类型名限定的静态函数（`T.g(...)`）。目标先于实参求值，提升后推迟到恢复之后；函数名 / 类型名
  的求值不读可变状态，推迟不改变语义。
- 实参按自左向右的求值顺序提升，每个 await 各占一个挂起点，原位置改读合成临时局部；先于 await 的
  实参仍须满足第十三片「可推迟」规则（字面量、局部 / 参数读取、不抛出无副作用运算）。调用本身在全部
  实参之后执行，因此被调函数只在实参 await 恢复之后运行、且每次恰好运行一次；它对同一表达式中其后
  的 await 构成前序副作用（拒绝）。
- 改写后的调用保留 `resolvedFunction` 与推断的类型实参，发射后照常经 `TypeChecker` 复查。
- 被调函数自身为 SUSPENDING 时，仍按同步调用挂起函数诊断（实参可提升也不例外）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 未解析调用、平台调用、安全调用、varargs 调用的实参中的 await；
- 局部 / 参数 / 字段作接收者（`x.g(await f)`）或任意复杂目标的调用实参中的 await；
- 同一表达式中位于另一调用之后的 await，包括先于 await 的实参本身是调用（`g(h(), await f)`）、
  以及实参含 await 的调用之后再 await（`g(await f, 1) + (await f)`）；
- 含 await 的调用实参中写局部；
- 第十三片已有的其余拒绝（短路右操作数、类型转换、`new`、属性 / 下标访问中的 await 等）。

### 验收结果

- `CoroutineBlockPlannerTest`：新增实参提升形状测试（按实参顺序各占一个临时局部、类型名限定目标原样
  保留、嵌套调用最后一个实参提升、`resolvedFunction` 保留）；新增固定种子 20261103 的 400 个随机程序，
  在第十三片随机形态上加入不可交换的 `mix(a, b) = 3a - b` 调用（实参含 await、可嵌套），× 4 组输入
  比对 AST 直接解释与块状态机的返回值和挂起点数，且要求超过 100 个程序含「调用直接读提升临时局部」；
  拒绝清单新增未解析调用、局部接收者、安全调用、先于 await 的调用实参、带 await 实参的调用之后再 await。
- `CallArgumentAwaitCoroutinePipelineTest` 4 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行：
  单工作线程下帧挂在局部初始化的实参上时被调函数一次都未执行，恢复后恰好执行一次，再挂在 while 条件
  实参上，跑完后结果与调用次数都与 Kotlin oracle 一致（class major 52）；输入已完成时 40 趟循环同步恢复
  与零趟；条件实参挂起点失败原样发布且不再调用，挂起中被作用域取消后回收、晚到输入不复活且不调用；
  固定种子 20261103 的 64 轮结果与调用次数均与 oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：已支持的「调用实参中的 await」换成先于 await 的调用
  实参、带 await 实参的调用之后再 await、对挂起函数的调用（实参含 await）三例。
- 变异检查：实参倒序提升时形状、随机比对与拒绝清单 3 项失败；去掉「调用构成前序副作用」时拒绝清单失败；
  放开局部接收者时拒绝清单失败。
- 帧协议未改：`run-s4-models.sh` 十二组配置复跑全部通过，入口检查变异仍按预期违反。

### 仍未支持

for-in 与循环后置块、带标签的 break/continue、try/catch/finally/defer 的清理后继、短路右操作数中的
await、实例 / 接收者调用实参中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起
标记名称；AST 构建器丢弃多表达式 for 初始化段的既有限制同前。

## S4-C 第十五片：`&&` / `||` 右操作数中的 await

本片继续「实现次序」第 5 条（条件）：await 可以出现在短路运算的右操作数中，包括嵌套组合与循环条件。
帧协议与发射器不变，变化只在块规划器的提升规则里。

### 降级规则

- 右操作数含真实 await 的 `a && b` / `a || b`：先按既有规则处理左操作数（其中的 await 照常提升），
  把左操作数的值写入合成 bool 临时局部，并在当前块按它分支；`&&` 为真、`||` 为假时进入右侧块，
  否则直接到汇合块。右侧块内的 await 各自挂起，右侧结果覆写同一临时局部后前进到汇合块；原位置改读
  该临时局部。左操作数决定结果时右操作数（及其中的 await）不求值、不挂起。
- 左操作数在分支前完整求值，所以其中的调用、除法等可能有副作用或抛出的运算不妨碍右操作数中的
  await（求值次序与源码一致）；分支之后左右两侧都已执行完，前序副作用清空。
- 起步块是循环头时先前进到新块再写临时局部，循环头仍只负责「每趟开始」（每趟终态检查不变）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 同一表达式中更早求值、尚未提升的部分可能有副作用或抛出时（如 `(10 / d > 0) == (flag && (await f) > 0)`），
  左操作数被提前到分支之前会颠倒二者先后，拒绝；
- 左或右操作数写局部；
- 第十三、十四片已有的其余拒绝不变。

### 验收结果

- `CoroutineBlockPlannerTest`：新增短路形状测试（`&&` 为真进入挂起的右侧块、为假到汇合块，`||` 相反；
  判定块写 bool 临时局部；右侧恢复块覆写后汇合；while 循环头先前进到判定块、自身不承载写入）；新增固定
  种子 20261105 的 400 个随机程序：在第十四片随机形态上把 if 条件与循环条件随机改写为 `&&` / `||` 组合
  （可嵌套一层，右操作数可含 await 与调用；循环条件只用 `&&` 以保证有界），AST 解释器按短路求值，
  × 4 组输入比对返回值与挂起点数；120 个程序含短路判定块，76 个含多个。拒绝清单中原「短路右操作数
  await」换成「更早除法之后的短路」「操作数写局部」两例。
- `ShortCircuitAwaitCoroutinePipelineTest` 4 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行：
  左操作数决定结果时 gate 与 input 从不完成，帧仍同步跑完 30 趟、probe 恰好 30 次（class major 52）；
  需要右操作数时先挂在 if 条件右侧的 gate 上（probe 未执行），再挂在 while 条件 `||` 右侧的 input 上
  （probe 恰好 1 次），跑完后结果与调用次数与 Kotlin oracle 一致；右侧挂起点失败原样发布且循环不执行，
  挂起中被作用域取消后回收、晚到输入不复活且不再调用；固定种子 20261105 的 64 轮结果与调用次数均与
  oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：已支持的「短路右操作数中的 await」换成「更早除法之后
  的短路右操作数 await」。
- 变异检查：`||` 分支方向接成与 `&&` 相同时，形状、随机比对与 4 项端到端测试共 6 项失败；去掉入口的
  前序副作用检查时，规划器与流水线的拒绝清单各 1 项失败。
- 帧协议未改：`run-s4-models.sh` 十二组配置复跑通过（见下）。

### 仍未支持

for-in 与循环后置块、带标签的 break/continue、try/catch/finally/defer 的清理后继、实例 / 接收者调用实参
中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称；AST 构建器丢弃多表达式
for 初始化段的既有限制同前。

## S4-C 第十六片：带标签的 break / continue

本片继续「实现次序」第 5 条（循环）：`label name:` 包住的 while / do-while / 三段式 for 中，
`break name` / `continue name` 可以从任意深度的内层循环跳到该循环。帧协议与发射器不变，
变化只在块规划器与两处语句遍历里。

### 降级规则

- 每个正在降级的循环上下文记下自己的标签；无标签跳转取最内层循环，带标签跳转取同名的外层循环。
- 跳转登记到目标循环的上下文，和无标签形式一样随该循环封闭：`break name` 前进到该循环的出口块
  （在整个循环体之后分配，必然是前向边）；`continue name` 回到 while 的循环头（回边，每趟开头的
  终态检查照常生效），或前进到 do-while 的条件块 / for 的步进块（在循环体之后分配）。
- do-while 是否正常结束、条件块是否需要分配，计入从内层循环跳来的带标签 break / continue。
- 挂起效果分析与「同步调用挂起函数」检查穿过标签进入被标记的循环；此前带标签循环会使函数被视为
  未知效果而不降级。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 标签不是任何外层循环的标签（含内层循环的标签被外层引用）：拒绝，不退回到最内层循环；
- 与外层循环同名的标签（遮蔽）：拒绝；相邻（不嵌套）的循环可以复用同一标签；
- 带标签的 for-in 与循环后置块照旧拒绝。

### 验收结果

- `CoroutineBlockPlannerTest`：新增带标签形状测试（内层 while 挂起恢复后 `continue outer` 回到外层循环头、
  `break outer` 前进到在内层之后分配的外层出口、内层出口只回到外层循环头、相邻循环复用标签）；拒绝清单
  原「带标签 break」换成未知标签、引用内层标签、遮蔽标签、带标签 for-in 四例；新增固定种子 20261107 的
  400 个随机程序：在第十二片 while / do-while / for 形态上四分之三的循环带标签，跳转多数指向随机一层外层
  带标签循环，AST 解释器按标签传播跳转信号，× 4 组输入比对返回值与挂起点数，并要求跨层跳转程序超过 40 个。
- `LabeledLoopCoroutinePipelineTest` 5 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行：
  内层 while 中 await 之后的 `continue rows`（经外层 for 步进块）、`break rows` 与无标签 break，内层 do-while
  中 await 之后的 `continue rounds`（到外层条件块），单工作线程按序挂起恢复且前序表达式只执行一次（class
  major 52）；输入就绪时三种出口同步走通（40 行逐行 continue rows）；不挂起的 `continue spinning` 自旋在外层
  循环头响应作用域取消并回收；带标签循环内挂起点失败原样发布、挂起中被取消后回收且晚到输入不复活；固定种子
  20261107 的 64 轮与 Kotlin oracle（Kotlin 带标签循环）一致。
- 变异检查：带标签跳转改接最内层循环时，形状、随机比对与 4 项端到端测试共 6 项失败；挂起效果分析不穿过
  标签时端到端 5 项全部失败（函数不再降级）。
- 帧协议未改：`run-s4-models.sh` 十二组配置复跑全部通过。

### 仍未支持

for-in 与循环后置块、try/catch/finally/defer 的清理后继、实例 / 接收者调用实参中的 await、实例分派、
取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称；AST 构建器丢弃多表达式 for 初始化段的
既有限制同前。另：同步函数路径（IR 翻译器）对未知标签会退回到最内层循环、不报诊断，是协程帧之外的既有
行为，本片未改。

## S4-C 第十七片：try/finally 清理后继

本片继续「实现次序」第 5 条（try/finally），只做不改变失败本身的清理：`try { ... } finally { ... }`
的 try 体内可以挂起，可以经 return / break / continue 跨出区域；finally 体是同步清理。帧协议的
变化是「非正常离开先展开清理、再发布终态、最后回收」。

### 降级规则

- 每个 try/finally 是一个清理区域，编号从 1 起按进入顺序分配（外层编号总小于内层），合成 int32
  帧局部 `$region` 记录当前所在区域（0 = 不在区域内）；进入 try 体的块写入区域编号。
- 正常离开：try 体正常结束、return、跨出区域的 break / continue 所在块，在出口前按由内到外的顺序
  执行各区域清理；执行一个区域前先把 `$region` 退回外层区域。try 体内 return 的值在清理之前求出
  并存入合成临时局部（字面量除外），清理改写局部不影响返回值。
- 非正常离开：挂起点失败 / 取消、段内抛出（含清理本身抛出）与作用域取消都跳出分派循环，按
  `$region` 由内到外展开清理（按编号从大到小检查一趟即可走完区域链），清理抛出时以新的 platform
  Failure 取代待发布结果并继续展开外层；展开结束后才发布 Failure / Cancelled（作用域取消时结果
  已终结，只做清理）。因此帧自己发布的终态在可见时，所有已进入区域的清理都已完成。
- 回收条件改为「结果已终结且 `$region` 为 0」：挂起点注册回调之后、run 返回之前到达的作用域取消
  不再让 finally 立即关闭调度门（那会丢弃取消回调请求的恢复、清理永不执行）；该恢复请求由调度门
  在本段结束后重新运行帧，入口检查跳出分派循环并展开，展开后再回收。挂起块在注册前发现结果已终结
  时同样跳出分派循环展开（此前直接 return）。没有 try 的函数行为不变（`$region` 不存在，条件退化
  为结果已终结）。
- 挂起效果分析与「同步调用挂起函数」检查进入 try 体、catch 体与 finally 体；此前含 try 的函数
  被视为未知效果。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 带 catch 的 try：捕获挂起点失败要决定 Failure 与源码错误类型的对应，属于错误模型的语义选择
  （错误模型尚未定案），本片不作出；
- finally 体中的 await、return、try、defer、do-while / for、带标签或跳出 finally 的 break / continue；
  finally 体只接受 int32 / bool 局部声明、对局部的 `=` 赋值、int32 局部 `++` / `--`、调用语句、
  if/else、while（体内无标签 break / continue）与块；
- finally 体中对挂起函数的同步调用；defer、throw 照旧拒绝。

### 验收结果

- `CoroutineBlockPlannerTest`：新增嵌套 try 形状测试（区域编号与外层、进入写区域局部、try 体内 return
  先存临时局部再按内、外顺序清理、break 与 try 体正常结束只清理内层）；拒绝清单新增 try/catch、finally
  中 await（语句与条件）、finally 中 return、跳出 finally 的 break、finally 内循环的带标签 break、finally
  中 try / for、finally 局部重名、defer 十例；新增固定种子 20261109 的 400 个随机程序：在第十二片循环
  形态上随机包 try/finally（可嵌套、在循环内外，try 体可 return / break / continue 跨出区域），清理体
  调用 `mark(k)` 并可改写外层局部；输入中约 1/12 是挂起点失败哨兵，块状态机按 `$region` 展开（与发射器
  同一算法）；比对结果（含失败）、挂起点数与清理轨迹。数量下限：嵌套区域、try 体内 return、失败展开
  各超过 40。
- `TryFinallyCoroutinePipelineTest` 5 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行（class
  major 52）：挂起期间不清理、恢复后每行内层清理恰好一次、外层清理最后执行、return 值不含外层清理对
  局部的改写；continue / break 只清理内层，两层 try 内 return 依次清理两层；input 在内层区域失败时两层
  清理完成后才发布原 Failure，gate 在外层区域失败只清理外层，除零与清理自身抛出发布 platform 失败且
  外层清理照常执行；挂在内层 / 外层区域时被作用域取消，清理完成后才 settled，晚到输入不复活、不重跑
  清理；固定种子 20261109 的 64 轮（含失败、除零与清理抛出）结果、清理次数与最近两次清理编号与
  Kotlin oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：已支持的「try 内 await」换成 try/catch 内 await、
  finally 中 await、finally 中同步调用挂起函数三例。
- 变异检查：展开改为按编号升序单趟检查、且 try 体内 return 不先固定返回值时，规划器 2 项与端到端
  5 项共 7 项失败。
- TLA+ `TryFinallyCoroutineFrame`（两层区域、挂起点成功 / 失败 / 取消、清理可抛出、作用域取消任意
  时刻到达）：1984 个不同状态，清理至多一次、由内到外、帧发布的结果可见时与 settled 时已进入区域
  全部清理、结果稳定、终将 settled、取消终将 settled 全部成立。两组变异配置：展开只清理当前区域
  违反 NoEarlyResult；回收只看结果是否终结（修复前的实现）违反 SettledAfterCleanup——反例正是
  「挂起注册后、run 返回前作用域取消」，本片据此修正了回收条件与挂起前的终结检查。端到端测试无法
  确定性地制造这一竞争窗口，该路径的证据是模型。`run-s4-models.sh` 纳入新模型，共十三组配置。

### 仍未支持

带 catch 的 try、defer、throw、finally 中的挂起与跳出、for-in 与循环后置块、实例 / 接收者调用实参
中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称；AST 构建器丢弃
多表达式 for 初始化段、同步路径对未知标签退回最内层循环的既有限制同前。

## S4-C 第十八片：协程帧内 try/catch

本片继续「实现次序」第 5 条，按 2026-10-28 裁决 C（见 `S4-coroutine-context.md` §11.1）实现带 catch
的 try：try 体内可以挂起，被等待 Future 的失败 / 取消与段内抛出的错误按捕获类型进入 catch；本任务
自身的取消不是异常，catch 看不到它，只执行 finally。

### 降级规则

- catch 区域与 finally 区域共用 `$region` 链与编号规则；catch 区域没有清理语句，正常离开（try 体
  正常结束、跨出区域的 return / break / continue）只把 `$region` 退回外层区域。`try B catch H finally F`
  降级为外层 finally 区域包住内层 catch 区域，因此处理块也在 finally 的覆盖之下，与源码语义一致。
- 每个 catch 子句开一个处理块，没有前驱边，在 catch 区域之外（外层区域内）降级；处理体可以挂起、
  分支、循环、return 与 break / continue。try 体与各处理块的正常出口汇合到同一块。
- 有 catch 区域的帧：恢复块消费到失败时先把原 Failure 记为待发布结果，再抛出
  `FutureFailureError(原 Failure 的 message)`；消费到取消时记待发布 Cancelled，再抛出
  `FutureCancelledError`——与 `Future.get()` 的既有语义相同。
- 异常路由（run 内包住分派循环的 catch）：段内抛出时若尚无待发布结果则记为 platform Failure；随后按
  `$region` 编号从大到小走一趟——finally 区域执行清理（清理抛出时以新异常继续路由，可被更外层 catch
  捕获，待发布结果改为新的 platform Failure），catch 区域先退回外层区域，再按源码顺序用 `instanceof`
  匹配捕获类型。命中时清除待发布结果、`__phase` 指向处理块并重新进入分派循环；入口检查照常进行，
  本任务若已被取消，处理块不执行，直接展开外层 finally。走完仍未命中时 `$region` 为 0，发布待发布结果
  ——未被捕获的挂起点失败 / 取消因此发布的仍是原 Failure / Cancelled，与没有 catch 时相同。
- 本任务自身的取消：入口检查与挂起前检查发现结果已终结时跳出分派循环，不抛异常；展开只执行 finally，
  catch 区域只退回外层区域。
- 没有 catch 区域的帧生成代码与第十七片相同。
- 生成帧的二次类型检查带上来源文件的 import：帧追加在来源文件中翻译，捕获类型等源码写出的类型名
  按同一组 import 解析，检查与翻译看到的名字一致（此前检查不带 import，短名捕获类型无法解析）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- 处理体引用捕获参数（读取、同名局部声明、嵌套 catch 同名参数）：语言禁止 Error 出现在字段中，
  捕获参数无法成为跨挂起的帧字段；也不悄悄改指同名的外层局部；
- 捕获类型带类型实参（`instanceof` 会擦除）；既无 catch 也无 finally 的 try；
- finally 体的既有限制（await、return、try、跳出 finally 等）、defer、throw 语句同第十七片。

### 验收结果

- `CoroutineBlockPlannerTest`：新增 try/catch/finally 形状测试（finally 区域包住 catch 区域、catch 区域无
  清理语句、处理块按源码顺序且没有前驱、处理块中的 return 只清理 finally 区域、汇合块清理 finally
  区域）；拒绝清单新增捕获参数读取、捕获参数遮蔽局部、泛型捕获类型、无 catch 无 finally、catch 之后的
  finally 中 await 五例；结构检查把处理块入口作为可达性根。新增固定种子 20261111 的 400 个随机程序：
  在第十七片的 try/finally 随机程序上三分之二的 try 带 1～2 个 catch（捕获类型在 FutureFailureError /
  FutureCancelledError / Error 中随机，处理体可挂起、跳转、return，一半省略 finally），输入中各约 1/12 是
  失败与取消哨兵；源码 AST 直接解释（Kotlin try/catch/finally）与块状态机异常路由（与发射器同一算法）
  比对结果、挂起点数与清理轨迹。数量下限：含 catch 的程序超过 150，失败后被捕获继续执行、失败穿过
  catch 区域、处理块入口即挂起各超过 40。过程中发现测试生成器的「是否跳到本循环」扫描漏看 catch 体，
  已修正。
- `TryCatchCoroutinePipelineTest` 5 项，经真实 ANTLR 解析、标准库 + 用户源码编译与 JVM 执行（class
  major 52）：input 失败时每行进入 FutureFailureError 处理块后继续、内层 finally 每行一次、外层 finally
  最后；input 取消进入 FutureCancelledError 处理块并在处理块内挂起；处理块中 gate 失败不被同一 try 的
  catch 捕获、穿过内层 finally 被外层 `catch (e: Error)` 捕获；源码 `throw new Error` 被外层捕获；除零
  （平台异常）穿过全部 catch 区域、两层清理后发布 platform 失败；作用域取消（挂在 try 体内或处理块内）
  只执行 finally、不执行任何处理块，晚到输入不复活；固定种子 20261111 的 64 轮与 Kotlin oracle 一致。
- `LinearCoroutinePipelineTest` 的不支持形态清单：已支持的 try/catch 内 await 换成处理体引用捕获参数。
- 变异检查：发射器忽略捕获类型（首个处理块总命中）4 项失败；规划器把处理块放进 catch 区域内 1 项失败；
  路由时跳过 finally 清理 3 项失败；去掉有 catch 帧的分派循环入口检查（自身取消的结果快照被当成
  被等待 Future 的取消、进入处理块）被作用域取消测试抓到。另试过「自身取消以异常进入路由」的变异：
  由于命中后重新进入分派循环先做入口检查，处理块不会执行，可观察行为与正确实现相同，不算有效变异。
- TLA+ `TryCatchCoroutineFrame`（finally 区域包住 catch 区域、挂起点成功 / 失败 / 取消、段内与处理块与
  清理可抛出可匹配 / 不可匹配错误、作用域取消任意时刻到达）：2520 个不同状态，清理至多一次、结果
  可见与 settled 时已清理、处理块入口检查点之前到达的自身取消使处理块不执行（OwnCancelInvisible）、
  catch 只接住匹配类型、被捕获错误不残留待发布结果、结果稳定、终将 settled、取消终将 settled 全部成立。
  两组变异配置：命中后不经入口检查直接执行处理块违反 OwnCancelInvisible；忽略捕获类型违反
  CatchMatchesType。`run-s4-models.sh` 纳入新模型，共十四组配置。

### 仍未支持

处理体使用捕获参数、defer、throw 语句、finally 中的挂起与跳出、for-in 与循环后置块、实例 / 接收者调用
实参中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称；AST 构建器
丢弃多表达式 for 初始化段、同步路径对未知标签退回最内层循环的既有限制同前。

## S4-C 第十九片：协程帧内 defer

本片继续「实现次序」第 5 条，实现规范 10.8 的 `defer`：「在当前作用域结束时执行」。协程帧中的作用域
就是源码块（函数体、if / else 分支、循环体每一趟、try 体、catch 处理体）；无论以正常结束、return、
break / continue、挂起点失败 / 取消、段内抛出还是作用域取消离开该块，defer 体都恰好执行一次，同一块中
后声明的先执行。帧协议不变。

### 降级规则

- 规划器降级语句序列时遇到 `defer D`：同一块中 D 之后的全部语句降级为 try 体，D 为 finally 体，即
  `defer D; S…` ≡ `try { S… } finally { D }`，直接复用第十七片的清理区域（区域编号、`$region`、出口前
  由内到外清理、非正常离开先展开后发布、回收要求区域为 0）。同块多个 defer 因此逐层嵌套，后声明的在
  内层、先执行；块末尾的 defer（其后没有语句）降级为空 try 体，离开块时执行。
- 块内的 return 照常先固定返回值再清理，所以 defer 改写局部不影响已求出的返回值（与同步路径「defer 在
  return 求值之后、真正返回之前执行」一致）。
- defer 体与 finally 体同为同步清理，接受的语句与限制相同；诊断措辞写 defer。
- 挂起效果分析与「同步调用挂起函数」检查进入 defer 体（此前含 defer 的函数被视为未知效果）。

### 明确诊断的形态（不写 class，不退化为同步求值）

- defer 体中的 await、return、try、defer、do-while / for、带标签或跳出 defer 体的 break / continue；
- defer 体中对挂起函数的同步调用；
- 作用域结束后仍未 return 的路径（「每条路径以 return 结束」的要求不变）。

### 验收结果

- `CoroutineBlockPlannerTest`：新增形状测试（if 分支末尾的 defer、函数体两个 defer 嵌套为父子区域、
  循环体 defer 在 continue 与循环体末尾各清理一次且不越过循环、return 先存临时局部再按后声明先执行的
  顺序清理）；拒绝清单原「defer」一例换成 defer 中 await / return / 跳出 / defer、finally 中 defer、
  defer 之后缺 return 六例。新增固定种子 20261113 的 400 个随机程序：在第十八片 try/catch/finally 随机
  程序上，每个语句位置约 1/4 插入 defer（可在循环体、分支、try 体与处理块中，同块可多个），输入各约
  1/12 为失败与取消哨兵；直接解释器按规范语义执行 defer（每个块一个作用域，离开时后进先出，**不经过**
  try/finally 改写），与块状态机比对结果、挂起点数与清理轨迹。数量下限：同块多个 defer、循环体内 defer、
  失败展开各超过 40。
- `DeferCoroutinePipelineTest` 5 项，经真实 ANTLR 解析（单语句与块两种 defer 形式）、标准库 + 用户源码
  编译与 JVM 执行（class major 52）：挂起期间不执行 defer、循环体 defer 每趟一次、函数结束时后声明的先
  执行、return 值不含 defer 的改写；continue / break 只执行循环体 defer，循环体内 return 依次执行三个；
  input 失败时三个 defer 完成后才发布原 Failure，gate 失败只执行函数体两个，除零与 defer 体自身抛出发布
  platform 失败且外层 defer 照常执行；挂在循环体内 / 循环后被作用域取消，defer 完成后才 settled，晚到
  输入不复活；固定种子 20261113 的 64 轮与 Kotlin oracle 一致。
- 变异检查：同块 defer 改为按声明顺序合并执行（先声明先执行）规划器 2 项与端到端 5 项共 7 项失败；
  丢弃 defer 共 8 项失败。
- TLA+：帧协议未改（defer 只是清理区域的另一种源码来源），`run-s4-models.sh` 十四组配置复跑通过。

### 同步路径对齐（本片之后）

同步函数路径（`JasperToIrTranslator`）原先的 defer 按词法累积、只在其后的 `return` 处展开（分支里未执行
到的 defer 也执行，循环体不按趟，块正常结束与异常离开都不执行，且在 return 值求值之前执行）。现已改为与
本片相同的改写 `defer D; S…` ≡ `try { S… } finally { D }`，并修正后端 try/finally 的两处缺陷：break /
continue 跨出循环内的 try/finally 时执行其 finally；return / break / continue 处内联的 finally 代码从
所属 try 及内层 try 的异常表中挖掉，finally 自身抛出时不再重进自己或被内层 catch 截获。见
`LANG-SYNC-DEFER-01`。

### 仍未支持

处理体使用捕获参数、throw 语句、finally / defer 中的挂起与跳出、for-in 与循环后置块、实例 / 接收者调用
实参中的 await、实例分派、取消原因与 Deadline、公开源码挂起调用 ABI 与显式挂起标记名称；清理抛出时
以新失败取代原失败（与草案 §4.4 第 4 条「原失败为主错误、清理错误只记诊断」不同，finally 与 defer
现均按前者）待错误模型定案时统一；AST 构建器丢弃多表达式 for 初始化段、同步路径对未知标签退回最内层
循环的既有限制同前。
