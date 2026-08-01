# s7 —— 结构不匹配的失败形态与时机

实测时间：2026-08-02T00:14:42+08:00

- **目标表少建一列（缺 c_text）**（batch.size=1）：5 秒时状态 `FAILED`，**5s 后 FAILED**，此前已落库 0 行
-     异常首行：`org.apache.kafka.connect.errors.ConnectException: Exiting WorkerSinkTask due to unrecoverable exception.`
- **目标表列类型建错（c_text 建成 integer）**（batch.size=1）：5 秒时状态 `RUNNING`，**35s 后 FAILED**，此前已落库 0 行
-     异常首行：`org.apache.kafka.connect.errors.ConnectException: Exiting WorkerSinkTask due to unrecoverable exception.`
- **同上但 batch.size 用默认 3000（验 #4 的「攒够 batch 才炸」）**（batch.size=3000）：5 秒时状态 `RUNNING`，**35s 后 FAILED**，此前已落库 0 行
-     异常首行：`org.apache.kafka.connect.errors.ConnectException: Exiting WorkerSinkTask due to unrecoverable exception.`
