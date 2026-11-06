package repository

import (
	"bytes"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"testing"
)

func TestSnapshotQueryImmutableVersionsAndIsolation(t *testing.T) {
	withSyncStores(t, func(t *testing.T, storage domain.Store, account string) {
		first := pushRevision(t, storage, account, revisionItem("diary_tags", "tag", "first", 0, map[string]any{"name": "old"}), domain.PushApplied)
		state, _ := storage.SyncState(account)
		second := pushRevision(t, storage, account, revisionItem("diary_tags", "tag", "second", first.Version, map[string]any{"name": "new"}), domain.PushApplied)
		deleted := revisionItem("diary_tags", "tag", "delete", second.Version, nil)
		deleted.Operation = domain.OpDelete
		third := pushRevision(t, storage, account, deleted, domain.PushApplied)
		query := domain.SnapshotQuery{Generation: state.Generation, Watermark: state.Head, Versions: []int64{first.Version}}
		rows, err := storage.SnapshotRevisions(account, query)
		if err != nil || len(rows) != 1 || !bytes.Contains(rows[0].Snapshot, []byte("old")) || rows[0].Deleted {
			t.Fatalf("immutable query: %+v %v", rows, err)
		}
		query.Watermark = third.Version
		query.Versions = []int64{third.Version, first.Version}
		rows, err = storage.SnapshotRevisions(account, query)
		if err != nil || !rows[0].Deleted || rows[1].Version != first.Version {
			t.Fatalf("tombstone/order: %+v %v", rows, err)
		}
		other, err := storage.InitAccount([]byte("manifest-other-account"))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := storage.SnapshotRevisions(other.ID, query); !errors.Is(err, domain.ErrResetRequired) {
			t.Fatalf("foreign generation accepted: %v", err)
		}
		for _, versions := range [][]int64{{0}, {first.Version, first.Version}, {third.Version + 1}} {
			query.Versions = versions
			if _, err := storage.SnapshotRevisions(account, query); !errors.Is(err, domain.ErrNotFound) {
				t.Fatalf("invalid versions accepted: %v", err)
			}
		}
		if _, err := storage.ClearDataset(account, state.Generation); err != nil {
			t.Fatal(err)
		}
		query.Versions = []int64{first.Version}
		if _, err := storage.SnapshotRevisions(account, query); !errors.Is(err, domain.ErrResetRequired) {
			t.Fatalf("stale generation accepted: %v", err)
		}
	})
}
