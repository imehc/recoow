package schema

import (
	"os"
	"path/filepath"
	"testing"
)

// TestLoadRepoOpenAPI 用仓库真实的 OpenAPI YAML 执行启动契约检查。
// 目录随运行位置解析：本包位于 backend/internal/schema，向上三级即仓库根。
func TestLoadRepoOpenAPI(t *testing.T) {
	dir := filepath.Clean(filepath.Join("..", "..", "..", "openapi", "openapi.yaml"))
	if fi, err := os.Stat(dir); err != nil || fi.IsDir() {
		t.Fatalf("仓库 OpenAPI 不可用：%s", dir)
	}
	reg, err := LoadOpenAPI(dir)
	if err != nil {
		t.Fatalf("真实 OpenAPI 加载失败：%v", err)
	}
	if len(reg.Tables()) == 0 {
		t.Fatal("真实 OpenAPI 未加载到任何实体")
	}
}
