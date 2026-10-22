package com.eternax.db.matching.rules;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Local copy of published reconciliation definitions, one row per definition, so matching never
 * depends on a synchronous call to the orchestration service. The payload is the event as received.
 */
@Repository
public class DefinitionSnapshotRepository {

    private final JdbcClient jdbc;

    public DefinitionSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps the newest version of each definition: an older or replayed event changes nothing. */
    public void upsert(
            String tenantId,
            String definitionId,
            int version,
            String rail,
            String status,
            String payload) {
        jdbc.sql(
                        """
                        INSERT INTO definition_snapshot (tenant_id, definition_id, version, rail, status, payload)
                        VALUES (:t, :d, :v, :r, :s, :p)
                        ON CONFLICT (tenant_id, definition_id) DO UPDATE
                           SET version = :v, rail = :r, status = :s, payload = :p
                         WHERE definition_snapshot.version <= :v
                        """)
                .param("t", tenantId)
                .param("d", definitionId)
                .param("v", version)
                .param("r", rail)
                .param("s", status)
                .param("p", payload)
                .update();
    }

    /** Payload of the newest ACTIVE definition for the rail. */
    public Optional<String> activePayload(String tenantId, String rail) {
        return jdbc.sql(
                        """
                        SELECT payload FROM definition_snapshot
                        WHERE tenant_id = :t AND rail = :r AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1
                        """)
                .param("t", tenantId)
                .param("r", rail)
                .query(String.class)
                .optional();
    }
}
