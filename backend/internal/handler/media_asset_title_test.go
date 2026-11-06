package handler

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

func TestMediaAssetTitleSyncCompatibility(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	environment, token, dataset := fullSyncEnvironment(t, registry)
	manifest, _ := registry.Get("media_assets")
	snapshot := contractSnapshot(manifest, "titled-asset")
	content := []byte("unchanged original asset image bytes")
	digest := sha256.Sum256(content)
	checksum := hex.EncodeToString(digest[:])
	key := "sync/" + checksum
	if response := uploadSyncMedia(t, environment, token, key, content); response.Code != http.StatusOK && response.Code != http.StatusCreated {
		t.Fatalf("upload failed: %d %s", response.Code, response.Body.String())
	}
	snapshot["kind"] = "image"
	snapshot["checksum"] = checksum
	snapshot["byte_count"] = len(content)
	snapshot["mime_type"] = "image/jpeg"
	snapshot["sort_order"] = 7
	snapshot["_media"] = map[string]any{"object": map[string]any{"key": key, "checksum": checksum, "size": len(content), "content_type": "image/jpeg"}}
	base := int64(0)
	steps := []struct {
		name    string
		version int
		present bool
		value   any
		want    any
	}{
		{"legacy asset without title", 1, false, nil, nil},
		{"set unicode title", manifest.DataVersion, true, "家里的猫 🐈", "家里的猫 🐈"},
		{"old client edit preserves title", 1, false, nil, "家里的猫 🐈"},
		{"rename title", manifest.DataVersion, true, "旅行照片", "旅行照片"},
		{"clear title explicitly", manifest.DataVersion, true, nil, nil},
	}
	for index, step := range steps {
		t.Run(step.name, func(t *testing.T) {
			delete(snapshot, "title")
			if step.present {
				snapshot["title"] = step.value
			}
			raw, err := json.Marshal(snapshot)
			if err != nil {
				t.Fatal(err)
			}
			operation := domain.OpUpdate
			if index == 0 {
				operation = domain.OpInsert
			}
			request := pushRequest{ProtocolVersion: 2, Generation: dataset.Generation, Items: []domain.PushItem{{
				Table: "media_assets", ID: "titled-asset", MutationID: fmt.Sprintf("asset-title-%d", index), BaseVersion: &base,
				Operation: operation, UpdatedAtMs: 1000, DeviceID: "device-fixture", DataVersion: step.version, Snapshot: raw,
			}}}
			response, data := environment.request(t, http.MethodPost, "/api/sync/mutations", token, request)
			if response.Code != http.StatusOK {
				t.Fatalf("push failed: %d %s", response.Code, data)
			}
			var result struct{ Results []domain.PushResult }
			if err := decodeResponse(data, &result); err != nil || len(result.Results) != 1 ||
				result.Results[0].Status != domain.PushApplied || result.Results[0].Current == nil {
				t.Fatalf("push not applied: %s (%v)", data, err)
			}
			var saved map[string]any
			if err := json.Unmarshal(result.Results[0].Current.Snapshot, &saved); err != nil {
				t.Fatal(err)
			}
			if saved["title"] != step.want {
				t.Fatalf("title = %v, want %v", saved["title"], step.want)
			}
			if saved["checksum"] != checksum || saved["sort_order"] != float64(7) {
				t.Fatalf("title update changed image or ordering: %+v", saved)
			}
			base = result.Results[0].Version
		})
		if t.Failed() {
			return
		}
	}
	response, data := environment.request(t, http.MethodGet, "/api/media/objects/"+key, token, nil)
	if response.Code != http.StatusOK || !bytes.Equal(data, content) {
		t.Fatalf("title edits changed image bytes: %d %s", response.Code, data)
	}
}
