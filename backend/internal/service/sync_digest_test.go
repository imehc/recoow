package service

import (
	"encoding/json"
	"os"
	"testing"
)

func TestSnapshotDigestSharedVectors(t *testing.T) {
	data, err := os.ReadFile("../../../tests/fixtures/sync-digest-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors []struct {
		Snapshot string
		Digest   string
	}
	if err := json.Unmarshal(data, &vectors); err != nil {
		t.Fatal(err)
	}
	for _, vector := range vectors {
		got, err := SnapshotDigest(json.RawMessage(vector.Snapshot))
		if err != nil || got != vector.Digest {
			t.Fatalf("snapshot %s: %s, %v", vector.Snapshot, got, err)
		}
	}
}

func TestSnapshotDigestPreservesBusinessDifferences(t *testing.T) {
	base, _ := SnapshotDigest(json.RawMessage(`{"amount":9007199254740993,"optional":null,"future":{"value":1}}`))
	for _, different := range []string{
		`{"amount":9007199254740992,"optional":null,"future":{"value":1}}`,
		`{"amount":9007199254740993,"future":{"value":1}}`,
		`{"amount":9007199254740993,"optional":null,"future":{"value":2}}`,
	} {
		got, err := SnapshotDigest(json.RawMessage(different))
		if err != nil || got == base {
			t.Fatalf("difference lost: %s", different)
		}
	}
}
