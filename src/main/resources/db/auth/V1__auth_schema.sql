-- eternaX-auth-service: identity, roles and permissions for every client organisation (tenant).

CREATE TABLE tenant (
    tenant_id   VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE | SUSPENDED
    time_zone   VARCHAR(60)  NOT NULL DEFAULT 'Asia/Kolkata',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by  VARCHAR(200) NOT NULL
);

-- What each organisation has chosen. Every value has a default in application.yml; a row here overrides it.
CREATE TABLE tenant_policy (
    tenant_id                  VARCHAR(100) PRIMARY KEY REFERENCES tenant (tenant_id),
    second_admin_for_admin     BOOLEAN      NOT NULL DEFAULT FALSE,   -- granting admin rights needs another admin's approval
    password_min_length        INT          NOT NULL DEFAULT 12,
    access_token_minutes       INT          NOT NULL DEFAULT 15,
    refresh_token_hours        INT          NOT NULL DEFAULT 8,
    max_failed_logins          INT          NOT NULL DEFAULT 5,
    lockout_minutes            INT          NOT NULL DEFAULT 15,
    allowed_email_domains      VARCHAR(500),                          -- comma separated; empty = any
    updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by                 VARCHAR(200)
);

CREATE TABLE app_role (
    role_id      VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(100) NOT NULL REFERENCES tenant (tenant_id),
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(300),
    system_role  BOOLEAN      NOT NULL DEFAULT FALSE,   -- seeded from a template when the tenant was created
    exclusive    BOOLEAN      NOT NULL DEFAULT FALSE,   -- a person holding it may hold no other role (an auditor)
    created_by   VARCHAR(200) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_role_name ON app_role (tenant_id, lower(name));

CREATE TABLE role_permission (
    role_id        VARCHAR(64)  NOT NULL REFERENCES app_role (role_id) ON DELETE CASCADE,
    permission_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission_key)
);

CREATE TABLE app_user (
    user_id              VARCHAR(64)  PRIMARY KEY,
    tenant_id            VARCHAR(100) NOT NULL REFERENCES tenant (tenant_id),
    login                VARCHAR(200) NOT NULL,                 -- lower-case e-mail address
    display_name         VARCHAR(200),
    password_hash        VARCHAR(100),
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    status               VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    failed_logins        INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP WITH TIME ZONE,
    last_login_at        TIMESTAMP WITH TIME ZONE,
    created_by           VARCHAR(200) NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_login UNIQUE (tenant_id, login)
);

CREATE TABLE user_role (
    user_id    VARCHAR(64) NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    role_id    VARCHAR(64) NOT NULL REFERENCES app_role (role_id),
    domain_id  VARCHAR(64),                                      -- optional: limit the role to one reconciliation domain
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_role ON user_role (role_id);

CREATE TABLE refresh_token (
    token_hash   VARCHAR(64)  PRIMARY KEY,                       -- SHA-256 of the token; the token itself is never stored
    user_id      VARCHAR(64)  NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    tenant_id    VARCHAR(100) NOT NULL,
    issued_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    replaced_by  VARCHAR(64)
);
CREATE INDEX idx_refresh_user ON refresh_token (user_id);

-- Changes to access that a second administrator must approve (only when the tenant's policy asks for it).
CREATE TABLE access_change (
    request_id    VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(100) NOT NULL REFERENCES tenant (tenant_id),
    change_type   VARCHAR(40)  NOT NULL,                         -- ASSIGN_ROLES
    user_id       VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,                         -- JSON: the role ids to apply
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING',       -- PENDING | APPROVED | REJECTED
    requested_by  VARCHAR(200) NOT NULL,
    decided_by    VARCHAR(200),
    note          VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    decided_at    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_change_status ON access_change (tenant_id, status);

-- Pairs of permissions that one person (or one custom role) must not hold together, per organisation.
CREATE TABLE sod_rule (
    rule_id       VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(100) NOT NULL REFERENCES tenant (tenant_id),
    permission_a  VARCHAR(100) NOT NULL,
    permission_b  VARCHAR(100) NOT NULL,
    explanation   VARCHAR(300),
    CONSTRAINT uq_sod UNIQUE (tenant_id, permission_a, permission_b)
);

CREATE TABLE signing_key (
    kid         VARCHAR(64) PRIMARY KEY,
    algorithm   VARCHAR(10) NOT NULL DEFAULT 'RS256',
    public_pem  TEXT        NOT NULL,
    private_pem TEXT        NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    retired_at  TIMESTAMP WITH TIME ZONE                         -- a retired key still verifies, never signs
);

-- Tokens issued before min_iat for this person are no longer accepted. user_id '*' means the whole tenant.
CREATE TABLE token_revocation (
    tenant_id   VARCHAR(100) NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    min_iat     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX idx_revocation_updated ON token_revocation (updated_at);
