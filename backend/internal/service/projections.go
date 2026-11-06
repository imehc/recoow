package service

import (
	"encoding/json"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"math"
	"sort"
	"strings"
)

func projectSnapshot(transaction domain.RevisionTransaction, table, id string, snapshot json.RawMessage) (json.RawMessage, error) {
	if table != "stored_value_accounts" && table != "reminders" {
		return snapshot, nil
	}
	object := map[string]json.RawMessage{}
	if err := json.Unmarshal(snapshot, &object); err != nil {
		return nil, err
	}
	if table == "stored_value_accounts" {
		balance, err := projectedBalance(transaction, id)
		if err != nil {
			return nil, err
		}
		object["balance_cents"], _ = json.Marshal(balance)
	} else {
		rows, err := transaction.AllRows("reminder_completions", true)
		if err != nil {
			return nil, err
		}
		completions := []completionProjection{}
		hasDetails := false
		for _, row := range rows {
			var detail completionSnapshot
			if err := json.Unmarshal(row.Snapshot, &detail); err != nil {
				return nil, err
			}
			if detail.ReminderID == id {
				hasDetails = true
				if row.DeletedAtMs != 0 {
					continue
				}
				completions = append(completions, completionProjection{
					DateKey: detail.DateKey, CompletedAt: detail.CompletedAt, Kind: detail.Kind, Note: detail.Note,
				})
			}
		}
		sort.Slice(completions, func(first, second int) bool { return completions[first].DateKey < completions[second].DateKey })
		keys := make([]string, 0, len(completions))
		for _, completion := range completions {
			keys = append(keys, completion.DateKey)
		}
		encoded, err := json.Marshal(completions)
		if err != nil {
			return nil, err
		}
		object["completion_records"], _ = json.Marshal(string(encoded))
		object["completed_date_keys"], _ = json.Marshal(strings.Join(keys, ","))
		if hasDetails {
			projectCompletionStatus(object, completions)
		}
	}
	return json.Marshal(object)
}

type completionSnapshot struct {
	ReminderID  string  `json:"reminder_id"`
	DateKey     string  `json:"date_key"`
	CompletedAt int64   `json:"completed_at"`
	Kind        string  `json:"kind"`
	Note        *string `json:"note"`
}

type completionProjection struct {
	DateKey     string  `json:"dateKey"`
	CompletedAt int64   `json:"completedAt"`
	Kind        string  `json:"kindRawValue"`
	Note        *string `json:"note,omitempty"`
}

func projectCompletionStatus(object map[string]json.RawMessage, completions []completionProjection) {
	var schedule string
	var target, imported int64
	var ended *int64
	_ = json.Unmarshal(object["schedule_kind"], &schedule)
	_ = json.Unmarshal(object["continuous_days"], &target)
	_ = json.Unmarshal(object["imported_completed_days"], &imported)
	_ = json.Unmarshal(object["ended_early_at"], &ended)
	switch schedule {
	case "dailyGoal":
		object["completed_at"] = json.RawMessage("null")
		return
	case "single":
		target = 1
	case "dateRange":
		if len(completions) == 0 {
			object["completed_at"] = json.RawMessage("null")
		}
		return
	default:
		target = max(1, target)
	}
	if ended != nil || int64(len(completions))+imported < target {
		object["completed_at"] = json.RawMessage("null")
		return
	}
	var latest int64
	for _, completion := range completions {
		latest = max(latest, completion.CompletedAt)
	}
	if latest > 0 {
		object["completed_at"], _ = json.Marshal(latest)
	}
}

func projectedBalance(transaction domain.RevisionTransaction, accountID string) (int64, error) {
	var balance int64
	for _, table := range []string{"bills", "stored_value_adjustments"} {
		rows, err := transaction.AllRows(table, table == "bills")
		if err != nil {
			return 0, err
		}
		for _, row := range rows {
			if table == "bills" {
				effects, err := legacyBalanceEffects(row)
				if err != nil {
					return 0, err
				}
				delta := effects[accountID]
				if delta > 0 && balance > math.MaxInt64-delta || delta < 0 && balance < math.MinInt64-delta {
					return 0, fmt.Errorf("balance overflow")
				}
				balance += delta
				continue
			}
			var fields struct {
				AccountID string `json:"account_id"`
				Amount    int64  `json:"amount_cents"`
			}
			if err := json.Unmarshal(row.Snapshot, &fields); err != nil {
				return 0, err
			}
			var delta int64
			if table == "stored_value_adjustments" && fields.AccountID == accountID {
				delta = fields.Amount
			}
			if delta > 0 && balance > math.MaxInt64-delta || delta < 0 && balance < math.MinInt64-delta {
				return 0, fmt.Errorf("balance overflow")
			}
			balance += delta
		}
	}
	return balance, nil
}

func refreshProjections(transaction domain.RevisionTransaction, changedTable string, trigger domain.ChangeEvent) ([]domain.ChangeEventRef, error) {
	var table string
	switch changedTable {
	case "bills", "stored_value_adjustments":
		table = "stored_value_accounts"
	case "reminder_completions":
		table = "reminders"
	default:
		return nil, nil
	}
	rows, err := transaction.AllRows(table, false)
	if err != nil {
		return nil, err
	}
	events := []domain.ChangeEventRef{}
	for _, row := range rows {
		snapshot, err := projectSnapshot(transaction, table, row.ID, row.Snapshot)
		if err != nil {
			return nil, err
		}
		if EqualSnapshots(snapshot, row.Snapshot) {
			continue
		}
		item := domain.PushItem{
			Table: table, ID: row.ID, Snapshot: snapshot, UpdatedAtMs: row.UpdatedAtMs,
			DeviceID: row.DeviceID, Operation: domain.OpUpdate, DataVersion: 2,
		}
		written, err := transaction.WriteRow(item)
		if err != nil {
			return nil, err
		}
		event := BuildChangeEvent(item, row, written, "system")
		event.OperationID, event.MutationID = trigger.OperationID, trigger.MutationID
		event.TriggerEventID = &trigger.EventID
		event.ModifiedAtMs, event.OriginalSource = nil, nil
		event, err = transaction.WriteChangeEvent(event)
		if err != nil {
			return nil, err
		}
		events = append(events, EventRef(event))
	}
	return events, nil
}
