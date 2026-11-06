package handler

import (
	"bytes"
	"github.com/imehc/recoow/backend/internal/domain"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRequestLogIncludesStatusAndResultsWithoutCredentials(t *testing.T) {
	var output bytes.Buffer
	handler := requestLogger(slog.New(slog.NewJSONHandler(&output, nil)), http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		recordPushResults(r, []domain.PushResult{{Status: domain.PushApplied}, {Status: domain.PushConflict}})
		w.WriteHeader(409)
		_, _ = w.Write([]byte("private response"))
		w.WriteHeader(500)
	}))
	r := httptest.NewRequest("POST", "/api/sync/mutations?token=query-secret", strings.NewReader("private request"))
	r.Header.Set("Authorization", "Bearer token-secret")
	r.Header.Set("X-Request-Id", "caller-controlled")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	id := w.Header().Get("X-Request-Id")
	text := output.String()
	if id == "" || id == "caller-controlled" || !strings.Contains(text, id) || !strings.Contains(text, `"status":409`) || !strings.Contains(text, `"conflict":1`) {
		t.Fatal(text)
	}
	for _, secret := range []string{"query-secret", "token-secret", "private request", "private response"} {
		if strings.Contains(text, secret) {
			t.Fatal("request log leaked payload or credentials")
		}
	}
}
