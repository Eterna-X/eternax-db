package com.eternax.db.notification;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of the service. Every query is scoped by tenant: a row of another organisation is never
 * visible.
 */
@Repository
public class NotificationRepository {

    /** A person who can be told, from the read-only copy of auth-service users. */
    public record Recipient(String userId, String login, String displayName) {}

    /** A contact from the read-only copy (the address stays encrypted). */
    public record ContactRow(
            String contactId,
            String channel,
            String addressCipher,
            String platform,
            boolean whatsappConsent) {}

    /** A tenant's override for one event type. */
    public record RuleRow(
            String ruleId,
            String eventType,
            List<String> channels,
            List<String> recipientPermissions,
            boolean enabled,
            String updatedBy,
            Instant updatedAt) {}

    public record InboxItem(
            String notificationId,
            String eventType,
            String title,
            String body,
            String link,
            Instant createdAt,
            Instant readAt) {}

    /** A delivery the dispatcher holds the lease on, with everything needed to send it. */
    public record ClaimedDelivery(
            String deliveryId,
            String tenantId,
            String channel,
            String contactId,
            int attemptCount,
            String recipientLogin,
            String recipientName,
            String title,
            String body,
            String link) {}

    /** One delivery's state, for support. */
    public record DeliveryView(
            String deliveryId,
            String channel,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant sentAt,
            String providerMessageId,
            String lastError) {}

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant at(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }

    private static List<String> strings(ResultSet rs, int column) throws SQLException {
        Array a = rs.getArray(column);
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    private static String[] array(Collection<String> values) {
        return values.toArray(String[]::new);
    }

    // ------------------------------------------------------------------ people (read-only copy)

    /**
     * Inserts or refreshes one person. A copy already newer than this event is kept, so a replayed
     * or out-of-order event never rolls data back.
     *
     * @return true when the row was written
     */
    public boolean upsertUser(
            String userId,
            String tenantId,
            String login,
            String displayName,
            String status,
            Collection<String> permissions,
            Instant updatedAt) {
        return jdbc.sql(
                                "INSERT INTO app_user (user_id, tenant_id, login, display_name,"
                                    + " status, permissions, source_updated_at) VALUES"
                                    + " (:u,:t,:l,:d,:s,:p,:at) ON CONFLICT (user_id) DO UPDATE SET"
                                    + " tenant_id=EXCLUDED.tenant_id, login=EXCLUDED.login,"
                                    + " display_name=EXCLUDED.display_name, status=EXCLUDED.status,"
                                    + " permissions=EXCLUDED.permissions,"
                                    + " source_updated_at=EXCLUDED.source_updated_at WHERE"
                                    + " app_user.source_updated_at < EXCLUDED.source_updated_at")
                        .param("u", userId)
                        .param("t", tenantId)
                        .param("l", login)
                        .param("d", displayName)
                        .param("s", status)
                        .param("p", array(permissions))
                        .param("at", utc(updatedAt))
                        .update()
                == 1;
    }

    public void replaceContacts(String tenantId, String userId, List<ContactRow> contacts) {
        jdbc.sql("DELETE FROM user_contact WHERE user_id=:u").param("u", userId).update();
        for (ContactRow c : contacts) {
            jdbc.sql(
                            "INSERT INTO user_contact (contact_id, tenant_id, user_id, channel,"
                                    + " address_cipher, platform, whatsapp_consent) VALUES"
                                    + " (:c,:t,:u,:ch,:a,:p,:w)")
                    .param("c", c.contactId())
                    .param("t", tenantId)
                    .param("u", userId)
                    .param("ch", c.channel())
                    .param("a", c.addressCipher())
                    .param("p", c.platform())
                    .param("w", c.whatsappConsent())
                    .update();
        }
    }

    /** Active people of the tenant who hold at least one of the permissions. */
    public List<Recipient> activeRecipients(String tenantId, Collection<String> anyPermission) {
        if (anyPermission.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT user_id, login, display_name FROM app_user WHERE tenant_id=:t AND"
                                + " status='ACTIVE' AND permissions && CAST(:p AS text[]) ORDER BY"
                                + " login")
                .param("t", tenantId)
                .param("p", array(anyPermission))
                .query((rs, n) -> new Recipient(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    public Optional<Recipient> userByLogin(String tenantId, String login) {
        return jdbc.sql(
                        "SELECT user_id, login, display_name FROM app_user WHERE tenant_id=:t AND"
                                + " login=:l")
                .param("t", tenantId)
                .param("l", login)
                .query((rs, n) -> new Recipient(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    // ------------------------------------------------------------------ rules

    private static RuleRow ruleRow(ResultSet rs, int n) throws SQLException {
        return new RuleRow(
                rs.getString(1),
                rs.getString(2),
                strings(rs, 3),
                strings(rs, 4),
                rs.getBoolean(5),
                rs.getString(6),
                at(rs.getObject(7, OffsetDateTime.class)));
    }

    private static final String RULE_COLUMNS =
            "rule_id, event_type, channels, recipient_permissions, enabled, updated_by, updated_at";

    public Optional<RuleRow> rule(String tenantId, String eventType) {
        return jdbc.sql(
                        "SELECT "
                                + RULE_COLUMNS
                                + " FROM notification_rule WHERE tenant_id=:t AND event_type=:e")
                .param("t", tenantId)
                .param("e", eventType)
                .query(NotificationRepository::ruleRow)
                .optional();
    }

    public List<RuleRow> rules(String tenantId) {
        return jdbc.sql(
                        "SELECT "
                                + RULE_COLUMNS
                                + " FROM notification_rule WHERE tenant_id=:t ORDER BY event_type")
                .param("t", tenantId)
                .query(NotificationRepository::ruleRow)
                .list();
    }

    public void saveRule(
            String tenantId,
            String ruleId,
            String eventType,
            Collection<String> channels,
            Collection<String> permissions,
            boolean enabled,
            String by) {
        jdbc.sql(
                        "INSERT INTO notification_rule (rule_id, tenant_id, event_type, channels,"
                                + " recipient_permissions, enabled, updated_by) VALUES"
                                + " (:i,:t,:e,:c,:p,:en,:by) ON CONFLICT (tenant_id, event_type) DO"
                                + " UPDATE SET channels=EXCLUDED.channels,"
                                + " recipient_permissions=EXCLUDED.recipient_permissions,"
                                + " enabled=EXCLUDED.enabled, updated_by=EXCLUDED.updated_by,"
                                + " updated_at=now()")
                .param("i", ruleId)
                .param("t", tenantId)
                .param("e", eventType)
                .param("c", array(channels))
                .param("p", array(permissions))
                .param("en", enabled)
                .param("by", by)
                .update();
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Writes one notification unless the same one exists already (same dedupe key).
     *
     * @return false for a duplicate: Kafka delivered the event again
     */
    public boolean insertNotification(
            String notificationId,
            String tenantId,
            String ruleId,
            String recipientUserId,
            String eventType,
            String sourceId,
            String dedupeKey,
            String title,
            String body,
            String link) {
        return jdbc.sql(
                                "INSERT INTO notification (notification_id, tenant_id, rule_id,"
                                    + " recipient_user_id, event_type, source_id, dedupe_key,"
                                    + " title, body, link) VALUES (:i,:t,:r,:u,:e,:s,:d,:ti,:b,:l)"
                                    + " ON CONFLICT (tenant_id, dedupe_key) DO NOTHING")
                        .param("i", notificationId)
                        .param("t", tenantId)
                        .param("r", ruleId)
                        .param("u", recipientUserId)
                        .param("e", eventType)
                        .param("s", sourceId)
                        .param("d", dedupeKey)
                        .param("ti", title)
                        .param("b", body)
                        .param("l", link)
                        .update()
                == 1;
    }

    /** In-app is delivered by writing the row; every other channel waits for the dispatcher. */
    public void insertDelivery(
            String deliveryId,
            String notificationId,
            String tenantId,
            String channel,
            String contactId,
            String status,
            Instant nextAttemptAt,
            Instant sentAt) {
        jdbc.sql(
                        "INSERT INTO notification_delivery (delivery_id, notification_id,"
                                + " tenant_id, channel, contact_id, status, next_attempt_at,"
                                + " sent_at) VALUES (:i,:n,:t,:c,:ct,:s,:na,:sa)")
                .param("i", deliveryId)
                .param("n", notificationId)
                .param("t", tenantId)
                .param("c", channel)
                .param("ct", contactId)
                .param("s", status)
                .param("na", utc(nextAttemptAt))
                .param("sa", utc(sentAt))
                .update();
    }

    // ------------------------------------------------------------------ inbox

    public List<InboxItem> inbox(
            String tenantId, String userId, boolean unreadOnly, int limit, int offset) {
        return jdbc.sql(
                        "SELECT notification_id, event_type, title, body, link, created_at,"
                                + " read_at FROM notification WHERE tenant_id=:t AND"
                                + " recipient_user_id=:u"
                                + (unreadOnly ? " AND read_at IS NULL" : "")
                                + " ORDER BY created_at DESC, notification_id LIMIT :l OFFSET :o")
                .param("t", tenantId)
                .param("u", userId)
                .param("l", limit)
                .param("o", offset)
                .query(
                        (rs, n) ->
                                new InboxItem(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        at(rs.getObject(6, OffsetDateTime.class)),
                                        at(rs.getObject(7, OffsetDateTime.class))))
                .list();
    }

    public long countInbox(String tenantId, String userId, boolean unreadOnly) {
        return jdbc.sql(
                        "SELECT count(*) FROM notification WHERE tenant_id=:t AND"
                                + " recipient_user_id=:u"
                                + (unreadOnly ? " AND read_at IS NULL" : ""))
                .param("t", tenantId)
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    /**
     * @return false when there is no such notification for this person (another person's id is
     *     simply not found)
     */
    public boolean markRead(String tenantId, String userId, String notificationId, Instant now) {
        int found =
                jdbc.sql(
                                "SELECT count(*) FROM notification WHERE tenant_id=:t AND"
                                        + " recipient_user_id=:u AND notification_id=:n")
                        .param("t", tenantId)
                        .param("u", userId)
                        .param("n", notificationId)
                        .query(Integer.class)
                        .single();
        if (found == 0) {
            return false;
        }
        jdbc.sql(
                        "UPDATE notification SET read_at=:r WHERE notification_id=:n AND read_at"
                                + " IS NULL")
                .param("r", utc(now))
                .param("n", notificationId)
                .update();
        return true;
    }

    public int markAllRead(String tenantId, String userId, Instant now) {
        return jdbc.sql(
                        "UPDATE notification SET read_at=:r WHERE tenant_id=:t AND"
                                + " recipient_user_id=:u AND read_at IS NULL")
                .param("r", utc(now))
                .param("t", tenantId)
                .param("u", userId)
                .update();
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Takes up to {@code batch} due deliveries for this instance: one statement locks them with
     * SKIP LOCKED (another instance passes over them) and pushes their due time to the lease end,
     * so if this instance dies they become due again for someone else. Returns them with the
     * content and recipient needed to send.
     */
    public List<ClaimedDelivery> claimDue(Instant now, int batch, Instant leaseUntil) {
        List<String> ids =
                jdbc.sql(
                                "WITH due AS (SELECT delivery_id FROM notification_delivery WHERE"
                                        + " status='PENDING' AND next_attempt_at <= :now ORDER BY"
                                        + " next_attempt_at LIMIT :b FOR UPDATE SKIP LOCKED) UPDATE"
                                        + " notification_delivery d SET next_attempt_at=:lease FROM"
                                        + " due WHERE d.delivery_id=due.delivery_id RETURNING"
                                        + " d.delivery_id")
                        .param("now", utc(now))
                        .param("b", batch)
                        .param("lease", utc(leaseUntil))
                        .query(String.class)
                        .list();
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT d.delivery_id, d.tenant_id, d.channel, d.contact_id,"
                                + " d.attempt_count, u.login, u.display_name, n.title, n.body,"
                                + " n.link FROM notification_delivery d JOIN notification n USING"
                                + " (notification_id) JOIN app_user u ON"
                                + " u.user_id=n.recipient_user_id WHERE d.delivery_id IN (:ids)")
                .param("ids", ids)
                .query(
                        (rs, n) ->
                                new ClaimedDelivery(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getInt(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getString(10)))
                .list();
    }

    public void recordSent(String deliveryId, String providerMessageId, Instant now) {
        jdbc.sql(
                        "UPDATE notification_delivery SET status='SENT', sent_at=:s,"
                                + " provider_message_id=:m, attempt_count=attempt_count+1,"
                                + " next_attempt_at=NULL, last_error=NULL WHERE delivery_id=:d"
                                + " AND status='PENDING'")
                .param("s", utc(now))
                .param("m", providerMessageId)
                .param("d", deliveryId)
                .update();
    }

    /** Stays PENDING, due again at {@code nextAttemptAt}. */
    public void recordRetry(String deliveryId, Instant nextAttemptAt, String error) {
        jdbc.sql(
                        "UPDATE notification_delivery SET attempt_count=attempt_count+1,"
                                + " next_attempt_at=:n, last_error=:e WHERE delivery_id=:d AND"
                                + " status='PENDING'")
                .param("n", utc(nextAttemptAt))
                .param("e", truncate(error))
                .param("d", deliveryId)
                .update();
    }

    public void recordFailed(String deliveryId, String error) {
        jdbc.sql(
                        "UPDATE notification_delivery SET status='FAILED',"
                                + " attempt_count=attempt_count+1, next_attempt_at=NULL,"
                                + " last_error=:e WHERE delivery_id=:d AND status='PENDING'")
                .param("e", truncate(error))
                .param("d", deliveryId)
                .update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public List<DeliveryView> deliveries(String tenantId, String notificationId) {
        return jdbc.sql(
                        "SELECT delivery_id, channel, status, attempt_count, next_attempt_at,"
                                + " sent_at, provider_message_id, last_error FROM"
                                + " notification_delivery WHERE tenant_id=:t AND"
                                + " notification_id=:n ORDER BY channel")
                .param("t", tenantId)
                .param("n", notificationId)
                .query(
                        (rs, n) ->
                                new DeliveryView(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        at(rs.getObject(5, OffsetDateTime.class)),
                                        at(rs.getObject(6, OffsetDateTime.class)),
                                        rs.getString(7),
                                        rs.getString(8)))
                .list();
    }
}
