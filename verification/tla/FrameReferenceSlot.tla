---------------------------- MODULE FrameReferenceSlot ----------------------------
\* 生成协程帧一个引用槽的生命周期（用户 2026-11-04 裁决；LANG-CONT-REF-SLOT-01）。
\* 只建模一个槽位走过的抽象程序：构造 -> 写入 -> 跨挂起读取 -> 死亡 -> 再挂起 -> 回收。
\* 外部事件：结果被作用域取消（自身回调随后写快照字段并请求恢复）、被等待者完成或失败、
\* 写入的值可能是 null（平台调用与泛型擦除）。规模：一个槽位、两个挂起点。
\*
\* 常量开关都为 FALSE 时是实现；各 _*.cfg 打开一个开关，分别对应去掉实现中的一条规则，
\* 期望 TLC 给出反例。
EXTENDS Naturals

CONSTANTS
    Nullable,        \* 槽位类型是否可空
    CleanupReads,    \* 所在区域的 finally 是否读这个槽位
    DefaultValue,    \* 变异：构造时写隐式默认值并视为有效
    NoWriteCheck,    \* 变异：非空槽位写入 null 不报错
    NoClear,         \* 变异：死亡后不清除
    IgnoreCleanup,   \* 变异：活跃性不算 finally，读取后就清除
    ConsumeOnOwn     \* 变异：自身结果回调的恢复也去消费快照（入口不查结果已终结）

VARIABLES
    pc,       \* "ctor" "suspended" "consuming" "body" "suspended2" "unwind" "settled"
    valid,    \* 槽位有效（可空：标记；非空：非 null）
    value,    \* "none"（未写或已清除）/ "obj" / "null"（显式空值）
    outcome,  \* 帧的快照字段："none" / "ok" / "failed"
    snap,     \* 恢复块取走的快照
    done,     \* 结果 Future 已终结
    ownFired, \* 结果自身的回调已送达
    pending,  \* 有恢复请求（串行门把恢复合并到段结束之后）
    entered,  \* 已进入 finally 区域（写入之后才进入）
    fault     \* 观察到的违规

vars == <<pc, valid, value, outcome, snap, done, ownFired, pending, entered, fault>>

Init ==
    /\ pc = "ctor"
    /\ valid = DefaultValue
    /\ value = IF DefaultValue THEN "null" ELSE "none"
    /\ outcome = "none"
    /\ snap = "none"
    /\ done = FALSE
    /\ ownFired = FALSE
    /\ pending = TRUE
    /\ entered = FALSE
    /\ fault = "none"

Clear == /\ valid' = FALSE /\ value' = "none"

\* 第一段：写入后进入区域并挂起。非空槽位写入 null 抛 FrameSlotError，槽位保持无效，去展开。
First(v) ==
    /\ pc = "ctor" /\ pending /\ ~done
    /\ pending' = FALSE
    /\ IF v = "null" /\ ~Nullable /\ ~NoWriteCheck
       THEN /\ pc' = "unwind"
            /\ UNCHANGED <<valid, value, entered>>
       ELSE /\ valid' = TRUE /\ value' = v /\ pc' = "suspended" /\ entered' = TRUE
    /\ UNCHANGED <<outcome, snap, done, ownFired, fault>>

\* 被等待者完成：快照写入帧，请求恢复。
AwaitedDone(kind) ==
    /\ pc \in {"suspended", "suspended2"}
    /\ outcome = "none" /\ ~pending /\ ~done
    /\ outcome' = kind /\ pending' = TRUE
    /\ UNCHANGED <<pc, valid, value, snap, done, ownFired, entered, fault>>

\* 作用域取消：结果终结。
ScopeCancel ==
    /\ ~done /\ pc # "settled"
    /\ done' = TRUE
    /\ UNCHANGED <<pc, valid, value, outcome, snap, ownFired, pending, entered, fault>>

\* 结果自身的回调：也写快照字段并请求恢复；可能落在恢复块取走快照与清空字段之间。
OwnCallback ==
    /\ done /\ ~ownFired /\ pc # "settled"
    /\ ownFired' = TRUE /\ outcome' = "ok" /\ pending' = TRUE
    /\ UNCHANGED <<pc, valid, value, snap, done, entered, fault>>

\* 分派入口：结果已终结就跳出去展开，不消费快照。
EntryUnwind ==
    /\ pc \in {"ctor", "suspended", "suspended2", "body"} /\ pending /\ done
    /\ (~ConsumeOnOwn \/ pc = "ctor")
    /\ pc' = "unwind" /\ pending' = FALSE
    /\ UNCHANGED <<valid, value, outcome, snap, done, ownFired, entered, fault>>

\* 恢复块第一步：取走快照。快照字段为空说明恢复来自不该消费的请求。
Consume(from) ==
    /\ pc = from /\ pending /\ (~done \/ (ConsumeOnOwn /\ pc # "ctor"))
    /\ fault' = IF outcome = "none" THEN "consume-empty" ELSE fault
    /\ snap' = outcome /\ pending' = FALSE
    /\ pc' = IF from = "suspended" THEN "consuming" ELSE "unwind"
    /\ outcome' = IF from = "suspended" THEN outcome ELSE "none"
    /\ UNCHANGED <<valid, value, done, ownFired, entered>>

\* 恢复块第二步：清空快照字段（它持有结果值，不能比绑定的槽位活得更久）。
ClearOutcome ==
    /\ pc = "consuming"
    /\ outcome' = "none"
    /\ pc' = IF snap = "failed" THEN "unwind" ELSE "body"
    /\ UNCHANGED <<valid, value, snap, done, ownFired, pending, entered, fault>>

\* 槽位最后一次正常读取；finally 还要读时保留，否则在下一次挂起前清除。
Body ==
    /\ pc = "body" /\ (~pending \/ ConsumeOnOwn)
    /\ fault' = IF valid THEN fault ELSE "read-invalid"
    /\ pc' = "suspended2"
    /\ IF (CleanupReads /\ ~IgnoreCleanup) \/ NoClear
       THEN UNCHANGED <<valid, value>>
       ELSE Clear
    /\ UNCHANGED <<outcome, snap, done, ownFired, pending, entered>>

\* 展开：已进入区域时 finally 读槽位；发布终态、回收并清除全部引用槽。门合并未处理的恢复请求。
Unwind ==
    /\ pc = "unwind"
    /\ fault' = IF CleanupReads /\ entered /\ ~valid THEN "read-invalid" ELSE fault
    /\ pc' = "settled" /\ done' = TRUE /\ pending' = FALSE
    /\ IF NoClear THEN UNCHANGED <<valid, value>> ELSE Clear
    /\ UNCHANGED <<outcome, snap, ownFired, entered>>

\* 回收之后迟到的恢复请求被关闭的门丢弃。
Finished ==
    /\ pc = "settled"
    /\ UNCHANGED vars

Next ==
    \/ \E v \in {"obj", "null"} : First(v)
    \/ \E kind \in {"ok", "failed"} : AwaitedDone(kind)
    \/ ScopeCancel \/ OwnCallback \/ EntryUnwind
    \/ Consume("suspended") \/ Consume("suspended2") \/ ClearOutcome
    \/ Body \/ Unwind \/ Finished

\* 没有隐式业务默认值：第一次写入之前槽位无效、不持有任何东西。
NoImplicitDefault == pc = "ctor" => (~valid /\ value = "none")
\* 非空引用有效时不为 null。
NonNullValidNotNull == (~Nullable /\ valid) => value # "null"
\* 有效标记 + null 才是显式空值：无效的槽位不持有任何东西。
InvalidHoldsNothing == ~valid => value = "none"
\* 读取之前已初始化且未死亡；恢复只消费真实快照。
NoFault == fault = "none"
\* 死亡后清除：第二次挂起时 finally 不读的槽位已清除，回收后一律清除。
DeadCleared == (pc = "suspended2" /\ ~CleanupReads) => value = "none"
SettledCleared == pc = "settled" => (~valid /\ value = "none")

TypeOK ==
    /\ valid \in BOOLEAN
    /\ value \in {"none", "obj", "null"}
    /\ outcome \in {"none", "ok", "failed"}
    /\ snap \in {"none", "ok", "failed"}
    /\ done \in BOOLEAN /\ ownFired \in BOOLEAN
    /\ pending \in BOOLEAN /\ entered \in BOOLEAN
====================================================================================
