-- notification-service: who can be told (a read-only copy of auth-service people), what each tenant wants
-- told, and every message with one delivery per channel.

-- Read-only copy of auth-service users, fed by eternax.user-directory.v1. Never edited here.
CREATE TABLE app_user (
    user_id            VARCHAR(64)  PRIMARY KEY,           -- same id as auth-service app_user
    tenant_id          VARCHAR(100) NOT NULL,
    login              VARCHAR(200) NOT NULL,              -- e-mail address, used for e-mail delivery
    display_name       VARCHAR(200),
    status             VARCHAR(12)  NOT NULL,              -- ACTIVE | DISABLED
    permissions        TEXT[]       NOT NULL DEFAULT '{}', -- flattened from the person's roles
    source_updated_at  TIMESTAMP WITH TIME ZONE NOT NULL   -- auth's change time; older events are ignored
);
CREATE INDEX idx_app_user_permissions ON app_user USING GIN (permissions);
CREATE INDEX idx_app_user_tenant ON app_user (tenant_id, status);

-- Read-only copy of a person's verified contacts in auth-service (phone, WhatsApp, push device).
CREATE TABLE user_contact (
    contact_id        VARCHAR(64)  PRIMARY KEY,            -- same id as auth-service user_contact
    tenant_id         VARCHAR(100) NOT NULL,
    user_id           VARCHAR(64)  NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    channel           VARCHAR(20)  NOT NULL,               -- SMS | WHATSAPP | PUSH
    address_cipher    TEXT         NOT NULL,               -- encrypted exactly as in auth-service
    platform          VARCHAR(10),                         -- ANDROID | IOS | WEB (push only)
    whatsapp_consent  BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_user_contact_user ON user_contact (user_id);

-- A tenant's override for one event type; without a row the built-in default rule applies.
CREATE TABLE notification_rule (
    rule_id                VARCHAR(64)  PRIMARY KEY,
    tenant_id              VARCHAR(100) NOT NULL,
    event_type             VARCHAR(50)  NOT NULL,          -- e.g. TAT_BREACH_IMMINENT
    channels               TEXT[]       NOT NULL,          -- e.g. {IN_APP,EMAIL}
    recipient_permissions  TEXT[]       NOT NULL,          -- e.g. {cases.work}
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by             VARCHAR(200) NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_rule UNIQUE (tenant_id, event_type)
);

-- One message for one person; also their in-app inbox row.
CREATE TABLE notification (
    notification_id    VARCHAR(64)  PRIMARY KEY,
    tenant_id          VARCHAR(100) NOT NULL,
    rule_id            VARCHAR(64)  REFERENCES notification_rule (rule_id), -- empty when the default applied
    recipient_user_id  VARCHAR(64)  NOT NULL REFERENCES app_user (user_id),
    event_type         VARCHAR(50)  NOT NULL,
    source_id          VARCHAR(64)  NOT NULL,              -- caseId, adjustmentId, periodId or breakId
    dedupe_key         VARCHAR(200) NOT NULL,              -- eventType|sourceId|userId
    title              VARCHAR(200) NOT NULL,
    body               TEXT         NOT NULL,
    link               VARCHAR(500),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    read_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notification_dedupe UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX idx_notification_inbox ON notification (tenant_id, recipient_user_id, created_at DESC);
CREATE INDEX idx_notification_unread ON notification (tenant_id, recipient_user_id) WHERE read_at IS NULL;

-- One send of a notification on one channel; PENDING rows are the dispatcher's work queue.
CREATE TABLE notification_delivery (
    delivery_id          VARCHAR(64)  PRIMARY KEY,
    notification_id      VARCHAR(64)  NOT NULL REFERENCES notification (notification_id) ON DELETE CASCADE,
    tenant_id            VARCHAR(100) NOT NULL,
    channel              VARCHAR(20)  NOT NULL,            -- IN_APP | EMAIL | SMS | WHATSAPP | PUSH
    contact_id           VARCHAR(64),                      -- the number or device used; empty for in-app and e-mail
    status               VARCHAR(12)  NOT NULL,            -- PENDING | SENT | DELIVERED | FAILED
    attempt_count        INT          NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMP WITH TIME ZONE,         -- when it is due; also the claim lease
    provider_message_id  VARCHAR(100),
    last_error           VARCHAR(500),
    sent_at              TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery UNIQUE (notification_id, channel)
);
CREATE INDEX idx_delivery_due ON notification_delivery (next_attempt_at) WHERE status = 'PENDING';
