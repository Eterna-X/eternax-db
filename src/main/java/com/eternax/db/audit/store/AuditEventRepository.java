package com.eternax.db.audit.store;

import com.eternax.recon.events.AuditEventMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The append-only audit log and the per-tenant head of its hash chain (HLD FR-100 to FR-104). The
 * chain rules (fingerprints, hashes, verification) live in audit-service; this only stores and
 * reads.
 */
@Repository
public class AuditEventRepository {

    /** Last link of a tenant's chain. */
    public record ChainHead(long lastSeq, String lastHash) {}

    /** How many events a tenant has, how many are older than {@code cutoff}, and the oldest. */
    public record RetentionStats(long total, long olderThanCutoff, Instant oldest) {}

    private final JdbcClient jdbc;

    public AuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RetentionStats retentionStats(String tenantId, Instant cutoff) {
        Map<String, Object> row =
                jdbc.sql(
                                "SELECT count(*) AS total, count(*) FILTER (WHERE at_utc < :c) AS"
                                        + " old, min(at_utc) AS oldest FROM audit_event WHERE"
                                        + " tenant_id=:t")
                        .param("t", tenantId)
                        .param("c", cutoff.atOffset(ZoneOffset.UTC))
                        .query()
                        .singleRow();
        Object oldest = row.get("oldest");
        return new RetentionStats(
                ((Number) row.get("total")).longValue(),
                ((Number) row.get("old")).longValue(),
                oldest == null ? null : ((java.sql.Timestamp) oldest).toInstant());
    }

    public boolean existsFingerprint(String tenantId, String fingerprint) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM audit_event WHERE tenant_id=:t AND"
                                + " fingerprint=:f)")
                .param("t", tenantId)
                .param("f", fingerprint)
                .query(Boolean.class)
                .single();
    }

    /**
     * Creates the tenant's chain head at {@code genesis} if it has none yet, then locks it for the
     * rest of the transaction: the head row is the per-tenant serialisation point, whoever holds it
     * appends the next link. Must run inside a transaction.
     */
    public ChainHead lockChainHead(String tenantId, String genesis) {
        jdbc.sql(
                        "INSERT INTO audit_chain_head (tenant_id, last_seq, last_hash) VALUES (:t,"
                                + " 0, :g) ON CONFLICT (tenant_id) DO NOTHING")
                .param("t", tenantId)
                .param("g", genesis)
                .update();
        Map<String, Object> head =
                jdbc.sql(
                                "SELECT last_seq, last_hash FROM audit_chain_head WHERE"
                                        + " tenant_id=:t FOR UPDATE")
                        .param("t", tenantId)
                        .query()
                        .singleRow();
        return new ChainHead(
                ((Number) head.get("last_seq")).longValue(), (String) head.get("last_hash"));
    }

    /** Stores the event as link {@code seq} and moves the chain head to it. */
    public void append(
            AuditEventMessage m, long seq, String fingerprint, String previousHash, String hash) {
        jdbc.sql(
                        """
                        INSERT INTO audit_event (tenant_id, seq_no, fingerprint, actor, action, entity_type, entity_id, before_json,
                            after_json, correlation_id, source_service, at_utc, previous_hash, event_hash)
                        VALUES (:t,:seq,:fp,:actor,:action,:et,:eid,:b,:a,:cid,:svc,:at,:prev,:hash)
                        """)
                .param("t", m.tenantId())
                .param("seq", seq)
                .param("fp", fingerprint)
                .param("actor", m.actor())
                .param("action", m.action())
                .param("et", m.entityType())
                .param("eid", m.entityId())
                .param("b", m.beforeStateJson())
                .param("a", m.afterStateJson())
                .param("cid", m.correlationId())
                .param("svc", m.sourceService())
                .param("at", m.atUtc().atOffset(ZoneOffset.UTC))
                .param("prev", previousHash)
                .param("hash", hash)
                .update();
        jdbc.sql("UPDATE audit_chain_head SET last_seq=:s, last_hash=:h WHERE tenant_id=:t")
                .param("s", seq)
                .param("h", hash)
                .param("t", m.tenantId())
                .update();
    }

    public List<AuditRecord> search(String tenantId, AuditSearchCriteria c) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE tenant_id = :t AND seq_no > :after");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        params.put("after", c.afterSeq());
        c.actor()
                .ifPresent(
                        v -> {
                            sql.append(" AND actor = :actor");
                            params.put("actor", v);
                        });
        c.action()
                .ifPresent(
                        v -> {
                            sql.append(" AND action = :action");
                            params.put("action", v);
                        });
        c.entityType()
                .ifPresent(
                        v -> {
                            sql.append(" AND entity_type = :et");
                            params.put("et", v);
                        });
        c.entityId()
                .ifPresent(
                        v -> {
                            sql.append(" AND entity_id = :eid");
                            params.put("eid", v);
                        });
        c.from()
                .ifPresent(
                        v -> {
                            sql.append(" AND at_utc >= :from");
                            params.put("from", v.atOffset(ZoneOffset.UTC));
                        });
        c.to().ifPresent(
                        v -> {
                            sql.append(" AND at_utc < :to");
                            params.put("to", v.atOffset(ZoneOffset.UTC));
                        });
        sql.append(" ORDER BY seq_no LIMIT :limit");
        params.put("limit", c.limit());
        return jdbc.sql(sql.toString()).params(params).query(AuditEventRepository::map).list();
    }

    /** Up to {@code limit} events after {@code afterSeq}, in chain order. */
    public List<AuditRecord> page(String tenantId, long afterSeq, int limit) {
        return jdbc.sql(SELECT + " WHERE tenant_id=:t AND seq_no > :after ORDER BY seq_no LIMIT :l")
                .param("t", tenantId)
                .param("after", afterSeq)
                .param("l", limit)
                .query(AuditEventRepository::map)
                .list();
    }

    public String fingerprint(String tenantId, long seq) {
        return jdbc.sql("SELECT fingerprint FROM audit_event WHERE tenant_id=:t AND seq_no=:s")
                .param("t", tenantId)
                .param("s", seq)
                .query(String.class)
                .single();
    }

    public Optional<ChainHead> chainHead(String tenantId) {
        return jdbc
                .sql("SELECT last_seq, last_hash FROM audit_chain_head WHERE tenant_id=:t")
                .param("t", tenantId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .map(
                        row ->
                                new ChainHead(
                                        ((Number) row.get("last_seq")).longValue(),
                                        (String) row.get("last_hash")));
    }

    public List<String> tenants() {
        return jdbc.sql("SELECT tenant_id FROM audit_chain_head ORDER BY tenant_id")
                .query(String.class)
                .list();
    }

    private static final String SELECT =
            "SELECT seq_no, tenant_id, actor, action, entity_type, entity_id, before_json,"
                + " after_json, correlation_id, source_service, at_utc, previous_hash, event_hash"
                + " FROM audit_event";

    private static AuditRecord map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AuditRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                rs.getObject(11, OffsetDateTime.class).toInstant(),
                rs.getString(12),
                rs.getString(13));
    }
}
