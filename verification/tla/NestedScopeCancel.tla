------------------------------ MODULE NestedScopeCancel ------------------------------
\* 嵌套作用域的取消链接（对应 CancellationToken.createScopeChild / inheritedReason / detachFromParent、
\* ChildCancellation.accept、pollReasonAt 的帮助传播、TaskGroup.within / close、TaskRunner.run）。
\* 令牌链 R →(同作用域) T →(跨作用域) N →(同作用域) C：R 是外层根，T 是外层任务令牌，N 是内层根，
\* C 是内层子任务令牌。传播是独立步骤（回调可能还在别的线程里），帮助传播写同一个换算结果。
\* 外层任务体：等内层子任务结束 → 内层 close（聚合时解除链接）→ 发布外层任务的 Future。
\* 变异：MapReason = FALSE（跨作用域不换算）；HelpMaps = FALSE（只有帮助传播不换算）；
\*       TokenFirst = FALSE（TaskRunner 不换算，把内层的 Parent 发布给外层任务）；
\*       Detach = FALSE（内层 close 后仍留在父令牌上）。
\* 不建模：计时器与时钟（DeadlinePropagation / DeadlineTimer 另有模型，这里 Deadline 只是一个取消源）、
\*        回调抛错、业务失败聚合（TaskGroupAggregation）、多个子任务。
EXTENDS Integers
CONSTANTS MapReason, HelpMaps, TokenFirst, Detach
None == "none"
Tokens == {"R", "T", "N", "C"}
Sources == {"Explicit", "Deadline", "SiblingFailed", "ScopeExit"}
Reasons == Sources \cup {"Parent"}
VARIABLES reason, own, linked, innerRan, childOutcome, closePc, closeReport, future, outerPc, outerReport
vars == <<reason, own, linked, innerRan, childOutcome, closePc, closeReport, future, outerPc, outerReport>>

Parent(t) == CASE t = "T" -> "R" [] t = "N" -> "T" [] t = "C" -> "N"
Crossing(t) == t = "N"
Inherit(t, r, map) == IF Crossing(t) /\ map /\ r # "Deadline" THEN "Parent" ELSE r
First(t, r) == IF reason[t] = None THEN [reason EXCEPT ![t] = r] ELSE reason
Live(t) == t # "N" \/ linked

Init == /\ reason = [t \in Tokens |-> None]
        /\ own = [t \in Tokens |-> FALSE]
        /\ linked = TRUE
        /\ innerRan = FALSE
        /\ childOutcome = None
        /\ closePc = "open"
        /\ closeReport = None
        /\ future = None
        /\ outerPc = "open"
        /\ outerReport = None

\* 任一取消源直接作用在某个令牌上（显式取消、到期、级联、退出）；来源不含 Parent。
\* C 上的取消是内层子任务被单独取消；N、T、R 对应内层作用域、外层任务、外层作用域。
Source(t, r) == /\ reason[t] = None
                /\ reason' = First(t, r)
                /\ own' = [own EXCEPT ![t] = TRUE]
                /\ UNCHANGED <<linked, innerRan, childOutcome, closePc, closeReport, future, outerPc, outerReport>>

\* ChildCancellation.accept：父已置位后的回调；解除链接后 N 不再收到。
Propagate(t) == /\ reason[Parent(t)] # None
                /\ reason[t] = None
                /\ Live(t)
                /\ reason' = First(t, Inherit(t, reason[Parent(t)], MapReason))
                /\ UNCHANGED <<own, linked, innerRan, childOutcome, closePc, closeReport, future, outerPc, outerReport>>

\* pollReasonAt 的帮助传播：检查点发现祖先已取消，不等回调。作用域结束后不再有检查点。
Help(t) == /\ reason[Parent(t)] # None
           /\ reason[t] = None
           /\ closePc = "open"
           /\ reason' = First(t, Inherit(t, reason[Parent(t)], MapReason /\ HelpMaps))
           /\ UNCHANGED <<own, linked, innerRan, childOutcome, closePc, closeReport, future, outerPc, outerReport>>

\* 内层子任务体结束（已观察取消或正常返回）。
InnerRun == /\ ~innerRan
            /\ innerRan' = TRUE
            /\ childOutcome' = reason["C"]
            /\ UNCHANGED <<reason, own, linked, closePc, closeReport, future, outerPc, outerReport>>

\* 内层 close：等子任务结束，聚合时解除链接；N 已取消则报告 N 的首因，否则报单独取消的子任务的原因。
InnerClose == /\ closePc = "open"
              /\ innerRan
              /\ closePc' = "closed"
              /\ linked' = IF Detach THEN FALSE ELSE linked
              /\ closeReport' = IF reason["N"] # None THEN reason["N"] ELSE childOutcome
              /\ UNCHANGED <<reason, own, innerRan, childOutcome, future, outerPc, outerReport>>

\* 外层 TaskRunner：内层报告取消时走 catch（Parent 换成令牌首因，其他原因是内层真实来源），否则看令牌；Future 只写一次。
Publish == /\ closePc = "closed"
           /\ future = None
           /\ LET thrown == closeReport
                  fromToken == reason["T"]
              IN future' = IF thrown # None
                           THEN (IF TokenFirst /\ thrown = "Parent" /\ fromToken # None THEN fromToken ELSE thrown)
                           ELSE (IF fromToken # None THEN fromToken ELSE "Done")
           /\ UNCHANGED <<reason, own, linked, innerRan, childOutcome, closePc, closeReport, outerPc, outerReport>>

\* 外层 close：等任务结束；根已取消报根因，否则报任务 Future 的取消原因。
OuterClose == /\ outerPc = "open"
              /\ future # None
              /\ outerPc' = "closed"
              /\ outerReport' = IF reason["R"] # None THEN reason["R"]
                                ELSE IF future # "Done" THEN future ELSE "ok"
              /\ UNCHANGED <<reason, own, linked, innerRan, childOutcome, closePc, closeReport, future>>

Next == \/ \E t \in Tokens, r \in Sources: Source(t, r)
        \/ \E t \in {"T", "N", "C"}: Propagate(t) \/ Help(t)
        \/ InnerRun \/ InnerClose \/ Publish \/ OuterClose

Fair == /\ \A t \in {"T", "N", "C"}: WF_vars(Propagate(t))
        /\ WF_vars(InnerRun) /\ WF_vars(InnerClose) /\ WF_vars(Publish) /\ WF_vars(OuterClose)
Spec == Init /\ [][Next]_vars /\ Fair

TypeOK == /\ reason \in [Tokens -> Reasons \cup {None}]
          /\ future \in Reasons \cup {None, "Done"}

\* 没有自身取消源时，N 的原因必是 T 的原因换算：Deadline 保留，其余为 Parent；C、T 沿用父的原因。
CrossingMaps == (reason["N"] # None /\ ~own["N"]) =>
                    reason["N"] = (IF reason["T"] = "Deadline" THEN "Deadline" ELSE "Parent")
SameScopeKeeps == /\ (reason["C"] # None /\ ~own["C"]) => reason["C"] = reason["N"]
                  /\ (reason["T"] # None /\ ~own["T"]) => reason["T"] = reason["R"]
\* 取消不向上：T 只因自身来源或 R 被取消，R 只因自身来源被取消（内层的取消碰不到外层）。
NoUpward == /\ reason["T"] # None => (own["T"] \/ reason["R"] # None)
            /\ reason["R"] # None => own["R"]
            /\ reason["N"] # None => (own["N"] \/ reason["T"] # None)
\* 外层任务的结果不会是 Parent：外层之上没有作用域，内层报告的 Parent 只是同一次取消的回声。
OuterNeverParent == future # "Parent" /\ outerReport # "Parent"
\* 内层报告 Parent 或没有报告取消时，已取消任务的结果就是令牌的首因。
TokenReasonWins == [][(future = None /\ future' # None /\ reason["T"] # None /\ closeReport \in {None, "Parent"})
                          => future' = reason["T"]]_vars
\* 内层 close 之后链接已解除：外层不再持有已结束的作用域。
ClosedIsDetached == closePc = "closed" => ~linked
FirstReasonStable == [][\A t \in Tokens: reason[t] # None => reason'[t] = reason[t]]_vars
\* 活性：链接存在时外层取消终将到达内层；外层 close 终将返回。
CancelReachesInner == (reason["T"] # None /\ linked) ~> (reason["N"] # None \/ ~linked)
OuterTerminates == <>(outerPc = "closed")
=============================================================================
