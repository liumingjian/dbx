#!/usr/bin/env bash
# 票 #10「端到端最小链路验证」的全套 scenario。
#
#   cd local-env && docker compose up -d      # 先等 5/5 healthy
#   ./e2e/run-all.sh                          # 全跑
#   ./e2e/run-all.sh s5 s7                    # 只跑指定几条
#
# 每条 scenario 独立、可重复执行、自己清理自己的 connector/topic/目标表。
# 产物落在 e2e/artifacts/<sid>/，其中 FINDINGS.md 是给人看的结论，
# 其余（trace-*.txt / connect-log-*.txt / avro-*.json）是原始证据，一律不要手改。

set -uo pipefail
cd "$(dirname "$0")/.."

ALL=(s1 s2 s3 s5 s6 s7 s8)
declare -A SCRIPT=(
  [s1]=s1_types.sh
  [s2]=s2_numeric_mapping.sh
  [s3]=s3_datetime.sh
  [s5]=s5_large_fields.sh
  [s6]=s6_pk_modes.sh
  [s7]=s7_mismatch.sh
  [s8]=s8_completion_signals.sh
)

source ./e2e/lib.sh
preflight

TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL[@]}")

for sid in "${TARGETS[@]}"; do
  s="${SCRIPT[$sid]:-}"
  [ -z "$s" ] && { echo "未知 scenario：$sid"; exit 1; }
  echo "════════════════ $sid ════════════════"
  bash "./e2e/scenarios/$s" || echo "!! $sid 非零退出，产物仍已落盘"
done

echo "════════════════ 汇总 ════════════════"
for sid in "${TARGETS[@]}"; do
  f="./e2e/artifacts/$sid/FINDINGS.md"
  [ -f "$f" ] && { echo; cat "$f"; }
done
