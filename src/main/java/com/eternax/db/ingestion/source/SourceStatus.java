package com.eternax.db.ingestion.source;

import java.time.Instant;

/**
 * Operational state of a source, separate from its versioned configuration.
 *
 * @param lastTestOk null when the connection was never tested
 * @param lastTestMessage sanitised outcome of the last test: never a stack trace, host detail or
 *     credential
 */
public record SourceStatus(
        boolean active,
        String changedBy,
        Instant changedAt,
        Instant lastTestAt,
        Boolean lastTestOk,
        String lastTestMessage,
        Instant lastIngestionAt) {

    public static SourceStatus initial() {
        return new SourceStatus(true, null, null, null, null, null, null);
    }
}
