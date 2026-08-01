# s6 —— 无主键表 insert 与复合主键表 upsert

实测时间：2026-08-02T00:46:26+08:00

- 无主键表 `insert.mode=insert`：落库 **5 / 5** 行
- 重复行组数（源侧应为 1，被去重则为 0）：1
- `mode=bulk` 的 offsets 端点：`{"offsets":[]}`
- 30 秒后行数 5（若 > 5，说明 bulk 会重复整表投递 → 完成判定必须靠外部 DELETE，见 #13）
- 复合主键表 `insert.mode=upsert` + `pk.mode=record_value`：落库 **4 / 4** 行
- 落库内容：1|ORD-0001|99.9900 1|ORD-0002|0.0001 2|è®¢å•-ä¸­æ–‡-ðŸšš|1.0000 2|ORD-0001|-12345.6789 
- 显式重启 bulk Source 后 topic offset 4 → 8，upsert 目标表保持 **4** 行
