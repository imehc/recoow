package handler

import (
	"encoding/json"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

func TestResourceThreeWayMergeAndVersionedConfirmation(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, token, _ := fullSyncEnvironment(t, registry)
	write := func(mutation string, base int64, name, note, resolution string, expected *int64, status int) domain.PushResult {
		t.Helper()
		body := map[string]any{"mutationId": mutation, "baseVersion": base, "dataVersion": 1, "updatedAtMs": 1000,
			"snapshot": map[string]any{"id": "tag", "created_at": 1000, "name": name, "note": note}}
		if resolution != "" {
			body["resolution"] = resolution
		}
		if expected != nil {
			body["expectedVersion"] = *expected
		}
		path, method := "/api/diary-tags/tag", "PUT"
		if base == 0 && mutation == "create" {
			path, method = "/api/diary-tags", "POST"
		}
		response, raw := e.request(t, method, path, token, body)
		if response.Code != status {
			t.Fatalf("%s: got %d %s, want %d", mutation, response.Code, raw, status)
		}
		if status == 400 {
			return domain.PushResult{}
		}
		return decode[domain.PushResult](t, raw)
	}
	base := write("create", 0, "old", "old", "", nil, 201)
	write("cloud", base.Version, "cloud", "old", "", nil, 200)
	merged := write("device", base.Version, "old", "device", "", nil, 200)
	var snapshot map[string]any
	_ = json.Unmarshal(merged.Current.Snapshot, &snapshot)
	if snapshot["name"] != "cloud" || snapshot["note"] != "device" {
		t.Fatalf("HTTP resource lost an edit: %v", snapshot)
	}
	remote := write("cloud-again", merged.Version, "cloud-again", "device", "", nil, 200)
	write("local-conflict", merged.Version, "local-again", "new-note", "", nil, 409)
	write("invalid-confirmation", merged.Version, "local-again", "new-note", "remote", nil, 400)
	resolved := write("confirm-cloud", merged.Version, "local-again", "new-note", "remote", &remote.Version, 200)
	_ = json.Unmarshal(resolved.Current.Snapshot, &snapshot)
	if snapshot["name"] != "cloud-again" || snapshot["note"] != "new-note" {
		t.Fatalf("confirmation lost compatible edits: %v", snapshot)
	}
	write("stale-confirmation", merged.Version, "local-again", "new-note", "local", &remote.Version, 409)
}
