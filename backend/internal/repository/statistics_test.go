package repository

import (
	"testing"
	"time"

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

func TestBillYearStatisticsAndDrilldownShareTimezoneCategoryAndRefundRules(t *testing.T) {
	storage := testPostgresStore(t)
	account := mustAccount(t, storage)
	otherAccount, err := storage.InitAccount([]byte("bill-statistics-other-account"))
	if err != nil {
		t.Fatal(err)
	}
	other := otherAccount.ID
	zone, _ := time.LoadLocation("America/New_York")
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, zone).UnixMilli()
	large := int64(9007199254740993)
	for _, item := range []struct {
		id, account, kind, status, category string
		at, cents                           int64
	}{
		{"boundary-before", account, "expense", "active", "food", start - 1, 20},
		{"boundary", account, "expense", "active", "food", start, large},
		{"unknown-category", account, "expense", "active", "custom/中文%_", start + 1, 30},
		{"uncategorized", account, "expense", "active", "", start + 1, 40},
		{"refund", account, "expense", "refunded", "food", start, 50},
		{"income-year", account, "income", "active", "food", start, 60},
		{"stored-year", account, "storedValueUse", "active", "food", start, 70},
		{"other-account", other, "expense", "active", "food", start, 80},
	} {
		pushRevision(t, storage, item.account, revisionItem("bills", item.id, item.id+"-create", 0, map[string]any{"id": item.id, "title": item.id, "transaction_type": item.kind, "settlement_status": item.status, "category": item.category, "occurred_at": item.at, "final_amount_cents": item.cents}), domain.PushApplied)
	}
	stats, err := storage.BillStatistics(account, 2026, "America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	if len(stats.Months) != 12 || len(stats.PreviousMonths) != 12 || stats.Months[0].ExpenseCents != large+70 || stats.Months[0].IncomeCents != 60 || stats.Months[0].StoredValueUseCents != 70 || stats.PreviousMonths[11].ExpenseCents != 20 {
		t.Fatalf("wrong totals: %+v", stats)
	}
	if stats.Months[0].FromMs != start || stats.Months[2].ToMs-stats.Months[2].FromMs != (31*24-1)*int64(time.Hour/time.Millisecond) {
		t.Fatal("timezone boundaries lost DST")
	}
	for _, category := range stats.Categories {
		page, err := storage.QueryRecords(account, domain.RecordQuery{Table: "bills", Limit: 50, DateField: "occurred_at", FromMs: stats.Months[0].FromMs, ToMs: stats.Months[11].ToMs, BillKind: "expense", BillCategory: &category.Category, ExcludeRefunded: true})
		if err != nil || int64(len(page.Rows)) != category.Count || len(page.Rows) != 1 {
			t.Fatalf("category drilldown mismatch: %+v %v %+v", category, err, page)
		}
	}
}
