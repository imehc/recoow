package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/store"
)

func fullSyncEnvironment(test *testing.T, registry *schema.Registry) (*testEnv, string, store.DatasetState) {
	test.Helper()
	storage, err := store.NewSQLiteStore(test.TempDir())
	if err != nil {
		test.Fatal(err)
	}
	test.Cleanup(func() { _ = storage.Close() })
	server, err := New(config.Config{BootstrapToken: "bootstrap-secret"}, storage, registry, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		test.Fatal(err)
	}
	environment := &testEnv{handler: server.Handler(), st: storage}
	token, _ := environment.initAccount(test)
	account, _ := storage.GetAccount(environment.accountID)
	dataset, err := storage.SyncState(account.ID)
	if err != nil {
		test.Fatal(err)
	}
	return environment, token, dataset
}

func uploadSyncMedia(test *testing.T, environment *testEnv, token, key string, content []byte) *httptest.ResponseRecorder {
	test.Helper()
	request := httptest.NewRequest(http.MethodPut, "/api/media/objects/"+key, bytes.NewReader(content))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "image/jpeg")
	response := httptest.NewRecorder()
	environment.handler.ServeHTTP(response, request)
	return response
}

func contractSnapshot(manifest schema.Manifest, identifier string) map[string]any {
	snapshot := map[string]any{}
	for _, column := range manifest.Columns {
		if column.Binary || column.Derived {
			continue
		}
		if column.Nullable {
			snapshot[column.Name] = nil
			continue
		}
		switch column.Type {
		case "bool":
			snapshot[column.Name] = true
		case "int", "real":
			snapshot[column.Name] = 1
		default:
			snapshot[column.Name] = "fixture"
			if column.Format == "json" {
				snapshot[column.Name] = "[]"
			}
			if len(column.EnumValues) > 0 {
				snapshot[column.Name] = column.EnumValues[0]
			}
		}
	}
	snapshot["id"] = identifier
	snapshot["created_at"] = 1000
	snapshot["updated_at"] = 1000
	snapshot["device_id"] = "device-fixture"
	return snapshot
}

func TestFullSyncGeneratedContractsAndAllEntities(test *testing.T) {
	registry, err := schema.Load("../../../schema")
	if err != nil {
		test.Fatal(err)
	}
	environment, token, dataset := fullSyncEnvironment(test, registry)
	response, data := environment.request(test, http.MethodGet, "/api/sync/capabilities", token, nil)
	if response.Code != http.StatusOK || !bytes.Contains(data, []byte(`"protocolVersion":2`)) {
		test.Fatalf("capabilities unavailable: %s", data)
	}
	content := []byte("complete-media-bytes")
	digest := sha256.Sum256(content)
	checksum := hex.EncodeToString(digest[:])
	key := "sync/" + checksum
	if response := uploadSyncMedia(test, environment, token, key, content); response.Code != http.StatusOK && response.Code != http.StatusCreated {
		test.Fatalf("upload: %d %s", response.Code, response.Body.String())
	}
	identifiers := map[string]string{}
	for _, table := range registry.Tables() {
		identifiers[table] = table + "-id"
	}
	identifiers["food_day_records"] = "food-day:1000"
	identifiers["reminder_completions"] = identifiers["reminders"] + ":2026-09-08"
	written := map[string]store.Row{}
	snapshots := map[string]map[string]any{}
	for len(written) < len(registry.Tables()) {
		before := len(written)
		for _, table := range registry.Tables() {
			if _, exists := written[table]; exists {
				continue
			}
			manifest, _ := registry.Get(table)
			ready := true
			for _, dependency := range manifest.Dependencies {
				if _, exists := written[dependency.Table]; !exists {
					ready = false
				}
			}
			if !ready {
				continue
			}
			snapshot := contractSnapshot(manifest, identifiers[table])
			for _, dependency := range manifest.Dependencies {
				snapshot[dependency.Field] = identifiers[dependency.Table]
			}
			switch table {
			case "media_assets":
				snapshot["checksum"] = checksum
				snapshot["byte_count"] = len(content)
				snapshot["mime_type"] = "image/jpeg"
				snapshot["_media"] = map[string]any{"object": map[string]any{"key": key, "checksum": checksum, "size": len(content), "contentType": "image/jpeg"}}
			case "reminder_completions":
				snapshot["date_key"] = "2026-09-08"
				snapshot["kind"] = "checkIn"
			case "food_day_records":
				snapshot["day_start_at"] = 1000
			}
			base := int64(0)
			raw, _ := json.Marshal(snapshot)
			request := pushRequest{ProtocolVersion: 2, Generation: dataset.Generation, Items: []store.PushItem{{
				Table: table, ID: identifiers[table], MutationID: "create-" + table, BaseVersion: &base,
				Operation: store.OpInsert, UpdatedAtMs: 1000, DeviceID: "device-fixture", DataVersion: manifest.DataVersion, Snapshot: raw,
			}}}
			response, data := environment.request(test, http.MethodPost, "/api/sync/push", token, request)
			if response.Code != http.StatusOK {
				test.Fatalf("%s: %d %s", table, response.Code, data)
			}
			var result struct{ Results []store.PushResult }
			if err := json.Unmarshal(data, &result); err != nil || len(result.Results) != 1 || result.Results[0].Status != store.PushApplied {
				test.Fatalf("%s not applied: %s (%v)", table, data, err)
			}
			written[table] = *result.Results[0].Current
			snapshots[table] = snapshot
		}
		if len(written) == before {
			test.Fatal("contract dependency graph cannot be initialized")
		}
	}
	account, _ := environment.st.GetAccount(environment.accountID)
	state, _ := environment.st.SyncState(account.ID)
	seen := map[string]bool{}
	after := int64(0)
	for {
		path := fmt.Sprintf("/api/sync/bootstrap?generation=%s&watermark=%d&after=%d&limit=3", state.Generation, state.Head, after)
		response, data := environment.request(test, http.MethodGet, path, token, nil)
		if response.Code != http.StatusOK {
			test.Fatalf("bootstrap failed: %s", data)
		}
		page := decode[store.BootstrapPage](test, data)
		for _, entry := range page.Entries {
			seen[entry.Table] = true
		}
		if !page.More {
			break
		}
		after = page.Cursor
	}
	if len(seen) != len(registry.Tables()) {
		test.Fatalf("incomplete bootstrap: %d/%d", len(seen), len(registry.Tables()))
	}
	snapshot := snapshots["diary_entries"]
	snapshot["future_web_field"] = map[string]any{"preserved": true}
	current := written["diary_entries"]
	for index := 0; index < 2; index++ {
		if index == 1 {
			delete(snapshot, "future_web_field")
		}
		raw, _ := json.Marshal(snapshot)
		request := pushRequest{ProtocolVersion: 2, Generation: state.Generation, Items: []store.PushItem{{
			Table: current.Table, ID: current.ID, MutationID: fmt.Sprintf("web-edit-%d", index), BaseVersion: &current.Version,
			Operation: store.OpUpdate, UpdatedAtMs: 1000, DeviceID: "device-fixture", DataVersion: 1, Snapshot: raw,
		}}}
		response, data := environment.request(test, http.MethodPost, "/api/sync/push", token, request)
		if response.Code != http.StatusOK {
			test.Fatalf("web/iOS shared write failed: %s", data)
		}
		var result struct{ Results []store.PushResult }
		_ = json.Unmarshal(data, &result)
		current = *result.Results[0].Current
		if !bytes.Contains(current.Snapshot, []byte(`"future_web_field"`)) {
			test.Fatal("older client erased an unknown field")
		}
	}
	response, _ = environment.request(test, http.MethodDelete, "/api/media/objects/"+key, token, nil)
	if response.Code != http.StatusConflict {
		test.Fatalf("referenced media deleted: %d", response.Code)
	}
	if response := uploadSyncMedia(test, environment, token, key, []byte("replacement")); response.Code != http.StatusConflict {
		test.Fatalf("immutable object overwritten: %d", response.Code)
	}
	response, data = environment.request(test, http.MethodGet, "/api/media/objects/"+key, token, nil)
	if response.Code != http.StatusOK || !bytes.Equal(data, content) {
		test.Fatalf("media bytes changed: %s", data)
	}
}

func TestFullSyncVersionDefaultsPreserveExistingFields(test *testing.T) {
	registry, err := schema.NewFromManifests([]schema.Manifest{{
		Table: "future_entities", ManifestVersion: 2, DataVersion: 2, MinDataVersion: 1,
		Columns: []schema.Column{
			{Name: "id", Type: "text", Format: "text", Required: true},
			{Name: "enabled", Type: "bool", Format: "bool", Required: true, Default: json.RawMessage("true")},
		},
	}})
	if err != nil {
		test.Fatal(err)
	}
	environment, token, dataset := fullSyncEnvironment(test, registry)
	base := int64(0)
	for index, snapshot := range []string{`{"id":"record","enabled":false}`, `{"id":"record"}`} {
		request := pushRequest{ProtocolVersion: 2, Generation: dataset.Generation, Items: []store.PushItem{{
			Table: "future_entities", ID: "record", MutationID: fmt.Sprintf("version-%d", index), BaseVersion: &base,
			Operation: store.OpUpdate, UpdatedAtMs: 1000, DeviceID: "device-a", DataVersion: 2 - index, Snapshot: json.RawMessage(snapshot),
		}}}
		response, data := environment.request(test, http.MethodPost, "/api/sync/push", token, request)
		if response.Code != http.StatusOK {
			test.Fatalf("version upgrade: %d %s", response.Code, data)
		}
		var result struct{ Results []store.PushResult }
		_ = json.Unmarshal(data, &result)
		if !bytes.Contains(result.Results[0].Current.Snapshot, []byte(`"enabled":false`)) {
			test.Fatalf("old client default overwrote newer field: %s", data)
		}
		base = result.Results[0].Version
	}
}

func TestFullSyncRejectsUnsafeRequestsWithoutWrites(test *testing.T) {
	registry, err := schema.Load("../../../schema")
	if err != nil {
		test.Fatal(err)
	}
	environment, token, dataset := fullSyncEnvironment(test, registry)
	manifest, _ := registry.Get("stored_value_accounts")
	snapshot := contractSnapshot(manifest, "account")
	base := int64(0)
	for _, mode := range []string{"legacy", "generation", "type", "media", "version"} {
		test.Run(mode, func(test *testing.T) {
			raw, _ := json.Marshal(snapshot)
			request := pushRequest{ProtocolVersion: 2, Generation: dataset.Generation, Items: []store.PushItem{{
				Table: manifest.Table, ID: "account", MutationID: "unsafe-" + mode, BaseVersion: &base,
				Operation: store.OpInsert, UpdatedAtMs: 1000, DeviceID: "device-fixture", DataVersion: 2, Snapshot: raw,
			}}}
			expected := http.StatusBadRequest
			switch mode {
			case "legacy":
				request.ProtocolVersion = 0
				expected = http.StatusUpgradeRequired
			case "generation":
				request.Generation = "stale"
				expected = http.StatusConflict
			case "type":
				request.Items[0].Snapshot = json.RawMessage(strings.ReplaceAll(string(raw), `"name":"fixture"`, `"name":42`))
			case "media":
				var fields map[string]any
				_ = json.Unmarshal(raw, &fields)
				fields["_media"] = map[string]any{"unknown_column": nil}
				request.Items[0].Snapshot, _ = json.Marshal(fields)
			case "version":
				request.Items[0].DataVersion = 1
			}
			response, data := environment.request(test, http.MethodPost, "/api/sync/push", token, request)
			if response.Code != expected {
				test.Fatalf("expected %d, got %d %s", expected, response.Code, data)
			}
		})
	}
	account, _ := environment.st.GetAccount(environment.accountID)
	state, _ := environment.st.SyncState(account.ID)
	if state != dataset {
		test.Fatal("rejected request changed the dataset")
	}
	originalLimit := maxMediaBytes
	maxMediaBytes = 4
	defer func() { maxMediaBytes = originalLimit }()
	request := httptest.NewRequest(http.MethodPut, "/api/media/objects/oversize", strings.NewReader("12345"))
	request.ContentLength = -1
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	environment.handler.ServeHTTP(response, request)
	if response.Code != http.StatusRequestEntityTooLarge {
		test.Fatalf("oversized chunked upload accepted: %d", response.Code)
	}
}
