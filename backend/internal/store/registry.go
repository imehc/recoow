package store

import (
	"fmt"
	"regexp"
)

var tableIdentifier = regexp.MustCompile(`^[a-z][a-z0-9_]*$`)

func configuredTables(tables []string) (map[string]bool, error) {
	result := make(map[string]bool, len(tables))
	for _, table := range tables {
		if !tableIdentifier.MatchString(table) {
			return nil, fmt.Errorf("invalid sync entity %q", table)
		}
		result[table] = true
	}
	return result, nil
}

func (storage *memoryStore) ConfigureSyncTables(tables []string) error {
	configured, err := configuredTables(tables)
	if err != nil {
		return err
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	storage.tables = configured
	return nil
}

func (storage *sqliteStore) ConfigureSyncTables(tables []string) error {
	configured, err := configuredTables(tables)
	if err != nil {
		return err
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	storage.tables = configured
	return nil
}

func (adapter memoryRevision) supportsTable(table string) bool {
	return adapter.store.tables[table]
}

func (adapter sqliteRevision) supportsTable(table string) bool {
	return adapter.store.tables[table]
}
