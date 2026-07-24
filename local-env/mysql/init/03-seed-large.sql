-- DBX 实验床：大字段种子数据
--
-- 数据必须是**不可压缩**的。Connect 侧配了 producer.compression.type=zstd，
-- 用 REPEAT('x', 19*1024*1024) 造出来的 19MiB 会被压成几 KB，
-- 于是 message.max.bytes（管的是压缩后的 record batch）永远不会被触发，
-- 整个大消息验证就成了空转。所以下面逐块拼 RANDOM_BYTES(1024) 的新鲜随机数据。
--
-- 首次启动时这段要跑一会儿（约几十秒到几分钟，取决于 CPU）；
-- compose 里 mysql 的 healthcheck start_period 已经放到 300s。

USE dbx_src;

DELIMITER $$

-- 拼出 target_bytes 字节的高熵随机数据。
-- RANDOM_BYTES 单次上限 1024 字节，所以先攒 256KiB 的块，再按块拼到目标大小。
CREATE PROCEDURE dbx_random_blob(IN target_bytes INT, OUT result LONGBLOB)
BEGIN
  DECLARE chunk LONGBLOB;
  DECLARE i INT;
  SET result = _binary'';
  WHILE LENGTH(result) < target_bytes DO
    SET chunk = _binary'';
    SET i = 0;
    WHILE i < 256 DO
      SET chunk = CONCAT(chunk, RANDOM_BYTES(1024));
      SET i = i + 1;
    END WHILE;
    SET result = CONCAT(result, chunk);
  END WHILE;
  SET result = SUBSTRING(result, 1, target_bytes);
END$$

DELIMITER ;

-- ------------------------------------------------------------ t_large_blob
-- 32KiB，落在 BLOB 列（BLOB 上限 64KB）
CALL dbx_random_blob(32768, @b);
INSERT INTO t_large_blob (label, c_blob, c_longblob) VALUES ('blob-32kib', @b, NULL);

-- 1MiB：常规大字段
CALL dbx_random_blob(1048576, @b);
INSERT INTO t_large_blob (label, c_blob, c_longblob) VALUES ('longblob-1mib', NULL, @b);

-- 19MiB：贴着 20MB 业务上限之下，应当能迁通
CALL dbx_random_blob(19922944, @b);
INSERT INTO t_large_blob (label, c_blob, c_longblob) VALUES ('longblob-19mib', NULL, @b);

-- 25MiB（= 26214400，正好等于 message.max.bytes）：加上 Avro 与 batch 框架开销必然超限。
-- 这条就是「不该被迁走」的那一行——#15 的迁移前预检要在建表审核阶段红字拦住它。
CALL dbx_random_blob(26214400, @b);
INSERT INTO t_large_blob (label, c_blob, c_longblob) VALUES ('longblob-25mib-over-limit', NULL, @b);

-- ------------------------------------------------------------ t_large_text
-- 60KiB，落在 TEXT 列（TEXT 上限 65535 字节）
CALL dbx_random_blob(46080, @b);
SET @t = SUBSTRING(REPLACE(TO_BASE64(@b), '\n', ''), 1, 61440);
INSERT INTO t_large_text (label, c_text, c_longtext) VALUES ('text-60kib', @t, NULL);

-- 1MiB ASCII（base64 后仍是高熵，压缩率有限）
CALL dbx_random_blob(786432, @b);
SET @t = SUBSTRING(REPLACE(TO_BASE64(@b), '\n', ''), 1, 1048576);
INSERT INTO t_large_text (label, c_text, c_longtext) VALUES ('longtext-1mib', NULL, @t);

-- 19MiB ASCII
CALL dbx_random_blob(14942208, @b);
SET @t = SUBSTRING(REPLACE(TO_BASE64(@b), '\n', ''), 1, 19922944);
INSERT INTO t_large_text (label, c_text, c_longtext) VALUES ('longtext-19mib', NULL, @t);

-- '数据迁移🚚' = 5 字符 / 16 字节。10 万次 = 50 万字符 / 160 万字节。
-- 这条是**字符数与字节数不等**的样本：预检写的是 LENGTH（字节）还是 CHAR_LENGTH（字符），
-- 在这里会差出 3.2 倍。内容可压缩，只用于字符集验证，不用于大小验证。
SET @t = REPEAT('数据迁移🚚', 100000);
INSERT INTO t_large_text (label, c_text, c_longtext) VALUES ('longtext-utf8mb4-charlen-trap', NULL, @t);

SET @b = NULL;
SET @t = NULL;
DROP PROCEDURE dbx_random_blob;
