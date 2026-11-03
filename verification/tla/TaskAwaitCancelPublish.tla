---- MODULE TaskAwaitCancelPublish ----
\* 任务体未捕获被等待 Future 的取消时的结果（LANG-S4-AWAIT-CANCEL-PUBLISH-01）。
\* 普通组：任务体等待输入，一个兄弟任务在运行；作用域可能并发取消。输入被取消后任务体可以捕获（随后
\* 正常返回）或不捕获（取消错误穿过清理）；清理可能抛出业务错误，取代传播中的取消。发布时取消错误走
\* Cancelled（Parent 换成令牌首因，其余原样），业务错误走 Failed 并级联取消兄弟。结果终态互斥，先写者决定。
\* AsFailure = TRUE 是变异配置：取消错误发布成 Failed（本裁决之前的行为），应违反 AwaitCancelNoCascade。
\* NoParentMap = TRUE 是变异配置：Parent 原样发布，应违反 ReasonMapped。
EXTENDS Naturals

CONSTANTS AsFailure, NoParentMap

None == "None"
InputReasons == {"Deadline", "Parent"}
ScopeReason == "Explicit"

VARIABLES input, token, result, sibling, pc, thrown, cleaned, seen

vars == <<input, token, result, sibling, pc, thrown, cleaned, seen>>

Init == /\ input = None
        /\ token = None
        /\ result = None
        /\ sibling = "running"
        /\ pc = "blocked"
        /\ thrown = None
        /\ cleaned = 0
        /\ seen = None  \* 发布那一刻令牌的首因；作用域在发布之后取消不影响已发布的结果

InputCancel(r) == /\ input = None
                  /\ input' = r
                  /\ UNCHANGED <<token, result, sibling, pc, thrown, cleaned, seen>>

\* 作用域取消：根令牌写首因，兄弟停下；写入本任务结果是单独一步，两者之间任务体可以先发布。
ScopeCancel == /\ token = None
               /\ token' = ScopeReason
               /\ sibling' = IF sibling = "running" THEN "scope" ELSE sibling
               /\ UNCHANGED <<input, result, pc, thrown, cleaned, seen>>

Relay == /\ token # None
         /\ result = None
         /\ result' = token
         /\ UNCHANGED <<input, token, sibling, pc, thrown, cleaned, seen>>

Catch == /\ pc = "blocked"
         /\ input # None
         /\ pc' = "caught"
         /\ UNCHANGED <<input, token, result, sibling, thrown, cleaned, seen>>

Uncaught == /\ pc = "blocked"
            /\ input # None
            /\ pc' = "cleanup"
            /\ thrown' = "cancel"
            /\ UNCHANGED <<input, token, result, sibling, cleaned, seen>>

\* 清理恰好一次；可能抛出业务错误，取代传播中的取消。
Cleanup(fails) == /\ pc = "cleanup"
                  /\ pc' = "publishing"
                  /\ cleaned' = cleaned + 1
                  /\ thrown' = IF fails THEN "error" ELSE thrown
                  /\ UNCHANGED <<input, token, result, sibling, seen>>

Mapped(r) == IF r = "Parent" /\ ~NoParentMap /\ token # None THEN token ELSE r

\* 业务失败发布后才级联；本任务结果已被作用域取消时不级联（TaskRunner 按结果状态判定）。
Fail == /\ result' = IF result = None THEN "Failed" ELSE result
        /\ IF result' = "Failed"
           THEN /\ sibling' = IF sibling = "running" THEN "cascade" ELSE sibling
                /\ token' = IF token = None THEN "SiblingFailed" ELSE token
           ELSE UNCHANGED <<sibling, token>>

Publish == /\ pc = "publishing"
           /\ pc' = "settled"
           /\ seen' = token
           /\ IF thrown = "error" \/ AsFailure
              THEN Fail
              ELSE /\ result' = IF result = None THEN Mapped(input) ELSE result
                   /\ UNCHANGED <<sibling, token>>
           /\ UNCHANGED <<input, thrown, cleaned>>

\* 捕获后正常返回：令牌未取消时完成；令牌已取消时迟到结果不算成功。
Return == /\ pc = "caught"
          /\ pc' = "settled"
          /\ result' = IF result # None THEN result
                       ELSE IF token # None THEN token ELSE "Completed"
          /\ UNCHANGED <<input, token, sibling, thrown, cleaned, seen>>

Next == \/ \E r \in InputReasons: InputCancel(r)
        \/ ScopeCancel \/ Relay
        \/ Catch \/ Uncaught
        \/ \E fails \in BOOLEAN: Cleanup(fails)
        \/ Publish \/ Return

Spec == /\ Init /\ [][Next]_vars
        /\ WF_vars(Catch \/ Uncaught)
        /\ WF_vars(\E fails \in BOOLEAN: Cleanup(fails))
        /\ WF_vars(Publish) /\ WF_vars(Return)

\* 未捕获的取消不级联：只有业务错误或作用域取消才会让兄弟停下。
AwaitCancelNoCascade == sibling = "cascade" => thrown = "error"

\* 未捕获的取消发布被等待 Future 的首因，Parent 换成令牌首因；作用域取消在先时结果已是根原因。
ReasonMapped == /\ (pc = "settled" /\ thrown = "cancel" /\ seen = None) => result = input
                /\ (pc = "settled" /\ thrown = "cancel" /\ input = "Parent" /\ seen # None) => result = seen

\* 取消错误的路径不写 Failed；清理抛出的业务错误不被吞掉（作用域取消已先终结结果时除外）。
CancelIsNotFailure == (pc = "settled" /\ thrown = "cancel") => result # "Failed"
CleanupErrorSurfaces == (pc = "settled" /\ thrown = "error" /\ result # "Failed") => result = ScopeReason

\* 捕获之后任务可以正常完成，取消不是不可捕获的强制终止。
CaughtCompletes == (pc = "settled" /\ thrown = None /\ token = None) => result = "Completed"

CleanupOnce == cleaned <= 1
ResultStable == [][result # None => result' = result]_vars
InputSettles == (input # None) ~> (pc = "settled")
====
