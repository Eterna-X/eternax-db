package com.eternax.db.ingestion.batch;

import java.util.List;

/** A record that failed validation, kept visible with its reasons instead of being dropped. */
public record QuarantineEntry(String recordReference, List<String> reasons, String rawLine) {}
