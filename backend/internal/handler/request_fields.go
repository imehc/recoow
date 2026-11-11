package handler

import (
	"github.com/imehc/recoow/backend/internal/wire"
	"net/http"
)

func apiQueryParameters(next http.Handler) http.Handler {
	// 旧请求别名仍用于兼容未完成的同步请求；响应始终使用统一契约。
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		query := request.URL.Query()
		for key, values := range query {
			canonical := wire.CanonicalName(key)
			if canonical == key {
				continue
			}
			if _, exists := query[canonical]; exists {
				writeError(writer, http.StatusBadRequest, "duplicate query field "+canonical)
				return
			}
			query[canonical] = values
			delete(query, key)
		}
		copy := request.Clone(request.Context())
		copy.URL.RawQuery = query.Encode()
		next.ServeHTTP(writer, copy)
	})
}
