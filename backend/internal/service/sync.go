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
	if err := ValidateOrigin(item); err != nil {
		return domain.PushResult{}, err
	}
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
	if item.Resolution != "" && item.Resolution != "local" && item.Resolution != "remote" ||
		(item.Resolution == "") != (item.ExpectedVersion == nil) || item.ExpectedVersion != nil && *item.ExpectedVersion < 1 {
		return RejectedPushResult(item, domain.PushInvalidRevision), nil
	}
	current, exists, err := transaction.ReadRow(item.Table, item.ID)
	if err != nil {
		return domain.PushResult{}, err
	}
	result := RejectedPushResult(item, domain.PushConflict)
	result.Version = current.Version
	if exists {
		result.Current = &current
	}
	if item.Resolution != "" && (item.ExpectedVersion == nil || *item.ExpectedVersion != current.Version) {
		return result, transaction.WriteReceipt(domain.MutationReceipt{MutationID: item.MutationID, RequestHash: requestHash, Result: result})
	}
	requestSnapshot := bytes.Clone(item.Snapshot)
	write := *item.BaseVersion == current.Version
	mergedRevision := false
	if !write {
		if !exists {
			return RejectedPushResult(item, domain.PushInvalidRevision), nil
		}
		if item.Operation == domain.OpDelete && current.DeletedAtMs > 0 {
			result.Status = domain.PushDuplicate
		} else if item.Operation != domain.OpDelete && current.DeletedAtMs == 0 {
			base, found, err := transaction.ReadRevision(item.Table, item.ID, *item.BaseVersion)
			if err != nil {
				return domain.PushResult{}, err
			}
			if found && base.DeletedAtMs == 0 && len(base.Snapshot) > 0 {
				candidate, conflict, err := threeWaySnapshot(item.Table, base.Snapshot, item.Snapshot, current.Snapshot, item.Resolution, item.DefaultedFields)
				if err != nil {
					return domain.PushResult{}, err
				}
				if !conflict {
					item.Snapshot = candidate
					write = !sameBusinessSnapshot(item.Table, candidate, current.Snapshot)
					mergedRevision = true
					if !write {
						result.Status = domain.PushDuplicate
					}
				}
			} else {
				candidate, err := mergeSnapshot(current.Snapshot, item.Snapshot, item.DefaultedFields...)
				if err != nil {
					return domain.PushResult{}, err
				}
				if sameBusinessSnapshot(item.Table, candidate, current.Snapshot) {
					result.Status = domain.PushDuplicate
				}
				// With no common ancestor, an explicit choice can only select a whole record.
				if item.Resolution == "local" {
					write = true
					mergedRevision = true
				}
				if item.Resolution == "remote" {
					result.Status = domain.PushDuplicate
				}
			}
		} else if item.Resolution == "local" {
			write = true
			mergedRevision = true
		} else if item.Resolution == "remote" {
			result.Status = domain.PushDuplicate
		}
	}
	if write {
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
			if mergedRevision || item.Resolution != "" {
				item.Snapshot, err = revisionMetadata(item.Snapshot, item.UpdatedAtMs, item.DeviceID)
				if err != nil {
					return domain.PushResult{}, err
				}
			}
		} else if exists {
			item.Snapshot = current.Snapshot
		}
		if mergedRevision || item.Resolution != "" {
			// A stale device's cumulative balance effects cannot describe the merged bill.
			requestSnapshot = json.RawMessage(`{}`)
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
		method := "normal"
		if item.Resolution != "" {
			method = "manual_resolution"
		} else if mergedRevision {
			method = "automatic_merge"
		}
		event, err := transaction.WriteChangeEvent(BuildChangeEvent(item, current, written, method))
		if err != nil {
			return domain.PushResult{}, err
		}
		derived, err := refreshProjections(transaction, item.Table, event)
		if err != nil {
			return domain.PushResult{}, err
		}
		result.Events = append([]domain.ChangeEventRef{EventRef(event)}, derived...)
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
