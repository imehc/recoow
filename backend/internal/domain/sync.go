package domain

import "errors"

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

type MutationReceipt struct {
	AccountID   string
	MutationID  string
	RequestHash string
	Result      PushResult
}

type RevisionTransaction interface {
	SupportsTable(table string) bool
	ReadRow(table, id string) (Row, bool, error)
	WriteRow(item PushItem) (Row, error)
	ReadReceipt(id string) (MutationReceipt, bool, error)
	WriteReceipt(receipt MutationReceipt) error
	AllRows(table string, includeDeleted bool) ([]Row, error)
}
