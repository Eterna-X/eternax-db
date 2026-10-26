package com.eternax.db.ingestion.batch;

import com.eternax.recon.exception.EntityNotFoundException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for batches, quarantine entries and the duplicate-detection ledger. */
@Repository
public class BatchRepository {

    private final JdbcClient jdbc;

    public BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BatchRow(
            String batchId,
            String tenantId,
            String sourceId,
            int sourceVersion,
            String feedId,
            String vaultLocation,
            Optional<Long> declaredCount) {}

    /**
     * @return true when the batch row was created, false when the same file was already received
     */
    public boolean createIfAbsent(
            String batchId,
            String tenantId,
            String sourceId,
            int sourceVersion,
            String feedId,
            String checksum,
            String vaultLocation,
            Long declaredCount,
            String actor) {
        return jdbc.sql(
                                """
                                INSERT INTO ingestion_batch (batch_id, tenant_id, source_id, source_version, feed_id,
                                    file_checksum, vault_location, status, declared_count, created_by)
                                VALUES (:id, :t, :s, :sv, :f, :c, :v, 'RECEIVED', :d, :by)
                                ON CONFLICT ON CONSTRAINT uq_batch_feed DO NOTHING
                                """)
                        .param("id", batchId)
                        .param("t", tenantId)
                        .param("s", sourceId)
                        .param("sv", sourceVersion)
                        .param("f", feedId)
                        .param("c", checksum)
                        .param("v", vaultLocation)
                        .param("d", declaredCount)
                        .param("by", actor)
                        .update()
                == 1;
    }

    public Optional<String> findBatchIdByFile(
            String tenantId, String sourceId, String feedId, String checksum) {
        return jdbc.sql(
                        "SELECT batch_id FROM ingestion_batch WHERE tenant_id=:t AND source_id=:s"
                                + " AND feed_id=:f AND file_checksum=:c")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("f", feedId)
                .param("c", checksum)
                .query(String.class)
                .optional();
    }

    public BatchRow load(String tenantId, String batchId) {
        return jdbc.sql(
                        "SELECT batch_id, tenant_id, source_id, source_version, feed_id,"
                                + " vault_location, declared_count FROM ingestion_batch WHERE"
                                + " tenant_id=:t AND batch_id=:b")
                .param("t", tenantId)
                .param("b", batchId)
                .query(
                        (rs, n) ->
                                new BatchRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getObject(7) == null
                                                ? Optional.<Long>empty()
                                                : Optional.of(rs.getLong(7))))
                .optional()
                .orElseThrow(() -> new EntityNotFoundException("IngestionBatch", batchId));
    }

    /** An earlier upload of a feed that a new, different file replaces. */
    public record EarlierFile(String batchId, BatchStatus status, String vaultLocation) {}

    /**
     * Earlier, different files of the same source and feed that are still in use: every batch of
     * the feed except those of the file stored at {@code vaultLocation} and those already
     * discarded.
     */
    public List<EarlierFile> earlierFilesOfFeed(
            String tenantId, String sourceId, String feedId, String vaultLocation) {
        return jdbc.sql(
                        "SELECT batch_id, status, vault_location FROM ingestion_batch WHERE"
                                + " tenant_id=:t AND source_id=:s AND feed_id=:f AND vault_location"
                                + " <> :v AND status <> 'DISCARDED' ORDER BY created_at")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("f", feedId)
                .param("v", vaultLocation)
                .query(
                        (rs, n) ->
                                new EarlierFile(
                                        rs.getString(1),
                                        BatchStatus.valueOf(rs.getString(2)),
                                        rs.getString(3)))
                .list();
    }

    /** The source version a replay reads the batch with, so its result shows that version. */
    public void recordSourceVersion(String batchId, int sourceVersion) {
        jdbc.sql("UPDATE ingestion_batch SET source_version=:v WHERE batch_id=:b")
                .param("v", sourceVersion)
                .param("b", batchId)
                .update();
    }

    public void markStatus(String batchId, BatchStatus status, String detail) {
        jdbc.sql("UPDATE ingestion_batch SET status=:s, status_detail=:d WHERE batch_id=:b")
                .param("s", status.name())
                .param("d", detail)
                .param("b", batchId)
                .update();
    }

    public void complete(
            String batchId,
            BatchStatus status,
            String detail,
            long received,
            long accepted,
            long quarantined,
            long duplicates) {
        // Records are published chunk by chunk before this runs, so the normalizer may already have
        // rejected some of them: keep those rejections (a replay clears the previous attempt's
        // when it starts, see clearRejections) and let them mark the batch.
        jdbc.sql(
                        """
                        UPDATE ingestion_batch SET status = CASE
                                   WHEN rejected_downstream > 0 AND :s IN ('ACCEPTED','ACCEPTED_WITH_QUARANTINE','REPLAYED')
                                   THEN 'ACCEPTED_WITH_FAILURES' ELSE :s END,
                               status_detail=:d, received=:r, accepted=:a,
                               quarantined=:q, duplicates=:dup, completed_at=now() WHERE batch_id=:b
                        """)
                .param("s", status.name())
                .param("d", detail)
                .param("r", received)
                .param("a", accepted)
                .param("q", quarantined)
                .param("dup", duplicates)
                .param("b", batchId)
                .update();
    }

    private static final String RESULT_COLUMNS =
            "b.batch_id, b.source_id, b.status, b.status_detail, b.received, b.accepted,"
                    + " b.quarantined, b.duplicates, b.feed_id, b.created_at, b.completed_at,"
                    + " b.created_by, b.source_version, b.declared_count, b.total_amount,"
                    + " b.debit_amount, b.credit_amount, b.declared_amount, b.rejected_downstream";

    private static IngestionBatchResult mapResult(java.sql.ResultSet rs, int n)
            throws java.sql.SQLException {
        OffsetDateTime completed = rs.getObject(11, OffsetDateTime.class);
        return new IngestionBatchResult(
                rs.getString(1),
                rs.getString(2),
                BatchStatus.valueOf(rs.getString(3)),
                rs.getString(4),
                rs.getLong(5),
                rs.getLong(6),
                rs.getLong(7),
                rs.getLong(8),
                rs.getString(9),
                rs.getObject(10, OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getString(12),
                rs.getInt(13),
                rs.getObject(14) == null ? null : rs.getLong(14),
                rs.getBigDecimal(15),
                rs.getBigDecimal(16),
                rs.getBigDecimal(17),
                rs.getBigDecimal(18),
                rs.getLong(19));
    }

    public IngestionBatchResult result(String tenantId, String batchId) {
        return jdbc.sql(
                        "SELECT "
                                + RESULT_COLUMNS
                                + " FROM ingestion_batch b WHERE b.tenant_id=:t AND b.batch_id=:b")
                .param("t", tenantId)
                .param("b", batchId)
                .query(BatchRepository::mapResult)
                .optional()
                .orElseThrow(() -> new EntityNotFoundException("IngestionBatch", batchId));
    }

    public List<IngestionBatchResult> recent(String tenantId, int limit) {
        return search(tenantId, new BatchFilter(null, null, null, null, null), limit, 0);
    }

    /** Optional filters for the batch monitor; null means "any". */
    public record BatchFilter(
            String sourceId,
            BatchStatus status,
            String rail,
            java.time.Instant from,
            java.time.Instant to) {}

    public List<IngestionBatchResult> search(
            String tenantId, BatchFilter f, int limit, int offset) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT "
                                + RESULT_COLUMNS
                                + " FROM ingestion_batch b WHERE b.tenant_id=:t");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("t", tenantId);
        if (f.sourceId() != null) {
            sql.append(" AND b.source_id = :src");
            params.put("src", f.sourceId());
        }
        if (f.status() != null) {
            sql.append(" AND b.status = :st");
            params.put("st", f.status().name());
        }
        if (f.rail() != null) {
            sql.append(
                    " AND EXISTS (SELECT 1 FROM source_config c WHERE c.tenant_id = b.tenant_id AND"
                        + " c.source_id = b.source_id AND c.version = b.source_version AND c.rail ="
                        + " :rail)");
            params.put("rail", f.rail());
        }
        if (f.from() != null) {
            sql.append(" AND b.created_at >= :from");
            params.put("from", f.from().atOffset(java.time.ZoneOffset.UTC));
        }
        if (f.to() != null) {
            sql.append(" AND b.created_at < :to");
            params.put("to", f.to().atOffset(java.time.ZoneOffset.UTC));
        }
        sql.append(" ORDER BY b.created_at DESC, b.batch_id LIMIT :l OFFSET :o");
        params.put("l", limit);
        params.put("o", offset);
        return jdbc.sql(sql.toString()).params(params).query(BatchRepository::mapResult).list();
    }

    /** Value control totals of a processed file. All nullable: no amount column, no direction. */
    public void recordTotals(
            String batchId,
            java.math.BigDecimal total,
            java.math.BigDecimal debit,
            java.math.BigDecimal credit,
            java.math.BigDecimal declared) {
        jdbc.sql(
                        "UPDATE ingestion_batch SET total_amount=:t, debit_amount=:d,"
                                + " credit_amount=:c, declared_amount=:decl WHERE batch_id=:b")
                .param("t", total)
                .param("d", debit)
                .param("c", credit)
                .param("decl", declared)
                .param("b", batchId)
                .update();
    }

    // ---- records rejected after ingestion (NRM-05)

    public record Rejection(
            String recordReference, String errorCode, String reason, java.time.Instant at) {}

    /**
     * Idempotent: a redelivered event changes nothing. Marks the batch ACCEPTED_WITH_FAILURES once
     * it is otherwise accepted.
     *
     * @return true when the rejection was new
     */
    public boolean addRejection(
            String tenantId,
            String batchId,
            String sourceId,
            String recordReference,
            String errorCode,
            String reason) {
        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO downstream_rejection (tenant_id, batch_id, source_id, record_reference, error_code, reason)
                                VALUES (:t,:b,:s,:r,:c,:x) ON CONFLICT ON CONSTRAINT uq_downstream_rejection DO NOTHING
                                """)
                        .param("t", tenantId)
                        .param("b", batchId)
                        .param("s", sourceId)
                        .param("r", recordReference)
                        .param("c", errorCode)
                        .param("x", reason)
                        .update();
        if (inserted == 0) {
            return false;
        }
        jdbc.sql(
                        """
                        UPDATE ingestion_batch SET rejected_downstream = rejected_downstream + 1,
                            status = CASE WHEN status IN ('ACCEPTED','ACCEPTED_WITH_QUARANTINE','REPLAYED')
                                          THEN 'ACCEPTED_WITH_FAILURES' ELSE status END
                        WHERE tenant_id=:t AND batch_id=:b
                        """)
                .param("t", tenantId)
                .param("b", batchId)
                .update();
        return true;
    }

    public List<Rejection> rejections(String tenantId, String batchId, int limit) {
        return jdbc.sql(
                        "SELECT record_reference, error_code, reason, created_at FROM"
                            + " downstream_rejection WHERE tenant_id=:t AND batch_id=:b ORDER BY id"
                            + " LIMIT :l")
                .param("t", tenantId)
                .param("b", batchId)
                .param("l", limit)
                .query(
                        (rs, n) ->
                                new Rejection(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
    }

    public void addQuarantine(String batchId, String tenantId, QuarantineEntry entry) {
        jdbc.sql(
                        "INSERT INTO quarantine_entry (batch_id, tenant_id, record_reference,"
                                + " reasons, raw_line) VALUES (:b,:t,:r,:x,:l)")
                .param("b", batchId)
                .param("t", tenantId)
                .param("r", entry.recordReference())
                .param("x", String.join(" | ", entry.reasons()))
                .param("l", entry.rawLine())
                .update();
    }

    public void clearQuarantine(String batchId) {
        jdbc.sql("DELETE FROM quarantine_entry WHERE batch_id=:b").param("b", batchId).update();
    }

    /** Before a replay: the previous attempt's downstream failures no longer apply. */
    public void clearRejections(String batchId) {
        jdbc.sql("DELETE FROM downstream_rejection WHERE batch_id=:b").param("b", batchId).update();
        jdbc.sql("UPDATE ingestion_batch SET rejected_downstream=0 WHERE batch_id=:b")
                .param("b", batchId)
                .update();
    }

    public List<QuarantineEntry> quarantine(String tenantId, String batchId, int limit) {
        return jdbc.sql(
                        "SELECT record_reference, reasons, raw_line FROM quarantine_entry WHERE"
                                + " tenant_id=:t AND batch_id=:b ORDER BY id LIMIT :l")
                .param("t", tenantId)
                .param("b", batchId)
                .param("l", limit)
                .query(
                        (rs, n) ->
                                new QuarantineEntry(
                                        rs.getString(1),
                                        List.of(rs.getString(2).split(" \\| ")),
                                        rs.getString(3)))
                .list();
    }

    /** True when an identical record was accepted for this source within the duplicate window. */
    public boolean isDuplicate(String tenantId, String sourceId, String hash, Duration window) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM ingested_record WHERE tenant_id=:t AND"
                                + " source_id=:s AND content_hash=:h AND created_at >= :since)")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("h", hash)
                .param("since", OffsetDateTime.now().minus(window))
                .query(Boolean.class)
                .single();
    }

    public void remember(String tenantId, String sourceId, String hash, String batchId) {
        jdbc.sql(
                        "INSERT INTO ingested_record (tenant_id, source_id, content_hash, batch_id)"
                                + " VALUES (:t,:s,:h,:b)")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("h", hash)
                .param("b", batchId)
                .update();
    }

    /**
     * Used by replay: forgets what this batch accepted so it is re-processed (downstream is
     * idempotent).
     */
    public void forgetBatch(String batchId) {
        jdbc.sql("DELETE FROM ingested_record WHERE batch_id=:b").param("b", batchId).update();
    }
}
