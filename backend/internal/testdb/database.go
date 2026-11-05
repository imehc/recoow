// Package testdb 为测试创建隔离 PostgreSQL schema，不修改其他数据库的数据。
package testdb

import (
	"database/sql"
	"fmt"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/migrations"
	"github.com/jackc/pgx/v5"
	_ "github.com/jackc/pgx/v5/stdlib"
	"net/url"
	"os"
	"testing"
	"time"
)

func Config(t *testing.T) (string, string, *schema.Registry) {
	t.Helper()
	dsn := os.Getenv("RECOOW_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("需要 RECOOW_TEST_DATABASE_URL 的 PostgreSQL 集成测试")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	name := fmt.Sprintf("test_%d", time.Now().UnixNano())
	if _, err := db.Exec("CREATE SCHEMA " + pgx.Identifier{name}.Sanitize()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = db.Exec("DROP SCHEMA " + pgx.Identifier{name}.Sanitize() + " CASCADE")
		_, _ = db.Exec("DROP ROLE IF EXISTS " + pgx.Identifier{name}.Sanitize())
		_ = db.Close()
	})
	address, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	query := address.Query()
	query.Set("search_path", name)
	address.RawQuery = query.Encode()
	owner, err := sql.Open("pgx", address.String())
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	if err := migrations.Apply(owner); err != nil {
		t.Fatal(err)
	}
	// 每个测试用独立的非表所有者角色执行；超级用户只负责建库与清理。
	role := pgx.Identifier{name}.Sanitize()
	if _, err := db.Exec("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + name + "'"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("GRANT USAGE ON SCHEMA " + pgx.Identifier{name}.Sanitize() + " TO " + role); err != nil {
		t.Fatal(err)
	}
	if _, err := owner.Exec("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA " + pgx.Identifier{name}.Sanitize() + " TO " + role); err != nil {
		t.Fatal(err)
	}
	address.User = url.UserPassword(name, name)
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	return address.String(), t.TempDir(), registry
}
