// Command api 运行 recoow 云同步 + 管理后台后端。
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/imehc/recoow/backend/internal/app"
	"github.com/imehc/recoow/backend/internal/buildinfo"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/handler"
	"github.com/imehc/recoow/backend/internal/repository"
	"github.com/imehc/recoow/backend/internal/service"
)

func main() {
	migrateOnly := flag.Bool("migrate", false, "apply PostgreSQL migrations and exit")
	versionOnly := flag.Bool("version", false, "print executable version and exit")
	flag.Parse()
	if *versionOnly {
		if err := json.NewEncoder(os.Stdout).Encode(buildinfo.Current()); err != nil {
			os.Exit(1)
		}
		return
	}
	cfg := config.Load()
	if *migrateOnly {
		if cfg.MigrationDatabaseURL == "" {
			slog.Error("需要 RECOOW_MIGRATION_DATABASE_URL")
			os.Exit(1)
		}
		if err := repository.Migrate(cfg.MigrationDatabaseURL, cfg.DatabaseURL); err != nil {
			slog.Error("迁移失败", "error", err)
			os.Exit(1)
		}
		return
	}

	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(log)
	build := buildinfo.Current()
	log.Info("recoow 后端版本", "version", build.Version, "commit", build.Commit, "dirty", build.Dirty)

	st, registries, err := app.Storage(cfg)
	if err != nil {
		log.Error("服务初始化失败", "err", err)
		os.Exit(1)
	}
	defer st.Close()
	log.Info("PostgreSQL 存储就绪")

	srv, err := handler.New(cfg, st, registries, log)
	if err != nil {
		log.Error("无法构建 api", "err", err)
		os.Exit(1)
	}

	httpServer := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       5 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       60 * time.Second,
	}

	if count, err := st.CountAccounts(); err == nil && count == 0 {
		log.Info("尚未注册任何账号，客户端可调用 POST /api/accounts 注册")
	} else if err == nil {
		log.Info("已注册账号数", "accounts", count)
	}

	go func() {
		log.Info("recoow 同步后端开始监听", "addr", cfg.ListenAddr)
		var err error
		if cfg.TLSCertFile != "" && cfg.TLSKeyFile != "" {
			err = httpServer.ListenAndServeTLS(cfg.TLSCertFile, cfg.TLSKeyFile)
		} else {
			err = httpServer.ListenAndServe()
		}
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("http server 异常退出", "err", err)
			os.Exit(1)
		}
	}()

	maintenanceContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go service.RunMaintenance(maintenanceContext, st, log)
	<-maintenanceContext.Done()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Error("关闭 http server 失败", "err", err)
	}
	if err := st.Save(); err != nil {
		log.Error("最终落盘失败", "err", err)
	}
	log.Info("已退出")
}
