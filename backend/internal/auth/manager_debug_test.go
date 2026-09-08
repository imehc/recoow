package auth

import (
	"testing"

	"github.com/imehc/recoow/backend/internal/store"
)

// TestManagerPairSingleUse 校验配对码一次性使用与设备注册。
func TestManagerPairSingleUse(t *testing.T) {
	st := store.NewMemoryStore()
	m := NewManager(st, 0)

	recoveryKey, _, _, err := m.InitAccount()
	if err != nil {
		t.Fatal(err)
	}
	if recoveryKey == "" {
		t.Fatal("应返回恢复密钥")
	}
	acc, err := st.GetAccount()
	if err != nil {
		t.Fatal(err)
	}
	code, err := m.IssuePairingCode(acc.ID)
	if err != nil {
		t.Fatal(err)
	}
	// 首次配对成功。
	accountID, token, deviceID, err := m.Pair(code, "iPad")
	if err != nil {
		t.Fatalf("配对失败: %v", err)
	}
	if accountID != acc.ID || token == "" || deviceID == "" {
		t.Fatalf("配对返回异常: acc=%s token=%d did=%s", accountID, len(token), deviceID)
	}
	// 同一配对码再次使用应失败（一次性）。
	if _, _, _, err := m.Pair(code, "again"); err != ErrBadCode {
		t.Fatalf("重复使用配对码应报错, 实际 %v", err)
	}
	// 未知 token 无法授权。
	if _, err := m.Authorize("not-a-token"); err == nil {
		t.Fatal("未知 token 应授权失败")
	}
	// 用刚拿到的 token 应能授权。
	if _, err := m.Authorize(token); err != nil {
		t.Fatalf("合法 token 应能授权: %v", err)
	}
}
