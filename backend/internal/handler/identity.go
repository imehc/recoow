package handler

import (
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/service"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type credentialsRequest = generated.CredentialsRequest
type loginResponse = generated.LoginResponse

type attemptWindow struct {
	count int
	until time.Time
}
type loginLimiter struct {
	mu      sync.Mutex
	windows map[string]attemptWindow
}

func (limiter *loginLimiter) allow(key string) bool {
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	now := time.Now()
	if limiter.windows == nil {
		limiter.windows = map[string]attemptWindow{}
	}
	for name, window := range limiter.windows {
		if now.After(window.until) {
			delete(limiter.windows, name)
		}
	}
	window := limiter.windows[key]
	if window.until.IsZero() {
		window.until = now.Add(15 * time.Minute)
	}
	if window.count >= 20 {
		return false
	}
	window.count++
	limiter.windows[key] = window
	return true
}
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if !s.allowIdentityAttempt(w, r) {
		return
	}
	var input credentialsRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid request")
		return
	}
	profiles, ok := s.registrationProfiles(w, r, true)
	if !ok {
		return
	}
	login, err := s.identity.Login(input.Username, input.Password, profiles...)
	if err != nil {
		writeError(w, 401, "invalid credentials")
		return
	}
	// 浏览器会话使用 HttpOnly Cookie；生产环境默认要求 HTTPS。
	http.SetCookie(w, &http.Cookie{Name: "recoow_session", Value: login.Token, Path: "/api", HttpOnly: true, Secure: !s.cfg.AllowInsecureCookies, SameSite: http.SameSiteStrictMode, Expires: login.ExpiresAt})
	access := generated.SessionAccess(login.Access)
	writeJSON(w, 200, loginResponse{UserId: login.User.ID, Username: login.User.Username, AccountId: login.User.AccountID, DeviceId: login.DeviceID, AccessToken: login.Token, ExpiresAt: login.ExpiresAt, Access: &access, WritableTables: s.sessionWritableTables(domain.Session{Access: login.Access})})
}
func (s *Server) handleBindCredentials(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	device, _ := s.deviceFrom(r.Context())
	var input generated.BindCredentialsRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid request")
		return
	}
	user, err := s.identity.Bind(device.AccountID, input.GetUsername(), input.Password)
	if errors.Is(err, domain.ErrConflict) {
		writeError(w, 409, "username or account already bound")
		return
	}
	if err != nil {
		if errors.Is(err, service.ErrInvalidPassword) || errors.Is(err, service.ErrInvalidUsername) {
			writeError(w, http.StatusBadRequest, "invalid username or password")
		} else {
			writeError(w, http.StatusInternalServerError, "credentials binding failed")
		}
		return
	}
	writeJSON(w, 201, user)
}
func (s *Server) handleIdentity(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	device, _ := s.deviceFrom(r.Context())
	user, err := s.st.UserByAccount(device.AccountID)
	if err != nil {
		writeError(w, 404, "account has no login identity")
		return
	}
	access := generated.SessionAccess(domain.SessionReadWrite)
	tables := s.schemas.Tables()
	if session, ok := r.Context().Value(sessionKey).(domain.Session); ok {
		tables = s.sessionWritableTables(session)
		if len(tables) == 0 {
			access = generated.SessionAccess(domain.SessionReadOnly)
		}
	}
	writeJSON(w, 200, generated.IdentityResponse{UserId: user.ID, Username: user.Username, AccountId: user.AccountID, Access: &access, WritableTables: tables})
}
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if err := s.identity.Logout(requestToken(r)); err != nil {
		writeError(w, 500, "logout failed")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: "recoow_session", Path: "/api", HttpOnly: true, Secure: !s.cfg.AllowInsecureCookies, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (s *Server) handlePassword(w http.ResponseWriter, r *http.Request) {
	if !s.allowIdentityAttempt(w, r) {
		return
	}
	var input generated.PasswordRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid request")
		return
	}
	device, _ := s.deviceFrom(r.Context())
	err := s.identity.ChangePassword(device.AccountID, input.CurrentPassword, input.NewPassword)
	if errors.Is(err, service.ErrUnauthorized) {
		writeError(w, 401, "invalid credentials")
		return
	}
	if err != nil {
		if errors.Is(err, service.ErrInvalidPassword) {
			writeError(w, http.StatusBadRequest, "invalid password")
		} else {
			writeError(w, http.StatusInternalServerError, "password change failed")
		}
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" && !s.validCookieOrigin(r) {
		writeError(w, http.StatusForbidden, "invalid origin")
		return
	}
	profiles, ok := s.registrationProfiles(w, r, false)
	if !ok {
		return
	}
	var input generated.RegisterRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid request")
		return
	}
	if s.cfg.BootstrapToken != "" && input.GetBootstrapToken() != s.cfg.BootstrapToken {
		writeError(w, 403, "registration restricted")
		return
	}
	s.registration.Lock()
	defer s.registration.Unlock()
	if s.cfg.MaxAccounts > 0 {
		count, err := s.st.CountAccounts()
		if err != nil {
			writeError(w, 500, "registration unavailable")
			return
		}
		if count >= s.cfg.MaxAccounts {
			writeError(w, 403, "account quota exceeded")
			return
		}
	}
	account, user, recovery, err := s.identity.Register(input.GetUsername(), input.Password)
	if errors.Is(err, domain.ErrAccountQuota) {
		writeError(w, 403, "account quota exceeded")
		return
	}
	if errors.Is(err, domain.ErrConflict) {
		writeError(w, 409, "username already registered")
		return
	}
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	token, err := service.NewDeviceToken()
	if err != nil {
		writeError(w, 500, "registration failed")
		return
	}
	device, err := s.st.CreateDevice(account.ID, "账号主设备", service.HashToken(token), true, profiles...)
	if err != nil {
		writeError(w, 500, "registration failed")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 201, initResponse{AccountID: account.ID, DeviceID: device.ID, DeviceToken: token, RecoveryKey: recovery, Username: user.Username})
}

// 所有密码验证入口共用频率限制；Header 只登记环境，不决定凭据权限。
func (s *Server) allowIdentityAttempt(w http.ResponseWriter, r *http.Request) bool {
	w.Header().Set("Cache-Control", "no-store")
	if r.Header.Get("Origin") != "" && !s.validCookieOrigin(r) {
		writeError(w, http.StatusForbidden, "invalid origin")
		return false
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !s.loginAttempts.allow(host) {
		w.Header().Set("Retry-After", "900")
		writeError(w, http.StatusTooManyRequests, "too many login attempts")
		return false
	}
	return true
}

func (s *Server) handleDeviceLogin(w http.ResponseWriter, r *http.Request) {
	if !s.allowIdentityAttempt(w, r) {
		return
	}
	var input generated.DeviceLoginRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request")
		return
	}
	if err := service.ValidateDeviceName(input.GetDeviceName()); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	profiles, ok := s.registrationProfiles(w, r, false)
	if !ok {
		return
	}
	user, device, token, err := s.identity.LoginDevice(input.Username, input.Password, input.GetDeviceName(), profiles...)
	if errors.Is(err, service.ErrUnauthorized) {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "device login failed")
		return
	}
	writeJSON(w, http.StatusCreated, generated.DeviceLoginResponse{
		UserId: user.ID, Username: user.Username, AccountId: user.AccountID, DeviceId: device.ID, DeviceToken: token,
	})
}

func (s *Server) handlePasswordReset(w http.ResponseWriter, r *http.Request) {
	if !s.allowIdentityAttempt(w, r) {
		return
	}
	var input generated.PasswordResetRequest
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request")
		return
	}
	user, err := s.identity.ResetPassword(input.RecoveryKey, input.NewPassword)
	if errors.Is(err, service.ErrUnauthorized) {
		writeError(w, http.StatusUnauthorized, "invalid recovery key")
		return
	}
	if err != nil {
		if errors.Is(err, service.ErrInvalidPassword) {
			writeError(w, http.StatusBadRequest, "invalid password")
		} else {
			writeError(w, http.StatusInternalServerError, "password reset failed")
		}
		return
	}
	writeJSON(w, http.StatusOK, user)
}
func requestToken(r *http.Request) string {
	if token := bearerToken(r); token != "" {
		return token
	}
	cookie, err := r.Cookie("recoow_session")
	if err == nil {
		return cookie.Value
	}
	return ""
}
func (s *Server) validCookieOrigin(r *http.Request) bool {
	if bearerToken(r) != "" || r.Method == http.MethodGet || r.Method == http.MethodHead {
		return true
	}
	origin := r.Header.Get("Origin")
	if origin == "" {
		return false
	}
	scheme := "https"
	if s.cfg.AllowInsecureCookies {
		scheme = "http"
	}
	if origin == scheme+"://"+r.Host {
		return true
	}
	for _, allowed := range s.cfg.CORSOrigins {
		if allowed != "*" && strings.TrimRight(allowed, "/") == origin {
			return true
		}
	}
	return false
}
