CREATE TABLE canonical_transaction (
    transaction_id  VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    rail            VARCHAR(30)  NOT NULL,
    source_id       VARCHAR(100) NOT NULL,
    feed_id         VARCHAR(200) NOT NULL,
    record_hash     VARCHAR(64)  NOT NULL,
    direction       VARCHAR(10)  NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    txn_time        TIMESTAMP WITH TIME ZONE NOT NULL,
    rrn             VARCHAR(100),
    utr             VARCHAR(100),
    terminal_id     VARCHAR(100),
    merchant_id     VARCHAR(100),
    txn_status      VARCHAR(20)  NOT NULL,
    account_token   VARCHAR(100) NOT NULL,
    vault_location  VARCHAR(500) NOT NULL,
    vault_checksum  VARCHAR(64)  NOT NULL,
    match_key       VARCHAR(200) NOT NULL,
    recon_status    VARCHAR(30)  NOT NULL DEFAULT 'WAITING',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- idempotency key of a record: tenant, source, content hash (HLD 6.3)
    CONSTRAINT uq_txn_idempotency UNIQUE (tenant_id, source_id, record_hash)
);
CREATE INDEX idx_txn_match_key ON canonical_transaction (tenant_id, rail, match_key);
CREATE INDEX idx_txn_composite ON canonical_transaction (tenant_id, rail, terminal_id, amount_minor, txn_time)
    WHERE recon_status <> 'MATCHED';

-- One row per business transaction (all counterparts share tenant, rail, match key).
CREATE TABLE match_cluster (
    tenant_id     VARCHAR(100) NOT NULL,
    rail          VARCHAR(30)  NOT NULL,
    match_key     VARCHAR(200) NOT NULL,
    definition_id VARCHAR(100) NOT NULL,
    state         VARCHAR(20)  NOT NULL,   -- WAITING | MATCHED | EXCEPTION
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    break_id      VARCHAR(100),
    matched_at    TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, rail, match_key)
);
CREATE INDEX idx_cluster_due ON match_cluster (deadline_at) WHERE state = 'WAITING';

CREATE TABLE match_group (
    match_group_id VARCHAR(64)  PRIMARY KEY,
    tenant_id      VARCHAR(100) NOT NULL,
    rail           VARCHAR(30)  NOT NULL,
    tier           VARCHAR(20)  NOT NULL,
    rule_name      VARCHAR(100) NOT NULL,
    rule_version   VARCHAR(20)  NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL,
    explanation    TEXT         NOT NULL,
    matched_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_group_tenant_time ON match_group (tenant_id, matched_at);

-- The unique key on transaction_id is the storage-level guard that a record is never matched twice
-- (HLD 7.6 item 2) while Phase 0 supports full allocation only.
CREATE TABLE match_group_allocation (
    match_group_id VARCHAR(64)  NOT NULL REFERENCES match_group (match_group_id),
    transaction_id VARCHAR(64)  NOT NULL UNIQUE REFERENCES canonical_transaction (transaction_id),
    side           VARCHAR(10)  NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    PRIMARY KEY (match_group_id, transaction_id)
);

CREATE TABLE definition_snapshot (
    tenant_id     VARCHAR(100) NOT NULL,
    definition_id VARCHAR(100) NOT NULL,
    version       INT          NOT NULL,
    rail          VARCHAR(30)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    payload       TEXT         NOT NULL,
    PRIMARY KEY (tenant_id, definition_id)
);
