package store

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"testing"
)

// TestMultiAccountIsolation 校验同一实例内两个账号的数据集、版本号空间、
// 媒体与导出完全隔离——这是多人共用一个后端的最低安全边界。
func TestMultiAccountIsolation(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, firstAccount string) {
		second, err := storage.InitAccount([]byte("recovery-hash-second"))
		if err != nil {
			test.Fatalf("init second account: %v", err)
		}
		if second.ID == firstAccount {
			test.Fatal("second account reused first account id")
		}

		// 同表同 id 在两个账号里各自写入，互不覆盖。
		firstResult := pushRevision(test, storage, firstAccount,
			revisionItem("diary_entries", "shared-id", "mutation-a", 0, map[string]any{"title": "first"}), PushApplied)
		secondResult := pushRevision(test, storage, second.ID,
			revisionItem("diary_entries", "shared-id", "mutation-b", 0, map[string]any{"title": "second"}), PushApplied)

		if firstResult.Version != 1 || secondResult.Version != 1 {
			test.Fatalf("version spaces must be per account: %d / %d", firstResult.Version, secondResult.Version)
		}
		if title := liveSnapshot(test, storage, firstAccount, "diary_entries", "shared-id"); title["title"] != "first" {
			test.Fatalf("first account snapshot overwritten: %+v", title)
		}
		if title := liveSnapshot(test, storage, second.ID, "diary_entries", "shared-id"); title["title"] != "second" {
			test.Fatalf("second account snapshot overwritten: %+v", title)
		}

		// 增量日志不得跨账号泄露。
		firstChanges, _, err := storage.Pull(firstAccount, 0, 100)
		if err != nil {
			test.Fatal(err)
		}
		if len(firstChanges) != 1 || firstChanges[0].ID != "shared-id" {
			test.Fatalf("first account changelog polluted: %+v", firstChanges)
		}
		for _, change := range firstChanges {
			if change.AccountID != firstAccount {
				test.Fatalf("changelog leaked another account: %+v", change)
			}
		}

		// 媒体同名 key 在两个账号下是两份独立对象。
		if err := storage.PutMedia(firstAccount, "media/same.png", "checksum-first", "image/png", 10); err != nil {
			test.Fatal(err)
		}
		if err := storage.PutMedia(second.ID, "media/same.png", "checksum-second", "image/png", 20); err != nil {
			test.Fatal(err)
		}
		if object, err := storage.GetMedia(firstAccount, "media/same.png"); err != nil || object.Checksum != "checksum-first" {
			test.Fatalf("first account media wrong: %+v %v", object, err)
		}
		if object, err := storage.GetMedia(second.ID, "media/same.png"); err != nil || object.Checksum != "checksum-second" {
			test.Fatalf("second account media wrong: %+v %v", object, err)
		}

		// 导出必须以账号为界。
		dumper, ok := storage.(SQLDumper)
		if !ok {
			test.Fatal("store must support SQL export")
		}
		var dump bytes.Buffer
		if err := dumper.ExportSQL(&dump, second.ID); err != nil {
			test.Fatal(err)
		}
		if !bytes.Contains(dump.Bytes(), []byte("checksum-second")) {
			test.Fatal("export missing own media")
		}
		if bytes.Contains(dump.Bytes(), []byte("checksum-first")) || bytes.Contains(dump.Bytes(), []byte("first")) {
			test.Fatal("export leaked another account's data")
		}

		// 未知账号一律拒绝，不能退化为"默认账号"。
		if _, err := storage.Rows("missing-account", "bills"); err == nil {
			test.Fatal("unknown account should be rejected")
		}
		if _, _, err := storage.Pull("missing-account", 0, 10); err == nil {
			test.Fatal("unknown account pull should be rejected")
		}
		if _, err := storage.Push("missing-account", []PushItem{item("bills", "x", OpInsert, 1, "dev", nil)}); err == nil {
			test.Fatal("unknown account push should be rejected")
		}
	})
}

// TestMultiAccountDevicesAndCount 校验设备归属与账号计数。
func TestMultiAccountDevicesAndCount(t *testing.T) {
	st := NewMemoryStore()
	if count, err := st.CountAccounts(); err != nil || count != 0 {
		t.Fatalf("expected empty store, got %d %v", count, err)
	}
	first := mustAccount(t, st)
	second, err := st.InitAccount([]byte("another-hash"))
	if err != nil {
		t.Fatal(err)
	}
	if count, _ := st.CountAccounts(); count != 2 {
		t.Fatalf("expected 2 accounts, got %d", count)
	}
	if _, err := st.CreateDevice(first, "iPhone", []byte("token-first"), true); err != nil {
		t.Fatal(err)
	}
	if _, err := st.CreateDevice(second.ID, "iPad", []byte("token-second"), true); err != nil {
		t.Fatal(err)
	}
	firstDevices, _ := st.ListDevices(first)
	secondDevices, _ := st.ListDevices(second.ID)
	if len(firstDevices) != 1 || len(secondDevices) != 1 {
		t.Fatalf("device lists not isolated: %d / %d", len(firstDevices), len(secondDevices))
	}
	if firstDevices[0].AccountID != first || secondDevices[0].AccountID != second.ID {
		t.Fatal("device bound to wrong account")
	}
	if _, err := st.InitAccount([]byte("another-hash")); err != ErrRecoveryKeyTaken {
		t.Fatalf("duplicate recovery key should be rejected, got %v", err)
	}
}

// legacyV3DDL 是加入多账号之前（user_version=3）的表结构，用于验证升级路径。
var legacyV3DDL = []string{
	`CREATE TABLE account (
		id                TEXT PRIMARY KEY,
		recovery_key_hash TEXT NOT NULL,
		created_at        TEXT NOT NULL,
		next_version      INTEGER NOT NULL
	)`,
	`CREATE TABLE devices (
		id           TEXT PRIMARY KEY,
		account_id   TEXT NOT NULL,
		name         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		is_owner     INTEGER NOT NULL,
		created_at   TEXT NOT NULL,
		last_seen_at TEXT NOT NULL
	)`,
	`CREATE TABLE row_state (
		table_name    TEXT NOT NULL,
		id            TEXT NOT NULL,
		snapshot      TEXT,
		updated_at_ms INTEGER NOT NULL,
		device_id     TEXT NOT NULL,
		deleted_at_ms INTEGER NOT NULL,
		version       INTEGER NOT NULL,
		data_version  INTEGER NOT NULL DEFAULT 1,
		PRIMARY KEY (table_name, id)
	)`,
	`CREATE INDEX idx_row_state_live ON row_state (table_name, deleted_at_ms)`,
	`CREATE TABLE change_log (
		version       INTEGER PRIMARY KEY,
		table_name    TEXT NOT NULL,
		id            TEXT NOT NULL,
		operation     TEXT NOT NULL,
		updated_at_ms INTEGER NOT NULL,
		device_id     TEXT NOT NULL,
		deleted       INTEGER NOT NULL,
		deleted_at_ms INTEGER NOT NULL,
		snapshot      TEXT,
		data_version  INTEGER NOT NULL DEFAULT 1
	)`,
	`CREATE INDEX idx_change_log_entity_version ON change_log (table_name, id, version)`,
	`CREATE TABLE media_objects (
		key          TEXT PRIMARY KEY,
		checksum     TEXT NOT NULL,
		size         INTEGER NOT NULL,
		content_type TEXT NOT NULL,
		created_at   TEXT NOT NULL
	)`,
	`CREATE TABLE sync_metadata (
		id INTEGER PRIMARY KEY CHECK (id = 1),
		generation TEXT NOT NULL,
		min_cursor INTEGER NOT NULL DEFAULT 0
	)`,
	`CREATE TABLE mutation_receipts (
		account_id TEXT NOT NULL,
		mutation_id TEXT NOT NULL,
		request_hash TEXT NOT NULL,
		result_json TEXT NOT NULL,
		PRIMARY KEY (account_id, mutation_id)
	)`,
}

// TestLegacySingleAccountUpgrade 校验旧单账号库升级到多账号结构后数据不丢、
// 归属正确，而且能在同一库里再建第二个账号而不串数据。
func TestLegacySingleAccountUpgrade(t *testing.T) {
	directory := t.TempDir()
	db, err := sql.Open("sqlite", "file:"+filepath.Join(directory, "recoow.db")+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range legacyV3DDL {
		if _, err := db.Exec(statement); err != nil {
			t.Fatalf("%s: %v", statement, err)
		}
	}
	snapshot, _ := json.Marshal(map[string]any{"title": "legacy bill"})
	statements := []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO account (id, recovery_key_hash, created_at, next_version) VALUES (?, ?, ?, ?)`,
			[]any{"legacy-account", "deadbeef", "2026-01-01T00:00:00Z", 4}},
		{`INSERT INTO devices (id, account_id, name, token_hash, is_owner, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
			[]any{"legacy-device", "legacy-account", "旧手机", "746f6b656e", 1, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"}},
		{`INSERT INTO row_state (table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			[]any{"bills", "b1", string(snapshot), 100, "legacy-device", 0, 3, 1}},
		{`INSERT INTO change_log (version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			[]any{3, "bills", "b1", "insert", 100, "legacy-device", 0, 0, string(snapshot), 1}},
		{`INSERT INTO media_objects (key, checksum, size, content_type, created_at) VALUES (?, ?, ?, ?, ?)`,
			[]any{"media/legacy.png", "legacy-checksum", 12, "image/png", "2026-01-01T00:00:00Z"}},
		{`INSERT INTO sync_metadata (id, generation, min_cursor) VALUES (1, ?, 0)`, []any{"legacy-generation"}},
		{`PRAGMA user_version=3`, nil},
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement.sql, statement.args...); err != nil {
			t.Fatalf("%s: %v", statement.sql, err)
		}
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	storage, err := NewSQLiteStore(directory)
	if err != nil {
		t.Fatalf("upgrade failed: %v", err)
	}
	defer storage.Close()

	rows, err := storage.Rows("legacy-account", "bills")
	if err != nil || len(rows) != 1 || rows[0].ID != "b1" {
		t.Fatalf("legacy rows lost: %+v %v", rows, err)
	}
	changes, cursor, err := storage.Pull("legacy-account", 0, 10)
	if err != nil || len(changes) != 1 || cursor != 3 {
		t.Fatalf("legacy changelog lost: %+v %d %v", changes, cursor, err)
	}
	if object, err := storage.GetMedia("legacy-account", "media/legacy.png"); err != nil || object.Checksum != "legacy-checksum" {
		t.Fatalf("legacy media lost: %+v %v", object, err)
	}
	state, err := storage.SyncState("legacy-account")
	if err != nil || state.Generation != "legacy-generation" || state.Head != 3 {
		t.Fatalf("legacy sync state lost: %+v %v", state, err)
	}
	devices, err := storage.ListDevices("legacy-account")
	if err != nil || len(devices) != 1 || devices[0].Name != "旧手机" {
		t.Fatalf("legacy devices lost: %+v %v", devices, err)
	}

	// 升级后仍能加入新账号，且新账号看不到旧数据。
	fresh, err := storage.InitAccount([]byte("fresh-hash"))
	if err != nil {
		t.Fatalf("init on upgraded store: %v", err)
	}
	if freshRows, err := storage.Rows(fresh.ID, "bills"); err != nil || len(freshRows) != 0 {
		t.Fatalf("new account sees legacy rows: %+v %v", freshRows, err)
	}
	if freshState, err := storage.SyncState(fresh.ID); err != nil || freshState.Generation == "legacy-generation" {
		t.Fatalf("new account shares generation: %+v %v", freshState, err)
	}
}
