#!/usr/bin/env bash
# S1 —— 票 #10 问题 1 与 4：按矩阵手工建表 + Source(Avro) + Sink(auto.create=false)，
# 数据能否正确落地；utf8mb4 中文与 emoji 是否无损。
#
# 这条是全票的主链路。它跑通，#12 的"DDL 生成器与 Sink 写入契约一致性"才有立足点。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s1 "t_types 全类型主链路 + utf8mb4"

TABLE=t_types
TOPIC="${TOPIC_PREFIX}${TABLE}"
SRC=s1-src; SINK=s1-sink

cleanup_link "$SRC" "$SINK" "$TOPIC" "$TABLE"
create_topic "$TOPIC"
psqlf < "$E2E_DIR/ddl/t_types.sql" >> "$(art)/run.log" 2>&1

put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "table.whitelist": "$TABLE",
  "topic.prefix": "$TOPIC_PREFIX",
  "poll.interval.ms": 1000, "batch.max.rows": 100, "tasks.max": 1
}
JSON

put_connector "$SINK" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$TOPIC",
  "table.name.format": "$TABLE",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "insert", "pk.mode": "none",
  "batch.size": "1", "errors.tolerance": "none"
}
JSON

wait_task_state "$SINK" RUNNING 60 || log "Sink task 未进入 RUNNING"
N=$(wait_rows "$TABLE" 4 20)
log "PG t_types 行数：$N / 4"
finding "落库行数 $N / 4"

snapshot_status "$SRC"
if [ "$N" -lt 4 ]; then
  wait_task_state "$SINK" FAILED 60 || true
fi
snapshot_status "$SINK"
capture_avro_schema "$TOPIC"
diff_columns "$TABLE" "$TOPIC"
capture_connect_log s1

# ---- 逐列比对，别只看行数（#5：静默丢列会让任务全绿而该列全 NULL）----
psqlq "SELECT id, c_decimal, c_bigint_u, c_double, c_float FROM $TABLE ORDER BY id" \
  > "$(art)/pg-numeric.txt"
mysqlq "SELECT id, c_decimal, c_bigint_u, c_double, c_float FROM $TABLE ORDER BY id" \
  > "$(art)/mysql-numeric.txt"

# 全列 NULL 计数：只有主链路完整落库时才用于判断静默丢列。
psqlq "SELECT string_agg(c || '=' || n, ' ') FROM (
         SELECT 'c_bit1' c, count(c_bit1) n FROM $TABLE
   UNION SELECT 'c_set',    count(c_set)    FROM $TABLE
   UNION SELECT 'c_year',   count(c_year)   FROM $TABLE
   UNION SELECT 'c_enum',   count(c_enum)   FROM $TABLE
   UNION SELECT 'c_json',   count(c_json::text) FROM $TABLE
   UNION SELECT 'c_varbinary', count(c_varbinary) FROM $TABLE) t" \
  > "$(art)/pg-suspect-nonnull-counts.txt"
if [ "$N" -eq 4 ]; then
  finding "可疑列非空计数（MySQL 侧应为 3）：$(cat "$(art)/pg-suspect-nonnull-counts.txt")"
else
  finding "主链路未完整落库，不能用 PG 非空计数判断静默丢列；以 Avro schema 差集和失败 trace 为准"
fi

# utf8mb4 与 JSON cast 使用隔离 query 复核，避免 BIGINT UNSIGNED / BIT(1) 的已知失败遮蔽无关断言。
SAFE_TABLE=t_types_s1_safe
SAFE_TOPIC=dbx.s1.safe.t_types
SAFE_SRC=s1-src-safe; SAFE_SINK=s1-sink-safe
cleanup_link "$SAFE_SRC" "$SAFE_SINK" "$SAFE_TOPIC" "$SAFE_TABLE"
create_topic "$SAFE_TOPIC"
psqlq "CREATE TABLE $SAFE_TABLE (
         id integer PRIMARY KEY,
         c_varchar varchar(255),
         c_json json,
         c_bit1 smallint,
         c_year date,
         c_set text)" >> "$(art)/run.log" 2>&1

put_connector "$SAFE_SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "query": "SELECT id, c_varchar, c_json, c_bit1, c_year, c_set FROM t_types",
  "topic.prefix": "$SAFE_TOPIC",
  "poll.interval.ms": 1000, "batch.max.rows": 100, "tasks.max": 1
}
JSON

put_connector "$SAFE_SINK" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$SAFE_TOPIC", "table.name.format": "$SAFE_TABLE",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "insert", "pk.mode": "none",
  "batch.size": "1", "errors.tolerance": "none"
}
JSON

SAFE_N=$(wait_rows "$SAFE_TABLE" 4 60)
snapshot_status "$SAFE_SRC" safe; snapshot_status "$SAFE_SINK" safe
finding "隔离 query（utf8mb4 + JSON + 可疑列）落库 $SAFE_N / 4 行"
psqlq "SELECT count(c_bit1), count(c_set), count(c_year) FROM $SAFE_TABLE" > "$(art)/pg-suspect-nonnull-counts-safe.txt"
finding "隔离链路 c_bit1 / c_set / c_year 非空计数：$(cat "$(art)/pg-suspect-nonnull-counts-safe.txt")（源侧均为 3）"

psqlq "SELECT c_varchar FROM $SAFE_TABLE WHERE id=2" > "$(art)/pg-utf8mb4.txt"
mysqlq "SELECT c_varchar FROM t_types WHERE id=2"     > "$(art)/mysql-utf8mb4.txt"
if diff -q "$(art)/pg-utf8mb4.txt" "$(art)/mysql-utf8mb4.txt" >/dev/null; then
  finding "utf8mb4 中文与 emoji **逐字节一致**（id=2 的 c_varchar）"
else
  finding "**utf8mb4 不一致**！见 pg-utf8mb4.txt / mysql-utf8mb4.txt"
fi

psqlq "SELECT c_json::text::jsonb FROM $SAFE_TABLE WHERE id=2" > "$(art)/pg-json-normalized.txt"
mysqlq "SELECT CAST(c_json AS CHAR) FROM t_types WHERE id=2"    > "$(art)/mysql-json.txt"
if diff -q "$(art)/pg-json-normalized.txt" "$(art)/mysql-json.txt" >/dev/null; then
  finding "MySQL JSON → PostgreSQL json cast 写入成功且内容一致"
else
  finding "MySQL JSON → PostgreSQL json cast 可写入，但字符串内容 **不一致**；见 pg-json-normalized.txt / mysql-json.txt"
fi

# ---- DECIMAL(38,10) 端到端（问题 2 的基线，numeric.mapping 变体见 S2）----
if [ "$N" -eq 4 ] && diff -q "$(art)/pg-numeric.txt" "$(art)/mysql-numeric.txt" >/dev/null; then
  finding "数值列（含 DECIMAL(38,10)、BIGINT UNSIGNED）文本表示完全一致"
else
  finding "**全类型主链路数值列未完成比对**，见 Source 溢出 trace 与 pg/mysql-numeric.txt"
  diff "$(art)/mysql-numeric.txt" "$(art)/pg-numeric.txt" > "$(art)/numeric.diff" || true
fi

capture_connect_log s1
log "S1 完成，产物见 $(art)"
