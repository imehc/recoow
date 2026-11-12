package repository

import (
	"database/sql"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"time"
)

func (storage *postgresStore) CreatePairingSession(codeHash string, session domain.Session, deviceTokenHash []byte, profiles ...domain.DeviceProfile) (domain.User, domain.Device, error) {
	profile, raw, err := deviceProfile(profiles)
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	tx, err := storage.db.Begin()
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	defer tx.Rollback()
	queries := sqlgen.New(tx.Tx)
	accountID, err := queries.ConsumePairingCode(storage.ctx, codeHash)
	if errors.Is(err, sql.ErrNoRows) {
		err = domain.ErrNotFound
	}
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	user, err := domainUser(queries.UserByAccountForShare(storage.ctx, accountID))
	if errors.Is(err, domain.ErrNotFound) {
		user = domain.User{AccountID: accountID}
		err = nil
	}
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	if user.Disabled {
		return domain.User{}, domain.Device{}, domain.ErrNotFound
	}
	now := time.Now().UTC()
	device := domain.Device{ID: newID(), AccountID: accountID, Name: "Web 配对会话", TokenHash: deviceTokenHash, CreatedAt: now, LastSeenAt: now, Profile: profile}
	if err := queries.CreateDevice(storage.ctx, sqlgen.CreateDeviceParams{ID: device.ID, AccountID: accountID, Name: device.Name, TokenHash: hexHash(deviceTokenHash), CreatedAt: rfc3339(now), LastSeenAt: rfc3339(now), Profile: raw}); err != nil {
		return domain.User{}, domain.Device{}, err
	}
	// 配对码、设备与会话同一事务提交，失败时保留配对码且不留下孤立设备。
	err = queries.CreatePairingSession(storage.ctx, sqlgen.CreatePairingSessionParams{ID: session.ID, UserID: sql.NullString{String: user.ID, Valid: user.ID != ""}, DeviceID: device.ID, TokenHash: session.TokenHash, ExpiresAt: session.ExpiresAt, Access: string(domain.SessionReadOnly)})
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	if err := tx.Commit(); err != nil {
		return domain.User{}, domain.Device{}, err
	}
	return user, device, nil
}

func (storage *memoryStore) CreatePairingSession(codeHash string, session domain.Session, deviceTokenHash []byte, profiles ...domain.DeviceProfile) (domain.User, domain.Device, error) {
	profile, _, err := deviceProfile(profiles)
	if err != nil {
		return domain.User{}, domain.Device{}, err
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	pair, exists := storage.pairings[codeHash]
	if !exists || !pair.expires.After(time.Now()) {
		return domain.User{}, domain.Device{}, domain.ErrNotFound
	}
	if _, exists := storage.accounts[pair.accountID]; !exists {
		return domain.User{}, domain.Device{}, domain.ErrNotFound
	}
	user := domain.User{AccountID: pair.accountID}
	for _, candidate := range storage.users {
		if candidate.AccountID == pair.accountID {
			user = candidate
			break
		}
	}
	if user.Disabled {
		return domain.User{}, domain.Device{}, domain.ErrNotFound
	}
	for _, device := range storage.devices {
		if hexHash(device.TokenHash) == hexHash(deviceTokenHash) {
			return domain.User{}, domain.Device{}, domain.ErrDeviceExists
		}
	}
	if _, exists := storage.sessions[session.TokenHash]; exists {
		return domain.User{}, domain.Device{}, domain.ErrConflict
	}
	now := time.Now().UTC()
	device := domain.Device{ID: newID(), AccountID: pair.accountID, Name: "Web 配对会话", TokenHash: deviceTokenHash, CreatedAt: now, LastSeenAt: now, Profile: profile}
	session.DeviceID, session.UserID, session.Access = device.ID, user.ID, domain.SessionReadOnly
	storage.devices[device.ID] = device
	storage.sessions[session.TokenHash] = session
	delete(storage.pairings, codeHash)
	return user, cloneDevice(device), nil
}
