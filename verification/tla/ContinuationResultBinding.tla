--------------------- MODULE ContinuationResultBinding ---------------------
\* 生成任务桥的结果绑定/失败发布契约；不建模 JVM 锁与不返回的通知回调。
EXTENDS Naturals
VARIABLES bound, started, terminal, result, notifying, settled, startBound, binds
vars == <<bound, started, terminal, result, notifying, settled, startBound, binds>>
Init == /\ bound = FALSE /\ started = FALSE /\ terminal = FALSE
        /\ result = "Pending" /\ notifying = FALSE /\ settled = FALSE
        /\ startBound = FALSE /\ binds = 0
Bind == /\ ~started /\ ~terminal /\ ~bound
        /\ bound' = TRUE /\ binds' = binds + 1
        /\ UNCHANGED <<started, terminal, result, notifying, settled, startBound>>
Start == /\ ~started /\ ~terminal
         /\ started' = TRUE /\ startBound' = bound
         /\ UNCHANGED <<bound, terminal, result, notifying, settled, binds>>
ExternalComplete ==
    /\ result = "Pending"
    /\ result' \in {"Completed", "Cancelled"}
    /\ UNCHANGED <<bound, started, terminal, notifying, settled, startBound, binds>>
Fail == /\ started /\ ~terminal
        /\ terminal' = TRUE /\ notifying' = bound
        /\ result' = IF bound /\ result = "Pending" THEN "Failed" ELSE result
        /\ UNCHANGED <<bound, started, settled, startBound, binds>>
\* 通知正常返回或抛异常都必须进入 finally；不承诺终止永不返回的用户回调。
FinishNotification ==
    /\ notifying
    /\ notifying' = FALSE /\ settled' = TRUE
    /\ UNCHANGED <<bound, started, terminal, result, startBound, binds>>
Next == Bind \/ Start \/ ExternalComplete \/ Fail \/ FinishNotification
Spec == Init /\ [][Next]_vars
TypeOK == /\ bound \in BOOLEAN /\ started \in BOOLEAN /\ terminal \in BOOLEAN
          /\ notifying \in BOOLEAN /\ settled \in BOOLEAN /\ startBound \in BOOLEAN
          /\ result \in {"Pending", "Completed", "Cancelled", "Failed"}
          /\ binds \in Nat
BindOnce == binds <= 1
BindingFrozen == started => bound = startBound
BoundFailureIsObservable == bound /\ terminal => result # "Pending"
SettledAfterTerminal == settled => bound /\ terminal /\ ~notifying
NotificationAfterTerminal == notifying => bound /\ terminal
=============================================================================
