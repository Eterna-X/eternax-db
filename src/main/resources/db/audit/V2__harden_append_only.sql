-- AUD-07: the audit table cannot be emptied either. TRUNCATE does not fire row triggers, so it
-- needs its own statement-level trigger.
CREATE TRIGGER trg_audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_immutable();

-- A table's owner can switch a trigger off, so the runtime must not be the owner. Migrations run as
-- the owner role (spring.flyway.user); the service connects as the application role, which gets
-- exactly what it needs on the audit tables: read and append, never change or remove.
-- The role is created by the platform team (eternasync-deploy/db-roles.sql). Where it does not exist
-- (embedded test databases) this block does nothing.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eternax_app') THEN
        EXECUTE 'REVOKE ALL ON audit_event, audit_chain_head FROM eternax_app';
        EXECUTE 'GRANT SELECT, INSERT ON audit_event TO eternax_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON audit_chain_head TO eternax_app';
        EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO eternax_app';
    END IF;
END
$$;
