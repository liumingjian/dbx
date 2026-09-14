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
#
# Must stay runnable on macOS's stock bash 3.2: no associative arrays.

set -uo pipefail
cd "$(dirname "$0")/.."

ALL=(s1 s2 s3 s5 s6 s7 s8)
script_of() {
  case "$1" in
    s1) echo s1_types.sh ;;
    s2) echo s2_numeric_mapping.sh ;;
    s3) echo s3_datetime.sh ;;
    s5) echo s5_large_fields.sh ;;
    s6) echo s6_pk_modes.sh ;;
    s7) echo s7_mismatch.sh ;;
    s8) echo s8_completion_signals.sh ;;
    # Opt-in only, not in ALL: needs ./e2e/bulk/seed-bulk.sh first and runs for tens of minutes (ticket #63)
    s9) echo s9_throughput.sh ;;
  esac
}

source ./e2e/lib.sh
preflight

TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL[@]}")

for sid in "${TARGETS[@]}"; do
  s="$(script_of "$sid")"
  [ -z "$s" ] && { echo "未知 scenario：$sid"; exit 1; }
  echo "════════════════ $sid ════════════════"
  "$BASH" "./e2e/scenarios/$s" || echo "!! $sid 非零退出，产物仍已落盘"
done

echo "════════════════ 汇总 ════════════════"
for sid in "${TARGETS[@]}"; do
  f="./e2e/artifacts/$sid/FINDINGS.md"
  [ -f "$f" ] && { echo; cat "$f"; }
done
