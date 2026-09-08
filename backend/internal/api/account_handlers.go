package api

import (
	"net/http"

	"github.com/imehc/recoow/backend/internal/auth"
	"github.com/imehc/recoow/backend/internal/store"
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

func (s *Server) handleInitAccount(w http.ResponseWriter, r *http.Request) {
	var req initRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if s.cfg.BootstrapToken != "" && req.BootstrapToken != s.cfg.BootstrapToken {
		writeError(w, http.StatusForbidden, "invalid bootstrap token")
		return
	}
	recoveryKey, token, deviceID, err := s.auth.InitAccount()
	if err != nil {
		if err == store.ErrAccountExists {
			writeError(w, http.StatusConflict, "account already initialized")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, initResponse{
		RecoveryKey: recoveryKey,
		DeviceToken: token,
		DeviceID:    deviceID,
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
	var req pairRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	accountID, token, deviceID, err := s.auth.Pair(req.Code, req.DeviceName)
	if err != nil {
		if err == auth.ErrBadCode || err == store.ErrDeviceExists {
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
	ID         string `json:"id"`
	Name       string `json:"name"`
	IsOwner    bool   `json:"isOwner"`
	LastSeenAt string `json:"lastSeenAt"`
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
