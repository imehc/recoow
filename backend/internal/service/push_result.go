package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"github.com/imehc/recoow/backend/internal/domain"
	"io"
	"math/big"
	"strings"
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
	return candidateError == nil && currentError == nil && equalJSONValue(candidateValue, currentValue)
}

// Compare JSON numbers by their exact decimal value. PostgreSQL/Swift may emit
// 0, 0.0 or exponents for the same value; float64 would lose large integer IDs.
func equalJSONValue(a, b any) bool {
	switch first := a.(type) {
	case json.Number:
		second, ok := b.(json.Number)
		if !ok {
			return false
		}
		return normalizedJSONNumber(string(first)) == normalizedJSONNumber(string(second))
	case map[string]any:
		second, ok := b.(map[string]any)
		if !ok || len(first) != len(second) {
			return false
		}
		for key, value := range first {
			other, exists := second[key]
			if !exists || !equalJSONValue(value, other) {
				return false
			}
		}
		return true
	case []any:
		second, ok := b.([]any)
		if !ok || len(first) != len(second) {
			return false
		}
		for index, value := range first {
			if !equalJSONValue(value, second[index]) {
				return false
			}
		}
		return true
	default:
		return a == b
	}
}

// Normalize the significand and exponent without expanding powers of ten.
// Valid JSON can contain enormous exponents; comparison must stay proportional
// to the input length rather than allocate that many decimal places.
func normalizedJSONNumber(value string) string {
	negative := strings.HasPrefix(value, "-")
	value = strings.TrimPrefix(value, "-")
	exponent := new(big.Int)
	if index := strings.IndexAny(value, "eE"); index >= 0 {
		exponent.SetString(value[index+1:], 10)
		value = value[:index]
	}
	if index := strings.IndexByte(value, '.'); index >= 0 {
		exponent.Sub(exponent, big.NewInt(int64(len(value)-index-1)))
		value = value[:index] + value[index+1:]
	}
	value = strings.TrimLeft(value, "0")
	if value == "" {
		return "0"
	}
	digits := strings.TrimRight(value, "0")
	exponent.Add(exponent, big.NewInt(int64(len(value)-len(digits))))
	if negative {
		digits = "-" + digits
	}
	return digits + "e" + exponent.String()
}
