package com.eternax.db.audit.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Filters for audit search and export (HLD FR-103). Paging is keyset on the per-tenant sequence.
 */
public record AuditSearchCriteria(
        Optional<String> actor,
        Optional<String> action,
        Optional<String> entityType,
        Optional<String> entityId,
        Optional<Instant> from,
        Optional<Instant> to,
        long afterSeq,
        int limit) {}
