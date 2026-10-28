---------------------- MODULE TryCatchCoroutineFrame ----------------------
\* S4-C 第十八片：带 try/catch/finally 的生成帧（按 2026-10-28 裁决 C）。
\* 抽象自端到端测试函数的骨架 `try { try { await input; ... } catch (FutureFailureError) { H } ...
\*   await gate; return } finally { F }`：
\*   区域 1 = finally 区域（清理 F），区域 2 = catch 区域（父区域 1，无清理语句）；
\*   块 0 进入两个区域（写 `$region` = 2），挂起 input；
\*   块 1 消费 input：成功时段内可能抛出可匹配 / 不可匹配的错误，否则离开 catch 区域（`$region` = 1）
\*     后挂起 gate；失败按 FutureFailureError（可匹配）、取消按 FutureCancelledError（不匹配）抛出，
\*     待发布结果先记为原 Failed / Cancelled；
\*   块 2 是处理块（在区域 1 内）：可能抛出（区域 1 内已无 catch），否则挂起 gate；
\*   块 3 消费 gate：成功时返回值先求出，再执行区域 1 的清理，最后完成结果；失败 / 取消抛出。
\* 段内抛出进入异常路由：按 `$region` 由内到外，finally 区域执行清理（抛出时以新的不可匹配错误
\* 继续，待发布结果改为 Failed），catch 区域先退回外层区域再匹配；命中时清除待发布结果、`phase`
\* 指向处理块并重新进入分派循环（每趟开头检查结果是否已终结）；走完仍未命中则发布待发布结果。
\* 本任务自身的取消（ExternalCancel）不是异常：入口检查 / 挂起前检查发现结果已终结时进入展开，
\* 只执行 finally（catch 区域只退回外层区域）。
\* 变异：RecheckOnReentry = FALSE 时命中后直接执行处理块（不经入口检查），预期违反 OwnCancelInvisible；
\* MatchAll = TRUE 时 catch 匹配任何错误，预期违反 CatchMatchesType。
\* 调度门、回调与送达检查同第十七片模型；抽象掉锁、线程调度与 JVM 内存模型。
EXTENDS Naturals
CONSTANTS CanThrow, RecheckOnReentry, MatchAll
Futures == {"input", "gate"}
Terminal == {"Completed", "Failed", "Cancelled"}
AwaitOf(p) == IF p = 1 THEN "input" ELSE "gate"
Matches(e) == e = "Matched" \/ MatchAll
Throws == IF CanThrow THEN {"None", "Matched", "Unmatched"} ELSE {"None"}

VARIABLES phase, region, entered, runs, pending, err, stage, running, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, early, bad, badHandler, badCatch, fence
vars == <<phase, region, entered, runs, pending, err, stage, running, queued, wake, closed, settled,
          result, resultNotified, input, armed, delivered, outcome, early, bad, badHandler, badCatch, fence>>

Init == /\ phase = 0 /\ region = 0 /\ entered = FALSE /\ runs = 0
        /\ pending = "None" /\ err = "None" /\ stage = "Idle" /\ running = FALSE
        /\ queued = TRUE /\ wake = FALSE /\ closed = FALSE /\ settled = FALSE
        /\ result = "Pending" /\ resultNotified = FALSE
        /\ input = [f \in Futures |-> "Pending"]
        /\ armed = [f \in Futures |-> FALSE] /\ delivered = [f \in Futures |-> FALSE]
        /\ outcome = "None" /\ early = FALSE /\ bad = FALSE /\ badHandler = FALSE /\ badCatch = FALSE
        /\ fence = "Pending"

RequestResume == IF closed THEN UNCHANGED <<queued, wake>>
                 ELSE IF running THEN wake' = TRUE /\ UNCHANGED queued
                 ELSE queued' = TRUE /\ UNCHANGED wake

\* run 结束（finally）：结果已终结且区域为 0 则关闭调度门并 settled，否则合并期间到达的恢复请求。
EndRunAt(res, reg) == /\ running' = FALSE /\ stage' = "Idle" /\ wake' = FALSE
               /\ IF res # "Pending" /\ reg = 0
                  THEN closed' = TRUE /\ settled' = TRUE /\ queued' = FALSE
                  ELSE queued' = (queued \/ wake) /\ UNCHANGED <<closed, settled>>

Cleaned(r) == entered => r = 1

CompleteInput(f) == /\ input[f] = "Pending" /\ \E s \in Terminal : input' = [input EXCEPT ![f] = s]
                    /\ UNCHANGED <<phase, region, entered, runs, pending, err, stage, running, queued, wake, closed,
                                   settled, result, resultNotified, armed, delivered, outcome, early, bad,
                                   badHandler, badCatch, fence>>

ExternalCancel == /\ result = "Pending" /\ result' = "Cancelled"
                  /\ UNCHANGED <<phase, region, entered, runs, pending, err, stage, running, queued, wake, closed,
                                 settled, resultNotified, input, armed, delivered, outcome, early, bad,
                                 badHandler, badCatch, fence>>

InputCallback(f) == /\ armed[f] /\ input[f] \in Terminal
                    /\ armed' = [armed EXCEPT ![f] = FALSE]
                    /\ delivered' = [delivered EXCEPT ![f] = TRUE] /\ outcome' = input[f]
                    /\ RequestResume
                    /\ UNCHANGED <<phase, region, entered, runs, pending, err, stage, running, closed, settled,
                                   result, resultNotified, input, early, bad, badHandler, badCatch, fence>>

ResultCallback == /\ result \in Terminal /\ ~resultNotified
                  /\ resultNotified' = TRUE /\ outcome' = result
                  /\ RequestResume
                  /\ UNCHANGED <<phase, region, entered, runs, pending, err, stage, running, closed, settled,
                                 result, input, armed, delivered, early, bad, badHandler, badCatch, fence>>

Begin == /\ queued /\ ~running /\ ~closed
         /\ running' = TRUE /\ stage' = "Pass" /\ queued' = FALSE
         /\ UNCHANGED <<phase, region, entered, runs, pending, err, wake, closed, settled, result, resultNotified,
                        input, armed, delivered, outcome, early, bad, badHandler, badCatch, fence>>

\* 分派循环每趟开头的终态检查：已终结（本任务自身的取消）则跳出循环进入展开，不抛异常。
PassStart == /\ running /\ stage = "Pass"
             /\ stage' = IF result # "Pending" THEN "Unwind" ELSE "Exec"
             /\ fence' = result
             /\ UNCHANGED <<phase, region, entered, runs, pending, err, running, queued, wake, closed, settled,
                            result, resultNotified, input, armed, delivered, outcome, early, bad,
                            badHandler, badCatch>>

\* 挂起：结果已终结时不注册而进入展开，否则写下一块编号后注册回调并返回。
Suspend(next) ==
    IF result # "Pending"
    THEN stage' = "Unwind" /\ UNCHANGED <<phase, armed>>
    ELSE /\ phase' = next /\ armed' = [armed EXCEPT ![AwaitOf(next)] = TRUE] /\ stage' = "Return"

\* 段内抛出：记待发布结果（挂起点已记的原 Failed / Cancelled 不覆盖）并进入路由。
Raise(kind, publish) == /\ err' = kind /\ pending' = publish /\ stage' = "Route"
                        /\ UNCHANGED <<phase, armed>>

\* 恢复块入口的送达检查（同第十七片模型）。
Consume(f) == /\ bad' = (bad \/ ~delivered[f] \/ (outcome = "Completed" /\ input[f] # "Completed"))
              /\ delivered' = [delivered EXCEPT ![f] = FALSE]

Exec ==
    /\ running /\ stage = "Exec"
    /\ CASE phase = 0 ->
              /\ entered' = TRUE /\ region' = 2
              /\ Suspend(1)
              /\ UNCHANGED <<runs, pending, err, result, delivered, bad, badHandler>>
         [] phase = 1 ->
              /\ Consume("input")
              /\ UNCHANGED <<entered, runs, result, badHandler>>
              /\ CASE outcome = "Failed" -> Raise("Matched", "Failed") /\ UNCHANGED region
                   [] outcome = "Cancelled" -> Raise("Unmatched", "Cancelled") /\ UNCHANGED region
                   [] OTHER -> \E thrown \in Throws :
                        IF thrown # "None"
                        THEN Raise(thrown, "Failed") /\ UNCHANGED region
                        ELSE region' = 1 /\ Suspend(3) /\ UNCHANGED <<pending, err>>
         [] phase = 2 ->
              \* 处理块：进入它之前的最近一次检查点必须看到结果仍未终结（检查点之后才到达的取消
              \* 与段执行中到达的取消一样，到下一个检查点才生效）；在区域 1 内，再抛出的错误已无 catch。
              /\ badHandler' = (badHandler \/ fence # "Pending")
              /\ UNCHANGED <<region, entered, runs, result, delivered, bad>>
              /\ \E thrown \in (IF CanThrow THEN {"None", "Unmatched"} ELSE {"None"}) :
                   IF thrown # "None" THEN Raise(thrown, "Failed")
                   ELSE Suspend(3) /\ UNCHANGED <<pending, err>>
         [] phase = 3 ->
              /\ Consume("gate")
              /\ UNCHANGED <<entered, badHandler>>
              /\ IF outcome \in {"Failed", "Cancelled"}
                 THEN Raise("Unmatched", outcome) /\ UNCHANGED <<region, runs, result>>
                 ELSE \* 返回值已求出；清理区域 1（先退回区域 0），清理可能抛出。
                      /\ region' = 0 /\ runs' = runs + 1
                      /\ \E thrown \in (IF CanThrow THEN BOOLEAN ELSE {FALSE}) :
                           IF thrown THEN Raise("Unmatched", "Failed") /\ UNCHANGED result
                           ELSE /\ result' = IF result = "Pending" THEN "Completed" ELSE result
                                /\ UNCHANGED <<phase, armed, pending, err>>
                                /\ stage' = "Publish"
    /\ early' = (early \/ (result' # result /\ ~Cleaned(runs')))
    /\ UNCHANGED <<running, queued, wake, closed, settled, resultNotified, input, outcome, badCatch, fence>>

\* 异常路由：一步处理一个区域。catch 区域命中时清除待发布结果并转入处理块。
Route == /\ running /\ stage = "Route"
         /\ CASE region = 0 ->
                   stage' = "Publish" /\ UNCHANGED <<phase, region, runs, pending, err, badCatch, fence>>
              [] region = 2 ->
                   /\ region' = 1 /\ UNCHANGED runs
                   /\ IF Matches(err)
                      THEN /\ phase' = 2 /\ pending' = "None" /\ err' = "None"
                           /\ badCatch' = (badCatch \/ err # "Matched")
                           /\ stage' = IF RecheckOnReentry THEN "Pass" ELSE "Exec"
                           /\ fence' = result
                      ELSE stage' = "Route" /\ UNCHANGED <<phase, pending, err, badCatch, fence>>
              [] OTHER ->
                   /\ region' = 0 /\ runs' = runs + 1 /\ stage' = "Route"
                   /\ \E thrown \in (IF CanThrow THEN BOOLEAN ELSE {FALSE}) :
                        /\ pending' = IF thrown THEN "Failed" ELSE pending
                        /\ err' = IF thrown THEN "Unmatched" ELSE err
                   /\ UNCHANGED <<phase, badCatch, fence>>
         /\ UNCHANGED <<entered, running, queued, wake, closed, settled, result, resultNotified,
                        input, armed, delivered, outcome, early, bad, badHandler>>

\* 本任务自身取消后的展开：只执行 finally，catch 区域只退回外层区域。
Unwind == /\ running /\ stage = "Unwind"
          /\ CASE region = 0 -> stage' = "Publish" /\ UNCHANGED <<region, runs, pending>>
               [] region = 2 -> region' = 1 /\ stage' = "Unwind" /\ UNCHANGED <<runs, pending>>
               [] OTHER -> /\ region' = 0 /\ runs' = runs + 1 /\ stage' = "Unwind"
                           /\ \E thrown \in (IF CanThrow THEN BOOLEAN ELSE {FALSE}) :
                                pending' = IF thrown THEN "Failed" ELSE pending
          /\ UNCHANGED <<phase, entered, err, running, queued, wake, closed, settled, result, resultNotified,
                         input, armed, delivered, outcome, early, bad, badHandler, badCatch, fence>>

Publish == /\ running /\ stage = "Publish"
           /\ UNCHANGED <<phase, region, entered, runs, pending, err, resultNotified, input, armed, delivered,
                          outcome, bad, badHandler, badCatch, fence>>
           /\ result' = IF pending # "None" /\ result = "Pending" THEN pending ELSE result
           /\ early' = (early \/ (result' # result /\ ~Cleaned(runs)))
           /\ EndRunAt(result', region)

Return == /\ running /\ stage = "Return" /\ EndRunAt(result, region)
          /\ UNCHANGED <<phase, region, entered, runs, pending, err, result, resultNotified, input, armed, delivered,
                         outcome, early, bad, badHandler, badCatch, fence>>

Next == \/ \E f \in Futures : CompleteInput(f) \/ InputCallback(f)
        \/ ExternalCancel \/ ResultCallback \/ Begin \/ PassStart \/ Exec \/ Route \/ Unwind \/ Publish \/ Return

Fairness == /\ \A f \in Futures : WF_vars(CompleteInput(f)) /\ WF_vars(InputCallback(f))
            /\ WF_vars(ResultCallback) /\ WF_vars(Begin) /\ WF_vars(PassStart)
            /\ WF_vars(Exec) /\ WF_vars(Route) /\ WF_vars(Unwind) /\ WF_vars(Publish) /\ WF_vars(Return)
Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ phase \in 0..3 /\ region \in 0..2 /\ runs \in 0..2
          /\ pending \in {"None", "Failed", "Cancelled"} /\ err \in {"None", "Matched", "Unmatched"}
          /\ stage \in {"Idle", "Pass", "Exec", "Route", "Unwind", "Publish", "Return"}
          /\ result \in {"Pending"} \cup Terminal /\ outcome \in {"None"} \cup Terminal
\* finally 清理至多执行一次（正常离开、路由与展开不重复）。
CleanupAtMostOnce == runs <= 1
\* 帧自己发布的结果在可见时，已进入的 finally 区域已清理。
NoEarlyResult == ~early
\* settled（父作用域可回收）时已进入的 finally 区域已清理。
SettledAfterCleanup == settled => Cleaned(runs)
\* 裁决 C：本任务自身的取消对 catch 不可见——处理块入口检查点之前到达的取消使处理块不执行。
OwnCancelInvisible == ~badHandler
\* catch 只接住匹配捕获类型的错误（被等待 Future 的取消不被 FutureFailureError 处理块接住）。
CatchMatchesType == ~badCatch
\* 被捕获的错误不残留待发布结果：处理块执行时没有待发布结果。
CaughtIsCleared == (running /\ stage = "Exec" /\ phase = 2) => pending = "None"
ResumeAfterDelivery == ~bad
NoOverlap == running => ~queued
SettledIsFinal == settled => (result \in Terminal /\ ~running /\ ~queued /\ closed)

ResultStable == [][(result \in Terminal) => (result' = result)]_result
Terminates == <>settled
CancelLeadsToSettled == (result = "Cancelled") ~> settled
=============================================================================
