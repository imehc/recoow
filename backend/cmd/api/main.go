// Command api 运行 recoow 云同步 + 管理后台后端。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/imehc/recoow/backend/internal/api"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/store"
)

func main() {
	cfg := config.Load()

	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(log)

	// 读取并自检三端共用的字段清单（唯一权威）。
	registries, err := schema.Load(cfg.SchemaDir)
	if err != nil {
		log.Error("字段清单加载失败", "err", err)
		os.Exit(1)
	}
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		log.Error("无法创建数据目录", "err", err)
		os.Exit(1)
	}

	st, err := store.NewSQLiteStore(cfg.DataDir)
	if err != nil {
		log.Error("无法打开存储", "err", err)
		os.Exit(1)
	}
	defer st.Close()
	log.Info("SQLite 存储就绪", "db", filepath.Join(cfg.DataDir, "recoow.db"))

	srv, err := api.New(cfg, st, registries, log)
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

	if _, err := st.GetAccount(); errors.Is(err, store.ErrNoAccount) {
		log.Info("尚未初始化账号，请调用 POST /api/account/init 完成引导")
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

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

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
