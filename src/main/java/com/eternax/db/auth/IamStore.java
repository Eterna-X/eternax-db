package com.eternax.db.auth;

import com.eternax.db.auth.Iam.ChangeView;
import com.eternax.db.auth.Iam.PolicyView;
import com.eternax.db.auth.Iam.RoleRef;
import com.eternax.db.auth.Iam.RoleView;
import com.eternax.db.auth.Iam.SodView;
import com.eternax.db.auth.Iam.TenantView;
import com.eternax.db.auth.Iam.UserView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of the service. Every query is scoped by tenant: a row of another organisation is never
 * visible.
 */
@Repository
public class IamStore {

    /** An account row including what must never leave the service. */
    public record UserRow(
            String userId,
            String tenantId,
            String login,
            String displayName,
            String passwordHash,
            boolean mustChangePassword,
            String status,
            int failedLogins,
            Instant lockedUntil,
            Instant lastLoginAt) {}

    public record RefreshRow(
            String tokenHash,
            String userId,
            String tenantId,
            Instant expiresAt,
            Instant revokedAt) {}

    public record KeyRow(
            String kid,
            String publicPem,
            String privatePem,
            Instant createdAt,
            Instant retiredAt) {}

    public record Revocation(String tenantId, String userId, Instant minIat, Instant updatedAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final DefaultTenantPolicy defaults;

    public IamStore(JdbcClient jdbc, ObjectMapper mapper, DefaultTenantPolicy defaults) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.defaults = defaults;
    }

    private static Instant at(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ tenants

    public Optional<TenantView> tenant(String tenantId) {
        return jdbc.sql(
                        "SELECT tenant_id, name, status, time_zone, created_at FROM tenant"
                                + " WHERE tenant_id=:t")
                .param("t", tenantId)
                .query(
                        (rs, n) ->
                                new TenantView(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        at(rs.getObject(5, OffsetDateTime.class))))
                .optional();
    }

    public List<TenantView> tenants() {
        return jdbc.sql(
                        "SELECT tenant_id, name, status, time_zone, created_at FROM tenant ORDER BY"
                                + " tenant_id")
                .query(
                        (rs, n) ->
                                new TenantView(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        at(rs.getObject(5, OffsetDateTime.class))))
                .list();
    }

    public void insertTenant(String id, String name, String timeZone, String createdBy) {
        jdbc.sql(
                        "INSERT INTO tenant (tenant_id, name, time_zone, created_by) VALUES"
                                + " (:i,:n,:z,:by)")
                .param("i", id)
                .param("n", name)
                .param("z", timeZone)
                .param("by", createdBy)
                .update();
    }

    public void setTenantStatus(String id, String status) {
        jdbc.sql("UPDATE tenant SET status=:s WHERE tenant_id=:t")
                .param("s", status)
                .param("t", id)
                .update();
    }

    // ------------------------------------------------------------------ policy

    public PolicyView policy(String tenantId) {
        return jdbc.sql(
                        "SELECT second_admin_for_admin, password_min_length, access_token_minutes,"
                                + " refresh_token_hours, max_failed_logins, lockout_minutes,"
                                + " allowed_email_domains FROM tenant_policy WHERE tenant_id=:t")
                .param("t", tenantId)
                .query(
                        (rs, n) ->
                                new PolicyView(
                                        rs.getBoolean(1),
                                        rs.getInt(2),
                                        rs.getInt(3),
                                        rs.getInt(4),
                                        rs.getInt(5),
                                        rs.getInt(6),
                                        rs.getString(7)))
                .optional()
                .orElseGet(this::defaultPolicy);
    }

    public PolicyView defaultPolicy() {
        return defaults.get();
    }

    public void savePolicy(String tenantId, PolicyView p, String by) {
        jdbc.sql(
                        "INSERT INTO tenant_policy (tenant_id, second_admin_for_admin,"
                                + " password_min_length, access_token_minutes, refresh_token_hours,"
                                + " max_failed_logins, lockout_minutes, allowed_email_domains,"
                                + " updated_by) VALUES (:t,:sa,:pl,:am,:rh,:mf,:lm,:ad,:by) ON"
                                + " CONFLICT (tenant_id) DO UPDATE SET"
                                + " second_admin_for_admin=:sa, password_min_length=:pl,"
                                + " access_token_minutes=:am, refresh_token_hours=:rh,"
                                + " max_failed_logins=:mf, lockout_minutes=:lm,"
                                + " allowed_email_domains=:ad, updated_by=:by, updated_at=now()")
                .param("t", tenantId)
                .param("sa", p.secondAdminForAdmin())
                .param("pl", p.passwordMinLength())
                .param("am", p.accessTokenMinutes())
                .param("rh", p.refreshTokenHours())
                .param("mf", p.maxFailedLogins())
                .param("lm", p.lockoutMinutes())
                .param("ad", p.allowedEmailDomains())
                .param("by", by)
                .update();
    }

    // ------------------------------------------------------------------ separation of duties

    public List<SodView> sodRules(String tenantId) {
        return jdbc.sql(
                        "SELECT rule_id, permission_a, permission_b, explanation FROM sod_rule"
                                + " WHERE tenant_id=:t ORDER BY permission_a, permission_b")
                .param("t", tenantId)
                .query(
                        (rs, n) ->
                                new SodView(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)))
                .list();
    }

    public void replaceSodRules(String tenantId, List<SodView> rules) {
        jdbc.sql("DELETE FROM sod_rule WHERE tenant_id=:t").param("t", tenantId).update();
        for (SodView r : rules) {
            jdbc.sql(
                            "INSERT INTO sod_rule (rule_id, tenant_id, permission_a, permission_b,"
                                    + " explanation) VALUES (:i,:t,:a,:b,:e)")
                    .param("i", r.ruleId())
                    .param("t", tenantId)
                    .param("a", r.a())
                    .param("b", r.b())
                    .param("e", r.explanation())
                    .update();
        }
    }

    // ------------------------------------------------------------------ roles

    public List<RoleView> roles(String tenantId) {
        return loadRoles(tenantId, null);
    }

    public Optional<RoleView> role(String tenantId, String roleId) {
        return loadRoles(tenantId, List.of(roleId)).stream().findFirst();
    }

    public List<RoleView> rolesByIds(String tenantId, Collection<String> roleIds) {
        return roleIds.isEmpty() ? List.of() : loadRoles(tenantId, roleIds);
    }

    private List<RoleView> loadRoles(String tenantId, Collection<String> ids) {
        String filter = ids == null ? "" : " AND r.role_id IN (:ids)";
        var spec =
                jdbc.sql(
                                "SELECT r.role_id, r.name, r.description, r.system_role,"
                                    + " r.exclusive, (SELECT count(*) FROM user_role ur WHERE"
                                    + " ur.role_id=r.role_id) FROM app_role r WHERE r.tenant_id=:t"
                                        + filter
                                        + " ORDER BY lower(r.name)")
                        .param("t", tenantId);
        if (ids != null) {
            spec = spec.param("ids", ids);
        }
        record Row(
                String id,
                String name,
                String description,
                boolean system,
                boolean exclusive,
                long users) {}
        List<Row> rows =
                spec.query(
                                (rs, n) ->
                                        new Row(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getBoolean(4),
                                                rs.getBoolean(5),
                                                rs.getLong(6)))
                        .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> perms = new HashMap<>();
        jdbc.sql(
                        "SELECT role_id, permission_key FROM role_permission WHERE role_id IN"
                                + " (:ids) ORDER BY permission_key")
                .param("ids", rows.stream().map(Row::id).toList())
                .query(
                        rs -> {
                            perms.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                    .add(rs.getString(2));
                        });
        return rows.stream()
                .map(
                        r ->
                                new RoleView(
                                        r.id(),
                                        r.name(),
                                        r.description(),
                                        r.system(),
                                        r.exclusive(),
                                        perms.getOrDefault(r.id(), List.of()),
                                        r.users()))
                .toList();
    }

    public void insertRole(
            String tenantId,
            String roleId,
            String name,
            String description,
            boolean system,
            boolean exclusive,
            Collection<String> permissions,
            String by) {
        jdbc.sql(
                        "INSERT INTO app_role (role_id, tenant_id, name, description, system_role,"
                                + " exclusive, created_by) VALUES (:i,:t,:n,:d,:s,:x,:by)")
                .param("i", roleId)
                .param("t", tenantId)
                .param("n", name)
                .param("d", description)
                .param("s", system)
                .param("x", exclusive)
                .param("by", by)
                .update();
        replacePermissions(roleId, permissions);
    }

    public void updateRole(
            String roleId,
            String name,
            String description,
            boolean exclusive,
            Collection<String> permissions) {
        jdbc.sql(
                        "UPDATE app_role SET name=:n, description=:d, exclusive=:x,"
                                + " updated_at=now() WHERE role_id=:i")
                .param("i", roleId)
                .param("n", name)
                .param("d", description)
                .param("x", exclusive)
                .update();
        replacePermissions(roleId, permissions);
    }

    private void replacePermissions(String roleId, Collection<String> permissions) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id=:i").param("i", roleId).update();
        for (String key : new LinkedHashSet<>(permissions)) {
            jdbc.sql("INSERT INTO role_permission (role_id, permission_key) VALUES (:i,:k)")
                    .param("i", roleId)
                    .param("k", key)
                    .update();
        }
    }

    public void deleteRole(String roleId) {
        jdbc.sql("DELETE FROM app_role WHERE role_id=:i").param("i", roleId).update();
    }

    public List<String> userIdsWithRole(String roleId) {
        return jdbc.sql("SELECT user_id FROM user_role WHERE role_id=:r")
                .param("r", roleId)
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------------ users

    private static UserRow userRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getBoolean(6),
                rs.getString(7),
                rs.getInt(8),
                at(rs.getObject(9, OffsetDateTime.class)),
                at(rs.getObject(10, OffsetDateTime.class)));
    }

    private static final String USER_COLUMNS =
            "user_id, tenant_id, login, display_name, password_hash, must_change_password, status,"
                    + " failed_logins, locked_until, last_login_at";

    public Optional<UserRow> userRow(String tenantId, String userId) {
        return jdbc.sql(
                        "SELECT "
                                + USER_COLUMNS
                                + " FROM app_user WHERE tenant_id=:t AND user_id=:u")
                .param("t", tenantId)
                .param("u", userId)
                .query(IamStore::userRow)
                .optional();
    }

    public Optional<UserRow> userByLogin(String tenantId, String login) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE tenant_id=:t AND login=:l")
                .param("t", tenantId)
                .param("l", login)
                .query(IamStore::userRow)
                .optional();
    }

    public List<UserRow> userRows(String tenantId) {
        return jdbc.sql(
                        "SELECT "
                                + USER_COLUMNS
                                + " FROM app_user WHERE tenant_id=:t ORDER BY login")
                .param("t", tenantId)
                .query(IamStore::userRow)
                .list();
    }

    public void insertUser(
            String tenantId,
            String userId,
            String login,
            String displayName,
            String passwordHash,
            String by) {
        jdbc.sql(
                        "INSERT INTO app_user (user_id, tenant_id, login, display_name,"
                                + " password_hash, created_by) VALUES (:u,:t,:l,:d,:p,:by)")
                .param("u", userId)
                .param("t", tenantId)
                .param("l", login)
                .param("d", displayName)
                .param("p", passwordHash)
                .param("by", by)
                .update();
    }

    public void setPassword(String userId, String hash, boolean mustChange) {
        jdbc.sql(
                        "UPDATE app_user SET password_hash=:h, must_change_password=:m,"
                                + " failed_logins=0, locked_until=NULL, updated_at=now() WHERE"
                                + " user_id=:u")
                .param("h", hash)
                .param("m", mustChange)
                .param("u", userId)
                .update();
    }

    public void setUserStatus(String userId, String status) {
        jdbc.sql("UPDATE app_user SET status=:s, updated_at=now() WHERE user_id=:u")
                .param("s", status)
                .param("u", userId)
                .update();
    }

    public void recordFailedLogin(String userId, int failed, Instant lockedUntil) {
        jdbc.sql("UPDATE app_user SET failed_logins=:f, locked_until=:l WHERE user_id=:u")
                .param("f", failed)
                .param("l", utc(lockedUntil))
                .param("u", userId)
                .update();
    }

    public void recordSuccessfulLogin(String userId, Instant now) {
        jdbc.sql(
                        "UPDATE app_user SET failed_logins=0, locked_until=NULL, last_login_at=:n"
                                + " WHERE user_id=:u")
                .param("n", utc(now))
                .param("u", userId)
                .update();
    }

    public void unlock(String userId) {
        jdbc.sql("UPDATE app_user SET failed_logins=0, locked_until=NULL WHERE user_id=:u")
                .param("u", userId)
                .update();
    }

    public void assignRoles(
            String userId, Collection<String> roleIds, Map<String, String> domains) {
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u").param("u", userId).update();
        for (String roleId : new LinkedHashSet<>(roleIds)) {
            jdbc.sql("INSERT INTO user_role (user_id, role_id, domain_id) VALUES (:u,:r,:d)")
                    .param("u", userId)
                    .param("r", roleId)
                    .param("d", domains == null ? null : domains.get(roleId))
                    .update();
        }
    }

    public List<RoleRef> roleRefs(String userId) {
        return jdbc.sql(
                        "SELECT r.role_id, r.name, ur.domain_id FROM user_role ur JOIN app_role r"
                                + " USING (role_id) WHERE ur.user_id=:u ORDER BY lower(r.name)")
                .param("u", userId)
                .query((rs, n) -> new RoleRef(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    /** Every permission the person holds through any of their roles. */
    public Set<String> permissionsOf(String userId) {
        return new LinkedHashSet<>(
                jdbc.sql(
                                "SELECT DISTINCT rp.permission_key FROM user_role ur JOIN"
                                        + " role_permission rp USING (role_id) WHERE ur.user_id=:u"
                                        + " ORDER BY rp.permission_key")
                        .param("u", userId)
                        .query(String.class)
                        .list());
    }

    public UserView view(UserRow u) {
        return new UserView(
                u.userId(),
                u.login(),
                u.displayName(),
                u.status(),
                u.mustChangePassword(),
                u.lockedUntil() != null && u.lockedUntil().isAfter(Instant.now()),
                u.lastLoginAt(),
                roleRefs(u.userId()),
                List.copyOf(permissionsOf(u.userId())));
    }

    /**
     * Active people who can manage users, other than the given one. Guards against locking everyone
     * out.
     */
    public long activeAdmins(String tenantId, String exceptUserId) {
        return jdbc.sql(
                        "SELECT count(DISTINCT u.user_id) FROM app_user u JOIN user_role ur USING"
                                + " (user_id) JOIN role_permission rp USING (role_id) WHERE"
                                + " u.tenant_id=:t AND u.status='ACTIVE' AND"
                                + " rp.permission_key='admin.users' AND u.user_id <> :x")
                .param("t", tenantId)
                .param("x", exceptUserId == null ? "" : exceptUserId)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------ access changes

    public void insertChange(
            String tenantId,
            String requestId,
            String type,
            String userId,
            Collection<String> roleIds,
            String requestedBy) {
        try {
            jdbc.sql(
                            "INSERT INTO access_change (request_id, tenant_id, change_type,"
                                + " user_id, payload, requested_by) VALUES (:i,:t,:c,:u,:p,:by)")
                    .param("i", requestId)
                    .param("t", tenantId)
                    .param("c", type)
                    .param("u", userId)
                    .param("p", mapper.writeValueAsString(roleIds))
                    .param("by", requestedBy)
                    .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<ChangeView> change(String tenantId, String requestId) {
        return changes(tenantId, null, requestId).stream().findFirst();
    }

    public List<ChangeView> changes(String tenantId, String status) {
        return changes(tenantId, status, null);
    }

    private List<ChangeView> changes(String tenantId, String status, String requestId) {
        var spec =
                jdbc.sql(
                                "SELECT c.request_id, c.change_type, c.user_id, u.login, c.payload,"
                                        + " c.status, c.requested_by, c.decided_by, c.note,"
                                        + " c.created_at FROM access_change c LEFT JOIN app_user u"
                                        + " ON u.user_id=c.user_id WHERE c.tenant_id=:t"
                                        + (status == null ? "" : " AND c.status=:s")
                                        + (requestId == null ? "" : " AND c.request_id=:r")
                                        + " ORDER BY c.created_at DESC")
                        .param("t", tenantId);
        if (status != null) spec = spec.param("s", status);
        if (requestId != null) spec = spec.param("r", requestId);
        return spec.query(
                        (rs, n) -> {
                            List<String> ids = parseIds(rs.getString(5));
                            List<String> names =
                                    rolesByIds(tenantId, ids).stream().map(RoleView::name).toList();
                            return new ChangeView(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                    ids,
                                    names,
                                    rs.getString(6),
                                    rs.getString(7),
                                    rs.getString(8),
                                    rs.getString(9),
                                    at(rs.getObject(10, OffsetDateTime.class)));
                        })
                .list();
    }

    private List<String> parseIds(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    public void decideChange(String requestId, String status, String decidedBy, String note) {
        jdbc.sql(
                        "UPDATE access_change SET status=:s, decided_by=:d, note=:n,"
                                + " decided_at=now() WHERE request_id=:r")
                .param("s", status)
                .param("d", decidedBy)
                .param("n", note)
                .param("r", requestId)
                .update();
    }

    public boolean hasPendingChange(String userId) {
        return jdbc.sql("SELECT count(*) FROM access_change WHERE user_id=:u AND status='PENDING'")
                        .param("u", userId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    // ------------------------------------------------------------------ refresh tokens

    public void insertRefresh(
            String hash, String userId, String tenantId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql(
                        "INSERT INTO refresh_token (token_hash, user_id, tenant_id, issued_at,"
                                + " expires_at) VALUES (:h,:u,:t,:i,:e)")
                .param("h", hash)
                .param("u", userId)
                .param("t", tenantId)
                .param("i", utc(issuedAt))
                .param("e", utc(expiresAt))
                .update();
    }

    public Optional<RefreshRow> refresh(String hash) {
        return jdbc.sql(
                        "SELECT token_hash, user_id, tenant_id, expires_at, revoked_at FROM"
                                + " refresh_token WHERE token_hash=:h")
                .param("h", hash)
                .query(
                        (rs, n) ->
                                new RefreshRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        at(rs.getObject(4, OffsetDateTime.class)),
                                        at(rs.getObject(5, OffsetDateTime.class))))
                .optional();
    }

    /**
     * Marks one token used. Returns false when somebody else already did, so a token works once.
     */
    public boolean revokeRefresh(String hash, String replacedBy, Instant now) {
        return jdbc.sql(
                                "UPDATE refresh_token SET revoked_at=:n, replaced_by=:r WHERE"
                                        + " token_hash=:h AND revoked_at IS NULL")
                        .param("n", utc(now))
                        .param("r", replacedBy)
                        .param("h", hash)
                        .update()
                == 1;
    }

    public void revokeAllRefresh(String userId, Instant now) {
        jdbc.sql(
                        "UPDATE refresh_token SET revoked_at=:n WHERE user_id=:u AND revoked_at IS"
                                + " NULL")
                .param("n", utc(now))
                .param("u", userId)
                .update();
    }

    public void revokeTenantRefresh(String tenantId, Instant now) {
        jdbc.sql(
                        "UPDATE refresh_token SET revoked_at=:n WHERE tenant_id=:t AND revoked_at"
                                + " IS NULL")
                .param("n", utc(now))
                .param("t", tenantId)
                .update();
    }

    public int purgeExpiredRefresh(Instant before) {
        return jdbc.sql("DELETE FROM refresh_token WHERE expires_at < :b")
                .param("b", utc(before))
                .update();
    }

    // ------------------------------------------------------------------ signing keys

    public List<KeyRow> keys() {
        return jdbc.sql(
                        "SELECT kid, public_pem, private_pem, created_at, retired_at FROM"
                                + " signing_key ORDER BY created_at DESC")
                .query(
                        (rs, n) ->
                                new KeyRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        at(rs.getObject(4, OffsetDateTime.class)),
                                        at(rs.getObject(5, OffsetDateTime.class))))
                .list();
    }

    public void insertKey(String kid, String publicPem, String privatePem) {
        jdbc.sql("INSERT INTO signing_key (kid, public_pem, private_pem) VALUES (:k,:pu,:pr)")
                .param("k", kid)
                .param("pu", publicPem)
                .param("pr", privatePem)
                .update();
    }

    public void retireKeysExcept(String kid, Instant now) {
        jdbc.sql("UPDATE signing_key SET retired_at=:n WHERE kid <> :k AND retired_at IS" + " NULL")
                .param("n", utc(now))
                .param("k", kid)
                .update();
    }

    /** Retired keys stop verifying once every token they signed has expired. */
    public void dropKeysRetiredBefore(Instant before) {
        jdbc.sql("DELETE FROM signing_key WHERE retired_at IS NOT NULL AND retired_at < :b")
                .param("b", utc(before))
                .update();
    }

    // ------------------------------------------------------------------ revocation

    public void revoke(String tenantId, String userId, Instant minIat) {
        jdbc.sql(
                        "INSERT INTO token_revocation (tenant_id, user_id, min_iat) VALUES"
                                + " (:t,:u,:m) ON CONFLICT (tenant_id, user_id) DO UPDATE SET"
                                + " min_iat=:m, updated_at=now()")
                .param("t", tenantId)
                .param("u", userId)
                .param("m", utc(minIat))
                .update();
    }

    public List<Revocation> revocationsSince(Instant since) {
        return jdbc.sql(
                        "SELECT tenant_id, user_id, min_iat, updated_at FROM token_revocation WHERE"
                                + " updated_at > :s ORDER BY updated_at")
                .param("s", utc(since))
                .query(
                        (rs, n) ->
                                new Revocation(
                                        rs.getString(1),
                                        rs.getString(2),
                                        at(rs.getObject(3, OffsetDateTime.class)),
                                        at(rs.getObject(4, OffsetDateTime.class))))
                .list();
    }
}
