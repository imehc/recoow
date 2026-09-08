package store

import (
	"encoding/json"
	"fmt"
	"math"
)

type billBalanceFields struct {
	AccountID        string `json:"stored_value_account_id"`
	Original         int64  `json:"original_amount_cents"`
	Final            int64  `json:"final_amount_cents"`
	TransactionType  string `json:"transaction_type"`
	Category         string `json:"category"`
	SettlementStatus string `json:"settlement_status"`
	ValidUntil       *int64 `json:"group_buy_valid_until"`
}

func addBalanceEffect(effects map[string]int64, accountID string, amount int64) error {
	previous := effects[accountID]
	if amount > 0 && previous > math.MaxInt64-amount || amount < 0 && previous < math.MinInt64-amount {
		return fmt.Errorf("balance overflow")
	}
	effects[accountID] = previous + amount
	if effects[accountID] == 0 {
		delete(effects, accountID)
	}
	return nil
}

func billBalanceDelta(fields billBalanceFields, deleted bool, timestamp int64) (int64, error) {
	if deleted || fields.AccountID == "" || fields.SettlementStatus == "refunded" {
		return 0, nil
	}
	if timestamp > 0 && fields.TransactionType == "expense" && fields.Category == "groupBuy" &&
		fields.SettlementStatus == "active" && fields.ValidUntil != nil && *fields.ValidUntil < timestamp {
		return 0, nil
	}
	switch fields.TransactionType {
	case "expense":
		return fields.Original, nil
	case "storedValueUse":
		if fields.Final == math.MinInt64 {
			return 0, fmt.Errorf("balance overflow")
		}
		return -fields.Final, nil
	}
	return 0, nil
}

func decodeBalanceEffects(snapshot json.RawMessage) (map[string]int64, bool, error) {
	object := map[string]json.RawMessage{}
	if len(snapshot) == 0 {
		return map[string]int64{}, false, nil
	}
	if err := json.Unmarshal(snapshot, &object); err != nil {
		return nil, false, err
	}
	raw, exists := object["stored_value_effects"]
	if !exists || string(raw) == "null" {
		return map[string]int64{}, false, nil
	}
	var encoded string
	if err := json.Unmarshal(raw, &encoded); err != nil {
		return nil, false, err
	}
	effects := map[string]int64{}
	if err := json.Unmarshal([]byte(encoded), &effects); err != nil || effects == nil {
		return nil, false, fmt.Errorf("stored_value_effects must encode an account-to-int64 object")
	}
	for accountID := range effects {
		if accountID == "" {
			return nil, false, fmt.Errorf("balance effect requires an account")
		}
	}
	return effects, true, nil
}

func legacyBalanceEffects(row Row) (map[string]int64, error) {
	effects, exists, err := decodeBalanceEffects(row.Snapshot)
	if err != nil || exists || len(row.Snapshot) == 0 {
		return effects, err
	}
	var fields billBalanceFields
	if err := json.Unmarshal(row.Snapshot, &fields); err != nil {
		return nil, err
	}
	delta, err := billBalanceDelta(fields, row.DeletedAtMs != 0, 0)
	if err != nil {
		return nil, err
	}
	if delta != 0 {
		effects[fields.AccountID] = delta
	}
	return effects, nil
}

func encodeBalanceEffects(snapshot json.RawMessage, effects map[string]int64) (json.RawMessage, error) {
	object := map[string]json.RawMessage{}
	if len(snapshot) > 0 {
		if err := json.Unmarshal(snapshot, &object); err != nil {
			return nil, err
		}
	}
	encoded, err := json.Marshal(effects)
	if err != nil {
		return nil, err
	}
	object["stored_value_effects"], _ = json.Marshal(string(encoded))
	return json.Marshal(object)
}

func prepareBillBalanceEffects(transaction revisionTransaction, previous Row, item PushItem, request json.RawMessage) (json.RawMessage, error) {
	if item.Table != "bills" || item.Operation == OpDelete && len(previous.Snapshot) == 0 {
		return item.Snapshot, nil
	}
	effects, provided, err := decodeBalanceEffects(request)
	if err != nil {
		return nil, err
	}
	if !provided {
		effects, err = legacyBalanceEffects(previous)
		if err != nil {
			return nil, err
		}
		for index, row := range []Row{previous, {Snapshot: item.Snapshot}} {
			if len(row.Snapshot) == 0 {
				continue
			}
			var fields billBalanceFields
			if err := json.Unmarshal(row.Snapshot, &fields); err != nil {
				return nil, err
			}
			delta, err := billBalanceDelta(fields, row.DeletedAtMs != 0 || index == 1 && item.Operation == OpDelete, item.UpdatedAtMs)
			if err != nil {
				return nil, err
			}
			if index == 0 {
				if delta == math.MinInt64 {
					return nil, fmt.Errorf("balance overflow")
				}
				delta = -delta
			}
			account, exists, err := transaction.readRow("stored_value_accounts", fields.AccountID)
			if err != nil {
				return nil, err
			}
			if exists && account.DeletedAtMs == 0 {
				if err := addBalanceEffect(effects, fields.AccountID, delta); err != nil {
					return nil, err
				}
			}
		}
	}
	return encodeBalanceEffects(item.Snapshot, effects)
}
