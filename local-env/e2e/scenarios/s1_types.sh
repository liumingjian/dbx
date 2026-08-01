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
N=$(wait_rows "$TABLE" 4 120)
log "PG t_types 行数：$N / 4"
finding "落库行数 $N / 4"

snapshot_status "$SRC"; snapshot_status "$SINK"
capture_avro_schema "$TOPIC"
diff_columns "$TABLE" "$TOPIC"
capture_connect_log s1

# ---- 逐列比对，别只看行数（#5：静默丢列会让任务全绿而该列全 NULL）----
psqlq "SELECT id, c_decimal, c_bigint_u, c_double, c_float FROM $TABLE ORDER BY id" \
  > "$(art)/pg-numeric.txt"
mysqlq "SELECT id, c_decimal, c_bigint_u, c_double, c_float FROM $TABLE ORDER BY id" \
  > "$(art)/mysql-numeric.txt"

# 全列 NULL 计数：某列在 PG 侧 4 行全 NULL 而 MySQL 侧不是 → 就是静默丢列的现场
psqlq "SELECT string_agg(c || '=' || n, ' ') FROM (
         SELECT 'c_bit1' c, count(c_bit1) n FROM $TABLE
   UNION SELECT 'c_set',    count(c_set)    FROM $TABLE
   UNION SELECT 'c_year',   count(c_year)   FROM $TABLE
   UNION SELECT 'c_enum',   count(c_enum)   FROM $TABLE
   UNION SELECT 'c_json',   count(c_json::text) FROM $TABLE
   UNION SELECT 'c_varbinary', count(c_varbinary) FROM $TABLE) t" \
  > "$(art)/pg-suspect-nonnull-counts.txt"
finding "可疑列非空计数（MySQL 侧应为 3）：$(cat "$(art)/pg-suspect-nonnull-counts.txt")"

# ---- utf8mb4：中文 + emoji（问题 4）----
psqlq "SELECT c_varchar FROM $TABLE WHERE id=2"   > "$(art)/pg-utf8mb4.txt"
mysqlq "SELECT c_varchar FROM $TABLE WHERE id=2"  > "$(art)/mysql-utf8mb4.txt"
if diff -q "$(art)/pg-utf8mb4.txt" "$(art)/mysql-utf8mb4.txt" >/dev/null; then
  finding "utf8mb4 中文与 emoji **逐字节一致**（id=2 的 c_varchar）"
else
  finding "**utf8mb4 不一致**！见 pg-utf8mb4.txt / mysql-utf8mb4.txt"
fi

# ---- DECIMAL(38,10) 端到端（问题 2 的基线，numeric.mapping 变体见 S2）----
if diff -q "$(art)/pg-numeric.txt" "$(art)/mysql-numeric.txt" >/dev/null; then
  finding "数值列（含 DECIMAL(38,10)、BIGINT UNSIGNED）文本表示完全一致"
else
  finding "**数值列存在差异**，见 pg-numeric.txt / mysql-numeric.txt 的 diff"
  diff "$(art)/mysql-numeric.txt" "$(art)/pg-numeric.txt" > "$(art)/numeric.diff" || true
fi

log "S1 完成，产物见 $(art)"
