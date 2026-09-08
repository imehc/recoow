package store

import (
	"maps"
	"sort"
)

type memoryRevision struct {
	store *memoryStore
}

type memoryRevisionBackup struct {
	rows        map[string]Row
	receipts    map[string]mutationReceipt
	logCount    int
	nextVersion int64
}

func (storage *memoryStore) revisionBackup() memoryRevisionBackup {
	return memoryRevisionBackup{
		rows: maps.Clone(storage.rows), receipts: maps.Clone(storage.receipts),
		logCount: len(storage.log), nextVersion: storage.account.NextVersion,
	}
}

func (storage *memoryStore) restoreRevisionBackup(backup memoryRevisionBackup) {
	storage.rows = backup.rows
	storage.receipts = backup.receipts
	storage.log = storage.log[:backup.logCount]
	storage.account.NextVersion = backup.nextVersion
}

func (adapter memoryRevision) readRow(table, id string) (Row, bool, error) {
	row, exists := adapter.store.rows[rowKey(table, id)]
	return row, exists, nil
}

func (adapter memoryRevision) writeRow(item PushItem) (Row, error) {
	row := Row{
		Table: item.Table, ID: item.ID, Snapshot: item.Snapshot, UpdatedAtMs: item.UpdatedAtMs,
		DeviceID: item.DeviceID, DataVersion: item.DataVersion, Version: adapter.store.account.NextVersion,
	}
	if row.DataVersion == 0 {
		row.DataVersion = 1
	}
	if item.Operation == OpDelete {
		row.DeletedAtMs = item.UpdatedAtMs
	}
	adapter.store.account.NextVersion++
	adapter.store.rows[rowKey(item.Table, item.ID)] = row
	adapter.store.log = append(adapter.store.log, changeFromRow(row))
	return row, nil
}

func (adapter memoryRevision) readReceipt(id string) (mutationReceipt, bool, error) {
	receipt, exists := adapter.store.receipts[id]
	return receipt, exists, nil
}

func (adapter memoryRevision) writeReceipt(receipt mutationReceipt) error {
	receipt.AccountID = adapter.store.account.ID
	adapter.store.receipts[receipt.MutationID] = receipt
	return nil
}

func (adapter memoryRevision) allRows(table string, includeDeleted bool) ([]Row, error) {
	result := []Row{}
	for _, row := range adapter.store.rows {
		if row.Table == table && (includeDeleted || row.DeletedAtMs == 0) {
			result = append(result, row)
		}
	}
	return result, nil
}

func (storage *memoryStore) SyncState(accountID string) (DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if storage.account == nil || storage.account.ID != accountID {
		return DatasetState{}, ErrNoAccount
	}
	return DatasetState{Generation: storage.generation, Head: storage.account.NextVersion - 1}, nil
}

func (storage *memoryStore) Bootstrap(accountID string, request BootstrapRequest) (BootstrapPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if storage.account == nil || storage.account.ID != accountID {
		return BootstrapPage{}, ErrNoAccount
	}
	state := DatasetState{Generation: storage.generation, Head: storage.account.NextVersion - 1}
	if err := validateBootstrap(state, request); err != nil {
		return BootstrapPage{}, err
	}
	latest := map[string]Change{}
	for _, change := range storage.log {
		if change.Version <= request.Watermark {
			latest[rowKey(change.Table, change.ID)] = change
		}
	}
	entries := []Change{}
	for _, change := range latest {
		if change.Version > request.After {
			entries = append(entries, change)
		}
	}
	sort.Slice(entries, func(first, second int) bool { return entries[first].Version < entries[second].Version })
	page := BootstrapPage{
		Entries: entries, Generation: state.Generation, Watermark: request.Watermark,
		Cursor: request.After, More: len(entries) > request.Limit,
	}
	if page.More {
		page.Entries = entries[:request.Limit]
	}
	if len(page.Entries) > 0 {
		page.Cursor = page.Entries[len(page.Entries)-1].Version
	}
	return page, nil
}
