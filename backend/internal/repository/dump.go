// SQL 导出渲染：把整库状态输出为可移植的 SQL 脚本（DDL + 数据）。
package repository

import (
	"database/sql/driver"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/migrations"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"
)

// SQLDumper 是能把某个账号的全部状态导出为 SQL 语句流的存储实现。
// PostgreSQL 使用共享的渲染逻辑，保证导出格式稳定。
// ddlStatements 描述存储结构（account/devices/row_state/change_log/media_objects）。
// 运行时建表与 SQL 导出共用同一份 DDL，避免两处漂移。
// 所有数据集表以 account_id 作分区键，同一实例可承载多个互不可见的账号。
// dumpData 是一次 SQL 导出所需的全部状态，由具体存储实现采集。
type dumpData struct {
	Registry *schema.Registry
	Account  *domain.Account
	Devices  []domain.Device
	Rows     []domain.Row
	Log      []domain.Change
	Media    []domain.MediaObject
	Dataset  *domain.DatasetState
	Receipts []domain.MutationReceipt
	Events   []domain.ChangeEvent
}

// writeSQLDump 按稳定顺序（主键/版本）把状态渲染为 SQL：
// 先建表，再逐行 INSERT，整体包在一个事务里，可直接回放重建。
// 使用 PostgreSQL 结构化表、JSONB、数组与行级隔离策略。
func writeSQLDump(w io.Writer, d dumpData) error {
	devices := append([]domain.Device(nil), d.Devices...)
	sort.Slice(devices, func(i, j int) bool { return devices[i].ID < devices[j].ID })
	rows := append([]domain.Row(nil), d.Rows...)
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].Table != rows[j].Table {
			return rows[i].Table < rows[j].Table
		}
		return rows[i].ID < rows[j].ID
	})
	media := append([]domain.MediaObject(nil), d.Media...)
	sort.Slice(media, func(i, j int) bool { return media[i].Key < media[j].Key })
	log := append([]domain.Change(nil), d.Log...)
	sort.Slice(log, func(i, j int) bool { return log[i].Version < log[j].Version })

	if _, err := fmt.Fprintf(w, "-- recoow 云同步数据导出\n-- 生成时间: %s\n-- 结构: account / devices / row_state / change_log / media_objects\n\n",
		time.Now().UTC().Format(time.RFC3339)); err != nil {
		return err
	}
	if _, err := io.WriteString(w, "BEGIN TRANSACTION;\n"); err != nil {
		return err
	}
	if _, err := io.WriteString(w, migrations.ExportSQL()); err != nil {
		return err
	}

	if a := d.Account; a != nil {
		if _, err := fmt.Fprintf(w, "INSERT INTO account (id, recovery_key_hash, created_at, next_version) VALUES (%s, %s, %s, %d);\n",
			sqlStr(a.ID), sqlStr(hex.EncodeToString(a.RecoveryKeyHash)), sqlStr(rfc3339(a.CreatedAt)), a.NextVersion); err != nil {
			return err
		}
	}
	for _, dv := range devices {
		if _, err := fmt.Fprintf(w, "INSERT INTO devices (id, account_id, name, token_hash, is_owner, created_at, last_seen_at) VALUES (%s, %s, %s, %s, %d, %s, %s);\n",
			sqlStr(dv.ID), sqlStr(dv.AccountID), sqlStr(dv.Name), sqlStr(hex.EncodeToString(dv.TokenHash)),
			boolInt(dv.IsOwner), sqlStr(rfc3339(dv.CreatedAt)), sqlStr(rfc3339(dv.LastSeenAt))); err != nil {
			return err
		}
	}
	if d.Account != nil {
		if _, err := fmt.Fprintf(w, "SELECT set_config('recoow.tenant_id', %s, true);\n", sqlStr(d.Account.ID)); err != nil {
			return err
		}
	}
	for _, row := range rows {
		storage := &postgresStore{registry: d.Registry}
		query, values, err := storage.businessStatement(row)
		if err != nil {
			return err
		}
		parts := strings.Split(query, "?")
		var statement strings.Builder
		for index, part := range parts {
			statement.WriteString(part)
			if index < len(values) {
				literal, err := sqlValue(values[index])
				if err != nil {
					return err
				}
				statement.WriteString(literal)
			}
		}
		if _, err := fmt.Fprintln(w, statement.String()+";"); err != nil {
			return err
		}
	}

	for _, ch := range log {
		if _, err := fmt.Fprintf(w, "INSERT INTO change_log (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version) VALUES (%s, %d, %s, %s, %s, %d, %s, %d, %d, %s, %d);\n",
			sqlStr(ch.AccountID), ch.Version, sqlStr(ch.Table), sqlStr(ch.ID), sqlStr(string(ch.Operation)), ch.UpdatedAtMs,
			sqlStr(ch.DeviceID), boolInt(ch.Deleted), ch.DeletedAtMs, sqlSnap(ch.Snapshot), max(1, ch.DataVersion)); err != nil {
			return err
		}
	}
	for _, m := range media {
		if _, err := fmt.Fprintf(w, "INSERT INTO media_objects (account_id, key, checksum, size, content_type, created_at) VALUES (%s, %s, %s, %d, %s, %s);\n",
			sqlStr(m.AccountID), sqlStr(m.Key), sqlStr(m.Checksum), m.Size, sqlStr(m.ContentType), sqlStr(rfc3339(m.CreatedAt))); err != nil {
			return err
		}
	}

	for _, change := range log {
		if _, err := fmt.Fprintf(w, "UPDATE change_log SET data_version = %d WHERE account_id = %s AND version = %d;\n",
			max(1, change.DataVersion), sqlStr(change.AccountID), change.Version); err != nil {
			return err
		}
	}
	if d.Dataset != nil && d.Account != nil {
		if _, err := fmt.Fprintf(w, "INSERT INTO sync_metadata (account_id, generation, min_cursor) VALUES (%s, %s, %d) ON CONFLICT (account_id) DO UPDATE SET generation = excluded.generation, min_cursor = excluded.min_cursor;\n",
			sqlStr(d.Account.ID), sqlStr(d.Dataset.Generation), d.Dataset.MinCursor); err != nil {
			return err
		}
	}
	sort.Slice(d.Events, func(i, j int) bool { return d.Events[i].Version < d.Events[j].Version })
	for _, event := range d.Events {
		values, err := eventValues(event)
		if err != nil {
			return err
		}
		literals := make([]string, len(values))
		for index, value := range values {
			literal, err := sqlValue(value)
			if err != nil {
				return err
			}
			literals[index] = literal
		}
		if _, err := fmt.Fprintf(w, "INSERT INTO change_events (%s) VALUES (%s);\n", eventColumns, strings.Join(literals, ",")); err != nil {
			return err
		}
	}
	sort.Slice(d.Receipts, func(first, second int) bool { return d.Receipts[first].MutationID < d.Receipts[second].MutationID })
	for _, receipt := range d.Receipts {
		result, err := json.Marshal(receipt.Result)
		if err != nil {
			return err
		}
		if _, err := fmt.Fprintf(
			w,
			"INSERT INTO mutation_receipts (account_id, mutation_id, request_hash, result_json) VALUES (%s, %s, %s, %s);\n",
			sqlStr(receipt.AccountID),
			sqlStr(receipt.MutationID),
			sqlStr(receipt.RequestHash),
			sqlStr(string(result)),
		); err != nil {
			return err
		}
	}
	_, err := io.WriteString(w, "COMMIT;\n")
	return err
}

// sqlStr 对 SQL 字符串字面量做转义（单引号翻倍）。
func sqlStr(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// sqlSnap 把可选的行快照渲染为 SQL 字面量或 NULL。
func sqlSnap(s []byte) string {
	if len(s) == 0 {
		return "NULL"
	}
	return sqlStr(string(s))
}

func sqlValue(value any) (string, error) {
	if valuer, ok := value.(driver.Valuer); ok {
		converted, err := valuer.Value()
		if err != nil {
			return "", err
		}
		return sqlValue(converted)
	}
	switch typed := value.(type) {
	case *string:
		if typed == nil {
			return "NULL", nil
		}
		return sqlStr(*typed), nil
	case *int64:
		if typed == nil {
			return "NULL", nil
		}
		return strconv.FormatInt(*typed, 10), nil
	case nil:
		return "NULL", nil
	case []string:
		if typed == nil {
			return "NULL", nil
		}
		parts := make([]string, len(typed))
		for index, item := range typed {
			parts[index] = sqlStr(item)
		}
		return "ARRAY[" + strings.Join(parts, ",") + "]::text[]", nil
	case string:
		return sqlStr(typed), nil
	case int64:
		return strconv.FormatInt(typed, 10), nil
	case int:
		return strconv.Itoa(typed), nil
	case float64:
		return strconv.FormatFloat(typed, 'g', -1, 64), nil
	case bool:
		return strconv.FormatBool(typed), nil
	default:
		return "", fmt.Errorf("unsupported SQL export value %T", value)
	}
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
