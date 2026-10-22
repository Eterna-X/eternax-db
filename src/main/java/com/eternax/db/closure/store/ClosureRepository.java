package com.eternax.db.closure.store;

import com.eternax.db.closure.model.CheckResult;
import com.eternax.db.closure.model.ClosurePeriod;
import com.eternax.db.closure.model.ClosureState;
import com.eternax.recon.exception.EntityNotFoundException;
import com.eternax.recon.exception.OptimisticLockConflictException;
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
public class ClosureRepository {

    private static final String COLUMNS =
            "period_id, tenant_id, rail, period_key, from_at, to_at, state, materiality_minor,"
                    + " created_by, submitted_by, first_approver, second_approver, closed_at,"
                    + " snapshot_id, version";

    private final JdbcClient jdbc;

    public ClosureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClosurePeriod p) {
        jdbc.sql(
                        """
                        INSERT INTO closure_period (period_id, tenant_id, rail, period_key, from_at, to_at, state, materiality_minor, created_by)
                        VALUES (:id,:t,:r,:k,:f,:to,:st,:m,:by)
                        """)
                .param("id", p.periodId())
                .param("t", p.tenantId())
                .param("r", p.rail())
                .param("k", p.periodKey())
                .param("f", p.from().atOffset(ZoneOffset.UTC))
                .param("to", p.to().atOffset(ZoneOffset.UTC))
                .param("st", p.state().name())
                .param("m", p.materialityMinor())
                .param("by", p.createdBy())
                .update();
    }

    public ClosurePeriod get(String tenantId, String periodId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM closure_period WHERE tenant_id=:t AND period_id=:id")
                .param("t", tenantId)
                .param("id", periodId)
                .query(ClosureRepository::map)
                .optional()
                .orElseThrow(() -> new EntityNotFoundException("ClosurePeriod", periodId));
    }

    public List<ClosurePeriod> list(String tenantId, Optional<String> rail) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM closure_period WHERE tenant_id=:t AND (CAST(:rail AS"
                                + " VARCHAR) IS NULL OR rail=:rail) ORDER BY from_at DESC")
                .param("t", tenantId)
                .param("rail", rail.orElse(null))
                .query(ClosureRepository::map)
                .list();
    }

    /** Writes the mutable columns, guarded by the version (optimistic locking). */
    public void update(ClosurePeriod p) {
        int rows =
                jdbc.sql(
                                """
                                UPDATE closure_period SET state=:st, submitted_by=:sb, first_approver=:fa, second_approver=:sa,
                                       closed_at=:ca, snapshot_id=:snap, version = version + 1
                                WHERE period_id=:id AND tenant_id=:t AND version=:v
                                """)
                        .param("st", p.state().name())
                        .param("sb", p.submittedBy().orElse(null))
                        .param("fa", p.firstApprover().orElse(null))
                        .param("sa", p.secondApprover().orElse(null))
                        .param("ca", p.closedAt().map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null))
                        .param("snap", p.snapshotId().orElse(null))
                        .param("id", p.periodId())
                        .param("t", p.tenantId())
                        .param("v", p.version())
                        .update();
        if (rows == 0) {
            throw new OptimisticLockConflictException("ClosurePeriod", p.periodId(), p.version());
        }
    }

    public void replaceChecks(String periodId, List<CheckResult> checks, Instant at) {
        jdbc.sql("DELETE FROM closure_check WHERE period_id=:id").param("id", periodId).update();
        for (CheckResult c : checks) {
            jdbc.sql(
                            "INSERT INTO closure_check (period_id, check_name, passed, detail,"
                                    + " evaluated_at) VALUES (:id,:n,:p,:d,:at)")
                    .param("id", periodId)
                    .param("n", c.name())
                    .param("p", c.passed())
                    .param("d", c.detail())
                    .param("at", at.atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    public List<CheckResult> checks(String periodId) {
        return jdbc.sql(
                        "SELECT check_name, passed, detail FROM closure_check WHERE period_id=:id"
                                + " ORDER BY check_name")
                .param("id", periodId)
                .query(
                        (rs, n) ->
                                new CheckResult(rs.getString(1), rs.getBoolean(2), rs.getString(3)))
                .list();
    }

    public record CarryForward(
            String caseId, String rationale, long amountMinor, String createdBy, String severity) {
        public CarryForward(String caseId, String rationale, long amountMinor, String createdBy) {
            this(caseId, rationale, amountMinor, createdBy, "LOW");
        }
    }

    public void addCarryForward(String periodId, CarryForward c) {
        jdbc.sql(
                        "INSERT INTO carry_forward (period_id, case_id, rationale, amount_minor,"
                            + " created_by, severity) VALUES (:p,:c,:r,:a,:by,:sev) ON CONFLICT"
                            + " (period_id, case_id) DO UPDATE SET rationale=:r, created_by=:by,"
                            + " severity=:sev")
                .param("sev", c.severity())
                .param("p", periodId)
                .param("c", c.caseId())
                .param("r", c.rationale())
                .param("a", c.amountMinor())
                .param("by", c.createdBy())
                .update();
    }

    public List<CarryForward> carryForwards(String periodId) {
        return jdbc.sql(
                        "SELECT case_id, rationale, amount_minor, created_by, severity FROM"
                                + " carry_forward WHERE period_id=:p ORDER BY case_id")
                .param("p", periodId)
                .query(
                        (rs, n) ->
                                new CarryForward(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getLong(3),
                                        rs.getString(4),
                                        rs.getString(5)))
                .list();
    }

    public record HistoryEntry(
            String fromState, String toState, String actor, String reason, Instant at) {}

    public void addHistory(
            String periodId, ClosureState from, ClosureState to, String actor, String reason) {
        jdbc.sql(
                        "INSERT INTO closure_history (period_id, from_state, to_state, actor,"
                                + " reason) VALUES (:p,:f,:t,:a,:r)")
                .param("p", periodId)
                .param("f", from == null ? null : from.name())
                .param("t", to.name())
                .param("a", actor)
                .param("r", reason)
                .update();
    }

    public List<HistoryEntry> history(String periodId) {
        return jdbc.sql(
                        "SELECT from_state, to_state, actor, reason, at_utc FROM closure_history"
                                + " WHERE period_id=:p ORDER BY id")
                .param("p", periodId)
                .query(
                        (rs, n) ->
                                new HistoryEntry(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * True when a CLOSED period of this rail covers the instant: matches inside it must not change
     * (HLD 19.3 auto-lock).
     */
    public boolean isLocked(String tenantId, String rail, Instant at) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM closure_period WHERE tenant_id=:t AND rail=:r"
                                + " AND state='CLOSED' AND from_at <= :at AND to_at > :at)")
                .param("t", tenantId)
                .param("r", rail)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }

    private static ClosurePeriod map(ResultSet rs, int n) throws SQLException {
        OffsetDateTime closed = rs.getObject(13, OffsetDateTime.class);
        return new ClosurePeriod(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, OffsetDateTime.class).toInstant(),
                rs.getObject(6, OffsetDateTime.class).toInstant(),
                ClosureState.valueOf(rs.getString(7)),
                rs.getLong(8),
                rs.getString(9),
                Optional.ofNullable(rs.getString(10)),
                Optional.ofNullable(rs.getString(11)),
                Optional.ofNullable(rs.getString(12)),
                Optional.ofNullable(closed).map(OffsetDateTime::toInstant),
                Optional.ofNullable(rs.getString(14)),
                rs.getLong(15));
    }
}
