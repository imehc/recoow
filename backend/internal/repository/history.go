package repository

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
)

const eventColumns = "event_id, account_id, generation, version, previous_version, table_name, id, title, operation, method, operation_id, trigger_event_id, mutation_id, modified_at_ms, accepted_at_ms, original_source, submission"

func cloneEvent(event domain.ChangeEvent) domain.ChangeEvent {
	if event.OriginalSource != nil {
		copy := *event.OriginalSource
		event.OriginalSource = &copy
	}
	if event.Submission != nil {
		copy := *event.Submission
		event.Submission = &copy
	}
	if event.ModifiedAtMs != nil {
		copy := *event.ModifiedAtMs
		event.ModifiedAtMs = &copy
	}
	if event.AcceptedAtMs != nil {
		copy := *event.AcceptedAtMs
		event.AcceptedAtMs = &copy
	}
	if event.TriggerEventID != nil {
		copy := *event.TriggerEventID
		event.TriggerEventID = &copy
	}
	return event
}

func storedEvent(event domain.ChangeEvent, accountID, generation string, submission *domain.SubmissionSource, directEdit bool) (domain.ChangeEvent, error) {
	if submission != nil {
		if err := service.ValidateEnvironment(submission.ClientEnvironment); err != nil {
			return event, err
		}
	}
	event.AccountID, event.Generation, event.EventID = accountID, generation, newID()
	if event.OperationID == "" {
		event.OperationID = event.EventID
	}
	accepted := time.Now().UTC().UnixMilli()
	event.AcceptedAtMs, event.Submission = &accepted, submission
	if directEdit && submission != nil && event.Method != "system" {
		event.OriginalSource = &domain.ChangeOrigin{ClientEnvironment: submission.ClientEnvironment, DeviceID: submission.DeviceID, DeviceName: submission.DeviceName}
	}
	return cloneEvent(event), nil
}

func (adapter memoryRevision) WriteChangeEvent(event domain.ChangeEvent) (domain.ChangeEvent, error) {
	event, err := storedEvent(event, adapter.accountID, adapter.store.generations[adapter.accountID], adapter.submission, adapter.directEdit)
	if err == nil {
		adapter.store.events = append(adapter.store.events, event)
	}
	return cloneEvent(event), err
}

func eventValues(event domain.ChangeEvent) ([]any, error) {
	var original, submission any
	if event.OriginalSource != nil {
		raw, err := json.Marshal(event.OriginalSource)
		if err != nil {
			return nil, err
		}
		original = string(raw)
	}
	if event.Submission != nil {
		raw, err := json.Marshal(event.Submission)
		if err != nil {
			return nil, err
		}
		submission = string(raw)
	}
	var mutation any
	if event.MutationID != "" {
		mutation = event.MutationID
	}
	return []any{event.EventID, event.AccountID, event.Generation, event.Version, event.PreviousVersion, event.Table, event.ID,
		event.Title, event.Operation, event.Method, event.OperationID, event.TriggerEventID, mutation,
		event.ModifiedAtMs, event.AcceptedAtMs, original, submission}, nil
}

func (adapter postgresRevision) WriteChangeEvent(event domain.ChangeEvent) (domain.ChangeEvent, error) {
	event, err := storedEvent(event, adapter.accountID, adapter.generation, adapter.submission, adapter.directEdit)
	if err != nil {
		return event, err
	}
	values, err := eventValues(event)
	if err != nil {
		return event, err
	}
	_, err = adapter.transaction.Exec("INSERT INTO change_events ("+eventColumns+") VALUES ("+strings.TrimSuffix(strings.Repeat("?,", len(values)), ",")+")", values...)
	return event, err
}

func scanEvent(scanner rowScanner) (domain.ChangeEvent, error) {
	var event domain.ChangeEvent
	var trigger, mutation sql.NullString
	var modified, accepted sql.NullInt64
	var original, submission []byte
	err := scanner.Scan(&event.EventID, &event.AccountID, &event.Generation, &event.Version, &event.PreviousVersion,
		&event.Table, &event.ID, &event.Title, &event.Operation, &event.Method, &event.OperationID, &trigger, &mutation,
		&modified, &accepted, &original, &submission)
	if err != nil {
		return event, err
	}
	if trigger.Valid {
		event.TriggerEventID = &trigger.String
	}
	event.MutationID = mutation.String
	if modified.Valid {
		event.ModifiedAtMs = &modified.Int64
	}
	if accepted.Valid {
		event.AcceptedAtMs = &accepted.Int64
	}
	if len(original) > 0 {
		if err := json.Unmarshal(original, &event.OriginalSource); err != nil {
			return event, err
		}
	}
	if len(submission) > 0 {
		if err := json.Unmarshal(submission, &event.Submission); err != nil {
			return event, err
		}
	}
	return event, nil
}

func eventQueryState(state domain.DatasetState, query domain.ChangeEventQuery) (int64, error) {
	watermark := state.Head
	if query.Watermark != nil {
		watermark = *query.Watermark
	}
	if query.Generation != "" && query.Generation != state.Generation || watermark < 0 || watermark > state.Head || query.Before < 0 || query.Before > watermark && query.Before != 0 {
		return 0, domain.ErrResetRequired
	}
	if query.Limit < 1 || query.Limit > 200 {
		return 0, fmt.Errorf("invalid history limit")
	}
	return watermark, nil
}

func matchesEvent(event domain.ChangeEvent, query domain.ChangeEventQuery, watermark int64) bool {
	return event.Version <= watermark && (query.Before == 0 || event.Version < query.Before) &&
		(query.Table == "" || query.Table == event.Table) && (query.ID == "" || query.ID == event.ID) &&
		(query.Operation == "" || query.Operation == event.Operation) &&
		(query.Client == "" || event.OriginalSource != nil && event.OriginalSource.Client == query.Client) &&
		(query.DeviceID == "" || event.Submission != nil && event.Submission.DeviceID == query.DeviceID) &&
		(query.FromMs == 0 && query.ToMs == 0 || event.AcceptedAtMs != nil &&
			*event.AcceptedAtMs >= query.FromMs && (query.ToMs == 0 || *event.AcceptedAtMs < query.ToMs))
}

func (storage *memoryStore) ChangeEvents(accountID string, query domain.ChangeEventQuery) (domain.ChangeEventPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	account, ok := storage.accounts[accountID]
	if !ok {
		return domain.ChangeEventPage{}, domain.ErrNoAccount
	}
	state := domain.DatasetState{Generation: storage.generations[accountID], Head: account.NextVersion - 1}
	watermark, err := eventQueryState(state, query)
	if err != nil {
		return domain.ChangeEventPage{}, err
	}
	page := domain.ChangeEventPage{Events: []domain.ChangeEvent{}, Generation: state.Generation, Watermark: watermark}
	for _, event := range storage.events {
		if event.AccountID == accountID && event.Generation == state.Generation && matchesEvent(event, query, watermark) {
			page.Events = append(page.Events, cloneEvent(event))
		}
	}
	sort.Slice(page.Events, func(i, j int) bool { return page.Events[i].Version > page.Events[j].Version })
	if len(page.Events) > query.Limit {
		page.More = true
		page.Events = page.Events[:query.Limit]
	}
	if len(page.Events) > 0 {
		page.Before = page.Events[len(page.Events)-1].Version
	}
	return page, nil
}

func lockHistoryState(storage *postgresStore, tx *storeTx, accountID string) (domain.DatasetState, error) {
	var state domain.DatasetState
	err := tx.QueryRow("SELECT generation FROM sync_metadata WHERE account_id = ? FOR SHARE", accountID).Scan(&state.Generation)
	if err == nil {
		err = tx.QueryRow("SELECT next_version - 1 FROM account WHERE id = ?", accountID).Scan(&state.Head)
	}
	return state, err
}

func (storage *postgresStore) ChangeEvents(accountID string, query domain.ChangeEventQuery) (domain.ChangeEventPage, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return domain.ChangeEventPage{}, err
	}
	defer tx.Rollback()
	state, err := lockHistoryState(storage, tx, accountID)
	if err != nil {
		return domain.ChangeEventPage{}, err
	}
	watermark, err := eventQueryState(state, query)
	if err != nil {
		return domain.ChangeEventPage{}, err
	}
	where := []string{"account_id = ?", "generation = ?", "version <= ?"}
	args := []any{accountID, state.Generation, watermark}
	if query.Before > 0 {
		where = append(where, "version < ?")
		args = append(args, query.Before)
	}
	for _, filter := range []struct{ column, value string }{{"table_name", query.Table}, {"id", query.ID}, {"operation", query.Operation}, {"original_source->>'client'", query.Client}, {"submission->>'deviceId'", query.DeviceID}} {
		if filter.value != "" {
			where = append(where, filter.column+" = ?")
			args = append(args, filter.value)
		}
	}
	if query.FromMs > 0 {
		where = append(where, "accepted_at_ms >= ?")
		args = append(args, query.FromMs)
	}
	if query.ToMs > 0 {
		where = append(where, "accepted_at_ms < ?")
		args = append(args, query.ToMs)
	}
	args = append(args, query.Limit+1)
	rows, err := tx.Query("SELECT "+eventColumns+" FROM change_events WHERE "+strings.Join(where, " AND ")+" ORDER BY version DESC LIMIT ?", args...)
	if err != nil {
		return domain.ChangeEventPage{}, err
	}
	defer rows.Close()
	page := domain.ChangeEventPage{Events: []domain.ChangeEvent{}, Generation: state.Generation, Watermark: watermark}
	for rows.Next() {
		if len(page.Events) == query.Limit {
			page.More = true
			break
		}
		event, err := scanEvent(rows)
		if err != nil {
			return page, err
		}
		page.Events = append(page.Events, event)
		page.Before = event.Version
	}
	return page, rows.Err()
}

func historyRow(changes []domain.Change, accountID, table, id string, version int64) (domain.Row, bool) {
	for _, change := range changes {
		if change.AccountID == accountID && change.Table == table && change.ID == id && change.Version == version {
			return domain.Row{AccountID: accountID, Table: table, ID: id, Version: version, DataVersion: change.DataVersion,
				Snapshot: bytes.Clone(change.Snapshot), UpdatedAtMs: change.UpdatedAtMs, DeviceID: change.DeviceID, DeletedAtMs: change.DeletedAtMs}, true
		}
	}
	return domain.Row{}, false
}

func (storage *memoryStore) ChangeEvent(accountID, eventID string) (domain.ChangeEventDetail, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	var detail domain.ChangeEventDetail
	for _, event := range storage.events {
		if event.AccountID == accountID && event.EventID == eventID && event.Generation == storage.generations[accountID] {
			detail.Event = cloneEvent(event)
			break
		}
	}
	if detail.Event.EventID == "" {
		return detail, domain.ErrNotFound
	}
	event := detail.Event
	var found bool
	detail.After, found = historyRow(storage.log, accountID, event.Table, event.ID, event.Version)
	if !found {
		return detail, domain.ErrNotFound
	}
	if event.PreviousVersion > 0 {
		previous, found := historyRow(storage.log, accountID, event.Table, event.ID, event.PreviousVersion)
		if !found {
			return detail, domain.ErrNotFound
		}
		detail.Before = &previous
	}
	detail.Related = []domain.ChangeEvent{}
	for _, related := range storage.events {
		if related.AccountID == accountID && related.Generation == event.Generation && related.OperationID == event.OperationID && related.EventID != eventID {
			detail.Related = append(detail.Related, cloneEvent(related))
		}
	}
	sort.Slice(detail.Related, func(i, j int) bool { return detail.Related[i].Version < detail.Related[j].Version })
	return detail, nil
}

func (storage *postgresStore) ChangeEvent(accountID, eventID string) (domain.ChangeEventDetail, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	var detail domain.ChangeEventDetail
	tx, err := storage.beginAccount(accountID)
	if err != nil {
		return detail, err
	}
	defer tx.Rollback()
	state, err := lockHistoryState(storage, tx, accountID)
	if err != nil {
		return detail, err
	}
	detail.Event, err = scanEvent(tx.QueryRow("SELECT "+eventColumns+" FROM change_events WHERE account_id = ? AND generation = ? AND event_id = ?", accountID, state.Generation, eventID))
	if errors.Is(err, sql.ErrNoRows) {
		return detail, domain.ErrNotFound
	}
	if err != nil {
		return detail, err
	}
	adapter := postgresRevision{store: storage, transaction: tx, accountID: accountID}
	event := detail.Event
	var found bool
	detail.After, found, err = adapter.ReadRevision(event.Table, event.ID, event.Version)
	if err != nil {
		return detail, err
	}
	if !found {
		return detail, domain.ErrNotFound
	}
	if event.PreviousVersion > 0 {
		previous, found, err := adapter.ReadRevision(event.Table, event.ID, event.PreviousVersion)
		if err != nil {
			return detail, err
		}
		if !found {
			return detail, domain.ErrNotFound
		}
		detail.Before = &previous
	}
	rows, err := tx.Query("SELECT "+eventColumns+" FROM change_events WHERE account_id = ? AND generation = ? AND operation_id = ? AND event_id <> ? ORDER BY version", accountID, event.Generation, event.OperationID, eventID)
	if err != nil {
		return detail, err
	}
	defer rows.Close()
	detail.Related = []domain.ChangeEvent{}
	for rows.Next() {
		related, err := scanEvent(rows)
		if err != nil {
			return detail, err
		}
		detail.Related = append(detail.Related, related)
	}
	return detail, rows.Err()
}

func (storage *postgresStore) scanChangeEvents(data *dumpData, tx *storeTx, accountID string) error {
	rows, err := tx.Query("SELECT "+eventColumns+" FROM change_events WHERE account_id = ? ORDER BY version", accountID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		event, err := scanEvent(rows)
		if err != nil {
			return err
		}
		data.Events = append(data.Events, event)
	}
	return rows.Err()
}
