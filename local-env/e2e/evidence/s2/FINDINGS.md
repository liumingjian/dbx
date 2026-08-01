# s2 —— numeric.mapping 四种取值对 DECIMAL(38,10) 的影响

实测时间：2026-08-02T02:30:13+08:00

- `numeric.mapping=none` → c_decimal 的 Avro 类型：`{"name":"c_decimal","type":["null",{"type":"bytes","scale":10,"precision":38,"connect.version":1,"connect.parameters":{"scale":"10","connect.decimal.precision":"38"},"connect.name":"org.apache.kafka.connect.data.Decimal","logicalType":"decimal"}],"default":null}`
- `numeric.mapping=best_fit` → c_decimal 的 Avro 类型：`{"name":"c_decimal","type":["null",{"type":"bytes","scale":10,"precision":38,"connect.version":1,"connect.parameters":{"scale":"10","connect.decimal.precision":"38"},"connect.name":"org.apache.kafka.connect.data.Decimal","logicalType":"decimal"}],"default":null}`
- `numeric.mapping=best_fit_eager_double` → c_decimal 的 Avro 类型：`{"name":"c_decimal","type":["null",{"type":"bytes","scale":10,"precision":38,"connect.version":1,"connect.parameters":{"scale":"10","connect.decimal.precision":"38"},"connect.name":"org.apache.kafka.connect.data.Decimal","logicalType":"decimal"}],"default":null}`
- `numeric.mapping=precision_only` → c_decimal 的 Avro 类型：`{"name":"c_decimal","type":["null",{"type":"bytes","scale":10,"precision":38,"connect.version":1,"connect.parameters":{"scale":"10","connect.decimal.precision":"38"},"connect.name":"org.apache.kafka.connect.data.Decimal","logicalType":"decimal"}],"default":null}`
- DECIMAL(38,10) 经 Source → Avro → Sink → PostgreSQL **4/4 逐值一致**
- **四种取值产出的 c_decimal 类型完全相同 → #5「numeric.mapping 对 MySQL 无效」成立**
