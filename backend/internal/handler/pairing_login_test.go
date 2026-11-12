package handler

import (
	"bytes"
	"encoding/hex"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/service"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPairingWebLoginWithoutPasswordAndOwnerRevocation(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	_, body := e.request(t, "POST", "/api/device-pairing-codes", owner, nil)
	code := decode[map[string]any](t, body)["code"].(string)
	request := httptest.NewRequest("POST", "/api/pairing-sessions", bytes.NewBufferString(`{"code":"`+code+`"}`))
	request.Header.Set("Origin", "https://example.com")
	request.Header.Set("X-Recoow-Client", "web")
	response := httptest.NewRecorder()
	e.handler.ServeHTTP(response, request)
	if response.Code != 200 {
		t.Fatalf("pairing login: %d %s", response.Code, response.Body.String())
	}
	login := decode[generated.LoginResponse](t, response.Body.Bytes())
	if login.AccountId != e.accountID || login.UserId != "" || login.Username != "" || login.GetAccess() != generated.SessionAccess(domain.SessionReadOnly) {
		t.Fatal("wrong account identity or initial access")
	}
	if bytes.Contains(response.Body.Bytes(), []byte("device_token")) {
		t.Fatal("long-term device token exposed")
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || !cookies[0].Secure || cookies[0].SameSite != http.SameSiteStrictMode || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("unsafe session cookie")
	}
	if _, err := service.NewManager(e.st, time.Minute).Authorize(login.AccessToken); err == nil {
		t.Fatal("session accepted as a long-term device credential")
	}
	device, _ := e.st.GetDevice(login.DeviceId)
	if device.IsOwner || device.Name != "Web 配对会话" || device.Profile == nil || device.Profile.Client != "web" {
		t.Fatalf("wrong web device: %+v", device)
	}
	for _, path := range []string{"/api/users/me", "/api/statistics", "/api/devices"} {
		if response, body := e.request(t, "GET", path, login.AccessToken, nil); response.Code != 200 {
			t.Fatalf("read %s: %d %s", path, response.Code, body)
		}
	}
	if response, _ := e.request(t, "POST", "/api/pairing-sessions", "", map[string]string{"code": code}); response.Code != 401 {
		t.Fatal("reused code accepted")
	}
	if response, _ := e.request(t, "GET", "/api/accounts/current/web-access", login.AccessToken, nil); response.Code != 403 {
		t.Fatal("paired Web can manage authorization")
	}
	if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, map[string]bool{"enabled": true}); response.Code != 200 {
		t.Fatal("owner authorization failed")
	}
	response, body = e.request(t, "GET", "/api/users/me", login.AccessToken, nil)
	currentIdentity := decode[generated.IdentityResponse](t, body)
	if response.Code != 200 || currentIdentity.GetAccess() != generated.SessionAccess(domain.SessionReadWrite) {
		t.Fatal("pairing session did not apply account permission")
	}
	if response, _ := e.request(t, "DELETE", "/api/devices/"+login.DeviceId, owner, nil); response.Code != 200 {
		t.Fatal("owner could not revoke Web device")
	}
	for _, test := range []struct{ method, path string }{{"GET", "/api/users/me"}, {"GET", "/api/statistics"}, {"GET", "/api/bills"}, {"POST", "/api/bills"}, {"DELETE", "/api/sessions/current"}, {"GET", "/api/media/objects/x"}} {
		if response, _ := e.request(t, test.method, test.path, login.AccessToken, nil); response.Code != 401 {
			t.Fatalf("revoked credential accepted %s %s: %d", test.method, test.path, response.Code)
		}
	}
	if response, _ := e.request(t, "GET", "/api/devices", owner, nil); response.Code != 200 {
		t.Fatal("owner affected by revocation")
	}
	// Cookie 与 Bearer 的撤销行为一致。
	request = httptest.NewRequest("GET", "/api/users/me", nil)
	request.AddCookie(cookies[0])
	response = httptest.NewRecorder()
	e.handler.ServeHTTP(response, request)
	if response.Code != 401 {
		t.Fatal("revoked cookie accepted")
	}
}

func TestPairingWebLoginRejectsExpiredMalformedAndCrossOriginCodes(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	expired := "0123456789"
	if err := e.st.IssuePairingCode(hex.EncodeToString(service.HashToken(expired)), e.accountID, time.Now().Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	for _, code := range []string{expired, "123", "abcdefghij", "0000000000"} {
		if response, _ := e.request(t, "POST", "/api/pairing-sessions", "", map[string]string{"code": code}); response.Code != 401 {
			t.Fatalf("invalid code accepted: %d", response.Code)
		}
	}
	_, body := e.request(t, "POST", "/api/device-pairing-codes", owner, nil)
	code := decode[map[string]any](t, body)["code"].(string)
	req := httptest.NewRequest("POST", "/api/pairing-sessions", bytes.NewBufferString(`{"code":"`+code+`"}`))
	req.Header.Set("Origin", "https://other.example")
	response := httptest.NewRecorder()
	e.handler.ServeHTTP(response, req)
	if response.Code != 403 {
		t.Fatal("cross-origin pairing accepted")
	}
	if response, _ := e.request(t, "POST", "/api/pairing-sessions", "", map[string]string{"code": code}); response.Code != 200 {
		t.Fatal("origin failure consumed pairing code")
	}
}
