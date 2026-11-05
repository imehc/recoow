package repository

import (
	"database/sql"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"time"
)

func (storage *postgresStore) IssuePairingCode(hash, accountID string, expires time.Time) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	queries := sqlgen.New(tx.Tx)
	if err := queries.DeleteExpiredPairingCodes(storage.ctx); err != nil {
		return err
	}
	if err := queries.IssuePairingCode(storage.ctx, sqlgen.IssuePairingCodeParams{CodeHash: hash, AccountID: accountID, ExpiresAt: expires}); err != nil {
		return err
	}
	return tx.Commit()
}
func (storage *postgresStore) ConsumePairingCode(hash string) (string, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	// DELETE RETURNING 同时校验有效期和消费配对码，跨实例也只能成功一次。
	accountID, err := sqlgen.New(storage.db.DB).ConsumePairingCode(storage.ctx, hash)
	if errors.Is(err, sql.ErrNoRows) {
		err = domain.ErrNotFound
	}
	return accountID, err
}
func (storage *postgresStore) CleanupExpiredSessions(now time.Time, limit int) (int64, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if limit < 1 || limit > 1000 {
		limit = 200
	}
	return sqlgen.New(storage.db.DB).CleanupExpiredSessions(storage.ctx, sqlgen.CleanupExpiredSessionsParams{ExpiresAt: now, BatchSize: int32(limit)})
}
func (storage *postgresStore) SetAccountLimit(limit int) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	storage.accountLimit = limit
}
func (storage *postgresStore) checkAccountQuota(tx *storeTx) error {
	// 注册数量校验和账号创建处于同一个受锁保护的事务，避免多实例超额注册。
	queries := sqlgen.New(tx.Tx)
	if err := queries.LockRegistration(storage.ctx); err != nil {
		return err
	}
	if storage.accountLimit > 0 {
		count, err := queries.CountAccounts(storage.ctx)
		if err != nil {
			return err
		}
		if count >= int64(storage.accountLimit) {
			return domain.ErrAccountQuota
		}
	}
	return nil
}

type pairingRecord struct {
	accountID string
	expires   time.Time
}

func (storage *memoryStore) IssuePairingCode(hash, accountID string, expires time.Time) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	storage.pairings[hash] = pairingRecord{accountID, expires}
	return nil
}
func (storage *memoryStore) ConsumePairingCode(hash string) (string, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	pair, exists := storage.pairings[hash]
	delete(storage.pairings, hash)
	if !exists || !pair.expires.After(time.Now()) {
		return "", domain.ErrNotFound
	}
	return pair.accountID, nil
}
func (storage *memoryStore) CleanupExpiredSessions(now time.Time, limit int) (int64, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	var count int64
	for hash, session := range storage.sessions {
		if !session.ExpiresAt.After(now) && count < int64(limit) {
			delete(storage.devices, session.DeviceID)
			delete(storage.sessions, hash)
			count++
		}
	}
	return count, nil
}
func (storage *memoryStore) SetAccountLimit(limit int) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	storage.accountLimit = limit
}
