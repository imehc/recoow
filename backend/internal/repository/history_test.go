package repository

import (
	"bytes"
	"encoding/json"
	"reflect"
	"testing"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestHistoryTimesSourcesReceiptsAndImmutableDetail(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		device, err := store.CreateDevice(account, "Android submitter", []byte("history-device"), true)
		if err != nil {
			t.Fatal(err)
		}
		state, _ := store.SyncState(account)
		context := domain.PushContext{Generation: state.Generation, Submission: &domain.SubmissionSource{
			ClientEnvironment: domain.ClientEnvironment{Client: "android", OS: "android", AppVersion: "0.7.1", OSVersion: "16"}, DeviceID: device.ID, DeviceName: device.Name,
		}}
		item := revisionItem("diary_entries", "entry", "offline-edit", 0, map[string]any{"title": "offline title", "future": json.Number("9007199254740993")})
		item.UpdatedAtMs = 100
		item.Origin = &domain.ChangeOrigin{ClientEnvironment: domain.ClientEnvironment{Client: "ios", OS: "ios", AppVersion: "0.2.0"}, DeviceID: item.DeviceID, DeviceName: "Original iPhone"}
		start := time.Now().UnixMilli()
		results, err := store.PushWithContext(account, []domain.PushItem{item}, context)
		if err != nil || len(results) != 1 || len(results[0].Events) != 1 {
			t.Fatalf("push: %+v %v", results, err)
		}
		ref := results[0].Events[0]
		if ref.AcceptedAtMs < start || ref.AcceptedAtMs > time.Now().UnixMilli() {
			t.Fatal("server time is not acceptance time")
		}
		context.Submission.Client, context.Submission.AppVersion = "web", "different retry version"
		replayed, err := store.PushWithContext(account, []domain.PushItem{item}, context)
		if err != nil || !replayed[0].Replayed || !reflect.DeepEqual(results[0].Events, replayed[0].Events) {
			t.Fatal("retry changed original event association or time")
		}
		item.Origin.AppVersion = "changed origin"
		if changed := pushRevision(t, store, account, item, domain.PushMutationReused); len(changed.Events) != 0 {
			t.Fatal("reused mutation generated an event")
		}
		newer := revisionItem("diary_entries", "entry", "newer", results[0].Version, map[string]any{"title": "cloud title"})
		pushRevision(t, store, account, newer, domain.PushApplied)
		detail, err := store.ChangeEvent(account, ref.EventID)
		if err != nil || detail.Before != nil || detail.Event.Operation != "insert" || !bytes.Contains(detail.After.Snapshot, []byte("offline title")) {
			t.Fatalf("history uses current state: %+v %v", detail, err)
		}
		if detail.Event.OriginalSource.Client != "ios" || detail.Event.Submission.Client != "android" || detail.Event.Submission.DeviceID != device.ID || *detail.Event.ModifiedAtMs != 100 || *detail.Event.AcceptedAtMs != ref.AcceptedAtMs {
			t.Fatalf("source/time conflated: %+v", detail.Event)
		}
		detail.Event.Submission.DeviceName = "mutated caller copy"
		detail, _ = store.ChangeEvent(account, ref.EventID)
		if detail.Event.Submission.DeviceName != "Android submitter" {
			t.Fatal("caller can modify stored source")
		}
		if err := store.DeleteDevice(device.ID); err != nil {
			t.Fatal(err)
		}
		detail, err = store.ChangeEvent(account, ref.EventID)
		if err != nil || detail.Event.Submission.DeviceName != "Android submitter" {
			t.Fatal("device removal changed historical source")
		}
		other, err := store.InitAccount([]byte("history-other-account"))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.ChangeEvent(other.ID, ref.EventID); err != domain.ErrNotFound {
			t.Fatal("event leaked across accounts")
		}
		page, err := store.ChangeEvents(account, domain.ChangeEventQuery{Limit: 50, Client: "ios", DeviceID: device.ID})
		if err != nil || len(page.Events) != 1 {
			t.Fatalf("source filter: %+v %v", page, err)
		}
	})
}

func TestHistoryPaginationDeletionRestoreAndClear(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		created := pushRevision(t, store, account, revisionItem("diary_tags", "tag", "create", 0, map[string]any{"name": "initial"}), domain.PushApplied)
		changed := pushRevision(t, store, account, revisionItem("diary_tags", "tag", "update", created.Version, map[string]any{"name": "updated"}), domain.PushApplied)
		deletion := revisionItem("diary_tags", "tag", "delete", changed.Version, nil)
		deletion.Operation = domain.OpDelete
		deleted := pushRevision(t, store, account, deletion, domain.PushApplied)
		first, err := store.ChangeEvents(account, domain.ChangeEventQuery{Limit: 1})
		if err != nil || !first.More || first.Events[0].Operation != "delete" {
			t.Fatalf("page: %+v %v", first, err)
		}
		detail, err := store.ChangeEvent(account, deleted.Events[0].EventID)
		if err != nil || detail.Before.Version != changed.Version || !bytes.Contains(detail.Before.Snapshot, []byte("updated")) {
			t.Fatal("delete lost prior cloud state")
		}
		restored := pushRevision(t, store, account, revisionItem("diary_tags", "tag", "restore", deleted.Version, map[string]any{"name": "restored"}), domain.PushApplied)
		second, err := store.ChangeEvents(account, domain.ChangeEventQuery{Generation: first.Generation, Watermark: &first.Watermark, Before: first.Before, Limit: 20})
		if err != nil || len(second.Events) != 2 || second.Events[0].Version != changed.Version {
			t.Fatal("pagination included later changes or skipped earlier events")
		}
		detail, _ = store.ChangeEvent(account, restored.Events[0].EventID)
		if detail.Event.Operation != "restore" || detail.Before.DeletedAtMs == 0 {
			t.Fatal("restore not classified")
		}
		if _, err := store.ClearDataset(account, first.Generation); err != nil {
			t.Fatal(err)
		}
		if _, err := store.ChangeEvents(account, domain.ChangeEventQuery{Generation: first.Generation, Limit: 20}); err != domain.ErrResetRequired {
			t.Fatal("old history generation accepted")
		}
		if _, err := store.ChangeEvent(account, restored.Events[0].EventID); err != domain.ErrNotFound {
			t.Fatal("clear retained private history")
		}
	})
}

func TestHistoryDerivedChangesBelongToTrigger(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		initial := pushRevision(t, store, account, revisionItem("stored_value_accounts", "balance", "balance-create", 0, map[string]any{"name": "Wallet"}), domain.PushApplied)
		result := pushRevision(t, store, account, revisionItem("stored_value_adjustments", "adjustment", "credit", 0, map[string]any{"account_id": "balance", "amount_cents": 100}), domain.PushApplied)
		if len(result.Events) != 2 {
			t.Fatalf("expected primary and balance event: %+v", result)
		}
		primary, _ := store.ChangeEvent(account, result.Events[0].EventID)
		derived, err := store.ChangeEvent(account, result.Events[1].EventID)
		if err != nil || derived.Event.Method != "system" || derived.Event.ModifiedAtMs != nil || derived.Event.OriginalSource != nil || *derived.Event.TriggerEventID != primary.Event.EventID || derived.Event.OperationID != primary.Event.OperationID || derived.Event.MutationID != "credit" {
			t.Fatalf("derived source: %+v %v", derived.Event, err)
		}
		if derived.Before.Version != initial.Version || len(primary.Related) != 1 {
			t.Fatal("missing prior state or operation association")
		}
	})
}
