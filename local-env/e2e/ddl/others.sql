-- S5/S6/S7 用到的目标表。同样按 #11 矩阵 + #23 列属性手写。

-- ------------------------------------------------ S5：大字段
DROP TABLE IF EXISTS t_large_blob;
CREATE TABLE t_large_blob (
  id         integer PRIMARY KEY,
  label      varchar(64) NOT NULL,
  c_blob     bytea,
  c_longblob bytea
);

DROP TABLE IF EXISTS t_large_text;
CREATE TABLE t_large_text (
  id         integer PRIMARY KEY,
  label      varchar(64) NOT NULL,
  c_text     text,
  c_longtext text
);

-- ------------------------------------------------ S6：无主键表
-- #23：源表无主键且无全非空唯一索引 → 目标表不建主键，重跑必须清空目标表
DROP TABLE IF EXISTS t_no_pk;
CREATE TABLE t_no_pk (
  event_time timestamp(3) NOT NULL,
  source     varchar(64)  NOT NULL,
  payload    varchar(255)
);

-- ------------------------------------------------ S6：复合主键表
DROP TABLE IF EXISTS t_composite_pk;
CREATE TABLE t_composite_pk (
  tenant_id  integer      NOT NULL,
  order_no   varchar(32)  NOT NULL,
  amount     numeric(18,4) NOT NULL,
  created_at timestamp(3) NOT NULL,
  PRIMARY KEY (tenant_id, order_no)
);
