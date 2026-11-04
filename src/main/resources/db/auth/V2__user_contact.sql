-- How a person can be reached besides their login e-mail: a phone for SMS, a WhatsApp number, a push device.
-- The number or token itself is stored encrypted; the hash finds duplicates without decrypting, the hint is
-- what screens show.

CREATE TABLE user_contact (
    contact_id          VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(100) NOT NULL REFERENCES tenant (tenant_id),
    user_id             VARCHAR(64)  NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    channel             VARCHAR(20)  NOT NULL,                  -- SMS | WHATSAPP | PUSH
    address_cipher      TEXT         NOT NULL,                  -- AES-GCM of the number or device token
    address_hash        VARCHAR(64)  NOT NULL,                  -- HMAC-SHA256 of the number or token
    address_hint        VARCHAR(40)  NOT NULL,                  -- masked, e.g. +91 98XXXXX210
    platform            VARCHAR(10),                            -- ANDROID | IOS | WEB (push only)
    verified_at         TIMESTAMP WITH TIME ZONE,               -- one-time code confirmed (push: when registered)
    opt_in_at           TIMESTAMP WITH TIME ZONE,               -- WhatsApp consent
    codes_sent          INT          NOT NULL DEFAULT 0,        -- codes sent in the current window
    codes_window_start  TIMESTAMP WITH TIME ZONE,
    failed_checks       INT          NOT NULL DEFAULT 0,        -- wrong codes since the last one sent
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_contact UNIQUE (user_id, channel, address_hash)
);
CREATE INDEX idx_user_contact_user ON user_contact (user_id);
