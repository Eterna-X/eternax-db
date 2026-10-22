package com.eternax.db.normalizer.mapping;

import java.util.List;

/** A versioned set of field mappings for one source. */
public record MappingDefinition(
        String tenantId,
        String sourceId,
        int version,
        String rail,
        boolean active,
        List<FieldMapping> fieldMappings,
        String createdBy) {

    public MappingDefinition {
        fieldMappings = List.copyOf(fieldMappings);
    }
}
