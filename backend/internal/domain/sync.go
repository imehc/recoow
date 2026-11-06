package domain

import (
	"encoding/json"
	"errors"
)

var ErrResetRequired = errors.New("reset_required")

type RowReference struct {
	Table string
	ID    string
}

type DatasetState struct {
	Generation string `json:"generation"`
	Head       int64  `json:"head"`
	MinCursor  int64  `json:"minCursor"`
}

type BootstrapRequest struct {
	Generation string
	Watermark  int64
	After      int64
	Limit      int
}

type BootstrapPage struct {
	Entries    []Change `json:"entries"`
	Generation string   `json:"generation"`
	Watermark  int64    `json:"watermark"`
	Cursor     int64    `json:"cursor"`
	More       bool     `json:"more"`
}

// SnapshotQuery fetches immutable revisions selected from a fixed-watermark manifest.
type SnapshotQuery struct {
	Generation string  `json:"generation"`
	Watermark  int64   `json:"watermark"`
	Versions   []int64 `json:"versions"`
}

type ManifestEntry struct {
	Change
	Digest   string                     `json:"digest"`
	Metadata map[string]json.RawMessage `json:"metadata"`
}

type ManifestPage struct {
	Entries       []ManifestEntry `json:"entries"`
	Generation    string          `json:"generation"`
	Watermark     int64           `json:"watermark"`
	Cursor        int64           `json:"cursor"`
	More          bool            `json:"more"`
	DigestVersion int             `json:"digestVersion"`
}

type MutationReceipt struct {
	AccountID   string
	MutationID  string
	RequestHash string
	Result      PushResult
}

type RevisionTransaction interface {
	SupportsTable(table string) bool
	ReadRow(table, id string) (Row, bool, error)
	ReadRevision(table, id string, version int64) (Row, bool, error)
	WriteRow(item PushItem) (Row, error)
	WriteChangeEvent(event ChangeEvent) (ChangeEvent, error)
	ReadReceipt(id string) (MutationReceipt, bool, error)
	WriteReceipt(receipt MutationReceipt) error
	AllRows(table string, includeDeleted bool) ([]Row, error)
}
