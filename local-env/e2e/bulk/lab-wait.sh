#!/usr/bin/env bash
# Wait for a run started by lab-run.sh, then print a short receipt: its exit code, the log tail,
# and S9's FINDINGS. Exits with the run's exit code, or 124 if it is still running at the timeout.
#
#   bash local-env/e2e/bulk/lab-wait.sh <run-name> [timeout seconds, default 5400]

set -uo pipefail
[ $# -ge 1 ] || { echo "usage: $0 <run-name> [timeout-s]"; exit 2; }
dir="$HOME/dbx-lab/78/$1"; limit="${2:-5400}"; waited=0

until [ -f "$dir/s9-run.exit" ]; do
  [ "$waited" -ge "$limit" ] && { echo "still running after ${limit}s"; tail -5 "$dir/s9-run.log"; exit 124; }
  sleep 15; waited=$(( waited + 15 ))
done

ec=$(cat "$dir/s9-run.exit")
echo "run $1 exit=$ec after waiting ${waited}s"
grep -E '^\[' "$dir/s9-run.log" | tail -8
echo "---- FINDINGS"
sed -n '5,$p' "$dir/s9/FINDINGS.md"
exit "$ec"
