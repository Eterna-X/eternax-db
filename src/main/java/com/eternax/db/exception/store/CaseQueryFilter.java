package com.eternax.db.exception.store;

import com.eternax.recon.domain.CaseState;
import com.eternax.recon.domain.ReasonCode;
import java.time.Instant;
import java.util.Optional;

/**
 * Work-queue filter with keyset pagination on (detected_at desc, case_id desc).
 *
 * @param text free-text search over case id, break id, match key, reason detail, explanation and
 *     owner
 */
public record CaseQueryFilter(
        Optional<CaseState> state,
        Optional<ReasonCode> reasonCode,
        Optional<String> owner,
        Optional<String> rail,
        boolean openOnly,
        int limit,
        Optional<Instant> cursorDetectedAt,
        Optional<String> cursorCaseId,
        Optional<String> text) {

    public static CaseQueryFilter openCases(int limit) {
        return new CaseQueryFilter(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                limit,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
