-- DEF-10: an ordered list of matching rules per definition version, as JSON. NULL = the default plan
-- (exact reference, then aggregate parts, then terminal/amount/time), which is what every existing
-- definition keeps.
ALTER TABLE reconciliation_definition ADD COLUMN rules TEXT;
