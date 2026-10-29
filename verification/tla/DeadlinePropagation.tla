------------------------------ MODULE DeadlinePropagation ------------------------------
\* 截止点沿令牌链的收紧、检查点到期取消与原因传播（对应 Deadline.earliest、
\* CancellationToken.restrictDeadline / deadline / pollReasonAt / settleWith / cancelWith / ChildCancellation）。
\* 令牌链 1 <- 2 <- 3（1 为作用域根令牌）。每个令牌有自身截止点 own（Inf 表示无），有效截止点
\* 为祖先链上的最小值。并发参与者：时钟前进、任意令牌收紧截止点、对根的显式取消、每个令牌
\* 一个检查点进程（Pollers 中的令牌各一个），以及根到子的取消回调传播（与发起取消的线程异步）。
\* 检查点与实现同构（pollReasonAt 的递归）：下行阶段从本令牌到根，每层一次原子快照（自身截止点、
\* 是否已取消及原因）；上行阶段从根往下逐层决定：快照时已取消的层结果即其首个原因、不再动作；
\* 否则父结果非空时 Help 为真则以父的首个原因取消本层（settleWith，锁内首个置位者生效），否则按快照
\* 截止点判到期并以 Deadline 取消；每层结果是本层最终首个原因（未取消为 none）。
\* Help = FALSE 是变异：检查点不帮助传播，只靠回调；预期违反 PollSeesCancel 或 PollSeesExpiry。
\* 不建模：回调抛错（CallbackDispatchError 另有模型）、nanoTime 回绕（差值比较由属性测试覆盖）。
EXTENDS Naturals, Sequences
CONSTANTS Help, MaxTime, Pollers
Nodes == {1, 2, 3}
Parent == [n \in Nodes |-> IF n = 1 THEN 0 ELSE n - 1]
Path == [n \in Nodes |-> [k \in 1..n |-> k]]        \* 根到 n 的路径
Inf == MaxTime + 1
None == "none"
Reasons == {"Explicit", "Deadline"}
Min(a, b) == IF a <= b THEN a ELSE b
VARIABLES now, own, reason, phase, at, carry, snap, snapR, ret, pollAt, expAtStart, cancAtStart, explicitDone, restricted
vars == <<now, own, reason, phase, at, carry, snap, snapR, ret, pollAt, expAtStart, cancAtStart, explicitDone, restricted>>
pollVars == <<phase, at, carry, snap, snapR, ret, pollAt, expAtStart, cancAtStart>>

Eff(n) == IF n = 1 THEN own[1] ELSE Min(own[n], IF n = 2 THEN own[1] ELSE Min(own[1], own[2]))
Init == /\ now = 0
        /\ own \in [Nodes -> 0..Inf]
        /\ reason = [n \in Nodes |-> None]
        /\ phase = [n \in Nodes |-> "idle"]   \* idle / down / up / done
        /\ at = [n \in Nodes |-> n]             \* 当前处理的路径层
        /\ carry = [n \in Nodes |-> None]       \* 上行时：父层的结果
        /\ snap = [n \in Nodes |-> [m \in Nodes |-> Inf]]
        /\ snapR = [n \in Nodes |-> [m \in Nodes |-> None]]
        /\ ret = [n \in Nodes |-> None]
        /\ pollAt = [n \in Nodes |-> 0]
        /\ expAtStart = [n \in Nodes |-> FALSE]   \* 开始时路径上已有截止点 <= 检查时刻
        /\ cancAtStart = [n \in Nodes |-> FALSE]  \* 开始时路径上已有令牌取消
        /\ explicitDone = FALSE
        /\ restricted = FALSE

Tick == /\ now < MaxTime
        /\ now' = now + 1
        /\ UNCHANGED <<own, reason, pollVars, explicitDone, restricted>>
\* restrictDeadline：只能收紧。为控制状态空间，全程只发生一次收紧（与检查点任意交错）。
Restrict(n, d) == /\ ~restricted
                  /\ d < own[n]
                  /\ restricted' = TRUE
                  /\ own' = [own EXCEPT ![n] = d]
                  /\ UNCHANGED <<now, reason, pollVars, explicitDone>>
\* 根令牌 cancelWith(Explicit)：锁内首个置位者写原因；子令牌由回调异步传播。
Explicit == /\ ~explicitDone
            /\ explicitDone' = TRUE
            /\ reason' = IF reason[1] = None THEN [reason EXCEPT ![1] = "Explicit"] ELSE reason
            /\ UNCHANGED <<now, own, pollVars, restricted>>
\* ChildCancellation：父已取消时以父的首个原因取消子（已取消的子保留自己的原因）。
Propagate(c) == /\ c # 1
                /\ reason[Parent[c]] # None
                /\ reason[c] = None
                /\ reason' = [reason EXCEPT ![c] = reason[Parent[c]]]
                /\ UNCHANGED <<now, own, pollVars, explicitDone, restricted>>
\* 检查点进程开始（pollDeadline 读一次时钟，整条链共用该时刻；时钟单调）。
PollStart(t) == /\ phase[t] = "idle"
                /\ phase' = [phase EXCEPT ![t] = "down"]
                /\ at' = [at EXCEPT ![t] = t]
                /\ pollAt' = [pollAt EXCEPT ![t] = now]
                /\ expAtStart' = [expAtStart EXCEPT ![t] = \E m \in 1..t: own[m] <= now]
                /\ cancAtStart' = [cancAtStart EXCEPT ![t] = \E m \in 1..t: reason[m] # None]
                /\ UNCHANGED <<now, own, reason, carry, snap, snapR, ret, explicitDone, restricted>>
\* 下行一层：一次加锁读出自身截止点与是否已取消（及原因）。已取消的层仍继续检查祖先：作用域到期
\* 应能被任何检查点观察。
PollDown(t) == /\ phase[t] = "down"
               /\ LET m == at[t] IN
                  /\ snap' = [snap EXCEPT ![t][m] = own[m]]
                  /\ snapR' = [snapR EXCEPT ![t][m] = reason[m]]
                  /\ IF m = 1
                     THEN /\ phase' = [phase EXCEPT ![t] = "up"]
                          /\ at' = [at EXCEPT ![t] = 1]
                     ELSE /\ at' = [at EXCEPT ![t] = m - 1]
                          /\ UNCHANGED phase
               /\ UNCHANGED <<now, own, reason, carry, ret, pollAt, expAtStart, cancAtStart, explicitDone, restricted>>
\* 上行一层（settleWith 原子：锁内首个置位者写原因，返回最终首个原因）。
PollUp(t) == /\ phase[t] = "up"
             /\ LET m == at[t]
                    want == IF snapR[t][m] # None THEN None
                            ELSE IF Help /\ carry[t] # None THEN carry[t]
                            ELSE IF snap[t][m] <= pollAt[t] THEN "Deadline" ELSE None
                    res == IF want = None THEN reason[m]
                           ELSE IF reason[m] = None THEN want ELSE reason[m]
                    \* 实现里未请求取消时返回 none（不再读锁）；请求了则返回最终首个原因。
                    out == IF snapR[t][m] # None THEN snapR[t][m] ELSE IF want = None THEN None ELSE res
                IN /\ reason' = [reason EXCEPT ![m] = res]
                   /\ IF m = t
                      THEN /\ phase' = [phase EXCEPT ![t] = "done"]
                           /\ ret' = [ret EXCEPT ![t] = out]
                           /\ carry' = [carry EXCEPT ![t] = None]
                           /\ snap' = [snap EXCEPT ![t] = [k \in Nodes |-> Inf]]   \* 返回后快照不再可观察
                           /\ snapR' = [snapR EXCEPT ![t] = [k \in Nodes |-> None]]
                           /\ UNCHANGED at
                      ELSE /\ at' = [at EXCEPT ![t] = m + 1]
                           /\ carry' = [carry EXCEPT ![t] = out]
                           /\ UNCHANGED <<phase, ret, snap, snapR>>
             /\ UNCHANGED <<now, own, pollAt, expAtStart, cancAtStart, explicitDone, restricted>>

Next == \/ Tick \/ Explicit
        \/ \E n \in Nodes: (\E d \in 0..MaxTime: Restrict(n, d)) \/ Propagate(n)
        \/ \E t \in Pollers: PollStart(t) \/ PollDown(t) \/ PollUp(t)
Spec == Init /\ [][Next]_vars /\ (\A n \in Nodes: WF_vars(Propagate(n))) /\ \A t \in Pollers: WF_vars(PollDown(t)) /\ WF_vars(PollUp(t))

TypeOK == /\ now \in 0..MaxTime
          /\ own \in [Nodes -> 0..Inf]
          /\ reason \in [Nodes -> Reasons \cup {None}]
          /\ phase \in [Nodes -> {"idle", "down", "up", "done"}]
          /\ ret \in [Nodes -> Reasons \cup {None}]
\* 子不晚于父（有效截止点按定义取链上最小值，这里检查实现的读取路径给出的值确实满足）。
ChildNoLaterThanParent == \A n \in Nodes: n # 1 => Eff(n) <= Eff(Parent[n])
\* 检查点返回非空 ⇒ 本令牌已取消且返回值就是它的首个原因。
PollSound == \A t \in Pollers: (phase[t] = "done" /\ ret[t] # None) => reason[t] = ret[t]
\* 检查开始时路径上已有令牌取消 ⇒ 返回非空（帮助传播，不依赖另一线程的回调进度）。
PollSeesCancel == \A t \in Pollers: (phase[t] = "done" /\ cancAtStart[t]) => ret[t] # None
\* 检查点不漏判：检查开始时链上已有截止点 <= 检查时刻（之后的收紧不计；截止点只收紧，
\* 所以快照时它仍已到期），则返回非空。
PollSeesExpiry == \A t \in Pollers: (phase[t] = "done" /\ expAtStart[t]) => ret[t] # None
\* Deadline 原因只来自确实到期的截止点：链上某个令牌的自身截止点不晚于当前时刻。
DeadlineOrigin == \A n \in Nodes: reason[n] = "Deadline" => \E m \in 1..n: own[m] <= now
\* Explicit 只来自显式取消请求。
ExplicitOrigin == \A n \in Nodes: reason[n] = "Explicit" => explicitDone
\* 子令牌原因与父一致，除非子因自身截止点先到期（单独 Deadline）。
ReasonAgreesWithParent == \A n \in Nodes: (n # 1 /\ reason[n] # None /\ reason[Parent[n]] # None)
                              => (reason[n] = reason[Parent[n]] \/ reason[n] = "Deadline")
\* 首因稳定（动作性质）；截止点只收紧（动作性质）。
FirstReasonStable == [][\A n \in Nodes: reason[n] # None => reason'[n] = reason[n]]_vars
DeadlineOnlyTightens == [][\A n \in Nodes: own'[n] <= own[n]]_vars
\* 活性：父取消终将传给全部后代。
CancelReachesDescendants == \A n \in Nodes: (reason[n] # None) ~> (\A m \in Nodes: m >= n => reason[m] # None)
=============================================================================
