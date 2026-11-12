package repository

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
)

func billStatisticsFrame(year int, zone string) (domain.BillStatistics, *time.Location, error) {
	location, err := time.LoadLocation(zone)
	result := domain.BillStatistics{Year: year, Timezone: zone, Categories: []domain.BillCategoryStatistics{}}
	if err != nil || year < 1971 || year > 9998 || zone == "" || zone == "Local" {
		return result, nil, fmt.Errorf("invalid year or timezone")
	}
	for _, selected := range []int{year, year - 1} {
		months := make([]domain.BillMonthStatistics, 12)
		for i := range months {
			start := time.Date(selected, time.Month(i+1), 1, 0, 0, 0, 0, location)
			months[i] = domain.BillMonthStatistics{Month: i + 1, FromMs: start.UnixMilli(), ToMs: start.AddDate(0, 1, 0).UnixMilli()}
		}
		if selected == year {
			result.Months = months
		} else {
			result.PreviousMonths = months
		}
	}
	return result, location, nil
}

func (storage *postgresStore) BillStatistics(accountID string, year int, zone string) (domain.BillStatistics, error) {
	result, _, err := billStatisticsFrame(year, zone)
	if err != nil {
		return result, err
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	// 月度、上年和分类使用同一数据库快照，避免并发修改使金额与分类列表互相矛盾。
	tx, err := storage.beginAccountWithOptions(accountID, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	queries := sqlgen.New(tx.Tx)
	for _, months := range [][]domain.BillMonthStatistics{result.Months, result.PreviousMonths} {
		totals, err := queries.BillMonthlyTotals(storage.ctx, sqlgen.BillMonthlyTotalsParams{AccountID: accountID, Timezone: zone, FromMs: months[0].FromMs, ToMs: months[11].ToMs})
		if err != nil {
			return result, err
		}
		for _, total := range totals {
			i := int(total.Month) - 1
			if i < 0 || i >= len(months) {
				return result, fmt.Errorf("invalid month")
			}
			months[i].ExpenseCents, months[i].IncomeCents, months[i].StoredValueUseCents = total.ExpenseCents, total.IncomeCents, total.StoredValueUseCents
		}
	}
	categories, err := queries.BillCategoryTotals(storage.ctx, sqlgen.BillCategoryTotalsParams{AccountID: accountID, FromMs: result.Months[0].FromMs, ToMs: result.Months[11].ToMs})
	for _, category := range categories {
		result.Categories = append(result.Categories, domain.BillCategoryStatistics{Category: category.Category, ExpenseCents: category.ExpenseCents, Count: category.Count})
	}
	return result, err
}

func (storage *memoryStore) BillStatistics(accountID string, year int, zone string) (domain.BillStatistics, error) {
	result, location, err := billStatisticsFrame(year, zone)
	if err != nil {
		return result, err
	}
	rows, err := storage.Rows(accountID, "bills")
	if err != nil {
		return result, err
	}
	categories := map[string]domain.BillCategoryStatistics{}
	for _, row := range rows {
		var bill struct {
			Occurred int64  `json:"occurred_at"`
			Cents    int64  `json:"final_amount_cents"`
			Kind     string `json:"transaction_type"`
			Status   string `json:"settlement_status"`
			Category string `json:"category"`
		}
		if err := json.Unmarshal(row.Snapshot, &bill); err != nil {
			return result, err
		}
		if bill.Status == "refunded" {
			continue
		}
		date := time.UnixMilli(bill.Occurred).In(location)
		months := result.Months
		if date.Year() == year-1 {
			months = result.PreviousMonths
		} else if date.Year() != year {
			continue
		}
		month := &months[int(date.Month())-1]
		var target *int64
		switch bill.Kind {
		case "expense":
			target = &month.ExpenseCents
		case "income":
			target = &month.IncomeCents
		case "storedValueUse":
			target = &month.StoredValueUseCents
		}
		if target == nil {
			continue
		}
		if err := addBillCents(target, bill.Cents); err != nil {
			return result, err
		}
		if date.Year() == year && bill.Kind == "expense" {
			category := categories[bill.Category]
			category.Category, category.Count = bill.Category, category.Count+1
			if err := addBillCents(&category.ExpenseCents, bill.Cents); err != nil {
				return result, err
			}
			categories[bill.Category] = category
		}
	}
	for _, category := range categories {
		result.Categories = append(result.Categories, category)
	}
	sort.Slice(result.Categories, func(i, j int) bool {
		a, b := result.Categories[i], result.Categories[j]
		if a.ExpenseCents == b.ExpenseCents {
			return a.Category < b.Category
		}
		return a.ExpenseCents > b.ExpenseCents
	})
	return result, nil
}

func addBillCents(target *int64, value int64) error {
	if value > 0 && *target > math.MaxInt64-value || value < 0 && *target < math.MinInt64-value {
		return fmt.Errorf("amount overflow")
	}
	*target += value
	return nil
}
