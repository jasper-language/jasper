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
