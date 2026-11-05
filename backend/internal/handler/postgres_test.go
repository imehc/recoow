package handler

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository"
	"github.com/imehc/recoow/backend/internal/testdb"
	"testing"
)

func testPostgresStore(t *testing.T) (domain.Store, error) {
	t.Helper()
	dsn, dir, registry := testdb.Config(t)
	storage, err := repository.NewPostgresStore(dsn, dir, registry, false)
	if err == nil {
		t.Cleanup(func() { _ = storage.Close() })
	}
	return storage, err
}
