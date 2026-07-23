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

---

## 3. 完整用户旅程（分步）

导航是**四个固定入口**：系统设置 / 数据源 / 作业中心 / （用户菜单）。整个产品只有这几屏。

### 第 0 步：登录与改密
`http://$host:9091` → `admin/admin`（或 `admin/selectdb`）→ 右上角用户名 → 修改密码表单 → 成功后提示"立即退出 / 取消"。
（https://docs.selectdb.com/ecosystem/x2doris/x2doris-use-guide/）

### 第 1 步：系统设置（一次性，全局）
左侧「系统设置」，是一张**键值列表**，每行右侧一个「编辑」按钮，点开填值、点「提交」。字段：

| 字段 | 官方释义 | 何时必填 |
| --- | --- | --- |
| Hadoop user | "指定提交作业到 yarn 上的用户" | 有 Yarn 时 |
| Spark Home | Spark 安装路径 | 启动作业必需（文档明确警告不配就没有 Spark 客户端环境） |
| Hive metastore uris | Hive 元数据地址 | Hive 源必需 |
| Target doris info | 目标端连接信息，点「编辑」弹窗 | 必需 |

`Target doris info` 弹窗标题"Doris/SelectDB Cloud 目标端信息录入"，字段：**HTTP Nodes**（`ip:port`，多个逗号分隔）、**MySQL Nodes**（JDBC 的 `ip:port`）、**User**、**Password**，另有可选的 BE 节点写入选项。点「确定」保存。
（阿里云文档 https://help.aliyun.com/zh/selectdb/import-data-via-x2doris 字段级最细）

> 关键设计：**目标端只有一个，全局配一次**。X2Doris 假设"我就是往这一个 Doris 搬"，因此目标端不是作业级参数。DBX 的目标 PG 大概率也是这个假设。

### 第 2 步：添加数据源（非 Hive 源）
左侧「数据源」→ 右上角「新增数据源」→ 选类型（Doris / Kudu / StarRocks / ClickHouse）→ 填 HTTP 节点、JDBC 节点、用户名、密码（节点支持 `ip1:port1,ip2:port2` 多节点）→ **系统自动验证连接信息** → 「确定」。

Hive 源特殊：**不走「数据源」页**，靠系统设置里的 metastore uri + `conf/application-hive.yaml`（client 可选 `metastore` / `jdbc` / `dlf`）。即 Hive 的连接信息一半在界面、一半在配置文件里——**这是它的一个割裂点**。

### 第 3 步：新建作业 → 选源数据
「作业中心」→ 右上角「新增作业」→ 选源类型。
- Hive：**进入时自动检测 Hive 连接**，失败直接告警并提示去检查系统设置里的 metastore uris。
- 非 Hive：弹窗"请选择数据源"，在列表里点「使用」列的按钮选中一个数据源。

进入选择页后，**左侧是库表树，右侧是映射/DDL 区**。可以选：**单表 / 多表 / 整库**。

### 第 4 步：字段映射（核心一屏）
左侧点中表，右侧列出该表字段与 Doris 字段的对照表，用户要处理三列：

| 列 | 用户决策 |
| --- | --- |
| **DORIS 字段类型** | 系统自动识别并预填，**可下拉修改** |
| **DUPLICATE KEY** | 勾选排序列，至少一个 |
| **DISTRIBUTED BY**（分桶列） | 勾选，至少一个 |

自动化程度：文档明确"已经自动识别了 Hive 表中的分区字段，并且自动强制将分区自动设置为 DUPLICATE KEY 字段"。
已知坑（官方标注）：**STRING 类型不能作为 DUPLICATE KEY，必须改成 VARCHAR**——这是唯一一个文档强调用户"必须动手"的映射修正。

### 第 5 步：分区映射（Hive 专属）
若 Hive 分区字段声明为 STRING 但实际是时间，可在此转成 Doris 时间类型并设置分区区间范围。点「下一步」。

### 第 6 步：建表决策（三选一按钮）
这是整个产品最值得抄的一屏。**不是一个"下一步"，而是三个平行的意图按钮**：

- **跳过建表** —— 目标端表已存在
- **只建表** —— 只创建表，不创建迁移作业
- **创建表 & 作业** —— 一次做完

（Hive 流程的按钮措辞略有差异：「跳过建表」/「去建表」，点「去建表」后进入 DDL 确认页 → 「创建Doris表」→ 「下一步」。）

### 第 7 步：DDL 确认
选择建表后，页面**展示自动生成的 Doris DDL 全文**，用户 Review，官方说明 DDL **可手动修改**。确认后点「创建Doris表」。
前置约束：**目标库（database）必须用户先手工建好**，X2Doris 只建表不建库。

### 第 8 步：作业设置
一屏表单，作业名与标签**自动生成**，其余是执行参数：

| 参数 | 说明 |
| --- | --- |
| Master | `local` / `yarn` / `standalone` |
| Yarn Queue | Spark 队列 |
| Memory Options | executor/driver 核数与内存 |
| Write Batch | 刷写批次大小，大数据量建议 ≥ 500000 |
| Max retry | 失败重试次数 |
| Spark option | 自定义 `key=value` |
| Properties | 源读取 / Doris 写入的调优参数（如 `doris.request.tablet.size=1`） |

点「新建作业」→ 自动跳回作业列表。

### 第 9 步：启动作业
作业列表「操作」列点启动 → 弹窗「启动作业」，两个决策：
- **查询条件**：过滤条件，"仅需写 where 后的逻辑"，如 `name='Alice'`
- **清空数据**：默认 OFF；官方原文"极度危险的操作，生产数据迁移时，慎重操作"

点「确定」开始跑。

### 第 10 步：看结果
作业列表展示**迁移进度（百分比）**与执行状态。进度**不是自动推送的**——文档说明需手动点刷新按钮更新，并警告"切记不要频繁的刷新进度更新按钮"。失败后排查靠**服务器上的 `log/selectdb.out` 文件**。

---

## 4. 自动建表与类型映射的处理方式

**结论：DDL 给用户看，且可改；映射规则藏在代码里，不暴露为配置。**

1. **谁生成**：平台读源库元数据自动生成。官方描述："读取 Hive 表的元数据信息，通过字段名和字段类型自动识别分区字段……直接生成对应的 Doris 目标表 DDL"（https://www.selectdb.com/blog/160）。Doris/StarRocks 源还会"自动根据源表信息解析出表模型"，并把源端 Properties 转换成目标表属性。
2. **给不给看**：**给看，而且是流程中的强制一屏**。第 7 步「Doris 表 DDL 确认」页展示完整 DDL 全文供 Review。
3. **能不能改**：**两层都能改**。
   - 结构化层：第 4 步字段映射表里逐字段下拉改 Doris 类型、勾 DUPLICATE KEY / DISTRIBUTED BY。
   - 文本层：第 7 步的 DDL 文本可手工编辑后再执行。
4. **规则藏在哪**：**藏在闭源代码里**。官方文档里的配置文件只有 `application.yml`（服务自身）、`application-mysql.yml`（元数据库）、`application-hive.yaml`（Hive 元数据获取方式：metastore/jdbc/dlf）——**没有任何用户可编辑的类型映射表**（无 `type-mapping.yaml` 之类）。要偏离默认映射，唯一途径是在界面上改。
5. **例外都靠界面点**，不靠配置：分区字段自动强制设为 DUPLICATE KEY；STRING 不能当 KEY，官方要求用户在映射表里改成 VARCHAR；Hive STRING 分区列可在"分区映射"页转成时间类型并设分区区间。
6. **建库不管**：目标 database 必须用户手工先建好，X2Doris 只建表。

> **对 DBX 的启示**：DBX 已定的"平台生成 DDL → 前端展示审核 → 用户批准后执行"与 X2Doris 完全一致，这是一次正面验证。可直接抄的两个细节：(a) **结构化编辑 + DDL 文本编辑双通道**，前者给 DBA 快速改类型，后者给专家兜底；(b) **把映射规则做成产品内置而非可配置文件**——不让 DBA 去编辑 YAML，符合"目标用户不懂开发"。

---

## 5. 多表 / 整库迁移的表选择与批量配置

这是本票最关心的"如何避免 200 张表逐个配"。X2Doris 的答案是**分级降配**：

| 选择粒度 | 界面行为 | 用户要做的决定数 |
| --- | --- | --- |
| **单表** | 右侧展示该表的字段映射表 + 自动生成的 DDL 供 Review | 逐字段可调 |
| **多表**（树上勾多张） | **右侧不展示 DDL**，全部**使用默认生成策略** | 0 |
| **整库** | 同多表，全库默认策略 | 0 |

（一手依据：阿里云文档"可选择单表（右侧显示 DDL）／可选择多表（使用默认生成策略，右侧不显示 DDL）／可选择整库"，https://help.aliyun.com/zh/selectdb/import-data-via-x2doris ；官方使用手册"树形结构选择单表/多表/整库，单表时自动生成 DDL，可 Review 确认"。）

关键设计判断：

- **它没有做"批量映射编辑器"**。它做的是**放弃精细化**：一旦你选了多表，就等于接受平台的默认策略，界面直接把 DDL 审核环节隐藏掉。这是一个非常果断的产品取舍——**用"默认全自动 + 不给你看"来消灭 200 次决策**，而不是用"批量编辑控件"去优化 200 次决策。
- **需要例外的表怎么办？** 官方流程里没有"整库里挑第 37 张表单独配"的入口。可行的绕法是分两次作业：例外表单表建、其余整库建；或先「只建表」再手工改 DDL。**文档没有给出官方推荐做法——这是一个真实缺口。**
- **一次多表选择 = 一个作业还是 N 个作业？** 官方文档未明确说明作业与表的基数关系（作业列表按作业展示，进度是单一百分比）。**这一点无法从一手来源确认，不做断言。**

> **对 DBX 的启示（要点）**：
> - "整库默认、单表例外"的分级是对的，但 X2Doris 把它做成了**互斥**（多表就完全不给看）。DBX 已定方案是"默认全字段同名同类型 + 前端做例外配置"，其实**比 X2Doris 更进一步**：应保留"整库选中后仍能展开某一张表做例外"的能力，而不是像 X2Doris 那样一进多表模式就关掉审核入口。
> - 但要抄它的**决策计数意识**：默认路径上的用户点击数必须与表数量无关（O(1)），只有例外才付出 O(表数) 的代价。

---

## 6. 进度、耗时、日志的展示形态

| 维度 | X2Doris 实际做法 | 评价 |
| --- | --- | --- |
| 粒度 | **任务（作业）级**。作业列表一行一个作业，展示迁移进度百分比 + 执行状态 | 无表级进度 |
| 刷新 | **手动点刷新按钮**；官方警告"切记不要频繁的刷新进度更新按钮" | 无 WebSocket/轮询推送 |
| 耗时 | 官方文档未描述独立的耗时/吞吐展示 | **缺口，不做断言** |
| 失败信息 | 界面只给状态；根因要去服务器看 `X2Doris 安装目录/log/selectdb.out` | **对非开发用户是硬伤** |
| 重试 | 作业设置里有 `Max retry`（Spark 层自动重试次数）；界面上是整作业重启 | 无"按表重跑" |
| 兜底 | 官方文档明确写"记录任务运行背景信息和日志后联系 SelectDB 官方支持" | 说明自助排障能力不足 |

一手依据：https://docs.selectdb.com/ecosystem/x2doris/x2doris-use-guide/ 与 https://help.aliyun.com/zh/selectdb/import-data-via-x2doris （"任务失败排查：优先查看 X2Doris 安装目录下 log/selectdb.out 文件"）。

> **对 DBX 的启示**：这是 X2Doris **明显不该抄**的部分，也正是 DBX 已定方案（进度按表统计、失败按表重跑）胜出的地方。DBX 应在此超越模型：表级进度条 + 表级失败原因（把 SQL 报错翻译成人话）+ 表级重跑按钮 + 界面内可读日志，让 DBA 永远不需要 ssh 到服务器 tail 日志。
