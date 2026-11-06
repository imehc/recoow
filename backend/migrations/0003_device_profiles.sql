ALTER TABLE devices ADD COLUMN profile JSONB NOT NULL DEFAULT 'null'::jsonb;
CREATE INDEX idx_devices_local_source ON devices (account_id, (profile->>'localDeviceId'));
-- A historical association is a snapshot, not a live foreign key to a revocable device.
ALTER TABLE change_events ADD COLUMN original_registered_device_id TEXT;
