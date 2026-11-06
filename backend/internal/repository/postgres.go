// PostgreSQL 保存结构化业务数据、同步修订及身份凭据，媒体字节按内容寻址落盘。
package repository

import (
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"github.com/imehc/recoow/backend/internal/schema"
	"github.com/imehc/recoow/backend/internal/service"
	"io"
	"path/filepath"
	"sync"
	"time"
)

// postgresStore 是 domain.Store 的 PostgreSQL 实现。所有方法在进程内互斥锁下执行，
// 保证与内存实现一致的串行语义；多语句批量操作（Push）使用单个事务，
// 要么整体成功提交，要么整体回滚。
type postgresStore struct {
	accountLimit int
	registry     *schema.Registry
	mu           sync.Mutex
	ctx          context.Context
	db           *storeDB
	mediaDir     string // 媒体字节目录 <dir>/media
	tables       map[string]bool
}

// querier 抽象 *sql.DB 与 *sql.Tx 的查询能力，供内部助手复用。
type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// requireAccountLocked 校验账号存在，否则返回 domain.ErrNoAccount。
func requireAccountLocked(q querier, ctx context.Context, accountID string) error {
	var one int
	err := q.QueryRowContext(ctx, "SELECT 1 FROM account WHERE id = ?", accountID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.ErrNoAccount
	}
	if err != nil {
		return fmt.Errorf("读取账号: %w", err)
	}
	return nil
}

func parseRFC3339(s string) (time.Time, error) {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, err
	}
	return t, nil
}

// ---- 账号 ----

// InitAccount 新建一个账号：同一实例允许多个账号并存，各自拥有独立的
// 行镜像、变更日志、媒体索引与版本号空间。
func (s *postgresStore) InitAccount(recoveryKeyHash []byte) (domain.Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.BeginTx(s.ctx, nil)
	if err != nil {
		return domain.Account{}, fmt.Errorf("开启事务: %w", err)
	}
	defer tx.Rollback()
	if err := s.checkAccountQuota(tx); err != nil {
		return domain.Account{}, err
	}

	a := domain.Account{
		ID:              newID(),
		RecoveryKeyHash: recoveryKeyHash,
		CreatedAt:       time.Now().UTC(),
		NextVersion:     1,
	}
	queries := sqlgen.New(tx.Tx)
	if err := queries.SetAccountContext(s.ctx, a.ID); err != nil {
		return domain.Account{}, err
	}
	if err := queries.CreateAccount(s.ctx, sqlgen.CreateAccountParams{ID: a.ID, RecoveryKeyHash: hexHash(recoveryKeyHash), CreatedAt: rfc3339(a.CreatedAt), NextVersion: a.NextVersion}); err != nil {
		if errors.Is(identityError(err), domain.ErrConflict) {
			return domain.Account{}, domain.ErrRecoveryKeyTaken
		}
		return domain.Account{}, fmt.Errorf("写入账号: %w", err)
	}
	if err := queries.InitializeSyncMetadata(s.ctx, sqlgen.InitializeSyncMetadataParams{AccountID: a.ID, Generation: newID()}); err != nil {
		return domain.Account{}, fmt.Errorf("写入同步元数据: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return domain.Account{}, fmt.Errorf("提交事务: %w", err)
	}
	return a, nil
}

func accountModel(record sqlgen.Account, err error) (domain.Account, error) {
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Account{}, domain.ErrNoAccount
	}
	if err != nil {
		return domain.Account{}, err
	}
	hash, err := hex.DecodeString(record.RecoveryKeyHash)
	if err != nil {
		return domain.Account{}, err
	}
	created, err := parseRFC3339(record.CreatedAt)
	return domain.Account{ID: record.ID, RecoveryKeyHash: hash, CreatedAt: created, NextVersion: record.NextVersion}, err
}
func (s *postgresStore) GetAccount(id string) (domain.Account, error) {
	return accountModel(sqlgen.New(s.db.DB).GetAccount(s.ctx, id))
}
func (s *postgresStore) GetAccountByRecoveryKeyHash(hash []byte) (domain.Account, error) {
	return accountModel(sqlgen.New(s.db.DB).GetAccountByRecoveryHash(s.ctx, hexHash(hash)))
}

func (s *postgresStore) CountAccounts() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	count, err := sqlgen.New(s.db.DB).CountAccounts(s.ctx)
	if err != nil {
		return 0, fmt.Errorf("统计账号: %w", err)
	}
	return int(count), nil
}

// ---- 设备 ----

// rowScanner 抽象 sql.Row 与 sql.Rows 的 Scan。
type rowScanner interface{ Scan(dest ...any) error }

func deviceModel(record sqlgen.Device, err error) (domain.Device, error) {
	if errors.Is(err, sql.ErrNoRows) {
		return domain.Device{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.Device{}, err
	}
	hash, err := hex.DecodeString(record.TokenHash)
	if err != nil {
		return domain.Device{}, err
	}
	created, err := parseRFC3339(record.CreatedAt)
	if err != nil {
		return domain.Device{}, err
	}
	seen, err := parseRFC3339(record.LastSeenAt)
	return domain.Device{ID: record.ID, AccountID: record.AccountID, Name: record.Name, TokenHash: hash, IsOwner: record.IsOwner != 0, CreatedAt: created, LastSeenAt: seen}, err
}
func (s *postgresStore) CreateDevice(accountID, name string, tokenHash []byte, owner bool) (domain.Device, error) {
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return domain.Device{}, err
	}
	now := time.Now().UTC()
	record := sqlgen.CreateDeviceParams{ID: newID(), AccountID: accountID, Name: name, TokenHash: hexHash(tokenHash), IsOwner: int64(boolInt(owner)), CreatedAt: rfc3339(now), LastSeenAt: rfc3339(now)}
	err := sqlgen.New(s.db.DB).CreateDevice(s.ctx, record)
	if errors.Is(identityError(err), domain.ErrConflict) {
		return domain.Device{}, domain.ErrDeviceExists
	}
	if err != nil {
		return domain.Device{}, err
	}
	return domain.Device{ID: record.ID, AccountID: accountID, Name: name, TokenHash: tokenHash, IsOwner: owner, CreatedAt: now, LastSeenAt: now}, nil
}
func (s *postgresStore) GetDeviceByTokenHash(hash []byte) (domain.Device, error) {
	return deviceModel(sqlgen.New(s.db.DB).GetDeviceByTokenHash(s.ctx, hexHash(hash)))
}
func (s *postgresStore) GetDevice(id string) (domain.Device, error) {
	return deviceModel(sqlgen.New(s.db.DB).GetDevice(s.ctx, id))
}
func (s *postgresStore) ListDevices(accountID string) ([]domain.Device, error) {
	records, err := sqlgen.New(s.db.DB).ListDevices(s.ctx, accountID)
	if err != nil {
		return nil, err
	}
	devices := make([]domain.Device, 0, len(records))
	for _, record := range records {
		device, err := deviceModel(record, nil)
		if err != nil {
			return nil, err
		}
		devices = append(devices, device)
	}
	return devices, nil
}
func (s *postgresStore) DeleteDevice(id string) error {
	count, err := sqlgen.New(s.db.DB).DeleteDevice(s.ctx, id)
	if err == nil && count == 0 {
		return domain.ErrNotFound
	}
	return err
}
func (s *postgresStore) TouchDeviceLastSeen(id string) error {
	count, err := sqlgen.New(s.db.DB).TouchDevice(s.ctx, sqlgen.TouchDeviceParams{ID: id, LastSeenAt: rfc3339(time.Now().UTC())})
	if err == nil && count == 0 {
		return domain.ErrNotFound
	}
	return err
}

// ---- 同步 ----

func (s *postgresStore) Push(accountID string, items []domain.PushItem) ([]domain.PushResult, error) {
	return s.PushWithContext(accountID, items, domain.PushContext{})
}

func (s *postgresStore) PushWithContext(accountID string, items []domain.PushItem, context domain.PushContext) ([]domain.PushResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.beginAccount(accountID)
	if err != nil {
		return nil, fmt.Errorf("开启事务: %w", err)
	}
	defer tx.Rollback()

	if err := requireAccountLocked(tx, s.ctx, accountID); err != nil {
		return nil, err
	}
	var generation string
	if err := tx.QueryRow("SELECT generation FROM sync_metadata WHERE account_id = ? FOR SHARE", accountID).Scan(&generation); err != nil {
		return nil, err
	}
	if context.Generation != "" && context.Generation != generation {
		return nil, domain.ErrResetRequired
	}
	if context.Submission != nil {
		var deviceAccount string
		if err := tx.QueryRow("SELECT account_id FROM devices WHERE id = ?", context.Submission.DeviceID).Scan(&deviceAccount); err != nil || deviceAccount != accountID {
			return nil, domain.ErrNotFound
		}
	}
	if _, err := sqlgen.New(tx.Tx).LockAccount(s.ctx, accountID); err != nil {
		return nil, err
	}
	results := make([]domain.PushResult, 0, len(items))
	for _, it := range items {
		adapter := postgresRevision{store: s, transaction: tx, accountID: accountID, generation: generation, submission: context.Submission, directEdit: context.DirectEdit}
		var res domain.PushResult
		var err error
		if it.MutationID != "" {
			res, err = service.ApplyRevision(adapter, it)
		} else {
			previous, _, readErr := adapter.ReadRow(it.Table, it.ID)
			if readErr != nil {
				return nil, readErr
			}
			res, err = s.pushOneTx(tx, accountID, it)
			if err == nil && res.Applied {
				written, _, readErr := adapter.ReadRow(it.Table, it.ID)
				if readErr != nil {
					return nil, readErr
				}
				var event domain.ChangeEvent
				event, err = adapter.WriteChangeEvent(service.BuildChangeEvent(it, previous, written, "normal"))
				if err == nil {
					res.Events = []domain.ChangeEventRef{service.EventRef(event)}
				}
			}
		}
		if err != nil {
			return nil, err
		}
		results = append(results, res)
	}
	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("提交事务: %w", err)
	}
	return results, nil
}

// pushOneTx 在事务内处理一条变更，语义与内存实现的 pushOneLocked 一致
// （LWW 比较、墓碑行、幂等重放、单调版本号）。
func (s *postgresStore) pushOneTx(tx *storeTx, accountID string, it domain.PushItem) (domain.PushResult, error) {
	if !s.tables[it.Table] {
		return service.RejectedPushResult(it, domain.PushUnsupportedTable), nil
	}
	if it.Operation != domain.OpInsert && it.Operation != domain.OpUpdate && it.Operation != domain.OpDelete {
		return service.RejectedPushResult(it, domain.PushInvalidOperation), nil
	}

	current := domain.Row{Table: it.Table, ID: it.ID, AccountID: accountID}
	var currentSnapshot sql.NullString
	err := tx.QueryRowContext(
		s.ctx,
		`SELECT updated_at_ms, device_id, version, deleted_at_ms, snapshot
		 FROM row_state WHERE account_id = ? AND table_name = ? AND id = ?`,
		accountID,
		it.Table,
		it.ID,
	).Scan(
		&current.UpdatedAtMs,
		&current.DeviceID,
		&current.Version,
		&current.DeletedAtMs,
		&currentSnapshot,
	)
	switch {
	case err == nil:
		if currentSnapshot.Valid {
			current.Snapshot = json.RawMessage(currentSnapshot.String)
		}
		if !service.CandidateWins(
			it.UpdatedAtMs,
			current.UpdatedAtMs,
			it.DeviceID,
			current.DeviceID,
		) {
			return service.UnchangedPushResult(it, current), nil
		}
	case errors.Is(err, sql.ErrNoRows):
		// 全新行，直接采纳。
	default:
		return domain.PushResult{}, fmt.Errorf("读取行状态: %w", err)
	}

	var nextVersion int64
	if err := tx.QueryRowContext(s.ctx, "SELECT next_version FROM account WHERE id = ?", accountID).
		Scan(&nextVersion); err != nil {
		return domain.PushResult{}, fmt.Errorf("读取账号版本号: %w", err)
	}
	deleted := it.Operation == domain.OpDelete
	deletedAt := int64(0)
	var snapshot any
	if deleted {
		deletedAt = it.UpdatedAtMs
	} else {
		snapshot = string(it.Snapshot)
	}
	op := it.Operation
	if op != domain.OpDelete {
		op = domain.OpUpdate
	}
	if _, err := tx.ExecContext(s.ctx,
		"UPDATE account SET next_version = ? WHERE id = ?", nextVersion+1, accountID); err != nil {
		return domain.PushResult{}, fmt.Errorf("推进版本号: %w", err)
	}
	if err := s.upsertBusinessRow(tx, domain.Row{AccountID: accountID, Table: it.Table, ID: it.ID, Snapshot: it.Snapshot,
		UpdatedAtMs: it.UpdatedAtMs, DeviceID: it.DeviceID, DeletedAtMs: deletedAt, Version: nextVersion, DataVersion: 1}); err != nil {
		return domain.PushResult{}, err
	}

	if _, err := tx.ExecContext(s.ctx,
		`INSERT INTO change_log (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		accountID, nextVersion, it.Table, it.ID, op, it.UpdatedAtMs, it.DeviceID, boolInt(deleted), deletedAt, snapshot); err != nil {
		return domain.PushResult{}, fmt.Errorf("写入变更日志: %w", err)
	}
	return domain.PushResult{
		Table: it.Table, ID: it.ID, Applied: true, Operation: it.Operation,
		Version: nextVersion, Status: domain.PushApplied,
	}, nil
}

func (s *postgresStore) Pull(accountID string, cursor int64, limit int) ([]domain.Change, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, beginErr := s.beginAccount(accountID)
	if beginErr != nil {
		return nil, 0, beginErr
	}
	defer tx.Rollback()

	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return nil, 0, err
	}
	if limit <= 0 {
		limit = 500
	}
	rows, err := tx.QueryContext(s.ctx,
		`SELECT version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version
		 FROM change_log WHERE account_id = ? AND version > ? ORDER BY version LIMIT ?`, accountID, cursor, limit)
	if err != nil {
		return nil, 0, fmt.Errorf("查询变更日志: %w", err)
	}
	defer rows.Close()

	out := make([]domain.Change, 0, limit)
	next := cursor
	for rows.Next() {
		var ch domain.Change
		var op string
		var deleted, deletedAt int64
		var snap sql.NullString
		if err := rows.Scan(&ch.Version, &ch.Table, &ch.ID, &op, &ch.UpdatedAtMs, &ch.DeviceID,
			&deleted, &deletedAt, &snap, &ch.DataVersion); err != nil {
			return nil, 0, fmt.Errorf("扫描变更日志: %w", err)
		}
		ch.AccountID = accountID
		ch.Operation = domain.Operation(op)
		ch.Deleted = deleted != 0
		ch.DeletedAtMs = deletedAt
		if snap.Valid && snap.String != "" {
			ch.Snapshot = json.RawMessage(snap.String)
		}
		out = append(out, ch)
		next = ch.Version
	}
	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("遍历变更日志: %w", err)
	}
	return out, next, nil
}

func (s *postgresStore) Rows(accountID, table string) ([]domain.Row, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return nil, err
	}
	if !s.tables[table] {
		return nil, domain.ErrNotFound
	}
	tx, err := s.beginAccount(accountID)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(s.ctx,
		`SELECT id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version
		 FROM row_state WHERE account_id = ? AND table_name = ? AND deleted_at_ms = 0 ORDER BY id`, accountID, table)
	if err != nil {
		return nil, fmt.Errorf("查询行状态: %w", err)
	}
	defer rows.Close()

	var out []domain.Row
	for rows.Next() {
		var r domain.Row
		var snap sql.NullString
		if err := rows.Scan(&r.ID, &snap, &r.UpdatedAtMs, &r.DeviceID, &r.DeletedAtMs, &r.Version, &r.DataVersion); err != nil {
			return nil, fmt.Errorf("扫描行状态: %w", err)
		}
		r.Table = table
		r.AccountID = accountID
		if snap.Valid && snap.String != "" {
			r.Snapshot = json.RawMessage(snap.String)
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历行状态: %w", err)
	}
	return out, nil
}

// ---- 媒体 ----

func (s *postgresStore) PutMedia(accountID, key, checksum, contentType string, size int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, beginErr := s.beginAccount(accountID)
	if beginErr != nil {
		return beginErr
	}
	defer tx.Rollback()

	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return err
	}
	if key == "" || checksum == "" {
		return fmt.Errorf("key and checksum are required")
	}
	if immutableMediaKey(key) && key != "sync/"+checksum {
		return domain.ErrConflict
	}
	var previous string
	if err := tx.QueryRow("SELECT checksum FROM media_objects WHERE account_id = ? AND key = ?", accountID, key).Scan(&previous); err == nil && previous != checksum {
		return domain.ErrConflict
	} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if _, err := tx.ExecContext(s.ctx,
		`INSERT INTO media_objects (account_id, key, checksum, size, content_type, created_at) VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT (account_id, key) DO UPDATE SET
			checksum = excluded.checksum, size = excluded.size,
			content_type = excluded.content_type, created_at = excluded.created_at`,
		accountID, key, checksum, size, contentType, rfc3339(time.Now().UTC())); err != nil {
		return fmt.Errorf("写入媒体索引: %w", err)
	}
	return tx.Commit()
}

func (s *postgresStore) GetMedia(accountID, key string) (domain.MediaObject, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, beginErr := s.beginAccount(accountID)
	if beginErr != nil {
		return domain.MediaObject{}, beginErr
	}
	defer tx.Rollback()

	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return domain.MediaObject{}, err
	}
	var m domain.MediaObject
	var created string
	err := tx.QueryRowContext(s.ctx,
		"SELECT key, checksum, size, content_type, created_at FROM media_objects WHERE account_id = ? AND key = ?", accountID, key).
		Scan(&m.Key, &m.Checksum, &m.Size, &m.ContentType, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return domain.MediaObject{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.MediaObject{}, fmt.Errorf("读取媒体索引: %w", err)
	}
	m.AccountID = accountID
	if m.CreatedAt, err = parseRFC3339(created); err != nil {
		return domain.MediaObject{}, fmt.Errorf("created_at 解析: %w", err)
	}
	return m, nil
}

func (s *postgresStore) DeleteMedia(accountID, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, beginErr := s.beginAccount(accountID)
	if beginErr != nil {
		return beginErr
	}
	defer tx.Rollback()

	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return err
	}
	var references int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM change_log, LATERAL jsonb_each(COALESCE(change_log.snapshot::jsonb -> '_media', '{}'::jsonb)) AS media
		WHERE change_log.account_id = ? AND media.value ->> 'key' = ?`, accountID, key).Scan(&references); err != nil {
		return err
	}
	if references > 0 {
		return domain.ErrConflict
	}
	res, err := tx.ExecContext(s.ctx, "DELETE FROM media_objects WHERE account_id = ? AND key = ?", accountID, key)
	if err != nil {
		return fmt.Errorf("删除媒体索引: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return domain.ErrNotFound
	}
	return tx.Commit()
}

// MediaPath 返回某对象字节在磁盘上的落点（内容寻址，可被多个 key 共享）。
func (s *postgresStore) MediaPath(accountID, checksum string) string {
	return filepath.Join(s.mediaDir, accountID, checksum)
}

// Save 为幂等兼容保留：PostgreSQL 每次变更即时提交，无需额外落盘。
func (s *postgresStore) Save() error { return nil }

func (s *postgresStore) Close() error { return s.db.Close() }

// ExportSQL 把指定账号的全部状态导出为可移植 SQL（含建表与全部数据）。
// 多账号实例里各账号互不可见，因此导出必须以账号为界。
func (s *postgresStore) ExportSQL(w io.Writer, accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.BeginTx(s.ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	queries := sqlgen.New(tx.Tx)
	if err := queries.SetAccountContext(s.ctx, accountID); err != nil {
		return err
	}
	account, err := accountModel(queries.GetAccount(s.ctx, accountID))
	if err != nil {
		return err
	}
	d := dumpData{Registry: s.registry, Account: &account}
	devices, err := queries.ListDevices(s.ctx, accountID)
	if err != nil {
		return err
	}
	for _, record := range devices {
		device, err := deviceModel(record, nil)
		if err != nil {
			return err
		}
		d.Devices = append(d.Devices, device)
	}
	state, err := queries.GetSyncState(s.ctx, accountID)
	if err != nil {
		return err
	}
	d.Dataset = &domain.DatasetState{Generation: state.Generation, MinCursor: state.MinCursor, Head: state.Head}
	if err := s.scanRowState(&d, tx, accountID); err != nil {
		return err
	}
	if err := s.scanChangeLog(&d, tx, accountID); err != nil {
		return err
	}
	if err := s.scanMedia(&d, tx, accountID); err != nil {
		return err
	}
	if err := s.scanReceipts(&d, tx, accountID); err != nil {
		return err
	}
	if err := s.scanChangeEvents(&d, tx, accountID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return writeSQLDump(w, d)
}

func (s *postgresStore) scanRowState(d *dumpData, tx *storeTx, accountID string) error {
	rows, err := tx.QueryContext(s.ctx,
		`SELECT table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version
		 FROM row_state WHERE account_id = ? ORDER BY table_name, id`, accountID)
	if err != nil {
		return fmt.Errorf("导出行状态: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		r := domain.Row{AccountID: accountID}
		var snap sql.NullString
		if err := rows.Scan(&r.Table, &r.ID, &snap, &r.UpdatedAtMs, &r.DeviceID, &r.DeletedAtMs, &r.Version, &r.DataVersion); err != nil {
			return fmt.Errorf("扫描行状态导出: %w", err)
		}
		if snap.Valid && snap.String != "" {
			r.Snapshot = json.RawMessage(snap.String)
		}
		d.Rows = append(d.Rows, r)
	}
	return rows.Err()
}

func (s *postgresStore) scanChangeLog(d *dumpData, tx *storeTx, accountID string) error {
	rows, err := tx.QueryContext(s.ctx,
		`SELECT version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version
		 FROM change_log WHERE account_id = ? ORDER BY version`, accountID)
	if err != nil {
		return fmt.Errorf("导出变更日志: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		ch := domain.Change{AccountID: accountID}
		var op string
		var deleted int64
		var snap sql.NullString
		if err := rows.Scan(&ch.Version, &ch.Table, &ch.ID, &op, &ch.UpdatedAtMs, &ch.DeviceID,
			&deleted, &ch.DeletedAtMs, &snap, &ch.DataVersion); err != nil {
			return fmt.Errorf("扫描变更日志导出: %w", err)
		}
		ch.Operation = domain.Operation(op)
		ch.Deleted = deleted != 0
		if snap.Valid && snap.String != "" {
			ch.Snapshot = json.RawMessage(snap.String)
		}
		d.Log = append(d.Log, ch)
	}
	return rows.Err()
}

func (s *postgresStore) scanMedia(d *dumpData, tx *storeTx, accountID string) error {
	rows, err := tx.QueryContext(s.ctx,
		"SELECT key, checksum, size, content_type, created_at FROM media_objects WHERE account_id = ? ORDER BY key", accountID)
	if err != nil {
		return fmt.Errorf("导出媒体索引: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		m := domain.MediaObject{AccountID: accountID}
		var created string
		if err := rows.Scan(&m.Key, &m.Checksum, &m.Size, &m.ContentType, &created); err != nil {
			return fmt.Errorf("扫描媒体导出: %w", err)
		}
		if m.CreatedAt, err = parseRFC3339(created); err != nil {
			return fmt.Errorf("created_at 解析: %w", err)
		}
		d.Media = append(d.Media, m)
	}
	return rows.Err()
}
