// 包 api 暴露 recoow 云同步后端的 HTTP/JSON API。
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/imehc/recoow/backend/internal/buildinfo"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/service"
	"github.com/imehc/recoow/backend/internal/wire"
)

// Server 装配鉴权、字段清单与存储，并为各 handler 提供依赖。
type Server struct {
	identity      *service.Identity
	syncValidator *service.SyncValidator
	loginAttempts loginLimiter
	registration  sync.Mutex
	datasetAccess sync.RWMutex
	cfg           config.Config
	st            domain.Store
	auth          *service.Manager
	schemas       *schema.Registry
	log           *slog.Logger
}

func New(cfg config.Config, st domain.Store, schemas *schema.Registry, log *slog.Logger) (*Server, error) {
	st.SetAccountLimit(cfg.MaxAccounts)
	if log == nil {
		log = slog.Default()
	}
	if schemas == nil {
		return nil, errors.New("缺少 schema 注册表")
	}
	if err := st.ConfigureSyncTables(schemas.Tables()); err != nil {
		return nil, err
	}
	identity, err := service.NewIdentity(st, cfg.SessionTTL)
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, st: st, auth: service.NewManager(st, cfg.PairingTTL), identity: identity, syncValidator: service.NewSyncValidator(st, schemas), schemas: schemas, log: log}, nil
}

type ctxKey int

const deviceKey ctxKey = 0
const sessionKey ctxKey = 1

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/version", func(w http.ResponseWriter, r *http.Request) {
		info := buildinfo.Current()
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusOK, generated.BackendVersion{Version: info.Version, Commit: info.Commit})
	})
	s.resourceRoutes(mux)
	mux.HandleFunc("POST /api/users", s.handleRegister)
	mux.HandleFunc("POST /api/sessions", s.handleLogin)
	mux.HandleFunc("POST /api/device-logins", s.handleDeviceLogin)
	mux.HandleFunc("POST /api/password-resets", s.handlePasswordReset)
	mux.Handle("DELETE /api/sessions/current", s.authRequired(http.HandlerFunc(s.handleLogout)))
	mux.Handle("GET /api/users/me", s.authRequired(http.HandlerFunc(s.handleIdentity)))
	mux.Handle("POST /api/users/me/credentials", s.authRequired(s.ownerOnly(http.HandlerFunc(s.handleBindCredentials))))
	mux.Handle("PUT /api/users/me/password", s.authRequired(http.HandlerFunc(s.handlePassword)))
	mux.HandleFunc("GET /api/openapi.yaml", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
		_, _ = w.Write(s.schemas.OpenAPI())
	})
	mux.HandleFunc("POST /api/accounts", s.handleInitAccount)
	mux.HandleFunc("POST /api/account-recoveries", s.handleRecover)
	mux.HandleFunc("POST /api/device-pairings", s.handlePair)

	mux.Handle("PUT /api/devices/current/profile", s.authRequired(http.HandlerFunc(s.handleDeviceProfile)))
	mux.Handle("GET /api/devices", s.authRequired(http.HandlerFunc(s.handleListDevices)))
	mux.Handle("POST /api/device-pairing-codes", s.authRequired(s.ownerOnly(http.HandlerFunc(s.handleCreatePairingCode))))
	mux.Handle("DELETE /api/devices/{id}", s.authRequired(http.HandlerFunc(s.handleDeleteDevice)))

	mux.Handle("POST /api/sync/mutations", s.authRequired(http.HandlerFunc(s.handlePush)))
	mux.Handle("GET /api/sync/changes", s.authRequired(http.HandlerFunc(s.handlePull)))
	mux.Handle("GET /api/sync/capabilities", s.authRequired(http.HandlerFunc(s.handleCapabilities)))
	mux.Handle("GET /api/sync/snapshots", s.authRequired(http.HandlerFunc(s.handleBootstrap)))
	mux.Handle("GET /api/sync/manifests", s.authRequired(http.HandlerFunc(s.handleManifest)))
	mux.Handle("GET /api/change-events", s.authRequired(http.HandlerFunc(s.handleChangeEvents)))
	mux.Handle("GET /api/change-events/{event_id}", s.authRequired(http.HandlerFunc(s.handleChangeEvent)))
	mux.Handle("POST /api/sync/snapshot-queries", s.authRequired(http.HandlerFunc(s.handleSnapshotQuery)))
	mux.Handle("DELETE /api/sync/dataset", s.authRequired(s.ownerOnly(http.HandlerFunc(s.handleClearDataset))))
	mux.Handle("GET /api/exports", s.authRequired(http.HandlerFunc(s.handleExport)))
	mux.Handle("GET /api/exports/sql", s.authRequired(http.HandlerFunc(s.handleExportSQL)))

	mux.Handle("HEAD /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaHead)))
	mux.Handle("PUT /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaPut)))
	mux.Handle("GET /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaGet)))
	mux.Handle("DELETE /api/media/objects/{key...}", s.authRequired(http.HandlerFunc(s.handleMediaDelete)))

	return requestLogger(s.log, s.cors(apiFieldCase(mux)))
}

// ---- 中间件 ----

func (s *Server) deviceFrom(ctx context.Context) (domain.Device, bool) {
	d, ok := ctx.Value(deviceKey).(domain.Device)
	return d, ok
}

func (s *Server) authRequired(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Keep generation checks, business writes and media transfers on one side
		// of a dataset reset, including in-flight uploads from other devices.
		if r.Method == http.MethodDelete && r.URL.Path == "/api/sync/dataset" {
			s.datasetAccess.Lock()
			defer s.datasetAccess.Unlock()
		} else {
			s.datasetAccess.RLock()
			defer s.datasetAccess.RUnlock()
		}
		token := requestToken(r)
		if token == "" {
			writeError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		if !s.validCookieOrigin(r) {
			writeError(w, http.StatusForbidden, "invalid origin")
			return
		}
		d, err := s.auth.Authorize(token)
		var session domain.Session
		if err != nil {
			d, session, err = s.identity.AuthorizeSession(token)
		}
		if err != nil {
			writeError(w, http.StatusUnauthorized, "invalid or revoked token")
			return
		}
		if fields, ok := r.Context().Value(requestLogKey).(*requestLogFields); ok {
			fields.accountID, fields.deviceID, fields.session = d.AccountID, d.ID, session.ID != ""
		}
		_ = s.st.TouchDeviceLastSeen(d.ID)
		ctx := context.WithValue(r.Context(), deviceKey, d)
		if session.ID != "" {
			if session.Access != domain.SessionReadWrite && !s.readOnlySessionRequest(r) {
				writeError(w, http.StatusForbidden, "read-only session")
				return
			}
			ctx = context.WithValue(ctx, sessionKey, session)
		}
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
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Add("Vary", "Origin")
		}
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Recoow-Client, X-Recoow-App-Version, X-Recoow-OS, X-Recoow-OS-Version, X-Recoow-Device-Id, X-Recoow-Field-Case")
		w.Header().Set("Access-Control-Expose-Headers", "Content-Length, ETag, X-Request-Id")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ---- 响应工具 ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	var encoded []byte
	if v != nil {
		var err error
		encoded, err = json.Marshal(v)
		legacy := false
		if writer, ok := w.(*fieldCaseWriter); ok {
			legacy = writer.legacy
		}
		if err == nil {
			encoded, err = wire.Rename(encoded, legacy)
		}
		if err != nil {
			status = http.StatusInternalServerError
			encoded = []byte(`{"error":"response encoding failed"}`)
		}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if encoded != nil {
		_, _ = w.Write(append(encoded, '\n'))
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	return wire.Decode(data, dst)
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
