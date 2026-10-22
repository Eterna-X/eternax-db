package com.eternax.db.auth;

import java.time.Instant;
import java.util.List;

/** The shapes the service returns. */
public final class Iam {

    private Iam() {}

    public record RoleView(
            String roleId,
            String name,
            String description,
            boolean systemRole,
            boolean exclusive,
            List<String> permissions,
            long users) {}

    public record RoleRef(String roleId, String name, String domainId) {}

    public record UserView(
            String userId,
            String login,
            String displayName,
            String status,
            boolean mustChangePassword,
            boolean locked,
            Instant lastLoginAt,
            List<RoleRef> roles,
            List<String> permissions) {}

    public record TenantView(
            String tenantId, String name, String status, String timeZone, Instant createdAt) {}

    public record PolicyView(
            boolean secondAdminForAdmin,
            int passwordMinLength,
            int accessTokenMinutes,
            int refreshTokenHours,
            int maxFailedLogins,
            int lockoutMinutes,
            String allowedEmailDomains) {}

    public record SodView(String ruleId, String a, String b, String explanation) {}

    public record ChangeView(
            String requestId,
            String changeType,
            String userId,
            String userLogin,
            List<String> roleIds,
            List<String> roleNames,
            String status,
            String requestedBy,
            String decidedBy,
            String note,
            Instant createdAt) {}

    /**
     * A new account plus the one-time password it starts with. It is shown once and never stored in
     * clear.
     */
    public record Created(UserView user, String temporaryPassword) {}

    public record AssignResult(UserView user, ChangeView pendingChange) {}

    /** What a sign-in or refresh returns. */
    public record Session(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            long refreshExpiresInSeconds,
            boolean mustChangePassword,
            UserView user) {}
}
