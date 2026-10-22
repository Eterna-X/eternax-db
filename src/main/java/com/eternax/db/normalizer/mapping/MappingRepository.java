package com.eternax.db.normalizer.mapping;

import com.eternax.recon.exception.ConfigurationInvalidException;
import com.eternax.recon.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned field mappings per source (HLD FR-030 to FR-033). Field mappings are stored as JSON.
 */
@Repository
public class MappingRepository {

    private static final TypeReference<List<FieldMapping>> LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MappingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The newest active version for this tenant and source ({@code "*"} = platform default). */
    public Optional<MappingDefinition> findActive(String tenantId, String sourceId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE tenant_id = :t AND source_id = :s AND active ORDER BY"
                                + " version DESC LIMIT 1")
                .param("t", tenantId)
                .param("s", sourceId)
                .query(this::map)
                .optional();
    }

    public Optional<MappingDefinition> find(String tenantId, String sourceId, int version) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :t AND source_id = :s AND version = :v")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", version)
                .query(this::map)
                .optional();
    }

    /** Stores an inactive draft as the source's next version. */
    @Transactional
    public MappingDefinition insertNextDraft(
            String tenantId,
            String sourceId,
            String rail,
            List<FieldMapping> mappings,
            String author) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))")
                .param("k", "map/" + tenantId + "/" + sourceId)
                .query()
                .listOfRows();
        int next =
                jdbc.sql(
                                "SELECT COALESCE(MAX(version), 0) + 1 FROM mapping_definition WHERE"
                                        + " tenant_id = :t AND source_id = :s")
                        .param("t", tenantId)
                        .param("s", sourceId)
                        .query(Integer.class)
                        .single();
        jdbc.sql(
                        "INSERT INTO mapping_definition (tenant_id, source_id, version, rail,"
                                + " active, field_mappings, created_by) VALUES"
                                + " (:t,:s,:v,:r,FALSE,:m,:by)")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", next)
                .param("r", rail)
                .param("m", write(mappings))
                .param("by", author)
                .update();
        return new MappingDefinition(tenantId, sourceId, next, rail, false, mappings, author);
    }

    /** Makes {@code version} the only active version of the source. */
    @Transactional
    public void makeActive(String tenantId, String sourceId, int version) {
        jdbc.sql(
                        "UPDATE mapping_definition SET active = FALSE WHERE tenant_id = :t AND"
                                + " source_id = :s AND active")
                .param("t", tenantId)
                .param("s", sourceId)
                .update();
        jdbc.sql(
                        "UPDATE mapping_definition SET active = TRUE WHERE tenant_id = :t AND"
                                + " source_id = :s AND version = :v")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", version)
                .update();
    }

    /** Every version for the tenant and the platform default, tenant's own first. */
    public List<MappingDefinition> history(String tenantId, String sourceId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE tenant_id IN (:t, '*') AND source_id = :s ORDER BY"
                                + " tenant_id DESC, version")
                .param("t", tenantId)
                .param("s", sourceId)
                .query(this::map)
                .list();
    }

    private static final String SELECT =
            "SELECT tenant_id, source_id, version, rail, active, field_mappings, created_by FROM"
                    + " mapping_definition";

    private MappingDefinition map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        try {
            return new MappingDefinition(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    mapper.readValue(rs.getString(6), LIST),
                    rs.getString(7));
        } catch (JsonProcessingException e) {
            throw new ConfigurationInvalidException(
                    ErrorCode.INF_8003_CONFIGURATION_INVALID,
                    "Stored mapping for "
                            + rs.getString(2)
                            + " v"
                            + rs.getInt(3)
                            + " is not valid JSON");
        }
    }

    private String write(List<FieldMapping> mappings) {
        try {
            return mapper.writeValueAsString(mappings);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("mappings not serialisable", e);
        }
    }
}
