package repository

import (
	"database/sql"
	"encoding/json"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"github.com/imehc/recoow/backend/internal/service"
)

type postgresRevision struct {
	store       *postgresStore
	transaction *storeTx
	accountID   string
}

const syncRowColumns = "table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version"

func scanSyncRow(scanner rowScanner) (domain.Row, error) {
	var row domain.Row
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

func (adapter postgresRevision) ReadRow(table, id string) (domain.Row, bool, error) {
	row, err := scanSyncRow(adapter.transaction.QueryRow(
		"SELECT "+syncRowColumns+" FROM row_state WHERE account_id = ? AND table_name = ? AND id = ?",
		adapter.accountID, table, id,
	))
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Row{}, false, nil
	}
	if err == nil {
		row.AccountID = adapter.accountID
	}
	return row, err == nil, err
}

func (adapter postgresRevision) WriteRow(item domain.PushItem) (domain.Row, error) {
	row := domain.Row{
		AccountID: adapter.accountID,
		Table:     item.Table, ID: item.ID, Snapshot: item.Snapshot, UpdatedAtMs: item.UpdatedAtMs,
		DeviceID: item.DeviceID, DataVersion: item.DataVersion,
	}
	if row.DataVersion == 0 {
		row.DataVersion = 1
	}
	if item.Operation == domain.OpDelete {
		row.DeletedAtMs = item.UpdatedAtMs
	}
	err := adapter.transaction.QueryRow("UPDATE account SET next_version = next_version + 1 WHERE id = ? RETURNING next_version - 1", adapter.accountID).
		Scan(&row.Version)
	if err != nil {
		return domain.Row{}, err
	}
	var snapshot any
	if len(row.Snapshot) > 0 {
		snapshot = string(row.Snapshot)
	}
	err = adapter.store.upsertBusinessRow(adapter.transaction, row)
	if err != nil {
		return domain.Row{}, err
	}
	change := service.ChangeFromRow(row)
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

func (adapter postgresRevision) ReadRevision(table, id string, version int64) (domain.Row, bool, error) {
	row, err := scanSyncRow(adapter.transaction.QueryRow(
		"SELECT "+syncRowColumns+" FROM change_log WHERE account_id = ? AND table_name = ? AND id = ? AND version = ?",
		adapter.accountID, table, id, version,
	))
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Row{}, false, nil
	}
	row.AccountID = adapter.accountID
	return row, err == nil, err
}

func (adapter postgresRevision) ReadReceipt(id string) (domain.MutationReceipt, bool, error) {
	receipt := domain.MutationReceipt{AccountID: adapter.accountID, MutationID: id}
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

func (adapter postgresRevision) WriteReceipt(receipt domain.MutationReceipt) error {
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

func (adapter postgresRevision) AllRows(table string, includeDeleted bool) ([]domain.Row, error) {
	rows, err := adapter.transaction.Query(
		"SELECT "+syncRowColumns+" FROM row_state WHERE account_id = ? AND table_name = ? AND (? OR deleted_at_ms = 0)",
		adapter.accountID, table, includeDeleted)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []domain.Row{}
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

func (storage *postgresStore) SyncState(accountID string) (domain.DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	return storage.syncStateLocked(accountID)
}

func (storage *postgresStore) syncStateLocked(accountID string) (domain.DatasetState, error) {
	tx, beginErr := storage.beginAccount(accountID)
	if beginErr != nil {
		return domain.DatasetState{}, beginErr
	}
	defer tx.Rollback()
	state, err := sqlgen.New(tx.Tx).GetSyncState(storage.ctx, accountID)
	return domain.DatasetState{Generation: state.Generation, MinCursor: state.MinCursor, Head: state.Head}, err
}

func (storage *postgresStore) Bootstrap(accountID string, request domain.BootstrapRequest) (domain.BootstrapPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	state, err := storage.syncStateLocked(accountID)
	if err != nil {
		return domain.BootstrapPage{}, err
	}
	if err := service.ValidateBootstrap(state, request); err != nil {
		return domain.BootstrapPage{}, err
	}
	tx, beginErr := storage.beginAccount(accountID)
	if beginErr != nil {
		return domain.BootstrapPage{}, beginErr
	}
	defer tx.Rollback()
	rows, err := tx.Query(
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
		return domain.BootstrapPage{}, err
	}
	defer rows.Close()
	page := domain.BootstrapPage{
		Entries: []domain.Change{}, Generation: state.Generation, Watermark: request.Watermark, Cursor: request.After,
	}
	for rows.Next() {
		if len(page.Entries) == request.Limit {
			page.More = true
			break
		}
		row, err := scanSyncRow(rows)
		if err != nil {
			return domain.BootstrapPage{}, err
		}
		row.AccountID = accountID
		page.Entries = append(page.Entries, service.ChangeFromRow(row))
		page.Cursor = row.Version
	}
	return page, rows.Err()
}
