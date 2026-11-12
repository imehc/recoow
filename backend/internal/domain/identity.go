package domain

import "time"

type User struct {
	ID           string `json:"userId"`
	AccountID    string `json:"accountId"`
	Username     string `json:"username"`
	PasswordHash string `json:"-"`
	Disabled     bool   `json:"-"`
}
type Session struct {
	ID        string
	UserID    string
	DeviceID  string
	TokenHash string
	// 会话创建时再次核对已验证的密码版本，防止密码变更后旧登录仍签发会话。
	PasswordHash string
	ExpiresAt    time.Time
	Access       SessionAccess
}

type SessionAccess string

const (
	SessionReadOnly  SessionAccess = "read_only"
	SessionReadWrite SessionAccess = "read_write"
)

type IdentityStore interface {
	BindUser(accountID, username, passwordHash string) (User, error)
	RegisterUser(username, passwordHash string, recoveryHash []byte) (Account, User, error)
	UserByUsername(username string) (User, error)
	UserByAccount(accountID string) (User, error)
	SetPassword(userID, passwordHash string) error
	SetPasswordIfCurrent(userID, expectedHash, passwordHash string) error
	// CreateLoginDevice 在同一事务核对密码版本并创建非主设备。
	CreateLoginDevice(user User, name string, tokenHash []byte, profiles ...DeviceProfile) (Device, error)
	// CreatePairingSession 原子消费配对码并创建非主设备和短期会话。
	CreatePairingSession(codeHash string, session Session, deviceTokenHash []byte, profiles ...DeviceProfile) (User, Device, error)
	CreateSession(session Session) error
	SessionByToken(tokenHash string) (Session, error)
	DeleteSession(tokenHash string) error
}
