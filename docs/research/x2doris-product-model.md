# X2Doris 产品模型与交互流程拆解

> 研究票：[liumingjian/dbx#7](https://github.com/liumingjian/dbx/issues/7)，父票 #1
> 目的：X2Doris 被指定为 DBX 的"理想平台模型"，本文拆解它的用户旅程、自动建表机制、批量配置策略、进度与日志形态，输出可直接喂给"迁移向导旅程原型"的结论。
> 日期：2026-07-23

## 0. 一手来源与可信度说明

本文优先采用官方一手来源：

| 来源 | URL | 性质 |
| --- | --- | --- |
| SelectDB 官方使用手册 | https://docs.selectdb.com/ecosystem/x2doris/x2doris-use-guide/ | 一手，界面步骤最权威 |
| SelectDB 官方安装与部署手册 | https://docs.selectdb.com/ecosystem/x2doris/x2doris-deployment-guide/ | 一手 |
| SelectDB 发布公告《百川终入海，一站式海量数据迁移工具 X2Doris 正式发布》 | https://www.selectdb.com/blog/160 | 一手 |
| SelectDB Tools 下载页 | https://www.selectdb.com/download/tools | 一手 |
| 阿里云《安装并使用 X2Doris 导入数据》（云数据库 SelectDB 版官方文档） | https://help.aliyun.com/zh/selectdb/import-data-via-x2doris | 云厂商官方文档，界面字段描述最细 |

**重要缺口（如实说明）：**

1. **X2Doris 没有公开的 GitHub 仓库**。GitHub 仓库/代码/Issue 搜索 `x2doris`：`selectdb` 组织下只有 `ccr-syncer`、`datax-selectdb`、`dbt-doris` 等，没有 x2doris 源码仓库；全网 issue 搜索 `x2doris` 仅命中 apache/doris 与 apache/seatunnel 的零星提及，**x2doris 自己没有 issue 区**。它是闭源产品，以 tar.gz 从官网分发。
   - 后果：本票要求的"从 GitHub issues 找真实用户抱怨证据"**无法满足**。第 8 节改为从官方文档自身的警告语、显式约束与能力边界推导，并标注哪些是推断而非用户原话。
2. 本文不含截图。所有界面描述来自官方文档正文与操作说明；能对应到具体按钮名称的地方都给了出处。
3. 官方部署手册写默认口令 `admin/admin`，发布公告写 `admin/selectdb`——两处一手来源不一致，以实际下载版本为准。

---

## 1. 产品定位与形态

- 官方定位原文："集自动建表和数据迁移为一体，超高性能，简单易用"，"全程界面化、可视化操作"（https://www.selectdb.com/blog/160）。
- **源端**：Apache Hive（1.x/2.x）、Apache Kudu、StarRocks、ClickHouse、Apache Doris。**目标端**：Apache Doris / SelectDB Cloud / SelectDB Enterprise。
- **只做离线全量搬迁**，不做 CDC 增量。底层是 Spark：界面上的选择最终被翻译成一个 Spark 作业提交出去。
- 与 DBX 同构性很高：**同为"一次性离线迁移 + 自动建表 + Web 界面"**，而非流式同步平台。这是它值得被当作模型的根本原因。

---

## 2. 部署形态与"随用随起"程度

来源：https://docs.selectdb.com/ecosystem/x2doris/x2doris-deployment-guide/

- 下载 tar.gz → 解压 → 改 `conf/application.yml` → `bin/startup.sh` → 浏览器访问 `http://$host:9091`。**单进程 Spring Boot Web 应用**，无容器编排要求。
- 元数据库两选一：
  - **H2**（默认，本地文件）：零配置，开箱即用。
  - **MySQL**（推荐生产）：手工执行 `script/schema/mysql-schema.sql` + `script/data/mysql-data.sql`，把 `spring.profiles.active` 改成 `mysql`。
- 安装包**按 Scala 版本 + CPU 架构分发**（如 `selectdb-x2doris-v1.2.2_2.12-x86-bin.tar.gz`）。用户需先去 `$SPARK_HOME/jars` 看 `spark-yarn_2.12-*.jar` 判断 Scala 版本；无 Spark 环境则选自带 Spark 的 2.12 包。
- 运行模式：`local`（单机，自带 Spark）或 `yarn`（提交到大数据集群）。官方推荐 Yarn 以获得并行度。
- **"随用随起"评价**：*启动*确实随用随起（一条 startup.sh + H2 免初始化）；但 *Hive 源*场景要求部署机是 Hadoop 集群节点或 gateway，且必须配好 `HADOOP_HOME`、`HADOOP_CONF_DIR`、`HIVE_CONF_DIR`，`hive-site.xml` 必须就位——这部分谈不上简单。

> **对 DBX 的启示**：X2Doris 恰好印证"部署可以复杂，使用必须简单"。它把所有环境级脏活（Spark 路径、Hadoop 用户、Hive metastore URI、目标库连接）**一次性收进「系统设置」页**，之后建作业流程里再也不问这些问题。DBX 应照抄这个切分：**环境配置 ≠ 迁移向导**。
