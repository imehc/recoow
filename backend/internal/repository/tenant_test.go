package repository

import (
	"bytes"
	"github.com/imehc/recoow/backend/internal/domain"
	"testing"
)

// TestMultiAccountIsolation 校验同一实例内两个账号的数据集、版本号空间、
// 媒体与导出完全隔离——这是多人共用一个后端的最低安全边界。
func TestMultiAccountIsolation(test *testing.T) {
	withSyncStores(test, func(test *testing.T, storage domain.Store, firstAccount string) {
		second, err := storage.InitAccount([]byte("recovery-hash-second"))
		if err != nil {
			test.Fatalf("init second account: %v", err)
		}
		if second.ID == firstAccount {
			test.Fatal("second account reused first account id")
		}

		// 同表同 id 在两个账号里各自写入，互不覆盖。
		firstResult := pushRevision(test, storage, firstAccount,
			revisionItem("diary_entries", "shared-id", "mutation-a", 0, map[string]any{"title": "first"}), domain.PushApplied)
		secondResult := pushRevision(test, storage, second.ID,
			revisionItem("diary_entries", "shared-id", "mutation-b", 0, map[string]any{"title": "second"}), domain.PushApplied)

		if firstResult.Version != 1 || secondResult.Version != 1 {
			test.Fatalf("version spaces must be per account: %d / %d", firstResult.Version, secondResult.Version)
		}
		if title := liveSnapshot(test, storage, firstAccount, "diary_entries", "shared-id"); title["title"] != "first" {
			test.Fatalf("first account snapshot overwritten: %+v", title)
		}
		if title := liveSnapshot(test, storage, second.ID, "diary_entries", "shared-id"); title["title"] != "second" {
			test.Fatalf("second account snapshot overwritten: %+v", title)
		}

		// 增量日志不得跨账号泄露。
		firstChanges, _, err := storage.Pull(firstAccount, 0, 100)
		if err != nil {
			test.Fatal(err)
		}
		if len(firstChanges) != 1 || firstChanges[0].ID != "shared-id" {
			test.Fatalf("first account changelog polluted: %+v", firstChanges)
		}
		for _, change := range firstChanges {
			if change.AccountID != firstAccount {
				test.Fatalf("changelog leaked another account: %+v", change)
			}
		}

		// 媒体同名 key 在两个账号下是两份独立对象。
		if err := storage.PutMedia(firstAccount, "media/same.png", "checksum-first", "image/png", 10); err != nil {
			test.Fatal(err)
		}
		if err := storage.PutMedia(second.ID, "media/same.png", "checksum-second", "image/png", 20); err != nil {
			test.Fatal(err)
		}
		if object, err := storage.GetMedia(firstAccount, "media/same.png"); err != nil || object.Checksum != "checksum-first" {
			test.Fatalf("first account media wrong: %+v %v", object, err)
		}
		if object, err := storage.GetMedia(second.ID, "media/same.png"); err != nil || object.Checksum != "checksum-second" {
			test.Fatalf("second account media wrong: %+v %v", object, err)
		}

		// 导出必须以账号为界。
		dumper, ok := storage.(domain.SQLDumper)
		if ok {
			var dump bytes.Buffer
			if err := dumper.ExportSQL(&dump, second.ID); err != nil {
				test.Fatal(err)
			}
			if !bytes.Contains(dump.Bytes(), []byte("checksum-second")) {
				test.Fatal("export missing own media")
			}
			if bytes.Contains(dump.Bytes(), []byte("checksum-first")) || bytes.Contains(dump.Bytes(), []byte("first")) {
				test.Fatal("export leaked another account's data")
			}

		}
		// 未知账号一律拒绝，不能退化为"默认账号"。
		if _, err := storage.Rows("missing-account", "bills"); err == nil {
			test.Fatal("unknown account should be rejected")
		}
		if _, _, err := storage.Pull("missing-account", 0, 10); err == nil {
			test.Fatal("unknown account pull should be rejected")
		}
		if _, err := storage.Push("missing-account", []domain.PushItem{item("bills", "x", domain.OpInsert, 1, "dev", nil)}); err == nil {
			test.Fatal("unknown account push should be rejected")
		}
	})
}

// TestMultiAccountDevicesAndCount 校验设备归属与账号计数。
func TestMultiAccountDevicesAndCount(t *testing.T) {
	st := NewMemoryStore()
	if count, err := st.CountAccounts(); err != nil || count != 0 {
		t.Fatalf("expected empty store, got %d %v", count, err)
	}
	first := mustAccount(t, st)
	second, err := st.InitAccount([]byte("another-hash"))
	if err != nil {
		t.Fatal(err)
	}
	if count, _ := st.CountAccounts(); count != 2 {
		t.Fatalf("expected 2 accounts, got %d", count)
	}
	if _, err := st.CreateDevice(first, "iPhone", []byte("token-first"), true); err != nil {
		t.Fatal(err)
	}
	if _, err := st.CreateDevice(second.ID, "iPad", []byte("token-second"), true); err != nil {
		t.Fatal(err)
	}
	firstDevices, _ := st.ListDevices(first)
	secondDevices, _ := st.ListDevices(second.ID)
	if len(firstDevices) != 1 || len(secondDevices) != 1 {
		t.Fatalf("device lists not isolated: %d / %d", len(firstDevices), len(secondDevices))
	}
	if firstDevices[0].AccountID != first || secondDevices[0].AccountID != second.ID {
		t.Fatal("device bound to wrong account")
	}
	if _, err := st.InitAccount([]byte("another-hash")); err != domain.ErrRecoveryKeyTaken {
		t.Fatalf("duplicate recovery key should be rejected, got %v", err)
	}
}

// legacyV3DDL 是加入多账号之前（user_version=3）的表结构，用于验证升级路径。
