package repository

import (
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"testing"
)

func item(table, id string, op domain.Operation, ts int64, device string, snap map[string]any) domain.PushItem {
	var raw json.RawMessage
	if snap != nil {
		b, _ := json.Marshal(snap)
		raw = b
	}
	return domain.PushItem{Table: table, ID: id, Operation: op, UpdatedAtMs: ts, DeviceID: device, Snapshot: raw}
}

func snap(updatedAt int64) map[string]any {
	return map[string]any{"updated_at": updatedAt, "title": "x"}
}

func mustAccount(t *testing.T, st domain.Store) string {
	t.Helper()
	acc, err := st.InitAccount([]byte("recovery-hash"))
	if err != nil {
		t.Fatalf("init account: %v", err)
	}
	return acc.ID
}

func TestPushLWWNewerWins(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)

	r, err := st.Push(acc, []domain.PushItem{
		item("bills", "b1", domain.OpInsert, 100, "dev-a", snap(100)),
		item("bills", "b1", domain.OpUpdate, 200, "dev-a", snap(200)),
		item("bills", "b1", domain.OpUpdate, 150, "dev-b", snap(150)), // 更旧，应失败
	})
	if err != nil {
		t.Fatal(err)
	}
	if !r[0].Applied || !r[1].Applied || r[2].Applied {
		t.Fatalf("expected newer applied, older lost: %+v", r)
	}
	rows, _ := st.Rows(acc, "bills")
	if len(rows) != 1 {
		t.Fatalf("expected 1 row, got %d", len(rows))
	}
	if rows[0].UpdatedAtMs != 200 {
		t.Fatalf("expected updatedAt 200, got %d", rows[0].UpdatedAtMs)
	}
}

func TestPushIdempotentReplayAndOutOfOrder(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)

	first := item("reminders", "r1", domain.OpInsert, 500, "dev-a", snap(500))
	if _, err := st.Push(acc, []domain.PushItem{first}); err != nil {
		t.Fatal(err)
	}
	// 重放完全相同的变更不应推进版本号。
	r2, _ := st.Push(acc, []domain.PushItem{first})
	if r2[0].Applied {
		t.Fatal("replay of identical change must not apply")
	}
	// 乱序：后到的更旧变更不应让状态回退。
	r3, _ := st.Push(acc, []domain.PushItem{item("reminders", "r1", domain.OpUpdate, 400, "dev-b", snap(400))})
	if r3[0].Applied {
		t.Fatal("older out-of-order change must lose")
	}
	rows, _ := st.Rows(acc, "reminders")
	if len(rows) != 1 || rows[0].UpdatedAtMs != 500 {
		t.Fatalf("state regressed: %+v", rows)
	}
}

func TestEqualUpdatedAtTieBreak(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)
	if _, err := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpInsert, 300, "dev-b", snap(300))}); err != nil {
		t.Fatal(err)
	}
	// 时间戳相同：device_id 字典序更大者确定性地胜出。
	r, _ := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpUpdate, 300, "dev-a", snap(300))})
	if r[0].Applied {
		t.Fatal("dev-a should lose tie to dev-b")
	}
	rows, _ := st.Rows(acc, "bills")
	if rows[0].DeviceID != "dev-b" {
		t.Fatalf("expected dev-b to remain, got %s", rows[0].DeviceID)
	}
}

func TestDeleteAndResurrect(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)
	if _, err := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpInsert, 100, "dev-a", snap(100))}); err != nil {
		t.Fatal(err)
	}
	// 删除获胜。
	r, _ := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpDelete, 200, "dev-a", nil)})
	if !r[0].Applied {
		t.Fatal("expected delete to apply")
	}
	rows, _ := st.Rows(acc, "bills")
	if len(rows) != 0 {
		t.Fatal("deleted row should not appear in live rows")
	}
	// 比墓碑更旧的写入不得复活该行。
	r2, _ := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpInsert, 150, "dev-b", snap(150))})
	if r2[0].Applied {
		t.Fatal("stale write must not resurrect tombstone")
	}
	// 严格更新的写入可复活该行。
	r3, _ := st.Push(acc, []domain.PushItem{item("bills", "b1", domain.OpInsert, 300, "dev-b", snap(300))})
	if !r3[0].Applied {
		t.Fatal("newer write should resurrect")
	}
	rows, _ = st.Rows(acc, "bills")
	if len(rows) != 1 {
		t.Fatalf("expected resurrected row, got %d", len(rows))
	}
}

func TestPullCursorAndMore(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)
	for i := 0; i < 10; i++ {
		if _, err := st.Push(acc, []domain.PushItem{item("tracks", id(i), domain.OpInsert, int64(i), "dev-a", snap(int64(i)))}); err != nil {
			t.Fatal(err)
		}
	}
	entries, next, err := st.Pull(acc, 0, 4)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 4 || next != 4 {
		t.Fatalf("expected 4 entries and next=4, got len=%d next=%d", len(entries), next)
	}
	// 从游标 4 取第二页
	entries2, next2, _ := st.Pull(acc, next, 4)
	if len(entries2) != 4 || next2 != 8 {
		t.Fatalf("page2: len=%d next=%d", len(entries2), next2)
	}
	// 最后一页
	entries3, next3, _ := st.Pull(acc, next2, 4)
	if len(entries3) != 2 || next3 != 10 {
		t.Fatalf("page3: len=%d next=%d", len(entries3), next3)
	}
	// 已无更多
	entries4, next4, _ := st.Pull(acc, next3, 4)
	if len(entries4) != 0 || next4 != next3 {
		t.Fatalf("expected empty tail: len=%d next=%d", len(entries4), next4)
	}
}

func TestUnsupportedTableRejected(t *testing.T) {
	st := NewMemoryStore()
	acc := mustAccount(t, st)
	r, _ := st.Push(acc, []domain.PushItem{item("not_a_table", "x", domain.OpInsert, 1, "dev-a", snap(1))})
	if r[0].Applied {
		t.Fatal("unsupported table must be rejected")
	}
}

func id(index int) string { return fmt.Sprintf("row-%d", index) }
