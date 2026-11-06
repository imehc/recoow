CREATE TABLE change_events (
    event_id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(id),
    generation TEXT NOT NULL CHECK (generation <> ''),
    version BIGINT NOT NULL CHECK (version > 0),
    previous_version BIGINT NOT NULL CHECK (previous_version >= 0 AND previous_version < version),
    table_name TEXT NOT NULL,
    id TEXT NOT NULL,
    title TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('insert', 'update', 'delete', 'restore')),
    method TEXT NOT NULL CHECK (method IN ('normal', 'automatic_merge', 'manual_resolution', 'system', 'unknown')),
    operation_id TEXT NOT NULL,
    trigger_event_id TEXT,
    mutation_id TEXT,
    modified_at_ms BIGINT,
    accepted_at_ms BIGINT,
    original_source JSONB,
    submission JSONB,
    UNIQUE (account_id, version),
    FOREIGN KEY (account_id, version) REFERENCES change_log(account_id, version) ON DELETE CASCADE
);
CREATE INDEX idx_change_events_entity ON change_events (account_id, generation, table_name, id, version DESC);
CREATE INDEX idx_change_events_time ON change_events (account_id, accepted_at_ms, version DESC);
CREATE INDEX idx_change_events_operation ON change_events (account_id, generation, operation_id, version DESC);
CREATE INDEX idx_change_events_client ON change_events (account_id, (original_source->>'client'), version DESC);
CREATE INDEX idx_change_events_device ON change_events (account_id, (submission->>'deviceId'), version DESC);

-- Existing snapshots have no reliable server acceptance time or submitting identity.
-- Inventory them without inventing those values or assigning old system changes to a user.
DO $$
DECLARE tenant TEXT;
BEGIN
    FOR tenant IN SELECT account.id FROM account LOOP
        PERFORM set_config('recoow.tenant_id', tenant, true);
        INSERT INTO change_events (
            event_id, account_id, generation, version, previous_version, table_name, id, title,
            operation, method, operation_id, modified_at_ms, original_source
        )
        SELECT event_key, account_id, generation, version, COALESCE(previous_version, 0), table_name, id,
            COALESCE(NULLIF(left(snapshot->>'title', 160), ''), NULLIF(left(snapshot->>'name', 160), ''),
                     NULLIF(left(previous_snapshot->>'title', 160), ''), NULLIF(left(previous_snapshot->>'name', 160), ''), id),
            CASE WHEN deleted <> 0 THEN 'delete' WHEN previous_version IS NULL THEN 'insert'
                 WHEN previous_deleted <> 0 THEN 'restore' ELSE 'update' END,
            'unknown', event_key, updated_at_ms,
            jsonb_build_object('deviceId', device_id, 'client', 'unknown', 'os', 'unknown')
        FROM (
            SELECT log.*, metadata.generation,
                md5(log.account_id || ':' || metadata.generation || ':' || log.version::text) AS event_key,
                lag(log.version) OVER entity AS previous_version,
                lag(log.deleted) OVER entity AS previous_deleted,
                lag(log.snapshot) OVER entity AS previous_snapshot
            FROM change_log log JOIN sync_metadata metadata ON metadata.account_id = log.account_id
            WHERE log.account_id = tenant
            WINDOW entity AS (PARTITION BY log.table_name, log.id ORDER BY log.version)
        ) history;
    END LOOP;
    PERFORM set_config('recoow.tenant_id', '', true);
END $$;

ALTER TABLE change_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE change_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_events
    USING (account_id = current_setting('recoow.tenant_id', true))
    WITH CHECK (account_id = current_setting('recoow.tenant_id', true));

ALTER TABLE sessions ADD COLUMN access TEXT NOT NULL DEFAULT 'read_only'
    CHECK (access IN ('read_only', 'read_write'));
