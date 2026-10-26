-- An approver can turn down a draft with a reason; the author sees who rejected it, why and when.
ALTER TABLE mapping_definition
    ADD COLUMN rejected_by      VARCHAR(100),
    ADD COLUMN rejection_reason VARCHAR(2000),
    ADD COLUMN rejected_at      TIMESTAMP WITH TIME ZONE;
