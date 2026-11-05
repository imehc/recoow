package service

import (
	"context"
	"github.com/imehc/recoow/backend/internal/domain"
	"log/slog"
	"time"
)

// RunMaintenance 清理过期网页登录会话；不删除业务墓碑、同步历史或媒体。
func RunMaintenance(ctx context.Context, store domain.Store, log *slog.Logger) {
	timer := time.NewTicker(time.Minute)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-timer.C:
			if _, err := store.CleanupExpiredSessions(now, 200); err != nil {
				log.Error("会话清理失败", "error", err)
			}
		}
	}
}
