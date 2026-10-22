package com.eternax.db.audit.store;

import java.time.Instant;

/**
 * A stored audit event. {@code eventHash} covers this event's content and the previous event's
 * hash.
 */
public record AuditRecord(
        long seqNo,
        String tenantId,
        String actor,
        String action,
        String entityType,
        String entityId,
        String beforeJson,
        String afterJson,
        String correlationId,
        String sourceService,
        Instant atUtc,
        String previousHash,
        String eventHash) {}
