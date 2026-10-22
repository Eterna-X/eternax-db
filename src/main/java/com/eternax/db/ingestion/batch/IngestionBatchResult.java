package com.eternax.db.ingestion.batch;

import com.eternax.recon.exception.InvalidDomainStateException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outcome of one batch. The constructor enforces the HLD 18.2 invariant: received = accepted +
 * quarantined + duplicates. A batch that violates it cannot be represented, so nothing is silently
 * dropped.
 *
 * @param totalAmount sum of the source's amount column over the structurally valid records, in the
 *     source's own unit; null when the source has no amount column configured
 * @param debitAmount part of {@code totalAmount} on debit rows (needs a direction column)
 * @param creditAmount part of {@code totalAmount} on credit rows
 * @param declaredAmount control total the sender declared, if any
 * @param recordsRejectedDownstream accepted records that later failed normalisation
 */
public record IngestionBatchResult(
        String batchId,
        String sourceId,
        BatchStatus status,
        String statusDetail,
        long recordsReceived,
        long recordsAccepted,
        long recordsQuarantined,
        long recordsDuplicate,
        String feedId,
        Instant receivedAt,
        Instant completedAt,
        String createdBy,
        Integer sourceVersion,
        Long declaredCount,
        BigDecimal totalAmount,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        BigDecimal declaredAmount,
        long recordsRejectedDownstream) {

    public IngestionBatchResult {
        boolean fullyProcessed =
                status == BatchStatus.ACCEPTED
                        || status == BatchStatus.ACCEPTED_WITH_QUARANTINE
                        || status == BatchStatus.ACCEPTED_WITH_FAILURES
                        || status == BatchStatus.REPLAYED;
        long sum = recordsAccepted + recordsQuarantined + recordsDuplicate;
        if (fullyProcessed && sum != recordsReceived) {
            throw new InvalidDomainStateException(
                    "Batch "
                            + batchId
                            + " violates received = accepted + quarantined + duplicates: "
                            + recordsReceived
                            + " != "
                            + sum);
        }
    }

    /** Net movement: credits minus debits, when a direction column is configured. */
    public BigDecimal netAmount() {
        return debitAmount == null || creditAmount == null
                ? null
                : creditAmount.subtract(debitAmount);
    }

    /** Result without the control-total detail. */
    public IngestionBatchResult(
            String batchId,
            String sourceId,
            BatchStatus status,
            String statusDetail,
            long recordsReceived,
            long recordsAccepted,
            long recordsQuarantined,
            long recordsDuplicate) {
        this(
                batchId,
                sourceId,
                status,
                statusDetail,
                recordsReceived,
                recordsAccepted,
                recordsQuarantined,
                recordsDuplicate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0);
    }
}
