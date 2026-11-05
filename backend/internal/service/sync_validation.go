package service

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
	"os"
	"strings"
)

type SyncValidator struct {
	store     domain.Store
	contracts *schema.Registry
}

func NewSyncValidator(store domain.Store, contracts *schema.Registry) *SyncValidator {
	return &SyncValidator{store: store, contracts: contracts}
}

const maxMediaBytes int64 = 200 << 20

func (server *SyncValidator) PrepareRevision(accountID string, item *domain.PushItem) error {
	requestHash, err := MutationRequestHash(*item)
	if err != nil {
		return err
	}
	item.RequestHash = requestHash
	if strings.TrimSpace(item.MutationID) == "" || len(item.MutationID) > 200 || item.BaseVersion == nil || *item.BaseVersion < 0 {
		return fmt.Errorf("mutationId and nonnegative baseVersion are required")
	}
	if item.Resolution != "" && item.Resolution != "local" && item.Resolution != "remote" ||
		(item.Resolution == "") != (item.ExpectedVersion == nil) || item.ExpectedVersion != nil && *item.ExpectedVersion < 1 {
		return fmt.Errorf("resolution requires local or remote and a positive expectedVersion")
	}
	manifest, _ := server.contracts.Get(item.Table)
	if item.DataVersion < max(1, manifest.MinDataVersion) || item.DataVersion > max(1, manifest.DataVersion) {
		return fmt.Errorf("unsupported dataVersion for %s", item.Table)
	}
	if item.Operation == domain.OpDelete {
		return nil
	}
	if item.Operation != domain.OpInsert && item.Operation != domain.OpUpdate {
		return fmt.Errorf("invalid operation")
	}
	original := map[string]json.RawMessage{}
	if err := json.Unmarshal(item.Snapshot, &original); err != nil {
		return err
	}
	for _, column := range manifest.Columns {
		if _, exists := original[column.Name]; !exists && item.DataVersion < manifest.DataVersion && len(column.Default) > 0 {
			item.DefaultedFields = append(item.DefaultedFields, column.Name)
		}
	}
	data, err := server.contracts.Normalize(item.Table, item.DataVersion, item.Snapshot)
	if err != nil {
		return err
	}
	item.Snapshot = data
	item.DataVersion = max(1, manifest.DataVersion)
	object := map[string]json.RawMessage{}
	if err := json.Unmarshal(data, &object); err != nil {
		return err
	}
	if raw, exists := object["_media"]; exists {
		var references map[string]json.RawMessage
		if err := json.Unmarshal(raw, &references); err != nil {
			return fmt.Errorf("invalid media references")
		}
		allowed := map[string]bool{"object": item.Table == "media_assets"}
		for _, column := range manifest.Columns {
			if column.Binary {
				allowed[column.Name] = true
			}
		}
		for field := range references {
			if !allowed[field] {
				return fmt.Errorf("unknown media field %s.%s", item.Table, field)
			}
		}
	}
	for _, dependency := range manifest.Dependencies {
		raw, exists := object[dependency.Field]
		if !exists || string(raw) == "null" {
			continue
		}
		var value string
		if err := json.Unmarshal(raw, &value); err != nil {
			return err
		}
		identifiers := []string{value}
		if dependency.Array {
			if err := json.Unmarshal([]byte(value), &identifiers); err != nil {
				return err
			}
		}
		for _, id := range identifiers {
			if id != "" {
				item.References = append(item.References, domain.RowReference{Table: dependency.Table, ID: id})
			}
		}
	}
	if item.Table == "reminder_completions" {
		var parent, date string
		_ = json.Unmarshal(object["reminder_id"], &parent)
		_ = json.Unmarshal(object["date_key"], &date)
		if item.ID != parent+":"+date {
			return fmt.Errorf("completion id must be reminder_id:date_key")
		}
	}
	if item.Table == "food_day_records" {
		var day int64
		if err := json.Unmarshal(object["day_start_at"], &day); err != nil {
			return err
		}
		if item.ID != fmt.Sprintf("food-day:%d", day) {
			return fmt.Errorf("food day id must match day_start_at")
		}
	}
	if item.Table == "media_assets" {
		var references map[string]*mediaReference
		if err := json.Unmarshal(object["_media"], &references); err != nil || references["object"] == nil {
			return fmt.Errorf("media asset requires a confirmed object reference")
		}
		var checksum string
		var size int64
		_ = json.Unmarshal(object["checksum"], &checksum)
		_ = json.Unmarshal(object["byte_count"], &size)
		if references["object"].Checksum != checksum || references["object"].Size != size {
			return fmt.Errorf("media asset metadata mismatch")
		}
	}
	return server.validateMediaReferences(accountID, object)
}

type mediaReference struct {
	Key         string `json:"key"`
	Checksum    string `json:"checksum"`
	Size        int64  `json:"size"`
	ContentType string `json:"contentType"`
}

func (server *SyncValidator) validateMediaReferences(accountID string, snapshot map[string]json.RawMessage) error {
	raw, exists := snapshot["_media"]
	if !exists {
		return nil
	}
	references := map[string]*mediaReference{}
	if err := json.Unmarshal(raw, &references); err != nil {
		return fmt.Errorf("invalid media references")
	}
	for _, reference := range references {
		if reference == nil {
			continue
		}
		checksum, err := hex.DecodeString(reference.Checksum)
		if err != nil || len(checksum) != 32 || reference.Checksum != strings.ToLower(reference.Checksum) ||
			reference.Key != "sync/"+reference.Checksum || reference.Size < 0 || reference.Size > maxMediaBytes {
			return fmt.Errorf("invalid media reference")
		}
		media, err := server.store.GetMedia(accountID, reference.Key)
		if err != nil || media.Checksum != reference.Checksum || media.Size != reference.Size {
			return fmt.Errorf("media_not_ready: %s", reference.Key)
		}
		if path := server.store.MediaPath(accountID, media.Checksum); path != "" {
			info, err := os.Stat(path)
			if err != nil || info.Size() != media.Size {
				return fmt.Errorf("media_not_ready: %s", reference.Key)
			}
		}
	}
	return nil
}
