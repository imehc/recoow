package handler

import (
	"bytes"
	"net/http"
	"testing"

	"github.com/imehc/recoow/backend/internal/config"
)

// TestAccountRecoverRestoresOwnerDevice 校验恢复密钥能把账号找回，
// 并且错误密钥、空密钥一律拒绝（恢复密钥是唯一凭据，不能模糊匹配）。
func TestAccountRecoverRestoresOwnerDevice(t *testing.T) {
	e := newEnv(t)
	ownerToken, recoveryKey := e.initAccount(t)
	if resp, _ := e.request(t, http.MethodPost, "/api/account-recoveries", "", map[string]string{"recovery_key": "wrong-key"}); resp.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for wrong key, got %d", resp.Code)
	}
	if resp, _ := e.request(t, http.MethodPost, "/api/account-recoveries", "", map[string]string{"recovery_key": ""}); resp.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for empty key, got %d", resp.Code)
	}

	resp, data := e.request(t, http.MethodPost, "/api/account-recoveries", "",
		map[string]string{"recovery_key": recoveryKey, "device_name": "换机后"})
	if resp.Code != http.StatusCreated {
		t.Fatalf("recover status=%d body=%s", resp.Code, data)
	}
	recovered := decode[pairResponse](t, data)
	if recovered.AccountID != e.accountID {
		t.Fatalf("recover returned wrong account: %q vs %q", recovered.AccountID, e.accountID)
	}
	if recovered.DeviceToken == "" || recovered.DeviceToken == ownerToken {
		t.Fatal("recover must issue a fresh device token")
	}

	// 两台设备都属于同一账号，且新设备是主设备。
	_, devicesData := e.request(t, http.MethodGet, "/api/devices", recovered.DeviceToken, nil)
	devices := decode[struct {
		Devices []deviceView `json:"devices"`
	}](t, devicesData)
	if len(devices.Devices) != 2 {
		t.Fatalf("expected 2 devices on recovered account, got %+v", devices.Devices)
	}
	owners := 0
	for _, device := range devices.Devices {
		if device.IsOwner {
			owners++
		}
	}
	if owners != 2 {
		t.Fatalf("expected both devices to be owners, got %d", owners)
	}

	// 恢复出来的 token 必须真能同步（不只是能列设备）。
	if resp, body := e.request(t, http.MethodGet, "/api/sync/capabilities", recovered.DeviceToken, nil); resp.Code != http.StatusOK {
		t.Fatalf("recovered token cannot sync: %d %s", resp.Code, body)
	}
}

// TestAccountQuotaAndIsolation 校验账号数量上限与账号间数据隔离。
func TestAccountQuotaAndIsolation(t *testing.T) {
	e := newEnvWithConfig(t, config.Config{BootstrapToken: "bootstrap-secret", MaxAccounts: 2})
	firstToken, _ := e.initAccount(t)
	firstAccount := e.accountID

	resp, data := e.request(t, http.MethodPost, "/api/accounts", "",
		map[string]string{"bootstrap_token": "bootstrap-secret"})
	if resp.Code != http.StatusCreated {
		t.Fatalf("second init status=%d body=%s", resp.Code, data)
	}
	second := decode[initResponse](t, data)
	if second.AccountID == firstAccount {
		t.Fatal("second account reused first id")
	}
	if resp, _ := e.request(t, http.MethodPost, "/api/accounts", "",
		map[string]string{"bootstrap_token": "bootstrap-secret"}); resp.Code != http.StatusForbidden {
		t.Fatalf("expected quota rejection, got %d", resp.Code)
	}

	// 第一个账号写入的数据，第二个账号既看不到设备也拉不到变更。
	if resp, body := e.request(t, http.MethodPost, "/api/sync/mutations", firstToken, map[string]any{
		"items": []any{map[string]any{
			"table": "bills", "id": "b1", "operation": "insert",
			"updated_at_ms": 100, "device_id": "dev-a", "snapshot": map[string]any{"id": "b1"},
		}},
	}); resp.Code != http.StatusOK {
		t.Fatalf("push failed: %d %s", resp.Code, body)
	}
	_, pullData := e.request(t, http.MethodGet, "/api/sync/changes?cursor=0", second.DeviceToken, nil)
	if bytes.Contains(pullData, []byte(`"b1"`)) || bytes.Contains(pullData, []byte(`"bills"`)) {
		t.Fatalf("second account pulled first account data: %s", pullData)
	}
	_, devicesData := e.request(t, http.MethodGet, "/api/devices", second.DeviceToken, nil)
	devices := decode[struct {
		Devices []deviceView `json:"devices"`
	}](t, devicesData)
	if len(devices.Devices) != 1 {
		t.Fatalf("second account sees foreign devices: %+v", devices.Devices)
	}
}
