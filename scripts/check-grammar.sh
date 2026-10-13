#!/usr/bin/env bash
# 校验语法 submodule 是否与权威仓库（jasper-language/jasper-grammar）的 main 一致。
# 权威位置只有一个；本仓库中的 .g4 是 submodule，请勿在本地副本上直接改。
set -euo pipefail
cd "$(dirname "$0")/.."
SUB="jasper-compiler/src/main/antlr"

git submodule update --init --quiet
(
  cd "$SUB"
  git fetch --quiet origin main
  local_rev=$(git rev-parse HEAD)
  remote_rev=$(git rev-parse origin/main)
  if [ "$local_rev" != "$remote_rev" ]; then
    echo "✗ 语法 submodule 与权威仓库 main 不一致"
    echo "    本地: ${local_rev:0:8}   权威 main: ${remote_rev:0:8}"
    echo "    修复: cd $SUB && git checkout origin/main && cd - && git add $SUB && git commit"
    exit 1
  fi
  echo "✓ 语法与权威仓库 main 一致（${local_rev:0:8}）"
)
