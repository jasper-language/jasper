------------------------- MODULE CancellationReasonPropagation -------------------------
\* 取消原因的首因稳定与作用域内传播（对应 CancellationToken.cancelWith / ChildCancellation、
\* Future.cancelWith / finish、TaskGroup.cancelBecause / launch / close、TaskRunner.run）。
\* 两个作用域取消请求并发：显式取消（Explicit）与普通组兄弟失败级联（SiblingFailed）；每个请求
\* 先取消根令牌（原子：锁内首个置位者写原因），再逐个取消子任务结果 Future。子令牌由根令牌回调
\* 传播；任务体观察到令牌取消后以令牌原因发布结果；调用方可单独以任意原因取消某个子 Future。
\* UseRootReason = FALSE 是变异：请求取消子 Future 时用自己的原因而不是根令牌的首个原因。
\* 不建模：回调抛错（CallbackDispatchError 另有模型）、阻塞、业务失败值、JVM 内存模型。
EXTENDS Naturals, FiniteSets
CONSTANTS Children, UseRootReason
None == "none"
Reasons == {"Explicit", "SiblingFailed", "Deadline"}
Requests == {"explicit", "sibling"}
ReqReason == [r \in Requests |-> IF r = "explicit" THEN "Explicit" ELSE "SiblingFailed"]
VARIABLES root, token, future, individual, pc, todo, closed, closeReason, closeRoot
vars == <<root, token, future, individual, pc, todo, closed, closeReason, closeRoot>>

Init == /\ root = None
        /\ token = [c \in Children |-> None]
        /\ future = [c \in Children |-> None]
        /\ individual = [c \in Children |-> FALSE]
        /\ pc = [r \in Requests |-> "idle"]
        /\ todo = [r \in Requests |-> Children]
        /\ closed = FALSE
        /\ closeReason = None
        /\ closeRoot = None

\* 请求开始：根令牌 cancelWith，只有首个置位者写原因。
RootCancel(r) == /\ pc[r] = "idle"
                 /\ root' = IF root = None THEN ReqReason[r] ELSE root
                 /\ pc' = [pc EXCEPT ![r] = "children"]
                 /\ UNCHANGED <<token, future, individual, todo, closed, closeReason, closeRoot>>

\* 请求逐个取消子 Future：Future.finish 只在 Pending 时写终态与原因。
ChildCancel(r) == /\ pc[r] = "children"
                  /\ \E c \in todo[r]:
                       LET reason == IF UseRootReason THEN root ELSE ReqReason[r]
                       IN /\ future' = IF future[c] = None THEN [future EXCEPT ![c] = reason] ELSE future
                          /\ todo' = [todo EXCEPT ![r] = @ \ {c}]
                  /\ pc' = IF Cardinality(todo[r]) = 1 THEN [pc EXCEPT ![r] = "done"] ELSE pc
                  /\ UNCHANGED <<root, token, individual, closed, closeReason, closeRoot>>

\* 根令牌回调把原因传给子令牌（ChildCancellation：reasonOr 读根的首个原因）。
TokenPropagate(c) == /\ root # None
                     /\ token[c] = None
                     /\ token' = [token EXCEPT ![c] = root]
                     /\ UNCHANGED <<root, future, individual, pc, todo, closed, closeReason, closeRoot>>

\* 任务体在检查点观察到令牌取消，以令牌原因发布结果（TaskRunner.run）。
BodyObserves(c) == /\ token[c] # None
                   /\ future[c] = None
                   /\ future' = [future EXCEPT ![c] = token[c]]
                   /\ UNCHANGED <<root, token, individual, pc, todo, closed, closeReason, closeRoot>>

\* 调用方单独取消某个子 Future（不经过令牌）。
Individual(c, x) == /\ future[c] = None
                    /\ future' = [future EXCEPT ![c] = x]
                    /\ individual' = [individual EXCEPT ![c] = TRUE]
                    /\ UNCHANGED <<root, token, pc, todo, closed, closeReason, closeRoot>>

\* close 快照（简化为全部请求结束后一次性聚合；无业务失败）：根已取消取根原因，否则按创建序
\* 取首个已取消子任务的原因。Children 为 1..N，创建序即数字序。
FirstCancelled == {c \in Children: future[c] # None}
Close == /\ ~closed
         /\ \A r \in Requests: pc[r] \in {"idle", "done"}
         /\ closed' = TRUE
         /\ closeReason' = IF root # None THEN root
                           ELSE IF FirstCancelled = {} THEN None
                           ELSE future[CHOOSE c \in FirstCancelled: \A d \in FirstCancelled: c <= d]
         /\ closeRoot' = root
         /\ UNCHANGED <<root, token, future, individual, pc, todo>>

Next == \/ \E r \in Requests: RootCancel(r) \/ ChildCancel(r)
        \/ \E c \in Children: TokenPropagate(c) \/ BodyObserves(c) \/ \E x \in Reasons: Individual(c, x)
        \/ Close
Spec == Init /\ [][Next]_vars /\ WF_vars(\E r \in Requests: ChildCancel(r)) /\ \A c \in Children: WF_vars(TokenPropagate(c))

TypeOK == /\ root \in Reasons \cup {None}
          /\ token \in [Children -> Reasons \cup {None}]
          /\ future \in [Children -> Reasons \cup {None}]
          /\ closeReason \in Reasons \cup {None}
          /\ closeRoot \in Reasons \cup {None}
\* 作用域只有一个原因：子令牌一律等于根的首个原因。
TokenAgreesWithRoot == \A c \in Children: token[c] # None => token[c] = root
\* 未被单独取消的子任务，其结果原因等于根的首个原因（不论哪个请求先写到它）。
FutureAgreesWithRoot == \A c \in Children: (future[c] # None /\ ~individual[c]) => future[c] = root
\* 同一子任务的令牌与结果不报告两个原因（单独取消的除外）。
TokenFutureAgree == \A c \in Children: (token[c] # None /\ future[c] # None /\ ~individual[c]) => token[c] = future[c]
\* close 报告的原因：快照时根已取消则等于当时的根原因（聚合在首次 close 冻结，之后的取消不改写）；
\* 根原因首因稳定，因此也等于之后任意时刻的根原因。
CloseReasonMatches == closed => (/\ closeRoot # None => closeReason = closeRoot
                                 /\ closeRoot # None => root = closeRoot)
\* 首因稳定：原因一旦写入不再改变（动作性质）。
FirstReasonStable == [][/\ root # None => root' = root
                        /\ \A c \in Children: future[c] # None => future'[c] = future[c]
                        /\ \A c \in Children: token[c] # None => token'[c] = token[c]]_vars
\* 活性：任一请求开始后，所有子令牌终将取消、请求终将完成。
ScopeCancelReachesAll == (root # None) ~> (\A c \in Children: token[c] # None)
=============================================================================
