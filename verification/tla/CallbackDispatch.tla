------------------------- MODULE CallbackDispatch -------------------------
\* 批次模型：终态先发布，按注册序尝试通知，全部尝试后才报告异常。
\* 对应 Future.finish / CancellationToken.cancel / TaskGroup.cancel 的锁外循环。
\* 回调内部只抽象为成功/抛错；不建模阻塞、重入、JVM 内存模型或内存耗尽。
EXTENDS Naturals, FiniteSets
CONSTANT Count
VARIABLES next, attempted, failed, errors, reported
vars == <<next, attempted, failed, errors, reported>>
Init == /\ next = 1
        /\ attempted = {}
        /\ failed = {}
        /\ errors = 0
        /\ reported = FALSE
Attempt == /\ next <= Count
           /\ \E throws \in BOOLEAN:
                /\ attempted' = attempted \cup {next}
                /\ failed' = IF throws THEN failed \cup {next} ELSE failed
                /\ errors' = errors + IF throws THEN 1 ELSE 0
           /\ next' = next + 1
           /\ UNCHANGED reported
Report == /\ next = Count + 1
          /\ ~reported
          /\ reported' = TRUE
          /\ UNCHANGED <<next, attempted, failed, errors>>
Next == Attempt \/ Report
Spec == Init /\ [][Next]_vars
TypeOK == /\ next \in 1..(Count + 1)
          /\ attempted \subseteq 1..Count
          /\ failed \subseteq attempted
          /\ errors \in 0..Count
          /\ reported \in BOOLEAN
InOrderExactlyOnce == attempted = 1..(next - 1)
FailuresCounted == errors = Cardinality(failed)
ReportAfterAll == reported => attempted = 1..Count
=============================================================================
