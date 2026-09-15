#!/usr/bin/env bash
# Put the lab on one of ADR-0031's deployment memory tiers (ticket #78): resize the Docker Desktop
# VM, then recreate the stack with that tier's heaps. macOS with Docker Desktop only.
#
#   bash local-env/e2e/bulk/set-tier.sh 8     # 8 GiB VM, Connect heap 3 GiB, Kafka heap 1 GiB
#   bash local-env/e2e/bulk/set-tier.sh 16    # 16 GiB VM, Connect heap 6 GiB, Kafka heap 2 GiB
#
# The heaps reach docker-compose.yml as DBX_CONNECT_HEAP / DBX_KAFKA_HEAP. S9 only restarts
# containers, which keeps them; a later plain `docker compose up` falls back to the 16 GiB tier.

set -uo pipefail
cd "$(dirname "$0")/../.."

case "${1:-}" in
  8)  mem=8192;  connect=3g; kafka=1g ;;
  16) mem=16384; connect=6g; kafka=2g ;;
  *)  echo "usage: $0 8|16"; exit 2 ;;
esac

settings="$HOME/Library/Group Containers/group.com.docker/settings-store.json"
cur=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("MemoryMiB", ""))' "$settings")
if [ "$cur" != "$mem" ]; then
  echo "Docker VM ${cur} MiB -> ${mem} MiB; restarting Docker Desktop"
  python3 - "$settings" "$mem" <<'PY'
import json, sys
path, mem = sys.argv[1], int(sys.argv[2])
d = json.load(open(path))
d["MemoryMiB"] = mem
json.dump(d, open(path, "w"), indent=2)
PY
  docker desktop restart >/dev/null 2>&1 || { osascript -e 'quit app "Docker"'; sleep 15; open -a Docker; }
  for _ in $(seq 1 90); do docker info >/dev/null 2>&1 && break; sleep 5; done
fi

DBX_CONNECT_HEAP=$connect DBX_KAFKA_HEAP=$kafka docker compose up -d --wait > /tmp/dbx-set-tier.log 2>&1
ec=$?; tail -3 /tmp/dbx-set-tier.log
docker info --format 'Docker VM: {{.MemTotal}} B, {{.NCPU}} vCPU'
docker compose exec -T connect jcmd 1 VM.flags 2>/dev/null | tr ' ' '\n' | grep -E '^-XX:MaxHeapSize'
exit $ec
