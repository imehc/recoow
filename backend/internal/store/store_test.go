package store

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func item(table, id string, op Operation, ts int64, device string, snap map[string]any) PushItem {
	var raw json.RawMessage
	if snap != nil {
		b, _ := json.Marshal(snap)
		raw = b
	}
	return PushItem{Table: table, ID: id, Operation: op, UpdatedAtMs: ts, DeviceID: device, Snapshot: raw}
}

func snap(updatedAt int64) map[string]any {
	return map[string]any{"updated_at": updatedAt, "title": "x"}
}

func mustAccount(t *testing.T, st Store) string {
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

	r, err := st.Push(acc, []PushItem{
		item("bills", "b1", OpInsert, 100, "dev-a", snap(100)),
		item("bills", "b1", OpUpdate, 200, "dev-a", snap(200)),
		item("bills", "b1", OpUpdate, 150, "dev-b", snap(150)), // 更旧，应失败
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

	first := item("reminders", "r1", OpInsert, 500, "dev-a", snap(500))
	if _, err := st.Push(acc, []PushItem{first}); err != nil {
		t.Fatal(err)
	}
	// 重放完全相同的变更不应推进版本号。
	r2, _ := st.Push(acc, []PushItem{first})
	if r2[0].Applied {
		t.Fatal("replay of identical change must not apply")
	}
	// 乱序：后到的更旧变更不应让状态回退。
	r3, _ := st.Push(acc, []PushItem{item("reminders", "r1", OpUpdate, 400, "dev-b", snap(400))})
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
	if _, err := st.Push(acc, []PushItem{item("bills", "b1", OpInsert, 300, "dev-b", snap(300))}); err != nil {
		t.Fatal(err)
	}
	// 时间戳相同：device_id 字典序更大者确定性地胜出。
	r, _ := st.Push(acc, []PushItem{item("bills", "b1", OpUpdate, 300, "dev-a", snap(300))})
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
	if _, err := st.Push(acc, []PushItem{item("bills", "b1", OpInsert, 100, "dev-a", snap(100))}); err != nil {
		t.Fatal(err)
	}
	// 删除获胜。
	r, _ := st.Push(acc, []PushItem{item("bills", "b1", OpDelete, 200, "dev-a", nil)})
	if !r[0].Applied {
		t.Fatal("expected delete to apply")
	}
	rows, _ := st.Rows(acc, "bills")
	if len(rows) != 0 {
		t.Fatal("deleted row should not appear in live rows")
	}
	// 比墓碑更旧的写入不得复活该行。
	r2, _ := st.Push(acc, []PushItem{item("bills", "b1", OpInsert, 150, "dev-b", snap(150))})
	if r2[0].Applied {
		t.Fatal("stale write must not resurrect tombstone")
	}
	// 严格更新的写入可复活该行。
	r3, _ := st.Push(acc, []PushItem{item("bills", "b1", OpInsert, 300, "dev-b", snap(300))})
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
		if _, err := st.Push(acc, []PushItem{item("tracks", id(i), OpInsert, int64(i), "dev-a", snap(int64(i)))}); err != nil {
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
	r, _ := st.Push(acc, []PushItem{item("not_a_table", "x", OpInsert, 1, "dev-a", snap(1))})
	if r[0].Applied {
		t.Fatal("unsupported table must be rejected")
	}
}

func TestSQLiteStoreRoundTrip(t *testing.T) {
	dir := t.TempDir()
	st, err := NewSQLiteStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	acc := mustAccount(t, st)
	if _, err := st.Push(acc, []PushItem{item("bills", "b1", OpInsert, 100, "dev-a", snap(100))}); err != nil {
		t.Fatal(err)
	}
	// 同主键再次推送：覆盖 upsert / 版本号推进路径。
	if _, err := st.Push(acc, []PushItem{item("bills", "b1", OpUpdate, 200, "dev-a", snap(200))}); err != nil {
		t.Fatal(err)
	}
	if err := st.PutMedia(acc, "media/x.png", "abc123", "image/png", 10); err != nil {
		t.Fatal(err)
	}
	if err := st.Close(); err != nil {
		t.Fatal(err)
	}

	st2, err := NewSQLiteStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	rows, err := st2.Rows(acc, "bills")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("expected reloaded row, got %d", len(rows))
	}
	if rows[0].UpdatedAtMs != 200 {
		t.Fatalf("expected updatedAt 200 after upsert, got %d", rows[0].UpdatedAtMs)
	}
	if _, err := st2.GetMedia(acc, "media/x.png"); err != nil {
		t.Fatalf("reloaded media lost: %v", err)
	}
}

func TestSQLiteExportRestore(t *testing.T) {
	st, err := NewSQLiteStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	acc := mustAccount(t, st)
	billSnap := snap(100)
	billRaw, err := json.Marshal(billSnap)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Push(acc, []PushItem{
		item("bills", "b1", OpInsert, 100, "dev-a", billSnap),
		item("reminders", "r1", OpInsert, 200, "dev-b", snap(200)),
	}); err != nil {
		t.Fatal(err)
	}
	// 删除一条：墓碑行也要被导出并保持不可复活。
	if _, err := st.Push(acc, []PushItem{item("reminders", "r1", OpDelete, 300, "dev-b", nil)}); err != nil {
		t.Fatal(err)
	}
	if err := st.PutMedia(acc, "media/x.png", "abc123", "image/png", 10); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	if err := st.(SQLDumper).ExportSQL(&buf, acc); err != nil {
		t.Fatal(err)
	}
	sqlText := buf.String()
	for _, want := range []string{
		"CREATE TABLE IF NOT EXISTS account",
		"CREATE TABLE IF NOT EXISTS change_log",
		"INSERT INTO row_state",
		"INSERT INTO change_log",
		"'b1'",
	} {
		if !strings.Contains(sqlText, want) {
			t.Fatalf("导出缺少 %q", want)
		}
	}
	if err := st.Close(); err != nil {
		t.Fatal(err)
	}

	// 把导出的 SQL 回放到全新数据库，应恢复出等价状态。
	st2raw, err := NewSQLiteStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st2raw.Close()
	st2 := st2raw.(*sqliteStore)
	if _, err := st2.db.ExecContext(st2.ctx, sqlText); err != nil {
		t.Fatalf("回放导出 SQL: %v", err)
	}
	acc2, err := st2.GetAccount(acc)
	if err != nil {
		t.Fatal(err)
	}
	if acc2.ID != acc || acc2.NextVersion != 4 || !bytes.Equal(acc2.RecoveryKeyHash, []byte("recovery-hash")) {
		t.Fatalf("账号恢复不一致: %+v vs %+v", acc2, acc)
	}
	rows, err := st2.Rows(acc, "bills")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || !bytes.Equal(rows[0].Snapshot, billRaw) {
		t.Fatalf("bills 行恢复不一致: %+v", rows)
	}
	// 墓碑行：无存活行，过期写入不得复活。
	rem, err := st2.Rows(acc, "reminders")
	if err != nil {
		t.Fatal(err)
	}
	if len(rem) != 0 {
		t.Fatalf("reminders 应无存活行, 实际 %d", len(rem))
	}
	if r, _ := st2.Push(acc, []PushItem{item("reminders", "r1", OpInsert, 250, "dev-a", snap(250))}); r[0].Applied {
		t.Fatal("过期写入不应复活墓碑")
	}
	// 变更日志完整可拉取。
	if _, next, err := st2.Pull(acc, 0, 10); err != nil || next != 3 {
		t.Fatalf("变更日志恢复不一致, next=%d err=%v", next, err)
	}
	if _, err := st2.GetMedia(acc, "media/x.png"); err != nil {
		t.Fatalf("媒体索引恢复失败: %v", err)
	}
}

func id(i int) string { return string(rune('0' + i)) }
