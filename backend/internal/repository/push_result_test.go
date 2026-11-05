package repository

import (
	"encoding/json"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"testing"
)

func TestPushResultLifecycle(test *testing.T) {
	factories := []struct {
		name string
		open func(*testing.T) domain.Store
	}{
		{name: "memory", open: func(*testing.T) domain.Store { return NewMemoryStore() }},
		{name: "postgres", open: testPostgresStore},
	}
	for _, factory := range factories {
		test.Run(factory.name, func(test *testing.T) {
			storage := factory.open(test)
			accountID := mustAccount(test, storage)
			push := func(candidate domain.PushItem, status domain.PushStatus, version int64) domain.PushResult {
				test.Helper()
				results, err := storage.Push(accountID, []domain.PushItem{candidate})
				if err != nil {
					test.Fatal(err)
				}
				if len(results) != 1 {
					test.Fatalf("expected one receipt, got %d", len(results))
				}
				result := results[0]
				if result.Status != status || result.Version != version || result.Applied != (status == domain.PushApplied) {
					test.Fatalf(
						"expected %s v%d, got %+v",
						status,
						version,
						result,
					)
				}
				if result.Table != candidate.Table || result.ID != candidate.ID || result.Operation != candidate.Operation {
					test.Fatalf("receipt does not match mutation: %+v", result)
				}
				return result
			}

			original := domain.PushItem{
				Table: "bills", ID: "bill-1", Operation: domain.OpInsert,
				UpdatedAtMs: 100, DeviceID: "device-a",
				Snapshot: json.RawMessage(`{"title":"original","updated_at":100}`),
			}
			push(original, domain.PushApplied, 1)

			replay := original
			replay.Snapshot = json.RawMessage(`{ "updated_at": 100, "title": "original" }`)
			duplicate := push(replay, domain.PushDuplicate, 1)
			if duplicate.Current == nil || !service.EqualSnapshots(duplicate.Current.Snapshot, original.Snapshot) {
				test.Fatalf("duplicate missing canonical row: %+v", duplicate)
			}

			sameClockEdit := original
			sameClockEdit.Operation = domain.OpUpdate
			sameClockEdit.Snapshot = json.RawMessage(`{"title":"different","updated_at":100}`)
			conflict := push(sameClockEdit, domain.PushConflict, 1)
			if conflict.Current == nil || !service.EqualSnapshots(conflict.Current.Snapshot, original.Snapshot) {
				test.Fatalf("conflict missing winning row: %+v", conflict)
			}

			sameClockDelete := original
			sameClockDelete.Operation = domain.OpDelete
			sameClockDelete.Snapshot = nil
			push(sameClockDelete, domain.PushConflict, 1)

			newer := original
			newer.Operation = domain.OpUpdate
			newer.UpdatedAtMs = 200
			newer.DeviceID = "device-b"
			newer.Snapshot = json.RawMessage(`{"title":"newer","updated_at":200}`)
			push(newer, domain.PushApplied, 2)
			stale := push(original, domain.PushConflict, 2)
			if stale.Current == nil || stale.Current.DeviceID != newer.DeviceID || stale.Current.UpdatedAtMs != 200 {
				test.Fatalf("stale retry must carry latest row: %+v", stale)
			}

			deletion := newer
			deletion.Operation = domain.OpDelete
			deletion.UpdatedAtMs = 300
			deletion.Snapshot = nil
			push(deletion, domain.PushApplied, 3)
			push(deletion, domain.PushDuplicate, 3)
			tombstone := push(newer, domain.PushConflict, 3)
			if tombstone.Current == nil || tombstone.Current.DeletedAtMs != 300 || len(tombstone.Current.Snapshot) != 0 {
				test.Fatalf("expected snapshot-free tombstone: %+v", tombstone)
			}
			sameClockResurrection := deletion
			sameClockResurrection.Operation = domain.OpInsert
			sameClockResurrection.Snapshot = json.RawMessage(`{"title":"resurrect","updated_at":300}`)
			push(sameClockResurrection, domain.PushConflict, 3)

			unsupported := original
			unsupported.Table = "unknown"
			push(unsupported, domain.PushUnsupportedTable, 0)
			invalid := original
			invalid.Operation = "replace"
			push(invalid, domain.PushInvalidOperation, 0)

			entries, cursor, err := storage.Pull(accountID, 0, 100)
			if err != nil {
				test.Fatal(err)
			}
			if len(entries) != 3 || cursor != 3 || !entries[2].Deleted || len(entries[2].Snapshot) != 0 {
				test.Fatalf("rejected/replayed mutations changed log: %+v cursor=%d", entries, cursor)
			}
		})
	}
}

func TestEqualSnapshotsPreservesIntegerPrecision(test *testing.T) {
	testCases := []struct {
		name      string
		candidate string
		current   string
		equal     bool
	}{
		{name: "real zero notation", candidate: `{"distance":0}`, current: `{"distance":0.0}`, equal: true},
		{name: "real exponent notation", candidate: `{"speed":0.000001}`, current: `{"speed":1e-6}`, equal: true},
		{name: "nested numeric values", candidate: `{"values":[1,2.50]}`, current: `{"values":[1.0,2.5e0]}`, equal: true},
		{name: "signed zero", candidate: `{"value":-0e99999999999999999999}`, current: `{"value":0.0}`, equal: true},
		{name: "huge equivalent exponents", candidate: `{"value":10e99999999999999999999}`, current: `{"value":1e100000000000000000000}`, equal: true},
		{name: "huge distinct exponents", candidate: `{"value":1e99999999999999999999}`, current: `{"value":1e100000000000000000000}`, equal: false},
		{name: "different real values", candidate: `{"distance":1.01}`, current: `{"distance":1.02}`, equal: false},
		{
			name: "reordered object", candidate: `{"title":"bill","amount":9007199254740993}`,
			current: `{"amount":9007199254740993,"title":"bill"}`, equal: true,
		},
		{
			name: "distinct large integers", candidate: `{"amount":9007199254740993}`,
			current: `{"amount":9007199254740992}`, equal: false,
		},
		{
			name: "explicit null is not missing", candidate: `{"note":null}`,
			current: `{}`, equal: false,
		},
	}
	for _, testCase := range testCases {
		test.Run(testCase.name, func(test *testing.T) {
			if actual := service.EqualSnapshots([]byte(testCase.candidate), []byte(testCase.current)); actual != testCase.equal {
				test.Fatalf("expected equality=%t, got %t", testCase.equal, actual)
			}
		})
	}
}
