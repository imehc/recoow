package handler

import (
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/service"
	"net/http"
	"strings"
	"testing"
)

func TestManifestFixedWatermarkAndSelectiveSnapshots(t *testing.T) {
	registry, err := schema.NewFromManifests([]schema.Manifest{{Table: "diary_tags", ManifestVersion: 1, DataVersion: 1, MinDataVersion: 1,
		Columns: []schema.Column{{Name: "id", Type: "text", Format: "text", Required: true}, {Name: "name", Type: "text", Format: "text", Required: true}}}})
	if err != nil {
		t.Fatal(err)
	}
	env, token, state := fullSyncEnvironment(t, registry)
	base := int64(0)
	for i := 0; i < 3; i++ {
		snapshot := json.RawMessage(fmt.Sprintf(`{"id":"tag-%d","name":"name-%d","future":{"amount":9007199254740993},"updated_at":1000,"device_id":"device"}`, i, i))
		_, err := env.st.Push(env.accountID, []domain.PushItem{{Table: "diary_tags", ID: fmt.Sprintf("tag-%d", i), Operation: domain.OpUpdate,
			MutationID: fmt.Sprintf("m-%d", i), BaseVersion: &base, DataVersion: 1, UpdatedAtMs: 1000, DeviceID: "device", Snapshot: snapshot}})
		if err != nil {
			t.Fatal(err)
		}
	}
	state, _ = env.st.SyncState(env.accountID)
	path := fmt.Sprintf("/api/sync/manifests?generation=%s&watermark=%d&limit=2", state.Generation, state.Head)
	response, body := env.request(t, http.MethodGet, path, token, nil)
	if response.Code != 200 {
		t.Fatalf("manifest: %s", body)
	}
	var page domain.ManifestPage
	if err := decodeResponse(body, &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Entries) != 2 || !page.More || page.DigestVersion != 1 {
		t.Fatalf("pagination: %+v", page)
	}
	for _, entry := range page.Entries {
		if len(entry.Snapshot) != 0 || entry.Digest == "" || string(entry.Metadata["updated_at"]) != "1000" {
			t.Fatalf("not a lightweight manifest: %+v", entry)
		}
	}
	version := page.Entries[0].Version
	response, body = env.request(t, http.MethodPost, "/api/sync/snapshot-queries", token, domain.SnapshotQuery{Generation: state.Generation, Watermark: state.Head, Versions: []int64{version}})
	if response.Code != 200 {
		t.Fatalf("selective query: %s", body)
	}
	var queried struct{ Entries []domain.Change }
	_ = decodeResponse(body, &queried)
	if len(queried.Entries) != 1 {
		t.Fatalf("unrequested snapshots returned: %s", body)
	}
	digest, err := service.SnapshotDigest(queried.Entries[0].Snapshot)
	if err != nil || digest != page.Entries[0].Digest {
		t.Fatalf("snapshot does not match manifest: %v", err)
	}
	_, err = env.st.Push(env.accountID, []domain.PushItem{{Table: "diary_tags", ID: "tag-0", Operation: domain.OpUpdate, MutationID: "new", BaseVersion: &version, DataVersion: 1, UpdatedAtMs: 2000, DeviceID: "device", Snapshot: json.RawMessage(`{"id":"tag-0","name":"changed"}`)}})
	if err != nil {
		t.Fatal(err)
	}
	_, body = env.request(t, http.MethodGet, path, token, nil)
	var again domain.ManifestPage
	_ = decodeResponse(body, &again)
	if again.Entries[0].Digest != page.Entries[0].Digest {
		t.Fatal("manifest changed after a concurrent edit")
	}
	if response, _ := env.request(t, http.MethodGet, path, "", nil); response.Code != 401 {
		t.Fatal("manifest without auth accepted")
	}
}

func TestManifestTransferSavingsForRestoredDataset(t *testing.T) {
	registry, err := schema.NewFromManifests([]schema.Manifest{{Table: "diary_tags", ManifestVersion: 1, DataVersion: 1, MinDataVersion: 1,
		Columns: []schema.Column{{Name: "id", Type: "text", Format: "text", Required: true}, {Name: "name", Type: "text", Format: "text", Required: true}}}})
	if err != nil {
		t.Fatal(err)
	}
	env, token, _ := fullSyncEnvironment(t, registry)
	items := []domain.PushItem{}
	base := int64(0)
	for i := 0; i < 200; i++ {
		id := fmt.Sprintf("tag-%d", i)
		snapshot, _ := json.Marshal(map[string]any{"id": id, "name": id, "note": strings.Repeat("restored diary content ", 500), "created_at": 1, "updated_at": 1000, "device_id": "device"})
		items = append(items, domain.PushItem{Table: "diary_tags", ID: id, Operation: domain.OpUpdate, MutationID: id, BaseVersion: &base, DataVersion: 1, UpdatedAtMs: 1000, DeviceID: "device", Snapshot: snapshot})
	}
	if _, err := env.st.Push(env.accountID, items); err != nil {
		t.Fatal(err)
	}
	state, _ := env.st.SyncState(env.accountID)
	query := fmt.Sprintf("?generation=%s&watermark=%d&limit=500", state.Generation, state.Head)
	_, manifest := env.request(t, http.MethodGet, "/api/sync/manifests"+query, token, nil)
	_, snapshots := env.request(t, http.MethodGet, "/api/sync/snapshots"+query, token, nil)
	if len(manifest)*10 >= len(snapshots) {
		t.Fatalf("manifest did not avoid content transfer: %d vs %d", len(manifest), len(snapshots))
	}
	t.Logf("200 existing records: manifest %d bytes, full snapshots %d bytes, reduction %.1f%%", len(manifest), len(snapshots), 100*(1-float64(len(manifest))/float64(len(snapshots))))
}
