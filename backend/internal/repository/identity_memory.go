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
	return storage.setPassword(userID, "", passwordHash)
}
func (storage *memoryStore) SetPasswordIfCurrent(userID, expectedHash, passwordHash string) error {
	if expectedHash == "" {
		return domain.ErrConflict
	}
	return storage.setPassword(userID, expectedHash, passwordHash)
}
func (storage *memoryStore) setPassword(userID, expectedHash, passwordHash string) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	user, exists := storage.users[userID]
	if !exists {
		return domain.ErrNotFound
	}
	if expectedHash != "" && (user.PasswordHash != expectedHash || user.Disabled) {
		return domain.ErrConflict
	}
	user.PasswordHash = passwordHash
	storage.users[userID] = user
	for hash, session := range storage.sessions {
		if storage.devices[session.DeviceID].AccountID == user.AccountID {
			delete(storage.devices, session.DeviceID)
			delete(storage.sessions, hash)
		}
	}
	return nil
}

func (storage *memoryStore) CreateLoginDevice(user domain.User, name string, tokenHash []byte, profiles ...domain.DeviceProfile) (domain.Device, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	current, exists := storage.users[user.ID]
	if !exists || current.Disabled || current.AccountID != user.AccountID || current.PasswordHash != user.PasswordHash {
		return domain.Device{}, domain.ErrNotFound
	}
	for _, device := range storage.devices {
		if string(device.TokenHash) == string(tokenHash) {
			return domain.Device{}, domain.ErrDeviceExists
		}
	}
	profile, _, err := deviceProfile(profiles)
	if err != nil {
		return domain.Device{}, err
	}
	now := time.Now().UTC()
	device := domain.Device{ID: newID(), AccountID: user.AccountID, Name: name, TokenHash: tokenHash, CreatedAt: now, LastSeenAt: now, Profile: profile}
	storage.devices[device.ID] = device
	return cloneDevice(device), nil
}
func (storage *memoryStore) CreateSession(session domain.Session) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	user, exists := storage.users[session.UserID]
	if !exists || user.Disabled || user.PasswordHash != session.PasswordHash {
		return domain.ErrNotFound
	}
	if session.Access == "" {
		session.Access = domain.SessionReadOnly
	}
	if session.Access != domain.SessionReadOnly && session.Access != domain.SessionReadWrite {
		return domain.ErrNotFound
	}
	storage.sessions[session.TokenHash] = session
	return nil
}
func (storage *memoryStore) SessionByToken(hash string) (domain.Session, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	session, exists := storage.sessions[hash]
	device, deviceExists := storage.devices[session.DeviceID]
	if !exists || !deviceExists || !session.ExpiresAt.After(time.Now()) {
		return domain.Session{}, domain.ErrNotFound
	}
	for _, user := range storage.users {
		if user.AccountID == device.AccountID && user.Disabled {
			return domain.Session{}, domain.ErrNotFound
		}
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
