package handler

import (
	"encoding/hex"
	"testing"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/service"
)

func TestAccountWebAccessOwnerIsolationAndDatasetReset(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	_, raw := e.request(t, "POST", "/api/accounts", "", map[string]any{"bootstrap_token": "bootstrap-secret"})
	other := decode[initResponse](t, raw)
	_, raw = e.request(t, "GET", "/api/accounts/current/web-access", owner, nil)
	if decode[generated.AccountWebAccess](t, raw).Enabled {
		t.Fatal("account must default to read only")
	}
	credentials := map[string]any{"username": "account.access", "password": "web-password"}
	e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	_, raw = e.request(t, "POST", "/api/sessions", "", credentials)
	login := decode[loginResponse](t, raw)
	_, err := e.st.CreateDevice(e.accountID, "Secondary", service.HashToken("secondary"), false)
	if err != nil {
		t.Fatal(err)
	}
	// 历史 Web 关联设备曾可能是 owner，仍不得通过会话自行提升权限。
	ownerDevice, err := e.st.GetDeviceByTokenHash(service.HashToken(owner))
	if err != nil {
		t.Fatal(err)
	}
	user, err := e.st.UserByAccount(e.accountID)
	if err != nil {
		t.Fatal(err)
	}
	legacy := domain.Session{ID: "legacy-owner", UserID: login.UserId, DeviceID: ownerDevice.ID, TokenHash: hex.EncodeToString(service.HashToken("legacy-owner-token")), PasswordHash: user.PasswordHash, ExpiresAt: time.Now().Add(time.Hour)}
	if err := e.st.CreateSession(legacy); err != nil {
		t.Fatal(err)
	}
	for _, input := range []any{map[string]any{}, map[string]any{"enabled": nil}, map[string]any{"enabled": "true"}} {
		if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, input); response.Code != 400 {
			t.Fatal("invalid setting accepted")
		}
	}
	for _, token := range []string{login.AccessToken, "secondary", "legacy-owner-token"} {
		for _, method := range []string{"GET", "PUT"} {
			if response, _ := e.request(t, method, "/api/accounts/current/web-access", token, map[string]any{"enabled": true}); response.Code != 403 {
				t.Fatalf("non-owner access: %s %d", method, response.Code)
			}
		}
	}
	if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, map[string]any{"enabled": true}); response.Code != 200 {
		t.Fatal("owner enable")
	}
	_, raw = e.request(t, "GET", "/api/accounts/current/web-access", other.DeviceToken, nil)
	if decode[generated.AccountWebAccess](t, raw).Enabled {
		t.Fatal("authorization leaked to another account")
	}
	state, _ := e.st.SyncState(e.accountID)
	if _, err := e.st.ClearDataset(e.accountID, state.Generation); err != nil {
		t.Fatal(err)
	}
	_, raw = e.request(t, "GET", "/api/users/me", login.AccessToken, nil)
	me := decode[generated.IdentityResponse](t, raw)
	if me.GetAccess() != "read_write" {
		t.Fatal("dataset reset erased account authorization")
	}
}
