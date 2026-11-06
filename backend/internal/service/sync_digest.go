package service

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"sort"
	"strings"
)

// DigestVersion 1 compares every snapshot field except transport bookkeeping.
// Derived values and unknown fields intentionally remain: false negatives cost a
// snapshot download, whereas ignoring a business difference would lose data.
const DigestVersion = 1

var DigestMetadataFields = []string{"updated_at", "device_id", "server_version", "sync_status"}

func SnapshotDigest(snapshot json.RawMessage) (string, error) {
	decoder := json.NewDecoder(bytes.NewReader(snapshot))
	decoder.UseNumber()
	var object map[string]any
	if err := decoder.Decode(&object); err != nil || object == nil {
		return "", fmt.Errorf("digest requires a snapshot object")
	}
	for _, field := range DigestMetadataFields {
		delete(object, field)
	}
	var encoded strings.Builder
	if err := digestValue(&encoded, object); err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(encoded.String()))
	return hex.EncodeToString(hash[:]), nil
}

func digestValue(out *strings.Builder, value any) error {
	switch value := value.(type) {
	case nil:
		out.WriteString("z")
	case bool:
		if value {
			out.WriteString("t")
		} else {
			out.WriteString("f")
		}
	case string:
		fmt.Fprintf(out, "s%d:%s", len([]byte(value)), value)
	case json.Number:
		number, err := digestNumber(string(value))
		if err != nil {
			return err
		}
		out.WriteString("n" + number + ";")
	case []any:
		fmt.Fprintf(out, "a%d[", len(value))
		for _, item := range value {
			if err := digestValue(out, item); err != nil {
				return err
			}
		}
		out.WriteString("]")
	case map[string]any:
		keys := make([]string, 0, len(value))
		for key := range value {
			keys = append(keys, key)
		}
		sort.Strings(keys) // UTF-8 byte order, shared with both mobile implementations.
		fmt.Fprintf(out, "o%d{", len(keys))
		for _, key := range keys {
			if err := digestValue(out, key); err != nil {
				return err
			}
			if err := digestValue(out, value[key]); err != nil {
				return err
			}
		}
		out.WriteString("}")
	default:
		return fmt.Errorf("unsupported digest value")
	}
	return nil
}

func digestNumber(raw string) (string, error) {
	parts := strings.Split(strings.ToLower(raw), "e")
	exponent := new(big.Int)
	if len(parts) == 2 {
		if _, ok := exponent.SetString(parts[1], 10); !ok {
			return "", fmt.Errorf("invalid exponent")
		}
	}
	mantissa := parts[0]
	negative := strings.HasPrefix(mantissa, "-")
	mantissa = strings.TrimPrefix(mantissa, "-")
	if dot := strings.IndexByte(mantissa, '.'); dot >= 0 {
		exponent.Sub(exponent, big.NewInt(int64(len(mantissa)-dot-1)))
		mantissa = strings.ReplaceAll(mantissa, ".", "")
	}
	mantissa = strings.TrimLeft(mantissa, "0")
	if mantissa == "" {
		return "0e0", nil
	}
	trimmed := strings.TrimRight(mantissa, "0")
	exponent.Add(exponent, big.NewInt(int64(len(mantissa)-len(trimmed))))
	if negative {
		trimmed = "-" + trimmed
	}
	return trimmed + "e" + exponent.String(), nil
}
