package handler

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"net/http"
	"strconv"
	"strings"
)

func (s *Server) resourceRoutes(mux *http.ServeMux) {
	for _, table := range s.schemas.Tables() {
		route := "/api/" + strings.ReplaceAll(table, "_", "-")
		for _, method := range []string{"GET", "POST"} {
			mux.Handle(method+" "+route, s.authRequired(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { s.handleResource(w, r, table) })))
		}
		for _, method := range []string{"GET", "PUT", "DELETE"} {
			mux.Handle(method+" "+route+"/{id}", s.authRequired(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { s.handleResource(w, r, table) })))
		}
	}
	mux.Handle("GET /api/statistics", s.authRequired(http.HandlerFunc(s.handleStatistics)))
}
func rangeQuery(r *http.Request) (int64, int64, error) {
	var from, to int64
	for key, target := range map[string]*int64{"fromMs": &from, "toMs": &to} {
		if value := r.URL.Query().Get(key); value != "" {
			number, err := strconv.ParseInt(value, 10, 64)
			if err != nil || number < 0 {
				return 0, 0, errors.New("invalid date range")
			}
			*target = number
		}
	}
	if to > 0 && to <= from {
		return 0, 0, errors.New("invalid date range")
	}
	return from, to, nil
}
func (s *Server) handleResource(w http.ResponseWriter, r *http.Request, table string) {
	device, _ := s.deviceFrom(r.Context())
	id := r.PathValue("id")
	if r.Method == http.MethodGet {
		if id != "" {
			row, err := s.st.Record(device.AccountID, table, id)
			if err != nil {
				writeError(w, 404, "record not found")
				return
			}
			writeJSON(w, 200, row)
			return
		}
		from, to, err := rangeQuery(r)
		if err != nil {
			writeError(w, 400, err.Error())
			return
		}
		after, err := base64.RawURLEncoding.DecodeString(r.URL.Query().Get("cursor"))
		if err != nil {
			writeError(w, 400, "invalid cursor")
			return
		}
		limit := int64(50)
		if value := r.URL.Query().Get("limit"); value != "" {
			limit, err = strconv.ParseInt(value, 10, 64)
			if err != nil {
				writeError(w, 400, "invalid limit")
				return
			}
		}
		if limit < 1 || limit > 200 {
			writeError(w, 400, "limit must be 1..200")
			return
		}
		dateField := ""
		manifest, _ := s.schemas.Get(table)
		for _, candidate := range []string{"occurred_at", "started_at", "selected_at", "day_start_at"} {
			for _, column := range manifest.Columns {
				if column.Name == candidate && dateField == "" {
					dateField = candidate
				}
			}
		}
		page, err := s.st.QueryRecords(device.AccountID, domain.RecordQuery{Table: table, After: string(after), Limit: int(limit), Search: r.URL.Query().Get("q"), FromMs: from, ToMs: to, DateField: dateField})
		if err != nil {
			writeError(w, 500, "query failed")
			return
		}
		writeJSON(w, 200, page)
		return
	}
	var input struct {
		MutationID      string          `json:"mutationId"`
		Resolution      string          `json:"resolution"`
		ExpectedVersion *int64          `json:"expectedVersion"`
		BaseVersion     *int64          `json:"baseVersion"`
		DataVersion     int             `json:"dataVersion"`
		UpdatedAtMs     int64           `json:"updatedAtMs"`
		Snapshot        json.RawMessage `json:"snapshot"`
	}
	if err := decodeJSON(w, r, &input); err != nil {
		writeError(w, 400, "invalid request")
		return
	}
	if input.UpdatedAtMs <= 0 || input.MutationID == "" || input.BaseVersion == nil {
		writeError(w, 400, "mutationId, baseVersion and updatedAtMs required")
		return
	}
	operation := domain.OpUpdate
	if r.Method == http.MethodPost {
		operation = domain.OpInsert
	}
	if r.Method == http.MethodDelete {
		operation = domain.OpDelete
	}
	if operation != domain.OpDelete {
		fields := map[string]json.RawMessage{}
		if err := json.Unmarshal(input.Snapshot, &fields); err != nil || fields == nil {
			writeError(w, 400, "snapshot must be an object")
			return
		}
		var bodyID string
		_ = json.Unmarshal(fields["id"], &bodyID)
		if id == "" {
			id = bodyID
		}
		if id == "" || bodyID != "" && bodyID != id {
			writeError(w, 400, "record id mismatch")
			return
		}
		fields["id"], _ = json.Marshal(id)
		fields["device_id"], _ = json.Marshal(device.ID)
		fields["updated_at"], _ = json.Marshal(input.UpdatedAtMs)
		if operation == domain.OpInsert {
			if _, exists := fields["created_at"]; !exists {
				fields["created_at"], _ = json.Marshal(input.UpdatedAtMs)
			}
		}
		input.Snapshot, _ = json.Marshal(fields)
	}
	item := domain.PushItem{Table: table, ID: id, Operation: operation, MutationID: input.MutationID, BaseVersion: input.BaseVersion, Resolution: input.Resolution, ExpectedVersion: input.ExpectedVersion, DataVersion: input.DataVersion, UpdatedAtMs: input.UpdatedAtMs, DeviceID: device.ID, Snapshot: input.Snapshot}
	if err := s.syncValidator.PrepareRevision(device.AccountID, &item); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	results, err := s.st.Push(device.AccountID, []domain.PushItem{item})
	if err != nil {
		writeError(w, 500, "mutation failed")
		return
	}
	status := 200
	if results[0].Status == domain.PushConflict || results[0].Status == domain.PushInvalidRevision || results[0].Status == domain.PushMutationReused {
		status = 409
	} else if results[0].Status == domain.PushDependencyMissing {
		status = 422
	} else if r.Method == http.MethodPost {
		status = 201
	}
	writeJSON(w, status, results[0])
}
func (s *Server) handleStatistics(w http.ResponseWriter, r *http.Request) {
	device, _ := s.deviceFrom(r.Context())
	from, to, err := rangeQuery(r)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	statistics, err := s.st.Statistics(device.AccountID, from, to)
	if err != nil {
		writeError(w, 500, "statistics failed")
		return
	}
	writeJSON(w, 200, statistics)
}
