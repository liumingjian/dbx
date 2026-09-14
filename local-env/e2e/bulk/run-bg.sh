#!/usr/bin/env bash
# Start lab-up + S9 detached from the caller (own session, stdin closed), for remote dispatch
# whose client may not survive a 30-60 min wait. Returns immediately. Poll with:
#
#   tail -5 local-env/e2e/artifacts/s9-run.log; cat local-env/e2e/artifacts/s9-run.exit
#
# s9-run.exit appears only when the run has finished.

set -uo pipefail
cd "$(dirname "$0")/../.."
mkdir -p e2e/artifacts
LOG="$PWD/e2e/artifacts/s9-run.log"; EXIT="$PWD/e2e/artifacts/s9-run.exit"
rm -f "$EXIT"

nohup perl -MPOSIX=setsid -e 'setsid(); exec @ARGV' \
  bash -c 'bash e2e/bulk/lab-up.sh && bash e2e/run-all.sh s9; echo $? > "$0"' "$EXIT" \
  > "$LOG" 2>&1 < /dev/null &
echo "started pid $!; log $LOG"
