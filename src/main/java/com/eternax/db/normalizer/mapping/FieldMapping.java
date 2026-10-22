package com.eternax.db.normalizer.mapping;

/**
 * Maps one canonical field from one source column.
 *
 * @param canonicalField target field name in the canonical record
 * @param sourceField source column name
 * @param transformation how the value is converted
 * @param parameter transformation-specific setting (pattern or code map), may be null
 * @param defaultValue used when the source column is missing or blank, may be null
 */
public record FieldMapping(
        String canonicalField,
        String sourceField,
        TransformationType transformation,
        String parameter,
        String defaultValue) {}
