package com.dbx.dialect.api;

/**
 * How a source dialect keeps reads bounded in bytes: every M-independent constant of ADR-0033 §Settings
 * and ADR-0037 §Bulk path. It declares and computes nothing from M, the table's largest row byte length;
 * {@code connector.deriveBox} applies these constants to M. The 1.5 planned-row-count factor is
 * {@code preflight}'s and is not declared here.
 *
 * @param cursorFetch cursor fetch on every Source connection; ADR-0033 makes it mandatory
 * @param fetchRows {@code batch.max.rows}, also the JDBC fetch size: {@code clamp(budget ÷ M, min, max)}
 * @param maxBufferSize how {@code max.buffer.size} follows the fetch rows
 * @param keysetChunkRows N of a keyset read's {@code LIMIT N}: {@code clamp(budget ÷ M, min, max)}
 * @param rounding how a clamped row count is rounded
 * @param largeRecord what replaces both fetch settings for a large record table (ADR-0003)
 * @param pollInterval {@code poll.interval.ms} per read path
 * @param bulkQuerySuffix a bulk read's {@code query.suffix}
 * @param bulkReadCapBytes the most one bulk read may hold, planned rows × M (ADR-0037 §64 MiB cap)
 */
public record BoundedReadRequirement(
        boolean cursorFetch,
        RowBudget fetchRows,
        BufferSizing maxBufferSize,
        RowBudget keysetChunkRows,
        Rounding rounding,
        LargeRecordOverride largeRecord,
        PollInterval pollInterval,
        String bulkQuerySuffix,
        long bulkReadCapBytes) {

    public BoundedReadRequirement {
        if (!cursorFetch) {
            throw new IllegalArgumentException(
                    "ADR-0033: cursor fetch is a mandatory platform policy, a source dialect cannot opt out");
        }
        Checks.present(fetchRows, "fetchRows");
        Checks.present(maxBufferSize, "maxBufferSize");
        Checks.present(keysetChunkRows, "keysetChunkRows");
        Checks.present(rounding, "rounding");
        Checks.present(largeRecord, "largeRecord");
        Checks.present(pollInterval, "pollInterval");
        Checks.present(bulkQuerySuffix, "bulkQuerySuffix");
        Checks.positive(bulkReadCapBytes, "bulkReadCapBytes");
    }

    /** A row count derived as {@code clamp(byteBudget ÷ M, minRows, maxRows)}. */
    public record RowBudget(long byteBudget, int minRows, int maxRows) {

        public RowBudget {
            Checks.positive(byteBudget, "byteBudget");
            Checks.positive(minRows, "minRows");
            Checks.positive(maxRows, "maxRows");
            if (minRows > maxRows) {
                throw new IllegalArgumentException("minRows " + minRows + " exceeds maxRows " + maxRows);
            }
        }
    }

    /** How {@code max.buffer.size} is sized for an ordinary table. */
    public enum BufferSizing {
        EQUAL_TO_FETCH_ROWS
    }

    /** How a clamped row count becomes a setting. */
    public enum Rounding {
        POWER_OF_TWO_DOWN
    }

    /** The fixed fetch settings of a large record table. */
    public record LargeRecordOverride(int batchMaxRows, int maxBufferSize) {

        public LargeRecordOverride {
            Checks.positive(batchMaxRows, "batchMaxRows");
            Checks.positive(maxBufferSize, "maxBufferSize");
        }
    }

    /** {@code poll.interval.ms} for a keyset read and for a bulk read. */
    public record PollInterval(int keysetReadMillis, int bulkReadMillis) {

        public PollInterval {
            Checks.positive(keysetReadMillis, "keysetReadMillis");
            Checks.positive(bulkReadMillis, "bulkReadMillis");
        }
    }
}
