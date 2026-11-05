package repository

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"github.com/jackc/pgx/v5"
	"sort"
	"strings"
)

func (storage *postgresStore) QueryRecords(accountID string, query domain.RecordQuery) (domain.RecordPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if !storage.tables[query.Table] {
		return domain.RecordPage{}, domain.ErrNotFound
	}
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return domain.RecordPage{}, err
	}
	defer tx.Rollback()
	dateField := "server_updated_at_ms"
	if query.DateField != "" {
		manifest, _ := storage.registry.Get(query.Table)
		valid := false
		for _, column := range manifest.Columns {
			valid = valid || column.Name == query.DateField && column.Type == "int"
		}
		if !valid {
			return domain.RecordPage{}, fmt.Errorf("invalid date field")
		}
		dateField = pgx.Identifier{query.DateField}.Sanitize()
	}
	// 表名和时间列来自已验证的契约；搜索词、游标与账号均使用参数。
	sqlText := fmt.Sprintf(`SELECT table_name,id,snapshot,updated_at_ms,device_id,deleted_at_ms,version,data_version FROM row_state WHERE account_id = ? AND table_name = ? AND deleted_at_ms = 0 AND id IN
      (SELECT id FROM %s WHERE tenant_id = ? AND tombstone_at_ms = 0 AND id > ? AND (?::text = '' OR to_jsonb(%s)::text ILIKE ?) AND (?::bigint = 0 OR %s >= ?) AND (?::bigint = 0 OR %s < ?)) ORDER BY id LIMIT ?`, pgx.Identifier{query.Table}.Sanitize(), pgx.Identifier{query.Table}.Sanitize(), dateField, dateField)
	rows, err := tx.Query(sqlText, accountID, query.Table, accountID, query.After, query.Search, "%"+query.Search+"%", query.FromMs, query.FromMs, query.ToMs, query.ToMs, query.Limit+1)
	if err != nil {
		return domain.RecordPage{}, err
	}
	defer rows.Close()
	page := domain.RecordPage{Rows: []domain.Row{}}
	for rows.Next() {
		row, err := scanSyncRow(rows)
		if err != nil {
			return page, err
		}
		if len(page.Rows) == query.Limit {
			page.More = true
			break
		}
		page.Rows = append(page.Rows, row)
	}
	if page.More {
		page.NextCursor = base64.RawURLEncoding.EncodeToString([]byte(page.Rows[len(page.Rows)-1].ID))
	}
	return page, rows.Err()
}
func (storage *postgresStore) Record(accountID, table, id string) (domain.Row, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if !storage.tables[table] {
		return domain.Row{}, domain.ErrNotFound
	}
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return domain.Row{}, err
	}
	defer tx.Rollback()
	row, exists, err := (postgresRevision{store: storage, transaction: tx, accountID: accountID}).ReadRow(table, id)
	if err != nil {
		return row, err
	}
	if !exists || row.DeletedAtMs > 0 {
		return domain.Row{}, domain.ErrNotFound
	}
	return row, nil
}
func (storage *postgresStore) Statistics(accountID string, from, to int64) (domain.Statistics, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return domain.Statistics{}, err
	}
	defer tx.Rollback()
	result := domain.Statistics{RecordCounts: map[string]int64{}}
	queries := sqlgen.New(tx.Tx)
	counts, err := queries.CountRecordsByTable(storage.ctx, sqlgen.CountRecordsByTableParams{AccountID: accountID, FromMs: from, ToMs: to})
	if err != nil {
		return result, err
	}
	for _, count := range counts {
		result.RecordCounts[count.TableName] = count.RecordCount
	}
	for _, table := range storage.registry.Tables() {
		if _, exists := result.RecordCounts[table]; !exists {
			result.RecordCounts[table] = 0
		}
	}
	// 金额按账单发生时间统计，退款不计入，使用整数分避免浮点误差。
	totals, err := queries.BillTotals(storage.ctx, sqlgen.BillTotalsParams{AccountID: accountID, FromMs: from, ToMs: to})
	result.BillExpenseCents = totals.ExpenseCents
	result.BillIncomeCents = totals.IncomeCents
	result.BillStoredValueUseCents = totals.StoredValueUseCents
	return result, err
}

func (storage *memoryStore) QueryRecords(accountID string, query domain.RecordQuery) (domain.RecordPage, error) {
	rows, err := storage.Rows(accountID, query.Table)
	if err != nil {
		return domain.RecordPage{}, err
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].ID < rows[j].ID })
	page := domain.RecordPage{Rows: []domain.Row{}}
	for _, row := range rows {
		date := row.UpdatedAtMs
		if query.DateField != "" {
			var fields map[string]json.RawMessage
			_ = json.Unmarshal(row.Snapshot, &fields)
			_ = json.Unmarshal(fields[query.DateField], &date)
		}
		if row.ID <= query.After || query.Search != "" && !strings.Contains(strings.ToLower(string(row.Snapshot)), strings.ToLower(query.Search)) || query.FromMs > 0 && date < query.FromMs || query.ToMs > 0 && date >= query.ToMs {
			continue
		}
		if len(page.Rows) == query.Limit {
			page.More = true
			break
		}
		page.Rows = append(page.Rows, row)
	}
	if page.More {
		page.NextCursor = base64.RawURLEncoding.EncodeToString([]byte(page.Rows[len(page.Rows)-1].ID))
	}
	return page, nil
}
func (storage *memoryStore) Record(accountID, table, id string) (domain.Row, error) {
	rows, err := storage.Rows(accountID, table)
	if err != nil {
		return domain.Row{}, err
	}
	for _, row := range rows {
		if row.ID == id {
			return row, nil
		}
	}
	return domain.Row{}, domain.ErrNotFound
}
func (storage *memoryStore) Statistics(accountID string, from, to int64) (domain.Statistics, error) {
	result := domain.Statistics{RecordCounts: map[string]int64{}}
	for table := range storage.tables {
		rows, err := storage.Rows(accountID, table)
		if err != nil {
			return result, err
		}
		for _, row := range rows {
			if (from == 0 || row.UpdatedAtMs >= from) && (to == 0 || row.UpdatedAtMs < to) {
				result.RecordCounts[table]++
			}
		}
	}
	return result, nil
}
