------------------------- MODULE CleanupBudgetNesting -------------------------
\* Author: opencode. S4-B(J) 清理预算嵌套模型。
\*
\* 模型目的：
\*   复现 CleanupBudget 的 deadline 计算裁决：
\*     1) 根预算 deadline = start + min(全局上限, 局部上限)；
\*     2) 嵌套预算复用父 startedAt，不重置计时起点；
\*     3) 子 deadline = min(父 deadline, 子创建时刻 + 子局部上限)，
\*        因而任何嵌套层级都不能晚于父截止点，也不能突破自己的局部上限；
\*     4) 父已过期时子必然已过期，即使子区间在父过期之后才创建。
\*
\* 抽象边界：
\*   - 时钟只建模为 Naturals 上的单调递增计数，不建模 System.nanoTime 的 JVM 实现；
\*   - 不建模整数溢出、线程竞争与 remainingNanos 的二次读时钟；这些由运行期测试覆盖；
\*   - 每层只保留一个子预算，ReNest 把当前子提升为父，从而检查多层嵌套的传递性。
\*
\* 状态数由 TLC 在 CleanupBudgetNesting.cfg 下穷举，见交付报告。

EXTENDS Naturals, TLC

CONSTANTS
    GlobalLimit,        \* 运行时全局清理上限
    ParentLocalLimit,   \* 根区间局部显式上限
    ChildLimits,        \* 嵌套区间可用的局部上限集合
    MaxTime             \* 有限时钟上界

ASSUME GlobalLimit \in Nat
ASSUME ParentLocalLimit \in Nat
ASSUME ChildLimits \subseteq Nat
ASSUME MaxTime \in Nat

Min(a, b) == IF a <= b THEN a ELSE b

VARIABLES
    now,             \* 单调时钟
    depth,           \* 当前父预算距根预算的嵌套层数
    parentStart,     \* 父预算 start
    parentDeadline,  \* 父预算 deadline
    childStart,      \* 最近一次嵌套出的子预算 start
    childDeadline,   \* 最近一次嵌套出的子预算 deadline
    childCreatedAt,  \* 子预算创建时刻
    childLimit,      \* 子预算的局部显式上限
    hasChild         \* 是否已经派生出子预算

vars == <<now, depth, parentStart, parentDeadline, childStart, childDeadline,
          childCreatedAt, childLimit, hasChild>>

Init ==
    /\ now = 0
    /\ depth = 0
    /\ parentStart = 0
    /\ parentDeadline = Min(GlobalLimit, ParentLocalLimit)
    /\ childStart = 0
    /\ childDeadline = 0
    /\ childCreatedAt = 0
    /\ childLimit = 0
    /\ hasChild = FALSE

\* 时钟只前进；预算本身不读取未来的时钟值。
Tick ==
    /\ now < MaxTime
    /\ now' = now + 1
    /\ UNCHANGED <<depth, parentStart, parentDeadline, childStart, childDeadline,
                   childCreatedAt, childLimit, hasChild>>

\* 第一次嵌套：start 继承父 start，deadline 取父 deadline 与 (now + 局部上限) 的较小者。
Nest(L) ==
    /\ ~hasChild
    /\ depth = 0
    /\ L \in ChildLimits
    /\ depth' = 1
    /\ childStart' = parentStart
    /\ childCreatedAt' = now
    /\ childLimit' = L
    /\ childDeadline' = Min(parentDeadline, now + L)
    /\ hasChild' = TRUE
    /\ UNCHANGED <<now, parentStart, parentDeadline>>

\* 再嵌套：当前子预算成为父预算，新子预算仍然复用同一个 start，并继续收紧 deadline。
ReNest(L) ==
    /\ hasChild
    /\ depth > 0
    /\ depth < 3
    /\ L \in ChildLimits
    /\ depth' = depth + 1
    /\ parentStart' = childStart
    /\ parentDeadline' = childDeadline
    /\ childStart' = childStart
    /\ childCreatedAt' = now
    /\ childLimit' = L
    /\ childDeadline' = Min(childDeadline, now + L)
    /\ hasChild' = TRUE
    /\ UNCHANGED now

Next ==
    \/ Tick
    \/ (\E L \in ChildLimits: Nest(L))
    \/ (\E L \in ChildLimits: ReNest(L))

Spec == Init /\ [][Next]_vars

\* 安全性不变量
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ now \in 0..MaxTime
    /\ depth \in 0..3
    /\ parentStart \in 0..MaxTime
    /\ parentDeadline \in 0..MaxTime
    /\ childStart \in 0..MaxTime
    /\ childDeadline \in 0..MaxTime
    /\ childCreatedAt \in 0..MaxTime
    /\ childLimit \in ChildLimits \cup {0}
    /\ hasChild \in BOOLEAN

\* 根预算的有效上限是全局与局部的较小值。
RootDeadlineIsMin ==
    depth = 0 => parentDeadline = Min(GlobalLimit, ParentLocalLimit)

\* 嵌套不重置计时：子 start 始终等于父 start（传递到根即恒为 0）。
ChildStartIsParentStart ==
    hasChild => childStart = parentStart

\* 嵌套不延长外层：子 deadline 永远不晚于父 deadline。
NestedNeverExtendsParent ==
    hasChild => childDeadline <= parentDeadline

\* 子区间也不能突破自己的局部显式上限（相对创建时刻）。
NestedHonorsLocalLimit ==
    hasChild => childDeadline <= childCreatedAt + childLimit

\* 子预算不能由未来的创建时刻产生。
ChildNotFromFuture ==
    hasChild => childCreatedAt <= now

\* 父已过期 => 子已过期；嵌套不能把外层过期后的时钟重新变成可用额度。
ExpiredParentImpliesExpiredChild ==
    hasChild /\ now >= parentDeadline => now >= childDeadline

=============================================================================
