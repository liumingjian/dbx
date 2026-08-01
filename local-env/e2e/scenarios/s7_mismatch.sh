#!/usr/bin/env bash
# S7 —— 票 #10 问题 7：结构不匹配时 Sink 报什么错、在什么时机报。
#
# #4 的两条结论要在这里验：
#  a. 缺列 → 结构错误在**该 topic 首条记录写入时**暴露（不是启动时）
#  b. 类型错 → 最晚攒够 batch.size（默认 3000）才炸 → 冒烟期必须设 1
#
# 三个变体，同一张 t_large_text：
#  A. 目标表少建一列 c_text
#  B. 目标表把 c_text 建成 integer
#  C. 与 B 相同但 batch.size 用默认值 → 观察报错时机是否被推迟
#
# 抓到的 trace 原文全部归档：它们是错误翻译层（#19）规则表的第一批条目。

source "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"
init_scenario s7 "结构不匹配的失败形态与时机"

run_variant() {
  local tag="$1" ddl="$2" batch="$3" desc="$4"
  local table="t_large_text_$tag"
  local topic="dbx.s7.$tag.t_large_text"
  local src="s7-src-v2-$tag" sink="s7-sink-v2-$tag"

  cleanup_link "$src" "$sink" "$topic" "$table"
  create_topic "$topic"
  psqlq "$ddl" >> "$(art)/run.log" 2>&1

  put_connector "$src" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx", "connection.password": "dbx",
  "mode": "incrementing", "incrementing.column.name": "id",
  "table.whitelist": "t_large_text",
  "topic.prefix": "dbx.s7.$tag.",
  "poll.interval.ms": 1000, "batch.max.rows": 1, "tasks.max": 1
}
JSON

  local t0=$SECONDS
  put_connector "$sink" <<JSON
{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx", "connection.password": "dbx",
  "topics": "$topic", "table.name.format": "$table",
  "auto.create": "false", "auto.evolve": "false",
  "insert.mode": "insert", "pk.mode": "none",
  "batch.size": "$batch", "errors.tolerance": "none"
}
JSON

  # 启动即失败还是首条记录才失败？先记 5 秒时的状态，再等 FAILED
  sleep 5
  snapshot_status "$sink" "${tag}-at5s"
  local at5; at5="$(jq -r '.tasks[0].state // "PENDING"' < "$(art)/status-$sink-${tag}-at5s.json")"

  if wait_task_state "$sink" FAILED 120; then
    local dt=$((SECONDS - t0))
    snapshot_status "$sink" "$tag"
    local rows; rows="$(psqlq "SELECT count(*) FROM $table" | tr -d ' ')"
    finding "**$desc**（batch.size=$batch）：5 秒时状态 \`$at5\`，**${dt}s 后 FAILED**，此前已落库 $rows 行"
    finding "    异常首行：\`$(head -1 "$(art)/trace-$sink-$tag.txt" 2>/dev/null)\`"
  else
    snapshot_status "$sink" "$tag"
    local rows; rows="$(psqlq "SELECT count(*) FROM $table" | tr -d ' ')"
    finding "**$desc**（batch.size=$batch）：120 秒内**未失败**，落库 $rows 行 —— 若 >0 则是「安静的失败」，最危险的形态"
  fi

  delete_connector "$src"; delete_connector "$sink"
}

# tag 一律用下划线：它同时是 PG 表名后缀、topic 名的一段与 connector 名的一段，
# 连字符在 PG 标识符里会要求加引号，反而撞上 #4 的大小写陷阱。
run_variant missing_col \
  "DROP TABLE IF EXISTS t_large_text_missing_col;
   CREATE TABLE t_large_text_missing_col (
     id integer PRIMARY KEY, label varchar(64) NOT NULL, c_longtext text)" \
  1 "目标表少建一列（缺 c_text）"

run_variant wrong_type \
  "DROP TABLE IF EXISTS t_large_text_wrong_type;
   CREATE TABLE t_large_text_wrong_type (
     id integer PRIMARY KEY, label varchar(64) NOT NULL, c_text integer, c_longtext text)" \
  1 "目标表列类型建错（c_text 建成 integer）"

run_variant wrong_type_bigbatch \
  "DROP TABLE IF EXISTS t_large_text_wrong_type_bigbatch;
   CREATE TABLE t_large_text_wrong_type_bigbatch (
     id integer PRIMARY KEY, label varchar(64) NOT NULL, c_text integer, c_longtext text)" \
  3000 "同上但 batch.size 用默认 3000（验 #4 的「攒够 batch 才炸」）"

capture_connect_log s7 1500
log "S7 完成，产物见 $(art)"
