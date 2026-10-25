# S4-B 接续与独立验收（2026-10-25）

## 接续修复

接手时工作分支 `oc/Q79` 的 `cf1a5d6` 只包含 17 行结果文件，未包含运行时源码，
不能据此判定 S4-B 完成。通过 reflog 找到 `3498a98` 及其祖先
`cb36525` / `02a03e6` / `2038f2d`，合并恢复已有实现，没有从头重写。

恢复的运行时文件为 `Future`、`CancellationToken`、`Executor`、`JvmExecutor`、
`Runnable`、`TaskGroup`、`Context`、`CleanupBudget`，均为 `.jas`。
Kotlin 改动只涉及 AST 整数字面量后缀处理、跨文件编译符号/描述符合并、标准库登记
及测试驱动，没有新增 Kotlin 运行时状态机。

清理预算已有修复：默认五秒必须使用 `5000000000L`；嵌套预算取父剩余与局部上限
的最小值，并将子截止点限制在父截止点以内，保留父起点。使用 `System.nanoTime`。

## 本次真实验证

全部重型验证在 Jasper 构建虚拟机执行：

```bash
bash /home/auth/Documents/jasper/jasper-os-build/vm-run.sh jasper ./gradlew test --no-daemon --rerun-tasks
```

结果为 BUILD SUCCESSFUL（2 分 7 秒）。直接汇总虚拟机 JUnit XML：

| 模块 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| jasper-compiler | 666 | 0 | 0 | 1 |
| jucs | 70 | 0 | 0 | 0 |
| jasper-llvm | 1 | 0 | 0 | 0 |
| 总计 | 737 | 0 | 0 | 1 |

其中 `S4RuntimeJasperTest` 17/17 通过；736 项实际执行。不能把仅 JUCS 的 70 项
误记为语言编译器全量验收。

新增 `verification/run-s4-models.sh`，复跑四份配置，日志保存于
`evidence/s4-models/`。TLC 2.16 均报告无错误：

| 配置 | 可达状态 |
|---|---:|
| FutureStateMachine | 32 |
| TaskGroupAggregation_normal | 115 |
| TaskGroupAggregation_supervisor | 72 |
| CleanupBudgetNesting | 8425 |

虚拟机复现（工具 jar 是已有本地副本，不自动下载）：

```bash
bash /home/auth/Documents/jasper/jasper-os-build/vm-run.sh jasper env TLA2TOOLS_JAR=/tmp/jasper-s4-tla2tools.jar bash verification/run-s4-models.sh
```

语法守卫通过（权威 `116277b3`），未改 `.g4`；代码规范检查公共 KDoc 100%、
无非法依赖或环；FV 闸门通过（基线 `f0165f6`）。

## 后续接续点与限制

本次验收覆盖回调式运行时骨架，不表示语言整体完成。S4-C 挂起/恢复降级仍未实现。
继续工作先检查工作分支与本记录，避免再次从只有结果文件的旧提交起步。

后续优先补运行时异常路径：回调抛异常时后续回调与任务 settled 标记能否完成、
执行器拒绝调度、取消/关闭并发边界。随后按 S4 契约推进协程降级。
TLA+ 只验证有限抽象模型，未证明 JVM 内存模型、实际信号量、时钟回绕或整数溢出；
无法中断的外部阻塞仍可能阻止 TaskGroup.close 返回。

改动保留在 `oc/Q79`，没有合入 main 或推送远端。


## 接续二：回调异常隔离

已修复首个回调抛错就中断后续通知的问题。新增回归在修改前真实执行失败
（callback-failure-isolation 返回 2：后续完成回调未执行），修改后通过。
Future 与 CancellationToken 先完成全部通知再抛 CallbackDispatchError；TaskGroup
继续尝试全部取消，TaskRunner 和调度拒绝路径用嵌套 finally 保证 settled。
详细公开契约见 `S4-coroutine-context.md` 的回调异常条目。

构建虚拟机 `./gradlew test --no-daemon` 通过（1 分 21 秒），JUnit XML 汇总
740 项 / 0 failures / 0 errors / 1 skipped；739 项实际执行。其中运行时专项
20/20，新增 3 项覆盖回调失败隔离、失败通知后的兄弟取消/close、调度拒绝后的
settled。其他模块为缓存或受影响任务按 Gradle 正常判定执行，未声称本次全部强制重跑。

五组 TLA+ 配置均重新运行通过，新增 CallbackDispatch 模型 23 个可达状态。
新增性质 LANG-S4BJ-03 为 tested；模型中的顺序、失败计数和全部尝试后才报告成立，
不表示 JVM 实现被形式证明。语法文件未改，代码规范与 FV 产物检查通过。

后续仍需推进 S4-C 挂起/恢复降级；当前回调协议不能中断阻塞回调。
