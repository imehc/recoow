package store

import (
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

func migrateAggregateRows(transaction revisionTransaction) error {
	bills, err := transaction.allRows("bills", true)
	if err != nil {
		return err
	}
	for _, bill := range bills {
		if bill.DataVersion >= 2 || len(bill.Snapshot) == 0 {
			continue
		}
		effects, err := legacyBalanceEffects(bill)
		if err != nil {
			return err
		}
		bill.Snapshot, err = encodeBalanceEffects(bill.Snapshot, effects)
		if err != nil {
			return err
		}
		if err := upgradeAggregateRow(transaction, bill); err != nil {
			return err
		}
	}
	accounts, err := transaction.allRows("stored_value_accounts", false)
	if err != nil {
		return err
	}
	for _, account := range accounts {
		if account.DataVersion >= 2 {
			continue
		}
		var fields struct {
			Balance int64 `json:"balance_cents"`
		}
		if err := json.Unmarshal(account.Snapshot, &fields); err != nil {
			return err
		}
		identifier := "opening:" + account.ID
		if _, exists, err := transaction.readRow("stored_value_adjustments", identifier); err != nil {
			return err
		} else if !exists {
			balance, err := projectedBalance(transaction, account.ID)
			if err != nil {
				return err
			}
			if balance > 0 && fields.Balance < math.MinInt64+balance || balance < 0 && fields.Balance > math.MaxInt64+balance {
				return fmt.Errorf("opening balance overflow")
			}
			if err := writeMigrationRow(transaction, account, "stored_value_adjustments", map[string]any{
				"id": identifier, "account_id": account.ID, "amount_cents": fields.Balance - balance, "reason": "migration",
			}); err != nil {
				return err
			}
		}
		if err := upgradeAggregateRow(transaction, account); err != nil {
			return err
		}
	}
	reminders, err := transaction.allRows("reminders", false)
	if err != nil {
		return err
	}
	for _, reminder := range reminders {
		if reminder.DataVersion >= 2 {
			continue
		}
		var fields struct {
			Records *string `json:"completion_records"`
			Keys    *string `json:"completed_date_keys"`
		}
		if err := json.Unmarshal(reminder.Snapshot, &fields); err != nil {
			return err
		}
		details := map[string]completionProjection{}
		if fields.Records != nil && *fields.Records != "" {
			values := []completionProjection{}
			if err := json.Unmarshal([]byte(*fields.Records), &values); err != nil {
				return err
			}
			for _, value := range values {
				details[value.DateKey] = value
			}
		}
		if fields.Keys != nil {
			for _, key := range strings.Split(*fields.Keys, ",") {
				if _, exists := details[key]; key != "" && !exists {
					details[key] = completionProjection{DateKey: key, CompletedAt: reminder.UpdatedAtMs, Kind: "checkIn"}
				}
			}
		}
		for key, detail := range details {
			identifier := reminder.ID + ":" + key
			if _, exists, err := transaction.readRow("reminder_completions", identifier); err != nil {
				return err
			} else if exists {
				continue
			}
			if err := writeMigrationRow(transaction, reminder, "reminder_completions", map[string]any{
				"id": identifier, "reminder_id": reminder.ID, "date_key": key, "completed_at": detail.CompletedAt,
				"kind": detail.Kind, "note": detail.Note,
			}); err != nil {
				return err
			}
		}
		if err := upgradeAggregateRow(transaction, reminder); err != nil {
			return err
		}
	}
	return nil
}

func writeMigrationRow(transaction revisionTransaction, parent Row, table string, fields map[string]any) error {
	fields["created_at"] = parent.UpdatedAtMs
	fields["updated_at"] = parent.UpdatedAtMs
	fields["device_id"] = parent.DeviceID
	fields["deleted_at"] = nil
	snapshot, err := json.Marshal(fields)
	if err != nil {
		return err
	}
	_, err = transaction.writeRow(PushItem{
		Table: table, ID: fields["id"].(string), Snapshot: snapshot, UpdatedAtMs: parent.UpdatedAtMs,
		DeviceID: parent.DeviceID, Operation: OpInsert, DataVersion: 1,
	})
	return err
}

func upgradeAggregateRow(transaction revisionTransaction, row Row) error {
	snapshot, err := projectSnapshot(transaction, row.Table, row.ID, row.Snapshot)
	if err != nil {
		return err
	}
	operation := OpUpdate
	if row.DeletedAtMs != 0 {
		operation = OpDelete
	}
	_, err = transaction.writeRow(PushItem{
		Table: row.Table, ID: row.ID, Snapshot: snapshot, UpdatedAtMs: row.UpdatedAtMs,
		DeviceID: row.DeviceID, Operation: operation, DataVersion: 2,
	})
	return err
}
