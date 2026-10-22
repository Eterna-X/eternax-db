-- EXC-20: manual priority, escalation and evidence attachments.
ALTER TABLE reconciliation_case ADD COLUMN priority_override VARCHAR(10);
ALTER TABLE reconciliation_case ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reconciliation_case ADD COLUMN escalated_to VARCHAR(100);
ALTER TABLE reconciliation_case ADD COLUMN escalation_reason TEXT;
ALTER TABLE reconciliation_case ADD COLUMN escalated_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE case_attachment (
    attachment_id VARCHAR(64)  PRIMARY KEY,
    case_id       VARCHAR(64)  NOT NULL REFERENCES reconciliation_case (case_id),
    tenant_id     VARCHAR(100) NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256        CHAR(64)     NOT NULL,
    description   VARCHAR(500),
    uploaded_by   VARCHAR(100) NOT NULL,
    uploaded_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    content       BYTEA        NOT NULL
);
CREATE INDEX idx_attachment_case ON case_attachment (tenant_id, case_id, uploaded_at);
