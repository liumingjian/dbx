# s5 —— 大字段 1MiB/19MiB 通过与 25MiB 的失败形态

实测时间：2026-08-01T23:21:54+08:00

- MySQL 源侧各行字节数：1	blob-32kib	32768	0 2	longblob-1mib	0	1048576 3	longblob-19mib	0	19922944 4	longblob-25mib-over-limit	0	26214400 
- 落库行数 **3**（期望 3：32KiB/1MiB/19MiB 通过，25MiB 被拦）
- 落库字节数：1|blob-32kib|32768| 2|longblob-1mib||1048576 3|longblob-19mib||19922944 
- 超限异常原文（前 5 行）：
    420:connect-1  | 	max.request.size = 26214400
    1404:connect-1  | org.apache.kafka.common.errors.RecordTooLargeException: The message is 26214526 bytes when serialized which is larger than 26214400, which is the value of the max.request.size configuration.
    1442:connect-1  | Caused by: org.apache.kafka.common.errors.RecordTooLargeException: The message is 26214526 bytes when serialized which is larger than 26214400, which is the value of the max.request.size configuration.
- 完整原文见 `oversize-evidence.txt` 与 `connect-log-s5.txt`，应整份归档为 #19 的测试夹具
- 判定要点：失败发生在 **Source producer 侧还是 Sink consumer 侧**？前者意味着预检必须在源端做（#15 的 SELECT MAX(LENGTH) 路线成立），后者意味着数据已进 Kafka、清理成本更高。
