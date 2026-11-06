package handler

import (
	"net/http"
	"strings"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

func (s *Server) readOnlySessionRequest(request *http.Request) bool {
	path := request.URL.Path
	if request.Method == http.MethodDelete && path == "/api/sessions/current" {
		return true
	}
	if request.Method == http.MethodPost && path == "/api/sync/snapshot-queries" {
		return true
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		return false
	}
	if strings.HasPrefix(path, "/api/media/objects/") {
		return true
	}
	if request.Method != http.MethodGet {
		return false
	}
	switch path {
	case "/api/users/me", "/api/devices", "/api/statistics", "/api/change-events", "/api/sync/capabilities", "/api/sync/changes", "/api/sync/snapshots", "/api/sync/manifests":
		return true
	}
	if strings.HasPrefix(path, "/api/change-events/") {
		return true
	}
	for _, table := range s.schemas.Tables() {
		route := "/api/" + strings.ReplaceAll(table, "_", "-")
		if path == route || strings.HasPrefix(path, route+"/") {
			return true
		}
	}
	return false
}

func (s *Server) pushContext(writer http.ResponseWriter, request *http.Request, generation string, directEdit bool) (domain.PushContext, bool) {
	device, _ := s.deviceFrom(request.Context())
	environment := domain.ClientEnvironment{Client: "unknown", OS: "unknown"}
	for _, header := range []struct {
		name   string
		target *string
	}{
		{"X-Recoow-Client", &environment.Client}, {"X-Recoow-App-Version", &environment.AppVersion},
		{"X-Recoow-OS", &environment.OS}, {"X-Recoow-OS-Version", &environment.OSVersion},
	} {
		values := request.Header.Values(header.name)
		if len(values) > 1 {
			writeError(writer, 400, "duplicate client environment header")
			return domain.PushContext{}, false
		}
		if len(values) == 1 && values[0] != "" {
			*header.target = strings.TrimSpace(values[0])
		}
	}
	if err := service.ValidateEnvironment(environment); err != nil {
		writeError(writer, 400, err.Error())
		return domain.PushContext{}, false
	}
	submission := &domain.SubmissionSource{ClientEnvironment: environment, DeviceID: device.ID, DeviceName: device.Name}
	if session, ok := request.Context().Value(sessionKey).(domain.Session); ok {
		submission.SessionID = session.ID
		submission.Client = "web"
	}
	return domain.PushContext{Generation: generation, Submission: submission, DirectEdit: directEdit}, true
}
