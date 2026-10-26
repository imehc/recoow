package store

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"maps"
	"sync"
	"time"
)

// memoryStore 是 Store 的纯内存实现（供测试与开发使用），不持久化。
// 所有数据集按 account_id 分区，语义与 SQLite 实现一致。
type memoryStore struct {
	mu          sync.Mutex
	accounts    map[string]*Account
	devices     map[string]Device
	rows        map[string]Row
	media       map[string]MediaObject
	log         []Change
	generations map[string]string
	receipts    map[string]mutationReceipt
	tables      map[string]bool
}

// NewMemoryStore 返回不做持久化的 Store（供测试 / 开发用）。
func NewMemoryStore() Store {
	return &memoryStore{
		accounts: map[string]*Account{}, devices: map[string]Device{},
		rows: map[string]Row{}, media: map[string]MediaObject{},
		generations: map[string]string{}, receipts: map[string]mutationReceipt{},
		log: []Change{}, tables: maps.Clone(SyncableTables),
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

func (s *memoryStore) InitAccount(recoveryKeyHash []byte) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, existing := range s.accounts {
		if string(existing.RecoveryKeyHash) == string(recoveryKeyHash) {
			return Account{}, ErrRecoveryKeyTaken
		}
	}
	a := Account{
		ID:              newID(),
		RecoveryKeyHash: recoveryKeyHash,
		CreatedAt:       time.Now().UTC(),
		NextVersion:     1,
	}
	s.accounts[a.ID] = &a
	s.generations[a.ID] = newID()
	return a, nil
}

func (s *memoryStore) GetAccount(id string) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.accounts[id]
	if !ok {
		return Account{}, ErrNoAccount
	}
	return *a, nil
}

func (s *memoryStore) GetAccountByRecoveryKeyHash(recoveryKeyHash []byte) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.accounts {
		if string(a.RecoveryKeyHash) == string(recoveryKeyHash) {
			return *a, nil
		}
	}
	return Account{}, ErrNoAccount
}

func (s *memoryStore) CountAccounts() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.accounts), nil
}

func (s *memoryStore) CreateDevice(accountID, name string, tokenHash []byte, isOwner bool) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return Device{}, ErrNoAccount
	}
	for _, d := range s.devices {
		if string(d.TokenHash) == string(tokenHash) {
			return Device{}, ErrDeviceExists
		}
	}
	now := time.Now().UTC()
	d := Device{
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

func (s *memoryStore) GetDeviceByTokenHash(tokenHash []byte) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if string(d.TokenHash) == string(tokenHash) {
			return d, nil
		}
	}
	return Device{}, ErrNotFound
}

func (s *memoryStore) GetDevice(id string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[id]
	if !ok {
		return Device{}, ErrNotFound
	}
	return d, nil
}

func (s *memoryStore) ListDevices(accountID string) ([]Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, 0, len(s.devices))
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
		return ErrNotFound
	}
	delete(s.devices, id)
	return nil
}

func (s *memoryStore) TouchDeviceLastSeen(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[id]
	if !ok {
		return ErrNotFound
	}
	d.LastSeenAt = time.Now().UTC()
	s.devices[id] = d
	return nil
}

// ---- 同步 ----

func (s *memoryStore) Push(accountID string, items []PushItem) ([]PushResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	account, ok := s.accounts[accountID]
	if !ok {
		return nil, ErrNoAccount
	}
	results := make([]PushResult, 0, len(items))
	backup := s.revisionBackup()
	for _, it := range items {
		var res PushResult
		if it.MutationID != "" {
			var err error
			res, err = applyRevision(memoryRevision{store: s, accountID: accountID}, it)
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

func (s *memoryStore) pushOneLocked(account *Account, it PushItem) PushResult {
	key := rowKey(account.ID, it.Table, it.ID)
	cur, exists := s.rows[key]
	if !s.tables[it.Table] {
		return rejectedPushResult(it, PushUnsupportedTable)
	}
	if it.Operation != OpInsert && it.Operation != OpUpdate && it.Operation != OpDelete {
		return rejectedPushResult(it, PushInvalidOperation)
	}

	if exists && !candidateWins(
		it.UpdatedAtMs,
		cur.UpdatedAtMs,
		it.DeviceID,
		cur.DeviceID,
	) {
		return unchangedPushResult(it, cur)
	}
	return s.acceptLocked(account, it, it.Operation == OpDelete)
}

func (s *memoryStore) acceptLocked(account *Account, it PushItem, deleted bool) PushResult {
	version := account.NextVersion
	account.NextVersion++

	row := Row{
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
	if op != OpDelete {
		op = OpUpdate
	}
	ch := Change{
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
	return PushResult{
		Table: it.Table, ID: it.ID, Applied: true, Operation: it.Operation,
		Version: version, Status: PushApplied,
	}
}

func (s *memoryStore) Pull(accountID string, cursor int64, limit int) ([]Change, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return nil, 0, ErrNoAccount
	}
	if limit <= 0 {
		limit = 500
	}
	out := make([]Change, 0, limit)
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

func (s *memoryStore) Rows(accountID, table string) ([]Row, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return nil, ErrNoAccount
	}
	if !s.tables[table] {
		return nil, ErrNotFound
	}
	var out []Row
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
		return ErrNoAccount
	}
	if key == "" || checksum == "" {
		return fmt.Errorf("key and checksum are required")
	}
	if immutableMediaKey(key) && key != "sync/"+checksum {
		return ErrConflict
	}
	if previous, exists := s.media[mediaKey(accountID, key)]; exists && previous.Checksum != checksum {
		return ErrConflict
	}
	s.media[mediaKey(accountID, key)] = MediaObject{
		AccountID:   accountID,
		Key:         key,
		Checksum:    checksum,
		Size:        size,
		ContentType: contentType,
		CreatedAt:   time.Now().UTC(),
	}
	return nil
}

func (s *memoryStore) GetMedia(accountID, key string) (MediaObject, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return MediaObject{}, ErrNoAccount
	}
	m, ok := s.media[mediaKey(accountID, key)]
	if !ok {
		return MediaObject{}, ErrNotFound
	}
	return m, nil
}

func (s *memoryStore) DeleteMedia(accountID, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.accounts[accountID]; !ok {
		return ErrNoAccount
	}
	for _, change := range s.log {
		if change.AccountID != accountID {
			continue
		}
		if snapshotReferencesMedia(change.Snapshot, key) {
			return ErrConflict
		}
	}
	if _, ok := s.media[mediaKey(accountID, key)]; !ok {
		return ErrNotFound
	}
	delete(s.media, mediaKey(accountID, key))
	return nil
}

func (s *memoryStore) MediaPath(accountID, checksum string) string {
	return ""
}

func (s *memoryStore) Save() error { return nil }

func (s *memoryStore) Close() error { return nil }

// ExportSQL 把指定账号的全部状态渲染为可移植 SQL（与 SQLite 实现同格式）。
func (s *memoryStore) ExportSQL(w io.Writer, accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	account, ok := s.accounts[accountID]
	if !ok {
		return ErrNoAccount
	}
	d := dumpData{Account: account}
	d.Dataset = &DatasetState{Generation: s.generations[accountID], Head: account.NextVersion - 1}
	for _, receipt := range s.receipts {
		if receipt.AccountID == accountID {
			d.Receipts = append(d.Receipts, receipt)
		}
	}
	for _, dv := range s.devices {
		if dv.AccountID == accountID {
			d.Devices = append(d.Devices, dv)
		}
	}
	for _, r := range s.rows {
		if r.AccountID == accountID {
			d.Rows = append(d.Rows, r)
		}
	}
	for _, change := range s.log {
		if change.AccountID == accountID {
			d.Log = append(d.Log, change)
		}
	}
	for _, m := range s.media {
		if m.AccountID == accountID {
			d.Media = append(d.Media, m)
		}
	}
	return writeSQLDump(w, d)
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
