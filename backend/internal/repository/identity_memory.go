package repository

import (
	"encoding/hex"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/jackc/pgx/v5/pgconn"
	"time"
)

func hexHash(value []byte) string { return hex.EncodeToString(value) }
func identityError(err error) error {
	var databaseError *pgconn.PgError
	if errors.As(err, &databaseError) && databaseError.Code == "23505" {
		return domain.ErrConflict
	}
	return err
}

func (storage *memoryStore) BindUser(accountID, username, passwordHash string) (domain.User, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if _, exists := storage.accounts[accountID]; !exists {
		return domain.User{}, domain.ErrNoAccount
	}
	for _, user := range storage.users {
		if user.Username == username || user.AccountID == accountID {
			return domain.User{}, domain.ErrConflict
		}
	}
	user := domain.User{ID: newID(), AccountID: accountID, Username: username, PasswordHash: passwordHash}
	storage.users[user.ID] = user
	return user, nil
}
func (storage *memoryStore) RegisterUser(username, passwordHash string, recoveryHash []byte) (domain.Account, domain.User, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if storage.accountLimit > 0 && len(storage.accounts) >= storage.accountLimit {
		return domain.Account{}, domain.User{}, domain.ErrAccountQuota
	}
	for _, user := range storage.users {
		if user.Username == username {
			return domain.Account{}, domain.User{}, domain.ErrConflict
		}
	}
	account := domain.Account{ID: newID(), RecoveryKeyHash: recoveryHash, CreatedAt: time.Now().UTC(), NextVersion: 1}
	user := domain.User{ID: newID(), AccountID: account.ID, Username: username, PasswordHash: passwordHash}
	storage.accounts[account.ID] = &account
	storage.generations[account.ID] = newID()
	storage.users[user.ID] = user
	return account, user, nil
}
func (storage *memoryStore) UserByUsername(username string) (domain.User, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	for _, user := range storage.users {
		if user.Username == username {
			return user, nil
		}
	}
	return domain.User{}, domain.ErrNotFound
}
func (storage *memoryStore) UserByAccount(accountID string) (domain.User, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	for _, user := range storage.users {
		if user.AccountID == accountID {
			return user, nil
		}
	}
	return domain.User{}, domain.ErrNotFound
}
func (storage *memoryStore) SetPassword(userID, passwordHash string) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	user, exists := storage.users[userID]
	if !exists {
		return domain.ErrNotFound
	}
	user.PasswordHash = passwordHash
	storage.users[userID] = user
	for hash, session := range storage.sessions {
		if session.UserID == userID {
			delete(storage.devices, session.DeviceID)
			delete(storage.sessions, hash)
		}
	}
	return nil
}
func (storage *memoryStore) CreateSession(session domain.Session) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	user, exists := storage.users[session.UserID]
	if !exists || user.Disabled || user.PasswordHash != session.PasswordHash {
		return domain.ErrNotFound
	}
	storage.sessions[session.TokenHash] = session
	return nil
}
func (storage *memoryStore) SessionByToken(hash string) (domain.Session, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	session, exists := storage.sessions[hash]
	if !exists || !session.ExpiresAt.After(time.Now()) || storage.users[session.UserID].Disabled {
		return domain.Session{}, domain.ErrNotFound
	}
	return session, nil
}
func (storage *memoryStore) DeleteSession(hash string) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	delete(storage.devices, storage.sessions[hash].DeviceID)
	delete(storage.sessions, hash)
	return nil
}
