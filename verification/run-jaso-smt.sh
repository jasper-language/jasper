#!/usr/bin/env bash
# 运行 `.jaso` 读取器的 Z3 算术证明：前 8 个查询必须 unsat，最后一个（故意写错的健全性检查）必须 sat。
# 用法：Z3=<z3 可执行文件> bash verification/run-jaso-smt.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$("${Z3:-z3}" -smt2 "$here/smt/jaso-arith.smt2")"
echo "$out"
expected="$(printf 'unsat\n%.0s' 1 2 3 4 5 6 7 8; echo sat)"
if [ "$out" != "$expected" ]; then
    echo "FAIL: unexpected Z3 results" >&2
    exit 1
fi
echo "PASS: 8 facts proved, 1 sanity check refuted ($("${Z3:-z3}" --version))"
