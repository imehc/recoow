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
}

type IdentityStore interface {
	BindUser(accountID, username, passwordHash string) (User, error)
	RegisterUser(username, passwordHash string, recoveryHash []byte) (Account, User, error)
	UserByUsername(username string) (User, error)
	UserByAccount(accountID string) (User, error)
	SetPassword(userID, passwordHash string) error
	CreateSession(session Session) error
	SessionByToken(tokenHash string) (Session, error)
	DeleteSession(tokenHash string) error
}
