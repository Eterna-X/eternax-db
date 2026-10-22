package com.eternax.db.closure.model;

import java.time.Instant;
import java.util.Optional;

/** A reconciliation period awaiting or holding sign-off. */
public record ClosurePeriod(
        String periodId,
        String tenantId,
        String rail,
        String periodKey,
        Instant from,
        Instant to,
        ClosureState state,
        long materialityMinor,
        String createdBy,
        Optional<String> submittedBy,
        Optional<String> firstApprover,
        Optional<String> secondApprover,
        Optional<Instant> closedAt,
        Optional<String> snapshotId,
        long version) {}
