# Resource-bounded immutable box scheduling

DBX v1 optimizes total migration makespan while keeping database and Kafka safety limits deterministic and explainable. Each run first groups tables by identical connector-level execution signature, isolates query-mode and large-record tables, and packs ordinary tables by conservatively estimated bytes using largest-processing-time-first balancing; box target size is the Kafka disk safety budget divided by computed maximum concurrency, with at most 50 tables per box. Execution uses rolling admission with large-box starvation protection rather than strict waves.

Concurrency is resource-aware but not runtime-adaptive: Connect tasks default to twice the logical CPU count, no more than 10 connector-active boxes may coexist, and source and target connection budgets independently default to 10% of each database's `max_connections` clamped to 4–20 with two connections reserved on each side. Kafka admission uses 60% of current available data-disk capacity, warns at 80%, and stops producing Sources at 90% usage or below 10 GB free; retention never substitutes for preserving unvalidated data.

A run's scheduling plan and execution signatures become immutable when the run starts. Restarts reconcile that plan with Connect, Kafka, and PostgreSQL facts; only an explicit rerun creates a new run and repacks tables. Connector, database, box-slot, and topic-disk resources are released at their actual lifecycle boundaries. Box failure stops only that box, leaves independent boxes running, preserves evidence, and never triggers an automatic retry or failure-rate shutdown.

Preflight estimates transfer bytes as 1.5 times the greater of MySQL `DATA_LENGTH` and exact frozen row count times average row length, using bounded sampling when statistics are unusable. Exact empty tables bypass Kafka but still undergo DDL and empty-set validation. Real-time progress polls Kafka offsets every 10 seconds; expensive target `COUNT(*)` queries occur only at completion boundaries, rechecks, validation, or manual diagnosis. A first run shows no completion estimate until at least five minutes and 1% of data provide a useful throughput sample, after which DBX shows a confidence-qualified range rather than a precise promise.

## Considered options

- Fixed per-box GB limits were rejected because deploy-time disk and concurrency differ across customer environments.
- Strict execution waves were rejected because a slow box would leave released capacity idle.
- Runtime adaptive concurrency was rejected for v1 because it is harder to explain, reproduce, and keep stable against database pressure.
- Packing solely by table count or row count was rejected because row width and large records dominate Kafka memory and disk risk.

## Consequences

Connector-level settings can create many small compatibility groups, especially when incrementing column names differ; DBX accepts this as the truthful cost of the JDBC Connector configuration model. A box remains disposable between runs but is immutable within a run so connector names, topics, recovery, and failure attribution remain stable.
