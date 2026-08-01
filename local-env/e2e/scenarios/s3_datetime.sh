#!/usr/bin/env bash
# S3 —— 票 #10 问题 3：DATETIME / TIMESTAMP 端到端时区是否正确。
#
# 两个已知的坑要在这里现形：
#  1. #5：DATETIME 与 TIMESTAMP 在 Connect 层不可区分（都落 org.apache.kafka.connect.data.Timestamp），
#     但 MySQL 侧 TIMESTAMP 是带时区语义的（存 UTC、按 session 时区显示），DATETIME 是墙上时钟。
#     一旦 Source 的 db.timezone 与 MySQL session 时区不一致，DATETIME 会被平移，TIMESTAMP 反而对。
#  2. #11：微秒不支持，timestamp.granularity=connect_logical → 毫秒截断，DDL 落 timestamp(3)。
#
# 做法：同一张表跑两遍，db.timezone 分别取 UTC 与 Asia/Shanghai，比对落库值。
# 差值就是产品必须替 DBA 决定的那个参数（他不该看见这个选项）。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s3 "DATETIME/TIMESTAMP 的时区与精度"

finding "MySQL 会话时区：\`$(mysqlq "SELECT @@global.time_zone, @@session.time_zone")\`"
finding "MySQL 源值：$(mysqlq "SELECT id, c_datetime6, c_timestamp6 FROM t_types ORDER BY id" | tr '\n' ' | ')"

for TZ in UTC Asia/Shanghai; do
  TAG="${TZ//\//-}"
  PREFIX="dbx.s3.${TAG}."
  TOPIC="${PREFIX}t_types"
  TABLE="t_types_s3_${TAG//-/_}"
  SRC="s3-src-$TAG"; SINK="s3-sink-$TAG"

  cleanup_link "$SRC" "$SINK" "$TOPIC" "$TABLE"
  create_topic "$TOPIC"
  psqlq "CREATE TABLE $TABLE (
           id integer PRIMARY KEY,
           c_datetime6  timestamp(3),
           c_timestamp6 timestamp(3))" >> "$(art)/run.log"

  # 只取三列 → 走 query 模式（#17：列裁剪与列改名共用「单独成箱 + query」这条路径）
  put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "query": "SELECT id, c_datetime6, c_timestamp6 FROM t_types",
  "topic.prefix": "$TOPIC",
  "db.timezone": "$TZ",
  "timestamp.granularity": "connect_logical",
  "poll.interval.ms": 1000, "batch.max.rows": 100, "tasks.max": 1
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
  "batch.size": "1", "errors.tolerance": "none"
}
JSON

  wait_task_state "$SINK" RUNNING 60 || snapshot_status "$SINK" "$TAG"
  N=$(wait_rows "$TABLE" 4 120)
  snapshot_status "$SRC" "$TAG"; snapshot_status "$SINK" "$TAG"
  psqlq "SELECT id, c_datetime6, c_timestamp6 FROM $TABLE ORDER BY id" > "$(art)/pg-$TAG.txt"
  finding "\`db.timezone=$TZ\` → 落库 $N 行：$(tr '\n' ' | ' < "$(art)/pg-$TAG.txt")"
done

if diff -q "$(art)/pg-UTC.txt" "$(art)/pg-Asia-Shanghai.txt" >/dev/null 2>&1; then
  finding "两种 db.timezone 落库结果**相同** → 该参数在本链路上不影响 DATETIME/TIMESTAMP"
else
  finding "**两种 db.timezone 落库结果不同** → 平台必须显式固定该参数，不能留给 DBA"
  diff "$(art)/pg-UTC.txt" "$(art)/pg-Asia-Shanghai.txt" > "$(art)/tz.diff" || true
fi

capture_connect_log s3
log "S3 完成，产物见 $(art)"
