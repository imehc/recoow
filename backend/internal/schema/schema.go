// 包 schema 负责加载并校验三端共用的字段清单（唯一权威）。
//
// 每个同步实体的字段清单是一个独立 JSON 文件，位于 recoow/schema/<table>.json。
// 行快照字段名沿用 iOS 的 CodingKeys（= GRDB 列名），全网统一 snake_case。
// 后端启动时读取这些清单并自检；GET /api/schema 将清单下发给前端。
package schema

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// 允许的字段 type 取值。
var allowedTypes = map[string]bool{
	"text": true, "int": true, "real": true, "bool": true, "blob": true,
}

// 允许的字段 format 取值（前端据此选择渲染控件）。
var allowedFormats = map[string]bool{
	"text": true, "multiline": true, "int": true, "real": true, "bool": true,
	"ts_ms": true, "amount_cents": true, "duration_s": true, "enum": true,
	"media_ref": true, "media_ids": true, "json": true, "binary": true,
}

// Localized 表示同一文案的中英两套文本。
type Localized struct {
	Zh string `json:"zh"`
	En string `json:"en"`
}

// Column 描述实体的一列字段。name 为 snake_case 列名，与 iOS 快照一致。
type Column struct {
	Name       string          `json:"name"`
	Type       string          `json:"type"`
	Format     string          `json:"format"`
	Required   bool            `json:"required,omitempty"`
	Nullable   bool            `json:"nullable,omitempty"`
	System     bool            `json:"system,omitempty"`     // 保留的系统同步列，界面隐藏
	Deprecated bool            `json:"deprecated,omitempty"` // 已弃用，界面隐藏但保留读取
	Binary     bool            `json:"binary,omitempty"`     // 二进制媒体，不进入行快照
	Searchable bool            `json:"searchable,omitempty"`
	Listable   bool            `json:"listable,omitempty"`
	Sortable   bool            `json:"sortable,omitempty"`
	Editable   bool            `json:"editable,omitempty"`
	Group      string          `json:"group,omitempty"`
	Unit       string          `json:"unit,omitempty"`
	EnumValues []string        `json:"enumValues,omitempty"`
	Labels     Localized       `json:"labels"`
	Derived    bool            `json:"derived,omitempty"`
	Default    json.RawMessage `json:"default,omitempty"`
}

// Manifest 描述一个同步实体在云端镜像里的字段集合。
type Manifest struct {
	Table           string       `json:"table"`
	ManifestVersion int          `json:"manifestVersion"`
	Summary         Localized    `json:"summary,omitempty"`
	Columns         []Column     `json:"columns"`
	DataVersion     int          `json:"dataVersion,omitempty"`
	MinDataVersion  int          `json:"minDataVersion,omitempty"`
	Dependencies    []Dependency `json:"dependencies,omitempty"`
}

type Dependency struct {
	Field string `json:"field"`
	Table string `json:"table"`
	Array bool   `json:"array,omitempty"`
}

// Registry 持有一组已加载并校验过的字段清单。
type Registry struct {
	manifests map[string]Manifest
	version   string // 全量清单版本号（排序后取哈希），用于缓存 ETag
}

// Load 读取目录下所有 *.json 清单并校验；任一份不合法即整体失败。
func Load(dir string) (*Registry, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var files []string
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		files = append(files, filepath.Join(dir, e.Name()))
	}
	sort.Strings(files)
	list := make([]Manifest, 0, len(files))
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		var m Manifest
		if err := json.Unmarshal(data, &m); err != nil {
			return nil, fmt.Errorf("schema %s: %w", f, err)
		}
		list = append(list, m)
	}
	return NewFromManifests(list)
}

// NewFromManifests 从内存中的清单构造并校验注册表（也用于测试）。
func NewFromManifests(list []Manifest) (*Registry, error) {
	reg := &Registry{manifests: map[string]Manifest{}}
	for _, m := range list {
		if err := validateManifest(m); err != nil {
			return nil, fmt.Errorf("schema %s: %w", m.Table, err)
		}
		if _, dup := reg.manifests[m.Table]; dup {
			return nil, fmt.Errorf("schema: 重复的实体 %q", m.Table)
		}
		reg.manifests[m.Table] = m
	}
	reg.version = computeVersion(list)
	for _, manifest := range list {
		for _, dependency := range manifest.Dependencies {
			if !reg.Known(dependency.Table) || dependency.Field == "" {
				return nil, fmt.Errorf("invalid dependency in %s", manifest.Table)
			}
		}
	}
	return reg, nil
}

// validateManifest 校验单份清单的基本合法性（字段名/类型/格式）。
func validateManifest(m Manifest) error {
	if m.Table == "" {
		return fmt.Errorf("缺少 table 字段")
	}
	if !identifierPattern.MatchString(m.Table) {
		return fmt.Errorf("invalid table identifier")
	}
	if m.DataVersion < 0 || m.MinDataVersion < 0 || m.MinDataVersion > m.DataVersion {
		return fmt.Errorf("invalid data version range")
	}
	if m.ManifestVersion < 1 {
		return fmt.Errorf("manifestVersion 必须 >= 1")
	}
	seen := map[string]bool{}
	for _, c := range m.Columns {
		if c.Name == "" {
			return fmt.Errorf("存在未命名字段")
		}
		if !identifierPattern.MatchString(c.Name) {
			return fmt.Errorf("invalid column identifier")
		}
		if seen[c.Name] {
			return fmt.Errorf("字段 %s 重复", c.Name)
		}
		seen[c.Name] = true
		if !allowedTypes[c.Type] {
			return fmt.Errorf("字段 %s 类型 %q 不合法", c.Name, c.Type)
		}
		if !allowedFormats[c.Format] {
			return fmt.Errorf("字段 %s format %q 不合法", c.Name, c.Format)
		}
		if c.Format == "enum" && len(c.EnumValues) == 0 {
			return fmt.Errorf("字段 %s 为 enum 但缺少 enumValues", c.Name)
		}
		if c.Binary && c.Format != "binary" {
			return fmt.Errorf("字段 %s 声明 binary 但 format 非 binary", c.Name)
		}
	}
	// 每个实体都必须声明主键 id。
	if !seen["id"] {
		return fmt.Errorf("实体 %s 缺少 id 主键字段", m.Table)
	}
	return nil
}

// computeVersion 计算全量清单版本号：对排序后的清单做稳定序列化再取摘要。
func computeVersion(list []Manifest) string {
	sorted := append([]Manifest(nil), list...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Table < sorted[j].Table })
	h := sha256.New()
	for _, m := range sorted {
		data, _ := json.Marshal(m)
		h.Write(data)
	}
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// Version 返回全量清单版本号，供客户端缓存使用。
func (r *Registry) Version() string { return r.version }

// Known 判断某表是否在清单内。
func (r *Registry) Known(table string) bool {
	_, ok := r.manifests[table]
	return ok
}

// Get 返回某实体的清单。
func (r *Registry) Get(table string) (Manifest, bool) {
	m, ok := r.manifests[table]
	return m, ok
}

// Tables 返回排序后的实体名列表。
func (r *Registry) Tables() []string {
	out := make([]string, 0, len(r.manifests))
	for t := range r.manifests {
		out = append(out, t)
	}
	sort.Strings(out)
	return out
}

// Manifests 返回所有清单，供 GET /api/schema 整体下发。
func (r *Registry) Manifests() map[string]Manifest { return r.manifests }
