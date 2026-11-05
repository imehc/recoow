package handler

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

func TestWebSessionWritesShareMobileSync(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, _ := fullSyncEnvironment(t, registry)
	credentials := map[string]any{"username": "Web.Owner", "password": "a-long-password-for-web"}
	response, body := e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	if response.Code != 201 || bytes.Contains(body, []byte("password")) {
		t.Fatalf("bind: %d %s", response.Code, body)
	}
	response, body = e.request(t, "POST", "/api/sessions", "", credentials)
	if response.Code != 200 {
		t.Fatalf("login: %d %s", response.Code, body)
	}
	login := decode[loginResponse](t, body)
	if login.AccountId != e.accountID || login.Username != "web.owner" {
		t.Fatal("login must retain account identity")
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || !cookies[0].Secure || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatal("session cookie must be secure")
	}
	manifest, _ := registry.Get("diary_tags")
	snapshot := contractSnapshot(manifest, "web-tag")
	snapshot["name"] = "Web tag"
	snapshot["future_field"] = map[string]any{"retained": true}
	mutation := map[string]any{"mutationId": "web-create", "baseVersion": 0, "dataVersion": 1, "updatedAtMs": 1000, "snapshot": snapshot}
	response, body = e.request(t, "POST", "/api/diary-tags", login.AccessToken, mutation)
	if response.Code != 201 {
		t.Fatalf("create: %d %s", response.Code, body)
	}
	created := decode[domain.PushResult](t, body)
	response, body = e.request(t, "POST", "/api/diary-tags", login.AccessToken, mutation)
	if response.Code != 201 || decode[domain.PushResult](t, body).Version != created.Version {
		t.Fatalf("retry changed revision: %s", body)
	}
	response, body = e.request(t, "GET", "/api/diary-tags?limit=1&q=Web", owner, nil)
	page := decode[domain.RecordPage](t, body)
	if response.Code != 200 || len(page.Rows) != 1 || !bytes.Contains(page.Rows[0].Snapshot, []byte("future_field")) {
		t.Fatalf("mobile read: %d %s", response.Code, body)
	}
	mutation["mutationId"] = "stale-web-update"
	mutation["updatedAtMs"] = 2000
	snapshot["name"] = "Unconfirmed edit"
	response, body = e.request(t, "PUT", "/api/diary-tags/web-tag", login.AccessToken, mutation)
	if response.Code != 409 || decode[domain.PushResult](t, body).Status != domain.PushConflict {
		t.Fatalf("stale write: %d %s", response.Code, body)
	}
	other, _ := e.initAccount(t)
	response, body = e.request(t, "GET", "/api/diary-tags/web-tag", other, nil)
	if response.Code != 404 {
		t.Fatalf("other account saw detail: %d %s", response.Code, body)
	}
	response, body = e.request(t, "GET", "/api/statistics", other, nil)
	if response.Code != 200 || decode[domain.Statistics](t, body).RecordCounts["diary_tags"] != 0 {
		t.Fatalf("statistics leaked: %s", body)
	}
	// Cookie 写请求必须验证来源，设备 Bearer 请求保留已有行为。
	request := httptest.NewRequest("DELETE", "/api/sessions/current", nil)
	request.AddCookie(cookies[0])
	denied := httptest.NewRecorder()
	e.handler.ServeHTTP(denied, request)
	if denied.Code != 403 {
		t.Fatal("cookie write without origin must fail")
	}
	request.Header.Set("Origin", "https://example.com")
	allowed := httptest.NewRecorder()
	e.handler.ServeHTTP(allowed, request)
	if allowed.Code != 200 {
		t.Fatalf("logout: %d %s", allowed.Code, allowed.Body.String())
	}
	response, _ = e.request(t, "GET", "/api/users/me", login.AccessToken, nil)
	if response.Code != 401 {
		t.Fatal("logout must revoke bearer session too")
	}
	response, _ = e.request(t, "GET", "/api/devices", owner, nil)
	if response.Code != 200 {
		t.Fatal("logout must preserve original device credential")
	}
}

func TestPasswordChangeRevokesAllWebSessions(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	credentials := map[string]any{"username": "password.owner", "password": "original-long-password"}
	response, body := e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	if response.Code != 201 {
		t.Fatalf("bind: %s", body)
	}
	response, body = e.request(t, "POST", "/api/sessions", "", credentials)
	if response.Code != 200 {
		t.Fatalf("login: %s", body)
	}
	login := decode[loginResponse](t, body)
	response, body = e.request(t, "PUT", "/api/users/me/password", login.AccessToken, map[string]any{"currentPassword": credentials["password"], "newPassword": "replacement-long-password"})
	if response.Code != 200 {
		t.Fatalf("password: %s", body)
	}
	response, _ = e.request(t, "GET", "/api/devices", login.AccessToken, nil)
	if response.Code != 401 {
		t.Fatal("password change must revoke existing sessions")
	}
	response, _ = e.request(t, "POST", "/api/sessions", "", credentials)
	if response.Code != 401 {
		t.Fatal("old password must fail")
	}
	response, _ = e.request(t, "GET", "/api/devices", owner, nil)
	if response.Code != 200 {
		t.Fatal("device credential must remain valid")
	}
}

func TestBrowserLoginRejectsUntrustedOrigin(t *testing.T) {
	e := newEnv(t)
	for _, path := range []string{"/api/sessions", "/api/users"} {
		request := httptest.NewRequest("POST", path, bytes.NewBufferString(`{"username":"attacker","password":"a-long-password"}`))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Origin", "https://untrusted.example")
		response := httptest.NewRecorder()
		e.handler.ServeHTTP(response, request)
		if response.Code != 403 || len(response.Result().Cookies()) != 0 {
			t.Fatalf("untrusted browser origin accepted: %d", response.Code)
		}
	}
}
