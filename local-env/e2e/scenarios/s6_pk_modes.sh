#!/usr/bin/env bash
# S6 —— 票 #10 问题 6：无主键表用 insert.mode=insert 能否迁移；复合主键表用 upsert 是否正常。
#
# 无主键表还有一层：它没有自增列 → Source 只能走 bulk，而 #3 的关键发现是
# **bulk 模式不写 source offset**。这里顺带把 bulk 的 offset 端点实测一遍，喂给 #13。
# t_no_pk 里有两行完全重复的记录（#16 的"重复检查"在这张表上无从下手），
# 迁完要确认 PG 侧同样是 5 行、重复行原样保留 —— 不能被去重。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s6 "无主键表 insert 与复合主键表 upsert"

psqlf < "$E2E_DIR/ddl/others.sql" >> "$(art)/run.log" 2>&1

# ---------------------------------------------------------------- 无主键表
TABLE=t_no_pk; TOPIC="${TOPIC_PREFIX}${TABLE}"; SRC=s6-src-nopk; SINK=s6-sink-nopk
cleanup_link "$SRC" "$SINK" "$TOPIC" ""
psqlq "TRUNCATE $TABLE" >> "$(art)/run.log"
create_topic "$TOPIC"

put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "bulk",
  "table.whitelist": "$TABLE",
  "topic.prefix": "$TOPIC_PREFIX",
  "poll.interval.ms": 3600000, "batch.max.rows": 100, "tasks.max": 1
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

wait_task_state "$SINK" RUNNING 60 || snapshot_status "$SINK" nopk
N=$(wait_rows "$TABLE" 5 120)
snapshot_status "$SRC" nopk; snapshot_status "$SINK" nopk
finding "无主键表 \`insert.mode=insert\`：落库 **$N / 5** 行"
psqlq "SELECT count(*) FROM (SELECT event_time, source, payload FROM $TABLE
       GROUP BY 1,2,3 HAVING count(*)>1) d" > "$(art)/nopk-dupes.txt"
finding "重复行组数（源侧应为 1，被去重则为 0）：$(cat "$(art)/nopk-dupes.txt")"

# bulk 模式的 offset 端点（#3 说不写 offset；#9 实测 incrementing 也返回空）
curl -sS "$CONNECT/connectors/$SRC/offsets" > "$(art)/offsets-bulk.json"
finding "\`mode=bulk\` 的 offsets 端点：\`$(jq -c . < "$(art)/offsets-bulk.json")\`"

# poll.interval.ms=1h 也挡不住 bulk 的重复轮询？观察 30 秒看行数是否翻倍
sleep 30
M=$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')
finding "30 秒后行数 $M（若 > $N，说明 bulk 会重复整表投递 → 完成判定必须靠外部 DELETE，见 #13）"

# ------------------------------------------------------------- 复合主键表
TABLE=t_composite_pk; TOPIC="${TOPIC_PREFIX}${TABLE}"; SRC=s6-src-cpk; SINK=s6-sink-cpk
cleanup_link "$SRC" "$SINK" "$TOPIC" ""
psqlq "TRUNCATE $TABLE" >> "$(art)/run.log"
create_topic "$TOPIC"

put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "bulk",
  "table.whitelist": "$TABLE",
  "topic.prefix": "$TOPIC_PREFIX",
  "poll.interval.ms": 3600000, "batch.max.rows": 100, "tasks.max": 1
}
JSON

# pk.mode=record_value + pk.fields：Source 不产 key，主键只能从 value 里取
put_connector "$SINK" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$TOPIC", "table.name.format": "$TABLE",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "upsert", "pk.mode": "record_value",
  "pk.fields": "tenant_id,order_no",
  "batch.size": "1", "errors.tolerance": "none"
}
JSON

wait_task_state "$SINK" RUNNING 60 || snapshot_status "$SINK" cpk
N=$(wait_rows "$TABLE" 4 120)
snapshot_status "$SRC" cpk; snapshot_status "$SINK" cpk
finding "复合主键表 \`insert.mode=upsert\` + \`pk.mode=record_value\`：落库 **$N / 4** 行"
psqlq "SELECT tenant_id, order_no, amount FROM $TABLE ORDER BY 1,2" > "$(art)/cpk-rows.txt"
finding "落库内容：$(tr '\n' ' | ' < "$(art)/cpk-rows.txt")"

# 显式重启 bulk Source 触发第二次整表投递；upsert 应吸收重复记录，目标仍保持 4 行。
BEFORE_END=$(topic_end_offset "$TOPIC")
curl -sS -X POST "$CONNECT/connectors/$SRC/restart?includeTasks=true&onlyFailed=false" >/dev/null
for _ in $(seq 1 30); do
  AFTER_END=$(topic_end_offset "$TOPIC")
  [ "$AFTER_END" -ge $((BEFORE_END + 4)) ] && break
  sleep 1
done
sleep 5
AFTER_ROWS=$(psqlq "SELECT count(*) FROM $TABLE" | tr -d ' ')
printf 'before_topic_end=%s\nafter_topic_end=%s\nafter_pg_rows=%s\n' \
  "$BEFORE_END" "${AFTER_END:-$BEFORE_END}" "$AFTER_ROWS" > "$(art)/cpk-replay.txt"
finding "显式重启 bulk Source 后 topic offset ${BEFORE_END} → ${AFTER_END:-$BEFORE_END}，upsert 目标表保持 **$AFTER_ROWS** 行"

capture_connect_log s6
log "S6 完成，产物见 $(art)"
