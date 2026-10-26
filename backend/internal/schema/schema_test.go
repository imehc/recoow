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

func TestLoadFromDir(t *testing.T) {
	dir := t.TempDir()
	write := func(name, content string) {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("a.json", `{"table":"bills","manifestVersion":1,"columns":[{"name":"id","type":"text","format":"text"}]}`)
	write("b.json", `{"table":"reminders","manifestVersion":1,"columns":[{"name":"id","type":"text","format":"text"}]}`)
	write("._a.json", "\x00\x05\x16\x07Mac OS X")
	reg, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := reg.Tables()
	if len(got) != 2 || got[0] != "bills" || got[1] != "reminders" {
		t.Fatalf("表清单排序错误: %v", got)
	}
}

func TestLoadRejectsInvalidManifest(t *testing.T) {
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, "bills.json"), []byte("invalid JSON"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(directory); err == nil {
		t.Fatal("invalid manifest must still fail")
	}
}
