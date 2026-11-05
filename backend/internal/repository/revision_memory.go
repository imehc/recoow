package repository

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"maps"
	"sort"
)

type memoryRevision struct {
	store     *memoryStore
	accountID string
}

type memoryRevisionBackup struct {
	rows         map[string]domain.Row
	media        map[string]domain.MediaObject
	receipts     map[string]domain.MutationReceipt
	logCount     int
	nextVersions map[string]int64
}

func (storage *memoryStore) revisionBackup() memoryRevisionBackup {
	versions := make(map[string]int64, len(storage.accounts))
	for id, account := range storage.accounts {
		versions[id] = account.NextVersion
	}
	return memoryRevisionBackup{
		rows: maps.Clone(storage.rows), media: maps.Clone(storage.media),
		receipts: maps.Clone(storage.receipts), logCount: len(storage.log),
		nextVersions: versions,
	}
}

func (storage *memoryStore) restoreRevisionBackup(backup memoryRevisionBackup) {
	storage.rows = backup.rows
	storage.media = backup.media
	storage.receipts = backup.receipts
	storage.log = storage.log[:backup.logCount]
	for id, version := range backup.nextVersions {
		if account, ok := storage.accounts[id]; ok {
			account.NextVersion = version
		}
	}
}

func (adapter memoryRevision) account() (*domain.Account, error) {
	account, ok := adapter.store.accounts[adapter.accountID]
	if !ok {
		return nil, domain.ErrNoAccount
	}
	return account, nil
}

func (adapter memoryRevision) ReadRow(table, id string) (domain.Row, bool, error) {
	row, exists := adapter.store.rows[rowKey(adapter.accountID, table, id)]
	return row, exists, nil
}

func (adapter memoryRevision) WriteRow(item domain.PushItem) (domain.Row, error) {
	account, err := adapter.account()
	if err != nil {
		return domain.Row{}, err
	}
	row := domain.Row{
		AccountID: adapter.accountID,
		Table:     item.Table, ID: item.ID, Snapshot: item.Snapshot, UpdatedAtMs: item.UpdatedAtMs,
		DeviceID: item.DeviceID, DataVersion: item.DataVersion, Version: account.NextVersion,
	}
	if row.DataVersion == 0 {
		row.DataVersion = 1
	}
	if item.Operation == domain.OpDelete {
		row.DeletedAtMs = item.UpdatedAtMs
	}
	account.NextVersion++
	adapter.store.rows[rowKey(adapter.accountID, item.Table, item.ID)] = row
	adapter.store.log = append(adapter.store.log, service.ChangeFromRow(row))
	return row, nil
}

func (adapter memoryRevision) ReadReceipt(id string) (domain.MutationReceipt, bool, error) {
	receipt, exists := adapter.store.receipts[receiptKey(adapter.accountID, id)]
	return receipt, exists, nil
}

func (adapter memoryRevision) WriteReceipt(receipt domain.MutationReceipt) error {
	receipt.AccountID = adapter.accountID
	adapter.store.receipts[receiptKey(adapter.accountID, receipt.MutationID)] = receipt
	return nil
}

func (adapter memoryRevision) AllRows(table string, includeDeleted bool) ([]domain.Row, error) {
	result := []domain.Row{}
	for _, row := range adapter.store.rows {
		if row.AccountID == adapter.accountID && row.Table == table && (includeDeleted || row.DeletedAtMs == 0) {
			result = append(result, row)
		}
	}
	return result, nil
}

func (storage *memoryStore) SyncState(accountID string) (domain.DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	account, ok := storage.accounts[accountID]
	if !ok {
		return domain.DatasetState{}, domain.ErrNoAccount
	}
	return domain.DatasetState{Generation: storage.generations[accountID], Head: account.NextVersion - 1}, nil
}

func (storage *memoryStore) Bootstrap(accountID string, request domain.BootstrapRequest) (domain.BootstrapPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	account, ok := storage.accounts[accountID]
	if !ok {
		return domain.BootstrapPage{}, domain.ErrNoAccount
	}
	state := domain.DatasetState{Generation: storage.generations[accountID], Head: account.NextVersion - 1}
	if err := service.ValidateBootstrap(state, request); err != nil {
		return domain.BootstrapPage{}, err
	}
	latest := map[string]domain.Change{}
	for _, change := range storage.log {
		if change.AccountID != accountID {
			continue
		}
		if change.Version <= request.Watermark {
			latest[rowKey(accountID, change.Table, change.ID)] = change
		}
	}
	entries := []domain.Change{}
	for _, change := range latest {
		if change.Version > request.After {
			entries = append(entries, change)
		}
	}
	sort.Slice(entries, func(first, second int) bool { return entries[first].Version < entries[second].Version })
	page := domain.BootstrapPage{
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
