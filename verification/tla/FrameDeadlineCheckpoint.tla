---------------------- MODULE FrameDeadlineCheckpoint ----------------------
\* S4-C 生成帧截止检查点（LANG-S4-FRAME-DEADLINE-01）：帧经 TaskGroup.launch / launchWithDeadline 持有
\* 根令牌的子令牌，每趟分派开头与每个挂起点入口检查截止时间。作用域到期由任一检查点（帧自身或 launch）
\* 观察：根令牌以 Deadline 取消，经子令牌回调（ScopeCancellationRelay）取消全部已登记帧的结果并唤醒挂起帧；
\* 帧自身期限到期只取消它自己。无计时器：到期本身不是检查点。
\* 帧位置：new -> ready0(首趟已入队) -> suspending(业务段已执行、位于挂起点入口) -> waiting(已挂起)
\*   -> ready1(恢复已入队) -> exit。
\* 变异常量：SuspendCheck = FALSE 去掉挂起点入口检查；Relay = FALSE 去掉令牌到结果的取消传播。
EXTENDS Naturals, FiniteSets
CONSTANTS Frames, Supervisor, SuspendCheck, Relay
VARIABLES pos, result, input, settled, launched, cause, rootCancelled, expired, ownExpired,
          ranAfterExpiry, suspendedAfterExpiry
vars == <<pos, result, input, settled, launched, cause, rootCancelled, expired, ownExpired,
          ranAfterExpiry, suspendedAfterExpiry>>

Init == /\ pos = [f \in Frames |-> "new"] /\ result = [f \in Frames |-> "Pending"]
        /\ input = [f \in Frames |-> "Pending"] /\ settled = [f \in Frames |-> FALSE]
        /\ launched = {} /\ cause = [f \in Frames |-> "none"] /\ rootCancelled = FALSE
        /\ expired = FALSE /\ ownExpired = [f \in Frames |-> FALSE]
        /\ ranAfterExpiry = FALSE /\ suspendedAfterExpiry = FALSE

\* 一次检查点的效果（帧 f 的检查，或 f = "launch" 表示 launch 登记处的检查）：
\* 作用域已到期且根未取消 -> 根以 Deadline 取消；有 Relay 时全部已登记帧的 Pending 结果随之取消，
\* 否则只有做检查的帧自己被取消（它的令牌检查经祖先看到根已取消）。随后帧自身期限到期只取消自己。
ScopeHit == expired /\ ~rootCancelled
Hit(f, reg) == IF ScopeHit \/ rootCancelled
               THEN (IF Relay THEN reg ELSE (reg \cap {f}))
               ELSE {}
Own(f) == IF f \in Frames /\ ownExpired[f] THEN {f} ELSE {}
NewResult(f, reg) == [g \in Frames |-> IF g \in Hit(f, reg) /\ result[g] = "Pending" THEN "Cancelled"
                      ELSE IF g \in Own(f) /\ result[g] = "Pending" THEN "Cancelled" ELSE result[g]]
NewCause(f, reg) == [g \in Frames |-> IF g \in Hit(f, reg) /\ result[g] = "Pending" THEN "deadline"
                     ELSE IF g \in Own(f) /\ result[g] = "Pending" THEN "own" ELSE cause[g]]
\* 被取消的挂起帧由结果回调唤醒。
Woken(f, reg, p) == [g \in Frames |-> IF g # f /\ g \in Hit(f, reg) /\ result[g] = "Pending" /\ p[g] = "waiting"
                     THEN "ready1" ELSE p[g]]

\* launch：先检查作用域截止点，再登记、派生子令牌（根已取消则子令牌立即取消）、注册传播、请求首趟。
\* withOwn 表示 launchWithDeadline 给了一个之后可能到期的显式期限。
Launch(f) == /\ pos[f] = "new"
             /\ LET reg == launched \cup {f}
                    hit == IF (ScopeHit \/ rootCancelled) /\ Relay THEN reg ELSE {}
                IN /\ launched' = reg
                   /\ result' = [g \in Frames |-> IF g \in hit /\ result[g] = "Pending" THEN "Cancelled" ELSE result[g]]
                   /\ cause' = [g \in Frames |-> IF g \in hit /\ result[g] = "Pending" THEN "deadline" ELSE cause[g]]
                   /\ pos' = [g \in Frames |-> IF g = f THEN "ready0"
                              ELSE IF g \in hit /\ result[g] = "Pending" /\ pos[g] = "waiting" THEN "ready1" ELSE pos[g]]
                   /\ rootCancelled' = (rootCancelled \/ expired)
             /\ UNCHANGED <<input, settled, expired, ownExpired, ranAfterExpiry, suspendedAfterExpiry>>
\* 首趟：分派入口检查点；结果已终结则回收，否则执行挂起点之前的业务段。
Run0(f) == /\ pos[f] = "ready0"
           /\ LET res == NewResult(f, launched) IN
              /\ result' = res /\ cause' = NewCause(f, launched)
              /\ rootCancelled' = (rootCancelled \/ expired)
              /\ IF res[f] # "Pending"
                 THEN /\ pos' = [Woken(f, launched, pos) EXCEPT ![f] = "exit"]
                      /\ settled' = [settled EXCEPT ![f] = TRUE]
                      /\ UNCHANGED ranAfterExpiry
                 ELSE /\ pos' = [Woken(f, launched, pos) EXCEPT ![f] = "suspending"]
                      /\ ranAfterExpiry' = (ranAfterExpiry \/ expired \/ ownExpired[f])
                      /\ UNCHANGED settled
           /\ UNCHANGED <<input, launched, expired, ownExpired, suspendedAfterExpiry>>
\* 挂起点入口：检查点（SuspendCheck）后注册输入回调；输入已就绪则立即请求下一趟。
Suspend(f) == /\ pos[f] = "suspending"
              /\ LET res == IF SuspendCheck THEN NewResult(f, launched) ELSE result
                     p0 == IF SuspendCheck THEN Woken(f, launched, pos) ELSE pos
                 IN /\ result' = res
                    /\ cause' = IF SuspendCheck THEN NewCause(f, launched) ELSE cause
                    /\ rootCancelled' = IF SuspendCheck THEN (rootCancelled \/ expired) ELSE rootCancelled
                    /\ IF res[f] # "Pending"
                       THEN /\ pos' = [p0 EXCEPT ![f] = "exit"] /\ settled' = [settled EXCEPT ![f] = TRUE]
                            /\ UNCHANGED suspendedAfterExpiry
                       ELSE /\ pos' = [p0 EXCEPT ![f] = IF input[f] = "Pending" THEN "waiting" ELSE "ready1"]
                            /\ suspendedAfterExpiry' = (suspendedAfterExpiry \/
                                   (input[f] = "Pending" /\ (expired \/ ownExpired[f])))
                            /\ UNCHANGED settled
              /\ UNCHANGED <<input, launched, expired, ownExpired, ranAfterExpiry>>
Input(f, v) == /\ input[f] = "Pending" /\ input' = [input EXCEPT ![f] = v]
               /\ pos' = [pos EXCEPT ![f] = IF pos[f] = "waiting" THEN "ready1" ELSE pos[f]]
               /\ UNCHANGED <<result, settled, launched, cause, rootCancelled, expired, ownExpired,
                              ranAfterExpiry, suspendedAfterExpiry>>
\* 恢复趟：分派入口检查点；结果已终结则回收（输入可能永不完成）；否则按输入发布，
\* 失败在普通组内取消根与兄弟（SiblingFailed）。
Run1(f) == /\ pos[f] = "ready1"
           /\ LET res == NewResult(f, launched)
                  p0 == Woken(f, launched, pos)
                  c0 == NewCause(f, launched)
              IN IF res[f] # "Pending"
                 THEN /\ result' = res /\ cause' = c0 /\ rootCancelled' = (rootCancelled \/ expired)
                      /\ pos' = [p0 EXCEPT ![f] = "exit"] /\ settled' = [settled EXCEPT ![f] = TRUE]
                      /\ UNCHANGED ranAfterExpiry
                 ELSE /\ input[f] # "Pending"
                      /\ ranAfterExpiry' = (ranAfterExpiry \/ expired \/ ownExpired[f])
                      /\ settled' = [settled EXCEPT ![f] = TRUE]
                      /\ LET out == IF input[f] = "Ok" THEN "Completed" ELSE IF input[f] = "Err" THEN "Failed" ELSE "Cancelled"
                             cascade == out = "Failed" /\ ~Supervisor
                             others == IF cascade THEN launched \ {f} ELSE {}
                         IN /\ result' = [g \in Frames |-> IF g = f THEN out
                                           ELSE IF g \in others /\ res[g] = "Pending" THEN "Cancelled" ELSE res[g]]
                            /\ cause' = [g \in Frames |-> IF g \in others /\ res[g] = "Pending" THEN "cascade" ELSE c0[g]]
                            /\ pos' = [g \in Frames |-> IF g = f THEN "exit"
                                        ELSE IF g \in others /\ res[g] = "Pending" /\ p0[g] = "waiting" THEN "ready1" ELSE p0[g]]
                            /\ rootCancelled' = (rootCancelled \/ expired \/ cascade)
           /\ UNCHANGED <<input, launched, expired, ownExpired, suspendedAfterExpiry>>
\* 到期：作用域截止点或某帧自身截止点过去；本身不是检查点，不改变任何结果。
Expire == /\ ~expired /\ expired' = TRUE
          /\ UNCHANGED <<pos, result, input, settled, launched, cause, rootCancelled, ownExpired,
                         ranAfterExpiry, suspendedAfterExpiry>>
ExpireOwn(f) == /\ ~ownExpired[f] /\ ownExpired' = [ownExpired EXCEPT ![f] = TRUE]
                /\ UNCHANGED <<pos, result, input, settled, launched, cause, rootCancelled, expired,
                               ranAfterExpiry, suspendedAfterExpiry>>

Next == \/ \E f \in Frames : Launch(f) \/ Run0(f) \/ Suspend(f) \/ Run1(f) \/ ExpireOwn(f)
        \/ \E f \in Frames, v \in {"Ok", "Err", "Cancel"} : Input(f, v)
        \/ Expire
Fairness == \A f \in Frames : WF_vars(Run0(f)) /\ WF_vars(Suspend(f)) /\ WF_vars(Run1(f))
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ pos \in [Frames -> {"new", "ready0", "suspending", "waiting", "ready1", "exit"}]
          /\ result \in [Frames -> {"Pending", "Completed", "Failed", "Cancelled"}]
          /\ input \in [Frames -> {"Pending", "Ok", "Err", "Cancel"}]
          /\ settled \in [Frames -> BOOLEAN] /\ launched \subseteq Frames
          /\ cause \in [Frames -> {"none", "deadline", "own", "cascade"}] /\ rootCancelled \in BOOLEAN
\* 到期之后不执行任何业务段（业务段只在检查点之后、同一原子步内执行）。
NoBusinessAfterExpiry == ~ranAfterExpiry
\* 业务段内到期：挂起点入口观察到，帧不会在到期后再挂起等待输入。
NoSuspendAfterExpiry == ~suspendedAfterExpiry
\* 根令牌已取消（含被观察到的作用域到期）时不存在 Pending 的已登记帧——挂起帧经传播被取消并唤醒。
CancelledScopeHasNoPending == rootCancelled => \A f \in launched : result[f] # "Pending"
\* 帧自身到期不是业务失败：不触发级联，也不取消根。
OwnExpiryIsLocal == /\ rootCancelled => (expired \/ \E f \in Frames : result[f] = "Failed")
                    /\ \A f \in Frames : cause[f] = "cascade" => \E g \in Frames : result[g] = "Failed"
SettledImpliesTerminal == \A f \in Frames : settled[f] => result[f] # "Pending"
UnlaunchedNeverRuns == \A f \in Frames : f \notin launched => pos[f] = "new"
\* 活性：已登记帧的结果一旦终结（含被到期取消、输入永不完成的挂起帧），终将 settled。
TerminalEventuallySettled == \A f \in Frames : [](f \in launched /\ result[f] # "Pending" => <>settled[f])
=============================================================================
