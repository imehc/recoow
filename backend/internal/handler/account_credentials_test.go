package handler

import (
	"bytes"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"

	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/schema"
)

type failingPasswordStore struct {
	domain.Store
}

func (store failingPasswordStore) SetPasswordIfCurrent(string, string, string) error {
	return errors.New("private database connection details")
}

func TestPasswordFailureDoesNotExposeStorageDetails(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	response, body := e.request(t, "POST", "/api/users/me/credentials", owner, map[string]any{"password": "original-long-password"})
	if response.Code != http.StatusCreated {
		t.Fatalf("bind: %d %s", response.Code, body)
	}
	registry, err := schema.NewFromManifests(testManifests())
	if err != nil {
		t.Fatal(err)
	}
	server, err := New(config.Config{}, failingPasswordStore{e.st}, registry, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	e.handler = server.Handler()
	for _, test := range []struct {
		password string
		status   int
		message  string
	}{
		{"short", http.StatusBadRequest, "invalid password"},
		{"replacement-long-password", http.StatusInternalServerError, "password change failed"},
	} {
		response, body := e.request(t, "PUT", "/api/users/me/password", owner, map[string]any{"current_password": "original-long-password", "new_password": test.password})
		if response.Code != test.status || decode[map[string]string](t, body)["error"] != test.message || response.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("password failure: %d %s", response.Code, body)
		}
	}
}

func TestGeneratedCredentialsKeepExistingAccountAndRecords(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, _ := fullSyncEnvironment(t, registry)
	manifest, _ := registry.Get("diary_tags")
	snapshot := contractSnapshot(manifest, "before-credentials")
	snapshot["name"] = "Existing record"
	response, body := e.request(t, "POST", "/api/diary-tags", owner, map[string]any{
		"mutation_id": "before-credentials", "base_version": 0, "data_version": 1, "updated_at_ms": 1000, "snapshot": snapshot,
	})
	if response.Code != 201 {
		t.Fatalf("create: %d %s", response.Code, body)
	}
	before, _ := e.st.SyncState(e.accountID)
	response, _ = e.request(t, "GET", "/api/users/me", owner, nil)
	if response.Code != 404 {
		t.Fatal("unbound account must have no login identity")
	}
	response, _ = e.request(t, "POST", "/api/users/me/credentials", owner, map[string]any{"password": "short"})
	if response.Code != 400 {
		t.Fatal("short password accepted")
	}
	response, body = e.request(t, "POST", "/api/users/me/credentials", owner, map[string]any{"password": "user-selected-password"})
	identity := decode[domain.User](t, body)
	if response.Code != 201 || identity.AccountID != e.accountID || !regexp.MustCompile(`^[a-z0-9]{3,64}$`).MatchString(identity.Username) {
		t.Fatalf("automatic bind: %d %s", response.Code, body)
	}
	if bytes.Contains(body, []byte("password")) || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("credentials must not expose or cache passwords")
	}
	response, body = e.request(t, "GET", "/api/users/me", owner, nil)
	if response.Code != 200 || decode[domain.User](t, body).Username != identity.Username {
		t.Fatal("generated username must remain stable")
	}
	response, _ = e.request(t, "POST", "/api/users/me/credentials", owner, map[string]any{"password": "another-long-password"})
	if response.Code != 409 {
		t.Fatal("rebind must not overwrite the existing identity")
	}
	after, _ := e.st.SyncState(e.accountID)
	count, _ := e.st.CountAccounts()
	if before != after || count != 1 {
		t.Fatal("binding changed dataset or created an empty account")
	}
	response, _ = e.request(t, "GET", "/api/diary-tags/before-credentials", owner, nil)
	if response.Code != 200 {
		t.Fatal("binding lost existing records")
	}
	response, _ = e.request(t, "POST", "/api/sessions", "", map[string]any{"username": identity.Username, "password": "user-selected-password"})
	if response.Code != 200 {
		t.Fatal("chosen password cannot log in")
	}
}

func TestDevicePasswordLoginIssuesSyncCredentialWithoutOwnerAccess(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, _ := fullSyncEnvironment(t, registry)
	credentials := map[string]any{"username": "mobile.owner", "password": "mobile-long-password"}
	response, _ := e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	if response.Code != 201 {
		t.Fatal("bind failed")
	}
	request := httptest.NewRequest("POST", "/api/device-logins", bytes.NewBufferString(`{"username":"mobile.owner","password":"mobile-long-password","device_name":"My iPhone"}`))
	request.Header.Set("X-Recoow-Client", "ios")
	request.Header.Set("X-Recoow-OS", "ios")
	request.Header.Set("X-Recoow-Device-Id", "phone-install")
	response = httptest.NewRecorder()
	e.handler.ServeHTTP(response, request)
	login := decode[generated.DeviceLoginResponse](t, response.Body.Bytes())
	if response.Code != 201 || login.AccountId != e.accountID || len(response.Result().Cookies()) != 0 {
		t.Fatalf("device login: %d %s", response.Code, response.Body.String())
	}
	device, err := e.st.GetDevice(login.DeviceId)
	if err != nil || device.IsOwner || device.Name != "My iPhone" || device.Profile.Client != "ios" || device.Profile.LocalDeviceID != "phone-install" {
		t.Fatalf("wrong device identity: %+v %v", device, err)
	}
	manifest, _ := registry.Get("diary_tags")
	mutation := map[string]any{"mutation_id": "password-device-write", "base_version": 0, "data_version": 1, "updated_at_ms": 1000, "snapshot": contractSnapshot(manifest, "password-device-tag")}
	response, body := e.request(t, "POST", "/api/diary-tags", login.DeviceToken, mutation)
	if response.Code != 201 {
		t.Fatalf("device cannot sync/write: %d %s", response.Code, body)
	}
	for _, path := range []string{"/api/device-pairing-codes", "/api/users/me/credentials"} {
		response, _ = e.request(t, "POST", path, login.DeviceToken, credentials)
		if response.Code != 403 {
			t.Fatalf("password login gained owner access at %s", path)
		}
	}
	response, body = e.request(t, "POST", "/api/sessions", "", credentials)
	web := decode[loginResponse](t, body)
	if response.Code != 200 || web.GetAccess() != "read_only" {
		t.Fatal("Web login changed access")
	}
	response, _ = e.request(t, "POST", "/api/diary-tags", web.AccessToken, mutation)
	if response.Code != 403 {
		t.Fatal("Web session gained write access")
	}
	response, _ = e.request(t, "POST", "/api/device-logins", web.AccessToken, map[string]any{"username": "mobile.owner", "password": "wrong"})
	if response.Code != 401 {
		t.Fatal("session token must not replace password verification")
	}
}

func TestPasswordRecoveryKeepsIdentityDataAndDeviceCredentials(t *testing.T) {
	e := newEnv(t)
	owner, recovery := e.initAccount(t)
	response, body := e.request(t, "POST", "/api/users/me/credentials", owner, map[string]any{"password": "original-long-password"})
	identity := decode[domain.User](t, body)
	if response.Code != 201 {
		t.Fatal("bind failed")
	}
	credentials := map[string]any{"username": identity.Username, "password": "original-long-password"}
	response, body = e.request(t, "POST", "/api/sessions", "", credentials)
	web := decode[loginResponse](t, body)
	if response.Code != 200 {
		t.Fatal("Web login failed")
	}
	response, body = e.request(t, "POST", "/api/device-logins", "", credentials)
	device := decode[generated.DeviceLoginResponse](t, body)
	if response.Code != 201 {
		t.Fatal("device login failed")
	}
	before, _ := e.st.SyncState(e.accountID)
	for _, key := range []string{"", "invalid-key"} {
		response, _ = e.request(t, "POST", "/api/password-resets", "", map[string]any{"recovery_key": key, "new_password": "replacement-long-password"})
		if response.Code != 401 {
			t.Fatal("invalid recovery key accepted")
		}
	}
	response, _ = e.request(t, "POST", "/api/password-resets", "", map[string]any{"recovery_key": recovery, "new_password": "short"})
	if response.Code != 400 {
		t.Fatal("invalid reset password accepted")
	}
	response, _ = e.request(t, "GET", "/api/users/me", web.AccessToken, nil)
	if response.Code != 200 {
		t.Fatal("failed reset revoked sessions")
	}
	response, body = e.request(t, "POST", "/api/password-resets", "", map[string]any{"recovery_key": recovery, "new_password": "replacement-long-password"})
	reset := decode[domain.User](t, body)
	if response.Code != 200 || reset.ID != identity.ID || reset.Username != identity.Username || reset.AccountID != e.accountID || bytes.Contains(body, []byte("password")) || bytes.Contains(body, []byte(recovery)) {
		t.Fatalf("reset changed identity or leaked secrets: %d %s", response.Code, body)
	}
	response, _ = e.request(t, "GET", "/api/users/me", web.AccessToken, nil)
	if response.Code != 401 {
		t.Fatal("reset did not revoke Web sessions")
	}
	for _, token := range []string{owner, device.DeviceToken} {
		response, _ = e.request(t, "GET", "/api/devices", token, nil)
		if response.Code != 200 {
			t.Fatal("reset unexpectedly revoked a long-lived device")
		}
	}
	response, _ = e.request(t, "POST", "/api/device-logins", "", credentials)
	if response.Code != 401 {
		t.Fatal("old password still works")
	}
	credentials["password"] = "replacement-long-password"
	response, _ = e.request(t, "POST", "/api/device-logins", "", credentials)
	if response.Code != 201 {
		t.Fatal("new password cannot log in")
	}
	after, _ := e.st.SyncState(e.accountID)
	count, _ := e.st.CountAccounts()
	if before != after || count != 1 {
		t.Fatal("reset changed the dataset or created an account")
	}
}

func TestRegistrationCanGenerateUsername(t *testing.T) {
	e := newEnv(t)
	response, body := e.request(t, "POST", "/api/users", "", map[string]any{"password": "registration-password", "bootstrap_token": "bootstrap-secret"})
	registered := decode[initResponse](t, body)
	if response.Code != 201 || registered.Username == "" {
		t.Fatalf("registration: %d %s", response.Code, body)
	}
	response, body = e.request(t, "GET", "/api/users/me", registered.DeviceToken, nil)
	if response.Code != 200 || decode[domain.User](t, body).Username != registered.Username {
		t.Fatal("registered identity differs from response")
	}
}

func TestPasswordEndpointsShareAttemptLimit(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	paths := []string{"/api/sessions", "/api/device-logins", "/api/password-resets", "/api/users/me/password"}
	for index := 0; index <= 20; index++ {
		path := paths[index%len(paths)]
		method := http.MethodPost
		if path == "/api/users/me/password" {
			method = http.MethodPut
		}
		request := httptest.NewRequest(method, path, bytes.NewBufferString(`{`))
		if method == http.MethodPut {
			request.Header.Set("Authorization", "Bearer "+owner)
		}
		response := httptest.NewRecorder()
		e.handler.ServeHTTP(response, request)
		if index < 20 && response.Code != 400 || index == 20 && (response.Code != 429 || response.Header().Get("Retry-After") == "") {
			t.Fatalf("attempt %d: %d", index, response.Code)
		}
	}
}
