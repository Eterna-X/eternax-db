package com.eternax.db.matching.settlement;

import com.eternax.recon.events.CanonicalTransactionEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for settlement records and their per-key comparison (SET-01). */
@Repository
public class SettlementRepository {

    public record Totals(
            long amountMinor,
            int records,
            List<LocalDate> dates,
            List<String> accounts,
            List<String> statuses,
            List<String> batches) {
        static Totals empty() {
            return new Totals(0, 0, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Comparison(
            String tenantId,
            String rail,
            String settlementKey,
            String state,
            String varianceCode,
            String varianceDetail,
            Long varianceMinor,
            String breakId,
            Instant firstSeenAt,
            Instant deadlineAt,
            Instant evaluatedAt) {}

    public record Record(
            String recordId,
            String side,
            String sourceId,
            String settlementRef,
            String batchId,
            LocalDate settlementDate,
            long amountMinor,
            String currency,
            String accountRef,
            String status,
            Instant txnTime) {}

    public record BatchRow(
            String batch,
            long expectedMinor,
            long actualMinor,
            int expectedRecords,
            int actualRecords) {}

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false when this exact record was already stored (redelivery)
     */
    public boolean insertRecord(CanonicalTransactionEvent e, String side) {
        return jdbc.sql(
                                """
                                INSERT INTO settlement_record (record_id, tenant_id, rail, side, source_id, feed_id, record_hash,
                                    settlement_key, settlement_ref, batch_id, settlement_date, amount_minor, currency, account_ref,
                                    status, txn_time, vault_location)
                                VALUES (:id,:t,:r,:side,:src,:feed,:hash,:key,:ref,:batch,:sd,:amt,:cur,:acct,:st,:tt,:vault)
                                ON CONFLICT ON CONSTRAINT uq_settlement_idempotency DO NOTHING
                                """)
                        .param("id", e.transactionId())
                        .param("t", e.tenantId())
                        .param("r", e.rail())
                        .param("side", side)
                        .param("src", e.sourceId())
                        .param("feed", e.feedId())
                        .param("hash", e.recordHash())
                        .param("key", e.settlementKey())
                        .param("ref", e.settlementRef())
                        .param("batch", e.batchReference())
                        .param(
                                "sd",
                                e.settlementDate() == null
                                        ? null
                                        : LocalDate.parse(e.settlementDate()))
                        .param("amt", e.amountMinor())
                        .param("cur", e.currency())
                        .param("acct", e.accountReferenceToken())
                        .param("st", e.transactionStatus())
                        .param("tt", e.transactionTimeUtc().atOffset(ZoneOffset.UTC))
                        .param("vault", e.vaultLocation())
                        .update()
                == 1;
    }

    /**
     * Creates the comparison if absent and returns it locked, so one key is evaluated at a time.
     */
    public Comparison lock(
            String tenantId, String rail, String key, Instant now, Instant deadline) {
        jdbc.sql(
                        "INSERT INTO settlement_comparison (tenant_id, rail, settlement_key, state,"
                            + " first_seen_at, deadline_at) VALUES (:t,:r,:k,'WAITING',:now,:d) ON"
                            + " CONFLICT DO NOTHING")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", key)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("d", deadline.atOffset(ZoneOffset.UTC))
                .update();
        return jdbc.sql(
                        COMPARISON_SELECT
                                + " WHERE tenant_id=:t AND rail=:r AND settlement_key=:k FOR"
                                + " UPDATE")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", key)
                .query(SettlementRepository::comparison)
                .single();
    }

    public List<Comparison> dueComparisons(Instant now, int limit) {
        return jdbc.sql(
                        COMPARISON_SELECT
                                + " WHERE state='WAITING' AND deadline_at <= :now ORDER BY"
                                + " deadline_at LIMIT :l FOR UPDATE SKIP LOCKED")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(SettlementRepository::comparison)
                .list();
    }

    public Totals totals(String tenantId, String rail, String key, String side) {
        List<Record> rows =
                records(tenantId, rail, key).stream().filter(r -> r.side().equals(side)).toList();
        if (rows.isEmpty()) {
            return Totals.empty();
        }
        return new Totals(
                rows.stream().mapToLong(Record::amountMinor).sum(),
                rows.size(),
                rows.stream()
                        .map(Record::settlementDate)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(),
                rows.stream()
                        .map(Record::accountRef)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(),
                rows.stream().map(Record::status).distinct().sorted().toList(),
                rows.stream()
                        .map(Record::batchId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList());
    }

    public List<Record> records(String tenantId, String rail, String key) {
        return jdbc.sql(
                        "SELECT record_id, side, source_id, settlement_ref, batch_id,"
                            + " settlement_date, amount_minor, currency, account_ref, status,"
                            + " txn_time FROM settlement_record WHERE tenant_id=:t AND rail=:r AND"
                            + " settlement_key=:k ORDER BY side, txn_time, record_id")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", key)
                .query(
                        (rs, n) ->
                                new Record(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getObject(6, LocalDate.class),
                                        rs.getLong(7),
                                        rs.getString(8).trim(),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getObject(11, OffsetDateTime.class).toInstant()))
                .list();
    }

    public void update(
            Comparison c,
            String state,
            String code,
            String detail,
            Long varianceMinor,
            String breakId,
            Instant at) {
        jdbc.sql(
                        "UPDATE settlement_comparison SET state=:s, variance_code=:c,"
                                + " variance_detail=:d, variance_minor=:m, break_id=COALESCE(:b,"
                                + " break_id), evaluated_at=:at WHERE tenant_id=:t AND rail=:r AND"
                                + " settlement_key=:k")
                .param("s", state)
                .param("c", code)
                .param("d", detail)
                .param("m", varianceMinor)
                .param("b", breakId)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("t", c.tenantId())
                .param("r", c.rail())
                .param("k", c.settlementKey())
                .update();
    }

    /**
     * A new record broke an agreement that was already reached: look at the key again at the
     * deadline.
     */
    public void reopen(Comparison c, Instant deadline) {
        jdbc.sql(
                        "UPDATE settlement_comparison SET state='WAITING', deadline_at=:d WHERE"
                                + " tenant_id=:t AND rail=:r AND settlement_key=:k")
                .param("d", deadline.atOffset(ZoneOffset.UTC))
                .param("t", c.tenantId())
                .param("r", c.rail())
                .param("k", c.settlementKey())
                .update();
    }

    public Optional<Comparison> find(String tenantId, String rail, String key) {
        return jdbc.sql(COMPARISON_SELECT + " WHERE tenant_id=:t AND rail=:r AND settlement_key=:k")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", key)
                .query(SettlementRepository::comparison)
                .optional();
    }

    public List<Comparison> list(String tenantId, String state, int limit, int offset) {
        return jdbc.sql(
                        COMPARISON_SELECT
                                + " WHERE tenant_id=:t AND (CAST(:s AS varchar) IS NULL OR state ="
                                + " CAST(:s AS varchar)) ORDER BY COALESCE(evaluated_at,"
                                + " first_seen_at) DESC, settlement_key LIMIT :l OFFSET :o")
                .param("t", tenantId)
                .param("s", state)
                .param("l", limit)
                .param("o", offset)
                .query(SettlementRepository::comparison)
                .list();
    }

    public record Summary(
            long waiting,
            long matched,
            long variances,
            long expectedMinor,
            long actualMinor,
            long varianceMinor) {}

    public Summary summary(String tenantId) {
        return jdbc.sql(
                        """
                        SELECT count(*) FILTER (WHERE c.state='WAITING'), count(*) FILTER (WHERE c.state='MATCHED'),
                               count(*) FILTER (WHERE c.state='VARIANCE'),
                               COALESCE((SELECT sum(amount_minor) FROM settlement_record WHERE tenant_id=:t AND side='EXPECTED'), 0),
                               COALESCE((SELECT sum(amount_minor) FROM settlement_record WHERE tenant_id=:t AND side='ACTUAL'), 0),
                               COALESCE(sum(abs(c.variance_minor)) FILTER (WHERE c.state='VARIANCE'), 0)
                        FROM settlement_comparison c WHERE c.tenant_id=:t
                        """)
                .param("t", tenantId)
                .query(
                        (rs, n) ->
                                new Summary(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4),
                                        rs.getLong(5),
                                        rs.getLong(6)))
                .single();
    }

    /** Totals per settlement batch (or per settlement date when the files carry no batch id). */
    public List<BatchRow> batches(String tenantId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(batch_id, CAST(settlement_date AS varchar), 'no-batch') AS b,
                               COALESCE(sum(amount_minor) FILTER (WHERE side='EXPECTED'), 0),
                               COALESCE(sum(amount_minor) FILTER (WHERE side='ACTUAL'), 0),
                               count(*) FILTER (WHERE side='EXPECTED'), count(*) FILTER (WHERE side='ACTUAL')
                        FROM settlement_record
                        WHERE tenant_id=:t AND (CAST(:f AS date) IS NULL OR settlement_date >= CAST(:f AS date))
                          AND (CAST(:to AS date) IS NULL OR settlement_date <= CAST(:to AS date))
                        GROUP BY 1 ORDER BY 1 LIMIT :l
                        """)
                .param("t", tenantId)
                .param("f", from)
                .param("to", to)
                .param("l", limit)
                .query(
                        (rs, n) ->
                                new BatchRow(
                                        rs.getString(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getInt(4),
                                        rs.getInt(5)))
                .list();
    }

    private static final String COMPARISON_SELECT =
            "SELECT tenant_id, rail, settlement_key, state, variance_code, variance_detail,"
                    + " variance_minor, break_id, first_seen_at, deadline_at, evaluated_at FROM"
                    + " settlement_comparison";

    private static Comparison comparison(java.sql.ResultSet rs, int n)
            throws java.sql.SQLException {
        OffsetDateTime evaluated = rs.getObject(11, OffsetDateTime.class);
        return new Comparison(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getObject(7) == null ? null : rs.getLong(7),
                rs.getString(8),
                rs.getObject(9, OffsetDateTime.class).toInstant(),
                rs.getObject(10, OffsetDateTime.class).toInstant(),
                evaluated == null ? null : evaluated.toInstant());
    }
}
