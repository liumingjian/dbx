# s1 —— t_types 全类型主链路 + utf8mb4

实测时间：2026-08-02T02:33:46+08:00

- 落库行数 0 / 4
- t_types 的列全部进了 Avro schema，无静默丢列
- 主链路未完整落库，不能用 PG 非空计数判断静默丢列；以 Avro schema 差集和失败 trace 为准
- 隔离 query（utf8mb4 + JSON + 可疑列）落库 4 / 4 行
- 隔离链路 c_bit1 / c_set / c_year 非空计数：3|3|3（源侧均为 3）
- **utf8mb4 不一致**！见 pg-utf8mb4.txt / mysql-utf8mb4.txt
- MySQL JSON → PostgreSQL json cast 可写入，但字符串内容 **不一致**；见 pg-json-normalized.txt / mysql-json.txt
- **全类型主链路数值列未完成比对**，见 Source 溢出 trace 与 pg/mysql-numeric.txt
