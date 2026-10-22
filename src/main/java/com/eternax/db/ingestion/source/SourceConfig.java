package com.eternax.db.ingestion.source;

import java.util.List;
import java.util.Objects;

/**
 * A registered source and how its records are validated. A change is a new version; every batch
 * records the version it used (HLD 18.1, FR-014).
 *
 * @param amountColumn column holding the amount, used for batch value control totals; optional
 * @param directionColumn column holding debit/credit (D/C, DR/CR, DEBIT/CREDIT); optional
 * @param endpoint where an API or SFTP source is pulled from; optional
 * @param credentialRef name of the secret in the secret manager. Never the secret itself.
 */
public record SourceConfig(
        String tenantId,
        String sourceId,
        int version,
        String rail,
        ConnectionType connectionType,
        List<String> requiredColumns,
        List<String> numericColumns,
        String amountColumn,
        String directionColumn,
        String endpoint,
        String credentialRef) {

    public SourceConfig {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(rail);
        Objects.requireNonNull(connectionType);
        requiredColumns = List.copyOf(requiredColumns);
        numericColumns = numericColumns == null ? List.of() : List.copyOf(numericColumns);
    }

    public SourceConfig(
            String tenantId,
            String sourceId,
            int version,
            String rail,
            ConnectionType connectionType,
            List<String> requiredColumns,
            List<String> numericColumns) {
        this(
                tenantId,
                sourceId,
                version,
                rail,
                connectionType,
                requiredColumns,
                numericColumns,
                null,
                null,
                null,
                null);
    }
}
