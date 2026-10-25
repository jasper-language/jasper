------------------------ MODULE ContinuationDispatch ------------------------
\* 调度门抽象：enqueue 只表示就绪队列中存在工作，不表示内联执行。
\* 对应 resume/run/finally/finish；不建模队列公平性、JVM 锁或业务状态机。
EXTENDS Naturals
VARIABLES queued, running, wake, finished, active
vars == <<queued, running, wake, finished, active>>
Init == /\ queued = FALSE /\ running = FALSE /\ wake = FALSE
        /\ finished = FALSE /\ active = 0
Resume == /\ ~finished
          /\ queued' = IF running THEN queued ELSE TRUE
          /\ wake' = IF running THEN TRUE ELSE wake
          /\ UNCHANGED <<running, finished, active>>
Enter == /\ queued /\ ~running /\ ~finished
         /\ queued' = FALSE /\ running' = TRUE /\ active' = active + 1
         /\ UNCHANGED <<wake, finished>>
Leave == /\ running
         /\ running' = FALSE /\ active' = active - 1
         /\ queued' = IF wake /\ ~finished THEN TRUE ELSE queued
         /\ wake' = FALSE
         /\ UNCHANGED finished
Finish == /\ ~finished
          /\ finished' = TRUE /\ wake' = FALSE /\ queued' = FALSE
          /\ UNCHANGED <<running, active>>
Next == Resume \/ Enter \/ Leave \/ Finish
Spec == Init /\ [][Next]_vars
TypeOK == /\ queued \in BOOLEAN /\ running \in BOOLEAN
          /\ wake \in BOOLEAN /\ finished \in BOOLEAN /\ active \in Nat
SingleExecutor == active <= 1
ActiveMatchesRunning == (running <=> active = 1)
WakeRetainedWhileRunning == wake => running /\ ~finished
NoConcurrentQueue == running => ~queued
TerminalDiscardsWork == finished => ~queued /\ ~wake
=============================================================================
