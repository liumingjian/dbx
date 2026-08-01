# s8 —— 从创建到读完的完整信号序列

实测时间：2026-08-02T00:16:18+08:00

- 源表行数（判定的分母）：**4**
- 120 秒后：topic 末端 offset **4**，PG 行数 **4**，源表 4
- Source connector/task 终态：`RUNNING` / `RUNNING`（若仍是 RUNNING → 印证 #3：无跑完即停语义）
- offsets 端点终值：`{"offsets":[{"partition":{"query":"query"},"offset":{"incrementing":4}}]}`（#9 实测为空数组，本次是否复现？）
- topic offset 最后一次变化发生在 t=5s → 停滞窗口至少要大于 poll.interval.ms(5s) 的若干倍，具体阈值由 #13 定
- DELETE connector 后 topic 末端 offset：**4**（数据仍在 → 重跑必须显式删 topic 或换运行标识前缀，见 #17）
