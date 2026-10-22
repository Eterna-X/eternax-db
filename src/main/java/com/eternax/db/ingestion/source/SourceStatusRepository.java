package com.eternax.db.ingestion.source;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceStatusRepository {

    private final JdbcClient jdbc;

    public SourceStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SourceStatus get(String tenantId, String sourceId) {
        return jdbc.sql(
                        "SELECT active, changed_by, changed_at, last_test_at, last_test_ok,"
                                + " last_test_message, last_ingestion_at FROM source_status"
                                + " WHERE tenant_id=:t AND source_id=:s")
                .param("t", tenantId)
                .param("s", sourceId)
                .query(
                        (rs, n) ->
                                new SourceStatus(
                                        rs.getBoolean(1),
                                        rs.getString(2),
                                        instant(rs.getObject(3, OffsetDateTime.class)),
                                        instant(rs.getObject(4, OffsetDateTime.class)),
                                        (Boolean) rs.getObject(5),
                                        rs.getString(6),
                                        instant(rs.getObject(7, OffsetDateTime.class))))
                .optional()
                .orElseGet(SourceStatus::initial);
    }

    private static java.time.Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    public void setActive(String tenantId, String sourceId, boolean active, String actor) {
        jdbc.sql(
                        """
                        INSERT INTO source_status (tenant_id, source_id, active, changed_by, changed_at)
                        VALUES (:t,:s,:a,:by,now())
                        ON CONFLICT (tenant_id, source_id)
                        DO UPDATE SET active=:a, changed_by=:by, changed_at=now()
                        """)
                .param("t", tenantId)
                .param("s", sourceId)
                .param("a", active)
                .param("by", actor)
                .update();
    }

    public void recordTest(String tenantId, String sourceId, boolean ok, String message) {
        jdbc.sql(
                        """
                        INSERT INTO source_status (tenant_id, source_id, last_test_at, last_test_ok, last_test_message)
                        VALUES (:t,:s,now(),:ok,:m)
                        ON CONFLICT (tenant_id, source_id)
                        DO UPDATE SET last_test_at=now(), last_test_ok=:ok, last_test_message=:m
                        """)
                .param("t", tenantId)
                .param("s", sourceId)
                .param("ok", ok)
                .param("m", message)
                .update();
    }

    public void recordIngestion(String tenantId, String sourceId) {
        jdbc.sql(
                        """
                        INSERT INTO source_status (tenant_id, source_id, last_ingestion_at)
                        VALUES (:t,:s,now())
                        ON CONFLICT (tenant_id, source_id) DO UPDATE SET last_ingestion_at=now()
                        """)
                .param("t", tenantId)
                .param("s", sourceId)
                .update();
    }
}
