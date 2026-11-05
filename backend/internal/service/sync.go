package service

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"slices"
)

func ApplyRevision(transaction domain.RevisionTransaction, item domain.PushItem) (domain.PushResult, error) {
	if !transaction.SupportsTable(item.Table) {
		return RejectedPushResult(item, domain.PushUnsupportedTable), nil
	}
	if item.Operation != domain.OpInsert && item.Operation != domain.OpUpdate && item.Operation != domain.OpDelete {
		return RejectedPushResult(item, domain.PushInvalidOperation), nil
	}
	requestHash, err := MutationRequestHash(item)
	if err != nil {
		return domain.PushResult{}, err
	}
	if item.RequestHash != "" {
		requestHash = item.RequestHash
	}
	receipt, found, err := transaction.ReadReceipt(item.MutationID)
	if err != nil {
		return domain.PushResult{}, err
	}
	if found {
		if receipt.RequestHash != requestHash {
			return RejectedPushResult(item, domain.PushMutationReused), nil
		}
		result := receipt.Result
		result.Replayed = true
		return result, nil
	}
	if item.BaseVersion == nil || *item.BaseVersion < 0 || item.MutationID == "" {
		return RejectedPushResult(item, domain.PushInvalidRevision), nil
	}
	current, exists, err := transaction.ReadRow(item.Table, item.ID)
	if err != nil {
		return domain.PushResult{}, err
	}
	result := RejectedPushResult(item, domain.PushConflict)
	if *item.BaseVersion != current.Version {
		if !exists {
			return RejectedPushResult(item, domain.PushInvalidRevision), nil
		}
		result.Version = current.Version
		result.Current = &current
		if item.Operation == domain.OpDelete && current.DeletedAtMs > 0 {
			result.Status = domain.PushDuplicate
		} else if item.Operation != domain.OpDelete && current.DeletedAtMs == 0 {
			candidate, err := mergeSnapshot(current.Snapshot, item.Snapshot, item.DefaultedFields...)
			if err != nil {
				return domain.PushResult{}, err
			}
			candidate, err = projectSnapshot(transaction, item.Table, item.ID, candidate)
			if err != nil {
				return domain.PushResult{}, err
			}
			if EqualSnapshots(candidate, current.Snapshot) {
				result.Status = domain.PushDuplicate
			}
		}
	} else {
		requestSnapshot := bytes.Clone(item.Snapshot)
		for _, reference := range item.References {
			parent, available, err := transaction.ReadRow(reference.Table, reference.ID)
			if err != nil {
				return domain.PushResult{}, err
			}
			if !available || parent.DeletedAtMs > 0 && len(parent.Snapshot) == 0 {
				return RejectedPushResult(item, domain.PushDependencyMissing), nil
			}
		}
		if item.Operation != domain.OpDelete {
			item.Snapshot, err = mergeSnapshot(current.Snapshot, item.Snapshot, item.DefaultedFields...)
			if err != nil {
				return domain.PushResult{}, err
			}
			item.Snapshot, err = projectSnapshot(transaction, item.Table, item.ID, item.Snapshot)
			if err != nil {
				return domain.PushResult{}, err
			}
		} else if exists {
			item.Snapshot = current.Snapshot
		}
		item.Snapshot, err = prepareBillBalanceEffects(transaction, current, item, requestSnapshot)
		if err != nil {
			return domain.PushResult{}, err
		}
		written, err := transaction.WriteRow(item)
		if err != nil {
			return domain.PushResult{}, err
		}
		result.Status = domain.PushApplied
		result.Applied = true
		result.Version = written.Version
		result.Current = &written
		if err := refreshProjections(transaction, item.Table); err != nil {
			return domain.PushResult{}, err
		}
	}
	return result, transaction.WriteReceipt(domain.MutationReceipt{
		MutationID: item.MutationID, RequestHash: requestHash, Result: result,
	})
}

func MutationRequestHash(item domain.PushItem) (string, error) {
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

func ValidateBootstrap(state domain.DatasetState, request domain.BootstrapRequest) error {
	if state.Generation != request.Generation || request.Watermark < state.MinCursor ||
		request.Watermark > state.Head || request.After < 0 || request.After > request.Watermark {
		return domain.ErrResetRequired
	}
	if request.Limit < 1 || request.Limit > 2000 {
		return fmt.Errorf("invalid bootstrap limit")
	}
	return nil
}

func ChangeFromRow(row domain.Row) domain.Change {
	operation := domain.OpUpdate
	if row.DeletedAtMs != 0 {
		operation = domain.OpDelete
	}
	return domain.Change{
		AccountID: row.AccountID,
		Table:     row.Table, ID: row.ID, Version: row.Version, DataVersion: row.DataVersion,
		UpdatedAtMs: row.UpdatedAtMs, DeviceID: row.DeviceID, Operation: operation,
		Deleted: row.DeletedAtMs != 0, DeletedAtMs: row.DeletedAtMs, Snapshot: bytes.Clone(row.Snapshot),
	}
}
