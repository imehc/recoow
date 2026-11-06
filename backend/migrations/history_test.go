package migrations

import (
	"crypto/sha256"
	"database/sql"
	"fmt"
	"net/url"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	_ "github.com/jackc/pgx/v5/stdlib"
)

func TestHistoryUpgradeDoesNotInventOldAcceptanceTime(t *testing.T) {
	dsn := os.Getenv("RECOOW_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("requires PostgreSQL")
	}
	admin, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	name := fmt.Sprintf("history_migration_%d", time.Now().UnixNano())
	quoted := pgx.Identifier{name}.Sanitize()
	if _, err := admin.Exec("CREATE ROLE " + quoted + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + name + "'"); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec("DROP ROLE " + quoted)
	if _, err := admin.Exec("CREATE SCHEMA " + quoted + " AUTHORIZATION " + quoted); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec("DROP SCHEMA " + quoted + " CASCADE")
	address, _ := url.Parse(dsn)
	address.User = url.UserPassword(name, name)
	query := address.Query()
	query.Set("search_path", name)
	address.RawQuery = query.Encode()
	owner, err := sql.Open("pgx", address.String())
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	initial, err := files.ReadFile("0001_initial.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := owner.Exec(string(initial)); err != nil {
		t.Fatal(err)
	}
	if _, err := owner.Exec("CREATE TABLE schema_migrations (version TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"); err != nil {
		t.Fatal(err)
	}
	if _, err := owner.Exec("INSERT INTO schema_migrations (version,checksum) VALUES ($1,$2)", "0001_initial.sql", fmt.Sprintf("%x", sha256.Sum256(initial))); err != nil {
		t.Fatal(err)
	}
	tx, err := owner.Begin()
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		"INSERT INTO account VALUES ('account','recovery','2026-11-06T00:00:00Z',4)",
		"SELECT set_config('recoow.tenant_id','account',true)",
		"INSERT INTO sync_metadata VALUES ('account','old-generation',0)",
		`INSERT INTO change_log (account_id,version,table_name,id,operation,updated_at_ms,device_id,deleted,deleted_at_ms,snapshot) VALUES
            ('account',1,'diary_tags','tag','update',100,'old-device',0,0,'{"name":"old"}'),
            ('account',2,'diary_tags','tag','delete',200,'old-device',1,200,NULL),
            ('account',3,'diary_tags','tag','update',300,'old-device',0,0,'{"name":"restored"}')`,
		"INSERT INTO devices VALUES ('web','account','Web','hidden',1,'2026-11-06T00:00:00Z','2026-11-06T00:00:00Z')",
		"INSERT INTO users VALUES ('user','account','old-user','hash',false)",
		"INSERT INTO sessions VALUES ('session','user','web','session-token',now()+interval '1 hour')",
	} {
		if _, err := tx.Exec(statement); err != nil {
			tx.Rollback()
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err := Apply(owner); err != nil {
		t.Fatal(err)
	}
	if err := Apply(owner); err != nil {
		t.Fatal("migration not idempotent: ", err)
	}
	tx, err = owner.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	if _, err := tx.Exec("SELECT set_config('recoow.tenant_id','account',true)"); err != nil {
		t.Fatal(err)
	}
	rows, err := tx.Query("SELECT operation, previous_version, modified_at_ms, accepted_at_ms, submission, method FROM change_events ORDER BY version")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	expected := []string{"insert", "delete", "restore"}
	index := 0
	for rows.Next() {
		var operation, method string
		var previous, modified int64
		var accepted sql.NullInt64
		var submission []byte
		if err := rows.Scan(&operation, &previous, &modified, &accepted, &submission, &method); err != nil {
			t.Fatal(err)
		}
		if index >= len(expected) || operation != expected[index] || previous != int64(index) || modified != int64(index+1)*100 || accepted.Valid || len(submission) != 0 || method != "unknown" {
			t.Fatalf("invented legacy metadata at %d", index)
		}
		index++
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if index != 3 {
		t.Fatalf("missing legacy events: %d", index)
	}
	rows.Close()
	var access string
	if err := tx.QueryRow("SELECT access FROM sessions WHERE id='session'").Scan(&access); err != nil || access != "read_only" {
		t.Fatalf("old session not restricted: %s %v", access, err)
	}
}
