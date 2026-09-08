package store

import (
	"encoding/json"
	"strings"
)

func snapshotReferencesMedia(snapshot json.RawMessage, key string) bool {
	if len(snapshot) == 0 {
		return false
	}
	var object struct {
		Media map[string]*struct {
			Key string `json:"key"`
		} `json:"_media"`
	}
	if err := json.Unmarshal(snapshot, &object); err != nil {
		return true
	}
	for _, reference := range object.Media {
		if reference != nil && reference.Key == key {
			return true
		}
	}
	return false
}

func (storage *sqliteStore) scanReceipts(data *dumpData) error {
	rows, err := storage.db.Query("SELECT account_id, mutation_id, request_hash, result_json FROM mutation_receipts ORDER BY account_id, mutation_id")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var receipt mutationReceipt
		var result string
		if err := rows.Scan(&receipt.AccountID, &receipt.MutationID, &receipt.RequestHash, &result); err != nil {
			return err
		}
		if err := json.Unmarshal([]byte(result), &receipt.Result); err != nil {
			return err
		}
		data.Receipts = append(data.Receipts, receipt)
	}
	return rows.Err()
}

func immutableMediaKey(key string) bool { return strings.HasPrefix(key, "sync/") }
