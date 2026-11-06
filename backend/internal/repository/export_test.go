package repository

import (
	"bytes"
	"database/sql"
	"net/url"
	"os"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/migrations"
	"github.com/jackc/pgx/v5"
)

func TestPostgresSQLExportRestoresAndCanRestart(t *testing.T) {
	storage := testPostgresStore(t)
	account := mustAccount(t, storage)
	pushRevision(t, storage, account, revisionItem("diary_tags", "export-tag", "export-mutation", 0, map[string]any{"name": "O'Reilly", "future": true}), domain.PushApplied)
	var dump bytes.Buffer
	if err := storage.(domain.SQLDumper).ExportSQL(&dump, account); err != nil {
		t.Fatal(err)
	}
	admin, err := sql.Open("pgx", os.Getenv("RECOOW_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	namespace := "restore_" + newID()
	if _, err := admin.Exec("CREATE SCHEMA " + pgx.Identifier{namespace}.Sanitize()); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec("DROP SCHEMA " + pgx.Identifier{namespace}.Sanitize() + " CASCADE")
	address, err := url.Parse(os.Getenv("RECOOW_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	query := address.Query()
	query.Set("search_path", namespace)
	address.RawQuery = query.Encode()
	target, err := sql.Open("pgx", address.String())
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	if _, err := target.Exec(dump.String()); err != nil {
		t.Fatalf("restore: %v", err)
	}
	if err := migrations.Apply(target); err != nil {
		t.Fatalf("restored database cannot restart: %v", err)
	}
	var snapshot []byte
	if err := target.QueryRow("SELECT snapshot FROM row_state WHERE account_id=$1 AND id='export-tag'", account).Scan(&snapshot); err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(snapshot, []byte(`"future": true`)) || !bytes.Contains(snapshot, []byte("O'Reilly")) {
		t.Fatalf("restored snapshot changed: %s", snapshot)
	}
	var receipts int
	if err := target.QueryRow("SELECT COUNT(*) FROM mutation_receipts").Scan(&receipts); err != nil || receipts != 1 {
		t.Fatalf("receipt restore: %d %v", receipts, err)
	}
	var eventID, generation string
	var accepted int64
	if err := target.QueryRow("SELECT event_id, generation, accepted_at_ms FROM change_events").Scan(&eventID, &generation, &accepted); err != nil {
		t.Fatal(err)
	}
	page, err := storage.ChangeEvents(account, domain.ChangeEventQuery{Limit: 1})
	if err != nil || page.Events[0].EventID != eventID || page.Generation != generation || *page.Events[0].AcceptedAtMs != accepted {
		t.Fatal("export changed event identity or time")
	}
}
