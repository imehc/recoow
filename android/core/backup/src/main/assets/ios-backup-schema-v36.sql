-- iOS interchange schema 36, created by AppMigrator and AppDataTransferService.
-- Runtime backup format, not an Android Room migration. Keep GRDB migration identities intact.
CREATE TABLE _recoow_backup_metadata (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE "anniversaries" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "note" TEXT, "category" TEXT NOT NULL, "occurred_at" INTEGER NOT NULL, "is_yearly" BOOLEAN NOT NULL DEFAULT 1, "lead_time_minutes" INTEGER NOT NULL DEFAULT 0, "is_enabled" BOOLEAN NOT NULL DEFAULT 1, "reminder_time_minutes" INTEGER NOT NULL DEFAULT 540, "date_calendar" TEXT NOT NULL DEFAULT 'gregorian');
CREATE TABLE "bills" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "original_amount_cents" INTEGER NOT NULL, "discount_amount_cents" INTEGER NOT NULL DEFAULT 0, "final_amount_cents" INTEGER NOT NULL, "category" TEXT NOT NULL, "payment_method" TEXT NOT NULL, "note" TEXT, "occurred_at" INTEGER NOT NULL, "image_data" BLOB, "transaction_type" TEXT NOT NULL DEFAULT 'expense', "start_location" TEXT, "end_location" TEXT, "transport_lines" TEXT, "settlement_status" TEXT NOT NULL DEFAULT 'active', "group_buy_valid_until" INTEGER, "redeemed_at" INTEGER, "refund_reason" TEXT, "image_asset_id" TEXT, "stored_value_account_id" TEXT, stored_value_effects TEXT, "utility_account_number" TEXT);
CREATE TABLE "change_log" ("id" INTEGER PRIMARY KEY AUTOINCREMENT, "entity_table" TEXT NOT NULL, "entity_id" TEXT NOT NULL, "operation" TEXT NOT NULL CHECK (operation IN ('insert','update','delete')), "payload_json" TEXT NOT NULL, "client_ts_ms" INTEGER NOT NULL, "attempt_count" INTEGER NOT NULL DEFAULT 0, "last_error" TEXT, "synced_at_ms" INTEGER, mutation_id TEXT);
CREATE TABLE "decision_choice_records" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "collection_id" TEXT NOT NULL, "collection_title" TEXT NOT NULL, "option_id" TEXT NOT NULL, "option_title" TEXT NOT NULL, "option_detail" TEXT, "option_custom_info" TEXT, "option_image_data" BLOB, "selected_at" INTEGER NOT NULL, "option_image_asset_id" TEXT);
CREATE TABLE "decision_collections" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "note" TEXT);
CREATE TABLE "decision_options" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "collection_id" TEXT NOT NULL REFERENCES "decision_collections"("id") ON DELETE CASCADE, "title" TEXT NOT NULL, "detail" TEXT, "custom_info" TEXT, "image_data" BLOB, "weight" INTEGER NOT NULL DEFAULT 1, "is_enabled" BOOLEAN NOT NULL DEFAULT 1, "image_asset_id" TEXT);
CREATE TABLE "diary_entries" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "content" TEXT NOT NULL, "mood" TEXT NOT NULL, "tags_json" TEXT NOT NULL DEFAULT '[]', "occurred_at" INTEGER NOT NULL, "latitude" DOUBLE, "longitude" DOUBLE, "horizontal_accuracy" DOUBLE);
CREATE TABLE "diary_links" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "diary_id" TEXT NOT NULL REFERENCES "diary_entries"("id") ON DELETE CASCADE, "source_type" TEXT NOT NULL, "source_id" TEXT NOT NULL, "source_title" TEXT NOT NULL, "source_subtitle" TEXT, "source_icon" TEXT NOT NULL, "source_occurred_at" INTEGER, "snapshot_json" TEXT);
CREATE TABLE "diary_tags" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "name" TEXT NOT NULL, "note" TEXT);
CREATE TABLE "food_day_records" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "day_start_at" INTEGER NOT NULL UNIQUE);
CREATE TABLE "food_entries" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "meal_kind" TEXT NOT NULL, "portion" TEXT, "note" TEXT, "occurred_at" INTEGER NOT NULL, "bill_id" TEXT REFERENCES "bills"("id") ON DELETE SET NULL, "bill_ids_json" TEXT NOT NULL DEFAULT '[]');
CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY);
CREATE TABLE "item_categories" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "name" TEXT NOT NULL, "note" TEXT);
CREATE TABLE "media_assets" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "kind" TEXT NOT NULL, "storage_backend" TEXT NOT NULL, "storage_key" TEXT NOT NULL, "mime_type" TEXT NOT NULL, "byte_count" INTEGER NOT NULL, "checksum" TEXT NOT NULL, "width" INTEGER, "height" INTEGER, "sort_order" INTEGER NOT NULL DEFAULT 0, "inline_data" BLOB, "title" TEXT);
CREATE TABLE "media_attachments" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "owner_type" TEXT NOT NULL, "owner_id" TEXT NOT NULL, "kind" TEXT NOT NULL, "title" TEXT, "asset_id" TEXT, "data" BLOB NOT NULL, "mime_type" TEXT NOT NULL, "duration_seconds" DOUBLE, "width" INTEGER, "height" INTEGER, "sort_order" INTEGER NOT NULL DEFAULT 0);
CREATE TABLE "reminder_completions" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "reminder_id" TEXT NOT NULL REFERENCES "reminders"("id"), "date_key" TEXT NOT NULL, "completed_at" INTEGER NOT NULL, "kind" TEXT NOT NULL, "note" TEXT, UNIQUE ("reminder_id", "date_key"));
CREATE TABLE "reminders" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "title" TEXT NOT NULL, "note" TEXT, "memory_icon" TEXT NOT NULL, "image_data" BLOB, "scheduled_at" INTEGER NOT NULL, "lead_time_minutes" INTEGER NOT NULL DEFAULT 0, "is_enabled" BOOLEAN NOT NULL DEFAULT 1, "schedule_kind" TEXT NOT NULL DEFAULT 'single', "end_at" INTEGER, "reminder_time_minutes" INTEGER NOT NULL DEFAULT 0, "weekdays" TEXT, "continuous_days" INTEGER NOT NULL DEFAULT 30, "completed_at" INTEGER, "imported_completed_days" INTEGER NOT NULL DEFAULT 0, "completed_date_keys" TEXT, "completion_records" TEXT, "image_asset_id" TEXT, "ended_early_at" INTEGER, "early_end_reason" TEXT, "paused_at" INTEGER, "pause_reason" TEXT, "total_paused_milliseconds" INTEGER);
CREATE TABLE "stored_items" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "category_id" TEXT REFERENCES "item_categories"("id") ON DELETE SET NULL, "title" TEXT NOT NULL, "location" TEXT NOT NULL, "note" TEXT, "tags" TEXT, "search_keywords" TEXT, "image_data" BLOB, "image_asset_id" TEXT);
CREATE TABLE "stored_value_accounts" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "name" TEXT NOT NULL, "kind" TEXT NOT NULL, "balance_cents" INTEGER NOT NULL DEFAULT 0, "note" TEXT);
CREATE TABLE "stored_value_adjustments" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "account_id" TEXT NOT NULL REFERENCES "stored_value_accounts"("id"), "amount_cents" INTEGER NOT NULL, "reason" TEXT NOT NULL);
CREATE TABLE sync_inbox (
    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
    version INTEGER NOT NULL, table_name TEXT NOT NULL, entity_id TEXT NOT NULL,
    envelope_json TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 0, last_error TEXT,
    PRIMARY KEY (scope, version)
);
CREATE TABLE sync_media_jobs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
    direction TEXT NOT NULL, object_key TEXT NOT NULL, checksum TEXT NOT NULL,
    size INTEGER NOT NULL, content_type TEXT NOT NULL, table_name TEXT NOT NULL,
    entity_id TEXT NOT NULL, column_name TEXT NOT NULL, storage_key TEXT,
    version INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
    attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,
    last_error TEXT, UNIQUE (scope, direction, table_name, entity_id, column_name, checksum)
);
CREATE TABLE sync_notification_jobs (
    table_name TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL,
    PRIMARY KEY (table_name, entity_id)
);
CREATE TABLE sync_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
    mutation_id TEXT NOT NULL UNIQUE, table_name TEXT NOT NULL, entity_id TEXT NOT NULL,
    operation TEXT NOT NULL, payload_json TEXT NOT NULL, base_version INTEGER NOT NULL,
    predecessor_id INTEGER, prepared_json TEXT, local_change_id INTEGER,
    status TEXT NOT NULL DEFAULT 'pending', server_version INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0, last_error TEXT,
    capture_pending INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE sync_received_pages (
    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
    page_key TEXT NOT NULL, body BLOB NOT NULL, PRIMARY KEY (scope, page_key)
);
CREATE TABLE sync_rows (
    scope TEXT NOT NULL REFERENCES sync_targets(scope) ON DELETE CASCADE,
    table_name TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL,
    envelope_json TEXT NOT NULL, PRIMARY KEY (scope, table_name, entity_id)
);
CREATE TABLE sync_targets (
    scope TEXT PRIMARY KEY, connection_id TEXT NOT NULL, account_id TEXT NOT NULL,
    generation TEXT NOT NULL, received_cursor INTEGER NOT NULL DEFAULT 0,
    applied_cursor INTEGER NOT NULL DEFAULT 0, bootstrap_watermark INTEGER NOT NULL,
    bootstrap_after INTEGER NOT NULL DEFAULT 0, bootstrap_complete INTEGER NOT NULL DEFAULT 0,
    seeded INTEGER NOT NULL DEFAULT 0, failure_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE "track_points" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "track_id" TEXT NOT NULL REFERENCES "tracks"("id") ON DELETE CASCADE, "latitude" DOUBLE NOT NULL, "longitude" DOUBLE NOT NULL, "altitude" DOUBLE, "horizontal_acc" DOUBLE, "vertical_acc" DOUBLE, "speed_mps" DOUBLE, "course_deg" DOUBLE, "timestamp_ms" INTEGER NOT NULL);
CREATE TABLE "track_segments" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "track_id" TEXT NOT NULL REFERENCES "tracks"("id") ON DELETE CASCADE, "started_at" INTEGER NOT NULL, "ended_at" INTEGER NOT NULL, "distance_m" DOUBLE NOT NULL DEFAULT 0, "duration_s" INTEGER NOT NULL DEFAULT 0, "avg_speed_mps" DOUBLE, "max_speed_mps" DOUBLE, "motion_type" TEXT NOT NULL DEFAULT 'unknown', "source" TEXT NOT NULL DEFAULT 'auto', "confidence" DOUBLE NOT NULL DEFAULT 0);
CREATE TABLE "tracks" ("id" TEXT PRIMARY KEY NOT NULL, "created_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "updated_at" INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)), "deleted_at" INTEGER, "sync_status" INTEGER NOT NULL DEFAULT 0, "device_id" TEXT NOT NULL, "server_version" INTEGER, "name" TEXT NOT NULL, "started_at" INTEGER NOT NULL, "ended_at" INTEGER, "desired_accuracy_m" INTEGER NOT NULL, "distance_m" DOUBLE NOT NULL DEFAULT 0, "duration_s" INTEGER NOT NULL DEFAULT 0, "avg_speed_mps" DOUBLE, "max_speed_mps" DOUBLE, "note" TEXT, "status" TEXT NOT NULL DEFAULT 'finished');
CREATE INDEX "idx_anniversaries_occurred_at" ON "anniversaries"("occurred_at");
CREATE INDEX "idx_anniversaries_updated_at" ON "anniversaries"("updated_at");
CREATE INDEX "idx_bills_image_asset" ON "bills"("image_asset_id");
CREATE INDEX "idx_bills_occurred_at" ON "bills"("occurred_at");
CREATE INDEX "idx_bills_stored_value_account_id" ON "bills"("stored_value_account_id");
CREATE INDEX "idx_bills_transaction_type" ON "bills"("transaction_type");
CREATE INDEX "idx_bills_updated_at" ON "bills"("updated_at");
CREATE UNIQUE INDEX idx_change_log_mutation_id ON change_log (mutation_id);
CREATE INDEX "idx_change_log_synced_at_ms" ON "change_log"("synced_at_ms");
CREATE INDEX "idx_decision_choice_records_collection_id" ON "decision_choice_records"("collection_id");
CREATE INDEX "idx_decision_choice_records_option_image_asset" ON "decision_choice_records"("option_image_asset_id");
CREATE INDEX "idx_decision_choice_records_selected_at" ON "decision_choice_records"("selected_at");
CREATE INDEX "idx_decision_collections_updated_at" ON "decision_collections"("updated_at");
CREATE INDEX "idx_decision_options_collection_id" ON "decision_options"("collection_id");
CREATE INDEX "idx_decision_options_image_asset" ON "decision_options"("image_asset_id");
CREATE INDEX "idx_diary_entries_occurred_at" ON "diary_entries"("occurred_at");
CREATE INDEX "idx_diary_entries_updated_at" ON "diary_entries"("updated_at");
CREATE INDEX "idx_diary_links_diary_id" ON "diary_links"("diary_id");
CREATE INDEX "idx_diary_links_source" ON "diary_links"("source_type", "source_id");
CREATE INDEX "idx_diary_links_updated_at" ON "diary_links"("updated_at");
CREATE INDEX "idx_diary_tags_name" ON "diary_tags"("name");
CREATE INDEX "idx_diary_tags_updated_at" ON "diary_tags"("updated_at");
CREATE INDEX "idx_food_day_records_day_start_at" ON "food_day_records"("day_start_at");
CREATE INDEX "idx_food_day_records_updated_at" ON "food_day_records"("updated_at");
CREATE INDEX "idx_food_entries_bill_id" ON "food_entries"("bill_id");
CREATE INDEX "idx_food_entries_meal_kind" ON "food_entries"("meal_kind");
CREATE INDEX "idx_food_entries_occurred_at" ON "food_entries"("occurred_at");
CREATE INDEX "idx_food_entries_updated_at" ON "food_entries"("updated_at");
CREATE INDEX "idx_item_categories_name" ON "item_categories"("name");
CREATE INDEX "idx_media_assets_checksum" ON "media_assets"("checksum");
CREATE INDEX "idx_media_assets_sort_order" ON "media_assets"("sort_order");
CREATE INDEX "idx_media_assets_updated_at" ON "media_assets"("updated_at");
CREATE INDEX "idx_media_attachments_asset" ON "media_attachments"("asset_id");
CREATE INDEX "idx_media_attachments_owner" ON "media_attachments"("owner_type", "owner_id");
CREATE INDEX "idx_media_attachments_updated_at" ON "media_attachments"("updated_at");
CREATE INDEX "idx_reminders_completed_at" ON "reminders"("completed_at");
CREATE INDEX "idx_reminders_image_asset" ON "reminders"("image_asset_id");
CREATE INDEX "idx_reminders_scheduled_at" ON "reminders"("scheduled_at");
CREATE INDEX "idx_reminders_updated_at" ON "reminders"("updated_at");
CREATE INDEX "idx_stored_items_category_id" ON "stored_items"("category_id");
CREATE INDEX "idx_stored_items_image_asset" ON "stored_items"("image_asset_id");
CREATE INDEX "idx_stored_items_updated_at" ON "stored_items"("updated_at");
CREATE INDEX "idx_stored_value_accounts_updated_at" ON "stored_value_accounts"("updated_at");
CREATE INDEX "idx_stored_value_adjustments_account" ON "stored_value_adjustments"("account_id");
CREATE INDEX idx_sync_inbox_pending ON sync_inbox (scope, applied, table_name, version);
CREATE INDEX idx_sync_outbox_pending ON sync_outbox (scope, status, table_name, entity_id, id);
CREATE INDEX "idx_track_points_track_id_timestamp_ms" ON "track_points"("track_id", "timestamp_ms");
CREATE INDEX "idx_track_segments_track_id_started_at" ON "track_segments"("track_id", "started_at");
CREATE INDEX "idx_tracks_started_at_desc" ON "tracks"("started_at");
CREATE INDEX "idx_tracks_updated_at" ON "tracks"("updated_at");
INSERT INTO grdb_migrations(identifier) VALUES ('v1_initial_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v2_tools_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v3_decision_choice_history_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v4_reminders_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v5_bills_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v6_bill_transaction_type_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v7_check_ins_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v8_check_in_imported_progress_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v9_check_in_occurrence_completion_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v10_anniversaries_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v11_anniversary_date_calendar_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v12_check_in_completion_records_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v13_diary_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v14_media_attachments_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v15_media_attachment_sort_order_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v16_diary_media_schema_reset');
INSERT INTO grdb_migrations(identifier) VALUES ('v17_bill_transport_locations_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v18_track_segments_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v19_bill_transport_lines_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v20_food_journal_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v21_food_entry_bill_link_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v22_food_day_records_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v23_bill_settlement_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v24_bill_redeemed_at');
INSERT INTO grdb_migrations(identifier) VALUES ('v25_bill_refund_reason');
INSERT INTO grdb_migrations(identifier) VALUES ('v26_media_assets_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v27_image_asset_references_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v28_media_asset_sort_order_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v29_food_entry_multiple_bills_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v30_stored_value_accounts_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v31_reminder_early_end_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v32_stored_value_recharge_original_balance_backfill');
INSERT INTO grdb_migrations(identifier) VALUES ('v33_reminder_pause_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v34_full_sync_schema');
INSERT INTO grdb_migrations(identifier) VALUES ('v35_bill_utility_account_number');
INSERT INTO grdb_migrations(identifier) VALUES ('v36_media_asset_title');
