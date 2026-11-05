package schema

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNewFromManifestsValidates(t *testing.T) {
	// 缺少 id 主键应报错。
	if _, err := NewFromManifests([]Manifest{
		{Table: "x", ManifestVersion: 1, Columns: []Column{{Name: "a", Type: "text", Format: "text"}}},
	}); err == nil {
		t.Fatal("缺少 id 应报错")
	}
	// 重复表名应报错。
	if _, err := NewFromManifests([]Manifest{
		{Table: "x", ManifestVersion: 1, Columns: []Column{{Name: "id", Type: "text", Format: "text"}}},
		{Table: "x", ManifestVersion: 1, Columns: []Column{{Name: "id", Type: "text", Format: "text"}}},
	}); err == nil {
		t.Fatal("重复表应报错")
	}
	// enum 缺 enumValues 应报错。
	if _, err := NewFromManifests([]Manifest{
		{Table: "x", ManifestVersion: 1, Columns: []Column{
			{Name: "id", Type: "text", Format: "text"},
			{Name: "kind", Type: "text", Format: "enum"},
		}},
	}); err == nil {
		t.Fatal("enum 缺取值应报错")
	}
	// 合法清单应通过，且版本稳定、可查询。
	reg, err := NewFromManifests([]Manifest{
		{Table: "bills", ManifestVersion: 1, Columns: []Column{{Name: "id", Type: "text", Format: "text"}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !reg.Known("bills") || reg.Known("nope") {
		t.Fatal("Known 查询结果错误")
	}
	if reg.Version() == "" {
		t.Fatal("版本号不应为空")
	}
}

func TestLoadRejectsInvalidOpenAPI(t *testing.T) {
	path := filepath.Join(t.TempDir(), "openapi.yaml")
	if err := os.WriteFile(path, []byte("openapi: 3.1.0\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadOpenAPI(path); err == nil {
		t.Fatal("unsupported contract must fail")
	}
}
