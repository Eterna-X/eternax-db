-- SET-01: expected-versus-actual settlement. Recon records and explains variances; it does not
-- calculate, instruct or execute settlement (PRD section 16).
CREATE TABLE settlement_record (
    record_id       VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    rail            VARCHAR(30)  NOT NULL,
    side            VARCHAR(10)  NOT NULL,              -- EXPECTED | ACTUAL
    source_id       VARCHAR(100) NOT NULL,
    feed_id         VARCHAR(200) NOT NULL,
    record_hash     VARCHAR(64)  NOT NULL,
    settlement_key  VARCHAR(200) NOT NULL,              -- the stable id both sides share
    settlement_ref  VARCHAR(200),
    batch_id        VARCHAR(100),
    settlement_date DATE,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    account_ref     VARCHAR(100),
    status          VARCHAR(20)  NOT NULL,
    txn_time        TIMESTAMP WITH TIME ZONE NOT NULL,
    vault_location  VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement_idempotency UNIQUE (tenant_id, source_id, record_hash)
);
CREATE INDEX idx_settlement_key ON settlement_record (tenant_id, rail, settlement_key);
CREATE INDEX idx_settlement_date ON settlement_record (tenant_id, rail, settlement_date);

-- one row per settlement key: waiting for the other side, matched, or a variance
CREATE TABLE settlement_comparison (
    tenant_id       VARCHAR(100) NOT NULL,
    rail            VARCHAR(30)  NOT NULL,
    settlement_key  VARCHAR(200) NOT NULL,
    state           VARCHAR(12)  NOT NULL,              -- WAITING | MATCHED | VARIANCE
    variance_code   VARCHAR(40),
    variance_detail TEXT,
    variance_minor  BIGINT,
    break_id        VARCHAR(100),
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    evaluated_at    TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, rail, settlement_key)
);
CREATE INDEX idx_settlement_due ON settlement_comparison (deadline_at) WHERE state = 'WAITING';
CREATE INDEX idx_settlement_variance ON settlement_comparison (tenant_id, state, evaluated_at DESC);
