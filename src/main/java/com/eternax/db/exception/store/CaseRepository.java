package com.eternax.db.exception.store;

import com.eternax.recon.common.Money;
import com.eternax.recon.common.TypedId;
import com.eternax.recon.domain.CaseHistoryEntry;
import com.eternax.recon.domain.CaseState;
import com.eternax.recon.domain.ReasonCode;
import com.eternax.recon.domain.ReconciliationCase;
import com.eternax.recon.exception.EntityNotFoundException;
import com.eternax.recon.exception.OptimisticLockConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence port for cases. Updates are guarded by the version column (optimistic locking). */
@Repository
public class CaseRepository {

    private static final String COLUMNS =
            "case_id, tenant_id, break_id, rail, match_key, reason_code, detail_code, explanation,"
                + " state, financial_impact_minor, currency, transaction_time, detected_at,"
                + " deadline_at, assigned_owner, involved_transactions, closure_reason, version,"
                + " priority_override, escalated, escalated_to";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CaseRecord record) {
        ReconciliationCase c = record.domain();
        jdbc.sql(
                        """
                        INSERT INTO reconciliation_case (case_id, tenant_id, break_id, rail, match_key, reason_code, detail_code,
                            explanation, state, financial_impact_minor, currency, transaction_time, detected_at, deadline_at,
                            assigned_owner, involved_transactions, closure_reason)
                        VALUES (:id,:t,:b,:rail,:mk,:rc,:dc,:ex,:st,:amt,:cur,:tt,:det,:dl,:own,:txns,:cr)
                        """)
                .param("id", c.caseId().value())
                .param("t", c.tenantId().value())
                .param("b", c.breakId())
                .param("rail", record.rail())
                .param("mk", record.matchKey())
                .param("rc", c.reasonCode().name())
                .param("dc", record.detailCode())
                .param("ex", record.explanation())
                .param("st", c.state().name())
                .param("amt", c.financialImpact().amountMinor())
                .param("cur", c.financialImpact().currencyCode())
                .param("tt", record.transactionTime().atOffset(ZoneOffset.UTC))
                .param("det", c.detectedAtUtc().atOffset(ZoneOffset.UTC))
                .param("dl", c.deadlineUtc().map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null))
                .param("own", c.assignedOwner().orElse(null))
                .param(
                        "txns",
                        String.join(
                                ",",
                                c.involvedTransactionIds().stream().map(TypedId::value).toList()))
                .param("cr", record.closureReason())
                .update();
        appendHistory(c.caseId().value(), c.history(), 0);
    }

    /**
     * @throws OptimisticLockConflictException if the case changed since it was read
     */
    public void update(CaseRecord record) {
        ReconciliationCase c = record.domain();
        int rows =
                jdbc.sql(
                                """
                                UPDATE reconciliation_case SET state=:st, assigned_owner=:own, closure_reason=:cr,
                                       priority_override=:po, escalated=:esc, escalated_to=:escto,
                                       escalated_at = CASE WHEN :esc AND NOT escalated THEN now() ELSE escalated_at END,
                                       version = version + 1, updated_at = now()
                                WHERE case_id=:id AND tenant_id=:t AND version=:v
                                """)
                        .param("st", c.state().name())
                        .param("own", c.assignedOwner().orElse(null))
                        .param("cr", record.closureReason())
                        .param("po", record.priorityOverride())
                        .param("esc", record.escalated())
                        .param("escto", record.escalatedTo())
                        .param("id", c.caseId().value())
                        .param("t", c.tenantId().value())
                        .param("v", c.version())
                        .update();
        if (rows == 0) {
            throw new OptimisticLockConflictException(
                    "ReconciliationCase", c.caseId().value(), c.version());
        }
        int persisted =
                jdbc.sql("SELECT count(*) FROM case_history WHERE case_id=:id")
                        .param("id", c.caseId().value())
                        .query(Integer.class)
                        .single();
        appendHistory(c.caseId().value(), c.history(), persisted);
    }

    private void appendHistory(
            String caseId, List<CaseHistoryEntry> history, int alreadyPersisted) {
        for (int i = alreadyPersisted; i < history.size(); i++) {
            CaseHistoryEntry e = history.get(i);
            jdbc.sql(
                            "INSERT INTO case_history (case_id, state, at_utc, actor, reason)"
                                    + " VALUES (:c,:s,:a,:u,:r)")
                    .param("c", caseId)
                    .param("s", e.state().name())
                    .param("a", e.atUtc().atOffset(ZoneOffset.UTC))
                    .param("u", e.actor())
                    .param("r", e.reason())
                    .update();
        }
    }

    public CaseRecord get(String tenantId, String caseId) {
        return find(tenantId, caseId)
                .orElseThrow(() -> new EntityNotFoundException("Case", caseId));
    }

    public Optional<CaseRecord> find(String tenantId, String caseId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_case WHERE tenant_id=:t AND case_id=:id")
                .param("t", tenantId)
                .param("id", caseId)
                .query(this::map)
                .optional();
    }

    /** Cases for one reference that are not closed yet (normally none or one). */
    public List<CaseRecord> openByMatchKey(String tenantId, String rail, String matchKey) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_case WHERE tenant_id=:t AND rail=:r AND"
                                + " match_key=:k AND state <> 'CLOSED'")
                .param("t", tenantId)
                .param("r", rail)
                .param("k", matchKey)
                .query(this::map)
                .list();
    }

    public Optional<CaseRecord> findByBreakId(String tenantId, String breakId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM reconciliation_case WHERE tenant_id=:t AND break_id=:b")
                .param("t", tenantId)
                .param("b", breakId)
                .query(this::map)
                .optional();
    }

    public List<CaseRecord> query(String tenantId, CaseQueryFilter f) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT " + COLUMNS + " FROM reconciliation_case WHERE tenant_id = :t");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        f.state()
                .ifPresent(
                        s -> {
                            sql.append(" AND state = :state");
                            params.put("state", s.name());
                        });
        f.reasonCode()
                .ifPresent(
                        r -> {
                            sql.append(" AND reason_code = :rc");
                            params.put("rc", r.name());
                        });
        f.owner()
                .ifPresent(
                        o -> {
                            sql.append(" AND assigned_owner = :owner");
                            params.put("owner", o);
                        });
        f.rail()
                .ifPresent(
                        r -> {
                            sql.append(" AND rail = :rail");
                            params.put("rail", r);
                        });
        if (f.openOnly()) {
            sql.append(" AND state <> 'CLOSED'");
        }
        f.text()
                .filter(t -> !t.isBlank())
                .ifPresent(
                        t -> {
                            sql.append(
                                    " AND (case_id ILIKE :q ESCAPE '\\' OR break_id ILIKE :q ESCAPE"
                                        + " '\\' OR match_key ILIKE :q ESCAPE '\\' OR explanation"
                                        + " ILIKE :q ESCAPE '\\' OR detail_code ILIKE :q ESCAPE"
                                        + " '\\' OR assigned_owner ILIKE :q ESCAPE '\\')");
                            params.put(
                                    "q",
                                    "%"
                                            + t.trim()
                                                    .replace("\\", "\\\\")
                                                    .replace("%", "\\%")
                                                    .replace("_", "\\_")
                                            + "%");
                        });
        if (f.cursorDetectedAt().isPresent() && f.cursorCaseId().isPresent()) {
            sql.append(" AND (detected_at, case_id) < (:cd, :cid)");
            params.put("cd", f.cursorDetectedAt().get().atOffset(ZoneOffset.UTC));
            params.put("cid", f.cursorCaseId().get());
        }
        sql.append(" ORDER BY detected_at DESC, case_id DESC LIMIT :limit");
        params.put("limit", f.limit());
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    public List<CaseHistoryEntry> history(String caseId) {
        return jdbc.sql(
                        "SELECT state, at_utc, actor, reason FROM case_history WHERE case_id=:id"
                                + " ORDER BY id")
                .param("id", caseId)
                .query(
                        (rs, n) ->
                                new CaseHistoryEntry(
                                        CaseState.valueOf(rs.getString(1)),
                                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                                        rs.getString(3),
                                        rs.getString(4)))
                .list();
    }

    public record Comment(long id, String author, String body, Instant at) {}

    public void addComment(String tenantId, String caseId, String author, String body) {
        jdbc.sql("INSERT INTO case_comment (case_id, tenant_id, author, body) VALUES (:c,:t,:a,:b)")
                .param("c", caseId)
                .param("t", tenantId)
                .param("a", author)
                .param("b", body)
                .update();
    }

    public List<Comment> comments(String tenantId, String caseId) {
        return jdbc.sql(
                        "SELECT id, author, body, at_utc FROM case_comment WHERE tenant_id=:t AND"
                                + " case_id=:c ORDER BY id")
                .param("t", tenantId)
                .param("c", caseId)
                .query(
                        (rs, n) ->
                                new Comment(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
    }

    public record AlertCandidate(
            String caseId,
            String tenantId,
            String rail,
            Instant transactionTime,
            boolean sent70,
            boolean sent90) {}

    /** Claims open cases that still owe an alert; safe under concurrent replicas. */
    public List<AlertCandidate> alertCandidates(int limit) {
        return jdbc.sql(
                        """
                        SELECT case_id, tenant_id, rail, transaction_time, alert_70_sent, alert_90_sent FROM reconciliation_case
                        WHERE state <> 'CLOSED' AND deadline_at IS NOT NULL AND NOT alert_90_sent
                        ORDER BY deadline_at LIMIT :l FOR UPDATE SKIP LOCKED
                        """)
                .param("l", limit)
                .query(
                        (rs, n) ->
                                new AlertCandidate(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, OffsetDateTime.class).toInstant(),
                                        rs.getBoolean(5),
                                        rs.getBoolean(6)))
                .list();
    }

    public void markAlert(String caseId, int level) {
        String column = level >= 90 ? "alert_90_sent" : "alert_70_sent";
        jdbc.sql("UPDATE reconciliation_case SET " + column + " = TRUE WHERE case_id = :id")
                .param("id", caseId)
                .update();
    }

    public record ExposureRow(
            String rail, long openCases, long openValueMinor, long compensationExposureMinor) {}

    /**
     * Open-case value and accrued RBI compensation per rail, computed in SQL from the clock table.
     */
    public List<ExposureRow> exposureByRail(String tenantId, LocalDate todayIst) {
        return jdbc.sql(
                        """
                        SELECT c.rail, count(*), COALESCE(sum(c.financial_impact_minor), 0),
                               COALESCE(sum(COALESCE(tt.compensation_per_day_minor, td.compensation_per_day_minor, 0)
                                 * GREATEST(0, (CAST(:today AS date) - CAST(c.transaction_time AT TIME ZONE 'Asia/Kolkata' AS date))
                                               - COALESCE(tt.deadline_days, td.deadline_days, 2147483647))), 0)
                        FROM reconciliation_case c
                        LEFT JOIN tat_rail_clock tt ON tt.rail = c.rail AND tt.tenant_id = c.tenant_id
                        LEFT JOIN tat_rail_clock td ON td.rail = c.rail AND td.tenant_id = '*'
                        WHERE c.tenant_id = :t AND c.state <> 'CLOSED'
                        GROUP BY c.rail ORDER BY c.rail
                        """)
                .param("t", tenantId)
                .param("today", todayIst)
                .query(
                        (rs, n) ->
                                new ExposureRow(
                                        rs.getString(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        rs.getLong(4)))
                .list();
    }

    public record Bucket(String key, long cases, long valueMinor) {}

    public record Summary(
            long openCases,
            long openValueMinor,
            long closedCases,
            long overdueCases,
            long escalatedCases,
            List<Bucket> byState,
            List<Bucket> byReason,
            List<Bucket> byOwner,
            List<Bucket> aging) {}

    private List<Bucket> buckets(String sql, String tenantId, String rail) {
        return jdbc.sql(sql)
                .param("t", tenantId)
                .param("rail", rail)
                .query((rs, n) -> new Bucket(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                .list();
    }

    /**
     * Exception dashboard figures (RPT-01): volume and value of open cases by state, reason and
     * owner, and how long they have been open. {@code rail} null means all rails.
     */
    public Summary summary(String tenantId, String rail) {
        String scope =
                " FROM reconciliation_case WHERE tenant_id = :t AND (CAST(:rail AS varchar) IS NULL"
                        + " OR rail = CAST(:rail AS varchar))";
        long[] head =
                jdbc.sql(
                                "SELECT count(*) FILTER (WHERE state <> 'CLOSED'),"
                                    + " COALESCE(sum(financial_impact_minor) FILTER (WHERE state <>"
                                    + " 'CLOSED'), 0), count(*) FILTER (WHERE state = 'CLOSED'),"
                                    + " count(*) FILTER (WHERE state <> 'CLOSED' AND deadline_at IS"
                                    + " NOT NULL AND deadline_at < now()), count(*) FILTER (WHERE"
                                    + " state <> 'CLOSED' AND escalated)"
                                        + scope)
                        .param("t", tenantId)
                        .param("rail", rail)
                        .query(
                                (rs, n) ->
                                        new long[] {
                                            rs.getLong(1),
                                            rs.getLong(2),
                                            rs.getLong(3),
                                            rs.getLong(4),
                                            rs.getLong(5)
                                        })
                        .single();
        return new Summary(
                head[0],
                head[1],
                head[2],
                head[3],
                head[4],
                buckets(
                        "SELECT state, count(*), COALESCE(sum(financial_impact_minor),0)"
                                + scope
                                + " AND state <> 'CLOSED' GROUP BY state ORDER BY state",
                        tenantId,
                        rail),
                buckets(
                        "SELECT reason_code, count(*), COALESCE(sum(financial_impact_minor),0)"
                                + scope
                                + " AND state <> 'CLOSED' GROUP BY reason_code ORDER BY 3 DESC",
                        tenantId,
                        rail),
                buckets(
                        "SELECT COALESCE(assigned_owner, 'unassigned'), count(*),"
                                + " COALESCE(sum(financial_impact_minor),0)"
                                + scope
                                + " AND state <> 'CLOSED' GROUP BY 1 ORDER BY 3 DESC LIMIT 20",
                        tenantId,
                        rail),
                buckets(
                        "SELECT CASE WHEN now() - detected_at < interval '1 day' THEN '0-1d' WHEN"
                            + " now() - detected_at < interval '3 days' THEN '1-3d' WHEN now() -"
                            + " detected_at < interval '7 days' THEN '3-7d' ELSE '7d+' END AS b,"
                            + " count(*), COALESCE(sum(financial_impact_minor),0)"
                                + scope
                                + " AND state <> 'CLOSED' GROUP BY 1 ORDER BY 1",
                        tenantId,
                        rail));
    }

    private CaseRecord map(ResultSet rs, int n) throws SQLException {
        String caseId = rs.getString(1);
        OffsetDateTime deadline = rs.getObject(14, OffsetDateTime.class);
        List<TypedId<com.eternax.recon.common.Markers.TransactionMarker>> txns = new ArrayList<>();
        Arrays.stream(rs.getString(16).split(","))
                .filter(s -> !s.isBlank())
                .forEach(s -> txns.add(TypedId.of(s)));
        ReconciliationCase domain =
                ReconciliationCase.restore(
                        TypedId.of(caseId),
                        TypedId.of(rs.getString(2)),
                        rs.getString(3),
                        txns,
                        ReasonCode.valueOf(rs.getString(6)),
                        new Money(rs.getLong(10), rs.getString(11).trim()),
                        rs.getObject(13, OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(deadline).map(OffsetDateTime::toInstant),
                        CaseState.valueOf(rs.getString(9)),
                        Optional.ofNullable(rs.getString(15)),
                        history(caseId),
                        rs.getLong(18));
        return new CaseRecord(
                domain,
                rs.getString(4),
                rs.getString(5),
                rs.getString(7),
                rs.getString(8),
                rs.getObject(12, OffsetDateTime.class).toInstant(),
                rs.getString(17),
                rs.getString(19),
                rs.getBoolean(20),
                rs.getString(21));
    }

    public record Attachment(
            String attachmentId,
            String caseId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            String description,
            String uploadedBy,
            Instant uploadedAt) {}

    public record AttachmentContent(Attachment meta, byte[] content) {}

    public void addAttachment(
            String tenantId,
            String caseId,
            String attachmentId,
            String fileName,
            String contentType,
            byte[] content,
            String sha256,
            String description,
            String uploadedBy) {
        jdbc.sql(
                        """
                        INSERT INTO case_attachment (attachment_id, case_id, tenant_id, file_name, content_type,
                            size_bytes, sha256, description, uploaded_by, content)
                        VALUES (:id,:c,:t,:f,:ct,:sz,:sha,:d,:u,:content)
                        """)
                .param("id", attachmentId)
                .param("c", caseId)
                .param("t", tenantId)
                .param("f", fileName)
                .param("ct", contentType)
                .param("sz", content.length)
                .param("sha", sha256)
                .param("d", description)
                .param("u", uploadedBy)
                .param("content", content)
                .update();
    }

    private static Attachment mapAttachment(ResultSet rs, int n) throws SQLException {
        return new Attachment(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getString(6).trim(),
                rs.getString(7),
                rs.getString(8),
                rs.getObject(9, OffsetDateTime.class).toInstant());
    }

    private static final String ATTACHMENT_COLUMNS =
            "attachment_id, case_id, file_name, content_type, size_bytes, sha256, description,"
                    + " uploaded_by, uploaded_at";

    public List<Attachment> attachments(String tenantId, String caseId) {
        return jdbc.sql(
                        "SELECT "
                                + ATTACHMENT_COLUMNS
                                + " FROM case_attachment WHERE tenant_id=:t AND case_id=:c"
                                + " ORDER BY uploaded_at, attachment_id")
                .param("t", tenantId)
                .param("c", caseId)
                .query(CaseRepository::mapAttachment)
                .list();
    }

    public Optional<AttachmentContent> attachment(
            String tenantId, String caseId, String attachmentId) {
        return jdbc.sql(
                        "SELECT "
                                + ATTACHMENT_COLUMNS
                                + ", content FROM case_attachment WHERE tenant_id=:t AND case_id=:c"
                                + " AND attachment_id=:a")
                .param("t", tenantId)
                .param("c", caseId)
                .param("a", attachmentId)
                .query((rs, n) -> new AttachmentContent(mapAttachment(rs, n), rs.getBytes(10)))
                .optional();
    }
}
