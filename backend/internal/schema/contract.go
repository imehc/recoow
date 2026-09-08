package schema

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"slices"
)

var identifierPattern = regexp.MustCompile(`^[a-z][a-z0-9_]*$`)

func (registry *Registry) Contracts() map[string]int {
	contracts := map[string]int{}
	for table, manifest := range registry.manifests {
		contracts[table] = max(1, manifest.DataVersion)
	}
	return contracts
}

func (registry *Registry) Normalize(table string, version int, snapshot json.RawMessage) (json.RawMessage, error) {
	manifest, exists := registry.Get(table)
	if !exists {
		return nil, fmt.Errorf("unsupported table %s", table)
	}
	if version < max(1, manifest.MinDataVersion) || version > max(1, manifest.DataVersion) {
		return nil, fmt.Errorf("unsupported dataVersion %d for %s", version, table)
	}
	object := map[string]json.RawMessage{}
	if err := json.Unmarshal(snapshot, &object); err != nil || object == nil {
		return nil, fmt.Errorf("snapshot must be an object")
	}
	for _, column := range manifest.Columns {
		if column.Binary {
			if raw, exists := object[column.Name]; exists && !bytes.Equal(raw, []byte("null")) {
				return nil, fmt.Errorf("%s.%s must use media objects", table, column.Name)
			}
			delete(object, column.Name)
			continue
		}
		if column.Derived {
			delete(object, column.Name)
			continue
		}
		raw, present := object[column.Name]
		if !present && version < manifest.DataVersion && len(column.Default) > 0 {
			raw, present = column.Default, true
			object[column.Name] = raw
		}
		if !present {
			if column.Required {
				return nil, fmt.Errorf("%s.%s is required", table, column.Name)
			}
			continue
		}
		if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			if !column.Nullable {
				return nil, fmt.Errorf("%s.%s may not be null", table, column.Name)
			}
			continue
		}
		if err := validateValue(column, raw); err != nil {
			return nil, fmt.Errorf("%s.%s: %w", table, column.Name, err)
		}
	}
	delete(object, "sync_status")
	delete(object, "server_version")
	return json.Marshal(object)
}

func validateValue(column Column, raw json.RawMessage) error {
	switch column.Type {
	case "text":
		var value string
		if err := json.Unmarshal(raw, &value); err != nil {
			return fmt.Errorf("expected text")
		}
		if column.Format == "enum" && !slices.Contains(column.EnumValues, value) {
			return fmt.Errorf("unsupported enum value %q", value)
		}
		if column.Format == "json" && !json.Valid([]byte(value)) {
			return fmt.Errorf("expected JSON-encoded text")
		}
	case "int":
		var value int64
		if err := json.Unmarshal(raw, &value); err != nil {
			return fmt.Errorf("expected int64")
		}
	case "real":
		var value float64
		if err := json.Unmarshal(raw, &value); err != nil || math.IsInf(value, 0) || math.IsNaN(value) {
			return fmt.Errorf("expected finite number")
		}
	case "bool":
		var value bool
		if err := json.Unmarshal(raw, &value); err != nil {
			return fmt.Errorf("expected boolean")
		}
	}
	return nil
}
