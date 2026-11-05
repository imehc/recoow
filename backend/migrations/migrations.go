// Package migrations 执行已发布的 PostgreSQL SQL 迁移，迁移文件不可原地修改。
package migrations

import (
	"crypto/sha256"
	"database/sql"
	"embed"
	"fmt"
	"sort"
	"strings"
)

//go:embed *.sql
var files embed.FS

// Check 只检查迁移版本，不让普通运行账号执行 DDL，也不带着未完成的结构启动。
func Check(db *sql.DB) error {
	entries, err := files.ReadDir(".")
	if err != nil {
		return err
	}
	for _, entry := range entries {
		data, err := files.ReadFile(entry.Name())
		if err != nil {
			return err
		}
		var checksum string
		if err := db.QueryRow("SELECT checksum FROM schema_migrations WHERE version=$1", entry.Name()).Scan(&checksum); err != nil {
			return fmt.Errorf("迁移 %s 未完成：%w", entry.Name(), err)
		}
		if checksum != fmt.Sprintf("%x", sha256.Sum256(data)) {
			return fmt.Errorf("迁移 %s 校验和不一致", entry.Name())
		}
	}
	return nil
}

// ExportSQL 同时导出 DDL 和迁移校验和，恢复后启动不会重复添加约束或策略。
func ExportSQL() string {
	var result strings.Builder
	result.WriteString("CREATE TABLE schema_migrations (version TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now());\n")
	entries, _ := files.ReadDir(".")
	for _, entry := range entries {
		data, _ := files.ReadFile(entry.Name())
		result.Write(data)
		fmt.Fprintf(&result, "\nINSERT INTO schema_migrations (version,checksum) VALUES ('%s','%x');\n", entry.Name(), sha256.Sum256(data))
	}
	return result.String()
}

func Apply(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// 事务锁阻止多个服务实例同时升级；任何失败都会回滚整个迁移。
	if _, err := tx.Exec("SELECT pg_advisory_xact_lock(72394108)"); err != nil {
		return err
	}
	if _, err := tx.Exec("CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"); err != nil {
		return err
	}
	entries, err := files.ReadDir(".")
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		data, err := files.ReadFile(entry.Name())
		if err != nil {
			return err
		}
		checksum := fmt.Sprintf("%x", sha256.Sum256(data))
		var previous string
		err = tx.QueryRow("SELECT checksum FROM schema_migrations WHERE version = $1", entry.Name()).Scan(&previous)
		if err == nil {
			if previous != checksum {
				return fmt.Errorf("迁移 %s 的校验和已改变", entry.Name())
			}
			continue
		}
		if err != sql.ErrNoRows {
			return err
		}
		if _, err = tx.Exec(string(data)); err != nil {
			return fmt.Errorf("执行迁移 %s：%w", entry.Name(), err)
		}
		if _, err = tx.Exec("INSERT INTO schema_migrations (version,checksum) VALUES ($1,$2)", entry.Name(), checksum); err != nil {
			return err
		}
	}
	return tx.Commit()
}
