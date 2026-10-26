-- A definition may carry the date and time of its first scheduled run. With a cron it repeats from there;
-- without one it runs once. next_run_at is filled when the definition is activated.
ALTER TABLE reconciliation_definition ADD COLUMN first_run_at TIMESTAMP WITH TIME ZONE;

DROP INDEX IF EXISTS idx_def_due;
CREATE INDEX idx_def_due ON reconciliation_definition (next_run_at)
    WHERE status = 'ACTIVE' AND next_run_at IS NOT NULL;
