#!/usr/bin/env bash
# Print one lab-run.sh run's compact evidence as a base64 gzip tarball, so a remote caller can
# rebuild it on its side (remote dispatch syncs one way only). Keeps FINDINGS, env, results,
# boxes, timelines, heap checks, RSS, temp-table and docker-stats samples, connector configs,
# and any failure traces or histograms. Leaves out raw GC logs and Connect log slices.
#
#   bash local-env/e2e/bulk/lab-evidence.sh <run-name>  > run.b64
#   base64 -d run.b64 | tar -xz                          # → <run-name>/...

set -uo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <run-name>" >&2; exit 2; }
cd "$HOME/dbx-lab/78" || exit 1
[ -d "$1/s9" ] || { echo "no run $1" >&2; exit 1; }

tar -cz -C . \
  --exclude 'gc-*.log' --exclude 'connect-log-*' --exclude 'put-*.json' --exclude 'run.log' \
  "$1/s9" | base64
