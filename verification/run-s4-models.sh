#!/usr/bin/env bash
# S4-B 有限模型验收；仅验证模型中的不变量，不替代 Jasper 端到端测试。
# 前置条件：Java 与本地 tla2tools.jar；不下载依赖，任一模型失败立即退出。
# 日志中的仓库绝对路径改写为相对路径，证据文件不暴露构建机目录布局。
set -euo pipefail
cd "$(dirname "$0")/.."
jar="${TLA2TOOLS_JAR:-build/tools/tla2tools.jar}"
if [[ ! -f "$jar" ]]; then
    echo "缺少 TLA+ 工具：请设置 TLA2TOOLS_JAR 指向已有的 tla2tools.jar" >&2
    exit 2
fi
mkdir -p evidence/s4-models build/tmp/s4-models
for config in FutureStateMachine TaskGroupAggregation_normal TaskGroupAggregation_supervisor CleanupBudgetNesting CallbackDispatch ContinuationDispatch ContinuationResultBinding LinearCoroutineFrame CoroutineScopeLaunch_normal CoroutineScopeLaunch_supervisor; do
    model="$config"
    case "$config" in
        TaskGroupAggregation_*) model=TaskGroupAggregation ;;
        CoroutineScopeLaunch_*) model=CoroutineScopeLaunch ;;
    esac
    java -cp "$jar" tlc2.TLC -workers 1 \
        -metadir "build/tmp/s4-models/$config" \
        -config "verification/tla/$config.cfg" "verification/tla/$model.tla" \
        2>&1 | sed -e "s#$PWD/##g" | tee "evidence/s4-models/$config.log"
done
