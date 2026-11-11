package wire_test

import (
	"bytes"
	"encoding/json"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"github.com/imehc/recoow/backend/internal/wire"
	"testing"
)

func TestCanonicalMutationKeepsExistingReceiptHash(t *testing.T) {
	original := domain.PushItem{Table: "diary_tags", ID: "tag", Operation: domain.OpUpdate,
		MutationID: "frozen", BaseVersion: new(int64), DataVersion: 1, UpdatedAtMs: 1000, DeviceID: "device",
		Snapshot: json.RawMessage(`{"id":"tag","updated_at":1000,"updatedAtMs":{"amount":9007199254740993},"future":null}`),
		Origin:   &domain.ChangeOrigin{ClientEnvironment: domain.ClientEnvironment{Client: "ios", OS: "ios", AppVersion: "1"}, DeviceID: "device"}}
	oldHash, err := service.MutationRequestHash(original)
	if err != nil {
		t.Fatal(err)
	}
	frozen, _ := json.Marshal(original)
	canonical, err := wire.Rename(frozen)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(canonical, []byte(`"mutation_id"`)) || !bytes.Contains(canonical, []byte(`"app_version"`)) {
		t.Fatalf("not canonical: %s", canonical)
	}
	var decoded domain.PushItem
	if err := wire.Decode(canonical, &decoded); err != nil {
		t.Fatal(err)
	}
	newHash, err := service.MutationRequestHash(decoded)
	if err != nil || oldHash != newHash {
		t.Fatalf("frozen receipt hash changed: %s %s %v", oldHash, newHash, err)
	}
	if !bytes.Equal(original.Snapshot, decoded.Snapshot) {
		t.Fatalf("snapshot changed: %s", decoded.Snapshot)
	}
}

func TestEnvelopeRenamingPreservesBusinessValuesAndDictionaryKeys(t *testing.T) {
	source := []byte(`{"originalSource":{"deviceId":"device"},"beforeValue":{"deviceId":"business","amount":9007199254740993,"decimal":1e20},"metadata":{"deviceId":"future"},"recordCounts":{"diary_entries":3,"deviceId":1},"snapshot":{"updatedAtMs":null}}`)
	renamed, err := wire.Rename(source)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"original_source":{"device_id":"device"}`, `"before_value":{"deviceId":"business","amount":9007199254740993,"decimal":1e20}`, `"metadata":{"deviceId":"future"}`, `"record_counts":{"diary_entries":3,"deviceId":1}`, `"snapshot":{"updatedAtMs":null}`} {
		if !bytes.Contains(renamed, []byte(want)) {
			t.Fatalf("missing %s in %s", want, renamed)
		}
	}
}

func TestAliasCollisionsUnknownFieldsAndTrailingBodiesAreRejected(t *testing.T) {
	for _, input := range []string{
		`{"deviceId":"old","device_id":"new"}`, `{"device_id":"new","extra":1}`, `{"device_id":"new"} {}`, `[]`,
	} {
		var target struct {
			DeviceID string `json:"deviceId"`
		}
		if err := wire.Decode([]byte(input), &target); err == nil {
			t.Fatalf("accepted %s", input)
		}
	}
	if _, err := wire.Rename([]byte(`{"deviceId":"old","device_id":"new"}`)); err == nil {
		t.Fatal("accepted collision")
	}
}
