package auth

import (
	"crypto/sha256"
	"errors"
	"sync"
	"time"

	"github.com/imehc/recoow/backend/internal/store"
)

var (
	ErrBadCode       = errors.New("invalid or expired pairing code")
	ErrUnauthorized  = errors.New("unauthorized")
	ErrCodeExhausted = errors.New("pairing code already used")
)

// pairingRequest 跟踪一条一次性、限时的配对码。
type pairingRequest struct {
	accountID string
	hash      [32]byte
	expiresAt time.Time
	used      bool
}

// Manager 面向 store 签发并校验设备令牌与配对码。配对码仅存于内存（单进程服务）。
type Manager struct {
	mu       sync.Mutex
	st       store.Store
	pairings map[[32]byte]*pairingRequest
	ttl      time.Duration
}

func NewManager(st store.Store, ttl time.Duration) *Manager {
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return &Manager{st: st, pairings: map[[32]byte]*pairingRequest{}, ttl: ttl}
}

// InitAccount 引导（单）账号，返回主设备的恢复密钥与设备令牌。恢复密钥只返回一次。
func (m *Manager) InitAccount() (recoveryKey, token, deviceID string, err error) {
	recoveryKey, err = NewRecoveryKey()
	if err != nil {
		return "", "", "", err
	}
	token, err = NewDeviceToken()
	if err != nil {
		return "", "", "", err
	}
	acc, err := m.st.InitAccount(sha256sum(recoveryKey))
	if err != nil {
		return "", "", "", err
	}
	dev, err := m.st.CreateDevice(acc.ID, "本机（主设备）", HashToken(token), true)
	if err != nil {
		return "", "", "", err
	}
	return recoveryKey, token, dev.ID, nil
}

// IssuePairingCode 为待加入的新设备生成一条短期一次性配对码。
func (m *Manager) IssuePairingCode(accountID string) (string, error) {
	code, err := NewPairingCode()
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pairings[sha256.Sum256([]byte(code))] = &pairingRequest{
		accountID: accountID,
		hash:      sha256.Sum256([]byte(code)),
		expiresAt: time.Now().Add(m.ttl),
	}
	return code, nil
}

// Pair 消费配对码并注册一台新设备，返回其令牌。
func (m *Manager) Pair(code, deviceName string) (accountID, token, deviceID string, err error) {
	m.mu.Lock()
	h := sha256.Sum256([]byte(code))
	p, ok := m.pairings[h]
	if ok && !p.used && time.Now().Before(p.expiresAt) {
		p.used = true
		delete(m.pairings, h)
	}
	m.mu.Unlock()
	if !ok || p == nil {
		return "", "", "", ErrBadCode
	}
	token, err = NewDeviceToken()
	if err != nil {
		return "", "", "", err
	}
	dev, err := m.st.CreateDevice(p.accountID, deviceName, HashToken(token), false)
	if err != nil {
		return "", "", "", err
	}
	return p.accountID, token, dev.ID, nil
}

// Authorize 把明文载体令牌解析为对应的设备。
func (m *Manager) Authorize(rawToken string) (store.Device, error) {
	if rawToken == "" {
		return store.Device{}, ErrUnauthorized
	}
	d, err := m.st.GetDeviceByTokenHash(HashToken(rawToken))
	if err != nil {
		return store.Device{}, ErrUnauthorized
	}
	return d, nil
}

func sha256sum(s string) []byte {
	sum := sha256.Sum256([]byte(s))
	return sum[:]
}
