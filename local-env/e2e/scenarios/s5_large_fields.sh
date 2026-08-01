#!/usr/bin/env bash
# S5 —— 票 #10 问题 5：1MiB / 19MiB 大字段能否通过；25MiB 的在**哪一环**炸、报**什么错**。
#
# 本 scenario 是全票价值最高的一条：那条 25MiB 行的错误原文是错误翻译层（#19）的头号素材，
# 也是大字段策略（#15）"预检拦在建表审核阶段"这一决定的依据。
#
# t_large_blob 四行按 id 递增：32KiB / 1MiB / 19MiB / 25MiB（超限）。
# incrementing 模式按 id 顺序读 → 前三行应当落库，第四行触发失败，落库停在 3。
# 关键要抓清楚：是 **Source producer 侧**（RecordTooLargeException）还是 **Sink consumer 侧**炸的。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s5 "大字段 1MiB/19MiB 通过与 25MiB 的失败形态"

TABLE=t_large_blob
TOPIC="${TOPIC_PREFIX}${TABLE}"
SRC=s5-src; SINK=s5-sink

cleanup_link "$SRC" "$SINK" "$TOPIC" ""
psqlf < "$E2E_DIR/ddl/others.sql" >> "$(art)/run.log" 2>&1
create_topic "$TOPIC"

finding "MySQL 源侧各行字节数：$(mysqlq "SELECT id, label, COALESCE(LENGTH(c_blob),0), COALESCE(LENGTH(c_longblob),0) FROM $TABLE ORDER BY id" | tr '\n' ' | ')"

# batch.max.rows=1：含大字段的表必须压到最低（#6：500×20MB 必 OOM）
put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "table.whitelist": "$TABLE",
  "topic.prefix": "$TOPIC_PREFIX",
  "poll.interval.ms": 1000, "batch.max.rows": 1, "tasks.max": 1
}
JSON

put_connector "$SINK" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$TOPIC", "table.name.format": "$TABLE",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "insert", "pk.mode": "none",
  "batch.size": "1", "errors.tolerance": "none",
  "consumer.override.max.poll.records": "1",
  "consumer.override.max.partition.fetch.bytes": "$MAX_MSG"
}
JSON

# 观察 90 秒：每 5 秒记一次 topic offset / PG 行数 / 两端状态
{
  printf 't\ttopic_end_offset\tpg_rows\tsrc_task\tsink_task\n'
  for i in $(seq 0 5 90); do
    printf '%s\t%s\t%s\t%s\t%s\n' "$i" \
      "$(topic_end_offset "$TOPIC")" \
      "$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')" \
      "$(status_json "$SRC"  | jq -r '.tasks[0].state // "-"')" \
      "$(status_json "$SINK" | jq -r '.tasks[0].state // "-"')"
    sleep 5
  done
} > "$(art)/timeline.tsv"
log "时间线："; cat "$(art)/timeline.tsv" | tee -a "$(art)/run.log"

snapshot_status "$SRC"; snapshot_status "$SINK"
capture_connect_log s5 1500

ROWS=$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')
finding "落库行数 **$ROWS**（期望 3：32KiB/1MiB/19MiB 通过，25MiB 被拦）"
psqlq "SELECT id, label, octet_length(c_blob), octet_length(c_longblob) FROM $TABLE ORDER BY id" \
  > "$(art)/pg-sizes.txt"
finding "落库字节数：$(tr '\n' ' | ' < "$(art)/pg-sizes.txt")"

# 从 worker 日志里捞出与超限有关的异常原文 —— #19 的直接素材
grep -nE 'RecordTooLarge|MESSAGE_TOO_LARGE|message.max.bytes|max.request.size|larger than|OutOfMemory' \
  "$(art)/connect-log-s5.txt" > "$(art)/oversize-evidence.txt" 2>/dev/null || true
if [ -s "$(art)/oversize-evidence.txt" ]; then
  finding "超限异常原文（前 5 行）："
  head -5 "$(art)/oversize-evidence.txt" | sed 's/^/    /' >> "$(art)/FINDINGS.md"
  finding "完整原文见 \`oversize-evidence.txt\` 与 \`connect-log-s5.txt\`，应整份归档为 #19 的测试夹具"
else
  finding "**日志里没抓到超限异常** —— 25MiB 行要么被静默丢弃（最坏情况），要么根本没被读到；须人工核对 timeline.tsv 与 connect-log-s5.txt"
fi

finding "判定要点：失败发生在 **Source producer 侧还是 Sink consumer 侧**？前者意味着预检必须在源端做（#15 的 SELECT MAX(LENGTH) 路线成立），后者意味着数据已进 Kafka、清理成本更高。"

log "S5 完成，产物见 $(art)"
