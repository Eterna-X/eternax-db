-- ADJ-10 / AUD-10: the reason given when an adjustment is rejected is part of the record.
ALTER TABLE adjustment ADD COLUMN rejection_reason TEXT;
-- ADJ-02: the break amount of the case as seen when the adjustment was proposed, and whether the
-- proposer explicitly asked for more than that (such an item always needs a second approver).
ALTER TABLE adjustment ADD COLUMN case_impact_minor BIGINT;
ALTER TABLE adjustment ADD COLUMN over_break BOOLEAN NOT NULL DEFAULT FALSE;
