---- MODULE JasoLoadPlan ----
\* std.jaso.JasoLoadPlanner.plan：显式栈深度优先后序。
\* 所有 Modules 上的依赖图（含自环）都作为初始状态枚举；某些模块在运行中、某些不在路径上。
\* 性质：结束时要么报环 / 缺失，要么结果恰是根的闭包中未运行的部分，且依赖排在依赖者之前。
\* 计划只读模块表快照，并发不在本模型范围内：最终裁定是 JasoModuleLifecycle 的 reserve。
\* 变异：UseVisiting = FALSE 去掉「在栈上」检查，UseDone = FALSE 去掉「已排好」检查，
\*   DescendRunning = TRUE 展开运行中的模块。
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS Modules, Root, UseVisiting, UseDone, DescendRunning

VARIABLES deps, running, onPath, mark, stack, order, outcome

vars == <<deps, running, onPath, mark, stack, order, outcome>>

\* 栈元素：[m |-> 模块, todo |-> 未处理的依赖集合]（实现按清单顺序，集合覆盖所有顺序）。
Push(m) == [m |-> m, todo |-> deps[m]]

InitResolve(m) ==
    CASE m \in running -> "running"
      [] m \notin onPath -> "missing"
      [] OTHER -> "file"

Init ==
    /\ deps \in [Modules -> SUBSET Modules]
    /\ running \in SUBSET (Modules \ {Root})
    /\ onPath \in SUBSET Modules
    /\ mark = [m \in Modules |-> 0]
    /\ order = <<>>
    /\ CASE InitResolve(Root) = "file" ->
                stack = <<Push(Root)>> /\ outcome = "planning"
         [] InitResolve(Root) = "missing" ->
                stack = <<>> /\ outcome = "missing"
         [] OTHER ->
                stack = <<>> /\ outcome = "done"

Top == stack[Len(stack)]
Marked(m, v) == [mark EXCEPT ![m] = v]

Finish ==
    /\ outcome = "planning"
    /\ stack # <<>>
    /\ Top.todo = {}
    /\ mark' = Marked(Top.m, 2)
    /\ order' = Append(order, Top.m)
    /\ stack' = SubSeq(stack, 1, Len(stack) - 1)
    /\ outcome' = IF Len(stack) = 1 THEN "done" ELSE "planning"
    /\ UNCHANGED <<deps, running, onPath>>

Step ==
    /\ outcome = "planning"
    /\ stack # <<>>
    /\ \E d \in Top.todo:
        LET rest == [Top EXCEPT !.todo = @ \ {d}]
            popped == [stack EXCEPT ![Len(stack)] = rest]
        IN
        /\ UNCHANGED <<deps, running, onPath, order>>
        /\ IF d \in running /\ ~DescendRunning THEN
                /\ stack' = popped /\ mark' = mark /\ outcome' = outcome
           ELSE IF d \notin onPath /\ d \notin running THEN
                /\ outcome' = "missing" /\ stack' = popped /\ mark' = mark
           ELSE IF UseVisiting /\ mark[d] = 1 THEN
                /\ outcome' = "cycle" /\ stack' = popped /\ mark' = mark
           ELSE IF UseDone /\ mark[d] = 2 THEN
                /\ stack' = popped /\ mark' = mark /\ outcome' = outcome
           ELSE IF Len(stack) >= 2 * Cardinality(Modules) THEN
                \* 只有去掉检查的变异才可能走到：把无界展开截成结果错误。
                /\ outcome' = "overflow" /\ stack' = popped /\ mark' = mark
           ELSE
                /\ stack' = Append(popped, Push(d))
                /\ mark' = Marked(d, 1)
                /\ outcome' = outcome

Stutter == outcome # "planning" /\ UNCHANGED vars

Next == Finish \/ Step \/ Stutter

\* 根的依赖闭包，运行中的模块不展开。
RECURSIVE Reach(_, _)
Reach(frontier, seen) ==
    IF frontier = {} THEN seen
    ELSE LET next == UNION {deps[m] : m \in frontier \ running} \ seen
         IN Reach(next, seen \cup next)

Closure == Reach({Root}, {Root})
Needed == Closure \ running

RECURSIVE HasCycleFrom(_, _)
HasCycleFrom(m, path) ==
    IF m \in running THEN FALSE
    ELSE IF m \in path THEN TRUE
    ELSE \E d \in deps[m]: HasCycleFrom(d, path \cup {m})

IndexOf(m) == CHOOSE i \in 1..Len(order): order[i] = m
Listed == {order[i] : i \in 1..Len(order)}

\* 结束且成功：恰好是闭包中未运行的部分、不重复、依赖在前。
PlanCorrect ==
    outcome = "done" =>
        /\ Listed = Needed
        /\ Len(order) = Cardinality(Listed)
        /\ \A m \in Listed: \A d \in deps[m] \ running: d \in Listed /\ IndexOf(d) < IndexOf(m)

\* 报环当且仅当闭包里真有环（闭包内没有缺失时才能走到判定完）。
NoFalseCycle == outcome = "cycle" => HasCycleFrom(Root, {})

NoFalseMissing == outcome = "missing" => \E m \in Closure: m \notin onPath /\ m \notin running

NoOverflow == outcome # "overflow"

\* 有环且闭包都可解析时不得报成功。
CycleDetected ==
    outcome = "done" => ~HasCycleFrom(Root, {})

Spec == Init /\ [][Next]_vars
====
