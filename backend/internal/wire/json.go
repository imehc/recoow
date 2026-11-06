// Package wire isolates HTTP field naming from persisted JSON and mutation hashes.
package wire

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
)

func CanonicalName(name string) string {
	if canonical, ok := FieldNames[name]; ok {
		return canonical
	}
	return name
}

func legacyName(name string) string {
	for legacy, canonical := range FieldNames {
		if canonical == name {
			return legacy
		}
	}
	return name
}

// These values are business data or dictionaries, not API envelopes.
func opaque(name string) bool {
	switch CanonicalName(name) {
	case "snapshot", "metadata", "before_value", "after_value", "contracts", "record_counts":
		return true
	}
	return false
}

// Rename changes only declared envelope keys. Raw JSON numbers, nulls and snapshot
// keys stay intact, including unknown fields that happen to look like API names.
func Rename(data []byte, legacy bool) ([]byte, error) {
	data = bytes.TrimSpace(data)
	if len(data) == 0 {
		return nil, fmt.Errorf("empty body")
	}
	if data[0] == '{' {
		var object map[string]json.RawMessage
		if err := json.Unmarshal(data, &object); err != nil {
			return nil, err
		}
		out := make(map[string]json.RawMessage, len(object))
		for name, value := range object {
			key := CanonicalName(name)
			if legacy {
				key = legacyName(key)
			}
			if _, exists := out[key]; exists {
				return nil, fmt.Errorf("duplicate field %s", key)
			}
			if !opaque(name) {
				var err error
				value, err = Rename(value, legacy)
				if err != nil {
					return nil, err
				}
			}
			out[key] = value
		}
		return json.Marshal(out)
	}
	if data[0] == '[' {
		var array []json.RawMessage
		if err := json.Unmarshal(data, &array); err != nil {
			return nil, err
		}
		for i, value := range array {
			var err error
			array[i], err = Rename(value, legacy)
			if err != nil {
				return nil, err
			}
		}
		return json.Marshal(array)
	}
	if !json.Valid(data) {
		return nil, fmt.Errorf("invalid JSON")
	}
	return data, nil
}

// Decode accepts canonical fields and explicit legacy aliases, without changing
// the storage representation of domain structs or accepting unknown API fields.
func Decode(data []byte, target any) error {
	normalized, err := forType(data, reflect.TypeOf(target))
	if err != nil {
		return err
	}
	decoder := json.NewDecoder(bytes.NewReader(normalized))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}

// Unmarshal reads a response into a partial model while accepting legacy names.
func Unmarshal(data []byte, target any) error {
	normalized, err := forType(data, reflect.TypeOf(target))
	if err != nil {
		return err
	}
	return json.Unmarshal(normalized, target)
}

type field struct {
	name string
	typ  reflect.Type
}

func structFields(typ reflect.Type, fields map[string]field) {
	for i := 0; i < typ.NumField(); i++ {
		value := typ.Field(i)
		if value.PkgPath != "" {
			continue
		}
		tag := strings.Split(value.Tag.Get("json"), ",")[0]
		if tag == "-" {
			continue
		}
		nested := value.Type
		for nested.Kind() == reflect.Pointer {
			nested = nested.Elem()
		}
		if value.Anonymous && tag == "" && nested.Kind() == reflect.Struct {
			structFields(nested, fields)
			continue
		}
		if tag == "" {
			tag = value.Name
		}
		definition := field{tag, value.Type}
		fields[tag] = definition
		fields[CanonicalName(tag)] = definition
		fields[legacyName(tag)] = definition
	}
}

func forType(data []byte, typ reflect.Type) ([]byte, error) {
	for typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	if typ == reflect.TypeOf(json.RawMessage{}) {
		return data, nil
	}
	data = bytes.TrimSpace(data)
	if len(data) == 0 || !json.Valid(data) {
		return nil, fmt.Errorf("invalid JSON")
	}
	if typ.Kind() == reflect.Struct && data[0] == '{' {
		var object map[string]json.RawMessage
		if err := json.Unmarshal(data, &object); err != nil {
			return nil, err
		}
		fields := map[string]field{}
		structFields(typ, fields)
		out := make(map[string]json.RawMessage, len(object))
		for key, value := range object {
			if definition, ok := fields[key]; ok {
				key = definition.name
				var err error
				value, err = forType(value, definition.typ)
				if err != nil {
					return nil, err
				}
			}
			if _, exists := out[key]; exists {
				return nil, fmt.Errorf("duplicate field %s", key)
			}
			out[key] = value
		}
		return json.Marshal(out)
	}
	if (typ.Kind() == reflect.Slice || typ.Kind() == reflect.Array) && data[0] == '[' {
		var array []json.RawMessage
		if err := json.Unmarshal(data, &array); err != nil {
			return nil, err
		}
		for i, value := range array {
			var err error
			array[i], err = forType(value, typ.Elem())
			if err != nil {
				return nil, err
			}
		}
		return json.Marshal(array)
	}
	return data, nil
}
