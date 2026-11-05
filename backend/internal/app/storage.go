// Package app 装配入口依赖，HTTP 和 worker 共享配置与仓储初始化。
package app

import (
	"fmt"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository"
	"github.com/imehc/recoow/backend/internal/schema"
)

func Storage(cfg config.Config) (domain.Store, *schema.Registry, error) {
	contracts, err := schema.LoadOpenAPI(cfg.OpenAPIFile)
	if err != nil {
		return nil, nil, err
	}
	if cfg.DatabaseURL == "" {
		return nil, nil, fmt.Errorf("需要 RECOOW_DATABASE_URL")
	}
	if cfg.MigrationDatabaseURL != "" {
		if err := repository.Migrate(cfg.MigrationDatabaseURL, cfg.DatabaseURL); err != nil {
			return nil, nil, err
		}
	}
	if err := repository.CheckRuntimeRole(cfg.DatabaseURL); err != nil {
		return nil, nil, err
	}
	store, err := repository.NewPostgresStore(cfg.DatabaseURL, cfg.DataDir, contracts, false)
	return store, contracts, err
}
