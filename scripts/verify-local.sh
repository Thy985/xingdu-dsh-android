#!/usr/bin/env bash
# verify-local.sh —— 一键本地快速验证（docs/verification.md 矩阵的 L0 + L1 + lint）。
#
# 用法: scripts/verify-local.sh [--no-lint] [--runtime-check]
#   --no-lint        跳过 lintDebug（只跑 L0 契约 + L1 JVM 单测）
#   --runtime-check  额外校验 app/src/main/assets/runtime.zip 存在与 SHA-256
#
# 用途：代理每次改完自检、维护者每次合入前确认。它不是发布门禁——
# 发布以 CI 与 docs/verification.md 矩阵全层为准。
#
# 注意：Windows 本地 JVM 单测 57/58（POSIX FIFO 用例差异）是已知状态，
# 不要为让本脚本全绿而修改该用例（见 AGENTS.md 环境备注）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

NO_LINT=0
RUNTIME_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --no-lint) NO_LINT=1 ;;
    --runtime-check) RUNTIME_CHECK=1 ;;
    *) echo "未知参数: $arg（支持 --no-lint / --runtime-check）"; exit 2 ;;
  esac
done

echo "==> [L0] 插件契约测试"
node tests/mobile-shell-contract.cjs

if [ "$RUNTIME_CHECK" -eq 1 ]; then
  echo "==> [L0] 运行时资产校验"
  python3 scripts/runtime/verify-runtime.py app/src/main/assets/runtime.zip --arch aarch64
fi

echo "==> [L1] JVM 单测"
if [ "$NO_LINT" -eq 1 ]; then
  ./gradlew testDebugUnitTest
else
  ./gradlew testDebugUnitTest lintDebug
fi

echo "==> [L1] protocol 回放单测"
./gradlew :protocol:test

echo "PASS: verify-local 全部通过（L0 + L1$( [ "$NO_LINT" -eq 0 ] && echo ' + lint' )）"
