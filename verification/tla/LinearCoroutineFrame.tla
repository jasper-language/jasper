----------------------- MODULE LinearCoroutineFrame -----------------------
\* 单 await 生成帧：注册前设置 phase，回调可同步发生；调度门禁止并行执行段。
\* 本模型只覆盖生成帧的程序位置/注册/快照/恢复控制，不替代 ContinuationDispatch 模型。
EXTENDS Naturals, Integers
VARIABLES phase, registered, input, snapshot, result, saved, restored, running, queued, wake
vars == <<phase, registered, input, snapshot, result, saved, restored, running, queued, wake>>
Init == /\ phase = 0 /\ registered = FALSE /\ input = "Pending"
        /\ snapshot = "None" /\ result = "Pending" /\ saved = 0 /\ restored = FALSE
        /\ running = FALSE /\ queued = TRUE /\ wake = FALSE
CompleteInput ==
    /\ input = "Pending"
    /\ input' \in {"Completed", "Failed", "Cancelled"}
    /\ UNCHANGED <<phase, registered, snapshot, result, saved, restored, running, queued, wake>>
Begin == /\ queued /\ ~running /\ result = "Pending"
         /\ running' = TRUE /\ queued' = FALSE
         /\ UNCHANGED <<phase, registered, input, snapshot, result, saved, restored, wake>>
Save == /\ running /\ phase = 0 /\ phase' = 1 /\ saved' = 3
        /\ UNCHANGED <<registered, input, snapshot, result, restored, running, queued, wake>>
Register == /\ running /\ phase = 1 /\ ~registered /\ registered' = TRUE
            /\ UNCHANGED <<phase, input, snapshot, result, saved, restored, running, queued, wake>>
Callback == /\ registered /\ input # "Pending" /\ snapshot = "None"
            /\ snapshot' = input /\ wake' = running
            /\ queued' = IF running THEN queued ELSE TRUE
            /\ UNCHANGED <<phase, registered, input, result, saved, restored, running>>
Suspend == /\ running /\ registered /\ phase = 1 /\ phase' = 2 /\ running' = FALSE
           /\ queued' = (queued \/ wake) /\ wake' = FALSE
           /\ UNCHANGED <<registered, input, snapshot, result, saved, restored>>
Restore == /\ running /\ phase = 2 /\ snapshot # "None"
           /\ result' = snapshot /\ restored' = TRUE /\ phase' = 3
           /\ running' = FALSE /\ queued' = FALSE /\ wake' = FALSE
           /\ UNCHANGED <<registered, input, snapshot, saved>>
Next == CompleteInput \/ Begin \/ Save \/ Register \/ Callback \/ Suspend \/ Restore
Spec == Init /\ [][Next]_vars
TypeOK == /\ phase \in 0..3 /\ registered \in BOOLEAN /\ saved \in {0, 3}
          /\ input \in {"Pending", "Completed", "Failed", "Cancelled"}
          /\ snapshot \in {"None", "Completed", "Failed", "Cancelled"}
          /\ result \in {"Pending", "Completed", "Failed", "Cancelled"}
          /\ restored \in BOOLEAN /\ running \in BOOLEAN /\ queued \in BOOLEAN /\ wake \in BOOLEAN
SavedAcrossSuspension == phase >= 1 => saved = 3
ResumeRequiresSnapshot == restored => (snapshot # "None" /\ result = snapshot)
RegisterAfterPhase == registered => phase >= 1
SingleQueuedSegment == running => ~queued
=============================================================================
