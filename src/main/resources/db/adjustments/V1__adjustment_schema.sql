CREATE TABLE adjustment (
    adjustment_id    VARCHAR(64)  PRIMARY KEY,
    tenant_id        VARCHAR(100) NOT NULL,
    case_id          VARCHAR(64)  NOT NULL,
    type             VARCHAR(30)  NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)      NOT NULL,
    narrative        TEXT,
    proposed_by      VARCHAR(100) NOT NULL,
    proposed_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    first_approver   VARCHAR(100),
    second_approver  VARCHAR(100),
    journal_reference VARCHAR(100),
    status           VARCHAR(30)  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_adjustment_queue ON adjustment (tenant_id, status, proposed_at DESC);
CREATE INDEX idx_adjustment_case ON adjustment (tenant_id, case_id);
-- A journal reference identifies one posting; it can be confirmed at most once.
CREATE UNIQUE INDEX uq_adjustment_journal ON adjustment (tenant_id, journal_reference) WHERE journal_reference IS NOT NULL;
