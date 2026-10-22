CREATE TABLE reconciliation_definition (
    definition_id       VARCHAR(64)  NOT NULL,
    version             INT          NOT NULL,
    tenant_id           VARCHAR(100) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    rail                VARCHAR(30)  NOT NULL,
    required_sources    TEXT         NOT NULL,
    wait_window_seconds BIGINT       NOT NULL CHECK (wait_window_seconds BETWEEN 60 AND 86400),
    composite_window_seconds BIGINT  NOT NULL CHECK (composite_window_seconds BETWEEN 60 AND 86400),
    amount_tolerance_minor BIGINT    NOT NULL CHECK (amount_tolerance_minor BETWEEN 0 AND 100),
    require_opposite_direction BOOLEAN NOT NULL DEFAULT FALSE,
    cron_schedule       VARCHAR(100),
    time_zone           VARCHAR(50)  NOT NULL DEFAULT 'Asia/Kolkata',
    status              VARCHAR(20)  NOT NULL,   -- DRAFT | ACTIVE | RETIRED
    created_by          VARCHAR(100) NOT NULL,
    approved_by         VARCHAR(100),
    next_run_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (definition_id, version)
);
CREATE INDEX idx_def_tenant ON reconciliation_definition (tenant_id, name, version DESC);
CREATE UNIQUE INDEX uq_def_one_active ON reconciliation_definition (tenant_id, name) WHERE status = 'ACTIVE';
CREATE INDEX idx_def_due ON reconciliation_definition (next_run_at) WHERE status = 'ACTIVE' AND cron_schedule IS NOT NULL;

CREATE TABLE reconciliation_run (
    run_id             VARCHAR(64)  PRIMARY KEY,
    tenant_id          VARCHAR(100) NOT NULL,
    definition_id      VARCHAR(64)  NOT NULL,
    definition_version INT          NOT NULL,
    period_key         VARCHAR(50)  NOT NULL,
    revision           INT          NOT NULL DEFAULT 1,
    trigger_type       VARCHAR(20)  NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at       TIMESTAMP WITH TIME ZONE,
    matched_count      BIGINT       NOT NULL DEFAULT 0,
    exception_count    BIGINT       NOT NULL DEFAULT 0,
    checkpoint         TEXT,
    operator_id        VARCHAR(100) NOT NULL,
    -- a run is keyed by definition, period and version; a re-run is a new revision (HLD 18.4)
    CONSTRAINT uq_run_key UNIQUE (definition_id, definition_version, period_key, revision)
);
CREATE INDEX idx_run_tenant ON reconciliation_run (tenant_id, started_at DESC);
CREATE INDEX idx_run_open ON reconciliation_run (started_at) WHERE status = 'RUNNING';
