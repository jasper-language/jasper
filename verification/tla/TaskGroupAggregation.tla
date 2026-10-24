----------------------- MODULE TaskGroupAggregation -----------------------
\* Author: opencode. S4-B(J) TaskGroup 失败传播与聚合快照模型。
\*
\* 模型目的：
\*   穷举单层作用域内“派生 / 完成 / 失败 / 取消 / 确认 / 关闭”的有限交错，
\*   检查三条已裁决语义：
\*     1) 普通组首个失败后，未终态兄弟进入 Cancelled（允许与已完成兄弟竞争）；
\*     2) 监督组不因兄弟失败取消其他任务；
\*     3) B 策略只在首个 close 时冻结聚合：已确认失败被排除，之后确认不再改写结果。
\*
\* 抽象边界：
\*   - 不建模线程池、等待队列和标记 settled 的具体协议；close 的前置条件直接写成
\*     “没有 Pending 子任务”，等价于实现中等待全部执行体退出后的可聚合状态。
\*   - Tasks 是固定的小集合；Spawn 用 Absent -> Pending 表达，关闭后 Spawn 被禁用。
\*   - 为聚焦聚合与冻结语义，Spawn 只在尚无失败时开放；真实实现允许在 close 前继续
\*     spawn，但失败后继续扩容不属于本模型要验证的结构化作用域用法。
\*   - cascadeIssued 表示普通组已经执行过 onChildFailure 的级联取消。
\*
\* 状态数由 TLC 分别用 normal / supervisor 两份 cfg 穷举，见交付报告。

EXTENDS Naturals, TLC, FiniteSets

CONSTANTS Tasks, Supervisor

ASSUME Tasks = {1, 2}
ASSUME Supervisor \in BOOLEAN

Statuses == {"Absent", "Pending", "Completed", "Failed", "Cancelled"}
Outcomes == {"Ok", "Failure"}

VARIABLES
    childStatus,      \* 每个任务槽位的状态
    acknowledged,     \* 失败是否已被调用方确认
    closed,           \* 是否已封闭新增入口
    frozen,           \* 聚合结果是否已在首次 close 冻结
    frozenAck,        \* 首次 close 时的确认快照
    outcome,          \* 冻结后的聚合结论
    primary,          \* primary 失败的任务编号；0 表示无
    suppressed,       \* 被压制的其他失败数量
    cascadeIssued,    \* 普通组是否已触发兄弟取消
    spawnedCount,     \* 已派生任务数
    closedSpawnCount  \* 首次 close 时已派生的任务数

vars == <<childStatus, acknowledged, closed, frozen, frozenAck, outcome,
          primary, suppressed, cascadeIssued, spawnedCount, closedSpawnCount>>

Init ==
    /\ childStatus = [t \in Tasks |-> "Absent"]
    /\ acknowledged = [t \in Tasks |-> FALSE]
    /\ closed = FALSE
    /\ frozen = FALSE
    /\ frozenAck = [t \in Tasks |-> FALSE]
    /\ outcome = "Ok"
    /\ primary = 0
    /\ suppressed = 0
    /\ cascadeIssued = FALSE
    /\ spawnedCount = 0
    /\ closedSpawnCount = 0

\* 关闭前且尚无失败时可以继续派生；关闭后该动作不可用。
Spawn(t) ==
    /\ ~closed
    /\ \A u \in Tasks: childStatus[u] # "Failed"
    /\ childStatus[t] = "Absent"
    /\ childStatus' = [childStatus EXCEPT ![t] = "Pending"]
    /\ spawnedCount' = spawnedCount + 1
    /\ UNCHANGED <<acknowledged, closed, frozen, frozenAck, outcome,
                   primary, suppressed, cascadeIssued, closedSpawnCount>>

Complete(t) ==
    /\ childStatus[t] = "Pending"
    /\ childStatus' = [childStatus EXCEPT ![t] = "Completed"]
    /\ UNCHANGED <<acknowledged, closed, frozen, frozenAck, outcome,
                   primary, suppressed, cascadeIssued, spawnedCount,
                   closedSpawnCount>>

Fail(t) ==
    /\ childStatus[t] = "Pending"
    /\ childStatus' = [childStatus EXCEPT ![t] = "Failed"]
    /\ UNCHANGED <<acknowledged, closed, frozen, frozenAck, outcome,
                   primary, suppressed, cascadeIssued, spawnedCount,
                   closedSpawnCount>>

Cancel(t) ==
    /\ childStatus[t] = "Pending"
    /\ childStatus' = [childStatus EXCEPT ![t] = "Cancelled"]
    /\ UNCHANGED <<acknowledged, closed, frozen, frozenAck, outcome,
                   primary, suppressed, cascadeIssued, spawnedCount,
                   closedSpawnCount>>

\* 普通组：任一失败出现后，把所有仍 Pending 的兄弟一次性取消；已完成/已失败者不变。
CancelSiblings ==
    /\ ~Supervisor
    /\ ~cascadeIssued
    /\ \E t \in Tasks: childStatus[t] = "Failed"
    /\ cascadeIssued' = TRUE
    /\ childStatus' =
         [t \in Tasks |->
              IF childStatus[t] = "Pending" THEN "Cancelled" ELSE childStatus[t]]
    /\ UNCHANGED <<acknowledged, closed, frozen, frozenAck, outcome,
                   primary, suppressed, spawnedCount, closedSpawnCount>>

\* 只允许确认已经失败的任务；确认本身不改变子任务状态。
Acknowledge(t) ==
    /\ childStatus[t] = "Failed"
    /\ ~acknowledged[t]
    /\ acknowledged' = [acknowledged EXCEPT ![t] = TRUE]
    /\ UNCHANGED <<childStatus, closed, frozen, frozenAck, outcome,
                   primary, suppressed, cascadeIssued, spawnedCount,
                   closedSpawnCount>>

\* 首次 close 冻结聚合；重复 close 不重新计算（对应 aggregationDone）。
Eligible(ackSnapshot) ==
    {t \in Tasks:
        childStatus[t] = "Failed" /\ ~(Supervisor /\ ackSnapshot[t])}

Close ==
    /\ \A t \in Tasks: childStatus[t] # "Pending"
    /\ closed' = TRUE
    /\ frozen' = TRUE
    /\ frozenAck' = IF frozen THEN frozenAck ELSE acknowledged
    /\ closedSpawnCount' = IF closed THEN closedSpawnCount ELSE spawnedCount
    /\ outcome' =
         IF frozen THEN outcome
         ELSE IF Eligible(acknowledged) = {} THEN "Ok" ELSE "Failure"
    /\ primary' =
         IF frozen THEN primary
         ELSE IF Eligible(acknowledged) = {} THEN 0
              ELSE CHOOSE t \in Eligible(acknowledged):
                       \A u \in Eligible(acknowledged): t <= u
    /\ suppressed' =
         IF frozen THEN suppressed
         ELSE IF Eligible(acknowledged) = {} THEN 0
              ELSE Cardinality(Eligible(acknowledged)) - 1
    /\ UNCHANGED <<childStatus, acknowledged, cascadeIssued, spawnedCount>>

Next ==
    \/ (\E t \in Tasks: Spawn(t) \/ Complete(t) \/ Fail(t) \/ Cancel(t)
                        \/ Acknowledge(t))
    \/ CancelSiblings
    \/ Close

Spec == Init /\ [][Next]_vars

\* ---------------------------------------------------------------------------
\* 安全性不变量
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ \A t \in Tasks: childStatus[t] \in Statuses
    /\ \A t \in Tasks: acknowledged[t] \in BOOLEAN
    /\ closed \in BOOLEAN
    /\ frozen \in BOOLEAN
    /\ \A t \in Tasks: frozenAck[t] \in BOOLEAN
    /\ outcome \in Outcomes
    /\ primary \in Tasks \cup {0}
    /\ suppressed \in 0..Cardinality(Tasks)
    /\ cascadeIssued \in BOOLEAN
    /\ spawnedCount \in 0..Cardinality(Tasks)
    /\ closedSpawnCount \in 0..Cardinality(Tasks)

\* 关闭后不再派生：派生计数与关闭时的快照一致。
ClosedFreezesSpawns ==
    closed => spawnedCount = closedSpawnCount

\* 聚合只可能发生在没有 Pending 子任务时；冻结后同样没有 Pending。
FrozenHasNoPending ==
    frozen => \A t \in Tasks: childStatus[t] # "Pending"

\* 普通组级联执行后，所有原先 Pending 的兄弟都已离开 Pending。
NormalCascadeLeavesNoPending ==
    cascadeIssued => \A t \in Tasks: childStatus[t] # "Pending"

\* 监督组从不触发级联取消。
SupervisorNeverCascades ==
    Supervisor => ~cascadeIssued

\* 普通组不得因确认而隐藏失败。
NormalFailureCannotBeAcknowledgedAway ==
    /\ frozen
    /\ ~Supervisor
    /\ \E t \in Tasks: childStatus[t] = "Failed"
    => outcome = "Failure"

\* 监督组按首次 close 时的确认快照排除失败。
SupervisorOutcomeMatchesSnapshot ==
    frozen =>
        (outcome = "Failure") <=>
            (\E t \in Tasks:
                 childStatus[t] = "Failed" /\ ~(Supervisor /\ frozenAck[t]))

\* 冻结后的 primary 必须是快照中最早编号的未排除失败。
PrimaryMatchesSnapshot ==
    frozen /\ outcome = "Failure" =>
        /\ primary \in Eligible(frozenAck)
        /\ \A t \in Eligible(frozenAck): primary <= t

\* suppressed 恰好是未排除失败数减一。
SuppressedMatchesSnapshot ==
    frozen =>
        suppressed =
            IF Eligible(frozenAck) = {} THEN 0
            ELSE Cardinality(Eligible(frozenAck)) - 1

\* 确认只可能发生在 Failed 任务上。
AcknowledgedOnlyAfterFailure ==
    \A t \in Tasks: acknowledged[t] => childStatus[t] = "Failed"

=============================================================================
