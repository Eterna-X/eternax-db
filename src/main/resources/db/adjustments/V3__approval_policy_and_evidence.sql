-- ADJ-15: approval depends on the kind of adjustment, how serious the case is and the rail, not only on
-- the amount; and some resolutions must carry evidence. The first enabled policy (lowest priority
-- number) that matches wins; with none, the global amount threshold applies and no evidence is needed.
CREATE TABLE approval_policy (
    policy_id           VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(100) NOT NULL,
    priority            INT          NOT NULL,
    name                VARCHAR(120) NOT NULL,
    adjustment_type     VARCHAR(30),                 -- null = any type
    severity            VARCHAR(10),                 -- LOW | MEDIUM | HIGH | CRITICAL, null = any
    rail                VARCHAR(30),                 -- null = any rail
    min_amount_minor    BIGINT       NOT NULL DEFAULT 0,
    second_approval     BOOLEAN      NOT NULL DEFAULT FALSE,
    evidence_required   BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by          VARCHAR(100) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_priority UNIQUE (tenant_id, priority)
);

-- what was decided for each adjustment when it was proposed, so a later policy change cannot rewrite it
ALTER TABLE adjustment ADD COLUMN policy_id VARCHAR(64);
ALTER TABLE adjustment ADD COLUMN requires_second_approval BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE adjustment ADD COLUMN evidence_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE adjustment ADD COLUMN evidence_reference VARCHAR(300);
