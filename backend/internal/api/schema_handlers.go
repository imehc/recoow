package api

import (
	"net/http"
)

// handleSchema 下发全量字段清单，供前端据此动态渲染列表/搜索/表单/导出。
func (s *Server) handleSchema(w http.ResponseWriter, _ *http.Request) {
	entities := make(map[string]any, len(s.schemas.Manifests()))
	for _, table := range s.schemas.Tables() {
		m, _ := s.schemas.Get(table)
		entities[table] = m
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"schemaVersion": s.schemas.Version(),
		"entities":      entities,
	})
}
