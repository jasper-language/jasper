---------------------- MODULE LoopingCoroutineFrame ----------------------
\* S4-C 第十一片：含 while 回边的生成帧块级状态机。
\* 控制流图取自端到端测试函数 Program.sum 的块规划：
\*   0 -> 1；1 自旋循环头 -> 2 | 3；2 -> 1（回边）；3 -> 4；
\*   4 主循环头 -> 5 | 17；5 自增后分支 -> 6 | 7；6 continue -> 4（回边）；7 -> 8；
\*   8 挂起 input -> 9；9 恢复后分支 -> 10 | 11；10 break -> 17；11 -> 12；
\*   12 分支 -> 13 | 15；13 挂起 gate -> 14；14 恢复 -> 16；15 -> 16；16 -> 4（回边）；
\*   17 分支 -> 18 | 19；18 return；19 -> 20；20 return。
\* 建模要点：`__phase` 是下一块编号；运行由分派循环组成，每趟开头做终态检查（PassCheck
\* 为 FALSE 时只在入口检查一次，用于变异）；块原子执行，前向出口在同一趟内继续，回边
\* 开启新一趟。主循环趟数由 MaxIter 限定（对应 i < limit），自旋循环由常量 spin 决定、
\* 不会自行结束。同一个 Future 在循环中被重复 await：每次经过挂起块都重新注册一个回调，
\* 已完成的 Future 在注册时即可回调；回调写共享 `__outcome` 并请求恢复，调度门同第十片。
\* 抽象掉锁、线程调度与 JVM 内存模型；块内表达式求值视为原子。
EXTENDS Naturals
CONSTANTS MaxIter, PassCheck
Blocks == 0..20
Futures == {"input", "gate"}
Terminal == {"Completed", "Failed", "Cancelled"}
Headers == {1, 4}
Suspends == {8, 13}
Resumes == {9, 14}
Finishes == {18, 20}
AwaitOf(b) == IF b = 8 THEN "input" ELSE "gate"
ResumeOf(b) == IF b = 9 THEN "input" ELSE "gate"
\* 普通出口目标（不含由常量 / 计数器决定的循环头）。
Succ(b) == CASE b = 0 -> {1} [] b = 2 -> {1} [] b = 3 -> {4} [] b = 5 -> {6, 7}
             [] b = 6 -> {4} [] b = 7 -> {8} [] b = 8 -> {9} [] b = 9 -> {10, 11}
             [] b = 10 -> {17} [] b = 11 -> {12} [] b = 12 -> {13, 15} [] b = 13 -> {14}
             [] b = 14 -> {16} [] b = 15 -> {16} [] b = 16 -> {4} [] b = 17 -> {18, 19}
             [] b = 19 -> {20} [] OTHER -> {}

VARIABLES spin, iter, phase, running, entry, returning, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, preludeRuns, bad
vars == <<spin, iter, phase, running, entry, returning, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, preludeRuns, bad>>

Init == /\ spin \in BOOLEAN /\ iter = 0
        /\ phase = 0 /\ running = FALSE /\ entry = FALSE /\ returning = FALSE
        /\ queued = TRUE /\ wake = FALSE /\ closed = FALSE /\ settled = FALSE
        /\ result = "Pending" /\ resultNotified = FALSE
        /\ input = [f \in Futures |-> "Pending"]
        /\ armed = [f \in Futures |-> FALSE]
        /\ delivered = [f \in Futures |-> FALSE]
        /\ outcome = "None" /\ preludeRuns = 0 /\ bad = FALSE

RequestResume == IF closed THEN UNCHANGED <<queued, wake>>
                 ELSE IF running THEN wake' = TRUE /\ UNCHANGED queued
                 ELSE queued' = TRUE /\ UNCHANGED wake

EndRun(res) == /\ running' = FALSE /\ entry' = FALSE /\ returning' = FALSE /\ wake' = FALSE
               /\ IF res # "Pending"
                  THEN closed' = TRUE /\ settled' = TRUE /\ queued' = FALSE
                  ELSE queued' = (queued \/ wake) /\ UNCHANGED <<closed, settled>>

CompleteInput(f) == /\ input[f] = "Pending" /\ \E s \in Terminal : input' = [input EXCEPT ![f] = s]
                    /\ UNCHANGED <<spin, iter, phase, running, entry, returning, queued, wake, closed,
                                   settled, result, resultNotified, armed, delivered, outcome, preludeRuns, bad>>

ExternalCancel == /\ result = "Pending" /\ result' = "Cancelled"
                  /\ UNCHANGED <<spin, iter, phase, running, entry, returning, queued, wake, closed,
                                 settled, resultNotified, input, armed, delivered, outcome, preludeRuns, bad>>

\* 一次注册对应一次回调：回调消耗注册（armed），并为当前这次经过记下送达（delivered）。
InputCallback(f) == /\ armed[f] /\ input[f] \in Terminal
                    /\ armed' = [armed EXCEPT ![f] = FALSE]
                    /\ delivered' = [delivered EXCEPT ![f] = TRUE] /\ outcome' = input[f]
                    /\ RequestResume
                    /\ UNCHANGED <<spin, iter, phase, running, entry, returning, closed, settled,
                                   result, resultNotified, input, preludeRuns, bad>>

ResultCallback == /\ result \in Terminal /\ ~resultNotified
                  /\ resultNotified' = TRUE /\ outcome' = result
                  /\ RequestResume
                  /\ UNCHANGED <<spin, iter, phase, running, entry, returning, closed, settled,
                                 result, input, armed, delivered, preludeRuns, bad>>

Begin == /\ queued /\ ~running /\ ~closed
         /\ running' = TRUE /\ entry' = TRUE /\ queued' = FALSE
         /\ UNCHANGED <<spin, iter, phase, returning, wake, closed, settled, result, resultNotified,
                        input, armed, delivered, outcome, preludeRuns, bad>>

\* 每趟开头的终态检查；第一趟即入口检查。
PassStart == /\ running /\ entry
             /\ IF result # "Pending"
                THEN EndRun(result)
                ELSE entry' = FALSE /\ UNCHANGED <<running, returning, queued, wake, closed, settled>>
             /\ UNCHANGED <<spin, iter, phase, result, resultNotified, input, armed, delivered,
                            outcome, preludeRuns, bad>>

\* 跳到 n：回边（n <= 当前块）结束本趟，下一趟开头再做终态检查。
Jump(b, n) == /\ phase' = n
              /\ entry' = (PassCheck /\ n <= b)
              /\ UNCHANGED <<running, returning, queued, wake, closed, settled, result>>

Exit(b) ==
    CASE b = 1 ->
            Jump(b, IF spin THEN 2 ELSE 3) /\ UNCHANGED <<iter, armed>>
      [] b = 4 ->
            IF iter < MaxIter THEN Jump(b, 5) /\ iter' = iter + 1 /\ UNCHANGED armed
            ELSE Jump(b, 17) /\ UNCHANGED <<iter, armed>>
      [] b \in Suspends ->
            IF result # "Pending"
            THEN EndRun(result) /\ UNCHANGED <<phase, result, iter, armed>>
            ELSE /\ phase' = CHOOSE s \in Succ(b) : TRUE
                 /\ armed' = [armed EXCEPT ![AwaitOf(b)] = TRUE]
                 /\ returning' = TRUE
                 /\ UNCHANGED <<result, iter, running, entry, queued, wake, closed, settled>>
      [] b \in Finishes ->
            LET res == IF result = "Pending" THEN "Completed" ELSE result IN
            /\ result' = res /\ EndRun(res) /\ UNCHANGED <<phase, iter, armed>>
      [] OTHER ->
            \E n \in Succ(b) : Jump(b, n) /\ UNCHANGED <<iter, armed>>

\* 重入检查：挂起块再次注册时上一次注册必须已经回调（同一 Future 不会挂两个回调）；
\* 恢复块必须消费本次经过的送达（不会用上一趟的陈旧快照），「成功」快照必来自该输入。
Step == /\ running /\ ~entry /\ ~returning
        /\ preludeRuns' = IF phase = 0 THEN preludeRuns + 1 ELSE preludeRuns
        /\ IF phase \in Resumes
           THEN LET f == ResumeOf(phase) IN
                /\ bad' = (bad \/ ~delivered[f] \/ (outcome = "Completed" /\ input[f] # "Completed"))
                /\ delivered' = [delivered EXCEPT ![f] = FALSE]
                /\ IF outcome \in {"Failed", "Cancelled"}
                   THEN LET res == IF result = "Pending" THEN outcome ELSE result IN
                        result' = res /\ EndRun(res) /\ UNCHANGED <<phase, iter, armed>>
                   ELSE Exit(phase)
           ELSE /\ bad' = (bad \/ (phase \in Suspends /\ armed[AwaitOf(phase)]))
                /\ Exit(phase) /\ UNCHANGED delivered
        /\ UNCHANGED <<spin, resultNotified, input, outcome>>

Return == /\ running /\ returning /\ EndRun(result)
          /\ UNCHANGED <<spin, iter, phase, result, resultNotified, input, armed, delivered,
                         outcome, preludeRuns, bad>>

Next == \/ \E f \in Futures : CompleteInput(f) \/ InputCallback(f)
        \/ ExternalCancel \/ ResultCallback \/ Begin \/ PassStart \/ Step \/ Return

Fairness == /\ \A f \in Futures : WF_vars(CompleteInput(f)) /\ WF_vars(InputCallback(f))
            /\ WF_vars(ResultCallback) /\ WF_vars(Begin) /\ WF_vars(PassStart)
            /\ WF_vars(Step) /\ WF_vars(Return)
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ spin \in BOOLEAN /\ iter \in 0..MaxIter /\ phase \in Blocks
          /\ running \in BOOLEAN /\ entry \in BOOLEAN /\ returning \in BOOLEAN
          /\ queued \in BOOLEAN /\ wake \in BOOLEAN /\ closed \in BOOLEAN /\ settled \in BOOLEAN
          /\ result \in {"Pending"} \cup Terminal /\ outcome \in {"None"} \cup Terminal
          /\ input \in [Futures -> {"Pending"} \cup Terminal]
\* 回边不重放循环之前的块：入口块至多执行一次。
PreludeOnce == preludeRuns <= 1
\* 每次恢复都消费本次注册的送达；同一 Future 不会同时挂两个回调。
ResumeAfterDelivery == ~bad
NoOverlap == running => ~queued
SettledIsFinal == settled => (result \in Terminal /\ ~running /\ ~queued /\ closed)

\* 程序位置只在回边上变小，且回边只指向循环头。
BackEdgesToHeaders == [][(phase' < phase) => (phase' \in Headers)]_phase
ResultStable == [][(result \in Terminal) => (result' = result)]_result
\* 不自旋时帧总会结束并回收；自旋时一旦作用域取消也必须回收（回边处的终态检查）。
TerminatesUnlessSpinning == ~spin => <>settled
CancelLeadsToSettled == (result = "Cancelled") ~> settled
=============================================================================
