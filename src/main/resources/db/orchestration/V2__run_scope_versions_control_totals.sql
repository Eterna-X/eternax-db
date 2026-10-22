-- RUN-10: a run describes the data since the previous run, not everything the definition ever saw.
ALTER TABLE reconciliation_run ADD COLUMN scope_from TIMESTAMP WITH TIME ZONE;
-- RUN-05: what the run was executed with: mapping version of every source, and the rule snapshot.
ALTER TABLE reconciliation_run ADD COLUMN mapping_versions TEXT;
ALTER TABLE reconciliation_run ADD COLUMN rules_snapshot TEXT;
-- RUN-04: expected / received / processed / rejected / duplicate counts, value totals, variance.
ALTER TABLE reconciliation_run ADD COLUMN control_totals TEXT;
