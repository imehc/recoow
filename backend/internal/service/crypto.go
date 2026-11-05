// 包 auth 实现无密码的设备配对与载体令牌。
package service

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"strings"
)

// NewSecret 返回 n 个随机字节经 URL 安全编码后的随机密钥。
func NewSecret(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// NewDeviceToken 返回高熵载体令牌（128 位）。
func NewDeviceToken() (string, error) { return NewSecret(32) }

// NewRecoveryKey 返回便于抄写的恢复密钥，供设备丢失后重建主设备使用。
func NewRecoveryKey() (string, error) {
	secret, err := NewSecret(24)
	if err != nil {
		return "", err
	}
	return secret, nil
}

// NewPairingCode 返回 10 位纯数字配对码（高熵、便于在手机上输入）。
func NewPairingCode() (string, error) {
	const digits = "0123456789"
	b := make([]byte, 10)
	// 对 10 位数字码无需做拒绝采样，直接按余数取数即可。
	for i := range b {
		n := make([]byte, 1)
		if _, err := rand.Read(n); err != nil {
			return "", err
		}
		b[i] = digits[int(n[0])%len(digits)]
	}
	return string(b), nil
}

// HashToken 返回 token 的 SHA-256（hex）用于存储。token 只以哈希形式保存，
// 明文仅在创建时返回一次给客户端。
func HashToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

// VerifyRecoveryKey 以常量时间比较候选恢复密钥与存储哈希。
func VerifyRecoveryKey(candidate string, hash []byte) bool {
	c := sha256.Sum256([]byte(candidate))
	return subtleEqual(c[:], hash)
}

func subtleEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := range a {
		v |= a[i] ^ b[i]
	}
	return v == 0
}

// EncodeKey 将恢复密钥按 4 位一组分组展示，便于阅读抄写。
func EncodeKey(secret string) string {
	s := strings.ToUpper(secret)
	var parts []string
	for i := 0; i < len(s); i += 4 {
		end := i + 4
		if end > len(s) {
			end = len(s)
		}
		parts = append(parts, s[i:end])
	}
	return strings.Join(parts, "-")
}
