# S4-A：协程上下文与结构化并发接口契约

> 状态：**设计定稿候选（草案）**。本文只定义接口契约、继承矩阵与运行语义，不包含实现，
> 不改编译器、标准库或语法文件。
>
> 本文出现的所有类型、方法、注解、字段和伪代码均为**草案**；草案只固定语义，不固定最终
> 拼写。后续实现可以在不改变语义的前提下调整重载、命名与参数顺序。
>
> 范围：Coroutine、Context、Structured Concurrency 三层的职责边界；上下文元素与继承
> 矩阵；取消、失败传播、清理；公开/内部 API 映射；Java 8 约束；executor 端口。
>
> 非目标：不定义编译器如何降级挂起点，不定义线程池参数，不定义 Jasper OS 的调度算法，
> 不新增并发关键字或语句。

---

## 0. 结论摘要

1. **三层单向依赖**：`TaskGroup` 编排 `Task`，`Task` 持有 `Context` 快照并调用
   `ExecutorPort`；`Context` 不反向依赖任务或作用域，平台适配器只实现端口。
2. **上下文是快照，取消是活链接**：身份、Deadline、Executor 引用、用户值、Trace、
   Security Identity 在子任务创建时定格；Cancellation 与父子关系是运行期活链接，
   父取消会传播到子，子的取消不影响父或兄弟。
3. **继承矩阵共 11 条**，每条都能用一句话判定，见 §3。默认继承 3 条、必须派生 3 条、
   取更严格 1 条、默认关联 1 条、禁止继承 3 条。
4. **取消只在取消检查点协作生效**：挂起点（`await`、`yield`、`delay`、可取消 IO）与
   显式 `checkCancellation()`；不强杀线程，不依赖中断。不可取消区间必须用有界注解
   标注，运行时按“更小上限”执行。
5. **取消不是失败**：`CancellationException` 是独立类型，带可判定的取消原因，不参与
   失败聚合。普通作用域取首个失败为主错误；监督作用域不牵连兄弟。
6. **公开面只有 `Future<T>`、`TaskGroup`、`Context`、`Deadline`、`ContextKey<T>` 与
   取消/契约异常**；`Task` 是内部实体，不出现在公开签名中。不新增并发关键字。
7. **Java 8 可交付**：第一版使用线程池、`Future`/`CompletableFuture`、自建续体和
   `ScheduledThreadPoolExecutor`；不依赖虚拟线程、Java 21 结构化并发 API 或
   `ThreadLocal`。
8. **executor 端口隔离平台变化**：`schedule` / `yield` / `cancel` / `completion`
   四个概念操作由 JVM 适配器与未来的 Jasper OS 适配器分别实现，语言层零改动。
9. **仍有 3 个问题待裁决**：作用域生命周期载体、监督作用域未观察失败、被 `catch`
   吞掉的取消如何重新生效；每条都给选项与影响，见 §9。

---

## 1. 三层职责边界

### 1.1 Coroutine：可挂起、可恢复的执行过程

| 项目 | 约定 |
|------|------|
| 职责 | 在挂起点保存续体，在恢复点还原局部状态与上下文引用；驱动状态机前进 |
| 拥有 | 挂起点、续体、局部变量快照、`Task` 状态 |
| 不拥有 | 线程、线程本地存储、进程、连接、事务 |
| 线程关系 | **无亲和性**：同一协程可以在不同线程恢复；任何线程都可以执行续体 |
| 失败模式 | 恢复时上下文丢失、续体重复调度、局部变量未还原、在非挂起点观察取消 |
| 设计边界 | 协程只描述“如何暂停/继续”，不描述“父子关系”和“何时取消” |

**关键判定**：协程的身份不是线程身份；`Thread.currentThread()` 不能作为上下文来源，
也不能作为父子关系或取消判断的依据。

### 1.2 Context：随逻辑执行传播的不可变快照

| 项目 | 约定 |
|------|------|
| 职责 | 在子任务创建时提供一份只读视图，保证挂起/恢复/换线程后仍一致 |
| 拥有 | 用户值、Deadline、Executor 引用、Trace、Security Identity 的创建时刻值 |
| 不拥有 | 父任务后续修改、线程本地变量、可变资源的独占所有权 |
| 传播方式 | 由续体显式携带；不得通过 `ThreadLocal`、线程名、全局单例或栈帧隐式传递 |
| 失败模式 | 把可变对象当值共享、子任务反向写父上下文、跨线程读取到半更新快照 |
| 设计边界 | Context 只回答“子任务看见什么”，不回答“子任务何时开始/结束” |

Context 的“不可变”指**结构不可变**：键集合与值是创建时的快照，父任务之后调用
`with`/`without` 不会改变已创建子任务的视图。值对象本身是否可变由值类型负责；把可变
对象放进 Context 等于显式共享，运行时不代为深拷贝（见 §3.3）。

### 1.3 Structured Concurrency：父子、取消、失败与回收

| 项目 | 约定 |
|------|------|
| 职责 | 维护任务树；父作用域等待全部子任务进入终态；传播取消；选择主失败；等待清理完成 |
| 拥有 | 子任务集合、作用域状态、关闭屏障、主失败选择顺序 |
| 不拥有 | 线程池实现、用户业务状态、安全策略、数据库连接 |
| 生命周期 | 作用域关闭后不得再启动子任务；关闭必须等所有子任务清理完毕才返回 |
| 失败模式 | fire-and-forget 泄漏、父退出后子继续跑、取消只标记不等待、失败被静默吞掉 |
| 设计边界 | `TaskGroup` 是作用域门面，只编排 `Task`/`Future`，不承载调度、存储或安全逻辑 |

**关键判定**：一个任务在父作用域关闭前必须处于终态；终态包括成功、失败、取消，
三者都要求清理已经完成。没有“已启动但无人等待”的合法状态。

### 1.4 模块依赖方向

```text
公开 API：Future<T> / TaskGroup / Context / Deadline / ContextKey<T>
                    │
                    ▼
运行期：Task / CancellationToken / TaskGroupImpl / Outcome
                    │
                    ▼
端口：ExecutorPort（schedule / yield / cancel / completion）
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
JVM 适配器                Jasper OS 适配器
```

依赖只允许向下：公开 API 不依赖适配器；运行期不依赖具体线程池；适配器不反向依赖
`TaskGroup`。`Context` 只依赖值类型，不依赖 `Task` 或 `TaskGroup`，避免环。

这里使用了 **Ports & Adapters（端口与适配器）**：变化点是“平台调度与完成通知”，
端口把它隔离在一个窄接口内；使用了 **Immutable Snapshot（不可变快照）**：变化点是
“父任务后续修改”，快照把子任务与父任务的变化隔开；`TaskGroupOptions` 使用
**Builder**：变化点是“可选参数增长”，避免构造器参数膨胀成上帝类。模式只服务于这些
明确变化点，不额外引入抽象层。

---

## 2. 上下文元素表

下表中的“可见性”指是否进入语言公开 API，不指进程内访问权限。

| 元素 | 类型形状（草案） | 创建方式 | 继承规则 | 可见性 |
|------|------------------|----------|----------|--------|
| Job / Task 身份 | 内部 `Task`：`TaskId`、`parent: Task?`、`state`、`outcome`；`TaskId` 为运行期单调 `int64` | 每次 `TaskGroup.spawn` 创建新 `Task` | **必派生**：子任务获得全新 ID，父指针指向创建者；ID 不复用 | 内部 |
| Cancellation | 内部 `CancellationToken`：原子请求位、取消原因、父 token 链接；公开 `CancellationException` | 创建任务时建立；`Future.cancel` / `TaskGroup.cancel` / Deadline / 父取消触发 | **关联传播**：父 token 链接到子 token；父取消 ⇒ 子取消；子取消不反向传播 | token 内部；异常公开 |
| Deadline | 公开不可变 `Deadline`：单调时钟绝对截止点；`remainingMillis()` | `Deadline.after(millis)`；或通过 `TaskGroupOptions.withDeadline` | **取更严格**：`min(父 Deadline, 子请求 Deadline)`；子不能延长 | 公开 |
| Executor | 内部 `ExecutorRef`：指向 `ExecutorPort` 的引用与能力标记；不暴露线程池类型 | 运行期默认选择；或内部作用域配置覆盖 | **默认继承，可局部覆盖**：未指定时继承父引用；覆盖只在创建子作用域/子任务时发生 | 内部 |
| Context Values | 公开 `ContextKey<T>` + 不可变键值快照；`Context.get/with/without` | `ContextKey.of<T>(name)`；`Context.with`；`TaskGroupOptions.withValue` | **默认继承**：子复制父快照并应用覆盖；子覆盖不影响父与兄弟 | 公开 |
| Trace | 公开只读 `TraceSpan`：`traceId`、`spanId`、`parentSpanId`、`isRecording` | 运行期在创建子任务时派生；跟踪关闭时为 `null` | **派生**：新 span 以父 span 为 parent，`traceId` 相同，`spanId` 必须不同 | 公开只读；创建内部 |
| Security Identity | 公开不透明 `SecurityIdentity`：只表示“是谁”，不携带权限位 | 由安全模块在入口认证后写入 | **继承身份**：子任务看到同一主体；不重新认证 | 公开只读 |
| Authorization | **不是 Context 元素**：授权是一次“主体 × 动作 × 资源”的策略求值结果 | 每次受保护操作前由安全模块重新计算 | **禁止继承**：不携带角色、权限位、缓存决策 | 安全模块内部 |
| Transaction | **不是 Context 元素**：`TransactionHandle` 由数据访问框架显式持有 | 框架打开事务时创建 | **禁止继承**：子任务默认看不到父事务；只能显式传递句柄 | 框架公开，但不在 Context |
| Connection / File handle | **不是 Context 元素**：`ConnectionHandle` / 文件句柄由资源所有者显式持有 | 资源打开时创建 | **禁止继承**：子任务默认看不到；只能显式转移所有权或借用 | 资源所有者公开，但不在 Context |

### 2.1 元素说明

- **Task 身份**：`TaskId` 只用于运行期诊断、Trace 和错误消息；不进入公开 API，
  避免用户把内部调度实体当句柄保存。父子关系由 `Task` 内部的 `parent` 指针维护，
  不通过 Context Values 传递。
- **Cancellation**：取消令牌是**活链接**，不是快照。父任务取消时，子 token 收到
  传播；子任务自行取消只影响自己和自己的后代，不影响父或兄弟。令牌只表达“请求
  取消”，不表达“已经停止”；终态以 `Task.outcome` 为准。
- **Deadline**：使用单调时钟，避免系统时间回拨导致超时延长或提前。`Deadline.after`
  接受相对毫秒；`0` 表示“立即过期”，负数属于参数错误。绝对墙钟时间不作为第一版
  公开构造方式。若作用域打开时 Deadline 已过期，该作用域直接处于已取消状态，
  `spawn` 返回已取消 `Future` 且不执行正文。
- **Executor**：`ExecutorRef` 是共享引用，不是所有权转移；任务结束不关闭执行器。
  执行器生命周期由运行期或嵌入方管理，任务只借用。第一版不公开执行器类型，避免
  平台细节泄漏；标准库内部可以通过内部 API 覆盖，例如阻塞 IO 使用独立池。
- **Context Values**：键必须是 `ContextKey<T>` 对象而不是裸字符串，避免不同模块用
  同名键互相覆盖。`Context.with` 返回新快照；`without` 只从新快照移除键。值对象的
  线程安全由值类型负责，Context 不提供深拷贝。
- **Trace**：Trace 是诊断用途，不能作为业务状态、授权依据或分布式锁。跟踪关闭时
  `Context.span()` 返回 `null`，不分配空 span；开启时每个任务必须有独立 `spanId`。
- **Security Identity**：身份可以继承，但“身份相同”不等于“授权相同”。授权决策
  不进入 Context；每次受保护操作前必须重新求值，见 §3.4。
- **Transaction / Connection**：它们是**资源**，不是上下文值。把它们放进可继承
  Context Values 是反模式；运行期不自动传播，框架也不得默认传播。

---

## 3. 继承矩阵

### 3.1 判定表（共 11 条）

| # | 元素 | 默认策略 | 一句话判定 | 覆盖 / 派生方式 | 可见性 |
|---|------|----------|------------|------------------|--------|
| 1 | Task 身份 | 必派生 | 每个子任务获得全新 `TaskId`，绝不复用父 ID | 只能由 `spawn` 派生，不能覆盖 | 内部 |
| 2 | 父子链接 | 必派生 | 子任务的 `parent` 恒为创建它的任务，不能指向别处 | 只能由 `spawn` 建立 | 内部 |
| 3 | Cancellation | 关联传播 | 父取消 ⇒ 子取消；子取消 ⇏ 父或兄弟取消 | 子 token 链接父 token；不能“复制后断开” | token 内部 |
| 4 | Deadline | 取更严格 | 子 Deadline = `min(父 Deadline, 子请求 Deadline)`；空值视为无穷大 | 子只能缩短，不能延长 | 公开 |
| 5 | Executor | 默认继承 | 子 Executor = 子请求值，未请求则继承父 Executor | 只在创建子作用域/子任务时覆盖；运行中不可切换 | 内部 |
| 6 | Context Values | 默认继承 | 子值 = 父快照 + 子覆盖；子覆盖不回写父或兄弟 | `Context.with` / `without` / `TaskGroupOptions.withValue` | 公开 |
| 7 | Trace | 派生 | 子 span 的 `parentSpanId` = 父 `spanId`，`traceId` 相同，`spanId` 必须新 | 每个任务派生一次；不可复用父 span | 公开只读 |
| 8 | Security Identity | 继承身份 | 子 Identity = 父 Identity（同一主体），不重新认证 | 只能由入口认证写入；子不能替换身份 | 公开只读 |
| 9 | Authorization | 禁止继承 | 子任务不继承任何授权、角色、权限位或授权缓存 | 每次受保护操作重新求值 | 安全模块内部 |
| 10 | Transaction | 禁止继承 | 子任务默认 `transaction = null`，只能显式传句柄 | 显式参数或框架句柄传递 | 框架公开 |
| 11 | Connection / File handle | 禁止继承 | 子任务默认 `connection = null` / `file = null`，只能显式转移或借用 | 显式所有权转移/借用协议 | 所有者公开 |

**计数口径**：上表 11 行就是 11 条继承规则；“默认继承”3 条（5、6、8），“必须派生”
3 条（1、2、7），“取更严格”1 条（4），“关联传播”1 条（3），“禁止继承”3 条
（9、10、11）。

### 3.2 判定函数

下面用伪代码把矩阵写成可执行判定；它是**草案**，只表达语义，不是实现代码。

```text
effective(childRequest, parentContext) =
    taskId        = freshTaskId()                       // 必派生
    parent        = creatorTask                         // 必派生
    cancellation  = link(parentContext.cancellation)    // 关联，不复制
    deadline      = minNullable(parentContext.deadline,
                                 childRequest.deadline) // null = 无穷大
    executor      = childRequest.executor
                        ?? parentContext.executor        // 未指定才继承
    values        = snapshot(parentContext.values)
                        + childRequest.overrides         // 子覆盖只写新快照
    trace         = parentContext.trace == null
                        ? null
                        : newSpan(parentContext.trace)   // 同 traceId，新 spanId
    identity      = parentContext.identity               // 身份继承
    authorization = absent                               // 禁止继承
    transaction   = absent                               // 禁止继承
    connection    = absent                               // 禁止继承
```

`minNullable(a, b)` 的规则：两者都为 `null` 返回 `null`；只有一个为 `null` 返回另一个；
都不为 `null` 返回较早的时间点。该函数满足交换律、结合律和幂等律，便于测试。

### 3.3 快照语义与可见性

1. **创建时刻定格**：子任务创建后，父任务调用 `Context.with`/`without` 不改变子任务
   视图；子任务也不能通过 Context 写回父任务。
2. **兄弟隔离**：两个子任务各自拿到独立快照；子 A 覆盖键 `K` 不影响子 B 或父。
3. **换线程不丢**：上下文由续体显式携带；恢复线程与挂起线程可以不同。
4. **浅快照**：Context 复制的是键值映射结构，不深拷贝值对象。值是可变对象时，
   共享该对象是显式行为；框架不得把事务、连接、文件句柄放入可继承值。
5. **只读视图**：`Context` 的公开方法不修改自身；所有修改返回新实例。运行期内部
   可以直接持有不可变映射，不需要加锁读取。
6. **空值规则**：`Context.get<T>` 返回 `T?`；键不存在返回 `null`；不允许用 `null`
   表示“存在但为空”，需要空值时使用可空类型或哨兵值，由值类型自行定义。

### 3.4 Security Identity 与 Authorization 的分离

- **身份继承**：子任务继承主体身份，用于审计“谁发起了这次操作”。
- **授权不继承**：Context 不保存角色、权限位、ACL 或“已授权”布尔值；每次受保护
  操作前，安全模块按 `(identity, action, resource)` 重新求值。
- **重新校验时机**：任何可能产生外部副作用的操作之前；跨挂起点之后；跨任务边界之后。
  一次不挂起的纯计算内部可以复用同一决策，但不得跨挂起点缓存。
- **失败关闭**：身份缺失或策略拒绝时拒绝操作，不因“父任务有权限”而放行。
- **不可提升**：子任务不能通过覆盖 Context 提升身份或权限；身份只能由入口认证写入。
- **审计**：Trace 可以记录身份标识和决策结果，但不能把授权结果当上下文值传播。

### 3.5 禁止继承的资源

事务、数据库连接、文件句柄、锁、网络会话属于**资源**，不属于上下文快照。禁止继承
的理由：

1. **并发安全**：同一连接/事务被父子任务并发使用会破坏协议状态；
2. **生命周期**：父任务退出后资源可能已关闭，子任务继续使用会产生悬空句柄；
3. **责任边界**：资源释放必须有唯一所有者，不能靠“继承”隐式分摊；
4. **可测试性**：显式传递句柄让所有权和释放点可被静态检查与测试观察。

允许的唯一形式是**显式传递**：作为函数参数、构造参数或框架句柄传入，并按该资源的
所有权/借用协议使用；不得通过 `ContextKey` 自动传播。

---

## 4. 取消语义

### 4.1 取消源与取消原因

| 取消源 | 触发方式 | 取消原因（草案） | 作用范围 |
|--------|----------|------------------|----------|
| 显式取消任务 | `Future.cancel()` | `Explicit` | 该任务及其后代 |
| 显式取消作用域 | `TaskGroup.cancel()` | `Explicit` | 作用域内全部子任务及其后代 |
| 父任务取消 | 父 token 传播 | `Parent` | 该任务及其后代 |
| Deadline 到期 | 运行期定时器 | `Deadline` | 该任务及其后代 |
| 作用域关闭 | `TaskGroup.close()` 取消未完成子任务 | `ScopeExit` | 未完成子任务 |
| 兄弟失败 | 普通作用域首个失败 | `SiblingFailed` | 其余兄弟任务 |

公开异常形状（**草案**）：

```text
public enum CancellationReason {
    Explicit,
    Parent,
    Deadline,
    ScopeExit,
    SiblingFailed
}

public class CancellationException extends Error {
    public function reason(): CancellationReason;
    public function message(): string;
}
```

`CancellationException` 是独立类型，不继承业务错误分支；它是控制流，不是业务失败。
`Deadline` 原因已经能区分超时，第一版不再增加单独的超时异常类型。

**可判定规则**：

- `cancel()` 对未进入终态的任务返回 `true`，并请求取消；对已进入终态的任务返回
  `false`，不改变结果。
- 取消是幂等的；重复请求保留**第一次**的原因。
- 取消 reason 只由第一个生效的取消源决定；后续取消源不覆盖，仅记录诊断。
- 父取消传播到所有后代；子取消不向父或兄弟传播。

### 4.2 取消检查点：只在挂起点协作生效

取消**不会异步抢占**正在执行的指令，也不会默认调用线程中断。任务只在以下
**取消检查点**观察取消：

1. `await future`；
2. `yield`；
3. `delay` / 定时等待；
4. 标记为可取消的 IO 挂起点；
5. 显式调用 `TaskGroup.checkCancellation()`。

除上述检查点外，普通语句、循环回边、函数调用都不会自动插入取消检查。这样做的
理由是：避免在每个基本块插入检查造成可观测的性能与语义噪声；让取消时机可预测；
让编译器降级保持简单。

**长 CPU 循环规则**：预计运行超过一个调度时间片的纯 CPU 循环，必须周期性调用
`checkCancellation()` 或 `yield`。这是编程契约，不是运行期可强制的能力；测试应覆盖
“取消请求后循环在下一个检查点退出”。

**状态机**（草案）：

```text
New ──start──▶ Running ──suspend──▶ Suspended
                  │                    │
                  │ cancel             │ cancel
                  ▼                    ▼
              Cancelling ◀─────────────┘
                  │ cleanup 完成
                  ▼
              Cancelled

Running ──正常返回──▶ Completed
Running ──业务异常──▶ Failed
```

| 取消请求时刻 | 行为 |
|--------------|------|
| 任务尚未开始 | 任务不执行正文，直接进入 `Cancelled`；`spawn` 返回已取消的 `Future` |
| 任务正在运行 | 设置 token；在下一个取消检查点抛出 `CancellationException` |
| 任务已挂起 | 唤醒挂起的续体，以 `CancellationException` 完成该挂起点 |
| 任务正在清理 | 不重复投递；清理结束后进入 `Cancelled` |
| 任务已终态 | 返回 `false`，不改变结果 |

### 4.3 不可取消区间：标注与限界

不可取消区间只用于“已经开始的清理/提交动作必须走完”的场景，不能用来屏蔽取消。
第一版使用已有注解机制表达，不新增关键字或语句：

```text
// 草案：注解类型
@interface NonCancellable {
    public int64 limitMillis();
}

// 草案：使用方式
@NonCancellable(limitMillis = 500)
function flushAndClose(): void {
    // 这里的挂起点不投递取消；退出后若 token 已置位，在下一个检查点继续取消
}
```

**规则（全部可判定）**：

1. `limitMillis` 必须是编译期正整数常量；缺省、`0`、负数、变量表达式均为编译错误。
2. 区间内取消请求不会丢失：token 保持置位，区间退出后的第一个取消检查点继续抛出
   `CancellationException`。
3. 区间不能延长父级上限：有效上限 = `min(本区间 limitMillis, 外层剩余上限)`。
4. 区间内不得执行无界阻塞调用；阻塞调用必须自带不超过剩余上限的超时。
5. 运行期在进入/退出时读取单调时钟；实际耗时超过有效上限时，报告
   `NonCancellableOverrun`（致命契约错误），并在下一个检查点投递取消。
6. 嵌套区间不允许扩大上限；内层只能更小或相等。
7. 注解覆盖该函数的**动态执行区间**，包括它调用的可挂起函数；但不覆盖它启动的
   子任务——子任务按自己的 token 正常观察取消。
8. 纯 CPU 且不含挂起点的代码不需要注解，因为它本来就不会在挂起点观察取消；但必须
   遵守 §4.2 的长循环规则。

**能力边界（必须明确）**：JVM 无法安全强杀线程，因此“限界”是**协作式限界**：
运行期能测量、能报告、能在下一个检查点投递取消，但不能在指令中途抢占。若底层
阻塞调用永不返回，任何语言层设计都无法保证回收；因此适配器必须为阻塞 IO 提供
超时或可取消变体，测试必须覆盖超时路径。

### 4.4 清理保证

1. **清理必达**：取消被观察后，任务进入 `Cancelling`，按 LIFO 执行 `defer` 与
   `finally`，全部完成后才进入 `Cancelled`。
2. **清理期间不重复取消**：清理阶段取消投递被抑制；已置位的 token 不丢失，清理
   结束后的检查点继续生效。
3. **作用域等待清理**：`TaskGroup.close()` 等待所有子任务完成清理才返回；不得出现
   “作用域已关闭但清理线程还在跑”的合法状态。
4. **清理错误不覆盖取消**：任务已被取消时，清理中抛出的业务错误只记录为诊断，
   终态仍是 `Cancelled`；任务原本成功时，清理错误把终态改为 `Failed`；任务原本
   失败时，原失败为主错误，清理错误只记录诊断。
5. **清理预算**：清理默认有运行期预算（建议默认 1000 ms，可由嵌入方配置）；清理中
   的挂起点按 `@NonCancellable` 规则限界。预算超限报告 `NonCancellableOverrun`，
   不静默忽略。
6. **资源顺序**：`defer` 后进先出；跨层清理由内向外；父作用域不代子任务释放其
   显式持有的资源，只保证等待子任务自己的清理完成。

### 4.5 CancellationException 与业务异常分离

| 维度 | `CancellationException` | 业务异常 |
|------|-------------------------|----------|
| 含义 | 执行被请求停止，不是结果错误 | 业务/系统失败 |
| 是否进入失败聚合 | 否 | 是（只有首个失败成为主错误） |
| 父任务能否区分 | 能，读取 `reason()` | 能，按异常类型区分 |
| 是否可被普通 `catch` 捕获 | 可以捕获以执行清理，但不得吞掉 | 可以按业务语义处理 |
| 吞掉后的行为 | 运行期保留 token，下一个检查点重新抛出 | 不会自动重新抛出 |
| 超时 | `reason == Deadline` | 不是超时；超时是取消原因，不是业务错误 |

**吞掉取消的处理**：允许 `catch (e: CancellationException)` 执行必要的本地清理，
但运行期不会清除 token；如果 catch 后继续执行，下一个取消检查点会再次抛出。这样
即使业务代码误吞，取消也不会永久失效。是否进一步在编译期禁止吞掉，是 §9 的裁决项。

### 4.6 取消与失败的竞态

终态由**第一次成功提交的终态转换**决定，使用原子比较交换保证唯一。终态提交本身是
最后一个取消检查点：如果 token 已经置位，成功提交或失败提交都转为 `Cancelled`，
正文错误只记录诊断；如果失败先于取消完成提交，则保留 `Failed`。

| 先发生的事件 | 终态 | 说明 |
|--------------|------|------|
| 取消请求先置位，随后正文返回/抛错 | `Cancelled` | 终态提交检查 token；正文错误只记录诊断 |
| 正文失败先提交，随后取消请求 | `Failed` | 失败优先；取消返回 `false` |
| Deadline 与显式取消同时到达 | 先提交者决定 reason | 另一个只记录诊断 |
| 兄弟失败触发取消 | 兄弟 `Failed`，被取消者 `Cancelled` | 取消不参与失败聚合 |

该规则让并发竞态在单次执行中可判定；测试通过可控调度器固定提交顺序，不依赖
真实时间竞争。

---

## 5. 失败传播

### 5.1 普通作用域（Structured）

普通作用域是默认模式，语义如下：

1. 任一子任务以业务异常进入 `Failed`，该失败成为**候选主失败**；
2. 作用域立即请求取消其余未完成子任务，原因 `SiblingFailed`；
3. 作用域停止接受新的 `spawn`；
4. `close()` 等待所有子任务进入终态并完成清理；
5. 若主失败尚未被作用域所有者通过 `await` 观察，`close()` 抛出主失败；主失败是
   **终态提交顺序最早**的失败，而不是“最后打印的”或“异常类型最严重的”；
6. 若主失败已经被所有者通过 `await` 观察，`close()` 只完成回收，不重复抛出，避免
   在 `defer close()` 路径上二次抛出并掩盖原始异常。

若没有业务失败，但作用域因外部原因（父取消、显式取消、Deadline）被取消，且该取消
尚未被所有者观察，则 `close()` 抛出带对应 reason 的 `CancellationException`；取消
不转成业务失败。由 `close()` 自己为回收未完成子任务而发起的 `ScopeExit` 取消属于
正常关闭路径，不单独抛出。

### 5.2 监督作用域（Supervisor）

监督作用域用于“一个子任务失败不应杀死兄弟”的场景：

1. 子任务失败只终止自己，不取消兄弟；
2. 每个 `Future.await()` 独立抛出该子任务的失败，由父任务显式处理；
3. 父作用域被取消时，取消仍然传播到所有子任务（监督只隔离失败，不隔离取消）；
4. `close()` 等待全部子任务进入终态；
5. 对**未被任何 `await` 观察**的失败，默认建议由 `close()` 抛出首个未观察失败，
   避免静默丢失（最终策略见 §9）。

“已观察”的判定：只要对该 `Future` 调用过 `await`，无论 `await` 是正常返回还是抛出，
都算已观察；父任务对已观察失败负责，`close()` 不再重复抛出。多次 `await` 同一
`Future` 返回同一结果/抛出同类型同消息的失败，不重复计数。

### 5.3 错误聚合规则

语言已裁决“错误不是值”：`Error` 及其子类只能出现在 `throw` 与 `catch` 中，不能作为
字段、参数、返回值或泛型实参。因此第一版**不引入 `AggregateError` 这类携带错误列表
的公开值类型**。聚合规则如下：

| 规则 | 判定 |
|------|------|
| 主错误选择 | 普通作用域取终态提交顺序最早的业务失败 |
| 后续失败 | 只记录到 Trace/诊断，不替换主错误，不单独抛出 |
| 取消 | 不参与聚合；取消与失败同时存在时按 §4.6 的终态规则 |
| 清理错误 | 按 §4.4 第 4 条处理，不改变已确定的取消/失败优先级 |
| 嵌套作用域 | 内层 `close()` 的主错误作为外层的一个子任务失败传播，不展开内层全部失败 |
| 监督作用域 | 每个失败由对应 `await` 独立观察；未观察失败按 §5.2 处理 |

这样做的代价是“后续失败不可被业务代码逐一捕获”，收益是不破坏错误模型、不引入
错误值容器、主错误稳定可判定。若未来确实需要完整聚合，必须先修改“错误不是值”的
语言裁决，而不是在运行时偷偷引入 `Error[]`。

### 5.4 作用域生命周期与回收

| 操作 | 前置条件 | 行为 | 失败模式 |
|------|----------|------|----------|
| `spawn` | 作用域未关闭且未取消；调用者是作用域所有者 | 创建子任务、捕获上下文快照、返回 `Future<T>` | 已关闭抛 `ScopeClosedException`；已取消返回已取消 `Future` 且不执行正文 |
| `cancel` | 任意任务可调用 | 请求取消全部子任务 | 已关闭时幂等，不复活任务 |
| `close` | 只能由所有者调用；可重复调用 | 停止接受新任务，取消未完成子任务，等待全部清理；仅抛出尚未被 `await` 观察的主失败/外部取消，自身 `ScopeExit` 不抛 | 非所有者调用抛 `ScopeClosedException`；重复调用返回同一终态结果 |
| 作用域逃逸 | 不应发生 | `TaskGroup` 不得被保存到比其词法作用域更长的位置 | 运行期通过所有者检查与关闭检查兜底；静态检查后续补充 |

**回收保证**：`close()` 返回时，作用域内不存在运行中、挂起中或清理中的子任务；不存在
未观察的 `Future` 仍在写状态；子任务持有的显式资源已由其自身清理路径释放。

---

## 6. 对外 API 映射

### 6.1 公开面与内部面

| 名称 | 可见性 | 角色 | 是否出现在公开签名 |
|------|--------|------|--------------------|
| `Future<T>` | 公开 | 异步结果句柄；可等待、可取消、可查询终态 | 是 |
| `TaskGroup` | 公开 | 结构化作用域；启动、取消、关闭、等待 | 是 |
| `TaskGroupOptions` | 公开 | 不可变选项构建器；模式、Deadline、上下文值 | 是 |
| `GroupMode` | 公开 | `Structured` / `Supervisor` | 是 |
| `Context` | 公开 | 不可变上下文快照的只读/派生视图 | 是 |
| `ContextKey<T>` | 公开 | 类型安全上下文键 | 是 |
| `Deadline` | 公开 | 单调时钟截止点 | 是 |
| `CancellationException` | 公开 | 取消控制流，带原因 | 是 |
| `CancellationReason` | 公开 | 取消原因枚举 | 是 |
| `ScopeClosedException` | 公开 | 作用域关闭后继续使用/非所有者调用的契约错误 | 是 |
| `NonCancellableOverrun` | 公开 | 不可取消区间超限的致命契约错误 | 是 |
| `@NonCancellable` | 公开 | 有界不可取消区间标注 | 是 |
| `TraceSpan` | 公开只读 | 追踪片段视图 | 是 |
| `SecurityIdentity` | 公开只读 | 不透明身份 | 是 |
| `Task` | 内部 | 运行期任务实体、父子链接、状态与结果 | **否** |
| `CancellationToken` | 内部 | 取消请求与传播 | 否 |
| `ExecutorPort` / `ExecutorRef` | 内部 | 平台调度端口与引用 | 否 |
| `Outcome` | 内部 | 终态与主错误/诊断 | 否 |

### 6.2 公开类型草案

以下签名均为**草案**。

```text
public interface Future<T> {
    // 挂起当前协程直到终态；在非协程入口阻塞当前线程。
    // 成功返回 T；失败抛原业务异常；取消抛 CancellationException。
    public function await(): T;

    // 请求取消；未终态返回 true，已终态返回 false。幂等。
    public function cancel(): bool;

    // 成功、失败、取消都算 done。
    public function isDone(): bool;

    // 仅当终态为 Cancelled 时为 true。
    public function isCancelled(): bool;
}
```

```text
public enum GroupMode {
    Structured,
    Supervisor
}

public final class TaskGroupOptions {
    // 不可变 Builder：每次调用返回新选项，避免共享可变配置。
    public static function structured(): TaskGroupOptions;
    public static function supervisor(): TaskGroupOptions;
    public function withDeadline(deadline: Deadline): TaskGroupOptions;
    public function withValue<T>(key: ContextKey<T>, value: T): TaskGroupOptions;
}

public final class TaskGroup {
    // 使用当前任务的上下文快照，默认 Structured 模式。
    public static function open(): TaskGroup;

    // 使用显式选项；选项在 open 时被复制，之后修改选项不影响已打开作用域。
    public static function open(options: TaskGroupOptions): TaskGroup;

    // 作用域自身的上下文快照（只读）。
    public function context(): Context;

    // 启动子任务；body 是“无参、返回 T 的 lambda”形状（TaskBody<T>），
    // 由编译器按目标类型推断，不是可实例化的公开名义类型。
    // 只能在所有者任务中调用；已关闭抛 ScopeClosedException；
    // 已取消时返回已取消 Future，且不执行 body。
    public function spawn<T>(body: TaskBody<T>): Future<T>;

    // 请求取消作用域内全部子任务；幂等；任意任务可调用。
    public function cancel(): void;

    // 显式取消检查点；已取消时抛 CancellationException。
    public function checkCancellation(): void;

    // 停止接受新任务，取消未完成子任务（原因 ScopeExit），等待全部清理；
    // 仅在主失败/外部取消尚未被 await 观察时抛出它；自身 ScopeExit 不抛。
    // 幂等；只能由所有者调用。
    public function close(): void;

    public function isClosed(): bool;
}
```

`TaskBody<T>` 表示“无参、返回 `T` 的 lambda”这一函数形状，由编译器按目标类型推断；
它不是新的公开类、接口或关键字，也不改变现有 lambda 语法。

```text
public final class Deadline {
    // 相对单调时钟的截止点；millis 必须 >= 0，0 表示立即过期。
    public static function after(millis: int64): Deadline;
    public function remainingMillis(): int64; // 已过期返回 <= 0
    public function isExpired(): bool;
}

public final class ContextKey<T> {
    public static function of<T>(name: string): ContextKey<T>;
    public function name(): string; // 仅用于诊断，不参与相等性
}

public final class Context {
    // 当前任务的快照；非协程入口返回根上下文。不得用 ThreadLocal 实现。
    public static function current(): Context;

    public function get<T>(key: ContextKey<T>): T?;
    public function with<T>(key: ContextKey<T>, value: T): Context;
    public function without<T>(key: ContextKey<T>): Context;
    public function deadline(): Deadline?;
    public function span(): TraceSpan?;
    public function identity(): SecurityIdentity?;
}
```

```text
public final class TraceSpan {
    public function traceId(): string;
    public function spanId(): string;
    public function parentSpanId(): string?;
    public function isRecording(): bool;
}

// 不透明身份：只表示“是谁”；权限、角色、授权缓存不在其中。
public interface SecurityIdentity {
}
```

```text
// 草案：作用域关闭后继续使用、或非所有者调用 close 时的契约错误。
public class ScopeClosedException extends Error {
}

// 草案：不可取消区间超过有效上限时的致命契约错误。
public class NonCancellableOverrun extends FatalError {
}
```

**公开面约束**：

- `Task` 不得出现在上述任何签名中；`spawn` 只返回 `Future<T>`。
- 不新增并发关键字；启动子任务只能通过 `TaskGroup.spawn`。
- `Future.await()` 是语言 `await` 的运行时落点；`await` 不是新的线程阻塞原语。
- 所有公开异常都遵循“错误不是值”：只抛出、只捕获，不作为字段或返回值。

### 6.3 内部类型草案

以下类型均为**草案**，不进入公开 API。

```text
internal class Task {
    id: TaskId;
    parent: Task?;
    context: Context;
    cancellation: CancellationToken;
    executor: ExecutorRef;
    state: TaskState;
    outcome: Outcome;
    children: TaskGroupImpl?;
}

internal class CancellationToken {
    requested: atomic bool;
    reason: CancellationReason?;
    parent: CancellationToken?;
    children: CancellationToken[];
}

internal class Outcome {
    state: TaskState;          // Completed / Failed / Cancelled
    value: opaque?;            // 成功值，按泛型擦除后的内部槽保存
    failure: opaque?;          // 主失败，按运行期错误载体保存
    diagnostics: Diagnostic[];// 后续失败、清理错误、取消竞态
}
```

`Outcome` 是唯一事实来源；`Future` 只是观察 `Outcome` 的视图，不维护第二份状态。

### 6.4 使用示例（草案）

普通作用域：两个子任务并行，任一失败会取消另一个；主失败未被 `await` 观察时由
`close()` 抛出，已被观察时只回收不重复抛出。

```text
function loadPair(): Pair {
    var group = TaskGroup.open();
    defer group.close();

    var left = group.spawn(lambda () {
        return fetchLeft();
    });
    var right = group.spawn(lambda () {
        return fetchRight();
    });

    return combine(await left, await right);
}
```

监督作用域：一个子任务失败不影响另一个，由父任务逐个处理。

```text
function loadBestEffort(): Pair {
    var group = TaskGroup.open(TaskGroupOptions.supervisor());
    defer group.close();

    var left = group.spawn(lambda () {
        return fetchLeft();
    });
    var right = group.spawn(lambda () {
        return fetchRight();
    });

    var a = tryAwaitOrNull(left);
    var b = tryAwaitOrNull(right);
    return combineNullable(a, b);
}
```

上下文值与 Deadline：

```text
var tenantKey = ContextKey.of<string>("tenant");

function withTenant(): void {
    var options = TaskGroupOptions.structured()
        .withValue(tenantKey, "acme")
        .withDeadline(Deadline.after(500));
    var group = TaskGroup.open(options);
    defer group.close();

    var result = await group.spawn(lambda () {
        // 子任务看到 tenant = "acme"；父任务看不到子任务的覆盖。
        return loadTenantData(Context.current().get(tenantKey));
    });
}
```

---

## 7. Java 8 约束

### 7.1 禁止与允许清单

| 能力 | 第一版 | 说明 |
|------|--------|------|
| 虚拟线程 | **禁止** | 不引用虚拟线程 API；线程模型由 `ExecutorPort` 决定 |
| Java 21+ 结构化并发 API | **禁止** | 不引用 `StructuredTaskScope` 等 API |
| `ExecutorService.close()` | **禁止** | 这是更高版本 API；关闭由适配器显式管理 |
| `ThreadLocal` | **禁止作为上下文载体** | 源码检索应为 0；上下文由续体显式携带 |
| 线程中断作为取消机制 | **禁止** | 取消是协作式检查点；适配器不得用中断代替 token |
| 线程池 | 允许 | `ThreadPoolExecutor`、`ScheduledThreadPoolExecutor` |
| `Future` / `CompletableFuture` | 允许 | 仅作为内部完成通知与等待实现，不泄漏到语言语义 |
| 自建续体/状态机 | 允许 | 挂起恢复的核心机制 |
| 字节码 major 52 | 必须 | 所有产物保持 Java 8 目标 |

### 7.2 第一版实现策略

| 关注点 | Java 8 实现策略 | 设计约束 |
|--------|------------------|----------|
| 协程挂起/恢复 | 编译期把可挂起函数降为堆上的状态机/续体；局部变量存入续体对象 | 续体必须显式持有 `Context` 引用，不读线程本地 |
| `Task` 执行 | 每个任务是一个状态机实例；由 `ExecutorPort.schedule` 驱动下一步 | `Task` 不绑定线程；恢复线程可以变化 |
| `Future<T>` | 内部用 CAS 状态 + 等待者列表；可用 `CompletableFuture` 承载完成通知 | `Future` 只是视图，`Outcome` 是唯一事实来源 |
| 任务内 `await` | 注册续体并让出执行权；不阻塞工作线程 | 工作线程不得因等待子任务而耗尽 |
| 入口处 `await` | 非协程入口可以阻塞当前线程，直到终态；必须有明确边界 | 阻塞只允许在调度器之外的调用线程；调度器线程内阻塞视为契约错误 |
| 取消 | 原子 token + 在检查点抛出；挂起中的任务由调度器以取消结果恢复 | 不调用线程中断；不强杀线程 |
| Deadline | `ScheduledThreadPoolExecutor` 定时投递取消；恢复时再次检查截止点 | 定时器延迟不能延长 Deadline |
| Executor 覆盖 | 默认 CPU 池；阻塞 IO 使用独立池；由内部 `ExecutorRef` 选择 | 运行中不可切换；子任务继承覆盖后的引用 |
| Trace / Security | 创建任务时写入 Context 快照；不依赖线程本地 | 跨线程恢复仍能读取同一快照 |
| 作用域关闭 | 引用计数/子任务集合 + 关闭屏障；等待全部终态与清理 | 关闭后不得再启动子任务 |

### 7.3 阻塞边界

- **协程内部**：`await` 必须是挂起，不得占住工作线程等待另一个任务；否则有界线程池
  可能死锁。
- **非协程入口**：允许阻塞等待，但该线程不属于调度池；嵌入方必须保证不会在调度
  线程上调用阻塞入口。
- **阻塞 IO**：必须通过独立执行器或可取消 IO 适配器；取消在 IO 返回/超时后观察。
- **不可取消区间**：不得用“阻塞等待”实现；必须使用带超时的原语，遵守 §4.3 的上限。

**结论**：Java 8 不提供栈式协程，但“状态机 + 续体 + 线程池 + 定时器”足以实现本契约；
关键不是线程数量，而是**上下文显式携带**与**完成通知唯一**。

---

## 8. executor 端口

### 8.1 概念形状

`ExecutorPort` 是语言运行期与平台之间的唯一调度缝。以下为**草案**，不是实现代码。

```text
internal interface ExecutorPort {
    // 在 delayMillis 后调度一个续体；delayMillis = 0 表示尽快。
    // 返回句柄用于撤销尚未开始的调度。
    public function schedule(continuation: Continuation, delayMillis: int64): ScheduleHandle;

    // 当前续体主动让出执行权，稍后重新调度；属于取消检查点。
    public function yield(continuation: Continuation): void;

    // 尽力撤销尚未开始的调度；不中断正在运行的续体。
    // 返回 true 表示撤销成功；false 表示已开始/已完成/不可撤销。
    public function cancel(handle: ScheduleHandle): bool;

    // 发布任务终态；每个任务恰好一次，且必须在清理完成之后。
    public function completion(task: TaskRef, outcome: Outcome): void;
}
```

### 8.2 操作语义

| 操作 | 输入 | 输出 | 何时使用 | 失败模式 |
|------|------|------|----------|----------|
| `schedule` | 续体、相对延迟（毫秒，`>= 0`） | `ScheduleHandle` | 任务启动、挂起后恢复、Deadline 定时 | 队列满/关闭时不得静默丢弃；必须完成失败或拒绝 |
| `yield` | 当前续体 | 无 | 协作让出、长循环检查点 | 不得忙等；不得丢失续体；不得改变上下文 |
| `cancel` | 调度句柄 | `bool` | 撤销尚未开始的恢复 | 不保证撤销正在运行的工作；不得清除取消 token |
| `completion` | 任务引用、终态 | 无 | 任务清理完成后发布结果 | 必须恰好一次；不得在发布后再次改状态 |

### 8.3 端口不变量

1. **恰好一次完成**：同一任务最多一次 `completion`；重复调用是适配器缺陷。
2. **先清理后完成**：`completion` 只能在 `Cancelling`/`Failed`/`Completed` 的清理
   路径结束后调用；清理完成 happens-before 于 `Future.await` 返回。
3. **无丢失唤醒**：未取消的 `schedule(delay=0)` 最终必须执行；关闭时必须以取消/失败
   完成，而不是丢弃。
4. **取消 token 与调度句柄分离**：`cancel(handle)` 只撤销排队中的恢复；任务是否取消
   由 `CancellationToken` 决定，二者不得互相替代。
5. **无上下文丢失**：`schedule`/`yield` 传递的是携带 `Context` 的续体；适配器不得
   重新从线程本地构造上下文。
6. **无重复恢复**：同一挂起点在一次挂起周期内只能被恢复一次；重复恢复是适配器缺陷。
7. **拒绝策略显式**：执行器关闭或饱和时，任务以可识别的致命错误完成；不得挂起
   永远不返回。
8. **时间单调**：Deadline 使用单调时钟；定时器晚到不延长截止时间，恢复时再次检查。

### 8.4 JVM 适配器边界

| 端口操作 | JVM 适配器映射（概念） | 注意 |
|----------|------------------------|------|
| `schedule` | `ScheduledThreadPoolExecutor.schedule` 或普通池提交 | 延迟 0 走就绪队列；延迟 > 0 走定时器 |
| `yield` | 重新提交当前续体到就绪队列 | 不忙等；不递归调用用户代码 |
| `cancel` | `ScheduledFuture.cancel(false)` | 不中断线程；返回是否撤销成功 |
| `completion` | CAS 写 `Outcome`，再完成内部 `CompletableFuture`/等待者 | 先写事实来源，再唤醒等待者 |

JVM 适配器只依赖 Java 8 API；不把 `java.util.concurrent.Future` 暴露为语言 `Future`，
两者同名但属于不同层。线程池大小、队列类型、拒绝策略属于适配器配置，不进入语言契约。

### 8.5 Jasper OS 适配器边界

未来适配器实现同一端口，把四个操作映射到平台的异步环/任务队列/定时器/完成事件：

| 端口操作 | 平台侧概念 | 约束 |
|----------|------------|------|
| `schedule` | 投递到异步环或任务队列；延迟交给定时轮 | 不改变语言层续体与上下文 |
| `yield` | 重新入队当前任务 | 保证其他任务有机会运行 |
| `cancel` | 撤销尚未开始的队列项 | 不强行终止正在运行的任务 |
| `completion` | 发布完成事件 | 恰好一次，先清理后发布 |

**边界规则**：语言层只认识 `ExecutorPort`，不认识 JVM 线程、文件描述符、异步环句柄或
平台任务 ID。平台适配器可以把这些细节封装在内部，但不得让它们出现在 `Future`、
`TaskGroup`、`Context` 或异常类型中。这样未来替换平台时语言层零改动。

---

## 9. 待用户裁决的问题

以下 3 条是本文无法单方面定稿的契约选择。每条给出推荐项；未裁决前，正文按推荐项
描述，后续实现不得擅自改变。

### 9.1 作用域生命周期载体

**问题**：`TaskGroup` 如何保证“离开词法作用域一定关闭”？

| 选项 | 说明 | 影响 |
|------|------|------|
| A. 工厂 + `defer close()`（推荐） | 现有语法即可表达；用户显式写 `defer` | 不新增语法；依赖用户纪律，漏写会由所有权/关闭检查兜底 |
| B. 新增块级作用域语法 | 类似资源作用域的专用语句 | 表达最强制；需要改语法与编译器，超出本轮“不新增关键字”的约束 |
| C. 回调式 `run(lambda)` | 由运行时在回调返回时自动关闭 | 不易漏写；但闭包捕获、返回值和异常路径更复杂，可能诱导嵌套回调 |

**推荐 A**：与现有 `defer` 语义一致，零语法变更；后续实现先用运行期关闭检查与测试
保证，再评估是否需要静态检查。

### 9.2 监督作用域的未观察失败

**问题**：监督作用域中，子任务失败但父任务从未 `await` 该 `Future`，`close()` 应如何
处理？

| 选项 | 说明 | 影响 |
|------|------|------|
| A. `close()` 抛首个未观察失败（推荐） | 保证失败不被静默吞掉 | 父任务必须处理或显式忽略；否则关闭路径抛错 |
| B. 静默记录到 Trace | 关闭不抛错 | 实现简单；但违反“失败不静默”的资源纪律，难以测试 |
| C. 强制注册失败处理器 | `spawn` 时要求提供 handler | 最显式；API 变重，和 `await` 二选一，增加学习成本 |

**推荐 A**：与普通作用域“失败必须被看到”一致；父任务若确实想忽略，应在 `await`
处显式捕获并记录。

### 9.3 被 `catch` 吞掉的取消

**问题**：业务代码 `catch (e: CancellationException)` 后不重新抛出，运行期应如何
处理？

| 选项 | 说明 | 影响 |
|------|------|------|
| A. 保留 token，下一个检查点重新抛出（推荐） | 取消不会被永久吞掉；catch 只能做局部清理 | 行为可预测；用户不能靠 catch 把取消变成正常返回 |
| B. 编译期禁止捕获 `CancellationException` | 从语法/检查器层面禁止 | 最强；但清理代码可能确实需要捕获后执行 finally，规则过严 |
| C. 允许吞掉，视为普通异常 | 完全交给用户 | 最简单；但会破坏结构化并发的取消保证，不推荐 |

**推荐 A**：与 §4.5 一致，兼顾清理需求与取消不可丢失。

---

## 10. 验收对照

| 本文要求 | 对应章节 | 机械检查 |
|----------|----------|----------|
| 三层职责边界 | §1 | 三层各有职责/不拥有/失败模式；依赖图无环 |
| 上下文元素表 | §2 | 每个元素有类型形状、创建方式、继承规则、可见性 |
| 继承矩阵可判定 | §3 | 11 行；每行一句话判定；判定函数可逐项对照 |
| 取消只在挂起点生效 | §4.2 | 检查点清单封闭；无异步抢占描述 |
| 不可取消区间有界 | §4.3 | 注解要求编译期正常量；有效上限取 min；超限有错误 |
| 清理保证 | §4.4 | 清理先于终态；作用域等待清理；清理错误优先级明确 |
| 取消与业务异常分离 | §4.5 | 独立类型；不参与失败聚合；reason 可区分超时 |
| 普通/监督作用域差异 | §5.1、§5.2 | 失败是否牵连兄弟；未观察失败规则明确 |
| 错误聚合规则 | §5.3 | 主错误选择、后续失败、嵌套、清理错误均有判定 |
| 公开 API 映射 | §6 | `Future`/`TaskGroup` 公开；`Task` 不出现在公开签名 |
| 不出现并发关键字 | §6.2 | 启动子任务只经 `TaskGroup.spawn` |
| Java 8 约束 | §7 | 禁止清单；线程池/Future/续体策略；major 52 |
| executor 端口 | §8 | 四个操作语义、不变量、JVM 与 OS 适配器边界 |
| 待裁决问题 | §9 | 3 条，每条有选项、推荐与影响 |

**本轮不修改任何语法文件、编译器、标准库或测试**；本文只作为后续实现的契约基线。
