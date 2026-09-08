package store

import (
	"encoding/json"
	"testing"
)

func TestPushResultLifecycle(test *testing.T) {
	factories := []struct {
		name string
		open func(*testing.T) Store
	}{
		{name: "memory", open: func(*testing.T) Store { return NewMemoryStore() }},
		{name: "sqlite", open: func(test *testing.T) Store {
			storage, err := NewSQLiteStore(test.TempDir())
			if err != nil {
				test.Fatal(err)
			}
			test.Cleanup(func() {
				if err := storage.Close(); err != nil {
					test.Error(err)
				}
			})
			return storage
		}},
	}
	for _, factory := range factories {
		test.Run(factory.name, func(test *testing.T) {
			storage := factory.open(test)
			accountID := mustAccount(test, storage)
			push := func(candidate PushItem, status PushStatus, version int64) PushResult {
				test.Helper()
				results, err := storage.Push(accountID, []PushItem{candidate})
				if err != nil {
					test.Fatal(err)
				}
				if len(results) != 1 {
					test.Fatalf("expected one receipt, got %d", len(results))
				}
				result := results[0]
				if result.Status != status || result.Version != version || result.Applied != (status == PushApplied) {
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

			original := PushItem{
				Table: "bills", ID: "bill-1", Operation: OpInsert,
				UpdatedAtMs: 100, DeviceID: "device-a",
				Snapshot: json.RawMessage(`{"title":"original","updated_at":100}`),
			}
			push(original, PushApplied, 1)

			replay := original
			replay.Snapshot = json.RawMessage(`{ "updated_at": 100, "title": "original" }`)
			duplicate := push(replay, PushDuplicate, 1)
			if duplicate.Current == nil || !equalSnapshots(duplicate.Current.Snapshot, original.Snapshot) {
				test.Fatalf("duplicate missing canonical row: %+v", duplicate)
			}

			sameClockEdit := original
			sameClockEdit.Operation = OpUpdate
			sameClockEdit.Snapshot = json.RawMessage(`{"title":"different","updated_at":100}`)
			conflict := push(sameClockEdit, PushConflict, 1)
			if conflict.Current == nil || !equalSnapshots(conflict.Current.Snapshot, original.Snapshot) {
				test.Fatalf("conflict missing winning row: %+v", conflict)
			}

			sameClockDelete := original
			sameClockDelete.Operation = OpDelete
			sameClockDelete.Snapshot = nil
			push(sameClockDelete, PushConflict, 1)

			newer := original
			newer.Operation = OpUpdate
			newer.UpdatedAtMs = 200
			newer.DeviceID = "device-b"
			newer.Snapshot = json.RawMessage(`{"title":"newer","updated_at":200}`)
			push(newer, PushApplied, 2)
			stale := push(original, PushConflict, 2)
			if stale.Current == nil || stale.Current.DeviceID != newer.DeviceID || stale.Current.UpdatedAtMs != 200 {
				test.Fatalf("stale retry must carry latest row: %+v", stale)
			}

			deletion := newer
			deletion.Operation = OpDelete
			deletion.UpdatedAtMs = 300
			deletion.Snapshot = nil
			push(deletion, PushApplied, 3)
			push(deletion, PushDuplicate, 3)
			tombstone := push(newer, PushConflict, 3)
			if tombstone.Current == nil || tombstone.Current.DeletedAtMs != 300 || len(tombstone.Current.Snapshot) != 0 {
				test.Fatalf("expected snapshot-free tombstone: %+v", tombstone)
			}
			sameClockResurrection := deletion
			sameClockResurrection.Operation = OpInsert
			sameClockResurrection.Snapshot = json.RawMessage(`{"title":"resurrect","updated_at":300}`)
			push(sameClockResurrection, PushConflict, 3)

			unsupported := original
			unsupported.Table = "unknown"
			push(unsupported, PushUnsupportedTable, 0)
			invalid := original
			invalid.Operation = "replace"
			push(invalid, PushInvalidOperation, 0)

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
			if actual := equalSnapshots([]byte(testCase.candidate), []byte(testCase.current)); actual != testCase.equal {
				test.Fatalf("expected equality=%t, got %t", testCase.equal, actual)
			}
		})
	}
}
