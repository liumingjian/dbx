-- DBX 实验床：MySQL 源库表结构
--
-- 每张表都对应一组要验证的东西，见 local-env/README.md「种子数据在验什么」。
-- 除 t_no_pk 外都有自增主键，好让 JDBC Source 能用 mode=incrementing（#3 的结论：
-- bulk 模式不写 offset，「查 offset 判完成」不成立，所以优先 incrementing）。

USE dbx_src;

-- 1) 标量类型全家桶。服务于 #11「类型映射矩阵定稿」。
--    重点不是"类型多"，而是 #5 点名的几个陷阱：BIGINT UNSIGNED 无升宽分支、
--    TINYINT(1) 默认落 INT8 而非 BOOLEAN、DATETIME 与 TIMESTAMP 在 Connect 层不可区分、
--    以及 BIT/SET/YEAR 这类"未知类型只打 WARN 返回 null"的静默丢列候选。
CREATE TABLE t_types (
  id           INT AUTO_INCREMENT PRIMARY KEY,
  c_decimal    DECIMAL(38,10),
  c_tinyint1   TINYINT(1),
  c_bool       BOOLEAN,              -- 与 TINYINT(1) 同物，验证 Connect 是否真能区分
  c_smallint_u SMALLINT UNSIGNED,
  c_int_u      INT UNSIGNED,
  c_bigint     BIGINT,
  c_bigint_u   BIGINT UNSIGNED,      -- #5：唯一没有升宽分支的无符号类型
  c_double     DOUBLE,
  c_float      FLOAT,
  c_bit1       BIT(1),
  c_date       DATE,
  c_datetime6  DATETIME(6),
  c_timestamp6 TIMESTAMP(6) NULL,
  c_time6      TIME(6),
  c_year       YEAR,
  c_char       CHAR(10),
  c_varchar    VARCHAR(255),         -- utf8mb4：中文 + emoji
  c_varbinary  VARBINARY(255),
  c_enum       ENUM('small','medium','large'),
  c_set        SET('a','b','c'),
  c_json       JSON,
  c_nullable   VARCHAR(50) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) 无主键表。JDBC Source 只能对它用 bulk 模式；重跑与断点续传语义都不一样。
--    刻意放了两行完全相同的记录：校验票 #16 的"主键重复检查"在这张表上无从下手。
CREATE TABLE t_no_pk (
  event_time DATETIME(6)  NOT NULL,
  source     VARCHAR(64)  NOT NULL,
  payload    VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 复合主键表。验证 Sink 的 pk.mode=record_key/record_value 与多列 PK 的 DDL 生成。
CREATE TABLE t_composite_pk (
  tenant_id  INT          NOT NULL,
  order_no   VARCHAR(32)  NOT NULL,
  amount     DECIMAL(18,4) NOT NULL,
  created_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (tenant_id, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) 长文本。TEXT 上限 64KB，1MiB / 19MiB 只能落 LONGTEXT。
CREATE TABLE t_large_text (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  label      VARCHAR(64) NOT NULL,
  c_text     TEXT,
  c_longtext LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5) 大二进制。含一条 25MiB 的"超限"行——它就是不该被迁走的那条，
--    用来验证 #15 的迁移前预检（SELECT MAX(LENGTH(col))）能在建表审核阶段拦住它。
CREATE TABLE t_large_blob (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  label      VARCHAR(64) NOT NULL,
  c_blob     BLOB,
  c_longblob LONGBLOB
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
