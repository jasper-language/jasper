--------------------- MODULE BranchingCoroutineFrame ---------------------
\* S4-C 第十片：多挂起点、if/else 分支生成帧的块级状态机。
\* 控制流图取自端到端测试函数 Program.choose 的块规划：
\*   0 分支 -> 1 | 3；1 挂起 A -> 2；2 恢复后汇合 -> 7；3 分支 -> 4 | 5；4 提前 return；
\*   5 挂起 A -> 6；6 恢复后汇合 -> 7；7 挂起 B -> 8；8 恢复后分支 -> 9 | 10；9、10 return。
\* 建模要点：`__phase` 是下一块编号；一次运行先做入口终态检查，再逐块执行（块原子），
\* 挂起块先检查结果是否已终结，再写 phase、注册回调（回调可在返回前同步到达）、返回；
\* 所有回调（输入 A/B 以及帧对自身结果的订阅）写同一个 `__outcome` 字段并请求恢复；
\* 调度门：运行中到达的恢复请求记为 wake，段结束后合并入队；结果终结的段结束即关门回收。
\* 抽象掉锁、线程调度与 JVM 内存模型；块内表达式求值视为原子。
EXTENDS Naturals
Blocks == 0..10
Futures == {"A", "B"}
Terminal == {"Completed", "Failed", "Cancelled"}
Branches == {0, 3, 8}
Suspends == {1, 5, 7}
Gotos == {2, 6}
Resumes == {2, 6, 8}
Succ(b) == CASE b = 0 -> {1, 3} [] b = 1 -> {2} [] b = 2 -> {7} [] b = 3 -> {4, 5}
             [] b = 5 -> {6} [] b = 6 -> {7} [] b = 7 -> {8} [] b = 8 -> {9, 10}
             [] OTHER -> {}
AwaitOf(b) == IF b = 7 THEN "B" ELSE "A"
ResumeOf(b) == IF b = 8 THEN "B" ELSE "A"
Next1(b) == CHOOSE s \in Succ(b) : TRUE

VARIABLES phase, running, entry, returning, queued, wake, closed, settled,
          result, resultNotified, input, registered, delivered, outcome, count, bad
vars == <<phase, running, entry, returning, queued, wake, closed, settled,
          result, resultNotified, input, registered, delivered, outcome, count, bad>>

Init == /\ phase = 0 /\ running = FALSE /\ entry = FALSE /\ returning = FALSE
        /\ queued = TRUE /\ wake = FALSE /\ closed = FALSE /\ settled = FALSE
        /\ result = "Pending" /\ resultNotified = FALSE
        /\ input = [f \in Futures |-> "Pending"]
        /\ registered = [f \in Futures |-> FALSE]
        /\ delivered = [f \in Futures |-> FALSE]
        /\ outcome = "None" /\ count = [b \in Blocks |-> 0] /\ bad = FALSE

\* 恢复请求：已关门忽略；运行中记 wake；否则入队（已排队则合并）。
RequestResume == IF closed THEN UNCHANGED <<queued, wake>>
                 ELSE IF running THEN wake' = TRUE /\ UNCHANGED queued
                 ELSE queued' = TRUE /\ UNCHANGED wake

\* 段结束（含 finally）：结果已终结则关门并标记 settled，否则把运行中到达的请求入队。
EndRun(res) == /\ running' = FALSE /\ entry' = FALSE /\ returning' = FALSE /\ wake' = FALSE
               /\ IF res # "Pending"
                  THEN closed' = TRUE /\ settled' = TRUE /\ queued' = FALSE
                  ELSE queued' = (queued \/ wake) /\ UNCHANGED <<closed, settled>>

CompleteInput(f) == /\ input[f] = "Pending" /\ \E s \in Terminal : input' = [input EXCEPT ![f] = s]
                    /\ UNCHANGED <<phase, running, entry, returning, queued, wake, closed, settled,
                                   result, resultNotified, registered, delivered, outcome, count, bad>>

ExternalCancel == /\ result = "Pending" /\ result' = "Cancelled"
                  /\ UNCHANGED <<phase, running, entry, returning, queued, wake, closed, settled,
                                 resultNotified, input, registered, delivered, outcome, count, bad>>

InputCallback(f) == /\ registered[f] /\ input[f] \in Terminal /\ ~delivered[f]
                    /\ delivered' = [delivered EXCEPT ![f] = TRUE] /\ outcome' = input[f]
                    /\ RequestResume
                    /\ UNCHANGED <<phase, running, entry, returning, closed, settled, result,
                                   resultNotified, input, registered, count, bad>>

\* start() 先订阅自身结果：结果终结（帧自己或作用域取消）时同样写 __outcome 并请求恢复。
ResultCallback == /\ result \in Terminal /\ ~resultNotified
                  /\ resultNotified' = TRUE /\ outcome' = result
                  /\ RequestResume
                  /\ UNCHANGED <<phase, running, entry, returning, closed, settled, result,
                                 input, registered, delivered, count, bad>>

Begin == /\ queued /\ ~running /\ ~closed
         /\ running' = TRUE /\ entry' = TRUE /\ queued' = FALSE
         /\ UNCHANGED <<phase, returning, wake, closed, settled, result, resultNotified,
                        input, registered, delivered, outcome, count, bad>>

EntryCheck == /\ running /\ entry
              /\ IF result # "Pending"
                 THEN EndRun(result)
                 ELSE entry' = FALSE /\ UNCHANGED <<running, returning, queued, wake, closed, settled>>
              /\ UNCHANGED <<phase, result, resultNotified, input, registered, delivered, outcome, count, bad>>

\* 执行块的普通出口（恢复消费成功之后或非恢复块）。
Exit(b) ==
    CASE b \in Branches ->
            /\ phase' \in Succ(b) /\ UNCHANGED <<result, registered>>
            /\ UNCHANGED <<running, entry, returning, queued, wake, closed, settled>>
      [] b \in Gotos ->
            /\ phase' = Next1(b) /\ UNCHANGED <<result, registered>>
            /\ UNCHANGED <<running, entry, returning, queued, wake, closed, settled>>
      [] b \in Suspends ->
            IF result # "Pending"
            THEN EndRun(result) /\ UNCHANGED <<phase, result, registered>>
            ELSE /\ phase' = Next1(b)
                 /\ registered' = [registered EXCEPT ![AwaitOf(b)] = TRUE]
                 /\ returning' = TRUE
                 /\ UNCHANGED <<result, running, entry, queued, wake, closed, settled>>
      [] OTHER ->
            LET res == IF result = "Pending" THEN "Completed" ELSE result IN
            /\ result' = res /\ EndRun(res) /\ UNCHANGED <<phase, registered>>

Step == /\ running /\ ~entry /\ ~returning
        /\ count' = [count EXCEPT ![phase] = @ + 1]
        /\ IF phase \in Resumes
           THEN LET f == ResumeOf(phase) IN
                /\ bad' = (bad \/ ~delivered[f] \/ (outcome = "Completed" /\ input[f] # "Completed"))
                /\ IF outcome \in {"Failed", "Cancelled"}
                   THEN LET res == IF result = "Pending" THEN outcome ELSE result IN
                        result' = res /\ EndRun(res) /\ UNCHANGED <<phase, registered>>
                   ELSE Exit(phase)
           ELSE Exit(phase) /\ UNCHANGED bad
        /\ UNCHANGED <<resultNotified, input, delivered, outcome>>

Return == /\ running /\ returning /\ EndRun(result)
          /\ UNCHANGED <<phase, result, resultNotified, input, registered, delivered, outcome, count, bad>>

Next == \/ \E f \in Futures : CompleteInput(f) \/ InputCallback(f)
        \/ ExternalCancel \/ ResultCallback \/ Begin \/ EntryCheck \/ Step \/ Return

Fairness == /\ \A f \in Futures : WF_vars(CompleteInput(f)) /\ WF_vars(InputCallback(f))
            /\ WF_vars(ResultCallback) /\ WF_vars(Begin) /\ WF_vars(EntryCheck)
            /\ WF_vars(Step) /\ WF_vars(Return)
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ phase \in Blocks /\ running \in BOOLEAN /\ entry \in BOOLEAN /\ returning \in BOOLEAN
          /\ queued \in BOOLEAN /\ wake \in BOOLEAN /\ closed \in BOOLEAN /\ settled \in BOOLEAN
          /\ result \in {"Pending"} \cup Terminal /\ outcome \in {"None"} \cup Terminal
          /\ input \in [Futures -> {"Pending"} \cup Terminal]
\* 每块至多执行一次：恢复不重放已执行的段，await 操作数与挂起前表达式只求值一次。
AtMostOnce == \A b \in Blocks : count[b] <= 1
\* 恢复块只在其输入回调送达之后执行，且「成功」快照必来自该输入。
ResumeAfterDelivery == ~bad
\* 调度门：运行中不会另有一段排队，因此执行段不重叠。
NoOverlap == running => ~queued
\* 回收只在结果终结之后，回收后不再运行、不再排队。
SettledIsFinal == settled => (result \in Terminal /\ ~running /\ ~queued /\ closed)

ForwardOnly == [][phase' >= phase]_phase
ResultStable == [][(result \in Terminal) => (result' = result)]_result
EventuallySettled == <>settled
=============================================================================
