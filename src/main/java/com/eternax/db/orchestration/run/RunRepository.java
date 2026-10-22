package com.eternax.db.orchestration.run;

import com.eternax.recon.common.TypedId;
import com.eternax.recon.domain.ReconciliationRun;
import com.eternax.recon.domain.RunStatus;
import com.eternax.recon.domain.RunTrigger;
import com.eternax.recon.exception.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {

    private static final String COLUMNS =
            "run_id, tenant_id, definition_id, definition_version, period_key, trigger_type,"
                    + " started_at, status, completed_at, matched_count, exception_count";

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReconciliationRun> latestFor(
            String definitionId, int version, String periodKey) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_run WHERE definition_id=:d AND"
                                + " definition_version=:v AND period_key=:p ORDER BY revision DESC"
                                + " LIMIT 1")
                .param("d", definitionId)
                .param("v", version)
                .param("p", periodKey)
                .query(RunRepository::map)
                .optional();
    }

    public int nextRevision(String definitionId, int version, String periodKey) {
        return jdbc.sql(
                        "SELECT COALESCE(MAX(revision), 0) + 1 FROM reconciliation_run WHERE"
                                + " definition_id=:d AND definition_version=:v AND period_key=:p")
                .param("d", definitionId)
                .param("v", version)
                .param("p", periodKey)
                .query(Integer.class)
                .single();
    }

    /** What a run was executed with and what it covered; stored as JSON next to the run. */
    public record RunExtras(
            Instant scopeFrom,
            String mappingVersionsJson,
            String rulesJson,
            String controlTotalsJson) {}

    public RunExtras extras(String tenantId, String runId) {
        return jdbc.sql(
                        "SELECT scope_from, mapping_versions, rules_snapshot, control_totals FROM"
                                + " reconciliation_run WHERE tenant_id=:t AND run_id=:id")
                .param("t", tenantId)
                .param("id", runId)
                .query(
                        (rs, n) -> {
                            OffsetDateTime from = rs.getObject(1, OffsetDateTime.class);
                            return new RunExtras(
                                    from == null ? null : from.toInstant(),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4));
                        })
                .optional()
                .orElse(new RunExtras(null, null, null, null));
    }

    public void saveControlTotals(String runId, String json) {
        jdbc.sql("UPDATE reconciliation_run SET control_totals=:c WHERE run_id=:id")
                .param("c", json)
                .param("id", runId)
                .update();
    }

    /**
     * Start of the data this run covers: that of the latest run of the same period for a re-run.
     */
    public Optional<Instant> scopeFromOfLatest(String definitionId, int version, String periodKey) {
        return jdbc.sql(
                        "SELECT scope_from FROM reconciliation_run WHERE definition_id=:d AND"
                                + " definition_version=:v AND period_key=:p ORDER BY revision DESC"
                                + " LIMIT 1")
                .param("d", definitionId)
                .param("v", version)
                .param("p", periodKey)
                .query(
                        (rs, n) ->
                                Optional.ofNullable(rs.getObject(1, OffsetDateTime.class))
                                        .map(OffsetDateTime::toInstant))
                .optional()
                .flatMap(x -> x);
    }

    /** When the previous run of this definition (any version, any period) started. */
    public Optional<Instant> previousStart(String tenantId, String definitionId) {
        return jdbc.sql(
                        "SELECT started_at FROM reconciliation_run WHERE tenant_id=:t AND"
                                + " definition_id=:d ORDER BY started_at DESC LIMIT 1")
                .param("t", tenantId)
                .param("d", definitionId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    public void insert(ReconciliationRun run, int revision, String operator) {
        insert(run, revision, operator, new RunExtras(null, null, null, null));
    }

    public void insert(ReconciliationRun run, int revision, String operator, RunExtras extras) {
        jdbc.sql(
                        """
                        INSERT INTO reconciliation_run (run_id, tenant_id, definition_id, definition_version, period_key, revision,
                            trigger_type, status, started_at, operator_id, scope_from, mapping_versions, rules_snapshot)
                        VALUES (:id,:t,:d,:v,:p,:rev,:tr,:st,:at,:op,:sf,:mv,:rs)
                        """)
                .param(
                        "sf",
                        extras.scopeFrom() == null
                                ? null
                                : extras.scopeFrom().atOffset(ZoneOffset.UTC))
                .param("mv", extras.mappingVersionsJson())
                .param("rs", extras.rulesJson())
                .param("id", run.runId().value())
                .param("t", run.tenantId().value())
                .param("d", run.definitionId().value())
                .param("v", run.definitionVersion())
                .param("p", run.periodKey())
                .param("rev", revision)
                .param("tr", run.trigger().name())
                .param("st", run.status().name())
                .param("at", run.startedAtUtc().atOffset(ZoneOffset.UTC))
                .param("op", operator)
                .update();
    }

    public void saveOutcome(ReconciliationRun run) {
        jdbc.sql(
                        "UPDATE reconciliation_run SET status=:s, completed_at=:c,"
                                + " matched_count=:m, exception_count=:e WHERE run_id=:id")
                .param("s", run.status().name())
                .param("c", run.completedAtUtc().map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null))
                .param("m", run.matchedCount())
                .param("e", run.exceptionCount())
                .param("id", run.runId().value())
                .update();
    }

    public void saveCheckpoint(String tenantId, String runId, String cursor) {
        int rows =
                jdbc.sql(
                                "UPDATE reconciliation_run SET checkpoint=:c WHERE tenant_id=:t AND"
                                        + " run_id=:id AND status='RUNNING'")
                        .param("c", cursor)
                        .param("t", tenantId)
                        .param("id", runId)
                        .update();
        if (rows == 0) {
            throw new EntityNotFoundException("Running run", runId);
        }
    }

    public Optional<String> checkpoint(String tenantId, String runId) {
        return jdbc.sql(
                        "SELECT checkpoint FROM reconciliation_run WHERE tenant_id=:t AND"
                                + " run_id=:id")
                .param("t", tenantId)
                .param("id", runId)
                .query(String.class)
                .optional();
    }

    public ReconciliationRun get(String tenantId, String runId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_run WHERE tenant_id=:t AND run_id=:id")
                .param("t", tenantId)
                .param("id", runId)
                .query(RunRepository::map)
                .optional()
                .orElseThrow(() -> new EntityNotFoundException("Run", runId));
    }

    public List<ReconciliationRun> recent(String tenantId, int limit) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_run WHERE tenant_id=:t ORDER BY started_at"
                                + " DESC LIMIT :l")
                .param("t", tenantId)
                .param("l", limit)
                .query(RunRepository::map)
                .list();
    }

    public List<ReconciliationRun> openStartedBefore(Instant cutoff, int limit) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_run WHERE status='RUNNING' AND started_at"
                                + " <= :c ORDER BY started_at LIMIT :l")
                .param("c", cutoff.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(RunRepository::map)
                .list();
    }

    private static ReconciliationRun map(ResultSet rs, int n) throws SQLException {
        ReconciliationRun base =
                new ReconciliationRun(
                        TypedId.of(rs.getString(1)),
                        TypedId.of(rs.getString(2)),
                        TypedId.of(rs.getString(3)),
                        rs.getInt(4),
                        rs.getString(5),
                        RunTrigger.valueOf(rs.getString(6)),
                        rs.getObject(7, OffsetDateTime.class).toInstant());
        OffsetDateTime completed = rs.getObject(9, OffsetDateTime.class);
        return ReconciliationRun.restore(
                base,
                RunStatus.valueOf(rs.getString(8)),
                Optional.ofNullable(completed).map(OffsetDateTime::toInstant),
                rs.getLong(10),
                rs.getLong(11));
    }
}
