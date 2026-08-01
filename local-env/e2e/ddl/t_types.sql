-- S1 目标表：严格按 #11 的类型映射矩阵 + #23 的列属性规格手写。
--
-- 这不是"能跑就行"的 DDL —— 它就是本原型要验的东西：
-- 如果矩阵是对的，Sink 在 auto.create=false 下应当一行不差地写进来；
-- 如果哪一档错了，错误会在这里现形（#4：Sink 完全不校验类型，正确性 100% 由这份 DDL 负责）。
--
-- 逐字符小写列名，不加引号建表 → PG 存成小写，与 Connect 字段名逐字符相等（#4 的半大小写不敏感陷阱、#17）。

DROP TABLE IF EXISTS t_types;

CREATE TABLE t_types (
  id           integer PRIMARY KEY,        -- INT AUTO_INCREMENT；本票不验 IDENTITY，只验写入
  c_decimal    numeric(38,10),             -- #5：numeric.mapping 对 MySQL 无效，恒为 Connect Decimal
  c_tinyint1   smallint,                   -- #5：TINYINT(1) 默认落 INT8 而非 BOOLEAN；PG 无 int1 → 只放宽不收窄
  c_bool       smallint,                   -- 与上同物，用来验证 Connect 是否真能区分二者
  c_smallint_u integer,                    -- SMALLINT UNSIGNED 升宽
  c_int_u      bigint,                     -- INT UNSIGNED 升宽
  c_bigint     bigint,
  c_bigint_u   numeric(20,0),              -- #11：唯一无升宽分支的无符号类型
  c_double     double precision,
  c_float      real,
  c_bit1       boolean,                    -- BIT(1) 是"静默丢列"候选之一，跑完看它是否全 NULL
  c_date       date,
  c_datetime6  timestamp(3),               -- #11：微秒不支持，DDL 如实落 (3) 而非 (6)
  c_timestamp6 timestamp(3),               -- 与上同列型 → Connect 层不可区分（#5），原类型只能平台自己记
  c_time6      time(3),
  c_year       smallint,                   -- YEAR 是"静默丢列"候选之二
  c_char       char(10),                   -- #11：CHAR(M) → char(M)，保留补空格语义
  c_varchar    varchar(255),
  c_varbinary  bytea,
  c_enum       text,                       -- #11 定的是 text + CHECK；本票只验数据通路，CHECK 略
  c_set        text,                       -- SET 是"静默丢列"候选之三
  c_json       json,                       -- #11：json 而非 jsonb，保字节以让校验裸比对
  c_nullable   varchar(50)
);
