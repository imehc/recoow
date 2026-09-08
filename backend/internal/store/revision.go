package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
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

type mutationReceipt struct {
	AccountID   string
	MutationID  string
	RequestHash string
	Result      PushResult
}

type revisionTransaction interface {
	supportsTable(table string) bool
	readRow(table, id string) (Row, bool, error)
	writeRow(item PushItem) (Row, error)
	readReceipt(id string) (mutationReceipt, bool, error)
	writeReceipt(receipt mutationReceipt) error
	allRows(table string, includeDeleted bool) ([]Row, error)
}

func applyRevision(transaction revisionTransaction, item PushItem) (PushResult, error) {
	if !transaction.supportsTable(item.Table) {
		return rejectedPushResult(item, PushUnsupportedTable), nil
	}
	if item.Operation != OpInsert && item.Operation != OpUpdate && item.Operation != OpDelete {
		return rejectedPushResult(item, PushInvalidOperation), nil
	}
	requestHash, err := MutationRequestHash(item)
	if err != nil {
		return PushResult{}, err
	}
	if item.RequestHash != "" {
		requestHash = item.RequestHash
	}
	receipt, found, err := transaction.readReceipt(item.MutationID)
	if err != nil {
		return PushResult{}, err
	}
	if found {
		if receipt.RequestHash != requestHash {
			return rejectedPushResult(item, PushMutationReused), nil
		}
		result := receipt.Result
		result.Replayed = true
		return result, nil
	}
	if item.BaseVersion == nil || *item.BaseVersion < 0 || item.MutationID == "" {
		return rejectedPushResult(item, PushInvalidRevision), nil
	}
	current, exists, err := transaction.readRow(item.Table, item.ID)
	if err != nil {
		return PushResult{}, err
	}
	result := rejectedPushResult(item, PushConflict)
	if *item.BaseVersion != current.Version {
		if !exists {
			return rejectedPushResult(item, PushInvalidRevision), nil
		}
		result.Version = current.Version
		result.Current = &current
		if item.Operation == OpDelete && current.DeletedAtMs > 0 {
			result.Status = PushDuplicate
		} else if item.Operation != OpDelete && current.DeletedAtMs == 0 {
			candidate, err := mergeSnapshot(current.Snapshot, item.Snapshot, item.DefaultedFields...)
			if err != nil {
				return PushResult{}, err
			}
			candidate, err = projectSnapshot(transaction, item.Table, item.ID, candidate)
			if err != nil {
				return PushResult{}, err
			}
			if equalSnapshots(candidate, current.Snapshot) {
				result.Status = PushDuplicate
			}
		}
	} else {
		requestSnapshot := bytes.Clone(item.Snapshot)
		for _, reference := range item.References {
			parent, available, err := transaction.readRow(reference.Table, reference.ID)
			if err != nil {
				return PushResult{}, err
			}
			if !available || parent.DeletedAtMs > 0 && len(parent.Snapshot) == 0 {
				return rejectedPushResult(item, PushDependencyMissing), nil
			}
		}
		if item.Operation != OpDelete {
			item.Snapshot, err = mergeSnapshot(current.Snapshot, item.Snapshot, item.DefaultedFields...)
			if err != nil {
				return PushResult{}, err
			}
			item.Snapshot, err = projectSnapshot(transaction, item.Table, item.ID, item.Snapshot)
			if err != nil {
				return PushResult{}, err
			}
		} else if exists {
			item.Snapshot = current.Snapshot
		}
		item.Snapshot, err = prepareBillBalanceEffects(transaction, current, item, requestSnapshot)
		if err != nil {
			return PushResult{}, err
		}
		written, err := transaction.writeRow(item)
		if err != nil {
			return PushResult{}, err
		}
		result.Status = PushApplied
		result.Applied = true
		result.Version = written.Version
		result.Current = &written
		if err := refreshProjections(transaction, item.Table); err != nil {
			return PushResult{}, err
		}
	}
	return result, transaction.writeReceipt(mutationReceipt{
		MutationID: item.MutationID, RequestHash: requestHash, Result: result,
	})
}

func MutationRequestHash(item PushItem) (string, error) {
	if len(item.Snapshot) > 0 {
		decoder := json.NewDecoder(bytes.NewReader(item.Snapshot))
		decoder.UseNumber()
		var snapshot any
		if err := decoder.Decode(&snapshot); err != nil {
			return "", err
		}
		canonical, err := json.Marshal(snapshot)
		if err != nil {
			return "", err
		}
		item.Snapshot = canonical
	}
	encoded, err := json.Marshal(item)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:]), nil
}

func mergeSnapshot(previous, incoming json.RawMessage, defaultedFields ...string) (json.RawMessage, error) {
	merged := map[string]json.RawMessage{}
	if len(previous) > 0 {
		if err := json.Unmarshal(previous, &merged); err != nil {
			return nil, err
		}
	}
	patch := map[string]json.RawMessage{}
	if err := json.Unmarshal(incoming, &patch); err != nil {
		return nil, err
	}
	for field, value := range patch {
		if _, exists := merged[field]; exists && slices.Contains(defaultedFields, field) {
			continue
		}
		merged[field] = value
	}
	delete(merged, "sync_status")
	delete(merged, "server_version")
	return json.Marshal(merged)
}

func validateBootstrap(state DatasetState, request BootstrapRequest) error {
	if state.Generation != request.Generation || request.Watermark < state.MinCursor ||
		request.Watermark > state.Head || request.After < 0 || request.After > request.Watermark {
		return ErrResetRequired
	}
	if request.Limit < 1 || request.Limit > 2000 {
		return fmt.Errorf("invalid bootstrap limit")
	}
	return nil
}

func changeFromRow(row Row) Change {
	operation := OpUpdate
	if row.DeletedAtMs != 0 {
		operation = OpDelete
	}
	return Change{
		Table: row.Table, ID: row.ID, Version: row.Version, DataVersion: row.DataVersion,
		UpdatedAtMs: row.UpdatedAtMs, DeviceID: row.DeviceID, Operation: operation,
		Deleted: row.DeletedAtMs != 0, DeletedAtMs: row.DeletedAtMs, Snapshot: bytes.Clone(row.Snapshot),
	}
}
