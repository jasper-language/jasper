---------------------- MODULE TryFinallyCoroutineFrame ----------------------
\* S4-C 第十七片：带嵌套 try/finally 清理区域的生成帧。
\* 抽象自端到端测试函数 Program.sum 的骨架：
\*   块 0 进入区域 1、区域 2（写 `$region`），在区域 2 内挂起 input；
\*   块 1 消费 input：成功时执行区域 2 的清理（先把 `$region` 退回 1），再在区域 1 内挂起 gate；
\*   块 2 消费 gate：成功时返回值先求出，再执行区域 1 的清理（`$region` 退回 0），最后完成结果。
\* 非正常离开（挂起点失败 / 取消、清理或段内抛出、作用域取消）跳出分派循环进入展开阶段：
\* 按 `$region` 由内到外逐个清理，清理抛出时以 Failed 取代待发布结果并继续展开外层；展开结束
\* 后发布待发布结果（结果已被外部终结时不改写），finally 关闭调度门并标记 settled。
\* 挂起块在注册回调前检查结果是否已被外部终结：终结时同样进入展开阶段。run 的 finally 只在
\* 结果已终结且 `$region` 为 0 时关闭调度门并 settled：注册回调之后、run 返回之前到达的作用域
\* 取消留下的恢复请求由调度门合并，重新运行时展开（SettleNeedsRegion 为 FALSE 时按修复前的
\* 实现只看结果，用于变异）。UnwindAll 为 FALSE 时展开只清理当前区域（按编号升序单趟检查的
\* 错误实现），用于变异。CanThrow 决定清理是否可能抛出。
\* 调度门、回调与送达检查同第十一片模型；抽象掉锁、线程调度与 JVM 内存模型。
EXTENDS Naturals
CONSTANTS CanThrow, UnwindAll, SettleNeedsRegion
Futures == {"input", "gate"}
Terminal == {"Completed", "Failed", "Cancelled"}
Regions == {1, 2}
Parent(r) == r - 1
AwaitOf(p) == IF p = 1 THEN "input" ELSE "gate"

VARIABLES phase, region, entered, runs, pending, stage, running, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, early, bad
vars == <<phase, region, entered, runs, pending, stage, running, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, early, bad>>

Init == /\ phase = 0 /\ region = 0 /\ entered = [r \in Regions |-> FALSE] /\ runs = [r \in Regions |-> 0]
        /\ pending = "None" /\ stage = "Idle" /\ running = FALSE
        /\ queued = TRUE /\ wake = FALSE /\ closed = FALSE /\ settled = FALSE
        /\ result = "Pending" /\ resultNotified = FALSE
        /\ input = [f \in Futures |-> "Pending"]
        /\ armed = [f \in Futures |-> FALSE] /\ delivered = [f \in Futures |-> FALSE]
        /\ outcome = "None" /\ early = FALSE /\ bad = FALSE

RequestResume == IF closed THEN UNCHANGED <<queued, wake>>
                 ELSE IF running THEN wake' = TRUE /\ UNCHANGED queued
                 ELSE queued' = TRUE /\ UNCHANGED wake

\* run 结束（finally）：结果已终结且（SettleNeedsRegion 时）区域为 0 则关闭调度门并 settled，
\* 否则合并期间到达的恢复请求。[reg] 是 run 结束时的区域局部。
EndRunAt(res, reg) == /\ running' = FALSE /\ stage' = "Idle" /\ wake' = FALSE
               /\ IF res # "Pending" /\ (reg = 0 \/ ~SettleNeedsRegion)
                  THEN closed' = TRUE /\ settled' = TRUE /\ queued' = FALSE
                  ELSE queued' = (queued \/ wake) /\ UNCHANGED <<closed, settled>>

EndRun(res) == EndRunAt(res, region')

AllCleaned(rs) == \A r \in Regions : entered'[r] => rs[r] = 1

CompleteInput(f) == /\ input[f] = "Pending" /\ \E s \in Terminal : input' = [input EXCEPT ![f] = s]
                    /\ UNCHANGED <<phase, region, entered, runs, pending, stage, running, queued, wake, closed,
                                   settled, result, resultNotified, armed, delivered, outcome, early, bad>>

ExternalCancel == /\ result = "Pending" /\ result' = "Cancelled"
                  /\ UNCHANGED <<phase, region, entered, runs, pending, stage, running, queued, wake, closed,
                                 settled, resultNotified, input, armed, delivered, outcome, early, bad>>

InputCallback(f) == /\ armed[f] /\ input[f] \in Terminal
                    /\ armed' = [armed EXCEPT ![f] = FALSE]
                    /\ delivered' = [delivered EXCEPT ![f] = TRUE] /\ outcome' = input[f]
                    /\ RequestResume
                    /\ UNCHANGED <<phase, region, entered, runs, pending, stage, running, closed, settled,
                                   result, resultNotified, input, early, bad>>

ResultCallback == /\ result \in Terminal /\ ~resultNotified
                  /\ resultNotified' = TRUE /\ outcome' = result
                  /\ RequestResume
                  /\ UNCHANGED <<phase, region, entered, runs, pending, stage, running, closed, settled,
                                 result, input, armed, delivered, early, bad>>

Begin == /\ queued /\ ~running /\ ~closed
         /\ running' = TRUE /\ stage' = "Pass" /\ queued' = FALSE
         /\ UNCHANGED <<phase, region, entered, runs, pending, wake, closed, settled, result, resultNotified,
                        input, armed, delivered, outcome, early, bad>>

\* 分派循环每趟开头的终态检查：已终结则跳出循环进入展开。
PassStart == /\ running /\ stage = "Pass"
             /\ stage' = IF result # "Pending" THEN "Unwind" ELSE "Exec"
             /\ UNCHANGED <<phase, region, entered, runs, pending, running, queued, wake, closed, settled,
                            result, resultNotified, input, armed, delivered, outcome, early, bad>>

\* 挂起：结果已终结时不注册（展开或按变异直接返回），否则写下一块编号后注册回调并返回。
Suspend(next) ==
    IF result # "Pending"
    THEN stage' = "Unwind" /\ UNCHANGED <<phase, armed, running, queued, wake, closed, settled>>
    ELSE /\ phase' = next /\ armed' = [armed EXCEPT ![AwaitOf(next)] = TRUE] /\ stage' = "Return"
         /\ UNCHANGED <<running, queued, wake, closed, settled>>

\* 正常路径上的一次区域清理；抛出时记 Failed 并进入展开（区域局部已退回外层）。
Exec ==
    /\ running /\ stage = "Exec"
    /\ CASE phase = 0 ->
              /\ entered' = [r \in Regions |-> TRUE] /\ region' = 2
              /\ Suspend(1)
              /\ UNCHANGED <<runs, pending, result, delivered, bad>>
         [] phase \in {1, 2} ->
              LET f == AwaitOf(phase) IN
              /\ bad' = (bad \/ ~delivered[f] \/ (outcome = "Completed" /\ input[f] # "Completed"))
              /\ delivered' = [delivered EXCEPT ![f] = FALSE]
              /\ UNCHANGED entered
              /\ IF outcome \in {"Failed", "Cancelled"}
                 THEN /\ pending' = outcome /\ stage' = "Unwind"
                      /\ UNCHANGED <<phase, region, runs, result, armed, running, queued, wake, closed, settled>>
                 ELSE /\ region' = 2 - phase /\ runs' = [runs EXCEPT ![3 - phase] = @ + 1]
                      /\ \E thrown \in (IF CanThrow THEN BOOLEAN ELSE {FALSE}) :
                           IF thrown
                           THEN /\ pending' = "Failed" /\ stage' = "Unwind"
                                /\ UNCHANGED <<phase, result, armed, running, queued, wake, closed, settled>>
                           ELSE IF phase = 1
                                THEN Suspend(2) /\ UNCHANGED <<pending, result>>
                                ELSE /\ result' = IF result = "Pending" THEN "Completed" ELSE result
                                     /\ EndRun(result') /\ UNCHANGED <<phase, pending, armed>>
    /\ early' = (early \/ (result' # result /\ ~AllCleaned(runs')))
    /\ UNCHANGED <<resultNotified, input, outcome>>

\* 展开：由内到外逐个清理（变异：只清理当前区域）；清理抛出以 Failed 取代待发布结果。
Unwind == /\ running /\ stage = "Unwind"
          /\ IF region = 0
             THEN stage' = "Publish" /\ UNCHANGED <<region, runs, pending>>
             ELSE /\ region' = Parent(region) /\ runs' = [runs EXCEPT ![region] = @ + 1]
                  /\ \E thrown \in (IF CanThrow THEN BOOLEAN ELSE {FALSE}) :
                        pending' = IF thrown THEN "Failed" ELSE pending
                  /\ stage' = IF UnwindAll THEN "Unwind" ELSE "Publish"
          /\ UNCHANGED <<phase, entered, running, queued, wake, closed, settled, result, resultNotified,
                         input, armed, delivered, outcome, early, bad>>

Publish == /\ running /\ stage = "Publish"
           /\ UNCHANGED <<phase, region, entered, runs, pending, resultNotified, input, armed, delivered, outcome, bad>>
           /\ result' = IF pending # "None" /\ result = "Pending" THEN pending ELSE result
           /\ early' = (early \/ (result' # result /\ ~AllCleaned(runs)))
           /\ EndRun(result')

Return == /\ running /\ stage = "Return" /\ EndRunAt(result, region)
          /\ UNCHANGED <<phase, region, entered, runs, pending, result, resultNotified, input, armed, delivered,
                         outcome, early, bad>>

Next == \/ \E f \in Futures : CompleteInput(f) \/ InputCallback(f)
        \/ ExternalCancel \/ ResultCallback \/ Begin \/ PassStart \/ Exec \/ Unwind \/ Publish \/ Return

Fairness == /\ \A f \in Futures : WF_vars(CompleteInput(f)) /\ WF_vars(InputCallback(f))
            /\ WF_vars(ResultCallback) /\ WF_vars(Begin) /\ WF_vars(PassStart)
            /\ WF_vars(Exec) /\ WF_vars(Unwind) /\ WF_vars(Publish) /\ WF_vars(Return)
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ phase \in 0..2 /\ region \in 0..2 /\ runs \in [Regions -> 0..2]
          /\ pending \in {"None", "Failed", "Cancelled"}
          /\ stage \in {"Idle", "Pass", "Exec", "Unwind", "Publish", "Return"}
          /\ result \in {"Pending"} \cup Terminal /\ outcome \in {"None"} \cup Terminal
\* 每个区域的清理至多执行一次（正常离开与展开不重复）。
CleanupAtMostOnce == \A r \in Regions : runs[r] <= 1
\* 外层区域清理时，已进入的内层区域必已清理（由内到外）。
InnerBeforeOuter == runs[1] = 1 => (entered[2] => runs[2] = 1)
\* 帧自己发布的结果在可见时，所有已进入区域都已清理。
NoEarlyResult == ~early
\* settled（父作用域可回收）时所有已进入区域都已清理。
SettledAfterCleanup == settled => \A r \in Regions : entered[r] => runs[r] = 1
ResumeAfterDelivery == ~bad
NoOverlap == running => ~queued
SettledIsFinal == settled => (result \in Terminal /\ ~running /\ ~queued /\ closed)

ResultStable == [][(result \in Terminal) => (result' = result)]_result
Terminates == <>settled
CancelLeadsToSettled == (result = "Cancelled") ~> settled
=============================================================================
