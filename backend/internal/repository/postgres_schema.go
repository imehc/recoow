package repository

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"maps"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/migrations"
	"github.com/jackc/pgx/v5"
)

// NewPostgresStore 只使用 PostgreSQL；当前业务状态保存到各实体的结构化表。
func NewPostgresStore(dsn, dir string, registry *schema.Registry, runMigrations ...bool) (domain.Store, error) {
	if dsn == "" {
		return nil, fmt.Errorf("需要配置 RECOOW_DATABASE_URL")
	}
	if registry == nil {
		return nil, fmt.Errorf("缺少 OpenAPI 契约")
	}
	database, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, err
	}
	database.SetMaxOpenConns(8)
	database.SetMaxIdleConns(4)
	storage := &postgresStore{ctx: context.Background(), db: &storeDB{database}, mediaDir: filepath.Join(dir, "media"), tables: maps.Clone(domain.SyncableTables), registry: registry}
	if err := os.MkdirAll(storage.mediaDir, 0o700); err != nil {
		_ = database.Close()
		return nil, err
	}
	if len(runMigrations) == 0 || runMigrations[0] {
		if err := storage.initialize(); err != nil {
			_ = database.Close()
			return nil, err
		}
	} else if err := migrations.Check(database); err != nil {
		_ = database.Close()
		return nil, err
	}
	if err := database.Ping(); err != nil {
		_ = database.Close()
		return nil, err
	}
	return storage, nil
}

func businessColumns(manifest schema.Manifest) []schema.Column {
	columns := []schema.Column{}
	for _, column := range manifest.Columns {
		if !column.Binary && column.Name != "server_version" {
			columns = append(columns, column)
		}
	}
	sort.Slice(columns, func(i, j int) bool { return columns[i].Name < columns[j].Name })
	return columns
}

func (storage *postgresStore) initialize() error { return migrations.Apply(storage.db.DB) }

func (storage *postgresStore) businessStatement(row domain.Row) (string, []any, error) {
	manifest, ok := storage.registry.Get(row.Table)
	if !ok {
		return "", nil, domain.ErrNotFound
	}
	snapshot := map[string]json.RawMessage{}
	if len(row.Snapshot) > 0 {
		if err := json.Unmarshal(row.Snapshot, &snapshot); err != nil {
			return "", nil, err
		}
	}
	extra := maps.Clone(snapshot)
	var present []string
	if len(row.Snapshot) > 0 {
		present = []string{}
	}
	names := []string{"tenant_id", "id", "extras", "present_fields", "server_updated_at_ms", "sync_device_id", "tombstone_at_ms", "server_version", "data_version"}
	values := []any{row.AccountID, row.ID, nil, nil, row.UpdatedAtMs, row.DeviceID, row.DeletedAtMs, row.Version, max(1, row.DataVersion)}
	for _, column := range businessColumns(manifest) {
		raw, exists := snapshot[column.Name]
		if exists {
			present = append(present, column.Name)
		}
		delete(extra, column.Name)
		if column.Name == "id" {
			continue
		}
		names = append(names, column.Name)
		var value any
		if exists && string(raw) != "null" {
			switch column.Type {
			case "text":
				var typed string
				if err := json.Unmarshal(raw, &typed); err != nil {
					return "", nil, err
				}
				value = typed
			case "int":
				var typed int64
				if err := json.Unmarshal(raw, &typed); err != nil {
					return "", nil, err
				}
				value = typed
			case "real":
				var typed float64
				if err := json.Unmarshal(raw, &typed); err != nil {
					return "", nil, err
				}
				value = typed
			case "bool":
				var typed bool
				if err := json.Unmarshal(raw, &typed); err != nil {
					return "", nil, err
				}
				value = typed
			}
		}
		values = append(values, value)
	}
	encoded, err := json.Marshal(extra)
	if err != nil {
		return "", nil, err
	}
	values[2] = string(encoded)
	values[3] = present
	placeholders := make([]string, len(names))
	updates := []string{}
	for index, name := range names {
		placeholders[index] = "?"
		names[index] = pgx.Identifier{name}.Sanitize()
		if index > 1 {
			updates = append(updates, names[index]+" = excluded."+names[index])
		}
	}
	query := "INSERT INTO " + pgx.Identifier{row.Table}.Sanitize() + " (" + strings.Join(names, ",") + ") VALUES (" + strings.Join(placeholders, ",") + ") ON CONFLICT (tenant_id,id) DO UPDATE SET " + strings.Join(updates, ",")
	return query, values, nil
}

func (storage *postgresStore) upsertBusinessRow(transaction *storeTx, row domain.Row) error {
	query, values, err := storage.businessStatement(row)
	if err != nil {
		return err
	}
	_, err = transaction.Exec(query, values...)
	return err
}
