package service

import (
	"encoding/hex"
	"github.com/imehc/recoow/backend/internal/domain"
	"time"
)

type Identity struct {
	store     domain.Store
	ttl       time.Duration
	dummyHash string
}
type Login struct {
	User      domain.User
	DeviceID  string
	Token     string
	ExpiresAt time.Time
}

func NewIdentity(store domain.Store, ttl time.Duration) (*Identity, error) {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	dummy, err := HashPassword("unusable-dummy-password-for-timing")
	if err != nil {
		return nil, err
	}
	return &Identity{store: store, ttl: ttl, dummyHash: dummy}, nil
}
func (identity *Identity) Bind(accountID, username, password string) (domain.User, error) {
	normalized, err := NormalizeUsername(username)
	if err != nil {
		return domain.User{}, err
	}
	encoded, err := HashPassword(password)
	if err != nil {
		return domain.User{}, err
	}
	return identity.store.BindUser(accountID, normalized, encoded)
}
func (identity *Identity) Register(username, password string) (domain.Account, domain.User, string, error) {
	normalized, err := NormalizeUsername(username)
	if err != nil {
		return domain.Account{}, domain.User{}, "", err
	}
	encoded, err := HashPassword(password)
	if err != nil {
		return domain.Account{}, domain.User{}, "", err
	}
	recovery, err := NewRecoveryKey()
	if err != nil {
		return domain.Account{}, domain.User{}, "", err
	}
	account, user, err := identity.store.RegisterUser(normalized, encoded, HashToken(recovery))
	return account, user, recovery, err
}
func (identity *Identity) Login(username, password string) (Login, error) {
	normalized, _ := NormalizeUsername(username)
	user, err := identity.store.UserByUsername(normalized)
	encoded := identity.dummyHash
	if err == nil {
		encoded = user.PasswordHash
	}
	// 不存在的用户也执行密码哈希，统一失败响应和计算成本。
	verified := VerifyPassword(encoded, password)
	if err != nil || !verified || user.Disabled {
		return Login{}, ErrUnauthorized
	}
	token, err := NewDeviceToken()
	if err != nil {
		return Login{}, err
	}
	hiddenToken, err := NewDeviceToken()
	if err != nil {
		return Login{}, err
	}
	// 会话凭据与设备凭据分离，避免过期会话被当成长期设备 token。
	device, err := identity.store.CreateDevice(user.AccountID, "Web 会话", HashToken(hiddenToken), true)
	if err != nil {
		return Login{}, err
	}
	id, err := NewSecret(24)
	if err != nil {
		_ = identity.store.DeleteDevice(device.ID)
		return Login{}, err
	}
	session := domain.Session{ID: id, UserID: user.ID, DeviceID: device.ID, TokenHash: hex.EncodeToString(HashToken(token)), PasswordHash: user.PasswordHash, ExpiresAt: time.Now().UTC().Add(identity.ttl), Access: domain.SessionReadOnly}
	if err := identity.store.CreateSession(session); err != nil {
		_ = identity.store.DeleteDevice(device.ID)
		return Login{}, err
	}
	return Login{User: user, DeviceID: device.ID, Token: token, ExpiresAt: session.ExpiresAt}, nil
}
func (identity *Identity) Authorize(token string) (domain.Device, error) {
	device, _, err := identity.AuthorizeSession(token)
	return device, err
}

func (identity *Identity) AuthorizeSession(token string) (domain.Device, domain.Session, error) {
	session, err := identity.store.SessionByToken(hex.EncodeToString(HashToken(token)))
	if err != nil {
		return domain.Device{}, domain.Session{}, ErrUnauthorized
	}
	device, err := identity.store.GetDevice(session.DeviceID)
	if err != nil {
		return domain.Device{}, domain.Session{}, ErrUnauthorized
	}
	return device, session, nil
}
func (identity *Identity) Logout(token string) error {
	return identity.store.DeleteSession(hex.EncodeToString(HashToken(token)))
}
func (identity *Identity) ChangePassword(accountID, current, next string) error {
	user, err := identity.store.UserByAccount(accountID)
	if err != nil || user.Disabled || !VerifyPassword(user.PasswordHash, current) {
		return ErrUnauthorized
	}
	encoded, err := HashPassword(next)
	if err != nil {
		return err
	}
	return identity.store.SetPassword(user.ID, encoded)
}
