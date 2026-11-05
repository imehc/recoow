package repository

import (
	"encoding/json"
	"fmt"
	"sync"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"github.com/imehc/recoow/backend/internal/testdb"
)

func TestThreeWayRevisions(t *testing.T) {
	cases := []struct {
		name, table, base, remote, local string
		status                           domain.PushStatus
		want                             string
	}{
		{"independent edits", "diary_entries", `{"title":"old","note":"old"}`, `{"title":"remote","note":"old"}`, `{"title":"old","note":"local"}`, domain.PushApplied, `{"title":"remote","note":"local"}`},
		{"same value", "diary_entries", `{"title":"old"}`, `{"title":"same"}`, `{"title":"same"}`, domain.PushDuplicate, `{"title":"same"}`},
		{"overlap", "diary_entries", `{"title":"old"}`, `{"title":"remote"}`, `{"title":"local"}`, domain.PushConflict, `{"title":"remote"}`},
		{"explicit null", "diary_entries", `{"title":"old","note":"old"}`, `{"title":"remote","note":"old"}`, `{"title":"old","note":null}`, domain.PushApplied, `{"title":"remote","note":null}`},
		{"unknown fields and int64", "diary_entries", `{"title":"old","future":9007199254740993}`, `{"title":"remote","future":9007199254740993}`, `{"title":"old","note":"local"}`, domain.PushApplied, `{"title":"remote","future":9007199254740993,"note":"local"}`},
		{"bill title and amount", "bills", `{"title":"old","final_amount_cents":100}`, `{"title":"old","final_amount_cents":120}`, `{"title":"local","final_amount_cents":100}`, domain.PushApplied, `{"title":"local","final_amount_cents":120}`},
		{"bill amount conflict", "bills", `{"final_amount_cents":100}`, `{"final_amount_cents":120}`, `{"final_amount_cents":150}`, domain.PushConflict, `{"final_amount_cents":120}`},
		{"bill payment is atomic with amount", "bills", `{"final_amount_cents":100,"payment_method":"cash"}`, `{"final_amount_cents":120,"payment_method":"cash"}`, `{"final_amount_cents":100,"payment_method":"alipay"}`, domain.PushConflict, `{"final_amount_cents":120,"payment_method":"cash"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
				item := revisionItem(tc.table, "record", "base", 0, nil)
				item.Snapshot = json.RawMessage(tc.base)
				base := pushRevision(t, store, account, item, domain.PushApplied)
				item.MutationID, item.BaseVersion, item.Snapshot = "remote", &base.Version, json.RawMessage(tc.remote)
				remote := pushRevision(t, store, account, item, domain.PushApplied)
				item.MutationID, item.Snapshot, item.DeviceID, item.UpdatedAtMs = "local", json.RawMessage(tc.local), "ios", 1200
				result := pushRevision(t, store, account, item, tc.status)
				var actual, want map[string]json.RawMessage
				_ = json.Unmarshal(result.Current.Snapshot, &actual)
				_ = json.Unmarshal([]byte(tc.want), &want)
				for field, expected := range want {
					if !service.EqualSnapshots(actual[field], expected) {
						t.Fatalf("%s: got %s, want %s", field, actual[field], expected)
					}
				}
				if tc.status != domain.PushApplied && result.Version != remote.Version {
					t.Fatal("rejection or duplicate wrote a version")
				}
				if tc.status == domain.PushApplied && (string(actual["device_id"]) != `"ios"` || string(actual["updated_at"]) != "1200") {
					t.Fatal("merged metadata disagrees with envelope")
				}
				replay := pushRevision(t, store, account, item, tc.status)
				if !replay.Replayed || replay.Version != result.Version {
					t.Fatal("merge replay changed the receipt")
				}
			})
		})
	}
}

func TestMergedBillEffectsUseFinalSettlement(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		pushRevision(t, store, account, revisionItem("stored_value_accounts", "card", "card", 0, map[string]any{"name": "card"}), domain.PushApplied)
		pushRevision(t, store, account, revisionItem("stored_value_adjustments", "opening", "opening", 0, map[string]any{"account_id": "card", "amount_cents": 10000}), domain.PushApplied)
		bill := revisionItem("bills", "bill", "bill", 0, map[string]any{"title": "old", "transaction_type": "storedValueUse", "stored_value_account_id": "card", "final_amount_cents": 100, "settlement_status": "active"})
		base := pushRevision(t, store, account, bill, domain.PushApplied)
		bill.MutationID, bill.BaseVersion, bill.Snapshot = "cloud-amount", &base.Version, json.RawMessage(`{"final_amount_cents":120}`)
		pushRevision(t, store, account, bill, domain.PushApplied)
		bill.MutationID, bill.Snapshot = "device-title", json.RawMessage(`{"title":"device","final_amount_cents":100,"stored_value_effects":"{\"card\":-100}"}`)
		merged := pushRevision(t, store, account, bill, domain.PushApplied)
		if balance := liveSnapshot(t, store, account, "stored_value_accounts", "card")["balance_cents"]; balance != float64(9880) {
			t.Fatalf("stale device effects replaced merged effects: %v", balance)
		}
		bill.MutationID, bill.Snapshot = "device-amount", json.RawMessage(`{"title":"device","final_amount_cents":150,"stored_value_effects":"{\"card\":-150}"}`)
		pushRevision(t, store, account, bill, domain.PushConflict)
		bill.MutationID, bill.Resolution, bill.ExpectedVersion = "choose-device", "local", &merged.Version
		chosen := pushRevision(t, store, account, bill, domain.PushApplied)
		if balance := liveSnapshot(t, store, account, "stored_value_accounts", "card")["balance_cents"]; balance != float64(9850) {
			t.Fatalf("resolved bill charged twice: %v", balance)
		}
		replay := pushRevision(t, store, account, bill, domain.PushApplied)
		if !replay.Replayed || replay.Version != chosen.Version {
			t.Fatal("resolution did not replay its receipt")
		}
	})
}

func TestPostgresIndependentRevisionsAcrossInstances(t *testing.T) {
	dsn, dir, registry := testdb.Config(t)
	first, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	account := mustAccount(t, first)
	base := pushRevision(t, first, account, revisionItem("diary_entries", "entry", "base", 0, map[string]any{"title": "old", "note": "old"}), domain.PushApplied)
	var group sync.WaitGroup
	results := make(chan domain.PushResult, 2)
	errors := make(chan error, 2)
	for index, store := range []domain.Store{first, second} {
		group.Add(1)
		go func(index int, store domain.Store) {
			defer group.Done()
			field := "title"
			if index == 1 {
				field = "note"
			}
			item := revisionItem("diary_entries", "entry", fmt.Sprintf("instance-%d", index), base.Version, map[string]any{field: "changed"})
			receipts, err := store.Push(account, []domain.PushItem{item})
			if err != nil {
				errors <- err
				return
			}
			results <- receipts[0]
		}(index, store)
	}
	group.Wait()
	close(results)
	close(errors)
	for err := range errors {
		t.Fatal(err)
	}
	for result := range results {
		if result.Status != domain.PushApplied {
			t.Fatalf("cross-instance edit failed: %+v", result)
		}
	}
	actual := liveSnapshot(t, first, account, "diary_entries", "entry")
	if actual["title"] != "changed" || actual["note"] != "changed" {
		t.Fatalf("database lock lost an edit: %v", actual)
	}
}

func TestConflictResolutionPreservesCompatibleEdits(t *testing.T) {
	for _, choice := range []string{"local", "remote"} {
		t.Run(choice, func(t *testing.T) {
			withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
				item := revisionItem("diary_entries", "record", "base", 0, map[string]any{"title": "old", "note": "old", "mood": "old"})
				base := pushRevision(t, store, account, item, domain.PushApplied)
				item.MutationID, item.BaseVersion, item.Snapshot = "remote", &base.Version, json.RawMessage(`{"title":"cloud","mood":"cloud"}`)
				remote := pushRevision(t, store, account, item, domain.PushApplied)
				item.MutationID, item.Snapshot = "local", json.RawMessage(`{"title":"device","note":"device","mood":"old"}`)
				pushRevision(t, store, account, item, domain.PushConflict)
				item.MutationID, item.Resolution, item.ExpectedVersion = "resolve", choice, &remote.Version
				resolved := pushRevision(t, store, account, item, domain.PushApplied)
				want := "device"
				if choice == "remote" {
					want = "cloud"
				}
				var result map[string]any
				_ = json.Unmarshal(resolved.Current.Snapshot, &result)
				if result["title"] != want || result["note"] != "device" || result["mood"] != "cloud" {
					t.Fatalf("lost compatible changes: %v", result)
				}
				item.MutationID = "stale-confirmation"
				pushRevision(t, store, account, item, domain.PushConflict)
			})
		})
	}
}

func TestThreeWayNeedsExactLiveAncestor(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		first := pushRevision(t, store, account, revisionItem("diary_entries", "one", "one", 0, map[string]any{"title": "one"}), domain.PushApplied)
		second := pushRevision(t, store, account, revisionItem("diary_entries", "two", "two", 0, map[string]any{"title": "two"}), domain.PushApplied)
		pushRevision(t, store, account, revisionItem("diary_entries", "two", "wrong-row", first.Version, map[string]any{"note": "local"}), domain.PushConflict)
		pushRevision(t, store, account, revisionItem("diary_entries", "two", "no-ancestor", 0, map[string]any{"note": "local"}), domain.PushConflict)
		deletion := revisionItem("diary_entries", "two", "delete", second.Version, nil)
		deletion.Operation = domain.OpDelete
		pushRevision(t, store, account, deletion, domain.PushApplied)
		pushRevision(t, store, account, revisionItem("diary_entries", "two", "edit-after-delete", second.Version, map[string]any{"title": "edited"}), domain.PushConflict)
	})
}

func TestConcurrentIndependentRevisions(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		base := pushRevision(t, store, account, revisionItem("diary_entries", "record", "base", 0, map[string]any{"title": "old", "note": "old"}), domain.PushApplied)
		var group sync.WaitGroup
		results := make(chan domain.PushResult, 2)
		errors := make(chan error, 2)
		for index, field := range []string{"title", "note"} {
			group.Add(1)
			go func(index int, field string) {
				defer group.Done()
				item := revisionItem("diary_entries", "record", fmt.Sprintf("device-%d", index), base.Version, map[string]any{field: "changed"})
				receipts, err := store.Push(account, []domain.PushItem{item})
				if err != nil {
					errors <- err
					return
				}
				results <- receipts[0]
			}(index, field)
		}
		group.Wait()
		close(results)
		close(errors)
		for err := range errors {
			t.Fatal(err)
		}
		for result := range results {
			if result.Status != domain.PushApplied {
				t.Fatalf("independent concurrent edit conflicted: %+v", result)
			}
		}
		actual := liveSnapshot(t, store, account, "diary_entries", "record")
		if actual["title"] != "changed" || actual["note"] != "changed" {
			t.Fatalf("lost edit: %v", actual)
		}
	})
}
