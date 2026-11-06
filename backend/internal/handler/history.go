package handler

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

type historyCursor struct {
	Account    string `json:"account"`
	Generation string `json:"generation"`
	Watermark  int64  `json:"watermark"`
	Before     int64  `json:"before"`
	Filters    string `json:"filters"`
}

func historyFilterHash(query domain.ChangeEventQuery) string {
	query.Generation, query.Watermark, query.Before, query.Limit = "", nil, 0, 0
	raw, _ := json.Marshal(query)
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:])
}

func (s *Server) handleChangeEvents(writer http.ResponseWriter, request *http.Request) {
	device, _ := s.deviceFrom(request.Context())
	from, to, err := rangeQuery(request)
	if err != nil {
		writeError(writer, 400, err.Error())
		return
	}
	query := domain.ChangeEventQuery{Generation: request.URL.Query().Get("generation"), Limit: 50,
		Table: request.URL.Query().Get("table"), ID: request.URL.Query().Get("id"),
		Operation: request.URL.Query().Get("operation"), Client: request.URL.Query().Get("client"),
		DeviceID: request.URL.Query().Get("device_id"), FromMs: from, ToMs: to}
	if query.Table != "" && !s.schemas.Known(query.Table) || query.ID != "" && query.Table == "" || len(query.ID) > 200 || len(query.DeviceID) > 200 {
		writeError(writer, 400, "invalid history entity filter")
		return
	}
	if query.Client != "" && !service.ValidClient(query.Client) {
		writeError(writer, 400, "invalid history client filter")
		return
	}
	switch query.Operation {
	case "", "insert", "update", "delete", "restore":
	default:
		writeError(writer, 400, "invalid history operation filter")
		return
	}
	if raw := request.URL.Query().Get("limit"); raw != "" {
		query.Limit, err = strconv.Atoi(raw)
		if err != nil || query.Limit < 1 || query.Limit > 200 {
			writeError(writer, 400, "limit must be 1..200")
			return
		}
	}
	filterHash := historyFilterHash(query)
	if raw := request.URL.Query().Get("cursor"); raw != "" {
		var cursor historyCursor
		decoded, err := base64.RawURLEncoding.DecodeString(raw)
		if err != nil || len(decoded) > 2048 || json.Unmarshal(decoded, &cursor) != nil || cursor.Account != device.AccountID || cursor.Filters != filterHash || cursor.Before < 1 || cursor.Watermark < cursor.Before || cursor.Generation == "" {
			writeError(writer, 400, "invalid history cursor")
			return
		}
		if query.Generation != "" && query.Generation != cursor.Generation {
			writeError(writer, 409, "reset_required")
			return
		}
		query.Generation, query.Watermark, query.Before = cursor.Generation, &cursor.Watermark, cursor.Before
	}
	page, err := s.st.ChangeEvents(device.AccountID, query)
	if err != nil {
		if errors.Is(err, domain.ErrResetRequired) {
			writeError(writer, 409, "reset_required")
		} else {
			writeError(writer, 500, "history query failed")
		}
		return
	}
	var nextCursor *string
	if page.More {
		raw, _ := json.Marshal(historyCursor{Account: device.AccountID, Generation: page.Generation, Watermark: page.Watermark, Before: page.Before, Filters: filterHash})
		encoded := base64.RawURLEncoding.EncodeToString(raw)
		nextCursor = &encoded
	}
	writer.Header().Set("Cache-Control", "no-store")
	writeJSON(writer, 200, struct {
		Events     []domain.ChangeEvent `json:"events"`
		Generation string               `json:"generation"`
		Watermark  int64                `json:"watermark"`
		More       bool                 `json:"more"`
		NextCursor *string              `json:"next_cursor"`
	}{page.Events, page.Generation, page.Watermark, page.More, nextCursor})
}

func (s *Server) handleChangeEvent(writer http.ResponseWriter, request *http.Request) {
	device, _ := s.deviceFrom(request.Context())
	detail, err := s.st.ChangeEvent(device.AccountID, request.PathValue("event_id"))
	if err != nil {
		if errors.Is(err, domain.ErrNotFound) {
			writeError(writer, 404, "history event not found")
		} else {
			writeError(writer, 500, "history detail failed")
		}
		return
	}
	detail.Changes, err = service.HistoryChanges(detail)
	if err != nil {
		writeError(writer, 500, "history difference failed")
		return
	}
	writer.Header().Set("Cache-Control", "no-store")
	writeJSON(writer, 200, detail)
}
