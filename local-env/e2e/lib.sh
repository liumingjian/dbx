# DBX #10 端到端最小链路原型：公共函数
#
# 所有 scenario 脚本 source 本文件。约定：
#   - 每个 scenario 有一个 SID（s1..s8），产物一律落在 artifacts/$SID/
#   - 任何一条命令的原始输出都要落盘，尤其是失败时的 trace —— 那是 #19 的素材
#   - scenario 必须可重复执行：开头先 cleanup，不依赖上一次的残留

set -uo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="$(dirname "$E2E_DIR")"
ART_ROOT="$E2E_DIR/artifacts"

CONNECT=http://localhost:8083
SR=http://localhost:8081
TOPIC_PREFIX="dbx.dbx_src."
MAX_MSG=26214400          # 25MiB，与 compose 里 broker 级一致（#6 §1.2）

dc() { docker compose -f "$ENV_DIR/docker-compose.yml" "$@"; }

art() { echo "$ART_ROOT/$SID"; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$(art)/run.log"; }

# 记一条事实到 FINDINGS.md：scenario 的结论都从这里汇总，不靠人事后回忆
finding() { printf -- '- %s\n' "$*" >> "$(art)/FINDINGS.md"; }

init_scenario() {
  SID="$1"; shift
  rm -rf "$(art)"; mkdir -p "$(art)"
  : > "$(art)/run.log"
  { echo "# $SID —— $*"; echo; echo "实测时间：$(date -Iseconds)"; echo; } > "$(art)/FINDINGS.md"
  log "scenario 开始：$*"
}

mysqlq() { dc exec -T mysql mysql -N -B -udbx -pdbx dbx_src -e "$1" 2>/dev/null; }
psqlq()  { dc exec -T postgres psql -qtAX -U dbx -d dbx_target -c "$1"; }
psqlf()  { dc exec -T postgres psql -v ON_ERROR_STOP=1 -U dbx -d dbx_target; }

create_topic() {
  local topic="$1"
  dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1 \
    --config max.message.bytes=$MAX_MSG \
    --config segment.bytes=268435456 \
    --config retention.ms=3600000 \
    --config cleanup.policy=delete >> "$(art)/run.log" 2>&1
}

delete_topic() {
  dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --topic "$1" >/dev/null 2>&1 || true
}

# topic 末端 offset。完成判定的主信号之一（#3），S8 靠它画时间线
topic_end_offset() {
  dc exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic "$1" --time -1 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'
}

# 建 connector。配置 JSON 从 stdin 读，原样存档 —— 方案书要贴的就是这份
put_connector() {
  local name="$1"
  local cfg; cfg="$(cat)"
  echo "$cfg" > "$(art)/connector-$name.json"
  curl -sS -X PUT "$CONNECT/connectors/$name/config" \
    -H 'Content-Type: application/json' -d "$cfg" > "$(art)/put-$name.json"
  log "PUT connector $name -> $(jq -r '.error_code // "created"' < "$(art)/put-$name.json")"
}

delete_connector() { curl -sS -X DELETE "$CONNECT/connectors/$1" >/dev/null 2>&1 || true; }

status_json() { curl -sS "$CONNECT/connectors/$1/status"; }

# 抓状态快照；FAILED 时把 trace 单独抽成 .trace.txt（错误翻译层的原料要能直接喂）
snapshot_status() {
  local name="$1" tag="${2:-}"
  local out="$(art)/status-$name${tag:+-$tag}.json"
  status_json "$name" > "$out"
  local trace
  trace="$(jq -r '[.connector, .tasks[]?] | map(select(.state=="FAILED") | .trace) | .[]?' < "$out")"
  if [ -n "$trace" ]; then
    echo "$trace" > "$(art)/trace-$name${tag:+-$tag}.txt"
    log "FAILED：trace 已存 trace-$name${tag:+-$tag}.txt（前 3 行）"
    echo "$trace" | head -3 | tee -a "$(art)/run.log"
  fi
}

# 等 connector 的 task 进入某状态；返回 0=达成，1=超时
wait_task_state() {
  local name="$1" want="$2" timeout="${3:-60}" i=0
  while [ $i -lt "$timeout" ]; do
    local st; st="$(status_json "$name" | jq -r '.tasks[0].state // "PENDING"')"
    [ "$st" = "$want" ] && return 0
    [ "$st" = "FAILED" ] && [ "$want" != "FAILED" ] && return 1
    sleep 1; i=$((i+1))
  done
  return 1
}

# 等目标表行数达到 want；返回实际行数
wait_rows() {
  local table="$1" want="$2" timeout="${3:-120}" i=0 n=0
  while [ $i -lt "$timeout" ]; do
    n="$(psqlq "SELECT count(*) FROM $table" | tr -d ' \r')"
    [ "${n:-0}" -ge "$want" ] && break
    sleep 2; i=$((i+2))
  done
  echo "${n:-0}"
}

# Connect worker 日志切片。REST 的 trace 只有最后一跳，根因常在 worker 日志里
capture_connect_log() {
  dc logs --no-color --tail "${2:-400}" connect > "$(art)/connect-log-$1.txt" 2>&1
}

# Connect 推给 Schema Registry 的 Avro schema —— 类型映射（#11）的一手证据
capture_avro_schema() {
  local topic="$1"
  curl -sS "$SR/subjects/$topic-value/versions/latest" \
    | jq -r '.schema' | jq . > "$(art)/avro-$topic.json" 2>/dev/null \
    || echo '{}' > "$(art)/avro-$topic.json"
}

# 源表列名 vs Avro 字段名的差集 = 静默丢列（#5 的头号风险）
diff_columns() {
  local table="$1" topic="$2"
  mysqlq "SELECT column_name FROM information_schema.columns
          WHERE table_schema='dbx_src' AND table_name='$table' ORDER BY column_name" \
    | tr -d '\r' | sort > "$(art)/cols-mysql-$table.txt"
  jq -r '.fields[].name' < "$(art)/avro-$topic.json" 2>/dev/null \
    | sort > "$(art)/cols-avro-$table.txt"
  comm -23 "$(art)/cols-mysql-$table.txt" "$(art)/cols-avro-$table.txt" \
    > "$(art)/cols-dropped-$table.txt"
  if [ -s "$(art)/cols-dropped-$table.txt" ]; then
    finding "**静默丢列**：$table 有列未进 Avro schema → $(tr '\n' ' ' < "$(art)/cols-dropped-$table.txt")"
  else
    finding "$table 的列全部进了 Avro schema，无静默丢列"
  fi
}

cleanup_link() {
  local src="$1" sink="$2" topic="$3" table="${4:-}"
  delete_connector "$src"; delete_connector "$sink"
  sleep 2
  delete_topic "$topic"
  [ -n "$table" ] && psqlq "DROP TABLE IF EXISTS $table" >/dev/null 2>&1
  return 0
}

preflight() {
  command -v jq >/dev/null || { echo "缺 jq"; exit 1; }
  curl -sf "$CONNECT/connectors" >/dev/null || { echo "Connect 未就绪（$CONNECT）；先 docker compose up -d"; exit 1; }
  curl -sf "$SR/subjects"     >/dev/null || { echo "Schema Registry 未就绪（$SR）"; exit 1; }
}
