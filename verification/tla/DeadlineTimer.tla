---------------------------- MODULE DeadlineTimer ----------------------------
\* S4 计时唤醒（LANG-S4-TIMER-01）：作用域拥有的 DeadlineTimer。登记（arm）、取消撤销（TimerDisarm）、
\* shutdown 与计时线程的「快照 -> 锁外检查点 -> 限时等待 / 退出」交错；线程空闲即退出，下次登记再启动。
\* 每个登记项对应一个令牌（项之间的父子传播由 DeadlinePropagation 覆盖，这里只关心计时协议）。
\* 时间是离散刻度 now ∈ 0..MaxTime；登记项的截止点 Due[e] 等于其编号。
\* 登记：arm1 注册撤销回调（令牌已取消则立即撤销）-> arm2 锁内入表并决定是否启动线程 -> arm3 启动或唤醒。
\* 取消：cancel 置位令牌（锁外）-> 撤销回调随后在取消者线程上执行（锁内撤销、唤醒）。
\* 线程：snap（锁内快照：取出有效且到期的项、移出无效项、表空则复位 running 并退出）-> poll（锁外逐项
\*   检查点，到期令牌以 Deadline 取消）-> 有触发则立即再 snap，否则 wait（限时等待：许可或最早截止点到达）。
\* 变异常量：ExitInLock = FALSE 把「表空 -> running := FALSE」挪到释放锁之后（登记会在两步之间看到
\*   running = TRUE 而不启动线程，项无人服务）；SnapChecksToken = FALSE 去掉快照里对令牌是否已取消的检查。
EXTENDS Naturals, FiniteSets
CONSTANTS Entries, MaxTime, MaxThreads, ExitInLock, SnapChecksToken
\* 登记项以其截止刻度命名（Entries ⊆ 1..MaxTime），同刻度的多项由 DeadlinePropagation 一侧覆盖。
Due == [e \in Entries |-> e]
VARIABLES now, armPc, inList, armed, cancelled, cancelPc, stopped, running, permits,
          tpc, tdue, starts, fired, firedEarly, staleFire
vars == <<now, armPc, inList, armed, cancelled, cancelPc, stopped, running, permits,
          tpc, tdue, starts, fired, firedEarly, staleFire>>

Threads == 1..MaxThreads
Live == {t \in Threads : tpc[t] # "none"}

Init == /\ now = 0
        /\ armPc = [e \in Entries |-> "idle"] /\ inList = {} /\ armed = [e \in Entries |-> TRUE]
        /\ cancelled = [e \in Entries |-> FALSE] /\ cancelPc = [e \in Entries |-> "idle"]
        /\ stopped = FALSE /\ running = FALSE /\ permits = 0
        /\ tpc = [t \in Threads |-> "none"] /\ tdue = [t \in Threads |-> {}] /\ starts = 0
        /\ fired = [e \in Entries |-> 0] /\ firedEarly = FALSE /\ staleFire = FALSE

Tick == /\ now < MaxTime /\ now' = now + 1
        /\ UNCHANGED <<armPc, inList, armed, cancelled, cancelPc, stopped, running, permits, tpc, tdue, starts, fired, firedEarly, staleFire>>

\* arm1：注册撤销回调；令牌已取消时回调立即在本线程撤销。
Arm1(e) == /\ armPc[e] = "idle" /\ armPc' = [armPc EXCEPT ![e] = "registered"]
           /\ armed' = [armed EXCEPT ![e] = ~cancelled[e]]
           /\ UNCHANGED <<now, inList, cancelled, cancelPc, stopped, running, permits, tpc, tdue, starts, fired, firedEarly, staleFire>>

\* arm2：锁内；已 shutdown 或已撤销则不入表；否则入表，running 为假时置真并负责启动线程。
Arm2(e) == /\ armPc[e] = "registered"
           /\ IF stopped \/ ~armed[e]
              THEN /\ armPc' = [armPc EXCEPT ![e] = "done"] /\ UNCHANGED <<inList, running>>
              ELSE /\ inList' = inList \cup {e}
                   /\ IF running
                      THEN /\ armPc' = [armPc EXCEPT ![e] = "wake"] /\ UNCHANGED running
                      ELSE /\ armPc' = [armPc EXCEPT ![e] = "start"] /\ running' = TRUE
           /\ UNCHANGED <<now, armed, cancelled, cancelPc, stopped, permits, tpc, tdue, starts, fired, firedEarly, staleFire>>

Arm3(e) == \/ /\ armPc[e] = "wake" /\ permits' = permits + 1 /\ armPc' = [armPc EXCEPT ![e] = "done"]
              /\ UNCHANGED <<tpc, starts>>
           \/ /\ armPc[e] = "start" /\ starts < MaxThreads
              /\ starts' = starts + 1 /\ tpc' = [tpc EXCEPT ![starts + 1] = "snap"]
              /\ armPc' = [armPc EXCEPT ![e] = "done"] /\ UNCHANGED permits
           /\ UNCHANGED <<now, inList, armed, cancelled, cancelPc, stopped, running, tdue, fired, firedEarly, staleFire>>

\* 外部取消：令牌置位（首次），撤销回调稍后执行。
Cancel(e) == /\ ~cancelled[e] /\ cancelPc[e] = "idle"
             /\ cancelled' = [cancelled EXCEPT ![e] = TRUE] /\ cancelPc' = [cancelPc EXCEPT ![e] = "callback"]
             /\ UNCHANGED <<now, armPc, inList, armed, stopped, running, permits, tpc, tdue, starts, fired, firedEarly, staleFire>>

\* 撤销回调：只有在 arm1 注册之后才存在；注册之前取消的由 arm1 自己撤销。
Disarm(e) == /\ cancelPc[e] = "callback" /\ cancelPc' = [cancelPc EXCEPT ![e] = "done"]
             /\ IF armPc[e] # "idle"
                THEN armed' = [armed EXCEPT ![e] = FALSE] /\ permits' = permits + 1
                ELSE UNCHANGED <<armed, permits>>
             /\ UNCHANGED <<now, armPc, inList, cancelled, stopped, running, tpc, tdue, starts, fired, firedEarly, staleFire>>

Shutdown == /\ ~stopped /\ stopped' = TRUE /\ armed' = [e \in Entries |-> FALSE] /\ permits' = permits + 1
            /\ UNCHANGED <<now, armPc, inList, cancelled, cancelPc, running, tpc, tdue, starts, fired, firedEarly, staleFire>>

Valid(e) == armed[e] /\ (SnapChecksToken => ~cancelled[e])

\* 快照（锁内）：到期有效项取出并撤销，未到期有效项留表，其余移出；表空则退出。
Snap(t) == /\ tpc[t] = "snap"
           /\ LET due == {e \in inList : Valid(e) /\ Due[e] <= now}
                  rest == {e \in inList : Valid(e) /\ Due[e] > now}
              IN /\ inList' = rest
                 /\ armed' = [e \in Entries |-> IF e \in due THEN FALSE ELSE armed[e]]
                 /\ tdue' = [tdue EXCEPT ![t] = due]
                 /\ staleFire' = (staleFire \/ \E e \in due : cancelled[e])
                 /\ IF due = {} /\ rest = {}
                    THEN IF ExitInLock
                         THEN /\ running' = FALSE /\ tpc' = [tpc EXCEPT ![t] = "none"]
                         ELSE /\ UNCHANGED running /\ tpc' = [tpc EXCEPT ![t] = "exiting"]
                    ELSE /\ UNCHANGED running
                         /\ tpc' = [tpc EXCEPT ![t] = IF due # {} THEN "poll" ELSE "wait"]
           /\ UNCHANGED <<now, armPc, cancelled, cancelPc, stopped, permits, starts, fired, firedEarly>>

\* 变异路径：释放锁之后才复位 running。
Exiting(t) == /\ tpc[t] = "exiting" /\ running' = FALSE /\ tpc' = [tpc EXCEPT ![t] = "none"]
              /\ UNCHANGED <<now, armPc, inList, armed, cancelled, cancelPc, stopped, permits, tdue, starts, fired, firedEarly, staleFire>>

\* 锁外检查点：每个取出的项触发一次；令牌未取消则以 Deadline 取消（撤销回调对已撤销项无效果）。
Poll(t) == /\ tpc[t] = "poll"
           /\ fired' = [e \in Entries |-> IF e \in tdue[t] THEN fired[e] + 1 ELSE fired[e]]
           /\ firedEarly' = (firedEarly \/ \E e \in tdue[t] : Due[e] > now)
           /\ cancelled' = [e \in Entries |-> cancelled[e] \/ e \in tdue[t]]
           /\ tdue' = [tdue EXCEPT ![t] = {}] /\ tpc' = [tpc EXCEPT ![t] = "snap"]
           /\ UNCHANGED <<now, armPc, inList, armed, cancelPc, stopped, running, permits, starts, staleFire>>

\* 限时等待：消耗一个许可，或最早有效截止点已到（超时）。
Wait(t) == /\ tpc[t] = "wait"
           /\ \/ /\ permits > 0 /\ permits' = permits - 1
              \/ /\ \E e \in inList : armed[e] /\ Due[e] <= now
                 /\ UNCHANGED permits
           /\ tpc' = [tpc EXCEPT ![t] = "snap"]
           /\ UNCHANGED <<now, armPc, inList, armed, cancelled, cancelPc, stopped, running, tdue, starts, fired, firedEarly, staleFire>>

Next == \/ Tick \/ Shutdown
        \/ \E e \in Entries : Arm1(e) \/ Arm2(e) \/ Arm3(e) \/ Cancel(e) \/ Disarm(e)
        \/ \E t \in Threads : Snap(t) \/ Exiting(t) \/ Poll(t) \/ Wait(t)

\* 公平性只给计时器与登记 / 撤销内部步骤；外部取消、shutdown 与时钟以外的事件不要求发生。
Fairness == /\ WF_vars(Tick)
            /\ \A e \in Entries : WF_vars(Arm1(e)) /\ WF_vars(Arm2(e)) /\ WF_vars(Arm3(e)) /\ WF_vars(Disarm(e))
            /\ \A t \in Threads : WF_vars(Snap(t)) /\ WF_vars(Exiting(t)) /\ WF_vars(Poll(t)) /\ WF_vars(Wait(t))

Spec == Init /\ [][Next]_vars /\ Fairness

TypeOK == /\ now \in 0..MaxTime /\ inList \subseteq Entries /\ permits \in Nat /\ starts \in 0..MaxThreads
          /\ \A t \in Threads : tpc[t] \in {"none", "snap", "poll", "wait", "exiting"}

\* 至多一个计时线程存活。
AtMostOneThread == Cardinality(Live) <= 1
\* 每项至多触发一次，且只在到期之后触发。
FireOnce == \A e \in Entries : fired[e] <= 1
NoEarlyFire == ~firedEarly
\* 快照从不取出令牌已取消的项（取消返回之后的触发不会再取出它）；快照之后、检查点之前才取消的项照常
\* 触发一次——那次触发线性化在取消之前，检查点只返回既有原因。SnapChecksToken = FALSE 的变异违反本条。
NoStaleFire == ~staleFire
\* shutdown 之后表里没有有效项。
StoppedHasNoArmed == stopped => \A e \in inList : ~armed[e]
\* running 为真时，要么有线程存活，要么有登记者正要启动线程。
RunningIsServed == running => (Live # {} \/ \E e \in Entries : armPc[e] = "start")

\* 不丢到期：登记完成、时钟到达截止点之后，令牌终将被取消（触发或外部取消），除非已 shutdown。
NoLostExpiry == \A e \in Entries : (armPc[e] = "done" /\ now >= Due[e]) ~> (cancelled[e] \/ stopped)
\* 线程终将退出：时钟停在 MaxTime 后，表终会清空、线程退出。
EventuallyIdle == <>[](Live = {})
=============================================================================
