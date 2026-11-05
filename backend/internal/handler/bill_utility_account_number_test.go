package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

func TestUtilityAccountNumberSyncCompatibility(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	environment, token, dataset := fullSyncEnvironment(t, registry)
	manifest, _ := registry.Get("bills")
	snapshot := contractSnapshot(manifest, "utility-bill")
	snapshot["category"] = "utility"
	snapshot["transaction_type"] = "expense"
	base := int64(0)
	steps := []struct {
		name    string
		version int
		present bool
		value   any
		want    any
	}{
		{"legacy bill without number", 2, false, nil, nil},
		{"preserve leading zeros and letters", manifest.DataVersion, true, "001234567890AB", "001234567890AB"},
		{"legacy edit preserves number", 2, false, nil, "001234567890AB"},
		{"edit number", manifest.DataVersion, true, "000987654321", "000987654321"},
		{"clear optional number", manifest.DataVersion, true, nil, nil},
	}
	for index, step := range steps {
		t.Run(step.name, func(t *testing.T) {
			delete(snapshot, "utility_account_number")
			if step.present {
				snapshot["utility_account_number"] = step.value
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
				Table: "bills", ID: "utility-bill", MutationID: fmt.Sprintf("utility-%d", index), BaseVersion: &base,
				Operation: operation, UpdatedAtMs: 1000, DeviceID: "device-fixture", DataVersion: step.version, Snapshot: raw,
			}}}
			response, data := environment.request(t, http.MethodPost, "/api/sync/mutations", token, request)
			if response.Code != http.StatusOK {
				t.Fatalf("push failed: %d %s", response.Code, data)
			}
			var result struct{ Results []domain.PushResult }
			if err := json.Unmarshal(data, &result); err != nil || len(result.Results) != 1 ||
				result.Results[0].Status != domain.PushApplied || result.Results[0].Current == nil {
				t.Fatalf("push not applied: %s (%v)", data, err)
			}
			var saved map[string]any
			if err := json.Unmarshal(result.Results[0].Current.Snapshot, &saved); err != nil {
				t.Fatal(err)
			}
			if saved["utility_account_number"] != step.want {
				t.Fatalf("number = %v, want %v", saved["utility_account_number"], step.want)
			}
			base = result.Results[0].Version
		})
		if t.Failed() {
			return
		}
	}
}
