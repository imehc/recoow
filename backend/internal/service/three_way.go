package service

import (
	"encoding/json"
	"slices"
)

// 结算相关字段构成一个整体：分开合并各自编辑的部分，
// 可能产生两端都未预期的金额或余额变动。
var billSettlementFields = []string{
	"original_amount_cents", "discount_amount_cents", "final_amount_cents",
	"transaction_type", "category", "payment_method", "stored_value_account_id",
	"settlement_status", "group_buy_valid_until", "redeemed_at", "refund_reason",
}

func mergeIgnored(table, field string) bool {
	if slices.Contains([]string{"id", "created_at", "updated_at", "device_id", "deleted_at", "server_version", "sync_status"}, field) {
		return true
	}
	return table == "bills" && field == "stored_value_effects" ||
		table == "media_assets" && (field == "storage_key" || field == "storage_backend") ||
		table == "stored_value_accounts" && field == "balance_cents" ||
		table == "reminders" && slices.Contains([]string{"completion_records", "completed_date_keys"}, field)
}

func sameField(first, second map[string]json.RawMessage, field string) bool {
	a, aExists := first[field]
	b, bExists := second[field]
	return aExists == bExists && (!aExists || EqualSnapshots(a, b))
}

func sameFields(first, second map[string]json.RawMessage, fields []string) bool {
	for _, field := range fields {
		if !sameField(first, second, field) {
			return false
		}
	}
	return true
}

// 下行快照可能缺少未知字段或新增的默认字段，需以共同祖先
// 为基准补全，绝不使用当前远端值。
func threeWaySnapshot(table string, base, incoming, current json.RawMessage, resolution string, defaulted []string) (json.RawMessage, bool, error) {
	localJSON, err := mergeSnapshot(base, incoming, defaulted...)
	if err != nil {
		return nil, false, err
	}
	ancestor, local, remote := map[string]json.RawMessage{}, map[string]json.RawMessage{}, map[string]json.RawMessage{}
	for _, entry := range []struct {
		raw    json.RawMessage
		target *map[string]json.RawMessage
	}{{base, &ancestor}, {localJSON, &local}, {current, &remote}} {
		if err := json.Unmarshal(entry.raw, entry.target); err != nil {
			return nil, false, err
		}
	}
	merged := make(map[string]json.RawMessage, len(remote))
	for field, value := range remote {
		merged[field] = value
	}
	groups := [][]string{}
	if table == "bills" {
		groups = append(groups, billSettlementFields)
	}
	grouped := map[string]bool{}
	for _, group := range groups {
		for _, field := range group {
			grouped[field] = true
		}
	}
	for field := range local {
		if !mergeIgnored(table, field) && !grouped[field] {
			groups = append(groups, []string{field})
		}
	}
	conflict := false
	for _, fields := range groups {
		if sameFields(local, ancestor, fields) {
			continue
		}
		overlap := !sameFields(remote, ancestor, fields) && !sameFields(local, remote, fields)
		if overlap && resolution == "" {
			conflict = true
			continue
		}
		if overlap && resolution == "remote" {
			continue
		}
		for _, field := range fields {
			if value, exists := local[field]; exists {
				merged[field] = value
			} else {
				delete(merged, field)
			}
		}
	}
	encoded, err := json.Marshal(merged)
	return encoded, conflict, err
}

func sameBusinessSnapshot(table string, first, second json.RawMessage) bool {
	a, b := map[string]json.RawMessage{}, map[string]json.RawMessage{}
	if json.Unmarshal(first, &a) != nil || json.Unmarshal(second, &b) != nil {
		return false
	}
	for field := range a {
		if mergeIgnored(table, field) {
			delete(a, field)
		}
	}
	for field := range b {
		if mergeIgnored(table, field) {
			delete(b, field)
		}
	}
	aJSON, _ := json.Marshal(a)
	bJSON, _ := json.Marshal(b)
	return EqualSnapshots(aJSON, bJSON)
}

func revisionMetadata(snapshot json.RawMessage, timestamp int64, device string) (json.RawMessage, error) {
	object := map[string]json.RawMessage{}
	if err := json.Unmarshal(snapshot, &object); err != nil {
		return nil, err
	}
	object["updated_at"], _ = json.Marshal(timestamp)
	object["device_id"], _ = json.Marshal(device)
	object["deleted_at"] = json.RawMessage("null")
	delete(object, "sync_status")
	delete(object, "server_version")
	return json.Marshal(object)
}
