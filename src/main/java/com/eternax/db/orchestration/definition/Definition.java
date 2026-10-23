package com.eternax.db.orchestration.definition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A reconciliation definition ties sources, rules, tolerances and schedule together (HLD 18.3).
 * Immutable per version; a change is a new version.
 */
public record Definition(
        String definitionId,
        int version,
        String tenantId,
        String name,
        String rail,
        List<String> requiredSources,
        Duration waitWindow,
        Duration compositeWindow,
        long amountToleranceMinor,
        boolean requireOppositeDirection,
        String cronSchedule,
        String timeZone,
        Status status,
        String createdBy,
        String approvedBy,
        Instant nextRunAt,
        List<com.eternax.recon.domain.MatchingRule> rules,
        Instant firstRunAt) {

    /** A definition without a first-run time (the schedule, if any, is purely the cron). */
    public Definition(
            String definitionId,
            int version,
            String tenantId,
            String name,
            String rail,
            List<String> requiredSources,
            Duration waitWindow,
            Duration compositeWindow,
            long amountToleranceMinor,
            boolean requireOppositeDirection,
            String cronSchedule,
            String timeZone,
            Status status,
            String createdBy,
            String approvedBy,
            Instant nextRunAt,
            List<com.eternax.recon.domain.MatchingRule> rules) {
        this(
                definitionId,
                version,
                tenantId,
                name,
                rail,
                requiredSources,
                waitWindow,
                compositeWindow,
                amountToleranceMinor,
                requireOppositeDirection,
                cronSchedule,
                timeZone,
                status,
                createdBy,
                approvedBy,
                nextRunAt,
                rules,
                null);
    }

    /** A definition without explicit rules uses the default plan. */
    public Definition(
            String definitionId,
            int version,
            String tenantId,
            String name,
            String rail,
            List<String> requiredSources,
            Duration waitWindow,
            Duration compositeWindow,
            long amountToleranceMinor,
            boolean requireOppositeDirection,
            String cronSchedule,
            String timeZone,
            Status status,
            String createdBy,
            String approvedBy,
            Instant nextRunAt) {
        this(
                definitionId,
                version,
                tenantId,
                name,
                rail,
                requiredSources,
                waitWindow,
                compositeWindow,
                amountToleranceMinor,
                requireOppositeDirection,
                cronSchedule,
                timeZone,
                status,
                createdBy,
                approvedBy,
                nextRunAt,
                null);
    }

    /** The steps that will actually be tried, in order. */
    public List<com.eternax.recon.domain.MatchingRule> plan() {
        return com.eternax.recon.domain.MatchingRule.plan(rules);
    }

    /** Lifecycle: Draft, then Active (approved by a second person), then Retired. */
    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }
}
