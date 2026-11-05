package repository

import (
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestBillStatisticsOccurrenceRangeRefundsAndTombstones(t *testing.T) {
	storage := testPostgresStore(t)
	account := mustAccount(t, storage)
	for _, bill := range []struct {
		id, kind, status string
		occurred, cents  int64
	}{
		{"expense", "expense", "active", 1000, 100},
		{"income", "income", "active", 1999, 200},
		{"card-use", "storedValueUse", "active", 1500, 300},
		{"refunded", "expense", "refunded", 1000, 400},
		{"range-end", "expense", "active", 2000, 500},
		{"deleted", "expense", "active", 1200, 600},
	} {
		item := revisionItem("bills", bill.id, bill.id+"-create", 0, map[string]any{"id": bill.id, "title": bill.id, "transaction_type": bill.kind, "settlement_status": bill.status, "occurred_at": bill.occurred, "final_amount_cents": bill.cents})
		item.UpdatedAtMs = 5000
		pushRevision(t, storage, account, item, domain.PushApplied)
	}
	row, err := storage.Record(account, "bills", "deleted")
	if err != nil {
		t.Fatal(err)
	}
	item := revisionItem("bills", "deleted", "deleted-remove", row.Version, nil)
	item.Operation = domain.OpDelete
	item.UpdatedAtMs = 6000
	pushRevision(t, storage, account, item, domain.PushApplied)
	statistics, err := storage.Statistics(account, 1000, 2000)
	if err != nil {
		t.Fatal(err)
	}
	if statistics.BillExpenseCents != 100 || statistics.BillIncomeCents != 200 || statistics.BillStoredValueUseCents != 300 {
		t.Fatalf("wrong bill totals: %+v", statistics)
	}
	if statistics.RecordCounts["bills"] != 0 {
		t.Fatal("record count must use update time separately from occurrence time")
	}
}
