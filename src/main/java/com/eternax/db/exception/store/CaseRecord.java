package com.eternax.db.exception.store;

import com.eternax.recon.domain.ReconciliationCase;
import java.time.Instant;

/**
 * A case with the workflow attributes that belong to the exception service, not to the domain
 * object.
 *
 * @param priorityOverride manually set priority (LOW, MEDIUM, HIGH, CRITICAL), or null to use the
 *     computed score
 * @param escalatedTo team or person the case was escalated to, or null
 */
public record CaseRecord(
        ReconciliationCase domain,
        String rail,
        String matchKey,
        String detailCode,
        String explanation,
        Instant transactionTime,
        String closureReason,
        String priorityOverride,
        boolean escalated,
        String escalatedTo) {

    public CaseRecord(
            ReconciliationCase domain,
            String rail,
            String matchKey,
            String detailCode,
            String explanation,
            Instant transactionTime,
            String closureReason) {
        this(
                domain,
                rail,
                matchKey,
                detailCode,
                explanation,
                transactionTime,
                closureReason,
                null,
                false,
                null);
    }

    public CaseRecord withClosureReason(String reason) {
        return new CaseRecord(
                domain,
                rail,
                matchKey,
                detailCode,
                explanation,
                transactionTime,
                reason,
                priorityOverride,
                escalated,
                escalatedTo);
    }

    public CaseRecord withPriority(String priority) {
        return new CaseRecord(
                domain,
                rail,
                matchKey,
                detailCode,
                explanation,
                transactionTime,
                closureReason,
                priority,
                escalated,
                escalatedTo);
    }

    public CaseRecord withEscalation(String to) {
        return new CaseRecord(
                domain,
                rail,
                matchKey,
                detailCode,
                explanation,
                transactionTime,
                closureReason,
                priorityOverride,
                true,
                to);
    }
}
