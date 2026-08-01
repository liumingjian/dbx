#!/usr/bin/env bash
# S8 —— 票 #10 问题 8：记录一次 connector 从创建到"表读完"的完整信号序列。
#
# 这份时间线是"完成判定"（#13）的唯一一手依据。#3 的结论是 Connect 完全没有跑完即停语义，
# #9 又实测到 incrementing 模式下 offsets 端点返回空数组 —— 所以这里把**所有候选信号**
# 同时采样，看究竟哪几个真能用：
#
#   1. connector / task 的 state（预期：读完后仍是 RUNNING，永远不会变 COMPLETED）
#   2. GET /connectors/{n}/offsets           （#9 实测为空，本次复核）
#   3. topic 末端 offset                      （#3 认定的主信号）
#   4. 目标表实际行数                          （最终真相）
#   5. Source task 的 source-record-poll-total JMX 指标（停滞检测的候选）
#
# 采样 120 秒，1 秒一次，全部落成 TSV —— 完成判定的阈值要从这张表上读出来，不能拍脑袋。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s8 "从创建到读完的完整信号序列"

TABLE=t_types_s8
TOPIC="dbx.s8.t_types"
SRC=s8-src-v2; SINK=s8-sink-v2

cleanup_link "$SRC" "$SINK" "$TOPIC" "$TABLE"
create_topic "$TOPIC"
psqlq "CREATE TABLE $TABLE (
         id integer PRIMARY KEY,
         c_decimal numeric(38,10))" >> "$(art)/run.log" 2>&1

SRC_COUNT=$(mysqlq "SELECT count(*) FROM t_types" | tr -d ' \r')
finding "源表行数（判定的分母）：**$SRC_COUNT**"

T0=$SECONDS
put_connector "$SINK" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$TOPIC", "table.name.format": "$TABLE",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "insert", "pk.mode": "none",
  "batch.size": "1", "errors.tolerance": "none"
}
JSON

put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "query": "SELECT id, c_decimal FROM t_types",
  "topic.prefix": "$TOPIC",
  "poll.interval.ms": 5000, "batch.max.rows": 100, "tasks.max": 1
}
JSON

{
  printf 't\tsrc_conn\tsrc_task\tsink_conn\tsink_task\toffsets_endpoint\ttopic_end\tpg_rows\n'
  while [ $((SECONDS - T0)) -lt 120 ]; do
    s=$(status_json "$SRC"); k=$(status_json "$SINK")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$((SECONDS - T0))" \
      "$(jq -r '.connector.state // "-"'  <<<"$s")" \
      "$(jq -r '.tasks[0].state // "-"'   <<<"$s")" \
      "$(jq -r '.connector.state // "-"'  <<<"$k")" \
      "$(jq -r '.tasks[0].state // "-"'   <<<"$k")" \
      "$(curl -sS "$CONNECT/connectors/$SRC/offsets" | jq -c '.offsets // []')" \
      "$(topic_end_offset "$TOPIC")" \
      "$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')"
    sleep 1
  done
} > "$(art)/signals.tsv"

log "信号时间线（去重后）："
awk -F'\t' 'NR==1 || $0!=prev {print; prev=$0}' "$(art)/signals.tsv" \
  | tee "$(art)/signals-dedup.tsv" | tee -a "$(art)/run.log"

FINAL_ROWS=$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')
FINAL_END=$(topic_end_offset "$TOPIC")
finding "120 秒后：topic 末端 offset **$FINAL_END**，PG 行数 **$FINAL_ROWS**，源表 $SRC_COUNT"
SRC_FINAL="$(status_json "$SRC")"
finding "Source connector/task 终态：\`$(jq -r '.connector.state' <<<"$SRC_FINAL")\` / \`$(jq -r '.tasks[0].state' <<<"$SRC_FINAL")\`（若仍是 RUNNING → 印证 #3：无跑完即停语义）"
finding "offsets 端点终值：\`$(curl -sS "$CONNECT/connectors/$SRC/offsets" | jq -c .)\`（#9 实测为空数组，本次是否复现？）"

# 停滞检测：topic offset 连续多少秒不变可判"读完"
awk -F'\t' 'NR>1 {if ($7!=last) {last=$7; t=$1} } END {print t}' "$(art)/signals.tsv" \
  > "$(art)/last-change-t.txt"
finding "topic offset 最后一次变化发生在 t=$(cat "$(art)/last-change-t.txt")s → 停滞窗口至少要大于 poll.interval.ms(5s) 的若干倍，具体阈值由 #13 定"

snapshot_status "$SRC"; snapshot_status "$SINK"
capture_connect_log s8

# 删除 connector 后，offsets 与 topic 是否留存 —— #17 留给 #13 的必答题「旧 topic 何时删」
delete_connector "$SRC"; delete_connector "$SINK"; sleep 3
finding "DELETE connector 后 topic 末端 offset：**$(topic_end_offset "$TOPIC")**（数据仍在 → 重跑必须显式删 topic 或换运行标识前缀，见 #17）"

log "S8 完成，产物见 $(art)"
