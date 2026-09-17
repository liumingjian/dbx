-- DBX 实验床：小体量种子数据（边界值为主）
--
-- 每张表都刻意放了 典型值 / 上界 / 下界 / 全 NULL 四类行。
-- 类型映射错了，最先在上界和下界那两行露馅。

USE dbx_src;

-- ---------------------------------------------------------------- t_types
-- 典型值
INSERT INTO t_types (
  c_decimal, c_tinyint1, c_bool, c_smallint_u, c_int_u, c_bigint, c_bigint_u,
  c_double, c_float, c_bit1, c_date, c_datetime6, c_timestamp6, c_time6, c_year,
  c_char, c_varchar, c_varbinary, c_enum, c_set, c_json, c_nullable
) VALUES (
  '123.4567890123', 1, TRUE, 100, 100000, 100000, 100000,
  3.141592653589793, 3.14159, b'1', '2026-07-24', '2026-07-24 10:30:00.123456',
  '2026-07-24 10:30:00.123456', '12:34:56.789012', 2026,
  'abcdefghij', '普通中文', x'DEADBEEF', 'medium', 'a,b', '{"k":"v","n":1}', 'not null'
);

-- 上界：每个类型的最大值。DECIMAL(38,10) 允许 28 位整数部分。
INSERT INTO t_types (
  c_decimal, c_tinyint1, c_bool, c_smallint_u, c_int_u, c_bigint, c_bigint_u,
  c_double, c_float, c_bit1, c_date, c_datetime6, c_timestamp6, c_time6, c_year,
  c_char, c_varchar, c_varbinary, c_enum, c_set, c_json, c_nullable
) VALUES (
  '1234567890123456789012345678.0123456789', 127, TRUE, 65535, 4294967295,
  9223372036854775807, 18446744073709551615,   -- BIGINT UNSIGNED 上界，#5 的头号升宽陷阱
  1.7976931348623157E308, 3.4028234E38, b'1',   -- FLOAT 别写满 3.402823466E38，严格模式下会因舍入判越界
  '9999-12-31', '9999-12-31 23:59:59.999999',
  '2038-01-19 03:14:07.999999',                 -- TIMESTAMP 的 2038 天花板，DATETIME 没有
  '838:59:59.000000', 2155,
  'ZZZZZZZZZZ',
  '中文与 emoji 混排 🚚📦🍜 —— utf8mb4 四字节字符',
  x'FFFFFFFFFFFFFFFF', 'large', 'a,b,c',
  '{"nested":{"arr":[1,2,3],"zh":"中文","emoji":"🚚"},"big":12345678901234567890}',
  'upper bound'
);

-- 下界：负数与最小值
INSERT INTO t_types (
  c_decimal, c_tinyint1, c_bool, c_smallint_u, c_int_u, c_bigint, c_bigint_u,
  c_double, c_float, c_bit1, c_date, c_datetime6, c_timestamp6, c_time6, c_year,
  c_char, c_varchar, c_varbinary, c_enum, c_set, c_json, c_nullable
) VALUES (
  '-1234567890123456789012345678.0123456789', -128, FALSE, 0, 0,
  -9223372036854775808, 0,
  -1.7976931348623157E308, -3.4028234E38, b'0',
  '1000-01-01', '1000-01-01 00:00:00.000000',
  '1970-01-01 00:00:01.000000',                 -- TIMESTAMP 的下界
  '-838:59:59.000000', 1901,
  '', '', x'00', 'small', '', '[]', ''
);

-- 全 NULL：验证 NULL 是否原样过桥（Sink 只按列名匹配，#4）
INSERT INTO t_types (
  c_decimal, c_tinyint1, c_bool, c_smallint_u, c_int_u, c_bigint, c_bigint_u,
  c_double, c_float, c_bit1, c_date, c_datetime6, c_timestamp6, c_time6, c_year,
  c_char, c_varchar, c_varbinary, c_enum, c_set, c_json, c_nullable
) VALUES (
  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
);

-- ---------------------------------------------------------------- t_no_pk
INSERT INTO t_no_pk (event_time, source, payload) VALUES
  ('2026-07-24 09:00:00.000000', 'sensor-a', 'reading 1'),
  ('2026-07-24 09:00:01.000000', 'sensor-b', 'reading 2'),
  ('2026-07-24 09:00:02.000000', 'sensor-a', 'reading 3'),
  -- 完全重复的两行：无主键表上「重复检查」无从下手，校验规格 #16 得正视这一点
  ('2026-07-24 09:00:03.000000', 'sensor-c', 'duplicate'),
  ('2026-07-24 09:00:03.000000', 'sensor-c', 'duplicate');

-- ------------------------------------------------------------ t_composite_pk
INSERT INTO t_composite_pk (tenant_id, order_no, amount, created_at) VALUES
  (1, 'ORD-0001', 99.9900,        '2026-07-24 08:00:00.000000'),
  (1, 'ORD-0002', 0.0001,         '2026-07-24 08:00:01.000000'),
  (2, 'ORD-0001', -12345.6789,    '2026-07-24 08:00:02.000000'),   -- 同 order_no，不同 tenant
  (2, '订单-中文-🚚', 1.0000,      '2026-07-24 08:00:03.000000');   -- 主键里带四字节字符
