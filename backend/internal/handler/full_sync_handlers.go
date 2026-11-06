package handler

import (
	"net/http"
	"strconv"

	"github.com/imehc/recoow/backend/internal/domain"
)

func (server *Server) handleCapabilities(writer http.ResponseWriter, request *http.Request) {
	device, _ := server.deviceFrom(request.Context())
	state, err := server.st.SyncState(device.AccountID)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{
		"protocolVersion": 2, "accountId": device.AccountID, "dataset": state,
		"contracts": server.schemas.Contracts(), "schemaVersion": server.schemas.Version(),
		"features": []string{"revision", "receipts", "bootstrap", "media", "unknown_fields", "projections", "three_way_merge", "conflict_resolution", "clear_dataset", "manifest_bootstrap_v1"},
		"limits":   map[string]int64{"pushItems": maxPushItems, "pullEntries": maxPullEntries, "mediaBytes": maxMediaBytes},
	})
}

func (server *Server) handleClearDataset(writer http.ResponseWriter, request *http.Request) {
	device, _ := server.deviceFrom(request.Context())
	generation := request.URL.Query().Get("generation")
	if generation == "" {
		writeError(writer, http.StatusBadRequest, "generation required")
		return
	}
	state, err := server.st.ClearDataset(device.AccountID, generation)
	if err != nil {
		if err == domain.ErrResetRequired {
			writeError(writer, http.StatusConflict, "reset_required")
		} else {
			writeError(writer, http.StatusInternalServerError, err.Error())
		}
		return
	}
	writeJSON(writer, http.StatusOK, state)
}

func (server *Server) requireDataset(writer http.ResponseWriter, accountID, generation string) (domain.DatasetState, bool) {
	state, err := server.st.SyncState(accountID)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, err.Error())
		return state, false
	}
	if state.Generation != generation {
		writeError(writer, http.StatusConflict, "reset_required")
		return state, false
	}
	return state, true
}

func syncQueryInteger(writer http.ResponseWriter, request *http.Request, name string, fallback int64) (int64, bool) {
	raw := request.URL.Query().Get(name)
	if raw == "" {
		return fallback, true
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < 0 {
		writeError(writer, http.StatusBadRequest, "invalid "+name)
		return 0, false
	}
	return value, true
}

func (server *Server) handleBootstrap(writer http.ResponseWriter, request *http.Request) {
	device, _ := server.deviceFrom(request.Context())
	state, ok := server.requireDataset(writer, device.AccountID, request.URL.Query().Get("generation"))
	if !ok {
		return
	}
	watermark, ok := syncQueryInteger(writer, request, "watermark", state.Head)
	if !ok {
		return
	}
	after, ok := syncQueryInteger(writer, request, "after", 0)
	if !ok {
		return
	}
	limit, ok := syncQueryInteger(writer, request, "limit", 500)
	if !ok {
		return
	}
	page, err := server.st.Bootstrap(device.AccountID, domain.BootstrapRequest{
		Generation: state.Generation, Watermark: watermark, After: after, Limit: int(min(max(1, limit), maxPullEntries)),
	})
	if err != nil {
		if err == domain.ErrResetRequired {
			writeError(writer, http.StatusConflict, "reset_required")
		} else {
			writeError(writer, http.StatusInternalServerError, err.Error())
		}
		return
	}
	writeJSON(writer, http.StatusOK, page)
}
