package com.eternax.db.normalizer.mapping;

import java.time.Instant;
import java.util.List;

/**
 * A versioned set of field mappings for one source. A draft an approver turned down carries who
 * rejected it, why and when; it can no longer be activated.
 */
public record MappingDefinition(
        String tenantId,
        String sourceId,
        int version,
        String rail,
        boolean active,
        List<FieldMapping> fieldMappings,
        String createdBy,
        String rejectedBy,
        String rejectionReason,
        Instant rejectedAt) {

    public MappingDefinition {
        fieldMappings = List.copyOf(fieldMappings);
    }

    /** A mapping that has not been rejected. */
    public MappingDefinition(
            String tenantId,
            String sourceId,
            int version,
            String rail,
            boolean active,
            List<FieldMapping> fieldMappings,
            String createdBy) {
        this(tenantId, sourceId, version, rail, active, fieldMappings, createdBy, null, null, null);
    }

    public boolean rejected() {
        return rejectedAt != null;
    }
}
