-- MAT-09: candidate sets that need a person (S-13). A set is every record that could be the
-- counterpart of the others; nothing in it is matched until someone selects or declines.
CREATE TABLE match_ambiguity (
    ambiguity_id  VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(100) NOT NULL,
    rail          VARCHAR(30)  NOT NULL,
    definition_id VARCHAR(100) NOT NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'OPEN',   -- OPEN | RESOLVED | DECLINED | EXPIRED
    explanation   TEXT         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at   TIMESTAMP WITH TIME ZONE,
    resolved_by   VARCHAR(100),
    resolution    TEXT,
    match_group_id VARCHAR(64)
);
CREATE INDEX idx_ambiguity_queue ON match_ambiguity (tenant_id, status, created_at);

CREATE TABLE match_ambiguity_member (
    ambiguity_id   VARCHAR(64) NOT NULL REFERENCES match_ambiguity (ambiguity_id),
    transaction_id VARCHAR(64) NOT NULL REFERENCES canonical_transaction (transaction_id),
    PRIMARY KEY (ambiguity_id, transaction_id)
);
CREATE INDEX idx_ambiguity_member_txn ON match_ambiguity_member (transaction_id);

-- DM-01: keep every attribute the canonical event already carries instead of dropping it at the door.
ALTER TABLE canonical_transaction ADD COLUMN value_date          DATE;
ALTER TABLE canonical_transaction ADD COLUMN posting_date        DATE;
ALTER TABLE canonical_transaction ADD COLUMN settlement_date     DATE;
ALTER TABLE canonical_transaction ADD COLUMN stan                VARCHAR(100);
ALTER TABLE canonical_transaction ADD COLUMN auth_code           VARCHAR(100);
ALTER TABLE canonical_transaction ADD COLUMN arn                 VARCHAR(100);
ALTER TABLE canonical_transaction ADD COLUMN batch_reference     VARCHAR(100);
ALTER TABLE canonical_transaction ADD COLUMN channel             VARCHAR(100);
ALTER TABLE canonical_transaction ADD COLUMN settlement_status   VARCHAR(30);
ALTER TABLE canonical_transaction ADD COLUMN parent_transaction_id VARCHAR(64);
ALTER TABLE canonical_transaction ADD COLUMN source_time_zone    VARCHAR(50);
ALTER TABLE canonical_transaction ADD COLUMN fee_minor           BIGINT;
ALTER TABLE canonical_transaction ADD COLUMN net_minor           BIGINT;
ALTER TABLE canonical_transaction ADD COLUMN fees_json           TEXT;
-- dedicated lookups used by the duplicate checks
CREATE INDEX idx_txn_matched_twin ON canonical_transaction (tenant_id, rail, source_id, terminal_id, amount_minor, txn_time)
    WHERE recon_status = 'MATCHED';
