package handler

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

// testManifests 提供内置的最小清单，避免测试依赖仓库外的 schema 目录。
func testManifests() []schema.Manifest {
	col := func(name, typ string) schema.Column {
		return schema.Column{Name: name, Type: typ, Format: typ, Labels: schema.Localized{Zh: name, En: name}}
	}
	return []schema.Manifest{
		{Table: "bills", ManifestVersion: 1, Columns: []schema.Column{col("id", "text")}},
		{Table: "reminders", ManifestVersion: 1, Columns: []schema.Column{col("id", "text")}},
		{Table: "stored_value_accounts", ManifestVersion: 1, Columns: []schema.Column{col("id", "text")}},
	}
}

type testEnv struct {
	handler   http.Handler
	st        domain.Store
	accountID string
}

func newEnv(t *testing.T) *testEnv {
	t.Helper()
	return newEnvWithConfig(t, config.Config{BootstrapToken: "bootstrap-secret"})
}

func newEnvWithConfig(t *testing.T, cfg config.Config) *testEnv {
	t.Helper()
	st, err := testPostgresStore(t)
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	reg, err := schema.NewFromManifests(testManifests())
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(cfg, st, reg, log)
	if err != nil {
		t.Fatal(err)
	}
	return &testEnv{handler: s.Handler(), st: st}
}

// request 直接对 handler 发起一次 HTTP 调用，不绑定 socket（沙箱安全）。
// path 形如 "/api/devices?cursor=1"。
func (e *testEnv) request(t *testing.T, method, path, token string, body any) (*httptest.ResponseRecorder, []byte) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.handler.ServeHTTP(rec, req)
	return rec, rec.Body.Bytes()
}

func decode[T any](t *testing.T, data []byte) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatalf("decode %s: %v", string(data), err)
	}
	return v
}

func (e *testEnv) initAccount(t *testing.T) (ownerToken, recoveryKey string) {
	t.Helper()
	resp, data := e.request(t, http.MethodPost, "/api/accounts", "", map[string]string{"bootstrapToken": "bootstrap-secret"})
	if resp.Code != http.StatusCreated {
		t.Fatalf("init status=%d body=%s", resp.Code, data)
	}
	out := decode[initResponse](t, data)
	e.accountID = out.AccountID
	return out.DeviceToken, out.RecoveryKey
}

func item(t *testing.T, table, id string, op domain.Operation, ts int64, dev string) domain.PushItem {
	t.Helper()
	b, _ := json.Marshal(map[string]any{"updated_at": ts, "title": id})
	return domain.PushItem{Table: table, ID: id, Operation: op, UpdatedAtMs: ts, DeviceID: dev, Snapshot: b}
}

func TestFullSyncLifecycle(t *testing.T) {
	e := newEnv(t)

	if resp, _ := e.request(t, http.MethodGet, "/api/devices", "", nil); resp.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without token, got %d", resp.Code)
	}
	if resp, _ := e.request(t, http.MethodPost, "/api/accounts", "", map[string]string{"bootstrapToken": "wrong"}); resp.Code != http.StatusForbidden {
		t.Fatalf("expected 403 wrong bootstrap, got %d", resp.Code)
	}

	ownerToken, recoveryKey := e.initAccount(t)
	if recoveryKey == "" || ownerToken == "" {
		t.Fatal("expected recovery key + token")
	}
	// 多账号：同一实例可以再注册一个账号，两个账号互不干扰。
	resp2, secondData := e.request(t, http.MethodPost, "/api/accounts", "", map[string]string{"bootstrapToken": "bootstrap-secret"})
	if resp2.Code != http.StatusCreated {
		t.Fatalf("expected 201 second init, got %d %s", resp2.Code, secondData)
	}
	second := decode[initResponse](t, secondData)
	if second.AccountID == "" || second.AccountID == e.accountID {
		t.Fatalf("expected a distinct account, got %q vs %q", second.AccountID, e.accountID)
	}

	_, data := e.request(t, http.MethodGet, "/api/devices", ownerToken, nil)
	devices := decode[struct {
		Devices []deviceView `json:"devices"`
	}](t, data)
	if len(devices.Devices) != 1 || !devices.Devices[0].IsOwner {
		t.Fatalf("expected one owner device, got %+v", devices.Devices)
	}

	_, data = e.request(t, http.MethodPost, "/api/device-pairing-codes", ownerToken, nil)
	pair := decode[struct {
		Code string `json:"code"`
	}](t, data)

	resp, data := e.request(t, http.MethodPost, "/api/device-pairings", "", map[string]string{"code": pair.Code, "deviceName": "iPad"})
	if resp.Code != http.StatusCreated {
		t.Fatalf("pair status=%d body=%s", resp.Code, data)
	}
	paired := decode[pairResponse](t, data)
	dev2Token := paired.DeviceToken

	if resp, _ := e.request(t, http.MethodPost, "/api/device-pairings", "", map[string]string{"code": pair.Code, "deviceName": "X"}); resp.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 reused code, got %d", resp.Code)
	}

	resp, data = e.request(t, http.MethodPost, "/api/sync/mutations", dev2Token, map[string]any{
		"items": []domain.PushItem{item(t, "bills", "bill-1", domain.OpInsert, 1000, "dev2")},
	})
	if resp.Code != http.StatusOK {
		t.Fatalf("push status=%d body=%s", resp.Code, data)
	}

	resp, data = e.request(t, http.MethodGet, "/api/sync/changes?cursor=0", ownerToken, nil)
	if resp.Code != http.StatusOK {
		t.Fatalf("pull status=%d", resp.Code)
	}
	pull := decode[struct {
		Entries []domain.Change `json:"entries"`
		Cursor  int64           `json:"cursor"`
		More    bool            `json:"more"`
	}](t, data)
	if len(pull.Entries) != 1 || pull.Entries[0].Version != 1 {
		t.Fatalf("expected 1 entry v1, got %+v", pull)
	}

	if resp, _ := e.request(t, http.MethodGet, "/api/bills", ownerToken, nil); resp.Code != http.StatusOK {
		t.Fatalf("rows status=%d", resp.Code)
	}
	if resp, _ := e.request(t, http.MethodGet, "/api/exports", ownerToken, nil); resp.Code != http.StatusOK {
		t.Fatalf("export status=%d", resp.Code)
	}

	if resp, _ := e.request(t, http.MethodDelete, "/api/devices/"+paired.DeviceID, ownerToken, nil); resp.Code != http.StatusOK {
		t.Fatalf("revoke status=%d", resp.Code)
	}
	if resp, _ := e.request(t, http.MethodPost, "/api/sync/mutations", dev2Token, map[string]any{"items": []domain.PushItem{}}); resp.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 revoked token, got %d", resp.Code)
	}
}

func TestMediaUploadDownloadChecksum(t *testing.T) {
	e := newEnv(t)
	ownerToken, _ := e.initAccount(t)

	content := []byte("hello media world")
	req := httptest.NewRequest(http.MethodPut, "/api/media/objects/media/x.jpg?contentType=image%2Fjpeg", bytes.NewReader(content))
	req.Header.Set("Authorization", "Bearer "+ownerToken)
	rec := httptest.NewRecorder()
	e.handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("upload status=%d body=%s", rec.Code, rec.Body.String())
	}
	up := decode[struct {
		Checksum string `json:"checksum"`
		Size     int64  `json:"size"`
	}](t, rec.Body.Bytes())
	if up.Size != int64(len(content)) {
		t.Fatalf("size mismatch %d", up.Size)
	}

	greq := httptest.NewRequest(http.MethodGet, "/api/media/objects/media/x.jpg", nil)
	greq.Header.Set("Authorization", "Bearer "+ownerToken)
	grec := httptest.NewRecorder()
	e.handler.ServeHTTP(grec, greq)
	if grec.Code != http.StatusOK || !bytes.Equal(grec.Body.Bytes(), content) {
		t.Fatalf("download mismatch status=%d", grec.Code)
	}

	breq := httptest.NewRequest(http.MethodPut, "/api/media/objects/media/y.png?checksum="+up.Checksum, strings.NewReader("corrupted bytes"))
	breq.Header.Set("Authorization", "Bearer "+ownerToken)
	brec := httptest.NewRecorder()
	e.handler.ServeHTTP(brec, breq)
	if brec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 checksum mismatch, got %d", brec.Code)
	}
}
