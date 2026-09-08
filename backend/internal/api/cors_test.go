package api

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/store"
)

func newCORSEnv(t *testing.T, origins []string) http.Handler {
	t.Helper()
	st, err := store.NewSQLiteStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{CORSOrigins: origins}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	reg, err := schema.NewFromManifests(testManifests())
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(cfg, st, reg, log)
	if err != nil {
		t.Fatal(err)
	}
	return s.Handler()
}

func requestWithOrigin(h http.Handler, method, path, origin string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, nil)
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestCORSDisabled(t *testing.T) {
	h := newCORSEnv(t, nil)
	rec := requestWithOrigin(h, http.MethodGet, "/api/schema", "https://admin.example.com")
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("期望未开启 CORS 时无允许来源头，得到 %q", got)
	}
}

func TestCORSAllowedOrigin(t *testing.T) {
	h := newCORSEnv(t, []string{"https://admin.example.com"})

	// 预检：命中来源应 204 并返回响应头。
	rec := requestWithOrigin(h, http.MethodOptions, "/api/rows/bills", "https://admin.example.com")
	if rec.Code != http.StatusNoContent {
		t.Fatalf("预检期望 204，得到 %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://admin.example.com" {
		t.Fatalf("允许来源头不匹配，得到 %q", got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatal("期望声明允许的 HTTP 方法")
	}

	// 未命中来源：不加 CORS 头、也不拦截（交由鉴权判定）。
	rec = requestWithOrigin(h, http.MethodGet, "/api/schema", "https://evil.example.com")
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("未命中来源不应返回 CORS 头，得到 %q", got)
	}
}

func TestCORSWildcard(t *testing.T) {
	h := newCORSEnv(t, []string{"*"})
	rec := requestWithOrigin(h, http.MethodOptions, "/api/schema", "https://any.example.com")
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("通配模式期望 *，得到 %q", got)
	}
}
