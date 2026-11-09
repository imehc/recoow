package service

import (
	"encoding/hex"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"strings"
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
	dummy, err := HashPassword("dummy-password-1")
	if err != nil {
		return nil, err
	}
	return &Identity{store: store, ttl: ttl, dummyHash: dummy}, nil
}
func (identity *Identity) Bind(accountID, username, password string) (domain.User, error) {
	if _, err := identity.store.UserByAccount(accountID); err == nil {
		return domain.User{}, domain.ErrConflict
	} else if !errors.Is(err, domain.ErrNotFound) {
		return domain.User{}, err
	}
	encoded, err := HashPassword(password)
	if err != nil {
		return domain.User{}, err
	}
	for attempt := 0; attempt < 5; attempt++ {
		normalized, err := identity.username(username)
		if err != nil {
			return domain.User{}, err
		}
		user, err := identity.store.BindUser(accountID, normalized, encoded)
		if !errors.Is(err, domain.ErrConflict) || username != "" {
			return user, err
		}
		// 并发绑定与随机用户名碰撞区分处理，不覆盖已有身份。
		if _, err := identity.store.UserByAccount(accountID); err == nil {
			return domain.User{}, domain.ErrConflict
		} else if !errors.Is(err, domain.ErrNotFound) {
			return domain.User{}, err
		}
	}
	return domain.User{}, domain.ErrConflict
}

func (identity *Identity) username(value string) (string, error) {
	if value == "" {
		return NewUsername()
	}
	return NormalizeUsername(value)
}

func (identity *Identity) Register(username, password string) (domain.Account, domain.User, string, error) {
	encoded, err := HashPassword(password)
	if err != nil {
		return domain.Account{}, domain.User{}, "", err
	}
	recovery, err := NewRecoveryKey()
	if err != nil {
		return domain.Account{}, domain.User{}, "", err
	}
	for attempt := 0; attempt < 5; attempt++ {
		normalized, err := identity.username(username)
		if err != nil {
			return domain.Account{}, domain.User{}, "", err
		}
		account, user, err := identity.store.RegisterUser(normalized, encoded, HashToken(recovery))
		if !errors.Is(err, domain.ErrConflict) || username != "" {
			return account, user, recovery, err
		}
	}
	return domain.Account{}, domain.User{}, "", domain.ErrConflict
}

func (identity *Identity) verify(username, password string) (domain.User, error) {
	normalized, _ := NormalizeUsername(username)
	user, err := identity.store.UserByUsername(normalized)
	encoded := identity.dummyHash
	if err == nil {
		encoded = user.PasswordHash
	}
	// 不存在的用户也执行密码哈希，统一失败响应和计算成本。
	verified := VerifyPassword(encoded, password)
	if err != nil || !verified || user.Disabled {
		return domain.User{}, ErrUnauthorized
	}
	return user, nil
}

func (identity *Identity) LoginDevice(username, password, name string, profiles ...domain.DeviceProfile) (domain.User, domain.Device, string, error) {
	if err := ValidateDeviceName(name); err != nil {
		return domain.User{}, domain.Device{}, "", err
	}
	user, err := identity.verify(username, password)
	if err != nil {
		return domain.User{}, domain.Device{}, "", err
	}
	token, err := NewDeviceToken()
	if err != nil {
		return domain.User{}, domain.Device{}, "", err
	}
	if name == "" {
		name = "登录设备"
	}
	device, err := identity.store.CreateLoginDevice(user, name, HashToken(token), profiles...)
	if errors.Is(err, domain.ErrNotFound) {
		err = ErrUnauthorized
	}
	return user, device, token, err
}

func (identity *Identity) Login(username, password string, profiles ...domain.DeviceProfile) (Login, error) {
	user, err := identity.verify(username, password)
	if err != nil {
		return Login{}, err
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
	device, err := identity.store.CreateDevice(user.AccountID, "Web 会话", HashToken(hiddenToken), true, profiles...)
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
	err = identity.store.SetPasswordIfCurrent(user.ID, user.PasswordHash, encoded)
	if errors.Is(err, domain.ErrConflict) {
		return ErrUnauthorized
	}
	return err
}

// ResetPassword 用恢复密钥找回现有账号，不创建账号或设备。
func (identity *Identity) ResetPassword(recoveryKey, next string) (domain.User, error) {
	recoveryKey = strings.TrimSpace(recoveryKey)
	if recoveryKey == "" || len(recoveryKey) > 256 {
		return domain.User{}, ErrUnauthorized
	}
	account, err := identity.store.GetAccountByRecoveryKeyHash(HashToken(recoveryKey))
	if err != nil {
		return domain.User{}, ErrUnauthorized
	}
	user, err := identity.store.UserByAccount(account.ID)
	if err != nil || user.Disabled {
		return domain.User{}, ErrUnauthorized
	}
	encoded, err := HashPassword(next)
	if err != nil {
		return domain.User{}, err
	}
	if err := identity.store.SetPasswordIfCurrent(user.ID, user.PasswordHash, encoded); err != nil {
		if errors.Is(err, domain.ErrConflict) {
			return domain.User{}, ErrUnauthorized
		}
		return domain.User{}, err
	}
	user.PasswordHash = encoded
	return user, nil
}
