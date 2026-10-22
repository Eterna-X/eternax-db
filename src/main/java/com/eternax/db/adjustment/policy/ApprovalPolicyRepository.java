package com.eternax.db.adjustment.policy;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Approval policies per tenant (ADJ-15, BR-10, BR-11). Priorities are unique per tenant: a clash
 * surfaces as Spring's {@link org.springframework.dao.DuplicateKeyException}.
 */
@Repository
public class ApprovalPolicyRepository {

    public record Policy(
            String policyId,
            int priority,
            String name,
            String adjustmentType,
            String severity,
            String rail,
            long minAmountMinor,
            boolean secondApproval,
            boolean evidenceRequired,
            boolean enabled) {}

    private final JdbcClient jdbc;

    public ApprovalPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT =
            "SELECT policy_id, priority, name, adjustment_type, severity, rail, min_amount_minor,"
                    + " second_approval, evidence_required, enabled FROM approval_policy";

    private static Policy map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Policy(
                rs.getString(1),
                rs.getInt(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getLong(7),
                rs.getBoolean(8),
                rs.getBoolean(9),
                rs.getBoolean(10));
    }

    /** The tenant's policies, lowest priority number first. */
    public List<Policy> list(String tenantId) {
        return jdbc.sql(SELECT + " WHERE tenant_id=:t ORDER BY priority")
                .param("t", tenantId)
                .query(ApprovalPolicyRepository::map)
                .list();
    }

    public void insert(String tenantId, String policyId, Policy p, String actor) {
        jdbc.sql(
                        "INSERT INTO approval_policy (policy_id, tenant_id, priority, name,"
                                + " adjustment_type, severity, rail, min_amount_minor,"
                                + " second_approval, evidence_required, enabled, created_by) VALUES"
                                + " (:i,:t,:p,:n,:ty,:sv,:r,:m,:s,:e,:en,:by)")
                .param("i", policyId)
                .param("t", tenantId)
                .param("p", p.priority())
                .param("n", p.name())
                .param("ty", p.adjustmentType())
                .param("sv", p.severity())
                .param("r", p.rail())
                .param("m", p.minAmountMinor())
                .param("s", p.secondApproval())
                .param("e", p.evidenceRequired())
                .param("en", p.enabled())
                .param("by", actor)
                .update();
    }

    public void update(String tenantId, String policyId, Policy p) {
        jdbc.sql(
                        "UPDATE approval_policy SET priority=:p, name=:n, adjustment_type=:ty,"
                                + " severity=:sv, rail=:r, min_amount_minor=:m, second_approval=:s,"
                                + " evidence_required=:e, enabled=:en WHERE tenant_id=:t AND"
                                + " policy_id=:i")
                .param("i", policyId)
                .param("t", tenantId)
                .param("p", p.priority())
                .param("n", p.name())
                .param("ty", p.adjustmentType())
                .param("sv", p.severity())
                .param("r", p.rail())
                .param("m", p.minAmountMinor())
                .param("s", p.secondApproval())
                .param("e", p.evidenceRequired())
                .param("en", p.enabled())
                .update();
    }

    public void delete(String tenantId, String policyId) {
        jdbc.sql("DELETE FROM approval_policy WHERE tenant_id=:t AND policy_id=:i")
                .param("t", tenantId)
                .param("i", policyId)
                .update();
    }
}
