#!/usr/bin/env bash
# Bulk dataset for the reference throughput band (ticket #63, map #1).
#
# Three table shapes stand in for the mix that dominates real schemas:
#   b_narrow_N  narrow keyed rows, ~120 B/row (orders, events, logs)
#   b_wide_N    wide text rows, ~2.3 KB/row (customer/product master data)
#   b_lob_N     large-record table: 1.5 MiB random LONGBLOB per row. Above 1 MiB, so
#               ADR-0003 classifies it as a large-record table (isolated box,
#               single-record Sink polling).
#
# Entropy is deliberate. Text columns are SHA2 hex (compresses about 2x under zstd,
# like real text). LOB bytes come from RANDOM_BYTES and don't compress (worst case).
# REPEAT() filler would let zstd inflate the measured throughput.
#
# Sizes are env-tunable. The defaults (~9 GB of InnoDB data) make each single stream
# run about two minutes on the reference Mac mini, so per-connector startup (~3 s) stays
# under a few percent of the measurement. Peak footprint is ~30 GB: MySQL, plus Kafka and
# PostgreSQL copies of the running phase, plus up to 4 GB of WAL. A 2.2 GB set
# (NARROW_ROWS=2000000 WIDE_ROWS=100000 LOB_ROWS=150) gave 15-28 s runs, too short.
#
#   ./e2e/bulk/seed-bulk.sh           # generate if missing, skip if counts already match
#   FORCE=1 ./e2e/bulk/seed-bulk.sh   # drop and regenerate
#
# Runs as root with sql_log_bin=0: MySQL 8 has binlog on by default, and it would
# write the whole dataset a second time to disk.

set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

NARROW_TABLES="${NARROW_TABLES:-4}"; NARROW_ROWS="${NARROW_ROWS:-8000000}"
WIDE_TABLES="${WIDE_TABLES:-3}";     WIDE_ROWS="${WIDE_ROWS:-600000}"
LOB_TABLES="${LOB_TABLES:-1}";       LOB_ROWS="${LOB_ROWS:-1000}"
LOB_BYTES="${LOB_BYTES:-1572864}"    # 1.5 MiB
CHUNK=100000

dc() { docker compose -f "$ENV_DIR/docker-compose.yml" "$@"; }
rootsql() { dc exec -T mysql mysql -N -B -uroot -pdbx dbx_src 2>/dev/null; }
rows_of() { echo "SELECT COUNT(*) FROM $1" | rootsql 2>/dev/null || echo 0; }

tables_of() {  # shape count -> table names
  local i; for i in $(seq 1 "$2"); do echo "b_$1_$i"; done
}

want_rows() {
  case "$1" in b_narrow_*) echo "$NARROW_ROWS";; b_wide_*) echo "$WIDE_ROWS";; b_lob_*) echo "$LOB_ROWS";; esac
}

ALL=( $(tables_of narrow "$NARROW_TABLES") $(tables_of wide "$WIDE_TABLES") $(tables_of lob "$LOB_TABLES") )

if [ "${FORCE:-0}" != 1 ]; then
  ok=1
  for t in "${ALL[@]}"; do [ "$(rows_of "$t")" = "$(want_rows "$t")" ] || { ok=0; break; }; done
  if [ $ok = 1 ]; then echo "==> bulk dataset already in place, skipping (FORCE=1 regenerates)"; exit 0; fi
fi

echo "==> helper sequence table (0..$((CHUNK-1)))"
rootsql <<SQL
SET SESSION sql_log_bin=0;
DROP TABLE IF EXISTS b_seq;
CREATE TABLE b_seq (n INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO b_seq
  SELECT a.d + 10*b.d + 100*c.d + 1000*e.d + 10000*f.d
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) e,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) f;
SQL

# Insert $2 rows into $1 in CHUNK-sized autocommit statements; $3 is the SELECT list.
fill() {
  local t="$1" total="$2" cols="$3" select="$4" done=0 n
  while [ "$done" -lt "$total" ]; do
    n=$(( total - done < CHUNK ? total - done : CHUNK ))
    echo "SET SESSION sql_log_bin=0; INSERT INTO $t ($cols) SELECT $select FROM b_seq WHERE n < $n;" | rootsql
    done=$(( done + n ))
    printf '    %s %d/%d\r' "$t" "$done" "$total"
  done
  echo
}

for t in $(tables_of narrow "$NARROW_TABLES"); do
  echo "==> $t ($NARROW_ROWS rows)"
  rootsql <<SQL
SET SESSION sql_log_bin=0;
DROP TABLE IF EXISTS $t;
CREATE TABLE $t (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id INT NOT NULL,
  status     TINYINT NOT NULL,
  amount     DECIMAL(18,4) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  code       VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL
  fill "$t" "$NARROW_ROWS" "account_id, status, amount, created_at, code" \
    "FLOOR(RAND()*1000000), FLOOR(RAND()*5), ROUND(RAND()*100000, 4),
     TIMESTAMP('2020-01-01') + INTERVAL FLOOR(RAND()*157680000000000) MICROSECOND,
     LEFT(MD5(RAND()), 24)"
done

H2='CONCAT(SHA2(RAND(),256), SHA2(RAND(),256))'   # 128 hex chars
for t in $(tables_of wide "$WIDE_TABLES"); do
  echo "==> $t ($WIDE_ROWS rows)"
  rootsql <<SQL
SET SESSION sql_log_bin=0;
DROP TABLE IF EXISTS $t;
CREATE TABLE $t (
  id  BIGINT AUTO_INCREMENT PRIMARY KEY,
  c01 VARCHAR(255), c02 VARCHAR(255), c03 VARCHAR(255), c04 VARCHAR(255), c05 VARCHAR(255),
  c06 VARCHAR(255), c07 VARCHAR(255), c08 VARCHAR(255), c09 VARCHAR(255), c10 VARCHAR(255),
  note       TEXT,
  amount     DECIMAL(18,4),
  created_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL
  fill "$t" "$WIDE_ROWS" "c01,c02,c03,c04,c05,c06,c07,c08,c09,c10,note,amount,created_at" \
    "$H2,$H2,$H2,$H2,$H2,$H2,$H2,$H2,$H2,$H2,
     CONCAT($H2,$H2,$H2,$H2,$H2,$H2,$H2,$H2),
     ROUND(RAND()*100000, 4), TIMESTAMP('2020-01-01') + INTERVAL FLOOR(RAND()*157680000) SECOND"
done

for t in $(tables_of lob "$LOB_TABLES"); do
  echo "==> $t ($LOB_ROWS rows x $LOB_BYTES bytes, random)"
  rootsql <<SQL
SET SESSION sql_log_bin=0;
DROP TABLE IF EXISTS $t;
CREATE TABLE $t (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  label     VARCHAR(64) NOT NULL,
  c_payload LONGBLOB
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP PROCEDURE IF EXISTS b_fill_lob;
DELIMITER \$\$
CREATE PROCEDURE b_fill_lob(IN nrows INT, IN nbytes INT)
BEGIN
  DECLARE r INT DEFAULT 0;
  DECLARE chunk LONGBLOB;
  DECLARE i INT;
  WHILE r < nrows DO
    SET chunk = _binary'';
    SET i = 0;
    WHILE i < CEIL(nbytes / 1024) DO
      SET chunk = CONCAT(chunk, RANDOM_BYTES(1024));
      SET i = i + 1;
    END WHILE;
    INSERT INTO $t (label, c_payload) VALUES (CONCAT('lob-', r), SUBSTRING(chunk, 1, nbytes));
    SET r = r + 1;
  END WHILE;
END\$\$
DELIMITER ;
CALL b_fill_lob($LOB_ROWS, $LOB_BYTES);
DROP PROCEDURE b_fill_lob;
SQL
done

echo "==> ANALYZE (the estimator reads information_schema, so statistics must be fresh)"
for t in "${ALL[@]}"; do echo "ANALYZE TABLE $t;" | rootsql >/dev/null; done
echo "DROP TABLE b_seq;" | rootsql

echo "==> manifest"
rootsql <<SQL
SELECT table_name, table_rows, data_length, avg_row_length
FROM information_schema.tables
WHERE table_schema='dbx_src' AND table_name LIKE 'b\\_%' ORDER BY table_name;
SQL
