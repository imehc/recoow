package repository

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"github.com/imehc/recoow/backend/internal/service"
)

func validSnapshotQuery(state domain.DatasetState, request domain.SnapshotQuery) error {
	if err := service.ValidateBootstrap(state, domain.BootstrapRequest{
		Generation: request.Generation, Watermark: request.Watermark, Limit: 1,
	}); err != nil {
		return err
	}
	if len(request.Versions) == 0 || len(request.Versions) > 500 {
		return domain.ErrNotFound
	}
	seen := map[int64]bool{}
	for _, version := range request.Versions {
		if version < 1 || version > request.Watermark || seen[version] {
			return domain.ErrNotFound
		}
		seen[version] = true
	}
	return nil
}

func (storage *memoryStore) SnapshotRevisions(accountID string, request domain.SnapshotQuery) ([]domain.Change, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	account, ok := storage.accounts[accountID]
	if !ok {
		return nil, domain.ErrNoAccount
	}
	state := domain.DatasetState{Generation: storage.generations[accountID], Head: account.NextVersion - 1}
	if err := validSnapshotQuery(state, request); err != nil {
		return nil, err
	}
	byVersion := map[int64]domain.Change{}
	for _, change := range storage.log {
		if change.AccountID == accountID {
			byVersion[change.Version] = change
		}
	}
	changes := make([]domain.Change, 0, len(request.Versions))
	for _, version := range request.Versions {
		change, ok := byVersion[version]
		if !ok {
			return nil, domain.ErrNotFound
		}
		changes = append(changes, change)
	}
	return changes, nil
}

func (storage *postgresStore) SnapshotRevisions(accountID string, request domain.SnapshotQuery) ([]domain.Change, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	// 防止在检查数据集代际与读取历史之间发生数据集清空。
	var generation string
	if err := tx.QueryRow("SELECT generation FROM sync_metadata WHERE account_id = ? FOR SHARE", accountID).Scan(&generation); err != nil {
		return nil, err
	}
	state, err := sqlgen.New(tx.Tx).GetSyncState(storage.ctx, accountID)
	if err != nil {
		return nil, err
	}
	if err := validSnapshotQuery(domain.DatasetState{Generation: generation, Head: state.Head, MinCursor: state.MinCursor}, request); err != nil {
		return nil, err
	}
	rows, err := tx.Query("SELECT "+syncRowColumns+" FROM change_log WHERE account_id = ? AND version = ANY(?::bigint[])", accountID, request.Versions)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byVersion := map[int64]domain.Change{}
	for rows.Next() {
		row, err := scanSyncRow(rows)
		if err != nil {
			return nil, err
		}
		row.AccountID = accountID
		byVersion[row.Version] = service.ChangeFromRow(row)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	changes := make([]domain.Change, 0, len(request.Versions))
	for _, version := range request.Versions {
		change, ok := byVersion[version]
		if !ok {
			return nil, domain.ErrNotFound
		}
		changes = append(changes, change)
	}
	return changes, nil
}
