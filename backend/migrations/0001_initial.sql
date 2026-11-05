CREATE TABLE IF NOT EXISTS account (
		id                TEXT PRIMARY KEY,
		recovery_key_hash TEXT NOT NULL UNIQUE,
		created_at        TEXT NOT NULL,
		next_version      BIGINT NOT NULL
	);
CREATE UNIQUE INDEX IF NOT EXISTS idx_account_recovery ON account (recovery_key_hash);
CREATE TABLE IF NOT EXISTS devices (
		id           TEXT PRIMARY KEY,
		account_id   TEXT NOT NULL,
		name         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		is_owner     BIGINT NOT NULL,
		created_at   TEXT NOT NULL,
		last_seen_at TEXT NOT NULL
	);
CREATE TABLE IF NOT EXISTS change_log (
		account_id    TEXT NOT NULL,
		version       BIGINT NOT NULL,
		table_name    TEXT NOT NULL,
		id            TEXT NOT NULL,
		operation     TEXT NOT NULL,
		updated_at_ms BIGINT NOT NULL,
		device_id     TEXT NOT NULL,
		deleted       BIGINT NOT NULL,
		deleted_at_ms BIGINT NOT NULL,
		snapshot      JSONB,
		data_version  BIGINT NOT NULL DEFAULT 1,
		PRIMARY KEY (account_id, version)
	);
CREATE TABLE IF NOT EXISTS media_objects (
		account_id   TEXT NOT NULL,
		key          TEXT NOT NULL,
		checksum     TEXT NOT NULL,
		size         BIGINT NOT NULL,
		content_type TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		PRIMARY KEY (account_id, key)
	);
CREATE INDEX IF NOT EXISTS idx_change_log_entity_version ON change_log (account_id, table_name, id, version);
CREATE TABLE IF NOT EXISTS sync_metadata (
		account_id TEXT PRIMARY KEY,
		generation TEXT NOT NULL,
		min_cursor BIGINT NOT NULL DEFAULT 0
	);
CREATE TABLE IF NOT EXISTS mutation_receipts (
		account_id TEXT NOT NULL,
		mutation_id TEXT NOT NULL,
		request_hash TEXT NOT NULL,
		result_json TEXT NOT NULL,
		PRIMARY KEY (account_id, mutation_id)
	);
CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, account_id TEXT NOT NULL UNIQUE REFERENCES account(id), username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, disabled BOOLEAN NOT NULL DEFAULT false);
CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE, token_hash TEXT NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id);
CREATE TABLE IF NOT EXISTS "anniversaries" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "category" TEXT, "created_at" BIGINT, "date_calendar" TEXT, "deleted_at" BIGINT, "device_id" TEXT, "is_enabled" BOOLEAN, "is_yearly" BOOLEAN, "lead_time_minutes" BIGINT, "note" TEXT, "occurred_at" BIGINT, "reminder_time_minutes" BIGINT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_anniversaries_live" ON "anniversaries" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_anniversaries_occurred_at" ON "anniversaries" (tenant_id, "occurred_at", id);
ALTER TABLE "anniversaries" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "anniversaries" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "anniversaries";
CREATE POLICY tenant_isolation ON "anniversaries" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "bills" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "category" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "discount_amount_cents" BIGINT, "end_location" TEXT, "final_amount_cents" BIGINT, "group_buy_valid_until" BIGINT, "image_asset_id" TEXT, "note" TEXT, "occurred_at" BIGINT, "original_amount_cents" BIGINT, "payment_method" TEXT, "redeemed_at" BIGINT, "refund_reason" TEXT, "settlement_status" TEXT, "start_location" TEXT, "stored_value_account_id" TEXT, "stored_value_effects" TEXT, "title" TEXT, "transaction_type" TEXT, "transport_lines" TEXT, "updated_at" BIGINT, "utility_account_number" TEXT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_bills_live" ON "bills" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_bills_occurred_at" ON "bills" (tenant_id, "occurred_at", id);
ALTER TABLE "bills" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "bills" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "bills";
CREATE POLICY tenant_isolation ON "bills" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "decision_choice_records" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "collection_id" TEXT, "collection_title" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "option_custom_info" TEXT, "option_detail" TEXT, "option_id" TEXT, "option_image_asset_id" TEXT, "option_title" TEXT, "selected_at" BIGINT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_decision_choice_records_live" ON "decision_choice_records" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_decision_choice_records_selected_at" ON "decision_choice_records" (tenant_id, "selected_at", id);
ALTER TABLE "decision_choice_records" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "decision_choice_records" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "decision_choice_records";
CREATE POLICY tenant_isolation ON "decision_choice_records" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "decision_collections" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "note" TEXT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_decision_collections_live" ON "decision_collections" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "decision_collections" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "decision_collections" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "decision_collections";
CREATE POLICY tenant_isolation ON "decision_collections" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "decision_options" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "collection_id" TEXT, "created_at" BIGINT, "custom_info" TEXT, "deleted_at" BIGINT, "detail" TEXT, "device_id" TEXT, "image_asset_id" TEXT, "is_enabled" BOOLEAN, "title" TEXT, "updated_at" BIGINT, "weight" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_decision_options_live" ON "decision_options" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "decision_options" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "decision_options" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "decision_options";
CREATE POLICY tenant_isolation ON "decision_options" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "diary_entries" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "content" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "horizontal_accuracy" DOUBLE PRECISION, "latitude" DOUBLE PRECISION, "longitude" DOUBLE PRECISION, "mood" TEXT, "occurred_at" BIGINT, "tags_json" TEXT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_diary_entries_live" ON "diary_entries" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_diary_entries_occurred_at" ON "diary_entries" (tenant_id, "occurred_at", id);
ALTER TABLE "diary_entries" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "diary_entries" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "diary_entries";
CREATE POLICY tenant_isolation ON "diary_entries" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "diary_links" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "diary_id" TEXT, "snapshot_json" TEXT, "source_icon" TEXT, "source_id" TEXT, "source_occurred_at" BIGINT, "source_subtitle" TEXT, "source_title" TEXT, "source_type" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_diary_links_live" ON "diary_links" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "diary_links" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "diary_links" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "diary_links";
CREATE POLICY tenant_isolation ON "diary_links" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "diary_tags" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "name" TEXT, "note" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_diary_tags_live" ON "diary_tags" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "diary_tags" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "diary_tags" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "diary_tags";
CREATE POLICY tenant_isolation ON "diary_tags" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "food_day_records" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "created_at" BIGINT, "day_start_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_food_day_records_live" ON "food_day_records" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_food_day_records_day_start_at" ON "food_day_records" (tenant_id, "day_start_at", id);
ALTER TABLE "food_day_records" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "food_day_records" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "food_day_records";
CREATE POLICY tenant_isolation ON "food_day_records" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "food_entries" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "bill_id" TEXT, "bill_ids_json" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "meal_kind" TEXT, "note" TEXT, "occurred_at" BIGINT, "portion" TEXT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_food_entries_live" ON "food_entries" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_food_entries_occurred_at" ON "food_entries" (tenant_id, "occurred_at", id);
ALTER TABLE "food_entries" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "food_entries" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "food_entries";
CREATE POLICY tenant_isolation ON "food_entries" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "item_categories" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "name" TEXT, "note" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_item_categories_live" ON "item_categories" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "item_categories" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "item_categories" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "item_categories";
CREATE POLICY tenant_isolation ON "item_categories" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "media_assets" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "byte_count" BIGINT, "checksum" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "height" BIGINT, "kind" TEXT, "mime_type" TEXT, "sort_order" BIGINT, "storage_backend" TEXT, "storage_key" TEXT, "title" TEXT, "updated_at" BIGINT, "width" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_media_assets_live" ON "media_assets" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "media_assets" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "media_assets" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "media_assets";
CREATE POLICY tenant_isolation ON "media_assets" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "media_attachments" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "asset_id" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "duration_seconds" DOUBLE PRECISION, "height" BIGINT, "kind" TEXT, "mime_type" TEXT, "owner_id" TEXT, "owner_type" TEXT, "sort_order" BIGINT, "title" TEXT, "updated_at" BIGINT, "width" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_media_attachments_live" ON "media_attachments" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "media_attachments" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "media_attachments" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "media_attachments";
CREATE POLICY tenant_isolation ON "media_attachments" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "reminder_completions" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "completed_at" BIGINT, "created_at" BIGINT, "date_key" TEXT, "deleted_at" BIGINT, "device_id" TEXT, "kind" TEXT, "note" TEXT, "reminder_id" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_reminder_completions_live" ON "reminder_completions" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "reminder_completions" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "reminder_completions" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "reminder_completions";
CREATE POLICY tenant_isolation ON "reminder_completions" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "reminders" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "completed_at" BIGINT, "completed_date_keys" TEXT, "completion_records" TEXT, "continuous_days" BIGINT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "early_end_reason" TEXT, "end_at" BIGINT, "ended_early_at" BIGINT, "image_asset_id" TEXT, "imported_completed_days" BIGINT, "is_enabled" BOOLEAN, "lead_time_minutes" BIGINT, "memory_icon" TEXT, "note" TEXT, "pause_reason" TEXT, "paused_at" BIGINT, "reminder_time_minutes" BIGINT, "schedule_kind" TEXT, "scheduled_at" BIGINT, "title" TEXT, "total_paused_milliseconds" BIGINT, "updated_at" BIGINT, "weekdays" TEXT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_reminders_live" ON "reminders" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "reminders" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "reminders" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "reminders";
CREATE POLICY tenant_isolation ON "reminders" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "stored_items" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "category_id" TEXT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "image_asset_id" TEXT, "location" TEXT, "note" TEXT, "search_keywords" TEXT, "tags" TEXT, "title" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_stored_items_live" ON "stored_items" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "stored_items" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "stored_items" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "stored_items";
CREATE POLICY tenant_isolation ON "stored_items" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "stored_value_accounts" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "balance_cents" BIGINT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "kind" TEXT, "name" TEXT, "note" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_stored_value_accounts_live" ON "stored_value_accounts" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "stored_value_accounts" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "stored_value_accounts" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "stored_value_accounts";
CREATE POLICY tenant_isolation ON "stored_value_accounts" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "stored_value_adjustments" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "account_id" TEXT, "amount_cents" BIGINT, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "reason" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_stored_value_adjustments_live" ON "stored_value_adjustments" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "stored_value_adjustments" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "stored_value_adjustments" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "stored_value_adjustments";
CREATE POLICY tenant_isolation ON "stored_value_adjustments" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "track_points" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "altitude" DOUBLE PRECISION, "course_deg" DOUBLE PRECISION, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "horizontal_acc" DOUBLE PRECISION, "latitude" DOUBLE PRECISION, "longitude" DOUBLE PRECISION, "speed_mps" DOUBLE PRECISION, "timestamp_ms" BIGINT, "track_id" TEXT, "updated_at" BIGINT, "vertical_acc" DOUBLE PRECISION, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_track_points_live" ON "track_points" (tenant_id, tombstone_at_ms, id);
ALTER TABLE "track_points" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "track_points" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "track_points";
CREATE POLICY tenant_isolation ON "track_points" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "track_segments" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "avg_speed_mps" DOUBLE PRECISION, "confidence" DOUBLE PRECISION, "created_at" BIGINT, "deleted_at" BIGINT, "device_id" TEXT, "distance_m" DOUBLE PRECISION, "duration_s" BIGINT, "ended_at" BIGINT, "max_speed_mps" DOUBLE PRECISION, "motion_type" TEXT, "source" TEXT, "started_at" BIGINT, "track_id" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_track_segments_live" ON "track_segments" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_track_segments_started_at" ON "track_segments" (tenant_id, "started_at", id);
ALTER TABLE "track_segments" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "track_segments" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "track_segments";
CREATE POLICY tenant_isolation ON "track_segments" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
CREATE TABLE IF NOT EXISTS "tracks" (tenant_id TEXT NOT NULL REFERENCES account(id), id TEXT NOT NULL, extras JSONB NOT NULL DEFAULT '{}', present_fields TEXT[], server_updated_at_ms BIGINT NOT NULL, sync_device_id TEXT NOT NULL, tombstone_at_ms BIGINT NOT NULL DEFAULT 0, server_version BIGINT NOT NULL, data_version INTEGER NOT NULL DEFAULT 1, "avg_speed_mps" DOUBLE PRECISION, "created_at" BIGINT, "deleted_at" BIGINT, "desired_accuracy_m" BIGINT, "device_id" TEXT, "distance_m" DOUBLE PRECISION, "duration_s" BIGINT, "ended_at" BIGINT, "max_speed_mps" DOUBLE PRECISION, "name" TEXT, "note" TEXT, "started_at" BIGINT, "status" TEXT, "updated_at" BIGINT, PRIMARY KEY (tenant_id, id));
CREATE INDEX IF NOT EXISTS "idx_tracks_live" ON "tracks" (tenant_id, tombstone_at_ms, id);
CREATE INDEX IF NOT EXISTS "idx_tracks_started_at" ON "tracks" (tenant_id, "started_at", id);
ALTER TABLE "tracks" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "tracks" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "tracks";
CREATE POLICY tenant_isolation ON "tracks" USING (tenant_id = current_setting('recoow.tenant_id', true)) WITH CHECK (tenant_id = current_setting('recoow.tenant_id', true));
ALTER TABLE "bills" ADD COLUMN IF NOT EXISTS "_reference_stored_value_account_id" TEXT GENERATED ALWAYS AS (NULLIF("stored_value_account_id", '')) STORED;
ALTER TABLE "bills" DROP CONSTRAINT IF EXISTS "fk_bills_stored_value_account_id";
ALTER TABLE "bills" ADD CONSTRAINT "fk_bills_stored_value_account_id" FOREIGN KEY (tenant_id, "_reference_stored_value_account_id") REFERENCES "stored_value_accounts" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "bills" ADD COLUMN IF NOT EXISTS "_reference_image_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("image_asset_id", '')) STORED;
ALTER TABLE "bills" DROP CONSTRAINT IF EXISTS "fk_bills_image_asset_id";
ALTER TABLE "bills" ADD CONSTRAINT "fk_bills_image_asset_id" FOREIGN KEY (tenant_id, "_reference_image_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "decision_choice_records" ADD COLUMN IF NOT EXISTS "_reference_option_image_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("option_image_asset_id", '')) STORED;
ALTER TABLE "decision_choice_records" DROP CONSTRAINT IF EXISTS "fk_decision_choice_records_option_image_asset_id";
ALTER TABLE "decision_choice_records" ADD CONSTRAINT "fk_decision_choice_records_option_image_asset_id" FOREIGN KEY (tenant_id, "_reference_option_image_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "decision_options" ADD COLUMN IF NOT EXISTS "_reference_collection_id" TEXT GENERATED ALWAYS AS (NULLIF("collection_id", '')) STORED;
ALTER TABLE "decision_options" DROP CONSTRAINT IF EXISTS "fk_decision_options_collection_id";
ALTER TABLE "decision_options" ADD CONSTRAINT "fk_decision_options_collection_id" FOREIGN KEY (tenant_id, "_reference_collection_id") REFERENCES "decision_collections" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "decision_options" ADD COLUMN IF NOT EXISTS "_reference_image_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("image_asset_id", '')) STORED;
ALTER TABLE "decision_options" DROP CONSTRAINT IF EXISTS "fk_decision_options_image_asset_id";
ALTER TABLE "decision_options" ADD CONSTRAINT "fk_decision_options_image_asset_id" FOREIGN KEY (tenant_id, "_reference_image_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "diary_links" ADD COLUMN IF NOT EXISTS "_reference_diary_id" TEXT GENERATED ALWAYS AS (NULLIF("diary_id", '')) STORED;
ALTER TABLE "diary_links" DROP CONSTRAINT IF EXISTS "fk_diary_links_diary_id";
ALTER TABLE "diary_links" ADD CONSTRAINT "fk_diary_links_diary_id" FOREIGN KEY (tenant_id, "_reference_diary_id") REFERENCES "diary_entries" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "food_entries" ADD COLUMN IF NOT EXISTS "_reference_bill_id" TEXT GENERATED ALWAYS AS (NULLIF("bill_id", '')) STORED;
ALTER TABLE "food_entries" DROP CONSTRAINT IF EXISTS "fk_food_entries_bill_id";
ALTER TABLE "food_entries" ADD CONSTRAINT "fk_food_entries_bill_id" FOREIGN KEY (tenant_id, "_reference_bill_id") REFERENCES "bills" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "media_attachments" ADD COLUMN IF NOT EXISTS "_reference_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("asset_id", '')) STORED;
ALTER TABLE "media_attachments" DROP CONSTRAINT IF EXISTS "fk_media_attachments_asset_id";
ALTER TABLE "media_attachments" ADD CONSTRAINT "fk_media_attachments_asset_id" FOREIGN KEY (tenant_id, "_reference_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "reminder_completions" ADD COLUMN IF NOT EXISTS "_reference_reminder_id" TEXT GENERATED ALWAYS AS (NULLIF("reminder_id", '')) STORED;
ALTER TABLE "reminder_completions" DROP CONSTRAINT IF EXISTS "fk_reminder_completions_reminder_id";
ALTER TABLE "reminder_completions" ADD CONSTRAINT "fk_reminder_completions_reminder_id" FOREIGN KEY (tenant_id, "_reference_reminder_id") REFERENCES "reminders" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "reminders" ADD COLUMN IF NOT EXISTS "_reference_image_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("image_asset_id", '')) STORED;
ALTER TABLE "reminders" DROP CONSTRAINT IF EXISTS "fk_reminders_image_asset_id";
ALTER TABLE "reminders" ADD CONSTRAINT "fk_reminders_image_asset_id" FOREIGN KEY (tenant_id, "_reference_image_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "stored_items" ADD COLUMN IF NOT EXISTS "_reference_category_id" TEXT GENERATED ALWAYS AS (NULLIF("category_id", '')) STORED;
ALTER TABLE "stored_items" DROP CONSTRAINT IF EXISTS "fk_stored_items_category_id";
ALTER TABLE "stored_items" ADD CONSTRAINT "fk_stored_items_category_id" FOREIGN KEY (tenant_id, "_reference_category_id") REFERENCES "item_categories" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "stored_items" ADD COLUMN IF NOT EXISTS "_reference_image_asset_id" TEXT GENERATED ALWAYS AS (NULLIF("image_asset_id", '')) STORED;
ALTER TABLE "stored_items" DROP CONSTRAINT IF EXISTS "fk_stored_items_image_asset_id";
ALTER TABLE "stored_items" ADD CONSTRAINT "fk_stored_items_image_asset_id" FOREIGN KEY (tenant_id, "_reference_image_asset_id") REFERENCES "media_assets" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "stored_value_adjustments" ADD COLUMN IF NOT EXISTS "_reference_account_id" TEXT GENERATED ALWAYS AS (NULLIF("account_id", '')) STORED;
ALTER TABLE "stored_value_adjustments" DROP CONSTRAINT IF EXISTS "fk_stored_value_adjustments_account_id";
ALTER TABLE "stored_value_adjustments" ADD CONSTRAINT "fk_stored_value_adjustments_account_id" FOREIGN KEY (tenant_id, "_reference_account_id") REFERENCES "stored_value_accounts" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "track_points" ADD COLUMN IF NOT EXISTS "_reference_track_id" TEXT GENERATED ALWAYS AS (NULLIF("track_id", '')) STORED;
ALTER TABLE "track_points" DROP CONSTRAINT IF EXISTS "fk_track_points_track_id";
ALTER TABLE "track_points" ADD CONSTRAINT "fk_track_points_track_id" FOREIGN KEY (tenant_id, "_reference_track_id") REFERENCES "tracks" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE "track_segments" ADD COLUMN IF NOT EXISTS "_reference_track_id" TEXT GENERATED ALWAYS AS (NULLIF("track_id", '')) STORED;
ALTER TABLE "track_segments" DROP CONSTRAINT IF EXISTS "fk_track_segments_track_id";
ALTER TABLE "track_segments" ADD CONSTRAINT "fk_track_segments_track_id" FOREIGN KEY (tenant_id, "_reference_track_id") REFERENCES "tracks" (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
CREATE OR REPLACE VIEW row_state WITH (security_invoker = true) AS SELECT tenant_id AS account_id, 'anniversaries'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "anniversaries" AS entity UNION ALL SELECT tenant_id AS account_id, 'bills'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "bills" AS entity UNION ALL SELECT tenant_id AS account_id, 'decision_choice_records'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "decision_choice_records" AS entity UNION ALL SELECT tenant_id AS account_id, 'decision_collections'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "decision_collections" AS entity UNION ALL SELECT tenant_id AS account_id, 'decision_options'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "decision_options" AS entity UNION ALL SELECT tenant_id AS account_id, 'diary_entries'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "diary_entries" AS entity UNION ALL SELECT tenant_id AS account_id, 'diary_links'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "diary_links" AS entity UNION ALL SELECT tenant_id AS account_id, 'diary_tags'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "diary_tags" AS entity UNION ALL SELECT tenant_id AS account_id, 'food_day_records'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "food_day_records" AS entity UNION ALL SELECT tenant_id AS account_id, 'food_entries'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "food_entries" AS entity UNION ALL SELECT tenant_id AS account_id, 'item_categories'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "item_categories" AS entity UNION ALL SELECT tenant_id AS account_id, 'media_assets'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "media_assets" AS entity UNION ALL SELECT tenant_id AS account_id, 'media_attachments'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "media_attachments" AS entity UNION ALL SELECT tenant_id AS account_id, 'reminder_completions'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "reminder_completions" AS entity UNION ALL SELECT tenant_id AS account_id, 'reminders'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "reminders" AS entity UNION ALL SELECT tenant_id AS account_id, 'stored_items'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "stored_items" AS entity UNION ALL SELECT tenant_id AS account_id, 'stored_value_accounts'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "stored_value_accounts" AS entity UNION ALL SELECT tenant_id AS account_id, 'stored_value_adjustments'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "stored_value_adjustments" AS entity UNION ALL SELECT tenant_id AS account_id, 'track_points'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "track_points" AS entity UNION ALL SELECT tenant_id AS account_id, 'track_segments'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "track_segments" AS entity UNION ALL SELECT tenant_id AS account_id, 'tracks'::text AS table_name, id,
          CASE WHEN present_fields IS NULL THEN NULL ELSE
          (extras || COALESCE((SELECT jsonb_object_agg(key,value) FROM jsonb_each(to_jsonb(entity)) WHERE key = ANY(entity.present_fields)), '{}'::jsonb))::text END AS snapshot,
          server_updated_at_ms AS updated_at_ms, sync_device_id AS device_id, tombstone_at_ms AS deleted_at_ms,
          server_version AS version, data_version FROM "tracks" AS entity;

-- 配对码只保存哈希，可在多服务实例之间原子消费。
CREATE TABLE IF NOT EXISTS device_pairing_codes (code_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES account(id), expires_at TIMESTAMPTZ NOT NULL);
CREATE INDEX IF NOT EXISTS idx_pairing_expiry ON device_pairing_codes (expires_at);

ALTER TABLE change_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE change_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_log USING (account_id = current_setting('recoow.tenant_id', true)) WITH CHECK (account_id = current_setting('recoow.tenant_id', true));

ALTER TABLE mutation_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE mutation_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mutation_receipts USING (account_id = current_setting('recoow.tenant_id', true)) WITH CHECK (account_id = current_setting('recoow.tenant_id', true));

ALTER TABLE sync_metadata ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_metadata FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sync_metadata USING (account_id = current_setting('recoow.tenant_id', true)) WITH CHECK (account_id = current_setting('recoow.tenant_id', true));

ALTER TABLE media_objects ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_objects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON media_objects USING (account_id = current_setting('recoow.tenant_id', true)) WITH CHECK (account_id = current_setting('recoow.tenant_id', true));
