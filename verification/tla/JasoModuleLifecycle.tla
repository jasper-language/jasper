---- MODULE JasoModuleLifecycle ----
\* .jaso L1 模块表（LANG-JASO-RUNTIME-01）：规范第 12.2 节依赖判定、stop 与失败装载。
\* 模块阶段：none 未装入 → loading 已预订（链接、init、start 中，模块代码在锁外运行）→ running → stopped；
\* loading 可回到 none（装载失败，名字可再用）。Reserve、Commit、Abort、BeginStop 各是表锁内的一步，
\* 对应 JasoModuleTable 的 reserve / commit / abort / beginStop；stop 钩子在 BeginStop 之后锁外运行。
\* NoLoadingPin = TRUE 是变异配置：stop 不看已预订的依赖者，应违反 DependenciesRunning。
\* StoppedSatisfies = TRUE 是变异配置：已停止的模块也满足依赖，应违反 DependenciesRunning。
\* ReuseStoppedName = TRUE 是变异配置：已停止的名字可再次预订，应违反 StopAtMostOnce。
EXTENDS Naturals

CONSTANTS Modules, Depends, NoLoadingPin, StoppedSatisfies, ReuseStoppedName

VARIABLES phase, hookRuns, attempts

vars == <<phase, hookRuns, attempts>>

Init == /\ phase = [m \in Modules |-> "none"]
        /\ hookRuns = [m \in Modules |-> 0]
        /\ attempts = 0

Satisfied(d) == phase[d] = "running" \/ (StoppedSatisfies /\ phase[d] = "stopped")

\* 有界：失败重试让状态空间有限。
Reserve(m) == /\ attempts < 6
              /\ phase[m] = "none" \/ (ReuseStoppedName /\ phase[m] = "stopped")
              /\ \A d \in Depends[m] : Satisfied(d)
              /\ phase' = [phase EXCEPT ![m] = "loading"]
              /\ attempts' = attempts + 1
              /\ UNCHANGED hookRuns

Commit(m) == /\ phase[m] = "loading"
             /\ phase' = [phase EXCEPT ![m] = "running"]
             /\ UNCHANGED <<hookRuns, attempts>>

Abort(m) == /\ phase[m] = "loading"
            /\ phase' = [phase EXCEPT ![m] = "none"]
            /\ UNCHANGED <<hookRuns, attempts>>

Pinned(m) == \E o \in Modules :
                /\ m \in Depends[o]
                /\ \/ phase[o] = "running"
                   \/ (~NoLoadingPin /\ phase[o] = "loading")

BeginStop(m) == /\ phase[m] = "running"
                /\ ~Pinned(m)
                /\ phase' = [phase EXCEPT ![m] = "stopped"]
                /\ hookRuns' = [hookRuns EXCEPT ![m] = @ + 1]
                /\ UNCHANGED attempts

Next == \E m \in Modules : Reserve(m) \/ Commit(m) \/ Abort(m) \/ BeginStop(m)

Spec == Init /\ [][Next]_vars

\* 已预订或运行中模块的每项依赖都在运行中（规范：stop 拒绝有运行中依赖者；预订同样挡住 stop）。
DependenciesRunning == \A m \in Modules :
    phase[m] \in {"loading", "running"} => \A d \in Depends[m] : phase[d] = "running"

\* stop 钩子至多运行一次；运行过的模块保持已停止，名字不复用。
StopAtMostOnce == \A m \in Modules : hookRuns[m] <= 1 /\ (hookRuns[m] = 1 => phase[m] = "stopped")

\* 已停止是终态（beta 没有卸载）。
StoppedIsFinal == [][\A m \in Modules : phase[m] = "stopped" => phase'[m] = "stopped"]_vars

\* 依赖图：c 依赖 a 与 b，b 依赖 a。
DependsDef == [m \in Modules |-> IF m = "c" THEN {"a", "b"} ELSE IF m = "b" THEN {"a"} ELSE {}]

TypeOK == phase \in [Modules -> {"none", "loading", "running", "stopped"}]
====
