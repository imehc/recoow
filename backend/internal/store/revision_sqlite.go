package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

func (storage *sqliteStore) migrateSyncSchema() error {
	var version int
	if err := storage.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > 4 {
		return fmt.Errorf("database version %d requires a newer server", version)
	}
	if version < 3 {
		var accounts int
		if err := storage.db.QueryRow("SELECT COUNT(*) FROM account").Scan(&accounts); err != nil {
			return err
		}
		if accounts > 0 {
			directory := filepath.Join(filepath.Dir(storage.mediaDir), "backups")
			if err := os.MkdirAll(directory, 0o700); err != nil {
				return err
			}
			if _, err := storage.db.Exec("VACUUM INTO ?", filepath.Join(directory, "before-sync-v3-"+newID()+".sqlite")); err != nil {
				return fmt.Errorf("backup before sync migration: %w", err)
			}
		}
	}
	transaction, err := storage.db.Begin()
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	for _, table := range []string{"row_state", "change_log"} {
		var count int
		if err := transaction.QueryRow("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = 'data_version'", table).
			Scan(&count); err != nil {
			return err
		}
		if count == 0 {
			if _, err := transaction.Exec("ALTER TABLE " + table + " ADD COLUMN data_version INTEGER NOT NULL DEFAULT 1"); err != nil {
				return err
			}
		}
	}
	// 先做结构升级（加 account_id 分区），再做数据语义升级——
	// v3 的聚合迁移经由 revision 适配器读写，必须走带 account_id 的新表。
	if version < 4 {
		if err := migrateToMultiTenant(transaction); err != nil {
			return fmt.Errorf("migrate multi-tenant: %w", err)
		}
	}
	if version < 3 {
		for _, table := range []string{"change_log", "row_state"} {
			if _, err := transaction.Exec(`UPDATE ` + table + ` AS tombstone SET snapshot = (
				SELECT previous.snapshot FROM change_log AS previous
				WHERE previous.table_name = tombstone.table_name AND previous.id = tombstone.id
					AND previous.version < tombstone.version AND previous.snapshot IS NOT NULL
				ORDER BY previous.version DESC LIMIT 1
			) WHERE tombstone.deleted_at_ms > 0 AND tombstone.snapshot IS NULL`); err != nil {
				return err
			}
		}
		var accountID string
		err := transaction.QueryRow("SELECT id FROM account LIMIT 1").Scan(&accountID)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			if err := migrateAggregateRows(sqliteRevision{store: storage, transaction: transaction, accountID: accountID}); err != nil {
				return fmt.Errorf("migrate aggregates: %w", err)
			}
		}
	}
	if _, err := transaction.Exec("PRAGMA user_version=4"); err != nil {
		return err
	}
	return transaction.Commit()
}

// migrateToMultiTenant 把 v3 的单账号库升级为按 account_id 分区的多账号库：
// 三张数据集表重建并回填既有账号 id，sync_metadata 改为按账号一行。
// 全新库在建表阶段已带 account_id，这里只需补齐索引。
func migrateToMultiTenant(transaction *sql.Tx) error {
	if _, err := transaction.Exec(
		"CREATE UNIQUE INDEX IF NOT EXISTS idx_account_recovery ON account (recovery_key_hash)"); err != nil {
		return err
	}
	hasAccountID, err := hasColumn(transaction, "row_state", "account_id")
	if err != nil {
		return err
	}
	if hasAccountID {
		return nil
	}
	var legacyAccountID string
	switch err := transaction.QueryRow("SELECT id FROM account LIMIT 1").Scan(&legacyAccountID); {
	case errors.Is(err, sql.ErrNoRows):
		legacyAccountID = ""
	case err != nil:
		return err
	}
	type step struct {
		sql  string
		args []any
	}
	backfill := []any{legacyAccountID}
	steps := []step{
		{sql: `CREATE TABLE row_state_mt (
			account_id    TEXT NOT NULL,
			table_name    TEXT NOT NULL,
			id            TEXT NOT NULL,
			snapshot      TEXT,
			updated_at_ms INTEGER NOT NULL,
			device_id     TEXT NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			version       INTEGER NOT NULL,
			data_version  INTEGER NOT NULL DEFAULT 1,
			PRIMARY KEY (account_id, table_name, id)
		)`},
		{sql: `INSERT INTO row_state_mt (account_id, table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version)
			SELECT ?, table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version FROM row_state`,
			args: backfill},
		{sql: `DROP TABLE row_state`},
		{sql: `ALTER TABLE row_state_mt RENAME TO row_state`},
		{sql: `CREATE INDEX IF NOT EXISTS idx_row_state_live ON row_state (account_id, table_name, deleted_at_ms)`},

		{sql: `CREATE TABLE change_log_mt (
			account_id    TEXT NOT NULL,
			version       INTEGER NOT NULL,
			table_name    TEXT NOT NULL,
			id            TEXT NOT NULL,
			operation     TEXT NOT NULL,
			updated_at_ms INTEGER NOT NULL,
			device_id     TEXT NOT NULL,
			deleted       INTEGER NOT NULL,
			deleted_at_ms INTEGER NOT NULL,
			snapshot      TEXT,
			data_version  INTEGER NOT NULL DEFAULT 1,
			PRIMARY KEY (account_id, version)
		)`},
		{sql: `INSERT INTO change_log_mt (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version)
			SELECT ?, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version FROM change_log`,
			args: backfill},
		{sql: `DROP TABLE change_log`},
		{sql: `ALTER TABLE change_log_mt RENAME TO change_log`},
		{sql: `CREATE INDEX IF NOT EXISTS idx_change_log_entity_version ON change_log (account_id, table_name, id, version)`},

		{sql: `CREATE TABLE media_objects_mt (
			account_id   TEXT NOT NULL,
			key          TEXT NOT NULL,
			checksum     TEXT NOT NULL,
			size         INTEGER NOT NULL,
			content_type TEXT NOT NULL,
			created_at   TEXT NOT NULL,
			PRIMARY KEY (account_id, key)
		)`},
		{sql: `INSERT INTO media_objects_mt (account_id, key, checksum, size, content_type, created_at)
			SELECT ?, key, checksum, size, content_type, created_at FROM media_objects`,
			args: backfill},
		{sql: `DROP TABLE media_objects`},
		{sql: `ALTER TABLE media_objects_mt RENAME TO media_objects`},

		{sql: `CREATE TABLE sync_metadata_mt (
			account_id TEXT PRIMARY KEY,
			generation TEXT NOT NULL,
			min_cursor INTEGER NOT NULL DEFAULT 0
		)`},
		{sql: `INSERT INTO sync_metadata_mt (account_id, generation, min_cursor)
			SELECT ?, generation, min_cursor FROM sync_metadata LIMIT 1`,
			args: backfill},
		{sql: `DROP TABLE sync_metadata`},
		{sql: `ALTER TABLE sync_metadata_mt RENAME TO sync_metadata`},
	}
	for _, step := range steps {
		if _, err := transaction.Exec(step.sql, step.args...); err != nil {
			return fmt.Errorf("%s: %w", step.sql, err)
		}
	}
	// 存量单账号库补上同步元数据；新账号在 InitAccount 时自行插入。
	if legacyAccountID != "" {
		if _, err := transaction.Exec(
			"INSERT OR IGNORE INTO sync_metadata (account_id, generation) VALUES (?, ?)",
			legacyAccountID, newID()); err != nil {
			return err
		}
	}
	return nil
}

// hasColumn 判断某表是否已有指定列（用于识别库结构版本）。
func hasColumn(q interface {
	QueryRow(query string, args ...any) *sql.Row
}, table, column string) (bool, error) {
	var count int
	err := q.QueryRow("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?", table, column).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

type sqliteRevision struct {
	store       *sqliteStore
	transaction *sql.Tx
	accountID   string
}

const syncRowColumns = "table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version"

func scanSyncRow(scanner rowScanner) (Row, error) {
	var row Row
	var snapshot sql.NullString
	err := scanner.Scan(
		&row.Table,
		&row.ID,
		&snapshot,
		&row.UpdatedAtMs,
		&row.DeviceID,
		&row.DeletedAtMs,
		&row.Version,
		&row.DataVersion,
	)
	if snapshot.Valid {
		row.Snapshot = json.RawMessage(snapshot.String)
	}
	return row, err
}

func (adapter sqliteRevision) readRow(table, id string) (Row, bool, error) {
	row, err := scanSyncRow(adapter.transaction.QueryRow(
		"SELECT "+syncRowColumns+" FROM row_state WHERE account_id = ? AND table_name = ? AND id = ?",
		adapter.accountID, table, id,
	))
	if errors.Is(err, sql.ErrNoRows) {
		return Row{}, false, nil
	}
	if err == nil {
		row.AccountID = adapter.accountID
	}
	return row, err == nil, err
}

func (adapter sqliteRevision) writeRow(item PushItem) (Row, error) {
	row := Row{
		AccountID: adapter.accountID,
		Table:     item.Table, ID: item.ID, Snapshot: item.Snapshot, UpdatedAtMs: item.UpdatedAtMs,
		DeviceID: item.DeviceID, DataVersion: item.DataVersion,
	}
	if row.DataVersion == 0 {
		row.DataVersion = 1
	}
	if item.Operation == OpDelete {
		row.DeletedAtMs = item.UpdatedAtMs
	}
	err := adapter.transaction.QueryRow("UPDATE account SET next_version = next_version + 1 WHERE id = ? RETURNING next_version - 1", adapter.accountID).
		Scan(&row.Version)
	if err != nil {
		return Row{}, err
	}
	var snapshot any
	if len(row.Snapshot) > 0 {
		snapshot = string(row.Snapshot)
	}
	_, err = adapter.transaction.Exec(
		`INSERT INTO row_state (account_id, table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (account_id, table_name, id) DO UPDATE SET
		 snapshot = excluded.snapshot, updated_at_ms = excluded.updated_at_ms, device_id = excluded.device_id,
		 deleted_at_ms = excluded.deleted_at_ms, version = excluded.version, data_version = excluded.data_version`,
		adapter.accountID,
		row.Table,
		row.ID,
		snapshot,
		row.UpdatedAtMs,
		row.DeviceID,
		row.DeletedAtMs,
		row.Version,
		row.DataVersion,
	)
	if err != nil {
		return Row{}, err
	}
	change := changeFromRow(row)
	_, err = adapter.transaction.Exec(
		`INSERT INTO change_log (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted,
		 deleted_at_ms, snapshot, data_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		adapter.accountID,
		change.Version,
		change.Table,
		change.ID,
		string(change.Operation),
		change.UpdatedAtMs,
		change.DeviceID,
		boolInt(change.Deleted),
		change.DeletedAtMs,
		snapshot,
		change.DataVersion,
	)
	return row, err
}

func (adapter sqliteRevision) readReceipt(id string) (mutationReceipt, bool, error) {
	receipt := mutationReceipt{AccountID: adapter.accountID, MutationID: id}
	var result string
	err := adapter.transaction.QueryRow(
		"SELECT request_hash, result_json FROM mutation_receipts WHERE account_id = ? AND mutation_id = ?",
		adapter.accountID, id,
	).Scan(&receipt.RequestHash, &result)
	if errors.Is(err, sql.ErrNoRows) {
		return receipt, false, nil
	}
	if err != nil {
		return receipt, false, err
	}
	err = json.Unmarshal([]byte(result), &receipt.Result)
	return receipt, err == nil, err
}

func (adapter sqliteRevision) writeReceipt(receipt mutationReceipt) error {
	result, err := json.Marshal(receipt.Result)
	if err != nil {
		return err
	}
	_, err = adapter.transaction.Exec(
		"INSERT INTO mutation_receipts (account_id, mutation_id, request_hash, result_json) VALUES (?, ?, ?, ?)",
		adapter.accountID,
		receipt.MutationID,
		receipt.RequestHash,
		string(result),
	)
	return err
}

func (adapter sqliteRevision) allRows(table string, includeDeleted bool) ([]Row, error) {
	rows, err := adapter.transaction.Query(
		"SELECT "+syncRowColumns+" FROM row_state WHERE account_id = ? AND table_name = ? AND (? OR deleted_at_ms = 0)",
		adapter.accountID, table, includeDeleted)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Row{}
	for rows.Next() {
		row, err := scanSyncRow(rows)
		if err != nil {
			return nil, err
		}
		row.AccountID = adapter.accountID
		result = append(result, row)
	}
	return result, rows.Err()
}

func (storage *sqliteStore) SyncState(accountID string) (DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	return storage.syncStateLocked(accountID)
}

func (storage *sqliteStore) syncStateLocked(accountID string) (DatasetState, error) {
	var state DatasetState
	err := storage.db.QueryRow(
		`SELECT sync_metadata.generation, sync_metadata.min_cursor, account.next_version - 1
		 FROM sync_metadata JOIN account ON account.id = sync_metadata.account_id
		 WHERE sync_metadata.account_id = ?`, accountID,
	).Scan(&state.Generation, &state.MinCursor, &state.Head)
	return state, err
}

func (storage *sqliteStore) Bootstrap(accountID string, request BootstrapRequest) (BootstrapPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	state, err := storage.syncStateLocked(accountID)
	if err != nil {
		return BootstrapPage{}, err
	}
	if err := validateBootstrap(state, request); err != nil {
		return BootstrapPage{}, err
	}
	rows, err := storage.db.Query(
		`SELECT table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version
		 FROM change_log WHERE account_id = ? AND version IN
		 (SELECT MAX(version) FROM change_log WHERE account_id = ? AND version <= ? GROUP BY table_name, id)
		 AND version > ? ORDER BY version LIMIT ?`,
		accountID,
		accountID,
		request.Watermark,
		request.After,
		request.Limit+1,
	)
	if err != nil {
		return BootstrapPage{}, err
	}
	defer rows.Close()
	page := BootstrapPage{
		Entries: []Change{}, Generation: state.Generation, Watermark: request.Watermark, Cursor: request.After,
	}
	for rows.Next() {
		if len(page.Entries) == request.Limit {
			page.More = true
			break
		}
		row, err := scanSyncRow(rows)
		if err != nil {
			return BootstrapPage{}, err
		}
		row.AccountID = accountID
		page.Entries = append(page.Entries, changeFromRow(row))
		page.Cursor = row.Version
	}
	return page, rows.Err()
}
