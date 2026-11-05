package repository

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/testdb"
	"testing"
)

func testPostgresStore(t *testing.T) domain.Store {
	t.Helper()
	dsn, dir, registry := testdb.Config(t)
	storage, err := NewPostgresStore(dsn, dir, registry, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = storage.Close() })
	return storage
}
