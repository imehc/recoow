package repository

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

func newID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		panic(err)
	}
	return hex.EncodeToString(value)
}
func rfc3339(value time.Time) string { return value.UTC().Format(time.RFC3339Nano) }
