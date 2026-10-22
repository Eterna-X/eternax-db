package com.eternax.db.adjustment.store;

import com.eternax.recon.common.Money;
import com.eternax.recon.common.TypedId;
import com.eternax.recon.domain.Adjustment;
import com.eternax.recon.domain.AdjustmentStatus;
import com.eternax.recon.domain.AdjustmentType;
import com.eternax.recon.exception.EntityNotFoundException;
import com.eternax.recon.exception.OptimisticLockConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdjustmentRepository {

    private static final String COLUMNS =
            "adjustment_id, tenant_id, case_id, type, amount_minor, currency, narrative,"
                + " proposed_by, proposed_at, first_approver, second_approver, journal_reference,"
                + " status, version, rejection_reason, over_break";

    private final JdbcClient jdbc;

    public AdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Totals of one case's adjustments, used to decide when the case is fully covered. */
    public record Coverage(
            long committedMinor,
            long postedOrConfirmedMinor,
            long confirmedMinor,
            long openCount,
            long postedUnconfirmedCount) {}

    public Coverage coverage(String tenantId, String caseId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(amount_minor) FILTER (WHERE status <> 'REJECTED'), 0),
                               COALESCE(SUM(amount_minor) FILTER (WHERE status IN ('POSTED','CONFIRMED')), 0),
                               COALESCE(SUM(amount_minor) FILTER (WHERE status = 'CONFIRMED'), 0),
                               COUNT(*) FILTER (WHERE status IN ('PROPOSED','MAKER_APPROVED','CHECKER_APPROVED')),
                               COUNT(*) FILTER (WHERE status = 'POSTED')
                        FROM adjustment WHERE tenant_id = :t AND case_id = :c
                        """)
                .param("t", tenantId)
                .param("c", caseId)
                .query(
                        (rs, n) ->
                                new Coverage(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5)))
                .single();
    }

    /** Break amount of the case recorded when the adjustment was proposed; 0 when unknown. */
    public long caseImpact(String tenantId, String adjustmentId) {
        return jdbc.sql(
                        "SELECT COALESCE(case_impact_minor, 0) FROM adjustment"
                                + " WHERE tenant_id=:t AND adjustment_id=:id")
                .param("t", tenantId)
                .param("id", adjustmentId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** What the approval policy decided for this adjustment when it was proposed. */
    public record Policy(
            String policyId,
            boolean secondApproval,
            boolean evidenceRequired,
            String evidenceReference) {}

    public Policy policyOf(String tenantId, String adjustmentId) {
        return jdbc.sql(
                        "SELECT policy_id, requires_second_approval, evidence_required,"
                                + " evidence_reference FROM adjustment WHERE tenant_id=:t AND"
                                + " adjustment_id=:id")
                .param("t", tenantId)
                .param("id", adjustmentId)
                .query(
                        (rs, n) ->
                                new Policy(
                                        rs.getString(1),
                                        rs.getBoolean(2),
                                        rs.getBoolean(3),
                                        rs.getString(4)))
                .optional()
                .orElse(new Policy(null, false, false, null));
    }

    public void insert(Adjustment a, long caseImpactMinor) {
        insert(a, caseImpactMinor, new Policy(null, false, false, null));
    }

    public void insert(Adjustment a, long caseImpactMinor, Policy policy) {
        jdbc.sql(
                        """
                        INSERT INTO adjustment (adjustment_id, tenant_id, case_id, type, amount_minor, currency, narrative,
                            proposed_by, proposed_at, status, case_impact_minor, over_break,
                            policy_id, requires_second_approval, evidence_required, evidence_reference)
                        VALUES (:id,:t,:c,:ty,:amt,:cur,:n,:by,:at,:st,:impact,:ob,:pid,:sec,:evr,:evref)
                        """)
                .param("id", a.adjustmentId().value())
                .param("t", a.tenantId().value())
                .param("c", a.caseId().value())
                .param("ty", a.type().name())
                .param("amt", a.amount().amountMinor())
                .param("cur", a.amount().currencyCode())
                .param("n", a.narrative())
                .param("by", a.proposedBy())
                .param("at", a.proposedAtUtc().atOffset(ZoneOffset.UTC))
                .param("st", a.status().name())
                .param("impact", caseImpactMinor)
                .param("ob", a.overBreak())
                .param("pid", policy.policyId())
                .param("sec", policy.secondApproval())
                .param("evr", policy.evidenceRequired())
                .param("evref", policy.evidenceReference())
                .update();
    }

    /**
     * @throws OptimisticLockConflictException if the adjustment changed since it was read
     */
    public void update(Adjustment a) {
        int rows =
                jdbc.sql(
                                """
                                UPDATE adjustment SET status=:st, first_approver=:f, second_approver=:s, journal_reference=:j,
                                       rejection_reason=:rr, version = version + 1, updated_at = now()
                                WHERE adjustment_id=:id AND tenant_id=:t AND version=:v
                                """)
                        .param("st", a.status().name())
                        .param("f", a.firstApprover().orElse(null))
                        .param("s", a.secondApprover().orElse(null))
                        .param("j", a.journalReference().orElse(null))
                        .param("rr", a.rejectionReason().orElse(null))
                        .param("id", a.adjustmentId().value())
                        .param("t", a.tenantId().value())
                        .param("v", a.version())
                        .update();
        if (rows == 0) {
            throw new OptimisticLockConflictException(
                    "Adjustment", a.adjustmentId().value(), a.version());
        }
    }

    public Adjustment get(String tenantId, String id) {
        return find(tenantId, id).orElseThrow(() -> new EntityNotFoundException("Adjustment", id));
    }

    public Optional<Adjustment> find(String tenantId, String id) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM adjustment WHERE tenant_id=:t AND adjustment_id=:id")
                .param("t", tenantId)
                .param("id", id)
                .query(AdjustmentRepository::map)
                .optional();
    }

    public Optional<Adjustment> findByJournalReference(String tenantId, String journalReference) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM adjustment WHERE tenant_id=:t AND journal_reference=:j")
                .param("t", tenantId)
                .param("j", journalReference)
                .query(AdjustmentRepository::map)
                .optional();
    }

    public List<Adjustment> list(
            String tenantId,
            Optional<AdjustmentStatus> status,
            Optional<String> caseId,
            int limit) {
        StringBuilder sql =
                new StringBuilder("SELECT " + COLUMNS + " FROM adjustment WHERE tenant_id = :t");
        status.ifPresent(
                s ->
                        sql.append(" AND status = '")
                                .append(s.name())
                                .append("'")); // enum-derived, not user text
        caseId.ifPresent(c -> sql.append(" AND case_id = :c"));
        sql.append(" ORDER BY proposed_at DESC, adjustment_id LIMIT :l");
        var q = jdbc.sql(sql.toString()).param("t", tenantId).param("l", limit);
        if (caseId.isPresent()) {
            q = q.param("c", caseId.get());
        }
        return q.query(AdjustmentRepository::map).list();
    }

    private static Adjustment map(ResultSet rs, int n) throws SQLException {
        Adjustment base =
                new Adjustment(
                        TypedId.of(rs.getString(1)),
                        TypedId.of(rs.getString(2)),
                        TypedId.of(rs.getString(3)),
                        AdjustmentType.valueOf(rs.getString(4)),
                        new Money(rs.getLong(5), rs.getString(6).trim()),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getObject(9, OffsetDateTime.class).toInstant());
        return Adjustment.restore(
                base,
                AdjustmentStatus.valueOf(rs.getString(13)),
                Optional.ofNullable(rs.getString(10)),
                Optional.ofNullable(rs.getString(11)),
                Optional.ofNullable(rs.getString(12)),
                rs.getLong(14),
                Optional.ofNullable(rs.getString(15)),
                rs.getBoolean(16));
    }
}
