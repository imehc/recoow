package handler

import (
	"context"
	"crypto/rand"
	"github.com/imehc/recoow/backend/internal/domain"
	"log/slog"
	"net/http"
	"time"
)

const requestLogKey ctxKey = 2

type requestLogFields struct {
	accountID, deviceID string
	session             bool
	results             map[string]int
}

type loggedResponse struct {
	http.ResponseWriter
	status, bytes int
}

func (w *loggedResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *loggedResponse) WriteHeader(status int) {
	if status < 200 {
		w.ResponseWriter.WriteHeader(status)
		return
	}
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *loggedResponse) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(data)
	w.bytes += n
	return n, err
}

func requestLogger(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, start := rand.Text(), time.Now()
		w.Header().Set("X-Request-Id", id)
		response, fields := &loggedResponse{ResponseWriter: w}, &requestLogFields{}
		next.ServeHTTP(response, r.WithContext(context.WithValue(r.Context(), requestLogKey, fields)))
		status := response.status
		if status == 0 {
			status = http.StatusOK
		}
		attributes := []any{"request_id", id, "method", r.Method, "path", r.URL.Path, "status", status, "bytes", response.bytes, "dur", time.Since(start).Round(time.Millisecond)}
		if fields.accountID != "" {
			attributes = append(attributes, "account_id", fields.accountID, "device_id", fields.deviceID, "web_session", fields.session)
		}
		if fields.results != nil {
			attributes = append(attributes, "mutation_results", fields.results)
		}
		log.Info("http", attributes...)
	})
}

func recordPushResults(r *http.Request, results []domain.PushResult) {
	if fields, ok := r.Context().Value(requestLogKey).(*requestLogFields); ok {
		fields.results = map[string]int{}
		for _, result := range results {
			fields.results[string(result.Status)]++
		}
	}
}
