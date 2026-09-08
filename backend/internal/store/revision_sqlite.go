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
	if version > 3 {
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
	if _, err := transaction.Exec("INSERT OR IGNORE INTO sync_metadata (id, generation) VALUES (1, ?)", newID()); err != nil {
		return err
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
		err := transaction.QueryRow("SELECT id FROM account").Scan(&accountID)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			if err := migrateAggregateRows(sqliteRevision{store: storage, transaction: transaction, accountID: accountID}); err != nil {
				return fmt.Errorf("migrate aggregates: %w", err)
			}
		}
	}
	if _, err := transaction.Exec("PRAGMA user_version=3"); err != nil {
		return err
	}
	return transaction.Commit()
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
		"SELECT "+syncRowColumns+" FROM row_state WHERE table_name = ? AND id = ?", table, id,
	))
	if errors.Is(err, sql.ErrNoRows) {
		return Row{}, false, nil
	}
	return row, err == nil, err
}

func (adapter sqliteRevision) writeRow(item PushItem) (Row, error) {
	row := Row{
		Table: item.Table, ID: item.ID, Snapshot: item.Snapshot, UpdatedAtMs: item.UpdatedAtMs,
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
		`INSERT INTO row_state (table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (table_name, id) DO UPDATE SET
		 snapshot = excluded.snapshot, updated_at_ms = excluded.updated_at_ms, device_id = excluded.device_id,
		 deleted_at_ms = excluded.deleted_at_ms, version = excluded.version, data_version = excluded.data_version`,
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
		`INSERT INTO change_log (version, table_name, id, operation, updated_at_ms, device_id, deleted,
		 deleted_at_ms, snapshot, data_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
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
	rows, err := adapter.transaction.Query("SELECT "+syncRowColumns+" FROM row_state WHERE table_name = ? AND (? OR deleted_at_ms = 0)", table, includeDeleted)
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
		`SELECT generation, min_cursor, next_version - 1 FROM sync_metadata, account
		 WHERE sync_metadata.id = 1 AND account.id = ?`, accountID,
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
		 FROM change_log WHERE version IN
		 (SELECT MAX(version) FROM change_log WHERE version <= ? GROUP BY table_name, id)
		 AND version > ? ORDER BY version LIMIT ?`,
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
		page.Entries = append(page.Entries, changeFromRow(row))
		page.Cursor = row.Version
	}
	return page, rows.Err()
}
