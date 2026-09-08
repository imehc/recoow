package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/imehc/recoow/backend/internal/store"
)

func TestPushReceiptsPreserveMixedRequestOrder(test *testing.T) {
	environment := newEnv(test)
	token, _ := environment.initAccount(test)
	candidates := []store.PushItem{
		item(
			test,
			"bills",
			"bill-1",
			store.OpInsert,
			1000,
			"web",
		),
		item(
			test,
			"food_entries",
			"food-1",
			store.OpInsert,
			1000,
			"web",
		),
		item(
			test,
			"bills",
			"bill-1",
			store.OpUpdate,
			1001,
			"web",
		),
		item(
			test,
			"bills",
			"invalid",
			"replace",
			1000,
			"web",
		),
		item(
			test,
			"reminders",
			"reminder-1",
			store.OpInsert,
			1000,
			"web",
		),
	}
	response, data := environment.request(
		test,
		http.MethodPost,
		"/api/sync/push",
		token,
		map[string]any{"items": candidates},
	)
	if response.Code != http.StatusOK {
		test.Fatalf("status=%d body=%s", response.Code, data)
	}
	result := decode[struct {
		Results []store.PushResult `json:"results"`
	}](test, data)
	expectedStatuses := []store.PushStatus{
		store.PushApplied, store.PushUnsupportedTable, store.PushApplied,
		store.PushInvalidOperation, store.PushApplied,
	}
	if len(result.Results) != len(candidates) {
		test.Fatalf("receipt count=%d, expected %d", len(result.Results), len(candidates))
	}
	for index, receipt := range result.Results {
		candidate := candidates[index]
		if receipt.Table != candidate.Table || receipt.ID != candidate.ID || receipt.Operation != candidate.Operation {
			test.Fatalf("receipt %d reordered: %+v", index, receipt)
		}
		if receipt.Status != expectedStatuses[index] {
			test.Fatalf("receipt %d status=%s", index, receipt.Status)
		}
	}
}

func TestPushRejectsInconsistentSnapshotsBeforeWriting(test *testing.T) {
	testCases := []struct {
		name     string
		snapshot string
		deviceID string
	}{
		{name: "missing snapshot", deviceID: "web"},
		{name: "null snapshot", snapshot: `null`, deviceID: "web"},
		{name: "array snapshot", snapshot: `[]`, deviceID: "web"},
		{name: "mismatched id", snapshot: `{"id":"other"}`, deviceID: "web"},
		{name: "mismatched device", snapshot: `{"device_id":"other"}`, deviceID: "web"},
		{name: "mismatched time", snapshot: `{"updated_at":999}`, deviceID: "web"},
		{name: "wrong time type", snapshot: `{"updated_at":"1000"}`, deviceID: "web"},
		{name: "hidden deletion", snapshot: `{"deleted_at":1000}`, deviceID: "web"},
		{name: "missing device", snapshot: `{}`},
	}
	for _, testCase := range testCases {
		test.Run(testCase.name, func(test *testing.T) {
			environment := newEnv(test)
			token, _ := environment.initAccount(test)
			invalid := store.PushItem{
				Table: "bills", ID: "invalid", Operation: store.OpInsert,
				UpdatedAtMs: 1000, DeviceID: testCase.deviceID,
				Snapshot: json.RawMessage(testCase.snapshot),
			}
			response, data := environment.request(
				test,
				http.MethodPost,
				"/api/sync/push",
				token,
				map[string]any{"items": []store.PushItem{
					item(
						test,
						"bills",
						"valid",
						store.OpInsert,
						1000,
						"web",
					),
					invalid,
				}},
			)
			if response.Code != http.StatusBadRequest {
				test.Fatalf("expected 400, got %d body=%s", response.Code, data)
			}
			_, data = environment.request(
				test,
				http.MethodGet,
				"/api/sync/pull?cursor=0",
				token,
				nil,
			)
			pull := decode[struct {
				Entries []store.Change `json:"entries"`
			}](test, data)
			if len(pull.Entries) != 0 {
				test.Fatalf("invalid batch partially written: %+v", pull.Entries)
			}
		})
	}
}

func TestPushConflictReturnsDeletedCurrentRow(test *testing.T) {
	environment := newEnv(test)
	token, _ := environment.initAccount(test)
	initial := item(
		test,
		"bills",
		"bill-1",
		store.OpInsert,
		1000,
		"web",
	)
	deletion := store.PushItem{
		Table: "bills", ID: "bill-1", Operation: store.OpDelete, UpdatedAtMs: 2000, DeviceID: "web",
	}
	response, data := environment.request(
		test,
		http.MethodPost,
		"/api/sync/push",
		token,
		map[string]any{"items": []store.PushItem{initial, deletion, initial, deletion}},
	)
	if response.Code != http.StatusOK {
		test.Fatalf("status=%d body=%s", response.Code, data)
	}
	result := decode[struct {
		Results []store.PushResult `json:"results"`
	}](test, data)
	if len(result.Results) != 4 {
		test.Fatalf("expected 4 receipts, got %+v", result.Results)
	}
	conflict := result.Results[2]
	if conflict.Status != store.PushConflict || conflict.Current == nil || conflict.Current.DeletedAtMs != 2000 {
		test.Fatalf("missing deletion in conflict receipt: %+v", conflict)
	}
	if len(conflict.Current.Snapshot) != 0 || conflict.Current.Version != 2 {
		test.Fatalf("invalid tombstone: %+v", conflict.Current)
	}
	if result.Results[3].Status != store.PushDuplicate || result.Results[3].Version != 2 {
		test.Fatalf("delete replay not acknowledged: %+v", result.Results[3])
	}
}
