package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"path/filepath"
	"reflect"
	"testing"
)

func withSyncStores(test *testing.T, run func(*testing.T, Store, string)) {
	test.Helper()
	for _, name := range []string{"memory", "sqlite"} {
		test.Run(name, func(test *testing.T) {
			storage := NewMemoryStore()
			if name == "sqlite" {
				var err error
				storage, err = NewSQLiteStore(test.TempDir())
				if err != nil {
					test.Fatal(err)
				}
			}
			test.Cleanup(func() { _ = storage.Close() })
			run(test, storage, mustAccount(test, storage))
		})
	}
}

func revisionItem(table, identifier, mutation string, base int64, fields map[string]any) PushItem {
	snapshot, _ := json.Marshal(fields)
	return PushItem{
		Table: table, ID: identifier, MutationID: mutation, BaseVersion: &base,
		Operation: OpUpdate, UpdatedAtMs: 1000, DeviceID: "device-a", DataVersion: 2, Snapshot: snapshot,
	}
}

func pushRevision(test *testing.T, storage Store, accountID string, candidate PushItem, expected PushStatus) PushResult {
	test.Helper()
	results, err := storage.Push(accountID, []PushItem{candidate})
	if err != nil {
		test.Fatal(err)
	}
	if len(results) != 1 || results[0].Status != expected {
		test.Fatalf("expected %s, got %+v", expected, results)
	}
	return results[0]
}

func liveSnapshot(test *testing.T, storage Store, accountID, table, identifier string) map[string]any {
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
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		original := revisionItem("diary_entries", "entry", "mutation-1", 0, map[string]any{
			"title": "original", "future_field": map[string]any{"nested": true},
		})
		first := pushRevision(test, storage, accountID, original, PushApplied)
		inventory := original
		inventory.MutationID = "same-local-inventory"
		if duplicate := pushRevision(test, storage, accountID, inventory, PushDuplicate); duplicate.Version != first.Version {
			test.Fatal("identical inventory created a conflict or a new version")
		}
		reordered := original
		reordered.Snapshot = json.RawMessage(`{ "title": "original", "future_field": { "nested": true } }`)
		if replay := pushRevision(test, storage, accountID, reordered, PushApplied); !replay.Replayed {
			test.Fatal("JSON key ordering changed idempotency")
		}
		updated := revisionItem("diary_entries", "entry", "mutation-2", first.Version, map[string]any{"title": "newer"})
		updated.UpdatedAtMs = 900
		second := pushRevision(test, storage, accountID, updated, PushApplied)
		if second.Version <= first.Version {
			test.Fatal("revision did not advance")
		}
		replay := pushRevision(test, storage, accountID, original, PushApplied)
		if !replay.Replayed || replay.Version != first.Version || !bytes.Equal(replay.Current.Snapshot, first.Current.Snapshot) {
			test.Fatalf("receipt changed after later edit: %+v", replay)
		}
		original.Snapshot = json.RawMessage(`{"title":"different request"}`)
		pushRevision(test, storage, accountID, original, PushMutationReused)
		deletion := revisionItem("diary_entries", "entry", "delete-stale", first.Version, nil)
		deletion.Operation = OpDelete
		conflict := pushRevision(test, storage, accountID, deletion, PushConflict)
		if conflict.Current == nil || conflict.Current.Version != second.Version || conflict.Current.DeletedAtMs != 0 {
			test.Fatalf("conflict lost the canonical row: %+v", conflict)
		}
		deletion.MutationID = "delete-current"
		deletion.BaseVersion = &second.Version
		deleted := pushRevision(test, storage, accountID, deletion, PushApplied)
		if deleted.Current.DeletedAtMs == 0 || len(deleted.Current.Snapshot) == 0 {
			test.Fatal("tombstone did not preserve the snapshot")
		}
		var fields map[string]any
		_ = json.Unmarshal(deleted.Current.Snapshot, &fields)
		if fields["future_field"] == nil || fields["title"] != "newer" {
			test.Fatalf("unknown field lost: %+v", fields)
		}
		pushRevision(test, storage, accountID, updated, PushApplied)
		state, _ := storage.SyncState(accountID)
		if state.Head != deleted.Version {
			test.Fatal("replaying a receipt changed the dataset")
		}
	})
}

func TestFullSyncLegacyMigrationPreservesData(test *testing.T) {
	directory := test.TempDir()
	storage, err := NewSQLiteStore(directory)
	if err != nil {
		test.Fatal(err)
	}
	accountID := mustAccount(test, storage)
	legacy := []PushItem{
		item("stored_value_accounts", "card", OpInsert, 100, "device-a", map[string]any{
			"name": "original card", "balance_cents": 7300, "future_field": "preserve",
		}),
		item("bills", "recharge", OpInsert, 100, "device-a", map[string]any{
			"stored_value_account_id": "card", "transaction_type": "expense", "original_amount_cents": 1500,
			"final_amount_cents": 1000, "category": "groupBuy", "group_buy_valid_until": 1, "settlement_status": "active",
		}),
		item("bills", "spending", OpInsert, 100, "device-a", map[string]any{
			"stored_value_account_id": "card", "transaction_type": "storedValueUse", "final_amount_cents": 400,
		}),
		item("reminders", "habit", OpInsert, 100, "device-a", map[string]any{
			"title": "original reminder", "schedule_kind": "continuousDays", "continuous_days": 7,
			"completion_records":  `[{"dateKey":"2026-09-07","completedAt":50,"kindRawValue":"checkIn","note":"keep"}]`,
			"completed_date_keys": "2026-09-07,2026-09-08",
		}),
		item("diary_entries", "deleted-parent", OpInsert, 100, "device-a", map[string]any{"title": "retained tombstone"}),
		item("diary_entries", "deleted-parent", OpDelete, 200, "device-a", nil),
	}
	if _, err := storage.Push(accountID, legacy); err != nil {
		test.Fatal(err)
	}
	if _, err := storage.(*sqliteStore).db.Exec("PRAGMA user_version = 2"); err != nil {
		test.Fatal(err)
	}
	_ = storage.Close()
	storage, err = NewSQLiteStore(directory)
	if err != nil {
		test.Fatal(err)
	}
	snapshot := liveSnapshot(test, storage, accountID, "stored_value_accounts", "card")
	if snapshot["balance_cents"] != float64(7300) || snapshot["name"] != "original card" || snapshot["future_field"] != "preserve" {
		test.Fatalf("migration changed existing account: %+v", snapshot)
	}
	adjustment := liveSnapshot(test, storage, accountID, "stored_value_adjustments", "opening:card")
	if adjustment["amount_cents"] != float64(6200) {
		test.Fatalf("opening adjustment not based on old balance: %+v", adjustment)
	}
	completion := liveSnapshot(test, storage, accountID, "reminder_completions", "habit:2026-09-07")
	if completion["note"] != "keep" || completion["completed_at"] != float64(50) {
		test.Fatalf("migration lost completion detail: %+v", completion)
	}
	_ = liveSnapshot(test, storage, accountID, "reminder_completions", "habit:2026-09-08")
	state, _ := storage.SyncState(accountID)
	page, err := storage.Bootstrap(accountID, BootstrapRequest{Generation: state.Generation, Watermark: state.Head, Limit: 100})
	if err != nil {
		test.Fatal(err)
	}
	foundTombstone := false
	for _, entry := range page.Entries {
		if entry.ID == "deleted-parent" {
			foundTombstone = entry.Deleted && len(entry.Snapshot) > 0
		}
	}
	if !foundTombstone {
		test.Fatal("legacy tombstone snapshot not recoverable")
	}
	backups, err := filepath.Glob(filepath.Join(directory, "backups", "before-sync-v3-*.sqlite"))
	if err != nil || len(backups) != 1 {
		test.Fatalf("missing pre-migration backup: %+v, %v", backups, err)
	}
	_ = storage.Close()
	storage, err = NewSQLiteStore(directory)
	if err != nil {
		test.Fatal(err)
	}
	defer storage.Close()
	after, _ := storage.SyncState(accountID)
	if after != state {
		test.Fatal("migration repeated on restart")
	}
}

func TestFullSyncRegistryAcceptsNewEntities(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		if err := storage.ConfigureSyncTables([]string{"future_entities"}); err != nil {
			test.Fatal(err)
		}
		pushRevision(test, storage, accountID, revisionItem("future_entities", "new", "new", 0, map[string]any{"title": "new model"}), PushApplied)
		pushRevision(test, storage, accountID, revisionItem("diary_entries", "closed", "closed", 0, map[string]any{"title": "not enabled"}), PushUnsupportedTable)
		if err := storage.ConfigureSyncTables([]string{`bad";drop table account`}); err == nil {
			test.Fatal("unsafe entity name accepted")
		}
	})
}

func TestFullSyncBootstrapFixedWatermark(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		versions := map[string]int64{}
		for index := 0; index < 7; index++ {
			identifier := fmt.Sprintf("entry-%d", index)
			result := pushRevision(test, storage, accountID, revisionItem("diary_entries", identifier, identifier, 0, map[string]any{"title": identifier}), PushApplied)
			versions[identifier] = result.Version
		}
		deletion := revisionItem("diary_entries", "entry-6", "delete", versions["entry-6"], nil)
		deletion.Operation = OpDelete
		versions["entry-6"] = pushRevision(test, storage, accountID, deletion, PushApplied).Version
		state, _ := storage.SyncState(accountID)
		request := BootstrapRequest{Generation: state.Generation, Watermark: state.Head, Limit: 2}
		seen := map[string]int64{}
		for {
			page, err := storage.Bootstrap(accountID, request)
			if err != nil {
				test.Fatal(err)
			}
			if len(seen) == 0 {
				pushRevision(test, storage, accountID, revisionItem("diary_entries", "entry-5", "concurrent", versions["entry-5"], map[string]any{"title": "after watermark"}), PushApplied)
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
		for _, invalid := range []BootstrapRequest{
			{Generation: "wrong", Watermark: state.Head, Limit: 2},
			{Generation: state.Generation, Watermark: state.Head + 100, Limit: 2},
			{Generation: state.Generation, Watermark: state.Head, After: state.Head + 1, Limit: 2},
		} {
			if _, err := storage.Bootstrap(accountID, invalid); !errors.Is(err, ErrResetRequired) {
				test.Fatalf("invalid cursor did not require reset: %v", err)
			}
		}
	})
}

func TestFullSyncDependencyRetryAndRollback(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		child := revisionItem("decision_options", "option", "child", 0, map[string]any{"title": "option"})
		child.References = []RowReference{{Table: "decision_collections", ID: "collection"}}
		pushRevision(test, storage, accountID, child, PushDependencyMissing)
		pushRevision(test, storage, accountID, revisionItem("decision_collections", "collection", "parent", 0, map[string]any{"title": "parent"}), PushApplied)
		if result := pushRevision(test, storage, accountID, child, PushApplied); result.Replayed {
			test.Fatal("retryable dependency error was cached as a final receipt")
		}
		pushRevision(test, storage, accountID, revisionItem("stored_value_accounts", "account", "account", 0, map[string]any{"name": "card"}), PushApplied)
		pushRevision(test, storage, accountID, revisionItem("stored_value_adjustments", "opening", "opening", 0, map[string]any{"account_id": "account", "amount_cents": int64(math.MaxInt64)}), PushApplied)
		state, _ := storage.SyncState(accountID)
		results, err := storage.Push(accountID, []PushItem{
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
		result := pushRevision(test, storage, accountID, revisionItem("diary_entries", "rollback", "rollback", 0, map[string]any{"title": "must roll back"}), PushApplied)
		if result.Replayed {
			test.Fatal("rolled-back receipt survived")
		}
	})
}

func TestFullSyncBalancesPreserveLegacyEffects(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		pushRevision(test, storage, accountID, revisionItem("stored_value_accounts", "card", "card", 0, map[string]any{"name": "card"}), PushApplied)
		pushRevision(test, storage, accountID, revisionItem("stored_value_adjustments", "opening:card", "opening", 0, map[string]any{"account_id": "card", "amount_cents": 10000}), PushApplied)
		for index, amount := range []int64{200, 300} {
			identifier := fmt.Sprintf("spend-%d", index)
			pushRevision(test, storage, accountID, revisionItem("bills", identifier, identifier, 0, map[string]any{
				"stored_value_account_id": "card", "transaction_type": "storedValueUse", "final_amount_cents": amount,
			}), PushApplied)
		}
		if snapshot := liveSnapshot(test, storage, accountID, "stored_value_accounts", "card"); snapshot["balance_cents"] != float64(9500) {
			test.Fatalf("offline spending did not accumulate: %+v", snapshot)
		}
		legacy := revisionItem("bills", "expired", "legacy", 0, map[string]any{
			"stored_value_account_id": "card", "transaction_type": "expense", "category": "groupBuy",
			"settlement_status": "active", "group_buy_valid_until": 500, "original_amount_cents": 1000,
			"stored_value_effects": `{"card":1000}`,
		})
		first := pushRevision(test, storage, accountID, legacy, PushApplied)
		edit := revisionItem("bills", "expired", "expired-edit", first.Version, map[string]any{"title": "edit expired bill"})
		second := pushRevision(test, storage, accountID, edit, PushApplied)
		deletion := revisionItem("bills", "expired", "expired-delete", second.Version, nil)
		deletion.Operation = OpDelete
		pushRevision(test, storage, accountID, deletion, PushApplied)
		if snapshot := liveSnapshot(test, storage, accountID, "stored_value_accounts", "card"); snapshot["balance_cents"] != float64(10500) {
			test.Fatalf("editing/deleting an expired bill changed legacy balance semantics: %+v", snapshot)
		}
	})
}

func TestFullSyncCompletionMergeAndDelete(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage Store, accountID string) {
		pushRevision(test, storage, accountID, revisionItem("reminders", "reminder", "reminder", 0, map[string]any{
			"schedule_kind": "continuousDays", "continuous_days": 2, "completed_at": nil,
		}), PushApplied)
		var last PushResult
		for _, dateKey := range []string{"2026-09-07", "2026-09-08"} {
			last = pushRevision(test, storage, accountID, revisionItem("reminder_completions", "reminder:"+dateKey, dateKey, 0, map[string]any{
				"reminder_id": "reminder", "date_key": dateKey, "completed_at": 1000, "kind": "checkIn",
			}), PushApplied)
		}
		snapshot := liveSnapshot(test, storage, accountID, "reminders", "reminder")
		if snapshot["completed_date_keys"] != "2026-09-07,2026-09-08" || snapshot["completed_at"] != float64(1000) {
			test.Fatalf("independent check-ins lost: %+v", snapshot)
		}
		deletion := revisionItem("reminder_completions", last.ID, "remove-last", last.Version, nil)
		deletion.Operation = OpDelete
		pushRevision(test, storage, accountID, deletion, PushApplied)
		snapshot = liveSnapshot(test, storage, accountID, "reminders", "reminder")
		if snapshot["completed_date_keys"] != "2026-09-07" || snapshot["completed_at"] != nil {
			test.Fatalf("removed check-in retained stale completion status: %+v", snapshot)
		}
	})
}

func TestFullSyncReceiptsSurviveRestartAndExport(test *testing.T) {
	directory := test.TempDir()
	storage, err := NewSQLiteStore(directory)
	if err != nil {
		test.Fatal(err)
	}
	accountID := mustAccount(test, storage)
	candidate := revisionItem("diary_entries", "entry", "stable-receipt", 0, map[string]any{"title": "original"})
	first := pushRevision(test, storage, accountID, candidate, PushApplied)
	pushRevision(test, storage, accountID, revisionItem("diary_entries", "entry", "later", first.Version, map[string]any{"title": "later"}), PushApplied)
	state, _ := storage.SyncState(accountID)
	if err := storage.Close(); err != nil {
		test.Fatal(err)
	}
	storage, err = NewSQLiteStore(directory)
	if err != nil {
		test.Fatal(err)
	}
	defer storage.Close()
	if replay := pushRevision(test, storage, accountID, candidate, PushApplied); !replay.Replayed || replay.Version != first.Version {
		test.Fatal("restart lost original receipt")
	}
	var dump bytes.Buffer
	if err := storage.(SQLDumper).ExportSQL(&dump, accountID); err != nil {
		test.Fatal(err)
	}
	restored, err := NewSQLiteStore(test.TempDir())
	if err != nil {
		test.Fatal(err)
	}
	defer restored.Close()
	if _, err := restored.(*sqliteStore).db.Exec(dump.String()); err != nil {
		test.Fatal(err)
	}
	if replay := pushRevision(test, restored, accountID, candidate, PushApplied); !replay.Replayed || replay.Version != first.Version {
		test.Fatal("export lost receipt")
	}
	restoredState, _ := restored.SyncState(accountID)
	if restoredState != state {
		test.Fatalf("export changed dataset identity: %+v != %+v", restoredState, state)
	}
}
