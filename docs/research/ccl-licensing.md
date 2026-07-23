# Confluent Community License (CCL) 与 DBX 商业分发的许可合规研究

> 研究票：[liumingjian/dbx#2](https://github.com/liumingjian/dbx/issues/2)（父票 #1）
> 日期：2026-07-23
> **免责声明：本文是技术尽调，不是法律意见。** 文中凡标注 **[法律判断]** 的部分，必须由真人律师确认后才能作为决策依据。凡标注 **[技术判断]** 的部分，可以由工程团队自行验证。

---

## 结论摘要

1. **可以。** DBX 现有技术栈（Kafka + Confluent JDBC Source/Sink Connector + Confluent Schema Registry/Avro）**能够**作为商业产品打包、分发给最终客户私有部署。CCL 的唯一实质限制是"Excluded Purpose"——不得用它去做**与 Confluent 自己那款软件竞争的 SaaS/PaaS/IaaS 在线服务**。DBX 是一个交付给客户自己装在自己机房里的离线迁移产品，不是托管的 Kafka/Schema Registry 服务，因此不落在限制内。**[法律判断，需律师确认]**
2. CCL 明文授予 **distribute（分发）** 和 **reproduce（复制）** 的权利，Confluent 官方 FAQ 也明确写了"embed Confluent Community software in any non-competitive offering"。所以把 connector JAR 和 Schema Registry 镜像打进 DBX 发行包，是被授权的再分发。**[法律判断]**
3. **必须做的合规动作**：随发行包附上 CCL 全文、保留全部版权声明、对任何修改加显著修改说明。CCL **不可再许可（non-sublicenseable）**，所以客户是直接从 Confluent 取得许可的——DBX 的 EULA 必须把 CCL 组件单列，不能声称对它们授予许可。**[法律判断]**
4. **最大的真实风险不是 CCL，是 MySQL Connector/J（GPLv2 + Universal FOSS Exception）。** UFE 只对"Other FOSS"（OSI/FSF 认可的自由软件）生效，对专有软件不生效。DBX 是专有商业软件 → **不要把 Connector/J 打进发行包**。这也是 Confluent 自己的做法：它的 JDBC connector 打包了 PostgreSQL 驱动，但**唯独要求用户自行下载 MySQL Connector/J**。
5. **CCL 不是开源许可**：不在 OSI 批准列表，也不在 SPDX 许可清单里。如果客户（尤其是国企/金融/政府）的采购条款要求"全部第三方组件为 OSI 认可开源许可"，CCL 会**卡在采购而不是卡在法律**上。这是需要提前评估的商务风险。
6. **存在完整的 Apache 2.0 逃生路线**：Aiven JDBC Connector（Apache-2.0，从 Confluent 改许可前 fork）+ Apicurio Registry（Apache-2.0，提供 Confluent Schema Registry 兼容 API `/apis/ccompat/v7`）。能力差距存在但可控，详见第 6 节。建议**保留这条备选路线**，不必现在切换。
7. **不要用 `cp-server-connect` / `cp-server` 镜像**（Confluent Enterprise License，需 license key）。要用 `cp-kafka-connect` / `cp-schema-registry`。即便如此，`cp-kafka-connect` 镜像内含 `confluent-hub-client`（Enterprise License），若要分发镜像需处理掉。详见第 4 节。

---

## 1. CCL 原文条款

**来源：** <https://www.confluent.io/confluent-community-license/>（Confluent Community License Agreement Version 1.0）

### 1.1 许可授予（Section 1.1）

> "Subject to the terms and conditions of this Agreement, Confluent hereby grants to Licensee a non-exclusive, royalty-free, worldwide, non-transferable, non-sublicenseable license during the term of this Agreement to: (a) use the Software; (b) prepare modifications and derivative works of the Software; (c) distribute the Software"

要点（**[技术判断]**：这是条文字面读取）：

| 权利 | 是否授予 |
|---|---|
| 使用 | ✅ |
| 修改 / 制作衍生作品 | ✅ |
| 分发（源码或目标码形式） | ✅ |
| 复制 | ✅ |
| **再许可（sublicense）** | ❌ 明确 non-sublicenseable |
| 商标 | ❌ 不授予 |
| 转让（transfer） | ❌ non-transferable |

### 1.2 Excluded Purpose（唯一实质限制）

> "'Excluded Purpose' means making available any software-as-a-service, platform-as-a-service, infrastructure-as-a-service or other similar online service that competes with Confluent products or services that provide the Software. Licensee is not granted the right to exercise the License for an Excluded Purpose."

**边界的确切措辞在于 "that provide the Software" 这个限定语。** 它把"竞争"的范围锁定在**同一款软件**上，而不是泛指与 Confluent 竞争。
来源：<https://www.confluent.io/confluent-community-license/>

Confluent 官方 FAQ 用酒店预订引擎的例子解释这个边界：

> 如果你在做一个 SaaS Hotel Booking Engine 并想在里面用 ksqlDB，那是允许的，因为你的服务并不与任何"提供该软件（provides the software）"的 Confluent 产品竞争；ksqlDB 的 Excluded Purpose 仅限于与 Confluent 自己的 ksqlDB SaaS 产品竞争。**即使 Confluent 后来自己也做了酒店预订产品，这个结论依然成立。**

来源：<https://www.confluent.io/confluent-community-license-faq/>

FAQ 另外两条相关口径：

- **免费也算竞争**："a free offering is just a competitive product with a price of zero"；"competitive" 的标准是构成**经济替代品（economic substitute）**。
  来源：<https://www.confluent.io/confluent-community-license-faq/>
- **修改不受 Excluded Purpose 限制**：Excluded Purpose 不限制"创建修改"这一行为本身；你可以给自己的修改加上自己的版权声明和不同的条款。
  来源：<https://www.confluent.io/confluent-community-license-faq/>

### 1.3 再分发义务

分发给下游时必须（来源：<https://www.confluent.io/confluent-community-license/>）：

- 完整保留全部版权声明，不得改动；
- 对改动过的文件加显著的修改说明（prominent modification notices）；
- 随每一份副本提供完整的 CCL 许可声明。

**不可再许可，但下游可直接行权**：FAQ 明确"you can't sublicense, but recipients you give the Software to may exercise the Licenses so long as they agree to this Agreement's terms"。
来源：<https://www.confluent.io/confluent-community-license-faq/>

**对 DBX 的含义 [法律判断，需律师确认]**：DBX 的最终用户许可协议（EULA）不能把 CCL 组件当作"DBX 授予客户的软件"一并授权，必须在第三方组件清单里单列 CCL 组件、附 CCL 全文、并说明客户与 Confluent 之间成立直接的许可关系。

### 1.4 其他条款

- 违约自动终止（Termination）。
- 无担保（as-is）。
- 适用法：加州法；美国被许可方在 Santa Clara County 法院，非美国被许可方在 Palo Alto 走 JAMS 仲裁。
  来源：<https://www.confluent.io/confluent-community-license/>

---

## 2. "打包分发给客户私有部署"是否被允许

### 2.1 Confluent 官方口径（一手来源）

**改许可公告博客**（Jay Kreps，Confluent 官方博客）明确写：

> "You can embed the code in software you distribute."
> "Can I use the code to build a SaaS product? Yes, in almost all cases."
> "you cannot build a SaaS offering where KSQL itself is the product being offered"

来源：<https://www.confluent.io/blog/license-changes-confluent-platform/>

**官方 FAQ**：

> 你可以 (1) 在生产环境运行 Confluent Community，或 (2) 把 Confluent Community 软件嵌入到**任何非竞争性的产品（any non-competitive offering）**中。

来源：<https://www.confluent.io/confluent-community-license-faq/>

### 2.2 DBX 的定性分析 **[法律判断，需律师确认]**

| 问题 | 分析 |
|---|---|
| DBX 是不是 SaaS/PaaS/IaaS 或"类似在线服务"？ | 否。DBX 是交付给客户、由客户在自有机房安装运行的离线迁移软件（含内网离线安装）。Excluded Purpose 的三个列举项外加 "other similar online service" 都指向"在线提供的服务"这一形态。 |
| 即便 DBX 将来做 SaaS 版本呢？ | 仍需看是否"与提供该软件的 Confluent 产品竞争"。DBX SaaS 提供的是**异构数据库迁移**，不是托管 Kafka Connect / 托管 Schema Registry。按 FAQ 的酒店预订例子，这属于允许范围。**但**：如果 DBX 对外暴露"我们给你托管一个 Schema Registry / 你可以自己提交 connector 配置"这类能力，就开始向 Excluded Purpose 靠拢，届时必须重新评估。 |
| 客户私有部署是否落在限制之外？ | 是。客户是最终用户，自己运行软件，不向第三方提供在线服务。FAQ 明确自托管被允许。 |
| 分发形态（JAR / Docker 镜像 / 离线安装包）有区别吗？ | CCL 的 distribute 权利不区分形态（"in source or object code form"）。但 Docker 镜像涉及镜像内**其他**组件的许可，见第 4 节。 |

**结论**：CCL 本身不阻止 DBX 的商业分发。**[法律判断]**

### 2.3 CCL 不是开源许可（对采购的影响）

- **OSI**：Confluent Community License 不在 OSI 批准许可列表中。
  来源：<https://opensource.org/licenses>（列表中无任何含 "Confluent" 的条目）
- **SPDX**：截至本文写作时，SPDX 官方许可清单（`spdx/license-list-data`，`json/licenses.json`）中**不存在** `Confluent-Community-1.0` 或任何 Confluent 条目。
  来源：<https://github.com/spdx/license-list-data/blob/main/json/licenses.json>（程序化检索 `licenses[].licenseId` / `.name`，无匹配）
- Confluent 自己也承认这一点：FAQ 中说该许可"is not open source because it doesn't meet the OSI's Open Source Definition"，属于 **source-available** 许可。
  来源：<https://www.confluent.io/confluent-community-license-faq/>

**对 DBX 的含义 [商务判断]**：SBOM 里会出现一个非 OSI 认可、非 SPDX 标准 ID 的许可。金融/国企/政府类客户的软件供应链审查（SBOM + 许可白名单）可能直接拒绝。这个风险独立于法律风险，可能比法律风险更现实。

---

## 3. 各组件的许可归属（逐个核实）

| 组件 | 许可 | 一手来源 |
|---|---|---|
| **Apache Kafka**（含 Connect、Streams） | Apache-2.0 | <https://github.com/apache/kafka>（GitHub API 报告 `spdx_id: Apache-2.0`）；Confluent 公告："This has no effect on Apache Kafka, which is developed as part of the Apache Software Foundation and remains under the Apache 2.0 license." <https://www.confluent.io/blog/license-changes-confluent-platform/> |
| **Confluent JDBC Source/Sink Connector**（`kafka-connect-jdbc`） | **Confluent Community License** | 仓库 LICENSE 文件即 CCL 全文：<https://github.com/confluentinc/kafka-connect-jdbc/blob/master/LICENSE>；`pom.xml` `<licenses>` 声明 name = "Confluent Community License", url = `https://www.confluent.io/confluent-community-license`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/master/pom.xml>；文档："This connector is available under the Confluent Community License." <https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/overview.html> |
| **Confluent Schema Registry 服务端**（`core`，即 cp-schema-registry 主体） | **Confluent Community License** | 仓库根 LICENSE 为 CCL，并注明部分子目录另有许可：<https://github.com/confluentinc/schema-registry/blob/master/LICENSE> |
| **Confluent Schema Registry 客户端**（`client`） | **Apache-2.0** | `client/pom.xml` `<licenses>` 声明 "Apache License 2.0"：<https://github.com/confluentinc/schema-registry/blob/master/client/pom.xml> |
| **Confluent Avro 序列化器**（`kafka-avro-serializer`） | **Apache-2.0** | `avro-serializer/pom.xml` `<licenses>` 声明 "Apache License 2.0"：<https://github.com/confluentinc/schema-registry/blob/master/avro-serializer/pom.xml> |
| **Confluent Clients** | Apache-2.0 | <https://docs.confluent.io/platform/current/installation/license.html> |
| **Confluent Server / Control Center / Commercial & Premium Connectors / RBAC / Cluster Linking / Replicator / Security Plugins** | **Confluent Enterprise License**（需付费 license key） | <https://docs.confluent.io/platform/current/installation/license.html> |
| **PostgreSQL JDBC (pgjdbc)** | **BSD-2-Clause** | LICENSE 原文："Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met..." <https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE> |
| **MySQL Connector/J** | **GPLv2 + Universal FOSS Exception 1.0** | 见第 5 节 |

**关键的好消息 [技术判断]**：Avro 序列化/反序列化的**客户端**库（`kafka-avro-serializer`、`kafka-schema-registry-client`）是 **Apache-2.0**。也就是说，DBX 自己的 Java 代码（Spring Boot）依赖这些库、并把它们打进 DBX 的 JAR，**没有任何 CCL 问题**。CCL 只涉及两个可执行体：JDBC connector 插件 JAR 和 Schema Registry 服务端。

**关键的坏消息 [技术判断]**：Confluent JDBC connector 从 **Confluent Platform 5.1（2018 年 12 月）** 起才变成 CCL；此前版本是 Apache 2.0。旧版本可以用 Apache 2.0，但已严重过时，不是可行选项。
来源：<https://www.confluent.io/blog/license-changes-confluent-platform/>

---

## 4. Docker 镜像的许可（分发发行包时的坑）

Confluent 的官方镜像页面按镜像列出内含软件包及其许可：

| 镜像 | 内含包与许可 |
|---|---|
| `cp-kafka` | `confluent-kafka`（Apache 2.0） |
| `cp-schema-registry` | `confluent-schema-registry`（**Confluent Community License**）；telemetry / security / control-center 相关包为 **Confluent Enterprise License** |
| `cp-kafka-connect` | `confluent-schema-registry`（CCL）；**`confluent-hub-client`（Confluent Enterprise License）** |
| `cp-server` / `cp-server-connect` | `cp-server`、`cp-security`、`cp-rebalancer` 等均为 **Confluent Enterprise License** |
| `cp-enterprise-control-center-next-gen` | **Confluent Enterprise License** |

来源：<https://docs.confluent.io/platform/current/installation/docker/image-reference.html>

Docker Hub 镜像页明确："Usage of this image is subject to the license terms of the software contained within."
来源：<https://hub.docker.com/r/confluentinc/cp-schema-registry>、<https://github.com/confluentinc/schema-registry-images>

**对 DBX 的行动项 [技术判断 + 需法律确认]**：

1. **绝不能**在发行包里分发 `cp-server`、`cp-server-connect`、`cp-enterprise-control-center*`——这些是 Enterprise License，需要付费 license key，私自分发是违约。
2. 用 `cp-kafka`（或社区版 Kafka 镜像）+ `cp-kafka-connect` + `cp-schema-registry`。
3. `cp-kafka-connect` 内含 Enterprise License 的 `confluent-hub-client`。若要在客户处分发该镜像，**要么**自己基于 Apache 2.0 的构建工具链（Confluent 声明镜像构建工具本身是 Apache 2.0，见 <https://github.com/confluentinc/schema-registry-images>）重新构建一个不含 hub-client 的镜像，**要么**由律师确认分发含该组件是否可接受。这是**目前最容易被忽略的具体违约点**。
4. 更干净的方案：自己用 Apache 2.0 的 Kafka Connect 基础镜像（或直接从 Apache Kafka 二进制包构建），只把 CCL 的 connector JAR 放进去。这样镜像里只有 Apache-2.0 + CCL 两种许可，边界清晰。

---

## 5. JDBC 驱动的分发许可

### 5.1 PostgreSQL JDBC —— 无问题

pgjdbc 采用 **BSD-2-Clause**（原文见 <https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE>）。二进制再分发只要求：保留版权声明、条件列表和免责声明于文档或随附材料中。**允许闭源商业分发。[技术判断，风险极低]**

顺带：Confluent JDBC connector **已经内置**了 PostgreSQL 驱动——"The JDBC Source and Sink connectors include the open source PostgreSQL JDBC 4.0 driver"，"no additional steps are necessary before running a connector to PostgreSQL databases"。
来源：<https://docs.confluent.io/kafka-connectors/jdbc/current/jdbc-drivers.html>

### 5.2 MySQL Connector/J —— **这是本次研究中最需要谨慎处理的一项**

**许可原文**（Connector/J 9.7.0 Community 的 LICENSE 文件）：

> "This software is released under version 2 of the GNU General Public License (GPLv2), as set forth below, with the following additional permissions: ... Without limiting the foregoing grant of rights under the GPLv2 and additional permission as to separately licensed software, this Connector is also subject to the Universal FOSS Exception, version 1.0..."

来源：<https://github.com/mysql/mysql-connector-j/blob/release/9.x/LICENSE>

**Universal FOSS Exception 1.0 的适用边界**（这是关键）：

- UFE 把 "Other FOSS" 定义为"distributed with complete corresponding source under a license that is OSI-approved and/or categorized by the FSF as free"的软件。
- UFE 结尾明确限缩：

  > "Nothing in this additional permission grants any right to distribute any portion of the Software on terms other than those of the Software License or grants any additional permission of any kind for use or distribution of the Software in conjunction with software other than Other FOSS."

来源：<https://oss.oracle.com/licenses/universal-foss-exception/>

**推论 [法律判断，需律师确认]**：

1. DBX 是专有商业软件，**不是** "Other FOSS"（没有以 OSI/FSF 认可的许可开放完整对应源码）。因此 **UFE 对 DBX 不适用**，只剩下裸的 GPLv2。
2. 更麻烦的是：即便只看 Kafka Connect 这一层，**Confluent JDBC connector 本身是 CCL——CCL 也不是 OSI 认可的自由软件许可**（见 2.3 节），所以 CCL connector 同样不是 "Other FOSS"，UFE 对它也不适用。把 CCL connector 和 GPLv2 驱动打进同一个发行包、同一个 JVM 里跑，构成 GPL 合规风险。
3. **Confluent 自己的做法证实了这个风险**：其 JDBC connector 打包了 PostgreSQL、SQLite、jTDS、Microsoft SQL Server 驱动，但**要求用户自行下载 MySQL Connector/J**（"select the Platform Independent Compressed TAR Archive option and extract the JAR file into the connector directory"）。Oracle DB 和 DB2 驱动同样要求自行下载。
   来源：<https://docs.confluent.io/kafka-connectors/jdbc/current/jdbc-drivers.html>
   （注：Confluent 文档没有明说这是许可原因，但 Debezium 的文档对同类情况明说了 "Some databases (Db2, Oracle) require manually-obtained JDBC drivers due to licensing restrictions" —— <https://debezium.io/documentation/reference/stable/connectors/jdbc.html>）

**DBX 的可选处置方案（按推荐度排序）**：

| 方案 | 说明 | 评价 |
|---|---|---|
| **A. 不打包，安装时由客户提供/下载** | 沿用 Confluent 自己的模式：安装器在客户环境中提示放入 Connector/J JAR。内网离线场景下，改为"客户从 Oracle 官网下载后放入指定目录"。 | ✅ **推荐**。风险最低，且与上游做法一致。代价是离线安装体验多一步。 |
| **B. 换用 MariaDB Connector/J（LGPL-2.1）** | 许可为 LGPL-2.1（来源：<https://github.com/mariadb-corporation/mariadb-connector-j> 的 GitHub 声明许可 `LGPL-2.1`）。LGPL 允许专有软件动态链接并分发，只需保证用户可替换该库。 | ⚠️ 可行但需验证：MariaDB 驱动连 MySQL 8.0 的兼容性（尤其是 `caching_sha2_password` 认证、JSON/几何类型、时区处理）必须实测。LGPL 的"可替换库"义务在 JAR 场景下通常自动满足，但仍需律师确认。 |
| **C. 向 Oracle 购买 MySQL Connector/J 商业许可** | Oracle 对 Connector/J 提供 GPL 之外的商业许可（双许可模式）。 | 💰 花钱能彻底解决，但要走 Oracle 采购流程，且与产品定价挂钩。 |
| **D. 论证"独立进程 + 动态链接"不触发 GPL 传染** | 驱动由 Kafka Connect（Apache 2.0）进程通过 JDBC 标准接口动态加载，DBX 的 Spring Boot 进程不直接链接它。 | ❌ **不要依赖这个论证**做分发决策。它在"是否可以放进同一发行包"这个问题上帮助有限，且 FSF 对 Java 动态加载的立场偏严。可以作为律师评估时的辅助材料，不能作为结论。 |

**明确标注 [法律判断]**：GPLv2 是否因"把驱动 JAR 放进 DBX 发行包"而传染到 DBX 专有代码，是一个真实存在争议的法律问题（"mere aggregation" vs "derivative work"）。**必须律师确认。** 但方案 A 可以让这个问题完全不必回答——这是选它的主要理由。

---

## 6. Apache 2.0 替代方案与能力差距

即便结论是"CCL 可以分发"，仍建议保留这条路线作为逃生舱：应对客户采购的开源许可白名单要求，以及 Confluent 未来再次改许可的风险。

### 6.1 Aiven JDBC Connector for Apache Kafka（替代 Confluent JDBC Connector）

- **许可：Apache-2.0**。来源：<https://github.com/Aiven-Open/jdbc-connector-for-apache-kafka>（GitHub API `spdx_id: Apache-2.0`）
- **来历**：README 原文——"The project originates from Confluent kafka-connect-jdbc. The code was forked before the change of the project's license."
  来源：<https://github.com/Aiven-Open/jdbc-connector-for-apache-kafka/blob/master/README.md>
- **能力**（来源：<https://github.com/Aiven-Open/jdbc-connector-for-apache-kafka/blob/master/docs/sink-connector.md>）：
  - Source + Sink **都有**（这是相对 Debezium JDBC 的关键优势）
  - Sink 支持 insert / multi / upsert 插入模式
  - 自动方言识别，或用 `dialect.name` 显式指定；支持 PostgreSQL、MySQL、MariaDB、SQLite、Derby 等
  - 要求 record value 为带 schema 的 struct → 配 Avro converter 完全可行（Avro converter 本身是 Apache-2.0，见第 3 节）

**能力差距 [技术判断，需实测验证]**：

| 维度 | 风险 |
|---|---|
| Fork 时间点（2018 年，Confluent 5.0 前后） | Confluent 版本此后 7 年的 bug 修复与新特性（新方言、类型映射修正、性能改进）未必都被 Aiven 回移。**MySQL 8.0 → PostgreSQL 15 的类型映射边界（DECIMAL 精度、TIMESTAMP/时区、BLOB/TEXT、无符号整型、ENUM/SET）必须逐一实测。** |
| 自动建表 / schema 演进 | Confluent 版有 `auto.create` / `auto.evolve`；Aiven fork 的对应支持范围需查配置参考确认。 |
| Source 端增量模式 | Confluent 版支持 incrementing / timestamp / timestamp+incrementing / custom query / bulk（来源：<https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/overview.html>）。Aiven fork 继承了这套设计，但具体行为需实测。 |
| 维护活跃度 | 需评估近期 commit 与 release 频率。 |

### 6.2 Debezium JDBC Sink Connector

- **许可：Apache-2.0**。来源：<https://github.com/debezium/debezium/blob/main/LICENSE.txt>
- **能力**（来源：<https://debezium.io/documentation/reference/stable/connectors/jdbc.html>）：
  - 支持数据库："CockroachDB, Db2, MySQL, Oracle, PostgreSQL, SingleStore, SQL Server, and StarRocks"
  - 插入模式：`insert` / `update` / `upsert`（upsert 提供幂等写入）
  - 主键策略：`none` / `kafka` / `record_key` / `record_value`
  - Schema 演进：`none` / `validate-only` / `basic`（basic 会自动加新字段）
- **能力差距 [技术判断]**：
  - **只有 Sink，没有 Source。** DBX 的 MySQL 侧抽取需要另找方案（Aiven JDBC source，或 Debezium MySQL CDC source——但 CDC 是增量复制模型，与"离线迁移"的产品定位不同，会显著改变架构）。
  - Schema 演进受限：文档明确"Cannot modify existing column types, rename, or drop columns"；不支持 schema change topic。

### 6.3 Apicurio Registry（替代 Confluent Schema Registry）

- **许可：Apache-2.0**。来源：<https://github.com/Apicurio/apicurio-registry>（GitHub API `spdx_id: Apache-2.0`）；官网："Code is open source and released under Apache License, v2.0" <https://www.apicur.io/registry/>
- **Confluent 兼容 API**：提供 `ccompat` 端点，客户端只需改 URL 即可无缝切换：
  > `schema.registry.url=http://my-registry:8080/apis/ccompat/v7`

  支持 v7 与 v8 端点；覆盖 schema/subject 的完整 CRUD、兼容性检查、全局与 subject 级配置、mode 管理（READWRITE/READONLY/READONLY_OVERRIDE/IMPORT）、Avro + JSON Schema + Protobuf 三种类型、分页。
  来源：<https://www.apicur.io/registry/docs/apicurio-registry/3.2.x/getting-started/assembly-confluent-schema-registry-compatibility.html>
- **已知不支持**（同一来源）：
  - Schema Linking / Exporters（`GET /exporters` 返回空，其他 exporter 操作报错）
  - 加密相关的 KEK/DEK 端点（返回 404）
  - Cluster Metadata API（`/v1/metadata/id`、`/v1/metadata/config`）
  - Data Contracts 强制（`metadata` 与 `ruleSet` 字段被接受但不存储、不强制）

**对 DBX 的影响 [技术判断]**：以上四项缺失**对 DBX v1 的离线迁移场景都用不上**。Apicurio 是一个低风险的直接替换项。
额外好处：DBX 仍可继续使用 Confluent 的 `kafka-avro-serializer`（Apache-2.0），只是把 `schema.registry.url` 指向 Apicurio 的 ccompat 端点；也可以改用 Apicurio 自己的 Apache-2.0 serdes。

### 6.4 替代路线小结

| 需求 | 现方案（含 CCL） | Apache-2.0 替代 | 差距 |
|---|---|---|---|
| MySQL 抽取（source） | Confluent JDBC Source（CCL） | Aiven JDBC Source（Apache-2.0） | 需实测 MySQL 8.0 类型映射与增量模式 |
| PostgreSQL 写入（sink） | Confluent JDBC Sink（CCL） | Aiven JDBC Sink 或 Debezium JDBC Sink（均 Apache-2.0） | Debezium sink 的 schema 演进受限 |
| Schema Registry | Confluent SR（CCL） | Apicurio Registry（Apache-2.0，ccompat v7/v8） | 缺失项与 DBX 场景无关 |
| Avro 序列化 | `kafka-avro-serializer` | **本来就是 Apache-2.0，无需替换** | 无 |
| Kafka / Connect | Apache Kafka | **本来就是 Apache-2.0，无需替换** | 无（只需避开 `cp-server*` 镜像） |

**结论 [技术判断]**：一条完整的 100% Apache-2.0 路线是存在的（Kafka + Aiven JDBC connector + Apicurio Registry + Confluent Avro serdes）。切换成本主要是 connector 的兼容性回归测试，不是架构重写。

---

## 7. 行动建议

### 立即执行（工程侧）

1. **不打包 MySQL Connector/J。** 安装器在部署阶段要求客户提供该 JAR（离线场景提供清晰的下载指引与校验和）。这是本研究中唯一具备明确操作性的高优先级动作。
2. **镜像基线**：只用 `cp-kafka` / `cp-kafka-connect` / `cp-schema-registry`，禁用 `cp-server*` 与 `cp-enterprise-control-center*`。进一步排查并移除 `cp-kafka-connect` 中的 `confluent-hub-client`（Enterprise License）。
3. **建立 SBOM 与 NOTICE 流程**：发行包中包含 `THIRD-PARTY-LICENSES` 目录，逐组件列出许可全文（CCL 全文、Apache-2.0、BSD-2-Clause 等），并保留全部版权与修改声明。
4. **保留逃生舱**：为 Aiven JDBC connector + Apicurio Registry 建立一套可运行的兼容性回归测试（MySQL 8.0 → PostgreSQL 15 全类型矩阵），一旦采购或许可环境变化可快速切换。

### 必须由律师确认（不要自行判断）

1. DBX 的产品形态是否确实不构成 CCL 的 "Excluded Purpose"（尤其若未来推出托管版本）。
2. DBX EULA 中如何正确表述 CCL 组件的许可关系（non-sublicenseable 的处理）。
3. 把 GPLv2 的 MySQL Connector/J 与专有 DBX 一同分发的 GPL 传染风险；以及 MariaDB Connector/J（LGPL-2.1）替代方案的合规边界。
4. 分发含 Confluent Enterprise License 组件（如 `confluent-hub-client`）的镜像是否违约。
5. 目标市场（中国大陆）法院对加州法+仲裁条款的可执行性，以及该管辖条款对争议成本的影响。

---

## 附：全部一手来源清单

**Confluent 官方**
- Confluent Community License Agreement v1.0（许可原文）：<https://www.confluent.io/confluent-community-license/>
- Confluent Community License FAQ：<https://www.confluent.io/confluent-community-license-faq/>
- 改许可公告博客：<https://www.confluent.io/blog/license-changes-confluent-platform/>
- 开发者指南博客：<https://www.confluent.io/blog/developers-guide-confluent-community-license/>
- Confluent Platform 许可总览：<https://docs.confluent.io/platform/current/installation/license.html>
- 自管连接器许可：<https://docs.confluent.io/platform/current/connect/license.html>
- Docker 镜像许可清单：<https://docs.confluent.io/platform/current/installation/docker/image-reference.html>
- JDBC Source Connector 概览（含许可声明）：<https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/overview.html>
- JDBC Sink Connector 概览：<https://docs.confluent.io/kafka-connectors/jdbc/current/sink-connector/overview.html>
- JDBC 驱动打包说明：<https://docs.confluent.io/kafka-connectors/jdbc/current/jdbc-drivers.html>

**仓库 LICENSE / POM（一手）**
- `confluentinc/kafka-connect-jdbc` LICENSE：<https://github.com/confluentinc/kafka-connect-jdbc/blob/master/LICENSE>
- `confluentinc/kafka-connect-jdbc` pom.xml：<https://github.com/confluentinc/kafka-connect-jdbc/blob/master/pom.xml>
- `confluentinc/schema-registry` LICENSE：<https://github.com/confluentinc/schema-registry/blob/master/LICENSE>
- `confluentinc/schema-registry` client/pom.xml：<https://github.com/confluentinc/schema-registry/blob/master/client/pom.xml>
- `confluentinc/schema-registry` avro-serializer/pom.xml：<https://github.com/confluentinc/schema-registry/blob/master/avro-serializer/pom.xml>
- `confluentinc/schema-registry-images`：<https://github.com/confluentinc/schema-registry-images>
- `apache/kafka`：<https://github.com/apache/kafka>
- `Aiven-Open/jdbc-connector-for-apache-kafka` README + LICENSE：<https://github.com/Aiven-Open/jdbc-connector-for-apache-kafka>
- `debezium/debezium` LICENSE.txt：<https://github.com/debezium/debezium/blob/main/LICENSE.txt>
- `Apicurio/apicurio-registry` LICENSE：<https://github.com/Apicurio/apicurio-registry/blob/main/LICENSE>
- `pgjdbc/pgjdbc` LICENSE：<https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE>
- `mysql/mysql-connector-j` LICENSE：<https://github.com/mysql/mysql-connector-j/blob/release/9.x/LICENSE>
- `mariadb-corporation/mariadb-connector-j`：<https://github.com/mariadb-corporation/mariadb-connector-j>

**许可机构 / 其他**
- OSI 批准许可列表：<https://opensource.org/licenses>
- SPDX 许可清单数据：<https://github.com/spdx/license-list-data/blob/main/json/licenses.json>
- Oracle Universal FOSS Exception 1.0：<https://oss.oracle.com/licenses/universal-foss-exception/>
- Debezium JDBC Sink 文档：<https://debezium.io/documentation/reference/stable/connectors/jdbc.html>
- Apicurio Registry Confluent 兼容 API：<https://www.apicur.io/registry/docs/apicurio-registry/3.2.x/getting-started/assembly-confluent-schema-registry-compatibility.html>
- Apicurio Registry 官网：<https://www.apicur.io/registry/>
- Docker Hub `cp-schema-registry`：<https://hub.docker.com/r/confluentinc/cp-schema-registry>
