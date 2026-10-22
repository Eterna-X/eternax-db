package com.eternax.db.matching.store;

import java.time.Instant;

/** State of one business transaction's cluster of counterpart records. */
public record ClusterRow(
        String tenantId,
        String rail,
        String matchKey,
        String definitionId,
        String state,
        Instant deadlineAt,
        String breakId) {}
