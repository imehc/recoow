package handler

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

const maxPushItems = 1000
const maxPullEntries = 2000

type pushRequest struct {
	Items           []domain.PushItem `json:"items"`
	ProtocolVersion int               `json:"protocolVersion"`
	Generation      string            `json:"generation"`
}

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	var req pushRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if len(req.Items) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{"results": []domain.PushResult{}})
		return
	}
	if req.ProtocolVersion != 0 && req.ProtocolVersion != 2 {
		writeError(w, http.StatusUpgradeRequired, "unsupported protocolVersion")
		return
	}
	if req.ProtocolVersion == 2 {
		if _, ok := s.requireDataset(w, d.AccountID, req.Generation); !ok {
			return
		}
	}
	if len(req.Items) > maxPushItems {
		writeError(w, http.StatusRequestEntityTooLarge, "too many items in one push")
		return
	}
	context, valid := s.pushContext(w, r, req.Generation, false)
	if !valid {
		return
	}
	accepted := make([]domain.PushItem, 0, len(req.Items))
	acceptedIndices := make([]int, 0, len(req.Items))
	results := make([]domain.PushResult, len(req.Items))
	for index, it := range req.Items {
		if err := service.ValidateOrigin(it); err != nil {
			writeError(w, 400, err.Error())
			return
		}
		if strings.TrimSpace(it.ID) == "" {
			writeError(w, http.StatusBadRequest, "变更缺少 id")
			return
		}
		if it.UpdatedAtMs <= 0 {
			writeError(w, http.StatusBadRequest, "变更缺少有效的 updatedAtMs")
			return
		}
		if strings.TrimSpace(it.DeviceID) == "" {
			writeError(w, http.StatusBadRequest, "变更缺少 deviceId")
			return
		}
		if !s.schemas.Known(it.Table) {
			results[index] = domain.PushResult{
				Table: it.Table, ID: it.ID, Operation: it.Operation, Status: domain.PushUnsupportedTable, MutationID: it.MutationID,
			}
			continue
		}
		if err := validatePushSnapshot(it); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		manifest, _ := s.schemas.Get(it.Table)
		if manifest.DataVersion > 0 && req.ProtocolVersion != 2 {
			writeError(w, http.StatusUpgradeRequired, "upgrade required: use protocolVersion 2 with mutationId and baseVersion")
			return
		}
		if req.ProtocolVersion == 2 {
			if err := s.syncValidator.PrepareRevision(d.AccountID, &it); err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
		}
		accepted = append(accepted, it)
		acceptedIndices = append(acceptedIndices, index)
	}
	if len(accepted) > 0 {
		storeResults, err := s.st.PushWithContext(d.AccountID, accepted, context)
		if err != nil {
			if err == domain.ErrResetRequired {
				writeError(w, http.StatusConflict, "reset_required")
				return
			}
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		for index, result := range storeResults {
			results[acceptedIndices[index]] = result
		}
	}
	recordPushResults(r, results)
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func validatePushSnapshot(item domain.PushItem) error {
	if item.Operation != domain.OpInsert && item.Operation != domain.OpUpdate {
		return nil
	}
	var snapshot map[string]json.RawMessage
	if err := json.Unmarshal(item.Snapshot, &snapshot); err != nil || snapshot == nil {
		return fmt.Errorf("snapshot 必须是 JSON 对象")
	}
	for _, field := range []struct {
		name  string
		value string
	}{
		{name: "id", value: item.ID},
		{name: "device_id", value: item.DeviceID},
	} {
		if raw, exists := snapshot[field.name]; exists {
			var value string
			if err := json.Unmarshal(raw, &value); err != nil || value != field.value {
				return fmt.Errorf("snapshot.%s 与变更信封不一致", field.name)
			}
		}
	}
	if raw, exists := snapshot["updated_at"]; exists {
		var updatedAt int64
		if err := json.Unmarshal(raw, &updatedAt); err != nil || updatedAt != item.UpdatedAtMs {
			return fmt.Errorf("snapshot.updated_at 与变更信封不一致")
		}
	}
	if raw, exists := snapshot["deleted_at"]; exists && !bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return fmt.Errorf("删除必须使用 delete 操作")
	}
	return nil
}

func (s *Server) handlePull(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	cursor, valid := syncQueryInteger(w, r, "cursor", 0)
	if !valid {
		return
	}
	state, err := s.st.SyncState(d.AccountID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if generation := r.URL.Query().Get("generation"); generation != "" && generation != state.Generation {
		writeError(w, http.StatusConflict, "reset_required")
		return
	}
	if cursor < state.MinCursor || cursor > state.Head {
		writeError(w, http.StatusConflict, "reset_required")
		return
	}
	limit := queryInt(r, "limit", 500)
	if limit < 1 {
		limit = 1
	}
	if limit > maxPullEntries {
		limit = maxPullEntries
	}
	entries, next, err := s.st.Pull(d.AccountID, cursor, int(limit))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	more := next > cursor && len(entries) == int(limit)
	writeJSON(w, http.StatusOK, map[string]any{
		"entries":    entries,
		"cursor":     next,
		"more":       more,
		"generation": state.Generation,
	})
}

func (s *Server) handleRows(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	table := r.PathValue("table")
	rows, err := s.st.Rows(d.AccountID, table)
	if err != nil {
		if err == domain.ErrNotFound {
			writeError(w, http.StatusNotFound, "unknown table")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"rows": rows})
}

func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	out := map[string][]domain.Row{}
	for _, t := range s.schemas.Tables() {
		rows, err := s.st.Rows(d.AccountID, t)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if len(rows) > 0 {
			out[t] = rows
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"exportedAt": nowRFC3339(), "tables": out})
}
