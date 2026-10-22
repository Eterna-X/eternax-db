-- Field mappings are configuration data (HLD 5.1 principle 4): a format change is a new version.
CREATE TABLE mapping_definition (
    tenant_id  VARCHAR(100) NOT NULL,   -- '*' = platform default
    source_id  VARCHAR(100) NOT NULL,
    version    INT          NOT NULL,
    rail       VARCHAR(30)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT FALSE,
    field_mappings TEXT     NOT NULL,   -- JSON array of FieldMapping
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_id, version)
);
