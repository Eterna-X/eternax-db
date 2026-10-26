package com.eternax.db.matching.store;

import com.eternax.recon.common.Money;
import com.eternax.recon.common.TypedId;
import com.eternax.recon.domain.Allocation;
import com.eternax.recon.domain.CanonicalTransaction;
import com.eternax.recon.domain.Direction;
import com.eternax.recon.domain.MatchGroup;
import com.eternax.recon.domain.Rail;
import com.eternax.recon.domain.RawRecordPointer;
import com.eternax.recon.domain.ReconciliationStatus;
import com.eternax.recon.domain.ReferenceKeys;
import com.eternax.recon.domain.TransactionStatus;
import com.eternax.recon.events.CanonicalTransactionEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL of the matching worker. Callers own the transaction boundary. */
@Repository
public class MatchingRepository {

    private static final String TXN_COLUMNS =
            "transaction_id, tenant_id, rail, source_id, feed_id, record_hash, direction,"
                    + " amount_minor, currency, txn_time, rrn, utr, terminal_id, merchant_id,"
                    + " txn_status, account_token, vault_location, vault_checksum, recon_status,"
                    + " value_date, posting_date, settlement_date, stan, auth_code, arn,"
                    + " batch_reference, channel, settlement_status, parent_transaction_id,"
                    + " source_time_zone, fee_minor, net_minor";

    private final JdbcClient jdbc;

    /** The zone a record's calendar day (value date) is read in when the source gives none. */
    private final ZoneId sourceZone;

    @org.springframework.beans.factory.annotation.Autowired
    public MatchingRepository(
            JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Value(
                            "${eternax.matching.source-time-zone:Asia/Kolkata}")
                    String sourceZone) {
        this.jdbc = jdbc;
        this.sourceZone = ZoneId.of(sourceZone);
    }

    public MatchingRepository(JdbcClient jdbc) {
        this(jdbc, "Asia/Kolkata");
    }

    /**
     * @return false when this record was already stored (redelivery), so it must not be processed
     *     again
     */
    public boolean insertTransaction(CanonicalTransactionEvent e, String matchKey) {
        return insertTransaction(e, matchKey, "WAITING");
    }

    /**
     * @param status WAITING normally; PERIOD_LOCKED for a record that arrives inside a closed
     *     period
     */
    public boolean insertTransaction(CanonicalTransactionEvent e, String matchKey, String status) {
        return jdbc.sql(
                                """
                                INSERT INTO canonical_transaction (transaction_id, tenant_id, rail, source_id, feed_id, record_hash,
                                    direction, amount_minor, currency, txn_time, rrn, utr, terminal_id, merchant_id, txn_status,
                                    account_token, vault_location, vault_checksum, match_key, recon_status,
                                    value_date, posting_date, settlement_date, stan, auth_code, arn, batch_reference, channel,
                                    settlement_status, parent_transaction_id, source_time_zone, fee_minor, net_minor, fees_json, attributes_json)
                                VALUES (:id, :t, :rail, :src, :feed, :hash, :dir, :amt, :cur, :time, :rrn, :utr, :term, :merch,
                                    :status, :acct, :vault, :sum, :key, :recon,
                                    :vd, :pd, :sd, :stan, :auth, :arn, :batch, :chan, :ss, :parent, :tz, :fee, :net, :fees, :attrs)
                                ON CONFLICT ON CONSTRAINT uq_txn_idempotency DO NOTHING
                                """)
                        .param("id", e.transactionId())
                        .param("t", e.tenantId())
                        .param("rail", e.rail())
                        .param("src", e.sourceId())
                        .param("feed", e.feedId())
                        .param("hash", e.recordHash())
                        .param("dir", e.direction())
                        .param("amt", e.amountMinor())
                        .param("cur", e.currency())
                        .param("time", e.transactionTimeUtc().atOffset(ZoneOffset.UTC))
                        .param("rrn", e.rrn())
                        .param("utr", e.utr())
                        .param("term", e.terminalId())
                        .param("merch", e.merchantId())
                        .param("status", e.transactionStatus())
                        .param("acct", e.accountReferenceToken())
                        .param("vault", e.vaultLocation())
                        .param("sum", e.vaultChecksum())
                        .param("key", matchKey)
                        .param("recon", status)
                        .param("vd", date(e.valueDate()))
                        .param("pd", date(e.postingDate()))
                        .param("sd", date(e.settlementDate()))
                        .param("stan", e.stan())
                        .param("auth", e.authCode())
                        .param("arn", e.arn())
                        .param("batch", e.batchReference())
                        .param("chan", e.channel())
                        .param("ss", e.settlementStatus())
                        .param("parent", e.parentTransactionId())
                        .param("tz", e.sourceTimeZone())
                        .param("fee", feeTotal(e))
                        .param("net", feeTotal(e) == null ? null : e.amountMinor() - feeTotal(e))
                        .param("fees", feesJson(e))
                        .param("attrs", attributesJson(e))
                        .update()
                == 1;
    }

    private static LocalDate date(String iso) {
        return iso == null || iso.isBlank() ? null : LocalDate.parse(iso);
    }

    private static Long feeTotal(CanonicalTransactionEvent e) {
        return e.fees() == null || e.fees().isEmpty()
                ? null
                : e.fees().stream().mapToLong(CanonicalTransactionEvent.Fee::amountMinor).sum();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String attributesJson(CanonicalTransactionEvent e) {
        if (e.attributes() == null || e.attributes().isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(e.attributes());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Attributes kept with the given records, by transaction id; records without any are absent.
     */
    public java.util.Map<String, java.util.Map<String, String>> attributes(
            Collection<String> transactionIds) {
        java.util.Map<String, java.util.Map<String, String>> out = new java.util.HashMap<>();
        if (transactionIds.isEmpty()) {
            return out;
        }
        jdbc.sql(
                        "SELECT transaction_id, attributes_json FROM canonical_transaction"
                                + " WHERE transaction_id IN (:ids) AND attributes_json IS NOT NULL")
                .param("ids", transactionIds)
                .query(
                        rs -> {
                            try {
                                out.put(
                                        rs.getString(1),
                                        JSON.readValue(
                                                rs.getString(2),
                                                new com.fasterxml.jackson.core.type.TypeReference<
                                                        java.util.Map<String, String>>() {}));
                            } catch (java.io.IOException ex) {
                                throw new IllegalStateException(ex);
                            }
                        });
        return out;
    }

    private static String feesJson(CanonicalTransactionEvent e) {
        if (e.fees() == null || e.fees().isEmpty()) {
            return null;
        }
        return e.fees().stream()
                .map(
                        f ->
                                "{\"type\":\""
                                        + f.feeType().replace("\"", "")
                                        + "\",\"amountMinor\":"
                                        + f.amountMinor()
                                        + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * Creates the cluster if absent, then returns it locked so concurrent handlers of one key
     * serialise.
     */
    public ClusterRow lockCluster(
            String tenantId,
            String rail,
            String matchKey,
            String definitionId,
            Instant now,
            Instant deadline) {
        jdbc.sql(
                        """
                        INSERT INTO match_cluster (tenant_id, rail, match_key, definition_id, state, first_seen_at, deadline_at)
                        VALUES (:t, :r, :k, :d, 'WAITING', :now, :deadline)
                        ON CONFLICT (tenant_id, rail, match_key) DO NOTHING
                        """)
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .param("d", definitionId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("deadline", deadline.atOffset(ZoneOffset.UTC))
                .update();
        return jdbc.sql(
                        "SELECT tenant_id, rail, match_key, definition_id, state, deadline_at,"
                                + " break_id FROM match_cluster WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k FOR UPDATE")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(MatchingRepository::cluster)
                .single();
    }

    /** A record arrived for a cluster that already raised a break: wait again for the rest. */
    public void reopenCluster(String tenantId, String rail, String matchKey, Instant deadline) {
        jdbc.sql(
                        "UPDATE match_cluster SET state='WAITING', deadline_at=:d WHERE"
                                + " tenant_id=:t AND rail=:r AND match_key=:k AND state IN"
                                + " ('EXCEPTION','LOCKED')")
                .param("d", deadline.atOffset(ZoneOffset.UTC))
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .update();
    }

    public List<ClusterRow> dueClusters(Instant now, int limit) {
        return jdbc.sql(
                        """
                        SELECT tenant_id, rail, match_key, definition_id, state, deadline_at, break_id FROM match_cluster
                        WHERE state = 'WAITING' AND deadline_at <= :now ORDER BY deadline_at LIMIT :l FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(MatchingRepository::cluster)
                .list();
    }

    public void markException(ClusterRow cluster, String breakId) {
        jdbc.sql(
                        "UPDATE match_cluster SET state='EXCEPTION', break_id=:b WHERE tenant_id=:t"
                                + " AND rail=:r AND match_key=:k")
                .param("b", breakId)
                .param("t", cluster.tenantId())
                .param("r", cluster.rail())
                .param("k", cluster.matchKey())
                .update();
        jdbc.sql(
                        "UPDATE canonical_transaction SET recon_status='EXCEPTION' WHERE"
                                + " tenant_id=:t AND rail=:r AND match_key=:k AND recon_status <>"
                                + " 'MATCHED'")
                .param("t", cluster.tenantId())
                .param("r", cluster.rail())
                .param("k", cluster.matchKey())
                .update();
    }

    /**
     * The cluster's period was closed while it waited: no break is raised, its records are frozen.
     */
    public void markLocked(ClusterRow cluster) {
        jdbc.sql(
                        "UPDATE match_cluster SET state='LOCKED' WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k")
                .param("t", cluster.tenantId())
                .param("r", cluster.rail())
                .param("k", cluster.matchKey())
                .update();
        jdbc.sql(
                        "UPDATE canonical_transaction SET recon_status='PERIOD_LOCKED' WHERE"
                                + " tenant_id=:t AND rail=:r AND match_key=:k AND recon_status <>"
                                + " 'MATCHED'")
                .param("t", cluster.tenantId())
                .param("r", cluster.rail())
                .param("k", cluster.matchKey())
                .update();
    }

    /** Records frozen inside the given period, oldest first, for replay after a reopen. */
    public List<String> lockedTransactionIds(
            String tenantId, String rail, Instant from, Instant to, int limit) {
        return jdbc.sql(
                        "SELECT transaction_id FROM canonical_transaction WHERE tenant_id=:t AND"
                                + " rail=:r AND recon_status='PERIOD_LOCKED' AND txn_time >= :f AND"
                                + " txn_time < :to ORDER BY txn_time, transaction_id LIMIT :l")
                .param("t", tenantId)
                .param("r", rail)
                .param("f", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(String.class)
                .list();
    }

    public void unfreeze(String transactionId) {
        jdbc.sql(
                        "UPDATE canonical_transaction SET recon_status='WAITING' WHERE"
                                + " transaction_id=:id AND recon_status='PERIOD_LOCKED'")
                .param("id", transactionId)
                .update();
    }

    /** Records of the given sources in a time range, for read-only simulation. */
    public List<CanonicalTransaction> sample(
            String tenantId,
            String rail,
            List<String> sources,
            Instant from,
            Instant to,
            int limit) {
        return jdbc.sql(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE tenant_id=:t AND rail=:r AND"
                                + " source_id IN (:s) AND txn_time >= :f AND txn_time < :to AND"
                                + " recon_status <> 'PERIOD_LOCKED' ORDER BY txn_time,"
                                + " transaction_id LIMIT :l")
                .param("t", tenantId)
                .param("r", rail)
                .param("s", sources)
                .param("f", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(this::transaction)
                .list();
    }

    public Optional<CanonicalTransaction> load(String transactionId) {
        return jdbc.sql(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE transaction_id = :id")
                .param("id", transactionId)
                .query(this::transaction)
                .optional();
    }

    public List<CanonicalTransaction> unallocatedByMatchKey(
            String tenantId, String rail, String matchKey) {
        return jdbc.sql(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k AND recon_status NOT IN"
                                + " ('MATCHED','PERIOD_LOCKED') ORDER BY txn_time, transaction_id")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(this::transaction)
                .list();
    }

    public List<CanonicalTransaction> allByMatchKey(String tenantId, String rail, String matchKey) {
        return jdbc.sql(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k ORDER BY txn_time, transaction_id")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(this::transaction)
                .list();
    }

    /**
     * Time-bucketed composite lookup, capped per HLD 7.5. Either key may be null (not a grouping
     * field).
     */
    public List<CanonicalTransaction> compositeCandidates(
            String tenantId,
            String rail,
            String terminalId,
            String merchantId,
            long amountMin,
            long amountMax,
            Instant from,
            Instant to,
            String excludeTransactionId,
            int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE tenant_id=:t AND rail=:r AND"
                                + " recon_status NOT IN ('MATCHED','PERIOD_LOCKED') AND"
                                + " amount_minor BETWEEN :min AND :max AND txn_time BETWEEN :from"
                                + " AND :to AND transaction_id <> :ex");
        if (terminalId != null) {
            sql.append(" AND terminal_id = :term");
        }
        if (merchantId != null) {
            sql.append(" AND merchant_id = :merch");
        }
        sql.append(" ORDER BY txn_time, transaction_id LIMIT :l");
        var q =
                jdbc.sql(sql.toString())
                        .param("t", tenantId)
                        .param("r", rail)
                        .param("min", amountMin)
                        .param("max", amountMax)
                        .param("from", from.atOffset(ZoneOffset.UTC))
                        .param("to", to.atOffset(ZoneOffset.UTC))
                        .param("ex", excludeTransactionId)
                        .param("l", limit);
        if (terminalId != null) {
            q = q.param("term", terminalId);
        }
        if (merchantId != null) {
            q = q.param("merch", merchantId);
        }
        return q.query(this::transaction).list();
    }

    /**
     * Persists a match group and flips its records to MATCHED. The UNIQUE key on allocations blocks
     * double matching.
     */
    public void saveGroup(MatchGroup group, String tenantId, String rail, String explanation) {
        jdbc.sql(
                        "INSERT INTO match_group (match_group_id, tenant_id, rail, tier, rule_name,"
                                + " rule_version, confidence, explanation, matched_at) VALUES"
                                + " (:id,:t,:r,:tier,:rn,:rv,:c,:x,:at)")
                .param("id", group.matchGroupId().value())
                .param("t", tenantId)
                .param("r", rail)
                .param("tier", group.tier().name())
                .param("rn", group.ruleName())
                .param("rv", group.ruleVersion())
                .param("c", group.confidenceScore())
                .param("x", explanation)
                .param("at", group.matchedAtUtc().atOffset(ZoneOffset.UTC))
                .update();
        for (Allocation a : group.allocations()) {
            jdbc.sql(
                            "INSERT INTO match_group_allocation (match_group_id, transaction_id,"
                                    + " side, amount_minor) VALUES (:g,:t,:s,:a)")
                    .param("g", group.matchGroupId().value())
                    .param("t", a.transactionId().value())
                    .param("s", a.side().name())
                    .param("a", a.allocatedAmount().amountMinor())
                    .update();
        }
        jdbc.sql(
                        "UPDATE canonical_transaction SET recon_status='MATCHED' WHERE"
                                + " transaction_id IN (:ids)")
                .param(
                        "ids",
                        group.allocations().stream().map(a -> a.transactionId().value()).toList())
                .update();
    }

    public List<String> matchKeysOf(Collection<String> transactionIds) {
        return jdbc.sql(
                        "SELECT DISTINCT match_key FROM canonical_transaction WHERE transaction_id"
                                + " IN (:ids)")
                .param("ids", transactionIds)
                .query(String.class)
                .list();
    }

    /**
     * @return break ids of clusters that had already raised a break (null-free), to emit late-match
     *     events
     */
    public List<String> markClustersMatched(
            String tenantId, String rail, Collection<String> matchKeys, Instant at) {
        List<String> breakIds =
                jdbc.sql(
                                "SELECT break_id FROM match_cluster WHERE tenant_id=:t AND rail=:r"
                                        + " AND match_key IN (:k) AND break_id IS NOT NULL")
                        .param("t", tenantId)
                        .param("r", rail)
                        .param("k", matchKeys)
                        .query(String.class)
                        .list();
        jdbc.sql(
                        "UPDATE match_cluster SET state='MATCHED', matched_at=:at WHERE"
                                + " tenant_id=:t AND rail=:r AND match_key IN (:k)")
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKeys)
                .update();
        return breakIds;
    }

    // ------------------------------------------------------------------ duplicates (EXC-06)

    /** Sources that already have a record in a match group for this reference. */
    public java.util.Set<String> matchedSources(String tenantId, String rail, String matchKey) {
        return new java.util.HashSet<>(
                jdbc.sql(
                                "SELECT DISTINCT source_id FROM canonical_transaction WHERE"
                                        + " tenant_id=:t AND rail=:r AND match_key=:k AND"
                                        + " recon_status='MATCHED'")
                        .param("t", tenantId)
                        .param("r", rail)
                        .param("k", matchKey)
                        .query(String.class)
                        .list());
    }

    /**
     * Clusters that were marked matched while a record of theirs is still unallocated (a second
     * delivery from one source). They go back to WAITING with an immediate deadline, so the sweeper
     * raises a DUPLICATE case instead of the extra record waiting forever.
     */
    public int reopenClustersWithLeftovers(
            String tenantId, String rail, Collection<String> matchKeys, Instant deadline) {
        if (matchKeys.isEmpty()) {
            return 0;
        }
        return jdbc.sql(
                        """
                        UPDATE match_cluster c SET state='WAITING', deadline_at=:d
                        WHERE c.tenant_id=:t AND c.rail=:r AND c.match_key IN (:k) AND c.state IN ('MATCHED','EXCEPTION')
                          AND EXISTS (SELECT 1 FROM canonical_transaction x
                                      WHERE x.tenant_id=c.tenant_id AND x.rail=c.rail AND x.match_key=c.match_key
                                        AND x.recon_status NOT IN ('MATCHED','PERIOD_LOCKED'))
                        """)
                .param("d", deadline.atOffset(ZoneOffset.UTC))
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKeys)
                .update();
    }

    // ------------------------------------------------------------------ ambiguity review (MAT-09)

    public record AmbiguitySummary(
            String ambiguityId,
            String rail,
            String status,
            String explanation,
            Instant createdAt,
            Instant resolvedAt,
            String resolvedBy,
            String resolution,
            String matchGroupId,
            int members) {}

    private static AmbiguitySummary ambiguity(ResultSet rs, int n) throws SQLException {
        OffsetDateTime resolved = rs.getObject(6, OffsetDateTime.class);
        return new AmbiguitySummary(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant(),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getInt(10));
    }

    private static final String AMBIGUITY_SELECT =
            "SELECT a.ambiguity_id, a.rail, a.status, a.explanation, a.created_at, a.resolved_at,"
                    + " a.resolved_by, a.resolution, a.match_group_id, (SELECT count(*) FROM"
                    + " match_ambiguity_member m WHERE m.ambiguity_id = a.ambiguity_id) FROM"
                    + " match_ambiguity a";

    /**
     * Open set that already contains one of these records, if any (sets are merged, not
     * duplicated).
     */
    public Optional<String> openAmbiguityContaining(
            String tenantId, Collection<String> transactionIds) {
        return jdbc.sql(
                        "SELECT a.ambiguity_id FROM match_ambiguity a JOIN match_ambiguity_member m"
                                + " ON m.ambiguity_id = a.ambiguity_id WHERE a.tenant_id=:t AND"
                                + " a.status='OPEN' AND m.transaction_id IN (:ids) ORDER BY"
                                + " a.created_at LIMIT 1")
                .param("t", tenantId)
                .param("ids", transactionIds)
                .query(String.class)
                .optional();
    }

    public void createAmbiguity(
            String id,
            String tenantId,
            String rail,
            String definitionId,
            String explanation,
            Instant at) {
        jdbc.sql(
                        "INSERT INTO match_ambiguity (ambiguity_id, tenant_id, rail, definition_id,"
                                + " explanation, created_at) VALUES (:id,:t,:r,:d,:x,:at)")
                .param("id", id)
                .param("t", tenantId)
                .param("r", rail)
                .param("d", definitionId)
                .param("x", explanation)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    public void addAmbiguityMembers(String ambiguityId, Collection<String> transactionIds) {
        for (String id : transactionIds) {
            jdbc.sql(
                            "INSERT INTO match_ambiguity_member (ambiguity_id, transaction_id)"
                                    + " VALUES (:a,:t) ON CONFLICT DO NOTHING")
                    .param("a", ambiguityId)
                    .param("t", id)
                    .update();
        }
    }

    /** Parks the clusters of an ambiguous set: no break is raised while a person decides. */
    public void markClustersAmbiguous(String tenantId, String rail, Collection<String> matchKeys) {
        jdbc.sql(
                        "UPDATE match_cluster SET state='AMBIGUOUS' WHERE tenant_id=:t AND rail=:r"
                                + " AND match_key IN (:k) AND state IN ('WAITING','EXCEPTION')")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKeys)
                .update();
    }

    public Optional<AmbiguitySummary> ambiguity(String tenantId, String ambiguityId) {
        return jdbc.sql(AMBIGUITY_SELECT + " WHERE a.tenant_id=:t AND a.ambiguity_id=:id")
                .param("t", tenantId)
                .param("id", ambiguityId)
                .query(MatchingRepository::ambiguity)
                .optional();
    }

    public List<AmbiguitySummary> ambiguities(String tenantId, String status, int limit) {
        return jdbc.sql(
                        AMBIGUITY_SELECT
                                + " WHERE a.tenant_id=:t AND (CAST(:s AS varchar) IS NULL OR"
                                + " a.status = CAST(:s AS varchar)) ORDER BY a.created_at DESC"
                                + " LIMIT :l")
                .param("t", tenantId)
                .param("s", status)
                .param("l", limit)
                .query(MatchingRepository::ambiguity)
                .list();
    }

    public long openAmbiguityCount(String tenantId, String rail) {
        return jdbc.sql(
                        "SELECT count(*) FROM match_ambiguity WHERE tenant_id=:t AND status='OPEN'"
                            + " AND (CAST(:r AS varchar) IS NULL OR rail = CAST(:r AS varchar))")
                .param("t", tenantId)
                .param("r", rail)
                .query(Long.class)
                .single();
    }

    public List<CanonicalTransaction> ambiguityMembers(String ambiguityId) {
        return jdbc.sql(
                        "SELECT "
                                + TXN_COLUMNS
                                + " FROM canonical_transaction WHERE transaction_id IN (SELECT"
                                + " transaction_id FROM match_ambiguity_member WHERE"
                                + " ambiguity_id=:a) ORDER BY source_id, txn_time, transaction_id")
                .param("a", ambiguityId)
                .query(this::transaction)
                .list();
    }

    public String ambiguityDefinition(String ambiguityId) {
        return jdbc.sql("SELECT definition_id FROM match_ambiguity WHERE ambiguity_id=:a")
                .param("a", ambiguityId)
                .query(String.class)
                .single();
    }

    /** Locks an open set for resolution so two reviewers cannot both resolve it. */
    public Optional<AmbiguitySummary> lockOpenAmbiguity(String tenantId, String ambiguityId) {
        return jdbc.sql(
                        AMBIGUITY_SELECT
                                + " WHERE a.tenant_id=:t AND a.ambiguity_id=:id AND a.status='OPEN'"
                                + " FOR UPDATE OF a")
                .param("t", tenantId)
                .param("id", ambiguityId)
                .query(MatchingRepository::ambiguity)
                .optional();
    }

    public void closeAmbiguity(
            String ambiguityId,
            String status,
            String actor,
            String resolution,
            String matchGroupId,
            Instant at) {
        jdbc.sql(
                        "UPDATE match_ambiguity SET status=:s, resolved_by=:by, resolution=:r,"
                                + " match_group_id=:g, resolved_at=:at WHERE ambiguity_id=:id")
                .param("s", status)
                .param("by", actor)
                .param("r", resolution)
                .param("g", matchGroupId)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("id", ambiguityId)
                .update();
    }

    /**
     * Open sets nobody looked at within the timeout: they become breaks rather than staying hidden.
     */
    public List<String> staleAmbiguities(Instant olderThan, int limit) {
        return jdbc.sql(
                        "SELECT ambiguity_id FROM match_ambiguity WHERE status='OPEN' AND"
                                + " created_at < :c ORDER BY created_at LIMIT :l FOR UPDATE SKIP"
                                + " LOCKED")
                .param("c", olderThan.atOffset(ZoneOffset.UTC))
                .param("l", limit)
                .query(String.class)
                .list();
    }

    public String ambiguityTenant(String ambiguityId) {
        return jdbc.sql("SELECT tenant_id FROM match_ambiguity WHERE ambiguity_id=:a")
                .param("a", ambiguityId)
                .query(String.class)
                .single();
    }

    public record GroupView(
            String matchGroupId,
            String tier,
            String ruleName,
            String ruleVersion,
            double confidence,
            String explanation,
            Instant matchedAt,
            List<String> transactionIds) {}

    public Optional<GroupView> groupOfTransaction(String tenantId, String transactionId) {
        Optional<String> groupId =
                jdbc.sql(
                                """
                                SELECT a.match_group_id FROM match_group_allocation a JOIN match_group g ON g.match_group_id = a.match_group_id
                                WHERE a.transaction_id = :id AND g.tenant_id = :t
                                """)
                        .param("id", transactionId)
                        .param("t", tenantId)
                        .query(String.class)
                        .optional();
        return groupId.flatMap(
                id ->
                        jdbc.sql(
                                        "SELECT match_group_id, tier, rule_name, rule_version,"
                                                + " confidence, explanation, matched_at FROM"
                                                + " match_group WHERE match_group_id=:id")
                                .param("id", id)
                                .query(
                                        (rs, n) ->
                                                new GroupView(
                                                        rs.getString(1),
                                                        rs.getString(2),
                                                        rs.getString(3),
                                                        rs.getString(4),
                                                        rs.getDouble(5),
                                                        rs.getString(6),
                                                        rs.getObject(7, OffsetDateTime.class)
                                                                .toInstant(),
                                                        jdbc.sql(
                                                                        "SELECT transaction_id FROM"
                                                                            + " match_group_allocation"
                                                                            + " WHERE"
                                                                            + " match_group_id=:g"
                                                                            + " ORDER BY"
                                                                            + " transaction_id")
                                                                .param("g", id)
                                                                .query(String.class)
                                                                .list()))
                                .optional());
    }

    public Optional<ClusterRow> cluster(String tenantId, String rail, String matchKey) {
        return jdbc.sql(
                        "SELECT tenant_id, rail, match_key, definition_id, state, deadline_at,"
                                + " break_id FROM match_cluster WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(MatchingRepository::cluster)
                .optional();
    }

    /** One reconciliation outcome per reference (S-12). */
    public record ResultRow(
            String rail,
            String matchKey,
            String state,
            Instant firstSeenAt,
            Instant deadlineAt,
            Instant matchedAt,
            String breakId,
            long records,
            long amountMinor,
            List<String> sources) {}

    public record ResultPage(List<ResultRow> items, long total) {}

    /**
     * The outcome of each reference, newest first. Every filter is optional; the match key filter
     * is a literal prefix (no wildcards).
     */
    public ResultPage results(
            String tenantId,
            String rail,
            String state,
            String source,
            String keyPrefix,
            Instant from,
            Instant to,
            int limit,
            int offset) {
        String where =
                " WHERE c.tenant_id=:t"
                        + (rail == null ? "" : " AND c.rail=:rail")
                        + (state == null ? "" : " AND c.state=:state")
                        + (keyPrefix == null ? "" : " AND starts_with(c.match_key, :prefix)")
                        + (from == null ? "" : " AND c.first_seen_at >= :from")
                        + (to == null ? "" : " AND c.first_seen_at < :to")
                        + (source == null
                                ? ""
                                : " AND EXISTS (SELECT 1 FROM canonical_transaction x WHERE"
                                        + " x.tenant_id=c.tenant_id AND x.rail=c.rail AND"
                                        + " x.match_key=c.match_key AND x.source_id=:source)");
        java.util.function.Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind =
                spec -> {
                    spec = spec.param("t", tenantId);
                    if (rail != null) spec = spec.param("rail", rail);
                    if (state != null) spec = spec.param("state", state);
                    if (keyPrefix != null) spec = spec.param("prefix", keyPrefix);
                    if (from != null) spec = spec.param("from", from.atOffset(ZoneOffset.UTC));
                    if (to != null) spec = spec.param("to", to.atOffset(ZoneOffset.UTC));
                    if (source != null) spec = spec.param("source", source);
                    return spec;
                };
        long total =
                bind.apply(jdbc.sql("SELECT count(*) FROM match_cluster c" + where))
                        .query(Long.class)
                        .single();
        List<ResultRow> items =
                bind.apply(
                                jdbc.sql(
                                        "SELECT c.rail, c.match_key, c.state, c.first_seen_at,"
                                                + " c.deadline_at, c.matched_at, c.break_id,"
                                                + " (SELECT count(*) FROM canonical_transaction t"
                                                + "   WHERE t.tenant_id=c.tenant_id AND"
                                                + " t.rail=c.rail AND t.match_key=c.match_key),"
                                                + " (SELECT coalesce(max(t.amount_minor),0) FROM"
                                                + " canonical_transaction t   WHERE"
                                                + " t.tenant_id=c.tenant_id AND t.rail=c.rail AND"
                                                + " t.match_key=c.match_key),"
                                                + " (SELECT string_agg(DISTINCT t.source_id, ',')"
                                                + " FROM canonical_transaction t   WHERE"
                                                + " t.tenant_id=c.tenant_id AND t.rail=c.rail AND"
                                                + " t.match_key=c.match_key)"
                                                + " FROM match_cluster c"
                                                + where
                                                + " ORDER BY c.first_seen_at DESC, c.match_key"
                                                + " LIMIT :limit OFFSET :offset"))
                        .param("limit", limit)
                        .param("offset", offset)
                        .query(
                                (rs, n) -> {
                                    String srcs = rs.getString(10);
                                    return new ResultRow(
                                            rs.getString(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getObject(4, OffsetDateTime.class).toInstant(),
                                            rs.getObject(5, OffsetDateTime.class).toInstant(),
                                            rs.getObject(6, OffsetDateTime.class) == null
                                                    ? null
                                                    : rs.getObject(6, OffsetDateTime.class)
                                                            .toInstant(),
                                            rs.getString(7),
                                            rs.getLong(8),
                                            rs.getLong(9),
                                            srcs == null ? List.of() : List.of(srcs.split(",")));
                                })
                        .list();
        return new ResultPage(items, total);
    }

    public record Stats(
            long matchedGroups,
            long waitingClusters,
            long exceptionClusters,
            long ambiguousClusters) {}

    /**
     * Counts for a run. With a {@code definitionId} everything is scoped to the records reconciled
     * under that definition (what the Workbench shows for it), not the window or other definitions;
     * without one it is the tenant-wide view of the window.
     */
    public Stats stats(
            String tenantId, Instant from, Instant to, String definitionId, Instant since) {
        if (definitionId != null && !definitionId.isBlank()) {
            return new Stats(
                    clusterCount(tenantId, definitionId, "MATCHED", since),
                    clusterCount(tenantId, definitionId, "WAITING", since),
                    clusterCount(tenantId, definitionId, "EXCEPTION", since),
                    clusterCount(tenantId, definitionId, "AMBIGUOUS", since));
        }
        long groups =
                jdbc.sql(
                                "SELECT count(*) FROM match_group WHERE tenant_id=:t AND matched_at"
                                        + " >= :f AND matched_at < :to")
                        .param("t", tenantId)
                        .param("f", from.atOffset(ZoneOffset.UTC))
                        .param("to", to.atOffset(ZoneOffset.UTC))
                        .query(Long.class)
                        .single();
        long waiting =
                jdbc.sql(
                                "SELECT count(*) FROM match_cluster WHERE tenant_id=:t AND"
                                        + " state='WAITING'")
                        .param("t", tenantId)
                        .query(Long.class)
                        .single();
        long exceptions =
                jdbc.sql(
                                "SELECT count(*) FROM match_cluster WHERE tenant_id=:t AND"
                                        + " state='EXCEPTION'")
                        .param("t", tenantId)
                        .query(Long.class)
                        .single();
        long ambiguous =
                jdbc.sql(
                                "SELECT count(*) FROM match_cluster WHERE tenant_id=:t AND"
                                        + " state='AMBIGUOUS'")
                        .param("t", tenantId)
                        .query(Long.class)
                        .single();
        return new Stats(groups, waiting, exceptions, ambiguous);
    }

    /**
     * Clusters of one definition in a state. With {@code since} only clusters first seen from then
     * on, so a run reports its own data rather than everything the definition ever reconciled.
     */
    private long clusterCount(String tenantId, String definitionId, String state, Instant since) {
        return jdbc.sql(
                        "SELECT count(*) FROM match_cluster WHERE tenant_id=:t AND"
                                + " definition_id=:d AND state=:s AND (CAST(:since AS timestamptz)"
                                + " IS NULL OR first_seen_at >= CAST(:since AS timestamptz))")
                .param("t", tenantId)
                .param("d", definitionId)
                .param("s", state)
                .param("since", since == null ? null : since.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------ replacing an uploaded file

    /** One record of an uploaded file, as matching holds it. */
    public record FileRecord(
            String rail, String matchKey, String reconStatus, Instant transactionTime) {}

    /** The records of one uploaded file: its source and its stored copy (vault location). */
    private static final String OF_FILE =
            "SELECT transaction_id FROM canonical_transaction WHERE tenant_id=:t AND source_id=:s"
                    + " AND vault_location=:v";

    public List<FileRecord> recordsOfFile(String tenantId, String sourceId, String vaultLocation) {
        return jdbc.sql(
                        "SELECT rail, match_key, recon_status, txn_time FROM canonical_transaction"
                                + " WHERE tenant_id=:t AND source_id=:s AND vault_location=:v")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", vaultLocation)
                .query(
                        (rs, n) ->
                                new FileRecord(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Settlement records (expected/actual) that came from the file. */
    public long settlementRecordsOfFile(String tenantId, String sourceId, String vaultLocation) {
        return jdbc.sql(
                        "SELECT count(*) FROM settlement_record WHERE tenant_id=:t AND source_id=:s"
                                + " AND vault_location=:v")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", vaultLocation)
                .query(Long.class)
                .single();
    }

    /** Open ambiguous matches a person still has to decide that include a record of the file. */
    public long openAmbiguitiesOfFile(String tenantId, String sourceId, String vaultLocation) {
        return jdbc.sql(
                        "SELECT count(DISTINCT a.ambiguity_id) FROM match_ambiguity a JOIN"
                            + " match_ambiguity_member m ON m.ambiguity_id = a.ambiguity_id WHERE"
                            + " a.tenant_id=:t AND a.status='OPEN' AND m.transaction_id IN ("
                                + OF_FILE
                                + ")")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", vaultLocation)
                .query(Long.class)
                .single();
    }

    /**
     * Takes the file's records out of matching: every match one of them is part of is undone (the
     * match's other records wait again) and the records are deleted. The caller then re-evaluates
     * the affected references. Must run inside a transaction.
     *
     * @return how many records were withdrawn
     */
    public int withdrawFile(String tenantId, String sourceId, String vaultLocation) {
        List<String> groups =
                jdbc.sql(
                                "SELECT DISTINCT match_group_id FROM match_group_allocation WHERE"
                                        + " transaction_id IN ("
                                        + OF_FILE
                                        + ")")
                        .param("t", tenantId)
                        .param("s", sourceId)
                        .param("v", vaultLocation)
                        .query(String.class)
                        .list();
        for (int i = 0; i < groups.size(); i += 500) {
            List<String> chunk = groups.subList(i, Math.min(groups.size(), i + 500));
            jdbc.sql(
                            "UPDATE canonical_transaction SET recon_status='WAITING' WHERE"
                                    + " recon_status='MATCHED' AND transaction_id IN (SELECT"
                                    + " transaction_id FROM match_group_allocation WHERE"
                                    + " match_group_id IN (:g))")
                    .param("g", chunk)
                    .update();
            jdbc.sql("DELETE FROM match_group_allocation WHERE match_group_id IN (:g)")
                    .param("g", chunk)
                    .update();
            jdbc.sql("DELETE FROM match_group WHERE match_group_id IN (:g)")
                    .param("g", chunk)
                    .update();
        }
        jdbc.sql("DELETE FROM match_ambiguity_member WHERE transaction_id IN (" + OF_FILE + ")")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", vaultLocation)
                .update();
        return jdbc.sql(
                        "DELETE FROM canonical_transaction WHERE tenant_id=:t AND source_id=:s AND"
                                + " vault_location=:v")
                .param("t", tenantId)
                .param("s", sourceId)
                .param("v", vaultLocation)
                .update();
    }

    /** Records left for a reference, of any status. */
    public long recordsWithKey(String tenantId, String rail, String matchKey) {
        return jdbc.sql(
                        "SELECT count(*) FROM canonical_transaction WHERE tenant_id=:t AND rail=:r"
                                + " AND match_key=:k")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(Long.class)
                .single();
    }

    /** A reference with no records left is forgotten, so its next record starts it afresh. */
    public void dropCluster(String tenantId, String rail, String matchKey) {
        jdbc.sql("DELETE FROM match_cluster WHERE tenant_id=:t AND rail=:r AND match_key=:k")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .update();
    }

    /**
     * The reference is decided again from scratch under the current definition: it waits for its
     * window with no break, and its unmatched records wait with it.
     */
    public void reopenForReevaluation(
            String tenantId, String rail, String matchKey, String definitionId, Instant deadline) {
        jdbc.sql(
                        "UPDATE match_cluster SET state='WAITING', break_id=NULL, matched_at=NULL,"
                            + " definition_id=:d, deadline_at=:dl WHERE tenant_id=:t AND rail=:r"
                            + " AND match_key=:k")
                .param("d", definitionId)
                .param("dl", deadline.atOffset(ZoneOffset.UTC))
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .update();
        jdbc.sql(
                        "UPDATE canonical_transaction SET recon_status='WAITING' WHERE tenant_id=:t"
                                + " AND rail=:r AND match_key=:k AND recon_status NOT IN"
                                + " ('MATCHED','PERIOD_LOCKED')")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .update();
    }

    private static ClusterRow cluster(ResultSet rs, int n) throws SQLException {
        return new ClusterRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getObject(6, OffsetDateTime.class).toInstant(),
                rs.getString(7));
    }

    private CanonicalTransaction transaction(ResultSet rs, int n) throws SQLException {
        Instant time = rs.getObject(10, OffsetDateTime.class).toInstant();
        LocalDate date = LocalDate.ofInstant(time, sourceZone);
        return CanonicalTransaction.builder()
                .transactionId(TypedId.of(rs.getString(1)))
                .tenantId(TypedId.of(rs.getString(2)))
                .rail(Rail.valueOf(rs.getString(3)))
                .sourceId(rs.getString(4))
                .feedId(rs.getString(5))
                .recordHash(rs.getString(6))
                .direction(Direction.valueOf(rs.getString(7)))
                .amount(new Money(rs.getLong(8), rs.getString(9).trim()))
                .transactionTimeUtc(time)
                .valueDate(
                        rs.getObject(20, LocalDate.class) == null
                                ? date
                                : rs.getObject(20, LocalDate.class))
                .postingDate(
                        rs.getObject(21, LocalDate.class) == null
                                ? date
                                : rs.getObject(21, LocalDate.class))
                .settlementDate(Optional.ofNullable(rs.getObject(22, LocalDate.class)))
                .referenceKeys(
                        new ReferenceKeys(
                                Optional.ofNullable(rs.getString(11)),
                                Optional.ofNullable(rs.getString(12)),
                                Optional.ofNullable(rs.getString(23)),
                                Optional.ofNullable(rs.getString(24)),
                                Optional.ofNullable(rs.getString(25)),
                                Optional.ofNullable(rs.getString(26))))
                .terminalId(Optional.ofNullable(rs.getString(13)))
                .merchantId(Optional.ofNullable(rs.getString(14)))
                .channel(rs.getString(27) == null ? rs.getString(4) : rs.getString(27))
                .settlementStatus(
                        rs.getString(28) == null
                                ? com.eternax.recon.domain.SettlementStatus.NOT_APPLICABLE
                                : com.eternax.recon.domain.SettlementStatus.valueOf(
                                        rs.getString(28)))
                .parentTransactionId(
                        Optional.ofNullable(rs.getString(29))
                                .map(com.eternax.recon.common.TypedId::of))
                .transactionStatus(TransactionStatus.valueOf(rs.getString(15)))
                .accountReferenceToken(rs.getString(16))
                .rawRecordPointer(new RawRecordPointer(rs.getString(17), rs.getString(18)))
                .reconciliationStatus(ReconciliationStatus.valueOf(rs.getString(19)))
                .build();
    }
}
