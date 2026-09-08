package api

import (
	"net/http"
	"testing"
)

// TestPairingCodeRequiresOwner 校验生成配对码需要主设备身份。
func TestPairingCodeRequiresOwner(t *testing.T) {
	e := newEnv(t)
	ownerToken, _ := e.initAccount(t)

	// 主设备可生成配对码。
	resp, data := e.request(t, http.MethodPost, "/api/devices/pairing-code", ownerToken, nil)
	if resp.Code != http.StatusOK {
		t.Fatalf("主设备应能生成配对码, 实际 %d body=%s", resp.Code, data)
	}
	// 无 token 时被拒绝。
	if resp, _ := e.request(t, http.MethodPost, "/api/devices/pairing-code", "", nil); resp.Code != http.StatusUnauthorized {
		t.Fatalf("无 token 应返回 401, 实际 %d", resp.Code)
	}
}
