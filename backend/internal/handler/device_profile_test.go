package handler

import (
	"bytes"
	"github.com/imehc/recoow/backend/internal/domain"
	"net/http/httptest"
	"testing"
)

func TestRegistrationHeadersAndAuthenticatedProfileUpdates(t *testing.T) {
	e := newEnv(t)
	r := httptest.NewRequest("POST", "/api/accounts", bytes.NewBufferString(`{"bootstrap_token":"bootstrap-secret"}`))
	r.Header.Set("X-Recoow-Client", "ios")
	r.Header.Set("X-Recoow-OS", "ios")
	r.Header.Set("X-Recoow-Device-Id", "local-installation")
	r.Header.Set("X-Recoow-App-Version", "1.0")
	w := httptest.NewRecorder()
	e.handler.ServeHTTP(w, r)
	if w.Code != 201 {
		t.Fatal(w.Body.String())
	}
	account := decode[initResponse](t, w.Body.Bytes())
	device, err := e.st.GetDevice(account.DeviceID)
	if err != nil || device.Profile == nil || device.Profile.Client != "ios" || device.Profile.LocalDeviceID != "local-installation" {
		t.Fatalf("registration profile: %+v %v", device, err)
	}
	profile := map[string]any{"client": "ios", "os": "ios", "local_device_id": "local-installation", "app_version": "1.1", "device_name": "My phone"}
	w, _ = e.request(t, "PUT", "/api/devices/current/profile", account.DeviceToken, profile)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	device, _ = e.st.GetDevice(account.DeviceID)
	if device.Name != "My phone" || device.Profile.AppVersion != "1.1" {
		t.Fatal("profile not refreshed")
	}
	credentials := map[string]any{"username": "profile.user", "password": "test-password"}
	w, _ = e.request(t, "POST", "/api/users/me/credentials", account.DeviceToken, credentials)
	if w.Code != 201 {
		t.Fatal(w.Body.String())
	}
	w, data := e.request(t, "POST", "/api/sessions", "", credentials)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var session struct {
		AccessToken string `json:"access_token"`
		DeviceID    string `json:"device_id"`
	}
	if err := decodeResponse(data, &session); err != nil {
		t.Fatal(err)
	}
	w, _ = e.request(t, "PUT", "/api/devices/current/profile", session.AccessToken, profile)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	web, _ := e.st.GetDevice(session.DeviceID)
	if web.Profile == nil || web.Profile.Client != "web" || web.Profile.LocalDeviceID != "" {
		t.Fatal("web session claimed mobile identity")
	}
	w, _ = e.request(t, "POST", "/api/sync/mutations", session.AccessToken, map[string]any{"items": []domain.PushItem{}})
	if w.Code != 403 {
		t.Fatal("profile update granted business write access")
	}
}
