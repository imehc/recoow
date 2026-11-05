package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"io"
	"reflect"
)

func RejectedPushResult(item domain.PushItem, status domain.PushStatus) domain.PushResult {
	return domain.PushResult{
		MutationID: item.MutationID,
		Table:      item.Table,
		ID:         item.ID,
		Operation:  item.Operation,
		Status:     status,
	}
}

func UnchangedPushResult(item domain.PushItem, current domain.Row) domain.PushResult {
	status := domain.PushConflict
	if item.UpdatedAtMs == current.UpdatedAtMs && item.DeviceID == current.DeviceID &&
		(item.Operation == domain.OpDelete) == (current.DeletedAtMs > 0) &&
		(item.Operation == domain.OpDelete || EqualSnapshots(item.Snapshot, current.Snapshot)) {
		status = domain.PushDuplicate
	}

	current.Snapshot = bytes.Clone(current.Snapshot)
	return domain.PushResult{
		Table:     item.Table,
		ID:        item.ID,
		Operation: item.Operation,
		Version:   current.Version,
		Status:    status,
		Current:   &current,
	}
}

func EqualSnapshots(candidate, current json.RawMessage) bool {
	if bytes.Equal(candidate, current) {
		return true
	}
	decode := func(raw json.RawMessage) (any, error) {
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		var value any
		if err := decoder.Decode(&value); err != nil {
			return nil, err
		}
		var extra any
		if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
			return nil, io.ErrUnexpectedEOF
		}
		return value, nil
	}
	candidateValue, candidateError := decode(candidate)
	currentValue, currentError := decode(current)
	return candidateError == nil && currentError == nil && reflect.DeepEqual(candidateValue, currentValue)
}
