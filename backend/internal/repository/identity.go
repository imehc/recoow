package repository

import (
	"database/sql"
	"errors"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
)

func domainUser(user sqlgen.User, err error) (domain.User, error) {
	if errors.Is(err, sql.ErrNoRows) {
		return domain.User{}, domain.ErrNotFound
	}
	return domain.User{ID: user.ID, AccountID: user.AccountID, Username: user.Username, PasswordHash: user.PasswordHash, Disabled: user.Disabled}, identityError(err)
}

func (storage *postgresStore) BindUser(accountID, username, passwordHash string) (domain.User, error) {
	return domainUser(sqlgen.New(storage.db.DB).CreateUser(storage.ctx, sqlgen.CreateUserParams{ID: newID(), AccountID: accountID, Username: username, PasswordHash: passwordHash}))
}
func (storage *postgresStore) RegisterUser(username, passwordHash string, recoveryHash []byte) (domain.Account, domain.User, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.db.Begin()
	if err != nil {
		return domain.Account{}, domain.User{}, err
	}
	defer tx.Rollback()
	if err := storage.checkAccountQuota(tx); err != nil {
		return domain.Account{}, domain.User{}, err
	}
	account := domain.Account{ID: newID(), RecoveryKeyHash: recoveryHash, CreatedAt: time.Now().UTC(), NextVersion: 1}
	queries := sqlgen.New(tx.Tx)
	if err := queries.SetAccountContext(storage.ctx, account.ID); err != nil {
		return domain.Account{}, domain.User{}, err
	}
	if err := queries.CreateAccount(storage.ctx, sqlgen.CreateAccountParams{ID: account.ID, RecoveryKeyHash: hexHash(recoveryHash), CreatedAt: rfc3339(account.CreatedAt), NextVersion: 1}); err != nil {
		return domain.Account{}, domain.User{}, identityError(err)
	}
	if err := queries.InitializeSyncMetadata(storage.ctx, sqlgen.InitializeSyncMetadataParams{AccountID: account.ID, Generation: newID()}); err != nil {
		return domain.Account{}, domain.User{}, err
	}
	user, err := domainUser(queries.CreateUser(storage.ctx, sqlgen.CreateUserParams{ID: newID(), AccountID: account.ID, Username: username, PasswordHash: passwordHash}))
	if err != nil {
		return domain.Account{}, domain.User{}, err
	}
	return account, user, tx.Commit()
}
func (storage *postgresStore) UserByUsername(username string) (domain.User, error) {
	return domainUser(sqlgen.New(storage.db.DB).UserByUsername(storage.ctx, username))
}
func (storage *postgresStore) UserByAccount(accountID string) (domain.User, error) {
	return domainUser(sqlgen.New(storage.db.DB).UserByAccount(storage.ctx, accountID))
}
func (storage *postgresStore) SetPassword(userID, passwordHash string) error {
	return storage.setPassword(userID, "", passwordHash)
}
func (storage *postgresStore) SetPasswordIfCurrent(userID, expectedHash, passwordHash string) error {
	if expectedHash == "" {
		return domain.ErrConflict
	}
	return storage.setPassword(userID, expectedHash, passwordHash)
}
func (storage *postgresStore) setPassword(userID, expectedHash, passwordHash string) error {
	tx, err := storage.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	queries := sqlgen.New(tx.Tx)
	var affected int64
	if expectedHash == "" {
		affected, err = queries.SetPassword(storage.ctx, sqlgen.SetPasswordParams{ID: userID, PasswordHash: passwordHash})
	} else {
		affected, err = queries.SetPasswordIfCurrent(storage.ctx, sqlgen.SetPasswordIfCurrentParams{ID: userID, ExpectedHash: expectedHash, PasswordHash: passwordHash})
	}
	if err != nil {
		return err
	}
	if affected != 1 {
		if expectedHash != "" {
			return domain.ErrConflict
		}
		return domain.ErrNotFound
	}
	if err := queries.RevokeUserSessions(storage.ctx, userID); err != nil {
		return err
	}
	return tx.Commit()
}

func (storage *postgresStore) CreateLoginDevice(user domain.User, name string, tokenHash []byte, profiles ...domain.DeviceProfile) (domain.Device, error) {
	profile, raw, err := deviceProfile(profiles)
	if err != nil {
		return domain.Device{}, err
	}
	now := time.Now().UTC()
	device := domain.Device{ID: newID(), AccountID: user.AccountID, Name: name, TokenHash: tokenHash, CreatedAt: now, LastSeenAt: now, Profile: profile}
	affected, err := sqlgen.New(storage.db.DB).CreateLoginDevice(storage.ctx, sqlgen.CreateLoginDeviceParams{
		ID: device.ID, UserID: user.ID, AccountID: user.AccountID, PasswordHash: user.PasswordHash,
		Name: name, TokenHash: hexHash(tokenHash), CreatedAt: rfc3339(now), LastSeenAt: rfc3339(now), Profile: raw,
	})
	if err != nil {
		return domain.Device{}, err
	}
	if affected != 1 {
		return domain.Device{}, domain.ErrNotFound
	}
	return device, nil
}
func (storage *postgresStore) CreateSession(session domain.Session) error {
	if session.Access == "" {
		session.Access = domain.SessionReadOnly
	}
	affected, err := sqlgen.New(storage.db.DB).CreateSession(storage.ctx, sqlgen.CreateSessionParams{ID: session.ID, UserID: session.UserID, DeviceID: session.DeviceID, TokenHash: session.TokenHash, ExpiresAt: session.ExpiresAt, PasswordHash: session.PasswordHash, Access: string(session.Access)})
	if err == nil && affected != 1 {
		return domain.ErrNotFound
	}
	return err
}
func (storage *postgresStore) SessionByToken(tokenHash string) (domain.Session, error) {
	session, err := sqlgen.New(storage.db.DB).SessionByToken(storage.ctx, tokenHash)
	if errors.Is(err, sql.ErrNoRows) {
		err = domain.ErrNotFound
	}
	return domain.Session{ID: session.ID, UserID: session.UserID, DeviceID: session.DeviceID, TokenHash: session.TokenHash, ExpiresAt: session.ExpiresAt, Access: domain.SessionAccess(session.Access)}, err
}
func (storage *postgresStore) DeleteSession(tokenHash string) error {
	return sqlgen.New(storage.db.DB).DeleteSession(storage.ctx, tokenHash)
}
