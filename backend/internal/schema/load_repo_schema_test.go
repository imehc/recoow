package schema

import (
	"os"
	"path/filepath"
	"testing"
)

// TestLoadRepoSchema 用仓库真实的 schema/ 目录跑一遍 Load，等价于后端启动时的自检。
// 目录随运行位置解析：本包位于 backend/internal/schema，向上三级即仓库根。
func TestLoadRepoSchema(t *testing.T) {
	dir := filepath.Clean(filepath.Join("..", "..", "..", "schema"))
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		t.Skipf("仓库 schema 目录不可用，跳过：%s", dir)
	}
	reg, err := Load(dir)
	if err != nil {
		t.Fatalf("真实 schema 加载失败：%v", err)
	}
	if len(reg.Tables()) == 0 {
		t.Fatal("真实 schema 未加载到任何实体")
	}
}
