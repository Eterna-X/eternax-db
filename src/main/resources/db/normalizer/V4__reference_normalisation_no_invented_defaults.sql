-- Publishes version n+1 of every platform-default mapping (tenants keep their own mappings):
--  * rrn and utr use the REFERENCE transformation, so 'abc12' / 'ABC12' and '0012345' / '12345' are
--    the same payment in every source (MAT-10);
--  * the sentinel defaults DEBIT (direction, except ATM cash movement which is always a debit) and
--    UNKNOWN (status), which silently invented facts when a source column was blank, are removed; a
--    blank column now fails the record visibly (NRM-04).
INSERT INTO mapping_definition (tenant_id, source_id, version, rail, active, field_mappings, created_by)
SELECT m.tenant_id, m.source_id, m.version + 1, m.rail, TRUE,
       (SELECT jsonb_agg(
                 CASE
                   WHEN e->>'canonicalField' IN ('rrn', 'utr')
                        THEN (e - 'parameter') || jsonb_build_object('transformation', 'REFERENCE')
                   WHEN e->>'canonicalField' = 'direction' AND e->>'defaultValue' = 'DEBIT' AND m.rail <> 'CARD_ATM'
                        THEN e - 'defaultValue'
                   WHEN e->>'canonicalField' = 'transactionStatus' AND e->>'defaultValue' = 'UNKNOWN'
                        THEN e - 'defaultValue'
                   ELSE e
                 END ORDER BY ord)
          FROM jsonb_array_elements(m.field_mappings::jsonb) WITH ORDINALITY AS t(e, ord))::text,
       'system'
FROM mapping_definition m
WHERE m.tenant_id = '*' AND m.active;

UPDATE mapping_definition old SET active = FALSE
WHERE old.tenant_id = '*' AND old.active
  AND EXISTS (SELECT 1 FROM mapping_definition n
              WHERE n.tenant_id = old.tenant_id AND n.source_id = old.source_id AND n.version = old.version + 1);

-- NRM-06: which dead-letter messages were already sent back, so a second click cannot double them.
CREATE TABLE dlt_replay (
    topic       VARCHAR(200) NOT NULL,
    part        INT          NOT NULL,
    msg_offset  BIGINT       NOT NULL,
    replayed_by VARCHAR(100) NOT NULL,
    replayed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (topic, part, msg_offset)
);
