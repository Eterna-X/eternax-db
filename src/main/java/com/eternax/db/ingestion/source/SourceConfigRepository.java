package com.eternax.db.ingestion.source;

import com.eternax.recon.exception.EntityNotFoundException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Versioned source configuration (HLD 18.1): every change is a new row with the next version. */
@Repository
public class SourceConfigRepository {

    private final JdbcClient jdbc;

    public SourceConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores {@code draft} as the source's next version and returns it with that version. Joins the
     * caller's transaction, so anything the caller records alongside commits or rolls back with it.
     */
    @Transactional
    public SourceConfig insertNextVersion(SourceConfig draft, String actor) {
        // Serialise concurrent registrations of the same source so versions stay gap-free.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))")
                .param("k", draft.tenantId() + "/" + draft.sourceId())
                .query()
                .listOfRows();
        int next =
                jdbc.sql(
                                "SELECT COALESCE(MAX(version), 0) + 1 FROM source_config WHERE"
                                        + " tenant_id = :t AND source_id = :s")
                        .param("t", draft.tenantId())
                        .param("s", draft.sourceId())
                        .query(Integer.class)
                        .single();
        jdbc.sql(
                        """
                        INSERT INTO source_config (tenant_id, source_id, version, rail, connection_type,
                                                   required_columns, numeric_columns, created_by,
                                                   amount_column, direction_column, endpoint, credential_ref)
                        VALUES (:t, :s, :v, :rail, :ct, :req, :num, :by, :ac, :dc, :ep, :cr)
                        """)
                .param("t", draft.tenantId())
                .param("s", draft.sourceId())
                .param("v", next)
                .param("rail", draft.rail())
                .param("ct", draft.connectionType().name())
                .param("req", String.join(",", draft.requiredColumns()))
                .param("num", String.join(",", draft.numericColumns()))
                .param("by", actor)
                .param("ac", draft.amountColumn())
                .param("dc", draft.directionColumn())
                .param("ep", draft.endpoint())
                .param("cr", draft.credentialRef())
                .update();
        return new SourceConfig(
                draft.tenantId(),
                draft.sourceId(),
                next,
                draft.rail(),
                draft.connectionType(),
                draft.requiredColumns(),
                draft.numericColumns(),
                draft.amountColumn(),
                draft.directionColumn(),
                draft.endpoint(),
                draft.credentialRef());
    }

    /**
     * @throws EntityNotFoundException if the source is unknown
     */
    public SourceConfig getCurrent(String tenantId, String sourceId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE tenant_id = :t AND source_id = :s ORDER BY version DESC"
                                + " LIMIT 1")
                .param("t", tenantId)
                .param("s", sourceId)
                .query(SourceConfigRepository::map)
                .optional()
                .orElseThrow(() -> new EntityNotFoundException("Source", sourceId));
    }

    public List<SourceConfig> history(String tenantId, String sourceId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :t AND source_id = :s ORDER BY version")
                .param("t", tenantId)
                .param("s", sourceId)
                .query(SourceConfigRepository::map)
                .list();
    }

    public List<SourceConfig> listCurrent(String tenantId) {
        return jdbc.sql(
                        """
                        SELECT s.tenant_id, s.source_id, s.version, s.rail, s.connection_type,
                               s.required_columns, s.numeric_columns, s.amount_column,
                               s.direction_column, s.endpoint, s.credential_ref
                        FROM source_config s
                        WHERE s.tenant_id = :t
                          AND s.version = (SELECT MAX(x.version) FROM source_config x
                                           WHERE x.tenant_id = s.tenant_id AND x.source_id = s.source_id)
                        ORDER BY s.source_id
                        """)
                .param("t", tenantId)
                .query(SourceConfigRepository::map)
                .list();
    }

    private static final String SELECT =
            "SELECT tenant_id, source_id, version, rail, connection_type, required_columns,"
                    + " numeric_columns, amount_column, direction_column, endpoint, credential_ref"
                    + " FROM source_config";

    private static SourceConfig map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SourceConfig(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getString(4),
                ConnectionType.valueOf(rs.getString(5)),
                split(rs.getString(6)),
                split(rs.getString(7)),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11));
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank()
                ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).toList();
    }
}
