package repository

import (
	"database/sql"
	"sync"
	"testing"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/testdb"
)

func TestRuntimeRLSWithoutAccountContext(t *testing.T) {
	dsn, dir, registry := testdb.Config(t)
	if err := CheckRuntimeRole(dsn); err != nil {
		t.Fatal(err)
	}
	storage, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	defer storage.Close()
	account := mustAccount(t, storage)
	pushRevision(t, storage, account, revisionItem("diary_tags", "private", "private-create", 0, map[string]any{"name": "secret"}), domain.PushApplied)
	database, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	for _, table := range []string{"diary_tags", "row_state", "change_log", "mutation_receipts", "sync_metadata"} {
		var count int
		if err := database.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil || count != 0 {
			t.Fatalf("%s visible without account context: %d %v", table, count, err)
		}
	}
}

func TestStalePasswordCannotIssueSession(t *testing.T) {
	storage := testPostgresStore(t)
	account := mustAccount(t, storage)
	user, err := storage.BindUser(account, "session-owner", "old-hash")
	if err != nil {
		t.Fatal(err)
	}
	device, err := storage.CreateDevice(account, "pending-login", []byte("hidden-token"), true)
	if err != nil {
		t.Fatal(err)
	}
	if err := storage.SetPassword(user.ID, "new-hash"); err != nil {
		t.Fatal(err)
	}
	err = storage.CreateSession(domain.Session{ID: "pending-session", UserID: user.ID, DeviceID: device.ID, TokenHash: "session-hash", PasswordHash: "old-hash", ExpiresAt: time.Now().Add(time.Hour)})
	if err == nil {
		t.Fatal("old password verification must not issue a new session after password change")
	}
}

func TestAccountQuotaAcrossStoreInstances(t *testing.T) {
	dsn, dir, registry := testdb.Config(t)
	first, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	first.SetAccountLimit(1)
	second.SetAccountLimit(1)
	var group sync.WaitGroup
	results := make(chan error, 2)
	for index, storage := range []domain.Store{first, second} {
		group.Add(1)
		go func() { defer group.Done(); _, err := storage.InitAccount([]byte{byte(index)}); results <- err }()
	}
	group.Wait()
	close(results)
	var created, rejected int
	for err := range results {
		if err == nil {
			created++
		} else if err == domain.ErrAccountQuota {
			rejected++
		} else {
			t.Fatal(err)
		}
	}
	if created != 1 || rejected != 1 {
		t.Fatalf("quota allowed concurrent registrations: %d / %d", created, rejected)
	}
}
