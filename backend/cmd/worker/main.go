package main

import (
	"context"
	"github.com/imehc/recoow/backend/internal/app"
	"github.com/imehc/recoow/backend/internal/config"
	"github.com/imehc/recoow/backend/internal/service"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	store, _, err := app.Storage(config.Load())
	if err != nil {
		log.Error("worker 初始化失败", "error", err)
		os.Exit(1)
	}
	defer store.Close()
	service.RunMaintenance(ctx, store, log)
}
