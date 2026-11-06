package repository

import (
	"errors"
	"os"
	"strings"

	"github.com/imehc/recoow/backend/internal/domain"
)

func (storage *memoryStore) ClearDataset(accountID, expectedGeneration string) (domain.DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	account, exists := storage.accounts[accountID]
	if !exists {
		return domain.DatasetState{}, domain.ErrNoAccount
	}
	if storage.generations[accountID] != expectedGeneration {
		return domain.DatasetState{}, domain.ErrResetRequired
	}
	for key, row := range storage.rows {
		if row.AccountID == accountID {
			delete(storage.rows, key)
		}
	}
	for key := range storage.media {
		if strings.HasPrefix(key, accountID+"\x00") {
			delete(storage.media, key)
		}
	}
	for key := range storage.receipts {
		if strings.HasPrefix(key, accountID+"\x00") {
			delete(storage.receipts, key)
		}
	}
	changes := storage.log[:0]
	for _, change := range storage.log {
		if change.AccountID != accountID {
			changes = append(changes, change)
		}
	}
	storage.log = changes
	events := storage.events[:0]
	for _, event := range storage.events {
		if event.AccountID != accountID {
			events = append(events, event)
		}
	}
	storage.events = events
	storage.generations[accountID] = newID()
	account.NextVersion = 1
	return domain.DatasetState{Generation: storage.generations[accountID]}, nil
}

func (storage *postgresStore) ClearDataset(accountID, expectedGeneration string) (domain.DatasetState, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return domain.DatasetState{}, err
	}
	defer tx.Rollback()
	var generation string
	if err := tx.QueryRow("SELECT generation FROM sync_metadata WHERE account_id = ? FOR UPDATE", accountID).Scan(&generation); err != nil {
		return domain.DatasetState{}, err
	}
	if generation != expectedGeneration {
		return domain.DatasetState{}, domain.ErrResetRequired
	}
	// Include every registered business table, retaining identity and devices.
	for _, table := range storage.registry.Tables() {
		if _, err := tx.Exec("DELETE FROM \""+table+"\" WHERE tenant_id = ?", accountID); err != nil {
			return domain.DatasetState{}, err
		}
	}
	for _, table := range []string{"change_events", "change_log", "mutation_receipts", "media_objects"} {
		if _, err := tx.Exec("DELETE FROM "+table+" WHERE account_id = ?", accountID); err != nil {
			return domain.DatasetState{}, err
		}
	}
	state := domain.DatasetState{Generation: newID()}
	if _, err := tx.Exec("UPDATE sync_metadata SET generation = ?, min_cursor = 0 WHERE account_id = ?", state.Generation, accountID); err != nil {
		return state, err
	}
	if _, err := tx.Exec("UPDATE account SET next_version = 1 WHERE id = ?", accountID); err != nil {
		return state, err
	}
	// Stage bytes before commit; restore their directory if the transaction fails.
	mediaPath := storage.MediaPath(accountID, "")
	stagedPath := mediaPath + ".cleared-" + state.Generation
	staged := false
	if err := os.Rename(mediaPath, stagedPath); err == nil {
		staged = true
	} else if !errors.Is(err, os.ErrNotExist) {
		return state, err
	}
	if err := tx.Commit(); err != nil {
		if staged {
			_ = os.Rename(stagedPath, mediaPath)
		}
		return state, err
	}
	if staged {
		return state, os.RemoveAll(stagedPath)
	}
	return state, nil
}
