package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"reflect"
)

func rejectedPushResult(item PushItem, status PushStatus) PushResult {
	return PushResult{
		MutationID: item.MutationID,
		Table:      item.Table,
		ID:         item.ID,
		Operation:  item.Operation,
		Status:     status,
	}
}

func unchangedPushResult(item PushItem, current Row) PushResult {
	status := PushConflict
	if item.UpdatedAtMs == current.UpdatedAtMs && item.DeviceID == current.DeviceID &&
		(item.Operation == OpDelete) == (current.DeletedAtMs > 0) &&
		(item.Operation == OpDelete || equalSnapshots(item.Snapshot, current.Snapshot)) {
		status = PushDuplicate
	}

	current.Snapshot = bytes.Clone(current.Snapshot)
	return PushResult{
		Table:     item.Table,
		ID:        item.ID,
		Operation: item.Operation,
		Version:   current.Version,
		Status:    status,
		Current:   &current,
	}
}

func equalSnapshots(candidate, current json.RawMessage) bool {
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
