package handler

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"net/http"
	"strings"
)

func (s *Server) registrationProfiles(w http.ResponseWriter, r *http.Request, web bool) ([]domain.DeviceProfile, bool) {
	environment, ok := s.requestEnvironment(w, r)
	if !ok {
		return nil, false
	}
	values := r.Header.Values("X-Recoow-Device-Id")
	if len(values) > 1 {
		writeError(w, 400, "duplicate local device header")
		return nil, false
	}
	profile := domain.DeviceProfile{ClientEnvironment: environment, LocalDeviceID: strings.TrimSpace(r.Header.Get("X-Recoow-Device-Id"))}
	if web {
		profile.Client, profile.LocalDeviceID = "web", ""
	}
	if err := service.ValidateDeviceProfile(profile); err != nil {
		writeError(w, 400, err.Error())
		return nil, false
	}
	if !web && profile.Client == "unknown" && profile.OS == "unknown" && profile.LocalDeviceID == "" && profile.AppVersion == "" && profile.OSVersion == "" {
		return nil, true
	}
	return []domain.DeviceProfile{profile}, true
}

func (s *Server) handleDeviceProfile(w http.ResponseWriter, r *http.Request) {
	var input struct {
		domain.DeviceProfile
		DeviceName string `json:"device_name"`
	}
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid device profile")
		return
	}
	device, _ := s.deviceFrom(r.Context())
	if _, web := r.Context().Value(sessionKey).(domain.Session); web {
		input.Client, input.LocalDeviceID = "web", ""
	}
	if err := service.ValidateDeviceProfile(input.DeviceProfile); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	if err := service.ValidateDeviceName(input.DeviceName); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	if err := s.st.UpdateDeviceProfile(device.AccountID, device.ID, input.DeviceName, input.DeviceProfile); err != nil {
		writeError(w, 500, "device profile update failed")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
