# DBX migration wizard prototype

> Throwaway UI prototype for GitHub issue #21. This is not production code: all mutations are in-memory only and no database or credential is contacted.

## Run

From the repository root:

```bash
python3 -m http.server 4173 --directory prototype/migration-wizard
```

Open <http://localhost:4173/?variant=A>. The user lands on A; the bottom switcher preserves `?variant=A|B|C` for provenance and comparison.

## Current direction after user feedback

Variant A is the primary route: a guided six-stage migration journey inside the DBX shell. The persistent dark navigation belongs to the product (作业中心 / 数据源 / 系统设置); the journey itself has only one horizontal progress header, avoiding a duplicate vertical stage rail. Its compact enterprise density and configuration workspace are informed by the X2Doris reference article and screenshots at <https://cloud.tencent.com/developer/article/2550911> without copying its branding, pixels, or editable-DDL behavior.

1. **连接与数据库** selects existing, separately managed source and target database connections, then explicitly chooses one source MySQL database and one target PostgreSQL schema. There is no inline credential or arbitrary JDBC configuration.
2. **迁移范围** uses a small source-database tree and four representative tables rather than hundreds of repetitive fixtures. Every table shows its current condition and supports explicit selection or exclusion; search and sorting remain, while regex selection is deliberately absent.
3. **逐表配置** gives every table its own configuration space with field mapping, mandatory preflight evidence, and a complete read-only rendering of its table write contract. Automatic mapping is the default; only structured exceptions require DBA intervention. A user cannot bypass an unsupported or inconclusive preflight.
4. **执行确认** summarizes the exact included and excluded tables and requires a time-bounded source write-freeze commitment before a migration run can start.
5. **运行监控** keeps the table as the observable unit: every included table has its own phase, progress, technical result, update time, and timeline.
6. **验证报告** keeps `PASS`, `FAIL`, and `INCONCLUSIVE` distinct from explicit preflight exclusion and accepted-risk disposition. Accepting risk never changes the technical result. Re-migrating eligible failed or inconclusive tables creates a new migration run, new preflight, and new source baseline rather than resuming the old run.

## Example scope

The fixture is intentionally small enough to inspect completely:

- two source-database choices (`commerce` and `commerce_staging`), with one selected at a time;
- one verified MySQL source connection and one verified PostgreSQL target connection/schema;
- four example tables: `customers`, `orders`, `order_items`, and `audit_log`;
- examples of automatic mapping, a structured `ENUM → text` exception, a supported preflight, and a 20 MiB blocker that must be corrected or explicitly excluded.

All counts and summaries are derived from these table records. An excluded table does not become a migration or validation failure.

## Variants and tradeoffs

- **A · 线性向导** is the active design direction: guided discovery and safety win over maximum information density.
- **B · 异常工作台** remains an earlier contrast concept that puts exceptions first for experienced operators.
- **C · 三个决策** remains an earlier contrast concept that compresses the journey for orientation.

B and C are kept only as provenance while feedback is gathered on A. They share the smaller fixture so they continue to render coherently, but they are not receiving further design work unless explicitly reopened.

## Prototype constraints

- Plain HTML/CSS/JS with no dependencies, persistence, SQL execution, authentication, database access, or real-time transport.
- Database connections are selected from existing verified records and maintained under 数据源.
- DDL is never an independent editable setting. It is a read-only rendering generated from approved source metadata, preflight findings, and structured mapping exceptions.
- The exact large-record preflight is mandatory. An unsupported or inconclusive result cannot be acknowledged away.
- Source write freeze is an externally enforced, time-bounded operator commitment; the prototype only records its confirmation.
- Platform scheduling resources such as boxes, connectors, and topics remain hidden from the DBA journey. The table is the durable observable unit.
- This remains directional validation for issue #21, not final approval of the journey or authorization to close the ticket.
