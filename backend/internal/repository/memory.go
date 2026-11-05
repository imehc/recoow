package repository

import (
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"maps"
	"sync"
	"time"
)

// memoryStore 是 domain.Store 的纯内存实现（供测试与开发使用），不持久化。
// 所有数据集按 account_id 分区，语义与 PostgreSQL 实现一致。
type memoryStore struct {
	accountLimit int
	pairings     map[string]pairingRecord
	users        map[string]domain.User
	sessions     map[string]domain.Session
	mu           sync.Mutex
	accounts     map[string]*domain.Account
	devices      map[string]domain.Device
	rows         map[string]domain.Row
	media        map[string]domain.MediaObject
	log          []domain.Change
	generations  map[string]string
	receipts     map[string]domain.MutationReceipt
	tables       map[string]bool
}

// NewMemoryStore 返回不做持久化的 domain.Store（供测试 / 开发用）。
func NewMemoryStore() domain.Store {
	return &memoryStore{
		pairings: map[string]pairingRecord{},
		users:    map[string]domain.User{}, sessions: map[string]domain.Session{},
		accounts: map[string]*domain.Account{}, devices: map[string]domain.Device{},
		rows: map[string]domain.Row{}, media: map[string]domain.MediaObject{},
		generations: map[string]string{}, receipts: map[string]domain.MutationReceipt{},
		log: []domain.Change{}, tables: maps.Clone(domain.SyncableTables),
	}
}

func rowKey(accountID, table, id string) string {
	return accountID + "\x00" + table + "\x00" + id
}

func mediaKey(accountID, key string) string { return accountID + "\x00" + key }

func receiptKey(accountID, mutationID string) string {
	return accountID + "\x00" + mutationID
}

// ---- 账号与设备 ----

func (s *memoryStore) InitAccount(recoveryKeyHash []byte) (domain.Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.accountLimit > 0 && len(s.accounts) >= s.accountLimit {
		return domain.Account{}, domain.ErrAccountQuota
	}
	for _, existing := range s.accounts {
		if string(existing.RecoveryKeyHash) == string(recoveryKeyHash) {
			return domain.Account{}, domain.ErrRecoveryKeyTaken
		}
	}
	a := domain.Account{
		ID:              newID(),
		RecoveryKeyHash: recoveryKeyHash,
		CreatedAt:       time.Now().UTC(),
		NextVersion:     1,
	}
	s.accounts[a.ID] = &a
	s.generations[a.ID] = newID()
	return a, nil
}

func (s *memoryStore) GetAccount(id string) (domain.Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.accounts[id]
	if !ok {
		return domain.Account{}, domain.ErrNoAccount
	}
	return *a, nil
}

func (s *memoryStore) GetAccountByRecoveryKeyHash(recoveryKeyHash []byte) (domain.Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.accounts {
		if string(a.RecoveryKeyHash) == string(recoveryKeyHash) {
			return *a, nil
		}
	}
	return domain.Account{}, domain.ErrNoAccount
}

func (s *memoryStore) CountAccounts() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.accounts), nil
}

func (s *memoryStore) CreateDevice(accountID, name string, tokenHash []byte, isOwner bool) (domain.Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return domain.Device{}, domain.ErrNoAccount
	}
	for _, d := range s.devices {
		if string(d.TokenHash) == string(tokenHash) {
			return domain.Device{}, domain.ErrDeviceExists
		}
	}
	now := time.Now().UTC()
	d := domain.Device{
		ID:         newID(),
		AccountID:  accountID,
		Name:       name,
		TokenHash:  tokenHash,
		IsOwner:    isOwner,
		CreatedAt:  now,
		LastSeenAt: now,
	}
	s.devices[d.ID] = d
	return d, nil
}

func (s *memoryStore) GetDeviceByTokenHash(tokenHash []byte) (domain.Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if string(d.TokenHash) == string(tokenHash) {
			return d, nil
		}
	}
	return domain.Device{}, domain.ErrNotFound
}

func (s *memoryStore) GetDevice(id string) (domain.Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[id]
	if !ok {
		return domain.Device{}, domain.ErrNotFound
	}
	return d, nil
}

func (s *memoryStore) ListDevices(accountID string) ([]domain.Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]domain.Device, 0, len(s.devices))
	for _, d := range s.devices {
		if d.AccountID == accountID {
			out = append(out, d)
		}
	}
	return out, nil
}

func (s *memoryStore) DeleteDevice(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.devices[id]; !ok {
		return domain.ErrNotFound
	}
	delete(s.devices, id)
	return nil
}

func (s *memoryStore) TouchDeviceLastSeen(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[id]
	if !ok {
		return domain.ErrNotFound
	}
	d.LastSeenAt = time.Now().UTC()
	s.devices[id] = d
	return nil
}

// ---- 同步 ----

func (s *memoryStore) Push(accountID string, items []domain.PushItem) ([]domain.PushResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	account, ok := s.accounts[accountID]
	if !ok {
		return nil, domain.ErrNoAccount
	}
	results := make([]domain.PushResult, 0, len(items))
	backup := s.revisionBackup()
	for _, it := range items {
		var res domain.PushResult
		if it.MutationID != "" {
			var err error
			res, err = service.ApplyRevision(memoryRevision{store: s, accountID: accountID}, it)
			if err != nil {
				s.restoreRevisionBackup(backup)
				return nil, err
			}
		} else {
			res = s.pushOneLocked(account, it)
		}
		results = append(results, res)
	}
	return results, nil
}

func (s *memoryStore) pushOneLocked(account *domain.Account, it domain.PushItem) domain.PushResult {
	key := rowKey(account.ID, it.Table, it.ID)
	cur, exists := s.rows[key]
	if !s.tables[it.Table] {
		return service.RejectedPushResult(it, domain.PushUnsupportedTable)
	}
	if it.Operation != domain.OpInsert && it.Operation != domain.OpUpdate && it.Operation != domain.OpDelete {
		return service.RejectedPushResult(it, domain.PushInvalidOperation)
	}

	if exists && !service.CandidateWins(
		it.UpdatedAtMs,
		cur.UpdatedAtMs,
		it.DeviceID,
		cur.DeviceID,
	) {
		return service.UnchangedPushResult(it, cur)
	}
	return s.acceptLocked(account, it, it.Operation == domain.OpDelete)
}

func (s *memoryStore) acceptLocked(account *domain.Account, it domain.PushItem, deleted bool) domain.PushResult {
	version := account.NextVersion
	account.NextVersion++

	row := domain.Row{
		AccountID:   account.ID,
		Table:       it.Table,
		ID:          it.ID,
		UpdatedAtMs: it.UpdatedAtMs,
		DeviceID:    it.DeviceID,
		Version:     version,
	}
	if deleted {
		row.DeletedAtMs = it.UpdatedAtMs
	} else {
		row.Snapshot = it.Snapshot
	}
	s.rows[rowKey(account.ID, it.Table, it.ID)] = row

	op := it.Operation
	if op != domain.OpDelete {
		op = domain.OpUpdate
	}
	ch := domain.Change{
		AccountID:   account.ID,
		Version:     version,
		Table:       it.Table,
		ID:          it.ID,
		Operation:   op,
		UpdatedAtMs: it.UpdatedAtMs,
		DeviceID:    it.DeviceID,
		Deleted:     deleted,
		Snapshot:    row.Snapshot,
	}
	if deleted {
		ch.DeletedAtMs = it.UpdatedAtMs
	}
	s.log = append(s.log, ch)
	return domain.PushResult{
		Table: it.Table, ID: it.ID, Applied: true, Operation: it.Operation,
		Version: version, Status: domain.PushApplied,
	}
}

func (s *memoryStore) Pull(accountID string, cursor int64, limit int) ([]domain.Change, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return nil, 0, domain.ErrNoAccount
	}
	if limit <= 0 {
		limit = 500
	}
	out := make([]domain.Change, 0, limit)
	next := cursor
	for _, ch := range s.log {
		if ch.AccountID != accountID || ch.Version <= cursor {
			continue
		}
		out = append(out, ch)
		next = ch.Version
		if len(out) >= limit {
			break
		}
	}
	return out, next, nil
}

func (s *memoryStore) Rows(accountID, table string) ([]domain.Row, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return nil, domain.ErrNoAccount
	}
	if !s.tables[table] {
		return nil, domain.ErrNotFound
	}
	var out []domain.Row
	for _, r := range s.rows {
		if r.AccountID == accountID && r.Table == table && r.DeletedAtMs == 0 {
			out = append(out, r)
		}
	}
	return out, nil
}

// ---- 媒体 ----

func (s *memoryStore) PutMedia(accountID, key, checksum, contentType string, size int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return domain.ErrNoAccount
	}
	if key == "" || checksum == "" {
		return fmt.Errorf("key and checksum are required")
	}
	if immutableMediaKey(key) && key != "sync/"+checksum {
		return domain.ErrConflict
	}
	if previous, exists := s.media[mediaKey(accountID, key)]; exists && previous.Checksum != checksum {
		return domain.ErrConflict
	}
	s.media[mediaKey(accountID, key)] = domain.MediaObject{
		AccountID:   accountID,
		Key:         key,
		Checksum:    checksum,
		Size:        size,
		ContentType: contentType,
		CreatedAt:   time.Now().UTC(),
	}
	return nil
}

func (s *memoryStore) GetMedia(accountID, key string) (domain.MediaObject, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return domain.MediaObject{}, domain.ErrNoAccount
	}
	m, ok := s.media[mediaKey(accountID, key)]
	if !ok {
		return domain.MediaObject{}, domain.ErrNotFound
	}
	return m, nil
}

func (s *memoryStore) DeleteMedia(accountID, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return domain.ErrNoAccount
	}
	for _, change := range s.log {
		if change.AccountID != accountID {
			continue
		}
		if snapshotReferencesMedia(change.Snapshot, key) {
			return domain.ErrConflict
		}
	}
	if _, ok := s.media[mediaKey(accountID, key)]; !ok {
		return domain.ErrNotFound
	}
	delete(s.media, mediaKey(accountID, key))
	return nil
}

func (s *memoryStore) MediaPath(accountID, checksum string) string {
	return ""
}

func (s *memoryStore) Save() error { return nil }

func (s *memoryStore) Close() error { return nil }
