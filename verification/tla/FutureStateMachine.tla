------------------------- MODULE FutureStateMachine -------------------------
\* Author: opencode. S4-B(J) Future 终态发布模型。
\*
\* 模型目的：
\*   用最小的显式状态机复现未来结果的两条核心裁决：
\*     1) complete / fail / cancel 三者只有第一个能发布终态；
\*     2) onComplete 注册的回调对每个注册者恰好执行一次（含终态后注册的立即回调）。
\*
\* 抽象边界：
\*   - 不建模锁、Semaphore、线程调度或 get() 的等待队列；这些是实现手段，
\*     其可观察结果由“原子发布终态”这一动作表达。
\*   - callbacks 用有限集合表示不同注册者；每个注册者至多注册一次，
\*     因此 callbackRuns[c] <= 1 等价于“回调不重复、不丢失”。
\*   - acknowledged 只对 Failed 有意义，对应 Future.acknowledgeFailure()。
\*
\* 状态数由 TLC 在 FutureStateMachine.cfg 下穷举得到，见交付报告。
------------------------------------------------------------------------------

EXTENDS Naturals, TLC

CONSTANTS Values, FailureKinds, Callbacks

ASSUME Values = {1, 2}
ASSUME FailureKinds = {"error", "platform"}
ASSUME Callbacks = {1, 2}

Statuses == {"Pending", "Completed", "Failed", "Cancelled"}

VARIABLES
    status,             \* FutureStatus
    value,              \* Completed 时的成功值；其他状态为 0
    failure,            \* Failed 时的失败分类；其他状态为 "none"
    acknowledged,       \* 失败是否已被调用方确认
    pendingCallbacks,   \* 已注册但尚未执行的回调
    callbackRuns,       \* 每个回调已执行次数
    publishCount        \* 终态发布次数，用来直接检查“只发布一次”

vars == <<status, value, failure, acknowledged, pendingCallbacks,
          callbackRuns, publishCount>>

\* 发布终态时统一清空待执行回调，并把它们各执行一次。
FlushCallbacks ==
    [c \in Callbacks |-> IF c \in pendingCallbacks THEN 1 ELSE callbackRuns[c]]

Init ==
    /\ status = "Pending"
    /\ value = 0
    /\ failure = "none"
    /\ acknowledged = FALSE
    /\ pendingCallbacks = {}
    /\ callbackRuns = [c \in Callbacks |-> 0]
    /\ publishCount = 0

\* 仅 Pending 时允许注册；注册本身不改变状态。
Register(c) ==
    /\ status = "Pending"
    /\ c \notin pendingCallbacks
    /\ callbackRuns[c] = 0
    /\ pendingCallbacks' = pendingCallbacks \cup {c}
    /\ UNCHANGED <<status, value, failure, acknowledged, callbackRuns,
                   publishCount>>

\* 终态后注册：立即执行一次，不进入 pendingCallbacks。
ObserveAfterTerminal(c) ==
    /\ status # "Pending"
    /\ c \notin pendingCallbacks
    /\ callbackRuns[c] = 0
    /\ callbackRuns' = [callbackRuns EXCEPT ![c] = 1]
    /\ UNCHANGED <<status, value, failure, acknowledged, pendingCallbacks,
                   publishCount>>

\* 三个终态动作的共同形状：只有 Pending 可迁移，且发布计数从 0 变 1。
Complete(v) ==
    /\ status = "Pending"
    /\ publishCount = 0
    /\ status' = "Completed"
    /\ value' = v
    /\ failure' = "none"
    /\ acknowledged' = FALSE
    /\ pendingCallbacks' = {}
    /\ callbackRuns' = FlushCallbacks
    /\ publishCount' = 1

Fail(k) ==
    /\ status = "Pending"
    /\ publishCount = 0
    /\ status' = "Failed"
    /\ value' = 0
    /\ failure' = k
    /\ acknowledged' = FALSE
    /\ pendingCallbacks' = {}
    /\ callbackRuns' = FlushCallbacks
    /\ publishCount' = 1

Cancel ==
    /\ status = "Pending"
    /\ publishCount = 0
    /\ status' = "Cancelled"
    /\ value' = 0
    /\ failure' = "none"
    /\ acknowledged' = FALSE
    /\ pendingCallbacks' = {}
    /\ callbackRuns' = FlushCallbacks
    /\ publishCount' = 1

\* 只有 Failed 且尚未确认时才允许确认；重复确认不再改变状态。
Acknowledge ==
    /\ status = "Failed"
    /\ ~acknowledged
    /\ acknowledged' = TRUE
    /\ UNCHANGED <<status, value, failure, pendingCallbacks, callbackRuns,
                   publishCount>>

Next ==
    \/ (\E v \in Values: Complete(v))
    \/ (\E k \in FailureKinds: Fail(k))
    \/ Cancel
    \/ (\E c \in Callbacks: Register(c) \/ ObserveAfterTerminal(c))
    \/ Acknowledge

Spec == Init /\ [][Next]_vars

\* ---------------------------------------------------------------------------
\* 安全性不变量
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ status \in Statuses
    /\ value \in Values \cup {0}
    /\ failure \in FailureKinds \cup {"none"}
    /\ acknowledged \in BOOLEAN
    /\ pendingCallbacks \subseteq Callbacks
    /\ \A c \in Callbacks: callbackRuns[c] \in 0..1
    /\ publishCount \in 0..1

\* 终态与发布计数一一对应：Pending 恰好 0 次，终态恰好 1 次。
ExactlyOneTerminalPublish ==
    (status = "Pending") <=> (publishCount = 0)

\* 已发布终态后不可能再进入另一个终态；该式与上一式共同锁定“恰好一次”。
TerminalIsAbsorbing ==
    publishCount = 1 => status \in {"Completed", "Failed", "Cancelled"}

\* 回调不重复执行：无论早注册还是终态后注册，每个注册者至多一次。
CallbacksAtMostOnce ==
    \A c \in Callbacks: callbackRuns[c] <= 1

\* 终态发布后不得残留待执行回调；否则表示有回调丢失。
NoPendingCallbacksAfterTerminal ==
    status # "Pending" => pendingCallbacks = {}

\* 待执行回调只可能存在于 Pending 状态。
PendingCallbacksOnlyWhilePending ==
    pendingCallbacks # {} => status = "Pending"

\* 成功值只属于 Completed；失败分类只属于 Failed。
PayloadMatchesStatus ==
    /\ (status = "Completed") <=> (value \in Values)
    /\ (status = "Failed") <=> (failure \in FailureKinds)

\* 失败确认只可能发生在 Failed 状态。
AcknowledgedOnlyAfterFailure ==
    acknowledged => status = "Failed"

=============================================================================
