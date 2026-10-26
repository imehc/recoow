package api

import (
	"bytes"
	"net/http"

	"github.com/imehc/recoow/backend/internal/store"
)

// handleExportSQL 把当前账号的全部状态导出为可移植 SQL 脚本（DDL + INSERT），
// 供人工审阅、备份，或迁移到其他 SQL 系数据库时回放。多账号实例下只导出
// 当前设备所属账号，不跨账号泄露。
func (s *Server) handleExportSQL(w http.ResponseWriter, r *http.Request) {
	dumper, ok := s.st.(store.SQLDumper)
	if !ok {
		writeError(w, http.StatusNotImplemented, "current store does not support SQL export")
		return
	}
	device, ok := s.deviceFrom(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "missing device context")
		return
	}
	var buf bytes.Buffer
	if err := dumper.ExportSQL(&buf, device.AccountID); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="recoow-backup.sql"`)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(buf.Bytes())
}
