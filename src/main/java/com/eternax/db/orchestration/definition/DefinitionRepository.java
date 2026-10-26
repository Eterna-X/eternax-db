package com.eternax.db.orchestration.definition;

import com.eternax.recon.exception.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DefinitionRepository {

    private static final String COLUMNS =
            "definition_id, version, tenant_id, name, rail, required_sources, wait_window_seconds,"
                + " composite_window_seconds, amount_tolerance_minor, require_opposite_direction,"
                + " cron_schedule, time_zone, status, created_by, approved_by, next_run_at, rules,"
                + " first_run_at";

    private final JdbcClient jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public DefinitionRepository(
            JdbcClient jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(Definition d) {
        jdbc.sql(
                        """
                        INSERT INTO reconciliation_definition (definition_id, version, tenant_id, name, rail, required_sources,
                            wait_window_seconds, composite_window_seconds, amount_tolerance_minor, require_opposite_direction,
                            cron_schedule, time_zone, status, created_by, rules, first_run_at) VALUES (:id,:v,:t,:n,:r,:src,:w,:c,:tol,:opp,:cron,:tz,:st,:by,:rules,:first)
                        """)
                .param("id", d.definitionId())
                .param("v", d.version())
                .param("t", d.tenantId())
                .param("n", d.name())
                .param("r", d.rail())
                .param("src", String.join(",", d.requiredSources()))
                .param("w", d.waitWindow().toSeconds())
                .param("c", d.compositeWindow().toSeconds())
                .param("tol", d.amountToleranceMinor())
                .param("opp", d.requireOppositeDirection())
                .param("cron", d.cronSchedule())
                .param("tz", d.timeZone())
                .param("st", d.status().name())
                .param("by", d.createdBy())
                .param("rules", rulesJson(d.rules()))
                .param(
                        "first",
                        d.firstRunAt() == null ? null : d.firstRunAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    private String rulesJson(List<com.eternax.recon.domain.MatchingRule> rules) {
        try {
            return rules == null || rules.isEmpty() ? null : mapper.writeValueAsString(rules);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("rules not serialisable", e);
        }
    }

    private List<com.eternax.recon.domain.MatchingRule> parseRules(String json) {
        try {
            return json == null
                    ? null
                    : mapper.readValue(
                            json,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    List<com.eternax.recon.domain.MatchingRule>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored rules are not valid JSON", e);
        }
    }

    public Optional<String> definitionIdByName(String tenantId, String name) {
        return jdbc.sql(
                        "SELECT definition_id FROM reconciliation_definition WHERE tenant_id=:t AND"
                                + " name=:n LIMIT 1")
                .param("t", tenantId)
                .param("n", name)
                .query(String.class)
                .optional();
    }

    public int nextVersion(String definitionId) {
        return jdbc.sql(
                        "SELECT COALESCE(MAX(version), 0) + 1 FROM reconciliation_definition WHERE"
                                + " definition_id=:id")
                .param("id", definitionId)
                .query(Integer.class)
                .single();
    }

    public Definition get(String tenantId, String definitionId, int version) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE tenant_id=:t AND"
                                + " definition_id=:id AND version=:v")
                .param("t", tenantId)
                .param("id", definitionId)
                .param("v", version)
                .query(this::map)
                .optional()
                .orElseThrow(
                        () ->
                                new EntityNotFoundException(
                                        "Definition", definitionId + " v" + version));
    }

    public Optional<Definition> active(String tenantId, String definitionId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE tenant_id=:t AND"
                                + " definition_id=:id AND status='ACTIVE'")
                .param("t", tenantId)
                .param("id", definitionId)
                .query(this::map)
                .optional();
    }

    public List<Definition> versions(String tenantId, String definitionId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE tenant_id=:t AND"
                                + " definition_id=:id ORDER BY version")
                .param("t", tenantId)
                .param("id", definitionId)
                .query(this::map)
                .list();
    }

    public List<Definition> list(String tenantId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE tenant_id=:t ORDER BY"
                                + " name, version DESC")
                .param("t", tenantId)
                .query(this::map)
                .list();
    }

    /** Every ACTIVE version on a rail for the tenant (normally one). */
    public List<Definition> activeOnRail(String tenantId, String rail) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE tenant_id=:t AND rail=:r"
                                + " AND status='ACTIVE'")
                .param("t", tenantId)
                .param("r", rail)
                .query(this::map)
                .list();
    }

    public void retireActive(String tenantId, String definitionId) {
        jdbc.sql(
                        "UPDATE reconciliation_definition SET status='RETIRED', next_run_at=NULL"
                                + " WHERE tenant_id=:t AND definition_id=:id AND status='ACTIVE'")
                .param("t", tenantId)
                .param("id", definitionId)
                .update();
    }

    public void markActive(String definitionId, int version, String approver, Instant nextRunAt) {
        jdbc.sql(
                        "UPDATE reconciliation_definition SET status='ACTIVE', approved_by=:a,"
                                + " next_run_at=:n WHERE definition_id=:id AND version=:v")
                .param("a", approver)
                .param("n", nextRunAt == null ? null : nextRunAt.atOffset(ZoneOffset.UTC))
                .param("id", definitionId)
                .param("v", version)
                .update();
    }

    public void retire(String tenantId, String definitionId, int version) {
        jdbc.sql(
                        "UPDATE reconciliation_definition SET status='RETIRED', next_run_at=NULL"
                                + " WHERE tenant_id=:t AND definition_id=:id AND version=:v")
                .param("t", tenantId)
                .param("id", definitionId)
                .param("v", version)
                .update();
    }

    /** Claims scheduled definitions that are due; safe under concurrent replicas. */
    public List<Definition> claimDue(Instant now, int limit) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_definition WHERE status='ACTIVE' AND"
                                + " next_run_at IS NOT NULL AND next_run_at <= :now ORDER BY"
                                + " next_run_at LIMIT :l FOR UPDATE SKIP LOCKED")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(this::map)
                .list();
    }

    public void setNextRun(String definitionId, int version, Instant next) {
        jdbc.sql(
                        "UPDATE reconciliation_definition SET next_run_at=:n WHERE"
                                + " definition_id=:id AND version=:v")
                .param("n", next == null ? null : next.atOffset(ZoneOffset.UTC))
                .param("id", definitionId)
                .param("v", version)
                .update();
    }

    private Definition map(ResultSet rs, int n) throws SQLException {
        OffsetDateTime next = rs.getObject(16, OffsetDateTime.class);
        OffsetDateTime first = rs.getObject(18, OffsetDateTime.class);
        return new Definition(
                rs.getString(1),
                rs.getInt(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                Arrays.stream(rs.getString(6).split(",")).toList(),
                Duration.ofSeconds(rs.getLong(7)),
                Duration.ofSeconds(rs.getLong(8)),
                rs.getLong(9),
                rs.getBoolean(10),
                rs.getString(11),
                rs.getString(12),
                Definition.Status.valueOf(rs.getString(13)),
                rs.getString(14),
                rs.getString(15),
                next == null ? null : next.toInstant(),
                parseRules(rs.getString(17)),
                first == null ? null : first.toInstant());
    }
}
