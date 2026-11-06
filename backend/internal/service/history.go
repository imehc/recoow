package service

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/imehc/recoow/backend/internal/domain"
)

func ValidClient(value string) bool {
	return value == "ios" || value == "android" || value == "web" || value == "unknown"
}

func ValidateEnvironment(environment domain.ClientEnvironment) error {
	if !ValidClient(environment.Client) {
		return fmt.Errorf("invalid client type")
	}
	switch environment.OS {
	case "ios", "android", "macos", "windows", "linux", "unknown":
	default:
		return fmt.Errorf("invalid operating system")
	}
	for _, value := range []string{environment.AppVersion, environment.OSVersion} {
		if !validSourceText(value, 80) {
			return fmt.Errorf("invalid environment version")
		}
	}
	return nil
}

func validSourceText(value string, maxBytes int) bool {
	return len(value) <= maxBytes && utf8.ValidString(value) && !strings.ContainsFunc(value, unicode.IsControl)
}

func ValidateOrigin(item domain.PushItem) error {
	if item.Origin == nil {
		return nil
	}
	if item.Origin.DeviceID != item.DeviceID || !validSourceText(item.Origin.DeviceID, 200) || !validSourceText(item.Origin.DeviceName, 200) {
		return fmt.Errorf("invalid original device")
	}
	return ValidateEnvironment(item.Origin.ClientEnvironment)
}

func BuildChangeEvent(item domain.PushItem, previous, written domain.Row, method string) domain.ChangeEvent {
	operation := "update"
	if written.DeletedAtMs > 0 {
		operation = "delete"
	} else if previous.Version == 0 {
		operation = "insert"
	} else if previous.DeletedAtMs > 0 {
		operation = "restore"
	}
	origin := &domain.ChangeOrigin{ClientEnvironment: domain.ClientEnvironment{Client: "unknown", OS: "unknown"}, DeviceID: item.DeviceID}
	if item.Origin != nil {
		copy := *item.Origin
		origin = &copy
	}
	modified := item.UpdatedAtMs
	return domain.ChangeEvent{
		Version: written.Version, PreviousVersion: previous.Version, Table: written.Table, ID: written.ID,
		Title: eventTitle(written, previous), Operation: operation, Method: method,
		MutationID: item.MutationID, ModifiedAtMs: &modified, OriginalSource: origin,
	}
}

func eventTitle(rows ...domain.Row) string {
	for _, row := range rows {
		var object map[string]json.RawMessage
		if json.Unmarshal(row.Snapshot, &object) != nil {
			continue
		}
		for _, field := range []string{"title", "name", "collection_title", "option_title"} {
			var value string
			if json.Unmarshal(object[field], &value) == nil && strings.TrimSpace(value) != "" {
				return string([]rune(value)[:min(160, utf8.RuneCountInString(value))])
			}
		}
	}
	return rows[0].ID
}

func EventRef(event domain.ChangeEvent) domain.ChangeEventRef {
	return domain.ChangeEventRef{EventID: event.EventID, Version: event.Version, AcceptedAtMs: *event.AcceptedAtMs}
}

func HistoryChanges(detail domain.ChangeEventDetail) ([]domain.FieldChange, error) {
	before, after := map[string]json.RawMessage{}, map[string]json.RawMessage{}
	if detail.Before != nil && detail.Before.DeletedAtMs == 0 && len(detail.Before.Snapshot) > 0 {
		if err := json.Unmarshal(detail.Before.Snapshot, &before); err != nil {
			return nil, err
		}
	}
	if detail.After.DeletedAtMs == 0 && len(detail.After.Snapshot) > 0 {
		if err := json.Unmarshal(detail.After.Snapshot, &after); err != nil {
			return nil, err
		}
	}
	keys := map[string]bool{}
	for key := range before {
		keys[key] = true
	}
	for key := range after {
		keys[key] = true
	}
	fields := make([]string, 0, len(keys))
	for key := range keys {
		if key != "id" && key != "updated_at" && key != "device_id" && key != "server_version" && key != "sync_status" && key != "deleted_at" {
			fields = append(fields, key)
		}
	}
	sort.Strings(fields)
	changes := []domain.FieldChange{}
	for _, field := range fields {
		old, oldPresent := before[field]
		next, nextPresent := after[field]
		if oldPresent == nextPresent && EqualSnapshots(old, next) {
			continue
		}
		changes = append(changes, domain.FieldChange{Field: field, BeforePresent: oldPresent, AfterPresent: nextPresent, BeforeValue: old, AfterValue: next})
	}
	return changes, nil
}
