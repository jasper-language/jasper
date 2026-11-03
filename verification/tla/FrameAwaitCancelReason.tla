---- MODULE FrameAwaitCancelReason ----
\* S4-C 帧因被等待 Future 取消而发布 Cancelled 时的原因（LANG-S4-AWAIT-REASON-01）。
\* 两个帧成链：帧 1 等待外部输入，帧 2 等待帧 1 的结果；作用域可能并发取消（Relay 把根原因写入未
\* 终结的结果，不一定及时送达）。帧恢复时：入口检查点已看到根原因或结果已终结 → 不消费快照，只展开清理；
\* 否则快照为 Cancelled → 记下快照原因，展开清理后以该原因发布。结果终态互斥，先写者决定原因。
\* FixedExplicit = TRUE 是变异配置：发布固定写 Explicit（本片之前的行为），应违反 ReasonHasSource。
EXTENDS Naturals

CONSTANTS FixedExplicit

Frames == {1, 2}
None == "None"
InputReasons == {"Deadline", "SiblingFailed"}
ScopeReason == "ScopeExit"

VARIABLES input, root, result, pending, pc, cleaned

vars == <<input, root, result, pending, pc, cleaned>>

Awaited(f) == IF f = 1 THEN input ELSE result[1]

Init == /\ input = None
        /\ root = None
        /\ result = [f \in Frames |-> None]
        /\ pending = [f \in Frames |-> None]
        /\ pc = [f \in Frames |-> "suspended"]
        /\ cleaned = [f \in Frames |-> 0]

InputCancel(r) == /\ input = None
                  /\ input' = r
                  /\ UNCHANGED <<root, result, pending, pc, cleaned>>

ScopeCancel == /\ root = None
               /\ root' = ScopeReason
               /\ UNCHANGED <<input, result, pending, pc, cleaned>>

\* ScopeCancellationRelay：把根原因写入尚未终结的结果；首因由终态互斥保证。
Relay(f) == /\ root # None
            /\ result[f] = None
            /\ result' = [result EXCEPT ![f] = root]
            /\ UNCHANGED <<input, root, pending, pc, cleaned>>

\* 一趟恢复：被等待者已终结或结果已被取消才会被调度。
Resume(f) ==
    /\ pc[f] = "suspended"
    /\ Awaited(f) # None \/ result[f] # None \/ root # None
    /\ pc' = [pc EXCEPT ![f] = "unwinding"]
    /\ IF root # None /\ result[f] = None
       THEN /\ result' = [result EXCEPT ![f] = root]
            /\ UNCHANGED pending
       ELSE /\ pending' = IF result[f] = None
                          THEN [pending EXCEPT ![f] = Awaited(f)]
                          ELSE pending
            /\ UNCHANGED result
    /\ UNCHANGED <<input, root, cleaned>>

\* 展开清理恰好一次，之后才发布待发布的 Cancelled。
Unwind(f) == /\ pc[f] = "unwinding"
             /\ cleaned' = [cleaned EXCEPT ![f] = @ + 1]
             /\ pc' = [pc EXCEPT ![f] = "publishing"]
             /\ UNCHANGED <<input, root, result, pending>>

Publish(f) ==
    /\ pc[f] = "publishing"
    /\ pc' = [pc EXCEPT ![f] = "settled"]
    /\ result' = IF result[f] = None
                 THEN [result EXCEPT ![f] = IF FixedExplicit THEN "Explicit" ELSE pending[f]]
                 ELSE result
    /\ UNCHANGED <<input, root, pending, cleaned>>

Next == \/ \E r \in InputReasons: InputCancel(r)
        \/ ScopeCancel
        \/ \E f \in Frames: Relay(f) \/ Resume(f) \/ Unwind(f) \/ Publish(f)

Spec == /\ Init /\ [][Next]_vars
        /\ \A f \in Frames: WF_vars(Resume(f)) /\ WF_vars(Unwind(f)) /\ WF_vars(Publish(f))

\* 结果的原因只能来自根令牌或链路源头的输入，不会凭空变成 Explicit。
ReasonHasSource == \A f \in Frames: result[f] \in {None, root, input}

\* 待发布原因就是被等待者的首个原因（被等待者终态不再改变）。
PendingIsAwaited == \A f \in Frames: pending[f] # None => pending[f] = Awaited(f)

\* 帧自己发布之前清理已经执行；清理最多一次。
CleanupOnce == \A f \in Frames: cleaned[f] <= 1
SettledAfterCleanup == \A f \in Frames: pc[f] = "settled" => cleaned[f] = 1 /\ result[f] # None

FirstReasonStable == [][\A f \in Frames: result[f] # None => result'[f] = result[f]]_vars

\* 输入被取消后整条链最终回收，不会有帧永远挂起。
ChainSettles == (input # None) ~> (\A f \in Frames: pc[f] = "settled")
====
