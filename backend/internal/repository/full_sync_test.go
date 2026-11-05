package repository

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"math"
	"reflect"
	"testing"
)

func withSyncStores(test *testing.T, run func(*testing.T, domain.Store, string)) {
	test.Helper()
	for _, name := range []string{"memory", "postgres"} {
		test.Run(name, func(test *testing.T) {
			storage := NewMemoryStore()
			if name == "postgres" {
				storage = testPostgresStore(test)
			}
			test.Cleanup(func() { _ = storage.Close() })
			run(test, storage, mustAccount(test, storage))
		})
	}
}

func revisionItem(table, identifier, mutation string, base int64, fields map[string]any) domain.PushItem {
	snapshot, _ := json.Marshal(fields)
	return domain.PushItem{
		Table: table, ID: identifier, MutationID: mutation, BaseVersion: &base,
		Operation: domain.OpUpdate, UpdatedAtMs: 1000, DeviceID: "device-a", DataVersion: 2, Snapshot: snapshot,
	}
}

func pushRevision(test *testing.T, storage domain.Store, accountID string, candidate domain.PushItem, expected domain.PushStatus) domain.PushResult {
	test.Helper()
	results, err := storage.Push(accountID, []domain.PushItem{candidate})
	if err != nil {
		test.Fatal(err)
	}
	if len(results) != 1 || results[0].Status != expected {
		test.Fatalf("expected %s, got %+v", expected, results)
	}
	return results[0]
}

func liveSnapshot(test *testing.T, storage domain.Store, accountID, table, identifier string) map[string]any {
	test.Helper()
	rows, err := storage.Rows(accountID, table)
	if err != nil {
		test.Fatal(err)
	}
	for _, row := range rows {
		if row.ID == identifier {
			var snapshot map[string]any
			if err := json.Unmarshal(row.Snapshot, &snapshot); err != nil {
				test.Fatal(err)
			}
			return snapshot
		}
	}
	test.Fatalf("missing %s/%s", table, identifier)
	return nil
}

func TestFullSyncRevisionReceiptsAndConflicts(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		original := revisionItem("diary_entries", "entry", "mutation-1", 0, map[string]any{
			"title": "original", "future_field": map[string]any{"nested": true},
		})
		first := pushRevision(test, storage, accountID, original, domain.PushApplied)
		inventory := original
		inventory.MutationID = "same-local-inventory"
		if duplicate := pushRevision(test, storage, accountID, inventory, domain.PushDuplicate); duplicate.Version != first.Version {
			test.Fatal("identical inventory created a conflict or a new version")
		}
		reordered := original
		reordered.Snapshot = json.RawMessage(`{ "title": "original", "future_field": { "nested": true } }`)
		if replay := pushRevision(test, storage, accountID, reordered, domain.PushApplied); !replay.Replayed {
			test.Fatal("JSON key ordering changed idempotency")
		}
		updated := revisionItem("diary_entries", "entry", "mutation-2", first.Version, map[string]any{"title": "newer"})
		updated.UpdatedAtMs = 900
		second := pushRevision(test, storage, accountID, updated, domain.PushApplied)
		if second.Version <= first.Version {
			test.Fatal("revision did not advance")
		}
		replay := pushRevision(test, storage, accountID, original, domain.PushApplied)
		if !replay.Replayed || replay.Version != first.Version || !bytes.Equal(replay.Current.Snapshot, first.Current.Snapshot) {
			test.Fatalf("receipt changed after later edit: %+v", replay)
		}
		original.Snapshot = json.RawMessage(`{"title":"different request"}`)
		pushRevision(test, storage, accountID, original, domain.PushMutationReused)
		deletion := revisionItem("diary_entries", "entry", "delete-stale", first.Version, nil)
		deletion.Operation = domain.OpDelete
		conflict := pushRevision(test, storage, accountID, deletion, domain.PushConflict)
		if conflict.Current == nil || conflict.Current.Version != second.Version || conflict.Current.DeletedAtMs != 0 {
			test.Fatalf("conflict lost the canonical row: %+v", conflict)
		}
		deletion.MutationID = "delete-current"
		deletion.BaseVersion = &second.Version
		deleted := pushRevision(test, storage, accountID, deletion, domain.PushApplied)
		if deleted.Current.DeletedAtMs == 0 || len(deleted.Current.Snapshot) == 0 {
			test.Fatal("tombstone did not preserve the snapshot")
		}
		var fields map[string]any
		_ = json.Unmarshal(deleted.Current.Snapshot, &fields)
		if fields["future_field"] == nil || fields["title"] != "newer" {
			test.Fatalf("unknown field lost: %+v", fields)
		}
		pushRevision(test, storage, accountID, updated, domain.PushApplied)
		state, _ := storage.SyncState(accountID)
		if state.Head != deleted.Version {
			test.Fatal("replaying a receipt changed the dataset")
		}
	})
}

func TestFullSyncRegistryAcceptsNewEntities(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		if err := storage.ConfigureSyncTables([]string{"diary_tags"}); err != nil {
			test.Fatal(err)
		}
		pushRevision(test, storage, accountID, revisionItem("diary_tags", "new", "new", 0, map[string]any{"title": "new model"}), domain.PushApplied)
		pushRevision(test, storage, accountID, revisionItem("diary_entries", "closed", "closed", 0, map[string]any{"title": "not enabled"}), domain.PushUnsupportedTable)
		if err := storage.ConfigureSyncTables([]string{`bad";drop table account`}); err == nil {
			test.Fatal("unsafe entity name accepted")
		}
	})
}

func TestFullSyncBootstrapFixedWatermark(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		versions := map[string]int64{}
		for index := 0; index < 7; index++ {
			identifier := fmt.Sprintf("entry-%d", index)
			result := pushRevision(test, storage, accountID, revisionItem("diary_entries", identifier, identifier, 0, map[string]any{"title": identifier}), domain.PushApplied)
			versions[identifier] = result.Version
		}
		deletion := revisionItem("diary_entries", "entry-6", "delete", versions["entry-6"], nil)
		deletion.Operation = domain.OpDelete
		versions["entry-6"] = pushRevision(test, storage, accountID, deletion, domain.PushApplied).Version
		state, _ := storage.SyncState(accountID)
		request := domain.BootstrapRequest{Generation: state.Generation, Watermark: state.Head, Limit: 2}
		seen := map[string]int64{}
		for {
			page, err := storage.Bootstrap(accountID, request)
			if err != nil {
				test.Fatal(err)
			}
			if len(seen) == 0 {
				pushRevision(test, storage, accountID, revisionItem("diary_entries", "entry-5", "concurrent", versions["entry-5"], map[string]any{"title": "after watermark"}), domain.PushApplied)
			}
			for _, entry := range page.Entries {
				if _, duplicate := seen[entry.ID]; duplicate || entry.Version > state.Head {
					test.Fatalf("inconsistent snapshot: %+v", entry)
				}
				seen[entry.ID] = entry.Version
				if entry.ID == "entry-6" && !entry.Deleted {
					test.Fatal("bootstrap omitted tombstone state")
				}
			}
			if !page.More {
				break
			}
			if page.Cursor <= request.After {
				test.Fatal("bootstrap cursor stalled")
			}
			request.After = page.Cursor
		}
		if !reflect.DeepEqual(seen, versions) {
			test.Fatalf("snapshot changed between pages: %+v != %+v", seen, versions)
		}
		changes, _, err := storage.Pull(accountID, state.Head, 10)
		if err != nil || len(changes) != 1 || changes[0].ID != "entry-5" {
			test.Fatalf("change after watermark lost: %+v, %v", changes, err)
		}
		for _, invalid := range []domain.BootstrapRequest{
			{Generation: "wrong", Watermark: state.Head, Limit: 2},
			{Generation: state.Generation, Watermark: state.Head + 100, Limit: 2},
			{Generation: state.Generation, Watermark: state.Head, After: state.Head + 1, Limit: 2},
		} {
			if _, err := storage.Bootstrap(accountID, invalid); !errors.Is(err, domain.ErrResetRequired) {
				test.Fatalf("invalid cursor did not require reset: %v", err)
			}
		}
	})
}

func TestFullSyncDependencyRetryAndRollback(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		child := revisionItem("decision_options", "option", "child", 0, map[string]any{"title": "option"})
		child.References = []domain.RowReference{{Table: "decision_collections", ID: "collection"}}
		pushRevision(test, storage, accountID, child, domain.PushDependencyMissing)
		pushRevision(test, storage, accountID, revisionItem("decision_collections", "collection", "parent", 0, map[string]any{"title": "parent"}), domain.PushApplied)
		if result := pushRevision(test, storage, accountID, child, domain.PushApplied); result.Replayed {
			test.Fatal("retryable dependency error was cached as a final receipt")
		}
		pushRevision(test, storage, accountID, revisionItem("stored_value_accounts", "account", "account", 0, map[string]any{"name": "card"}), domain.PushApplied)
		pushRevision(test, storage, accountID, revisionItem("stored_value_adjustments", "opening", "opening", 0, map[string]any{"account_id": "account", "amount_cents": int64(math.MaxInt64)}), domain.PushApplied)
		state, _ := storage.SyncState(accountID)
		results, err := storage.Push(accountID, []domain.PushItem{
			revisionItem("diary_entries", "rollback", "rollback", 0, map[string]any{"title": "must roll back"}),
			revisionItem("stored_value_adjustments", "overflow", "overflow", 0, map[string]any{"account_id": "account", "amount_cents": 1}),
		})
		if err == nil || results != nil {
			test.Fatal("overflow must roll back the batch")
		}
		after, _ := storage.SyncState(accountID)
		if after != state {
			test.Fatal("failed transaction advanced the head")
		}
		result := pushRevision(test, storage, accountID, revisionItem("diary_entries", "rollback", "rollback", 0, map[string]any{"title": "must roll back"}), domain.PushApplied)
		if result.Replayed {
			test.Fatal("rolled-back receipt survived")
		}
	})
}

func TestFullSyncBalancesPreserveLegacyEffects(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		pushRevision(test, storage, accountID, revisionItem("stored_value_accounts", "card", "card", 0, map[string]any{"name": "card"}), domain.PushApplied)
		pushRevision(test, storage, accountID, revisionItem("stored_value_adjustments", "opening:card", "opening", 0, map[string]any{"account_id": "card", "amount_cents": 10000}), domain.PushApplied)
		for index, amount := range []int64{200, 300} {
			identifier := fmt.Sprintf("spend-%d", index)
			pushRevision(test, storage, accountID, revisionItem("bills", identifier, identifier, 0, map[string]any{
				"stored_value_account_id": "card", "transaction_type": "storedValueUse", "final_amount_cents": amount,
			}), domain.PushApplied)
		}
		if snapshot := liveSnapshot(test, storage, accountID, "stored_value_accounts", "card"); snapshot["balance_cents"] != float64(9500) {
			test.Fatalf("offline spending did not accumulate: %+v", snapshot)
		}
		legacy := revisionItem("bills", "expired", "legacy", 0, map[string]any{
			"stored_value_account_id": "card", "transaction_type": "expense", "category": "groupBuy",
			"settlement_status": "active", "group_buy_valid_until": 500, "original_amount_cents": 1000,
			"stored_value_effects": `{"card":1000}`,
		})
		first := pushRevision(test, storage, accountID, legacy, domain.PushApplied)
		edit := revisionItem("bills", "expired", "expired-edit", first.Version, map[string]any{"title": "edit expired bill"})
		second := pushRevision(test, storage, accountID, edit, domain.PushApplied)
		deletion := revisionItem("bills", "expired", "expired-delete", second.Version, nil)
		deletion.Operation = domain.OpDelete
		pushRevision(test, storage, accountID, deletion, domain.PushApplied)
		if snapshot := liveSnapshot(test, storage, accountID, "stored_value_accounts", "card"); snapshot["balance_cents"] != float64(10500) {
			test.Fatalf("editing/deleting an expired bill changed legacy balance semantics: %+v", snapshot)
		}
	})
}

func TestFullSyncCompletionMergeAndDelete(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, accountID string) {
		pushRevision(test, storage, accountID, revisionItem("reminders", "reminder", "reminder", 0, map[string]any{
			"schedule_kind": "continuousDays", "continuous_days": 2, "completed_at": nil,
		}), domain.PushApplied)
		var last domain.PushResult
		for _, dateKey := range []string{"2026-09-07", "2026-09-08"} {
			last = pushRevision(test, storage, accountID, revisionItem("reminder_completions", "reminder:"+dateKey, dateKey, 0, map[string]any{
				"reminder_id": "reminder", "date_key": dateKey, "completed_at": 1000, "kind": "checkIn",
			}), domain.PushApplied)
		}
		snapshot := liveSnapshot(test, storage, accountID, "reminders", "reminder")
		if snapshot["completed_date_keys"] != "2026-09-07,2026-09-08" || snapshot["completed_at"] != float64(1000) {
			test.Fatalf("independent check-ins lost: %+v", snapshot)
		}
		deletion := revisionItem("reminder_completions", last.ID, "remove-last", last.Version, nil)
		deletion.Operation = domain.OpDelete
		pushRevision(test, storage, accountID, deletion, domain.PushApplied)
		snapshot = liveSnapshot(test, storage, accountID, "reminders", "reminder")
		if snapshot["completed_date_keys"] != "2026-09-07" || snapshot["completed_at"] != nil {
			test.Fatalf("removed check-in retained stale completion status: %+v", snapshot)
		}
	})
}
