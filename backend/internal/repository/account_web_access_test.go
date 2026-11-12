package repository

import (
	"errors"
	"testing"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestAccountWebAccessCheckedAtRevisionCommit(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		other, err := store.InitAccount([]byte("other-recovery-hash"))
		if err != nil {
			t.Fatal(err)
		}
		item := revisionItem("diary_tags", "tag", "web-create", 0, map[string]any{"name": "tag"})
		state, _ := store.SyncState(account)
		context := domain.PushContext{Generation: state.Generation, DirectEdit: true, RequireWebWrite: true}
		if _, err := store.PushWithContext(account, []domain.PushItem{item}, context); !errors.Is(err, domain.ErrWebWriteDenied) {
			t.Fatalf("disabled account wrote: %v", err)
		}
		if err := store.SetAccountWebRecordWrites(account, true); err != nil {
			t.Fatal(err)
		}
		settings, _ := store.GetAccount(other.ID)
		if settings.WebRecordWrites {
			t.Fatal("account authorization leaked")
		}
		if _, err := store.PushWithContext(account, []domain.PushItem{item}, context); err != nil {
			t.Fatal(err)
		}
		// PostgreSQL 的撤销与修订竞争同一行锁，覆盖不同实例的并发请求。
		if postgres, ok := store.(*postgresStore); ok {
			tx, err := postgres.db.BeginTx(postgres.ctx, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback()
			if _, err := tx.ExecContext(postgres.ctx, "UPDATE account SET web_record_writes = false WHERE id = ?", account); err != nil {
				t.Fatal(err)
			}
			result := make(chan error, 1)
			go func() {
				_, err := store.PushWithContext(account, []domain.PushItem{item}, context)
				result <- err
			}()
			if err := tx.Commit(); err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-result:
				if !errors.Is(err, domain.ErrWebWriteDenied) {
					t.Fatalf("revocation raced past revision lock: %v", err)
				}
			case <-time.After(10 * time.Second):
				t.Fatal("concurrent authorization update stalled")
			}
		} else if err := store.SetAccountWebRecordWrites(account, false); err != nil {
			t.Fatal(err)
		}
		before, _ := store.SyncState(account)
		if _, err := store.PushWithContext(account, []domain.PushItem{item}, context); !errors.Is(err, domain.ErrWebWriteDenied) {
			t.Fatal("revoked account retried a write")
		}
		after, _ := store.SyncState(account)
		if before != after {
			t.Fatal("rejected write advanced the revision")
		}
		// 长期设备同步与账号 Web 开关独立。
		pushRevision(t, store, account, revisionItem("diary_tags", "mobile", "mobile-create", 0, map[string]any{"name": "mobile"}), domain.PushApplied)
		if err := store.SetAccountWebRecordWrites(account, true); err != nil {
			t.Fatal(err)
		}
		after, _ = store.SyncState(account)
		if _, err := store.ClearDataset(account, after.Generation); err != nil {
			t.Fatal(err)
		}
		settings, _ = store.GetAccount(account)
		if !settings.WebRecordWrites {
			t.Fatal("dataset reset erased account setting")
		}
	})
}
