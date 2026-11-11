package repository

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestInventoryIgnoresMediaStorageAndRealNotation(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		for _, test := range []struct{ table, original, incoming string }{
			{"tracks", `{"name":"walk","distance_m":0.0,"avg_speed_mps":1e-6}`, `{"name":"walk","distance_m":0,"avg_speed_mps":0.000001}`},
			{"media_assets", `{"checksum":"same","storage_key":"images/asset.jpg","storage_backend":"local"}`, `{"checksum":"same","storage_key":"sync/same","storage_backend":"local"}`},
		} {
			first := revisionItem(test.table, test.table, test.table+"-original", 0, nil)
			first.Snapshot = json.RawMessage(test.original)
			applied := pushRevision(t, store, account, first, domain.PushApplied)
			first.MutationID += "-inventory"
			first.Snapshot = json.RawMessage(test.incoming)
			duplicate := pushRevision(t, store, account, first, domain.PushDuplicate)
			if duplicate.Version != applied.Version {
				t.Fatal("unchanged inventory generated a revision")
			}
			first.MutationID += "-changed"
			first.Snapshot = json.RawMessage(`{"checksum":"different","name":"different"}`)
			pushRevision(t, store, account, first, domain.PushConflict)
		}
	})
}

func TestClearDatasetIsScopedRotatesGenerationAndRetainsDevices(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		otherAccount, err := store.InitAccount([]byte("other-recovery-key"))
		if err != nil {
			t.Fatal(err)
		}
		other := otherAccount.ID
		device, err := store.CreateDevice(account, "owner", []byte("owner-token"), true)
		if err != nil {
			t.Fatal(err)
		}
		state, _ := store.SyncState(account)
		pushRevision(t, store, account, revisionItem("media_assets", "asset", "asset-edit", 0, map[string]any{"checksum": "test"}), domain.PushApplied)
		pushRevision(t, store, account, revisionItem("reminders", "reminder", "reminder-edit", 0, map[string]any{"image_asset_id": "asset"}), domain.PushApplied)
		otherMutation := revisionItem("diary_tags", "other-tag", "other-edit", 0, map[string]any{"name": "keep"})
		pushRevision(t, store, other, otherMutation, domain.PushApplied)
		if err := store.PutMedia(account, "sync/test", "test", "image/jpeg", 3); err != nil {
			t.Fatal(err)
		}
		mediaPath := store.MediaPath(account, "test")
		if mediaPath != "" {
			if err := os.MkdirAll(filepath.Dir(mediaPath), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(mediaPath, []byte("abc"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		if _, err := store.ClearDataset(account, "stale"); !errors.Is(err, domain.ErrResetRequired) {
			t.Fatal("stale reset accepted", err)
		}
		cleared, err := store.ClearDataset(account, state.Generation)
		if err != nil {
			t.Fatal(err)
		}
		if cleared.Generation == state.Generation || cleared.Head != 0 {
			t.Fatal("invalid new dataset", cleared)
		}
		for _, table := range []string{"media_assets", "reminders"} {
			rows, err := store.Rows(account, table)
			if err != nil || len(rows) != 0 {
				t.Fatal("rows retained", table, rows, err)
			}
		}
		if _, err := store.GetDevice(device.ID); err != nil {
			t.Fatal("device binding removed", err)
		}
		if _, err := store.GetMedia(account, "sync/test"); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("media retained", err)
		}
		if mediaPath != "" {
			if _, err := os.Stat(mediaPath); !errors.Is(err, os.ErrNotExist) {
				t.Fatal("media bytes retained", err)
			}
		}
		changes, _, err := store.Pull(account, 0, 10)
		if err != nil || len(changes) != 0 {
			t.Fatal("history retained", err)
		}
		pushRevision(t, store, other, otherMutation, domain.PushApplied)
		// 重置后复用同一个变更 ID 必须创建新数据，绝不复用旧回执。
		pushRevision(t, store, account, revisionItem("media_assets", "asset", "asset-edit", 0, map[string]any{"checksum": "new"}), domain.PushApplied)
	})
}
