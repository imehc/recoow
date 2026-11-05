package schema

import (
	"encoding/json"
	"fmt"
	"os"
	"sigs.k8s.io/yaml"
)

// LoadOpenAPI 从唯一接口契约派生同步校验元数据。
func LoadOpenAPI(path string) (*Registry, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var doc struct {
		OpenAPI    string `json:"openapi"`
		Components struct {
			Schemas map[string]struct {
				Sync       *Manifest `json:"x-recoow-sync"`
				Required   []string  `json:"required"`
				Properties map[string]struct {
					Type     string          `json:"type"`
					Nullable bool            `json:"nullable"`
					Enum     []string        `json:"enum"`
					Default  json.RawMessage `json:"default"`
					Metadata Column          `json:"x-recoow"`
				} `json:"properties"`
			} `json:"schemas"`
		} `json:"components"`
	}
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	if doc.OpenAPI != "3.0.3" {
		return nil, fmt.Errorf("unsupported OpenAPI version %q", doc.OpenAPI)
	}
	list := []Manifest{}
	for name, model := range doc.Components.Schemas {
		if model.Sync == nil {
			continue
		}
		manifest := *model.Sync
		required := map[string]bool{}
		for _, field := range model.Required {
			required[field] = true
		}
		// 原清单以列声明顺序为准；契约哈希在两种入口统一排序。
		for field, definition := range model.Properties {
			column := definition.Metadata
			column.Name = field
			column.Type = map[string]string{"string": "text", "integer": "int", "number": "real", "boolean": "bool"}[definition.Type]
			if column.Binary {
				column.Type = "blob"
			}
			if column.Type == "" {
				return nil, fmt.Errorf("unsupported field %s.%s", name, field)
			}
			if column.Format == "" {
				column.Format = column.Type
			}
			column.Required = required[field]
			column.Nullable = definition.Nullable
			column.EnumValues = definition.Enum
			column.Default = definition.Default
			manifest.Columns = append(manifest.Columns, column)
		}
		list = append(list, manifest)
	}
	if len(list) == 0 {
		return nil, fmt.Errorf("OpenAPI 缺少同步实体")
	}
	registry, err := NewFromManifests(list)
	if err == nil {
		registry.openAPI = data
	}
	return registry, err
}

func (registry *Registry) OpenAPI() []byte { return registry.openAPI }
