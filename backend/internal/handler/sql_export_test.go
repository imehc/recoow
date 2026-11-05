package handler

import (
	"net/http"
	"strings"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
)

// TestExportSQLEndpoint 校验 SQL 导出接口：需要鉴权，且返回完整可回放的建表与数据。
func TestExportSQLEndpoint(t *testing.T) {
	e := newEnv(t)
	ownerToken, _ := e.initAccount(t)

	if resp, _ := e.request(t, http.MethodGet, "/api/exports/sql", "", nil); resp.Code != http.StatusUnauthorized {
		t.Fatalf("未鉴权访问应 401, 实际 %d", resp.Code)
	}

	resp, data := e.request(t, http.MethodPost, "/api/sync/mutations", ownerToken, map[string]any{
		"items": []domain.PushItem{item(t, "bills", "bill-1", domain.OpInsert, 1000, "web")},
	})
	if resp.Code != http.StatusOK {
		t.Fatalf("push 应 200, 实际 %d body=%s", resp.Code, data)
	}

	resp, data = e.request(t, http.MethodGet, "/api/exports/sql", ownerToken, nil)
	if resp.Code != http.StatusOK {
		t.Fatalf("导出应 200, 实际 %d body=%s", resp.Code, data)
	}
	if ct := resp.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/plain") {
		t.Fatalf("Content-Type 应为 text/plain, 实际 %q", ct)
	}
	sqlText := string(data)
	for _, want := range []string{
		"BEGIN TRANSACTION",
		"CREATE TABLE IF NOT EXISTS account",
		"INSERT INTO \"bills\"",
		"INSERT INTO change_log",
		"'bill-1'",
		"COMMIT",
	} {
		if !strings.Contains(sqlText, want) {
			t.Fatalf("导出缺少 %q", want)
		}
	}
}
