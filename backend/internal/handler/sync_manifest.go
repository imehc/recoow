package handler

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

func (server *Server) handleManifest(writer http.ResponseWriter, request *http.Request) {
	device, _ := server.deviceFrom(request.Context())
	if request.URL.Query().Get("watermark") == "" {
		writeError(writer, 400, "watermark required")
		return
	}
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
	if limit < 1 || limit > 500 || after > watermark {
		writeError(writer, 400, "invalid manifest range")
		return
	}
	page, err := server.st.Bootstrap(device.AccountID, domain.BootstrapRequest{
		Generation: state.Generation, Watermark: watermark, After: after, Limit: int(min(max(1, limit), 500)),
	})
	if err != nil {
		server.snapshotError(writer, err)
		return
	}
	manifest := domain.ManifestPage{Entries: []domain.ManifestEntry{}, Generation: page.Generation,
		Watermark: page.Watermark, Cursor: page.Cursor, More: page.More, DigestVersion: service.DigestVersion}
	for _, change := range page.Entries {
		entry := domain.ManifestEntry{Change: change, Metadata: map[string]json.RawMessage{}}
		if entry.DataVersion == 0 {
			entry.DataVersion = 1
		}
		if len(change.Snapshot) > 0 {
			entry.Digest, err = service.SnapshotDigest(change.Snapshot)
			if err != nil {
				entry.Digest = ""
			} // Uncomparable content is fetched as a full snapshot.
			var object map[string]json.RawMessage
			if err := json.Unmarshal(change.Snapshot, &object); err != nil {
				server.snapshotError(writer, err)
				return
			}
			for _, field := range service.DigestMetadataFields {
				if value, ok := object[field]; ok {
					entry.Metadata[field] = value
				}
			}
		}
		entry.Snapshot = nil
		manifest.Entries = append(manifest.Entries, entry)
	}
	writer.Header().Set("Cache-Control", "no-store")
	writeJSON(writer, http.StatusOK, manifest)
}

func (server *Server) handleSnapshotQuery(writer http.ResponseWriter, request *http.Request) {
	device, _ := server.deviceFrom(request.Context())
	var query domain.SnapshotQuery
	if err := decodeJSON(writer, request, &query); err != nil {
		writeError(writer, 400, "invalid snapshot query")
		return
	}
	if query.Generation == "" || len(query.Versions) == 0 || len(query.Versions) > 500 {
		writeError(writer, 400, "generation and 1..500 versions required")
		return
	}
	entries, err := server.st.SnapshotRevisions(device.AccountID, query)
	if err != nil {
		server.snapshotError(writer, err)
		return
	}
	writer.Header().Set("Cache-Control", "no-store")
	writeJSON(writer, http.StatusOK, map[string]any{"entries": entries, "generation": query.Generation, "watermark": query.Watermark})
}

func (server *Server) snapshotError(writer http.ResponseWriter, err error) {
	if errors.Is(err, domain.ErrResetRequired) {
		writeError(writer, 409, "reset_required")
	} else if errors.Is(err, domain.ErrNotFound) {
		writeError(writer, 400, "invalid or missing snapshot versions")
	} else {
		writeError(writer, 500, "snapshot query failed")
	}
}
