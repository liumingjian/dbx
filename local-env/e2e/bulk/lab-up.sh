#!/usr/bin/env bash
# Bring the lab up and seed the bulk dataset for S9 (ticket #63). Prints only a short
# receipt; the full logs go to /tmp/dbx-lab-up.log and /tmp/dbx-seed.log.
#
#   bash local-env/e2e/bulk/lab-up.sh && bash local-env/e2e/run-all.sh s9

set -uo pipefail
cd "$(dirname "$0")/../.."

bash --version | head -1
(./fetch-plugins.sh && docker compose build connect && docker compose up -d --wait) > /tmp/dbx-lab-up.log 2>&1
ec=$?; tail -8 /tmp/dbx-lab-up.log
[ $ec = 0 ] || exit $ec

start=$(date +%s)
bash ./e2e/bulk/seed-bulk.sh > /tmp/dbx-seed.log 2>&1
ec=$?
tr '\r' '\n' < /tmp/dbx-seed.log | grep -v '^    b_' | tail -20
echo "seed exit=$ec in $(( $(date +%s) - start ))s"
docker system df | head -5
df -h ~ | tail -1
exit $ec
