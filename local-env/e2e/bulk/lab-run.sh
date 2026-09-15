#!/usr/bin/env bash
# One S9 run for ticket #78 as a single remote-dispatchable command: put the lab on an ADR-0031
# tier, then start S9 detached with its artifacts outside the rsynced workspace.
#
#   bash local-env/e2e/bulk/lab-run.sh <8|16> <run-name> [phases] [single tables|all] [live-gc s]
#   bash local-env/e2e/bulk/lab-run.sh 16 t16-a                      # both phases
#   bash local-env/e2e/bulk/lab-run.sh 16 smoke single b_lob_1
#
# Artifacts: ~/dbx-lab/78/<run-name>/ (s9-run.log, s9-run.exit when finished, s9/…).

set -uo pipefail
cd "$(dirname "$0")/../.."
[ $# -ge 2 ] || { echo "usage: $0 <8|16> <run-name> [phases] [single tables]"; exit 2; }

bash e2e/bulk/set-tier.sh "$1" || exit $?
export TIER="$1" ART_ROOT="$HOME/dbx-lab/78/$2" PHASES="${3:-single concurrent}"
[ -n "${4:-}" ] && [ "$4" != all ] && export SINGLE_TABLES="$4"
# 5th argument: force a full GC this often, so S9's heap check sees live heap (costs throughput)
[ -n "${5:-}" ] && export LIVE_GC_S="$5"
bash e2e/bulk/run-bg.sh "bash e2e/run-all.sh s9"
