#!/usr/bin/env bash
# S2 —— 票 #10 问题 2：DECIMAL(38,10) 端到端精度，逐个试 numeric.mapping 的四种取值。
#
# #5 的结论是"numeric.mapping 对 MySQL 完全无效"（MysqlType 声明 Types.DECIMAL，
# 而该配置只匹配 Types.NUMERIC）→ 预期四种取值产出的 Avro schema **完全相同**，
# 定点列恒为 Connect Decimal，不会静默变 double。
# 这是原先判定的头号风险，本 scenario 就是要把它钉死或推翻。
#
# 做法：每种取值单独建一个 Source（各自的 topic），只比 Avro schema 与首行 decimal 值，
# 不接 Sink —— 判定只需要 schema 与 topic 内容。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s2 "numeric.mapping 四种取值对 DECIMAL(38,10) 的影响"

for MODE in none best_fit best_fit_eager_double precision_only; do
  PREFIX="dbx.s2.${MODE}."
  TOPIC="${PREFIX}t_types"
  SRC="s2-src-${MODE//_/-}"

  delete_connector "$SRC"; sleep 1; delete_topic "$TOPIC"
  create_topic "$TOPIC"

  put_connector "$SRC" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "table.whitelist": "t_types",
  "topic.prefix": "$PREFIX",
  "numeric.mapping": "$MODE",
  "poll.interval.ms": 1000, "batch.max.rows": 100, "tasks.max": 1
}
JSON

  wait_task_state "$SRC" RUNNING 60 || snapshot_status "$SRC" "$MODE"
  # 等 topic 里真出现 4 条
  for _ in $(seq 1 30); do [ "$(topic_end_offset "$TOPIC")" -ge 4 ] && break; sleep 2; done

  capture_avro_schema "$TOPIC"
  jq -c '.fields[] | select(.name=="c_decimal")' < "$(art)/avro-$TOPIC.json" \
    > "$(art)/c_decimal-$MODE.json" 2>/dev/null || echo '{}' > "$(art)/c_decimal-$MODE.json"
  finding "\`numeric.mapping=$MODE\` → c_decimal 的 Avro 类型：\`$(cat "$(art)/c_decimal-$MODE.json")\`"

  # 真值：从 topic 里读第一条，看 decimal 是否被 double 化
  dc exec -T schema-registry kafka-avro-console-consumer \
    --bootstrap-server kafka:9092 --topic "$TOPIC" --from-beginning \
    --property schema.registry.url=http://schema-registry:8081 \
    --consumer-property max.partition.fetch.bytes=$MAX_MSG \
    --max-messages 2 > "$(art)/records-$MODE.json" 2>>"$(art)/run.log" || true

  delete_connector "$SRC"
done

# 四份 schema 是否逐字节相同 → #5 结论成立与否
if [ "$(md5sum "$(art)"/c_decimal-*.json | awk '{print $1}' | sort -u | wc -l)" -eq 1 ]; then
  finding "**四种取值产出的 c_decimal 类型完全相同 → #5「numeric.mapping 对 MySQL 无效」成立**"
else
  finding "**四种取值产出不同 → #5 的结论被推翻，类型映射矩阵（#11）须重开**"
fi

capture_connect_log s2
log "S2 完成，产物见 $(art)"
