package handler

import (
	"net/http"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

type initRequest struct {
	BootstrapToken string `json:"bootstrapToken"`
}

type initResponse struct {
	RecoveryKey string `json:"recoveryKey"` // 仅此一次返回明文
	DeviceToken string `json:"deviceToken"`
	DeviceID    string `json:"deviceId"`
	AccountID   string `json:"accountId"`
}

// handleInitAccount 注册一个新账号（同一实例可注册多个，彼此数据隔离）。
// 设置了 RECOOW_BOOTSTRAP_TOKEN 时需带对应令牌，RECOOW_MAX_ACCOUNTS 可限总量。
func (s *Server) handleInitAccount(w http.ResponseWriter, r *http.Request) {
	profiles, ok := s.registrationProfiles(w, r, false)
	if !ok {
		return
	}
	var req initRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if s.cfg.BootstrapToken != "" && req.BootstrapToken != s.cfg.BootstrapToken {
		writeError(w, http.StatusForbidden, "invalid bootstrap token")
		return
	}
	if s.cfg.MaxAccounts > 0 {
		count, err := s.st.CountAccounts()
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if count >= s.cfg.MaxAccounts {
			writeError(w, http.StatusForbidden, "account quota exceeded")
			return
		}
	}
	accountID, recoveryKey, token, deviceID, err := s.auth.InitAccount(profiles...)
	if err != nil {
		if err == domain.ErrAccountQuota {
			writeError(w, http.StatusForbidden, "account quota exceeded")
			return
		}
		if err == domain.ErrRecoveryKeyTaken {
			writeError(w, http.StatusConflict, "recovery key already registered")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, initResponse{
		RecoveryKey: recoveryKey,
		DeviceToken: token,
		DeviceID:    deviceID,
		AccountID:   accountID,
	})
}

type recoverRequest struct {
	RecoveryKey string `json:"recoveryKey"`
	DeviceName  string `json:"deviceName"`
}

// handleRecover 用恢复密钥把账号重新绑到一台新设备（换机 / 重装 / 丢机）。
// 恢复密钥是账号的唯一凭据，因此这条路径不设注册闸门，但校验失败即拒绝。
func (s *Server) handleRecover(w http.ResponseWriter, r *http.Request) {
	profiles, ok := s.registrationProfiles(w, r, false)
	if !ok {
		return
	}
	var req recoverRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	accountID, token, deviceID, err := s.auth.Recover(req.RecoveryKey, req.DeviceName, profiles...)
	if err != nil {
		if err == service.ErrUnauthorized {
			writeError(w, http.StatusUnauthorized, "invalid recovery key")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, pairResponse{
		DeviceToken: token,
		DeviceID:    deviceID,
		AccountID:   accountID,
	})
}

type pairRequest struct {
	Code       string `json:"code"`
	DeviceName string `json:"deviceName"`
}

type pairResponse struct {
	DeviceToken string `json:"deviceToken"`
	DeviceID    string `json:"deviceId"`
	AccountID   string `json:"accountId"`
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	profiles, ok := s.registrationProfiles(w, r, false)
	if !ok {
		return
	}
	var req pairRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	accountID, token, deviceID, err := s.auth.Pair(req.Code, req.DeviceName, profiles...)
	if err != nil {
		if err == service.ErrBadCode || err == domain.ErrDeviceExists {
			writeError(w, http.StatusUnauthorized, "invalid or already-used pairing code")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, pairResponse{
		DeviceToken: token,
		DeviceID:    deviceID,
		AccountID:   accountID,
	})
}

type deviceView struct {
	Profile    *domain.DeviceProfile `json:"profile,omitempty"`
	ID         string                `json:"id"`
	Name       string                `json:"name"`
	IsOwner    bool                  `json:"isOwner"`
	LastSeenAt string                `json:"lastSeenAt"`
}

func (s *Server) handleListDevices(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	devices, err := s.st.ListDevices(d.AccountID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	out := make([]deviceView, 0, len(devices))
	for _, dv := range devices {
		out = append(out, deviceView{
			ID:         dv.ID,
			Profile:    dv.Profile,
			Name:       dv.Name,
			IsOwner:    dv.IsOwner,
			LastSeenAt: dv.LastSeenAt.Format("2006-01-02T15:04:05Z07:00"),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": out})
}

func (s *Server) handleCreatePairingCode(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	code, err := s.auth.IssuePairingCode(d.AccountID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"code": code})
}

func (s *Server) handleDeleteDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	me, _ := s.deviceFrom(r.Context())
	target, err := s.st.GetDevice(id)
	if err != nil {
		writeError(w, http.StatusNotFound, "device not found")
		return
	}
	// 主设备可吊销任意设备；非主设备只能吊销自己。
	if target.AccountID != me.AccountID {
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}
	if !me.IsOwner && me.ID != target.ID {
		writeError(w, http.StatusForbidden, "only the owner can revoke other devices")
		return
	}
	if err := s.st.DeleteDevice(id); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
