// 包 api 暴露 recoow 云同步后端的 HTTP/JSON API。
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/imehc/recoow/backend/internal/auth"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/store"
)

// Server 装配鉴权、字段清单与存储，并为各 handler 提供依赖。
type Server struct {
	cfg     config.Config
	st      store.Store
	auth    *auth.Manager
	schemas *schema.Registry
	log     *slog.Logger
}

func New(cfg config.Config, st store.Store, schemas *schema.Registry, log *slog.Logger) (*Server, error) {
	if log == nil {
		log = slog.Default()
	}
	if schemas == nil {
		return nil, errors.New("缺少 schema 注册表")
	}
	if err := st.ConfigureSyncTables(schemas.Tables()); err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, st: st, auth: auth.NewManager(st, cfg.PairingTTL), schemas: schemas, log: log}, nil
}

type ctxKey int

const deviceKey ctxKey = 0

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/account/init", s.handleInitAccount)
	mux.HandleFunc("POST /api/auth/pair", s.handlePair)

	mux.Handle("GET /api/devices", s.authRequired(http.HandlerFunc(s.handleListDevices)))
	mux.Handle("POST /api/devices/pairing-code", s.authRequired(s.ownerOnly(http.HandlerFunc(s.handleCreatePairingCode))))
	mux.Handle("DELETE /api/devices/{id}", s.authRequired(http.HandlerFunc(s.handleDeleteDevice)))

	mux.Handle("POST /api/sync/push", s.authRequired(http.HandlerFunc(s.handlePush)))
	mux.Handle("GET /api/sync/pull", s.authRequired(http.HandlerFunc(s.handlePull)))
	mux.Handle("GET /api/sync/capabilities", s.authRequired(http.HandlerFunc(s.handleCapabilities)))
	mux.Handle("GET /api/sync/bootstrap", s.authRequired(http.HandlerFunc(s.handleBootstrap)))
	mux.Handle("GET /api/rows/{table}", s.authRequired(http.HandlerFunc(s.handleRows)))
	mux.Handle("GET /api/schema", s.authRequired(http.HandlerFunc(s.handleSchema)))
	mux.Handle("GET /api/export", s.authRequired(http.HandlerFunc(s.handleExport)))
	mux.Handle("GET /api/export/sql", s.authRequired(http.HandlerFunc(s.handleExportSQL)))

	mux.Handle("HEAD /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaHead)))
	mux.Handle("PUT /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaPut)))
	mux.Handle("GET /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaGet)))
	mux.Handle("DELETE /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaDelete)))

	return requestLogger(s.log, s.cors(mux))
}

// ---- 中间件 ----

func (s *Server) deviceFrom(ctx context.Context) (store.Device, bool) {
	d, ok := ctx.Value(deviceKey).(store.Device)
	return d, ok
}

func (s *Server) authRequired(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			writeError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		d, err := s.auth.Authorize(token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "invalid or revoked token")
			return
		}
		_ = s.st.TouchDeviceLastSeen(d.ID)
		ctx := context.WithValue(r.Context(), deviceKey, d)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) ownerOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		d, ok := s.deviceFrom(r.Context())
		if !ok || !d.IsOwner {
			writeError(w, http.StatusForbidden, "owner required")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "Bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

// cors 为后台(如 web/)跨源调用 /api 开启可配置的 CORS：
// - 仅当 RECOOW_CORS_ORIGINS 配置了来源(或 * )才响应，来源未命中则不加头、不拦截。
// - 对 OPTIONS 预检直接以 204 结束；其余请求在设置响应头后放行。
func (s *Server) cors(next http.Handler) http.Handler {
	allowAll := false
	allowed := make(map[string]struct{}, len(s.cfg.CORSOrigins))
	for _, o := range s.cfg.CORSOrigins {
		if o == "*" {
			allowAll = true
			continue
		}
		allowed[o] = struct{}{}
	}
	if len(allowed) == 0 && !allowAll {
		return next
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin == "" {
			// 非浏览器(如 curl/移动端)无需 CORS 头。
			next.ServeHTTP(w, r)
			return
		}
		if !allowAll {
			if _, ok := allowed[origin]; !ok {
				next.ServeHTTP(w, r)
				return
			}
		}

		if allowAll {
			w.Header().Set("Access-Control-Allow-Origin", "*")
		} else {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Add("Vary", "Origin")
		}
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		w.Header().Set("Access-Control-Expose-Headers", "Content-Length, ETag")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func requestLogger(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Info("http", "method", r.Method, "path", r.URL.Path, "dur", time.Since(start).Round(time.Millisecond))
	})
}

// ---- 响应工具 ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		if errors.Is(err, io.EOF) {
			return errors.New("empty body")
		}
		return err
	}
	return nil
}

func queryInt(r *http.Request, key string, def int64) int64 {
	v := r.URL.Query().Get(key)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return def
	}
	return n
}
