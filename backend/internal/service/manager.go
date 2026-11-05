package service

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
)

var (
	ErrBadCode       = errors.New("invalid or expired pairing code")
	ErrUnauthorized  = errors.New("unauthorized")
	ErrCodeExhausted = errors.New("pairing code already used")
)

// Manager 统一管理设备凭据；配对码由仓储持久化并原子消费。
type Manager struct {
	st  domain.Store
	ttl time.Duration
}

func NewManager(st domain.Store, ttl time.Duration) *Manager {
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return &Manager{st: st, ttl: ttl}
}

// InitAccount 新建一个账号，返回账号 id、主设备的恢复密钥与设备令牌。
// 恢复密钥只返回一次，服务端只保留其哈希。
func (m *Manager) InitAccount() (accountID, recoveryKey, token, deviceID string, err error) {
	recoveryKey, err = NewRecoveryKey()
	if err != nil {
		return "", "", "", "", err
	}
	token, err = NewDeviceToken()
	if err != nil {
		return "", "", "", "", err
	}
	acc, err := m.st.InitAccount(sha256sum(recoveryKey))
	if err != nil {
		return "", "", "", "", err
	}
	dev, err := m.st.CreateDevice(acc.ID, "本机（主设备）", HashToken(token), true)
	if err != nil {
		return "", "", "", "", err
	}
	return acc.ID, recoveryKey, token, dev.ID, nil
}

// Recover 用恢复密钥重建一台主设备：换机或重装后凭密钥把账号找回。
// 恢复密钥即账号凭据，因此这里只校验其哈希、不再回传明文。
func (m *Manager) Recover(recoveryKey, deviceName string) (accountID, token, deviceID string, err error) {
	if recoveryKey == "" {
		return "", "", "", ErrUnauthorized
	}
	acc, err := m.st.GetAccountByRecoveryKeyHash(sha256sum(recoveryKey))
	if err != nil {
		return "", "", "", ErrUnauthorized
	}
	token, err = NewDeviceToken()
	if err != nil {
		return "", "", "", err
	}
	if deviceName == "" {
		deviceName = "本机（主设备）"
	}
	dev, err := m.st.CreateDevice(acc.ID, deviceName, HashToken(token), true)
	if err != nil {
		return "", "", "", err
	}
	return acc.ID, token, dev.ID, nil
}

// IssuePairingCode 为待加入的新设备生成一条短期一次性配对码。
func (m *Manager) IssuePairingCode(accountID string) (string, error) {
	code, err := NewPairingCode()
	if err != nil {
		return "", err
	}
	err = m.st.IssuePairingCode(hex.EncodeToString(HashToken(code)), accountID, time.Now().UTC().Add(m.ttl))
	return code, err
}
func (m *Manager) Pair(code, deviceName string) (accountID, token, deviceID string, err error) {
	accountID, err = m.st.ConsumePairingCode(hex.EncodeToString(HashToken(code)))
	if err != nil {
		return "", "", "", ErrBadCode
	}
	token, err = NewDeviceToken()
	if err != nil {
		return "", "", "", err
	}
	device, err := m.st.CreateDevice(accountID, deviceName, HashToken(token), false)
	if err != nil {
		return "", "", "", err
	}
	return accountID, token, device.ID, nil
}

// Authorize 把明文载体令牌解析为对应的设备。
func (m *Manager) Authorize(rawToken string) (domain.Device, error) {
	if rawToken == "" {
		return domain.Device{}, ErrUnauthorized
	}
	d, err := m.st.GetDeviceByTokenHash(HashToken(rawToken))
	if err != nil {
		return domain.Device{}, ErrUnauthorized
	}
	user, userErr := m.st.UserByAccount(d.AccountID)
	if userErr != nil && !errors.Is(userErr, domain.ErrNotFound) || userErr == nil && user.Disabled {
		return domain.Device{}, ErrUnauthorized
	}
	return d, nil
}

func sha256sum(s string) []byte {
	sum := sha256.Sum256([]byte(s))
	return sum[:]
}
