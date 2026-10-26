// SQL 导出渲染：把整库状态输出为可移植的 SQL 脚本（DDL + 数据）。
package store

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"
)

// SQLDumper 是能把某个账号的全部状态导出为 SQL 语句流的存储实现。
// SQLite 与内存实现共用同一份渲染逻辑，保证导出格式稳定。
type SQLDumper interface {
	ExportSQL(w io.Writer, accountID string) error
}

// ddlStatements 描述存储结构（account/devices/row_state/change_log/media_objects）。
// 运行时建表与 SQL 导出共用同一份 DDL，避免两处漂移。
// 所有数据集表以 account_id 作分区键，同一实例可承载多个互不可见的账号。
var ddlStatements = []string{
	`CREATE TABLE IF NOT EXISTS account (
		id                TEXT PRIMARY KEY,
		recovery_key_hash TEXT NOT NULL UNIQUE,
		created_at        TEXT NOT NULL,
		next_version      INTEGER NOT NULL
	)`,
	`CREATE UNIQUE INDEX IF NOT EXISTS idx_account_recovery ON account (recovery_key_hash)`,
	`CREATE TABLE IF NOT EXISTS devices (
		id           TEXT PRIMARY KEY,
		account_id   TEXT NOT NULL,
		name         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		is_owner     INTEGER NOT NULL,
		created_at   TEXT NOT NULL,
		last_seen_at TEXT NOT NULL
	)`,
	`CREATE TABLE IF NOT EXISTS row_state (
		account_id    TEXT NOT NULL,
		table_name    TEXT NOT NULL,
		id            TEXT NOT NULL,
		snapshot      TEXT,
		updated_at_ms INTEGER NOT NULL,
		device_id     TEXT NOT NULL,
		deleted_at_ms INTEGER NOT NULL,
		version       INTEGER NOT NULL,
		data_version  INTEGER NOT NULL DEFAULT 1,
		PRIMARY KEY (account_id, table_name, id)
	)`,
	`CREATE INDEX IF NOT EXISTS idx_row_state_live ON row_state (account_id, table_name, deleted_at_ms)`,
	`CREATE TABLE IF NOT EXISTS change_log (
		account_id    TEXT NOT NULL,
		version       INTEGER NOT NULL,
		table_name    TEXT NOT NULL,
		id            TEXT NOT NULL,
		operation     TEXT NOT NULL,
		updated_at_ms INTEGER NOT NULL,
		device_id     TEXT NOT NULL,
		deleted       INTEGER NOT NULL,
		deleted_at_ms INTEGER NOT NULL,
		snapshot      TEXT,
		data_version  INTEGER NOT NULL DEFAULT 1,
		PRIMARY KEY (account_id, version)
	)`,
	`CREATE TABLE IF NOT EXISTS media_objects (
		account_id   TEXT NOT NULL,
		key          TEXT NOT NULL,
		checksum     TEXT NOT NULL,
		size         INTEGER NOT NULL,
		content_type TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		PRIMARY KEY (account_id, key)
	)`,
	`CREATE INDEX IF NOT EXISTS idx_change_log_entity_version ON change_log (account_id, table_name, id, version)`,
	`CREATE TABLE IF NOT EXISTS sync_metadata (
		account_id TEXT PRIMARY KEY,
		generation TEXT NOT NULL,
		min_cursor INTEGER NOT NULL DEFAULT 0
	)`,
	`CREATE TABLE IF NOT EXISTS mutation_receipts (
		account_id TEXT NOT NULL,
		mutation_id TEXT NOT NULL,
		request_hash TEXT NOT NULL,
		result_json TEXT NOT NULL,
		PRIMARY KEY (account_id, mutation_id)
	)`,
}

// sqlDDL 是 ddlStatements 拼接后的完整文本（每句以分号结束）。
var sqlDDL = strings.Join(ddlStatements, ";\n") + ";\n"

// dumpData 是一次 SQL 导出所需的全部状态，由具体存储实现采集。
type dumpData struct {
	Account  *Account
	Devices  []Device
	Rows     []Row
	Log      []Change
	Media    []MediaObject
	Dataset  *DatasetState
	Receipts []mutationReceipt
}

// writeSQLDump 按稳定顺序（主键/版本）把状态渲染为 SQL：
// 先建表，再逐行 INSERT，整体包在一个事务里，可直接回放重建。
// 只使用 TEXT/INTEGER 等通用类型与标准语法，兼容 SQLite 与 Postgres 系数据库。
func writeSQLDump(w io.Writer, d dumpData) error {
	devices := append([]Device(nil), d.Devices...)
	sort.Slice(devices, func(i, j int) bool { return devices[i].ID < devices[j].ID })
	rows := append([]Row(nil), d.Rows...)
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].Table != rows[j].Table {
			return rows[i].Table < rows[j].Table
		}
		return rows[i].ID < rows[j].ID
	})
	media := append([]MediaObject(nil), d.Media...)
	sort.Slice(media, func(i, j int) bool { return media[i].Key < media[j].Key })
	log := append([]Change(nil), d.Log...)
	sort.Slice(log, func(i, j int) bool { return log[i].Version < log[j].Version })

	if _, err := fmt.Fprintf(w, "-- recoow 云同步数据导出\n-- 生成时间: %s\n-- 结构: account / devices / row_state / change_log / media_objects\n\n",
		time.Now().UTC().Format(time.RFC3339)); err != nil {
		return err
	}
	if _, err := io.WriteString(w, "BEGIN TRANSACTION;\n"); err != nil {
		return err
	}
	if _, err := io.WriteString(w, sqlDDL); err != nil {
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
	for _, r := range rows {
		if _, err := fmt.Fprintf(w, "INSERT INTO row_state (account_id, table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version) VALUES (%s, %s, %s, %s, %d, %s, %d, %d);\n",
			sqlStr(r.AccountID), sqlStr(r.Table), sqlStr(r.ID), sqlSnap(r.Snapshot), r.UpdatedAtMs, sqlStr(r.DeviceID), r.DeletedAtMs, r.Version); err != nil {
			return err
		}
	}
	for _, ch := range log {
		if _, err := fmt.Fprintf(w, "INSERT INTO change_log (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot) VALUES (%s, %d, %s, %s, %s, %d, %s, %d, %d, %s);\n",
			sqlStr(ch.AccountID), ch.Version, sqlStr(ch.Table), sqlStr(ch.ID), sqlStr(string(ch.Operation)), ch.UpdatedAtMs,
			sqlStr(ch.DeviceID), boolInt(ch.Deleted), ch.DeletedAtMs, sqlSnap(ch.Snapshot)); err != nil {
			return err
		}
	}
	for _, m := range media {
		if _, err := fmt.Fprintf(w, "INSERT INTO media_objects (account_id, key, checksum, size, content_type, created_at) VALUES (%s, %s, %s, %d, %s, %s);\n",
			sqlStr(m.AccountID), sqlStr(m.Key), sqlStr(m.Checksum), m.Size, sqlStr(m.ContentType), sqlStr(rfc3339(m.CreatedAt))); err != nil {
			return err
		}
	}

	for _, row := range rows {
		if _, err := fmt.Fprintf(
			w,
			"UPDATE row_state SET data_version = %d WHERE account_id = %s AND table_name = %s AND id = %s;\n",
			max(1, row.DataVersion),
			sqlStr(row.AccountID),
			sqlStr(row.Table),
			sqlStr(row.ID),
		); err != nil {
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

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// rfc3339 输出 UTC RFC3339Nano 文本，供 SQL 存取与导出使用。
func rfc3339(t time.Time) string { return t.UTC().Format(time.RFC3339Nano) }
