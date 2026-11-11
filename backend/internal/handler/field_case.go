package handler

import (
	"github.com/imehc/recoow/backend/internal/wire"
	"net/http"
	"strings"
)

type fieldCaseWriter struct {
	http.ResponseWriter
	legacy bool
}

func (writer *fieldCaseWriter) Unwrap() http.ResponseWriter { return writer.ResponseWriter }

func apiFieldCase(next http.Handler) http.Handler {
	// 所有客户端都改用 snake_case 契约后，删除响应格式协商。
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requested := request.Header.Get("X-Recoow-Field-Case")
		if requested != "" && requested != "snake_case" {
			writeError(writer, http.StatusBadRequest, "unsupported field case")
			return
		}
		client := strings.ToLower(request.Header.Get("X-Recoow-Client"))
		legacy := requested == "" && (client == "ios" || client == "android" || client == "web")
		format := "snake_case"
		if legacy {
			format = "camelCase"
		}
		writer.Header().Set("X-Recoow-Field-Case", format)
		writer.Header().Add("Vary", "X-Recoow-Field-Case")
		writer.Header().Add("Vary", "X-Recoow-Client")
		wrapped := &fieldCaseWriter{ResponseWriter: writer, legacy: legacy}
		query := request.URL.Query()
		for key, values := range query {
			canonical := wire.CanonicalName(key)
			if canonical == key {
				continue
			}
			if _, exists := query[canonical]; exists {
				writeError(wrapped, http.StatusBadRequest, "duplicate query field "+canonical)
				return
			}
			query[canonical] = values
			delete(query, key)
		}
		copy := request.Clone(request.Context())
		copy.URL.RawQuery = query.Encode()
		next.ServeHTTP(wrapped, copy)
	})
}
