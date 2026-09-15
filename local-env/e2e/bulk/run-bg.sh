#!/usr/bin/env bash
# Start a lab command detached from the caller (own session, stdin closed), for remote dispatch
# whose client may not survive a 30-60 min wait. Returns immediately. The command defaults to
# lab-up + S9; the environment (ART_ROOT, PHASES, TIER, ...) passes through. Poll with:
#
#   tail -5 "$ART_ROOT/s9-run.log"; cat "$ART_ROOT/s9-run.exit"
#
# s9-run.exit appears only when the run has finished. ART_ROOT defaults to e2e/artifacts, which
# the next rsync --delete removes; point it outside the workspace for runs worth keeping.

set -uo pipefail
cd "$(dirname "$0")/../.."
ART_ROOT="${ART_ROOT:-$PWD/e2e/artifacts}"; export ART_ROOT
mkdir -p "$ART_ROOT"
LOG="$ART_ROOT/s9-run.log"; EXIT="$ART_ROOT/s9-run.exit"
rm -f "$EXIT"
CMD="${1:-bash e2e/bulk/lab-up.sh && bash e2e/run-all.sh s9}"

nohup perl -MPOSIX=setsid -e 'setsid(); exec @ARGV' \
  bash -c "$CMD"'; echo $? > "$0"' "$EXIT" \
  > "$LOG" 2>&1 < /dev/null &
echo "started pid $!; log $LOG"
