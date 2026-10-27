----------------------- MODULE CoroutineScopeLaunch -----------------------
\* S4-C 第九片：生成帧经 TaskGroup.launch 登记、失败级联、作用域取消唤醒与 settled 回收。
\* 帧位置：new(未启动) -> ready0(首段已入队) -> waiting(已挂起) -> ready1(恢复已入队) -> exit。
\* 每段入口先检查结果是否已被外部终结；结果终态回调（帧在 start 时订阅）把挂起帧唤醒。
\* 失败级联与作用域取消在失败发布的同一回调批次内原子完成（批次顺序另见 CallbackDispatch）。
EXTENDS Naturals, FiniteSets
CONSTANTS Frames, Supervisor
VARIABLES pos, result, input, settled, launched, closed, joined, cause, rootCancelled
vars == <<pos, result, input, settled, launched, closed, joined, cause, rootCancelled>>

Init == /\ pos = [f \in Frames |-> "new"] /\ result = [f \in Frames |-> "Pending"]
        /\ input = [f \in Frames |-> "Pending"] /\ settled = [f \in Frames |-> FALSE]
        /\ launched = {} /\ closed = FALSE /\ joined = FALSE
        /\ cause = [f \in Frames |-> "none"] /\ rootCancelled = FALSE

\* 取消一组已登记帧：只改 Pending 结果，挂起帧被结果回调唤醒为 ready1。
CancelSet(set, why) ==
    /\ result' = [g \in Frames |-> IF g \in set /\ result[g] = "Pending" THEN "Cancelled" ELSE result[g]]
    /\ cause' = [g \in Frames |-> IF g \in set /\ result[g] = "Pending" THEN why ELSE cause[g]]
    /\ pos' = [g \in Frames |-> IF g \in set /\ result[g] = "Pending" /\ pos[g] = "waiting" THEN "ready1" ELSE pos[g]]

\* launch：关闭后不可用（不登记、不启动）；否则先登记再请求首次恢复。
\* 登记时根令牌已取消，则先发布 Cancelled，帧首段在入口检查处直接回收。
Launch(f) == /\ pos[f] = "new" /\ ~closed
             /\ launched' = launched \cup {f} /\ pos' = [pos EXCEPT ![f] = "ready0"]
             /\ result' = [result EXCEPT ![f] = IF rootCancelled THEN "Cancelled" ELSE "Pending"]
             /\ cause' = [cause EXCEPT ![f] = IF rootCancelled THEN "group" ELSE "none"]
             /\ UNCHANGED <<input, settled, closed, joined, rootCancelled>>
\* 首段：结果已终结则直接回收；否则执行挂起前段并挂起（输入已就绪时立即再入队）。
Run0(f) == /\ pos[f] = "ready0"
           /\ IF result[f] # "Pending"
              THEN /\ pos' = [pos EXCEPT ![f] = "exit"] /\ settled' = [settled EXCEPT ![f] = TRUE]
              ELSE /\ pos' = [pos EXCEPT ![f] = IF input[f] = "Pending" THEN "waiting" ELSE "ready1"]
                   /\ UNCHANGED settled
           /\ UNCHANGED <<result, input, launched, closed, joined, cause, rootCancelled>>
\* 输入完成：回调只唤醒挂起帧，不直接执行恢复段。
Input(f, v) == /\ input[f] = "Pending" /\ input' = [input EXCEPT ![f] = v]
               /\ pos' = [pos EXCEPT ![f] = IF pos[f] = "waiting" THEN "ready1" ELSE pos[f]]
               /\ UNCHANGED <<result, settled, launched, closed, joined, cause, rootCancelled>>
\* 恢复段：结果已终结则只回收；否则按输入发布，失败在普通组内原子取消根令牌与兄弟。
Run1(f) == /\ pos[f] = "ready1" /\ input[f] # "Pending"
           /\ settled' = [settled EXCEPT ![f] = TRUE]
           /\ IF result[f] # "Pending"
              THEN /\ pos' = [pos EXCEPT ![f] = "exit"]
                   /\ UNCHANGED <<result, cause, rootCancelled>>
              ELSE LET out == IF input[f] = "Ok" THEN "Completed" ELSE IF input[f] = "Err" THEN "Failed" ELSE "Cancelled"
                       cascade == out = "Failed" /\ ~Supervisor
                       others == IF cascade THEN launched \ {f} ELSE {}
                   IN /\ result' = [g \in Frames |-> IF g = f THEN out
                                     ELSE IF g \in others /\ result[g] = "Pending" THEN "Cancelled" ELSE result[g]]
                      /\ cause' = [g \in Frames |-> IF g \in others /\ result[g] = "Pending" THEN "cascade" ELSE cause[g]]
                      /\ pos' = [g \in Frames |-> IF g = f THEN "exit"
                                  ELSE IF g \in others /\ result[g] = "Pending" /\ pos[g] = "waiting" THEN "ready1" ELSE pos[g]]
                      /\ rootCancelled' = (rootCancelled \/ cascade)
           /\ UNCHANGED <<input, launched, closed, joined>>
\* 被取消唤醒的挂起帧即使输入永不完成，也经入口检查回收。
Reclaim(f) == /\ pos[f] = "ready1" /\ input[f] = "Pending" /\ result[f] # "Pending"
              /\ pos' = [pos EXCEPT ![f] = "exit"] /\ settled' = [settled EXCEPT ![f] = TRUE]
              /\ UNCHANGED <<result, input, launched, closed, joined, cause, rootCancelled>>
GroupCancel == /\ CancelSet(launched, "group") /\ rootCancelled' = TRUE
               /\ UNCHANGED <<input, settled, launched, closed, joined>>
Close == /\ ~closed /\ closed' = TRUE
         /\ UNCHANGED <<pos, result, input, settled, launched, joined, cause, rootCancelled>>
Join == /\ closed /\ ~joined /\ (\A f \in launched : settled[f]) /\ joined' = TRUE
        /\ UNCHANGED <<pos, result, input, settled, launched, closed, cause, rootCancelled>>

Next == \/ \E f \in Frames : Launch(f) \/ Run0(f) \/ Run1(f) \/ Reclaim(f)
        \/ \E f \in Frames, v \in {"Ok", "Err", "Cancel"} : Input(f, v)
        \/ GroupCancel \/ Close \/ Join
Fairness == \A f \in Frames : WF_vars(Run0(f)) /\ WF_vars(Run1(f)) /\ WF_vars(Reclaim(f))
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ pos \in [Frames -> {"new", "ready0", "waiting", "ready1", "exit"}]
          /\ result \in [Frames -> {"Pending", "Completed", "Failed", "Cancelled"}]
          /\ input \in [Frames -> {"Pending", "Ok", "Err", "Cancel"}]
          /\ settled \in [Frames -> BOOLEAN] /\ launched \subseteq Frames
          /\ cause \in [Frames -> {"none", "group", "cascade"}] /\ rootCancelled \in BOOLEAN
\* 回收必在终态之后；未登记的帧从不启动。
SettledImpliesTerminal == \A f \in Frames : settled[f] => result[f] # "Pending"
UnlaunchedNeverRuns == \A f \in Frames : f \notin launched => pos[f] = "new"
\* 普通组：一旦有失败，所有已登记帧（含失败之后才 launch 的帧）都已终结。
NormalCascade == ~Supervisor => ((\E f \in launched : result[f] = "Failed") => \A g \in launched : result[g] # "Pending")
\* 根令牌已取消时不存在 Pending 的已登记帧。
CancelledScopeHasNoPending == rootCancelled => \A f \in launched : result[f] # "Pending"
\* 监督组从不级联。
SupervisorNoCascade == Supervisor => \A f \in Frames : cause[f] # "cascade"
\* join 只在全部已登记帧 settled 后成立；被取消的结果不会再变成成功或失败。
JoinAfterSettled == joined => \A f \in launched : settled[f]
CancelledIsFinal == \A f \in Frames : cause[f] # "none" => result[f] = "Cancelled"
\* 活性：已登记帧的结果一旦终结（含作用域取消、输入永不完成的挂起帧），终将 settled。
TerminalEventuallySettled == \A f \in Frames : [](f \in launched /\ result[f] # "Pending" => <>settled[f])
=============================================================================
