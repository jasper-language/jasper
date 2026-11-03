------------------------------ MODULE ScopeExitClose ------------------------------
\* 作用域异常退出（对应 TaskGroup.closeAbruptly / close / joinAndAggregateHoldingLock / cancelBecause、
\* TaskRunner.run）。两个退出调用可并发：closeAbruptly 持锁封闭入口并置 abrupt（聚合已冻结则
\* 不置、不取消），再以 ScopeExit 取消根令牌与各子 Future，等全部 settled 后聚合并返回被压制数；
\* close 封闭入口、等全部 settled 后聚合，abrupt 未置时才抛聚合结果。显式取消与普通组失败级联
\* 是并发的其他取消源；子任务可在封闭前登记。聚合首次冻结（持锁）。
\* 变异：ReadAbrupt = FALSE（close 不看 abrupt，把自身 ScopeExit 当外部取消抛出）；
\*       JoinBeforeReturn = FALSE（closeAbruptly 不等待回收就返回）。
\* 不建模：回调抛错（CallbackDispatch 另有模型）、计时器、业务值。
EXTENDS Integers, FiniteSets
CONSTANTS Children, Supervisor, ReadAbrupt, JoinBeforeReturn
None == "none"
Reasons == {"ScopeExit", "Explicit", "SiblingFailed"}
States == {"Absent", "Pending", "Done", "Failed"} \cup Reasons
VARIABLES child, ran, settled, root, closed, abrupt, abruptPc, abruptTodo, closePc, explicit,
          frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected
vars == <<child, ran, settled, root, closed, abrupt, abruptPc, abruptTodo, closePc, explicit,
          frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

Terminal(s) == s \notin {"Absent", "Pending"}
Registered == {c \in Children: child[c] # "Absent"}
AllSettled == \A c \in Registered: settled[c]
FailedSet == {c \in Children: child[c] = "Failed"}
FirstRoot(r) == IF root = None THEN r ELSE root

Init == /\ child = [c \in Children |-> "Absent"]
        /\ ran = [c \in Children |-> FALSE]
        /\ settled = [c \in Children |-> FALSE]
        /\ root = None
        /\ closed = FALSE
        /\ abrupt = FALSE
        /\ abruptPc = "idle"
        /\ abruptTodo = {}
        /\ closePc = "idle"
        /\ explicit = FALSE
        /\ frozen = FALSE
        /\ frozenFailed = 0
        /\ frozenCancel = None
        /\ abruptResult = -1
        /\ closeResult = None
        /\ rejected = FALSE

\* spawn：持锁判断 closed，已封闭则拒绝。
Spawn(c) == /\ child[c] = "Absent"
            /\ IF closed
               THEN /\ rejected' = TRUE
                    /\ UNCHANGED child
               ELSE /\ child' = [child EXCEPT ![c] = "Pending"]
                    /\ UNCHANGED rejected
            /\ UNCHANGED <<ran, settled, root, closed, abrupt, abruptPc, abruptTodo, closePc, explicit,
                           frozen, frozenFailed, frozenCancel, abruptResult, closeResult>>

\* 任务体一次执行：令牌已取消则以根原因发布；否则成功或失败。失败在普通组中级联（只写根令牌，
\* 子 Future 的写入由任务体观察令牌覆盖：结果原因都来自根）。Future 已终态时发布是空操作。
Run(c, outcome) ==
    /\ child[c] # "Absent"
    /\ ~ran[c]
    /\ ran' = [ran EXCEPT ![c] = TRUE]
    /\ settled' = [settled EXCEPT ![c] = TRUE]
    /\ LET result == IF root # None THEN root ELSE outcome
       IN /\ child' = IF child[c] = "Pending" THEN [child EXCEPT ![c] = result] ELSE child
          /\ root' = IF child[c] = "Pending" /\ result = "Failed" /\ ~Supervisor
                     THEN FirstRoot("SiblingFailed") ELSE root
    /\ UNCHANGED <<closed, abrupt, abruptPc, abruptTodo, closePc, explicit,
                   frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

\* 根令牌取消后 Future 被取消（cancelBecause 对子 Future 的写入，或 ScopeCancellationRelay）。
ChildObserves(c) == /\ root # None
                    /\ child[c] = "Pending"
                    /\ child' = [child EXCEPT ![c] = root]
                    /\ UNCHANGED <<ran, settled, root, closed, abrupt, abruptPc, abruptTodo, closePc, explicit,
                                   frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

ExplicitCancel == /\ ~explicit
                  /\ explicit' = TRUE
                  /\ root' = FirstRoot("Explicit")
                  /\ UNCHANGED <<child, ran, settled, closed, abrupt, abruptPc, abruptTodo, closePc,
                                 frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

\* closeAbruptly 第一段（持锁）。
AbruptSeal == /\ abruptPc = "idle"
              /\ closed' = TRUE
              /\ abrupt' = IF frozen THEN abrupt ELSE TRUE
              /\ abruptPc' = IF frozen THEN "join" ELSE "cancelRoot"
              /\ UNCHANGED <<child, ran, settled, root, abruptTodo, closePc, explicit,
                             frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

AbruptCancelRoot == /\ abruptPc = "cancelRoot"
                    /\ root' = FirstRoot("ScopeExit")
                    /\ abruptTodo' = Registered
                    /\ abruptPc' = "cancelChildren"
                    /\ UNCHANGED <<child, ran, settled, closed, abrupt, closePc, explicit,
                                   frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

AbruptCancelChild == /\ abruptPc = "cancelChildren"
                     /\ IF abruptTodo = {}
                        THEN /\ abruptPc' = "join"
                             /\ UNCHANGED <<child, abruptTodo>>
                        ELSE \E c \in abruptTodo:
                               /\ child' = IF child[c] = "Pending" THEN [child EXCEPT ![c] = root] ELSE child
                               /\ abruptTodo' = abruptTodo \ {c}
                               /\ UNCHANGED abruptPc
                     /\ UNCHANGED <<ran, settled, root, closed, abrupt, closePc, explicit,
                                    frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

\* joinAndAggregateHoldingLock 的聚合：首次到达时持锁冻结。
Freeze == /\ frozen' = TRUE
          /\ frozenFailed' = IF frozen THEN frozenFailed ELSE Cardinality(FailedSet)
          /\ frozenCancel' = IF frozen THEN frozenCancel
                             ELSE IF root # None THEN root
                             ELSE None

AbruptReturn == /\ abruptPc = "join"
                /\ JoinBeforeReturn => AllSettled
                /\ Freeze
                /\ abruptPc' = "returned"
                /\ abruptResult' = IF frozen THEN frozenFailed ELSE Cardinality(FailedSet)
                /\ UNCHANGED <<child, ran, settled, root, closed, abrupt, abruptTodo, closePc, explicit,
                               closeResult, rejected>>

CloseSeal == /\ closePc = "idle"
             /\ closed' = TRUE
             /\ closePc' = "join"
             /\ UNCHANGED <<child, ran, settled, root, abrupt, abruptPc, abruptTodo, explicit,
                            frozen, frozenFailed, frozenCancel, abruptResult, closeResult, rejected>>

\* close 的报告：abrupt（聚合与读取同一把锁）时不抛；否则失败优先于取消。
CloseReturn == /\ closePc = "join"
               /\ AllSettled
               /\ Freeze
               /\ closePc' = "returned"
               /\ LET failed == IF frozen THEN frozenFailed ELSE Cardinality(FailedSet)
                      cancel == IF frozen THEN frozenCancel ELSE IF root # None THEN root ELSE None
                  IN closeResult' = IF ReadAbrupt /\ abrupt THEN "ok"
                                    ELSE IF failed > 0 THEN "failure"
                                    ELSE IF cancel # None THEN cancel
                                    ELSE "ok"
               /\ UNCHANGED <<child, ran, settled, root, closed, abrupt, abruptPc, abruptTodo, explicit,
                              abruptResult, rejected>>

Next == \/ \E c \in Children: Spawn(c) \/ ChildObserves(c) \/ Run(c, "Done") \/ Run(c, "Failed")
        \/ ExplicitCancel
        \/ AbruptSeal \/ AbruptCancelRoot \/ AbruptCancelChild \/ AbruptReturn
        \/ CloseSeal \/ CloseReturn

Fair == /\ \A c \in Children: WF_vars(Run(c, "Done")) /\ WF_vars(ChildObserves(c))
        /\ WF_vars(AbruptCancelRoot) /\ WF_vars(AbruptCancelChild) /\ WF_vars(AbruptReturn)
        /\ WF_vars(CloseReturn)
Spec == Init /\ [][Next]_vars /\ Fair

TypeOK == /\ child \in [Children -> States]
          /\ root \in Reasons \cup {None}
          /\ abruptResult \in -1..Cardinality(Children)

\* 封闭之后不再有新子任务：Spawn 只在未封闭时登记（入口封闭先于取消）。
SealedHasNoNewChild == [][closed => \A c \in Children: (child[c] = "Absent" => child'[c] = "Absent")]_vars
\* closeAbruptly 返回时全部已登记子任务已退出（回收保证）。
AbruptReturnsAfterJoin == abruptPc = "returned" => AllSettled
\* 自身 ScopeExit 不当外部取消抛出，close 也不再抛出聚合（在途异常不被掩盖）。
ScopeExitNeverThrown == closeResult # "ScopeExit"
AbruptSilencesClose == (abrupt /\ closePc = "returned") => closeResult = "ok"
\* 被压制数恰为冻结时的失败数；两个调用看到同一聚合，失败不静默丢失。
AbruptCountIsFailures == abruptResult # -1 => abruptResult = frozenFailed
FrozenFailuresComplete == (frozen /\ AllSettled) => frozenFailed = Cardinality(FailedSet)
\* 根原因之外不会写入别的原因（首因稳定 + 作用域唯一原因）。
ChildReasonIsRoot == \A c \in Children: child[c] \in Reasons => child[c] = root
\* ScopeExit 只来自 closeAbruptly，且聚合冻结后不再写。
ScopeExitOnlyFromAbrupt == root = "ScopeExit" => abrupt
FirstReasonStable == [][root # None => root' = root]_vars
\* 活性：closeAbruptly 开始后终将返回，已登记子任务全部退出。
AbruptTerminates == (abruptPc # "idle") ~> (abruptPc = "returned" /\ AllSettled)
=============================================================================
