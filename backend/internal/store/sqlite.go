// SQLite 存储实现：账号/设备/行镜像/变更日志/媒体索引全部落在
// <dir>/recoow.db；媒体字节仍以内容寻址文件存放（见 MediaPath）。
// 使用纯 Go 驱动 modernc.org/sqlite，无 CGO，单文件便于自托管备份。
package store

import (
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// sqliteStore 是 Store 的 SQLite 实现。所有方法在进程内互斥锁下执行，
// 保证与内存实现一致的串行语义；多语句批量操作（Push）使用单个事务，
// 要么整体成功提交，要么整体回滚。
type sqliteStore struct {
	mu       sync.Mutex
	ctx      context.Context
	db       *sql.DB
	mediaDir string // 媒体字节目录 <dir>/media
	tables   map[string]bool
}

// NewSQLiteStore 打开（不存在则创建）<dir>/recoow.db 并初始化表结构。
func NewSQLiteStore(dir string) (Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	dsn := "file:" + filepath.Join(dir, "recoow.db") + "?_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("打开 SQLite: %w", err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	s := &sqliteStore{
		ctx:      context.Background(),
		db:       db,
		mediaDir: filepath.Join(dir, "media"),
		tables:   maps.Clone(SyncableTables),
	}
	if err := s.init(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// init 设置连接级 pragma 并执行建表语句（幂等，可重复启动）。
func (s *sqliteStore) init() error {
	for _, pragma := range []string{
		"PRAGMA journal_mode=WAL",
		"PRAGMA synchronous=NORMAL",
		"PRAGMA foreign_keys=ON",
	} {
		if _, err := s.db.ExecContext(s.ctx, pragma); err != nil {
			return fmt.Errorf("设置 pragma %q: %w", pragma, err)
		}
	}
	for _, stmt := range ddlStatements {
		if _, err := s.db.ExecContext(s.ctx, stmt); err != nil {
			return fmt.Errorf("初始化表结构: %w", err)
		}
	}
	return s.migrateSyncSchema()
}

// querier 抽象 *sql.DB 与 *sql.Tx 的查询能力，供内部助手复用。
type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// requireAccountLocked 校验账号存在，否则返回 ErrNoAccount。
func requireAccountLocked(q querier, ctx context.Context, accountID string) error {
	var one int
	err := q.QueryRowContext(ctx, "SELECT 1 FROM account WHERE id = ?", accountID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNoAccount
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
func (s *sqliteStore) InitAccount(recoveryKeyHash []byte) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.BeginTx(s.ctx, nil)
	if err != nil {
		return Account{}, fmt.Errorf("开启事务: %w", err)
	}
	defer tx.Rollback()

	a := Account{
		ID:              newID(),
		RecoveryKeyHash: recoveryKeyHash,
		CreatedAt:       time.Now().UTC(),
		NextVersion:     1,
	}
	if _, err := tx.ExecContext(s.ctx,
		"INSERT INTO account (id, recovery_key_hash, created_at, next_version) VALUES (?, ?, ?, ?)",
		a.ID, hex.EncodeToString(recoveryKeyHash), rfc3339(a.CreatedAt), a.NextVersion); err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			return Account{}, ErrRecoveryKeyTaken
		}
		return Account{}, fmt.Errorf("写入账号: %w", err)
	}
	if _, err := tx.ExecContext(s.ctx,
		"INSERT OR IGNORE INTO sync_metadata (account_id, generation) VALUES (?, ?)", a.ID, newID()); err != nil {
		return Account{}, fmt.Errorf("写入同步元数据: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return Account{}, fmt.Errorf("提交事务: %w", err)
	}
	return a, nil
}

func (s *sqliteStore) GetAccount(id string) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.getAccountLocked(s.db, id)
}

func (s *sqliteStore) GetAccountByRecoveryKeyHash(recoveryKeyHash []byte) (Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var id string
	err := s.db.QueryRowContext(s.ctx,
		"SELECT id FROM account WHERE recovery_key_hash = ?", hex.EncodeToString(recoveryKeyHash)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrNoAccount
	}
	if err != nil {
		return Account{}, fmt.Errorf("读取账号: %w", err)
	}
	return s.getAccountLocked(s.db, id)
}

func (s *sqliteStore) CountAccounts() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var count int
	if err := s.db.QueryRowContext(s.ctx, "SELECT COUNT(*) FROM account").Scan(&count); err != nil {
		return 0, fmt.Errorf("统计账号: %w", err)
	}
	return count, nil
}

func (s *sqliteStore) getAccountLocked(q querier, id string) (Account, error) {
	var a Account
	var hash, created string
	err := q.QueryRowContext(s.ctx,
		"SELECT id, recovery_key_hash, created_at, next_version FROM account WHERE id = ?", id).
		Scan(&a.ID, &hash, &created, &a.NextVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrNoAccount
	}
	if err != nil {
		return Account{}, fmt.Errorf("读取账号: %w", err)
	}
	if a.RecoveryKeyHash, err = hex.DecodeString(hash); err != nil {
		return Account{}, fmt.Errorf("recovery_key_hash 解码: %w", err)
	}
	if a.CreatedAt, err = parseRFC3339(created); err != nil {
		return Account{}, fmt.Errorf("created_at 解析: %w", err)
	}
	return a, nil
}

// ---- 设备 ----

// rowScanner 抽象 sql.Row 与 sql.Rows 的 Scan。
type rowScanner interface{ Scan(dest ...any) error }

func scanDevice(sc rowScanner) (Device, error) {
	var dv Device
	var hash, created, lastSeen string
	var owner int
	if err := sc.Scan(&dv.ID, &dv.AccountID, &dv.Name, &hash, &owner, &created, &lastSeen); err != nil {
		return Device{}, err
	}
	var err error
	if dv.TokenHash, err = hex.DecodeString(hash); err != nil {
		return Device{}, fmt.Errorf("token_hash 解码: %w", err)
	}
	if dv.CreatedAt, err = parseRFC3339(created); err != nil {
		return Device{}, fmt.Errorf("created_at 解析: %w", err)
	}
	if dv.LastSeenAt, err = parseRFC3339(lastSeen); err != nil {
		return Device{}, fmt.Errorf("last_seen_at 解析: %w", err)
	}
	dv.IsOwner = owner != 0
	return dv, nil
}

const deviceColumns = "id, account_id, name, token_hash, is_owner, created_at, last_seen_at"

func (s *sqliteStore) CreateDevice(accountID, name string, tokenHash []byte, isOwner bool) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return Device{}, err
	}
	encoded := hex.EncodeToString(tokenHash)
	var one int
	err := s.db.QueryRowContext(s.ctx, "SELECT 1 FROM devices WHERE token_hash = ?", encoded).Scan(&one)
	if err == nil {
		return Device{}, ErrDeviceExists
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Device{}, fmt.Errorf("查询设备: %w", err)
	}

	now := time.Now().UTC()
	dv := Device{
		ID:         newID(),
		AccountID:  accountID,
		Name:       name,
		TokenHash:  tokenHash,
		IsOwner:    isOwner,
		CreatedAt:  now,
		LastSeenAt: now,
	}
	if _, err := s.db.ExecContext(s.ctx,
		"INSERT INTO devices (id, account_id, name, token_hash, is_owner, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
		dv.ID, dv.AccountID, dv.Name, encoded, boolInt(isOwner), rfc3339(now), rfc3339(now)); err != nil {
		return Device{}, fmt.Errorf("写入设备: %w", err)
	}
	return dv, nil
}

func (s *sqliteStore) GetDeviceByTokenHash(tokenHash []byte) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	dv, err := scanDevice(s.db.QueryRowContext(s.ctx,
		"SELECT "+deviceColumns+" FROM devices WHERE token_hash = ?", hex.EncodeToString(tokenHash)))
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	if err != nil {
		return Device{}, fmt.Errorf("读取设备: %w", err)
	}
	return dv, nil
}

func (s *sqliteStore) GetDevice(id string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	dv, err := scanDevice(s.db.QueryRowContext(s.ctx,
		"SELECT "+deviceColumns+" FROM devices WHERE id = ?", id))
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	if err != nil {
		return Device{}, fmt.Errorf("读取设备: %w", err)
	}
	return dv, nil
}

func (s *sqliteStore) ListDevices(accountID string) ([]Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.db.QueryContext(s.ctx,
		"SELECT "+deviceColumns+" FROM devices WHERE account_id = ? ORDER BY created_at, id", accountID)
	if err != nil {
		return nil, fmt.Errorf("查询设备列表: %w", err)
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		dv, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, dv)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历设备列表: %w", err)
	}
	return out, nil
}

func (s *sqliteStore) DeleteDevice(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.ExecContext(s.ctx, "DELETE FROM devices WHERE id = ?", id)
	if err != nil {
		return fmt.Errorf("删除设备: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *sqliteStore) TouchDeviceLastSeen(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	res, err := s.db.ExecContext(s.ctx,
		"UPDATE devices SET last_seen_at = ? WHERE id = ?", rfc3339(time.Now().UTC()), id)
	if err != nil {
		return fmt.Errorf("更新设备活跃时间: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ---- 同步 ----

func (s *sqliteStore) Push(accountID string, items []PushItem) ([]PushResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.BeginTx(s.ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("开启事务: %w", err)
	}
	defer tx.Rollback()

	if err := requireAccountLocked(tx, s.ctx, accountID); err != nil {
		return nil, err
	}
	results := make([]PushResult, 0, len(items))
	for _, it := range items {
		var res PushResult
		var err error
		if it.MutationID != "" {
			res, err = applyRevision(sqliteRevision{store: s, transaction: tx, accountID: accountID}, it)
		} else {
			res, err = s.pushOneTx(tx, accountID, it)
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
func (s *sqliteStore) pushOneTx(tx *sql.Tx, accountID string, it PushItem) (PushResult, error) {
	if !s.tables[it.Table] {
		return rejectedPushResult(it, PushUnsupportedTable), nil
	}
	if it.Operation != OpInsert && it.Operation != OpUpdate && it.Operation != OpDelete {
		return rejectedPushResult(it, PushInvalidOperation), nil
	}

	current := Row{Table: it.Table, ID: it.ID, AccountID: accountID}
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
		if !candidateWins(
			it.UpdatedAtMs,
			current.UpdatedAtMs,
			it.DeviceID,
			current.DeviceID,
		) {
			return unchangedPushResult(it, current), nil
		}
	case errors.Is(err, sql.ErrNoRows):
		// 全新行，直接采纳。
	default:
		return PushResult{}, fmt.Errorf("读取行状态: %w", err)
	}

	var nextVersion int64
	if err := tx.QueryRowContext(s.ctx, "SELECT next_version FROM account WHERE id = ?", accountID).
		Scan(&nextVersion); err != nil {
		return PushResult{}, fmt.Errorf("读取账号版本号: %w", err)
	}
	deleted := it.Operation == OpDelete
	deletedAt := int64(0)
	var snapshot any
	if deleted {
		deletedAt = it.UpdatedAtMs
	} else {
		snapshot = string(it.Snapshot)
	}
	op := it.Operation
	if op != OpDelete {
		op = OpUpdate
	}
	if _, err := tx.ExecContext(s.ctx,
		"UPDATE account SET next_version = ? WHERE id = ?", nextVersion+1, accountID); err != nil {
		return PushResult{}, fmt.Errorf("推进版本号: %w", err)
	}
	if _, err := tx.ExecContext(s.ctx,
		`INSERT INTO row_state (account_id, table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (account_id, table_name, id) DO UPDATE SET
			snapshot = excluded.snapshot, updated_at_ms = excluded.updated_at_ms,
			device_id = excluded.device_id, deleted_at_ms = excluded.deleted_at_ms, version = excluded.version`,
		accountID, it.Table, it.ID, snapshot, it.UpdatedAtMs, it.DeviceID, deletedAt, nextVersion); err != nil {
		return PushResult{}, fmt.Errorf("写入行状态: %w", err)
	}
	if _, err := tx.ExecContext(s.ctx,
		`INSERT INTO change_log (account_id, version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		accountID, nextVersion, it.Table, it.ID, op, it.UpdatedAtMs, it.DeviceID, boolInt(deleted), deletedAt, snapshot); err != nil {
		return PushResult{}, fmt.Errorf("写入变更日志: %w", err)
	}
	return PushResult{
		Table: it.Table, ID: it.ID, Applied: true, Operation: it.Operation,
		Version: nextVersion, Status: PushApplied,
	}, nil
}

func (s *sqliteStore) Pull(accountID string, cursor int64, limit int) ([]Change, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return nil, 0, err
	}
	if limit <= 0 {
		limit = 500
	}
	rows, err := s.db.QueryContext(s.ctx,
		`SELECT version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version
		 FROM change_log WHERE account_id = ? AND version > ? ORDER BY version LIMIT ?`, accountID, cursor, limit)
	if err != nil {
		return nil, 0, fmt.Errorf("查询变更日志: %w", err)
	}
	defer rows.Close()

	out := make([]Change, 0, limit)
	next := cursor
	for rows.Next() {
		var ch Change
		var op string
		var deleted, deletedAt int64
		var snap sql.NullString
		if err := rows.Scan(&ch.Version, &ch.Table, &ch.ID, &op, &ch.UpdatedAtMs, &ch.DeviceID,
			&deleted, &deletedAt, &snap, &ch.DataVersion); err != nil {
			return nil, 0, fmt.Errorf("扫描变更日志: %w", err)
		}
		ch.AccountID = accountID
		ch.Operation = Operation(op)
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

func (s *sqliteStore) Rows(accountID, table string) ([]Row, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return nil, err
	}
	if !s.tables[table] {
		return nil, ErrNotFound
	}
	rows, err := s.db.QueryContext(s.ctx,
		`SELECT id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version
		 FROM row_state WHERE account_id = ? AND table_name = ? AND deleted_at_ms = 0 ORDER BY id`, accountID, table)
	if err != nil {
		return nil, fmt.Errorf("查询行状态: %w", err)
	}
	defer rows.Close()

	var out []Row
	for rows.Next() {
		var r Row
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

func (s *sqliteStore) PutMedia(accountID, key, checksum, contentType string, size int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return err
	}
	if key == "" || checksum == "" {
		return fmt.Errorf("key and checksum are required")
	}
	if immutableMediaKey(key) && key != "sync/"+checksum {
		return ErrConflict
	}
	var previous string
	if err := s.db.QueryRow("SELECT checksum FROM media_objects WHERE account_id = ? AND key = ?", accountID, key).Scan(&previous); err == nil && previous != checksum {
		return ErrConflict
	} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if _, err := s.db.ExecContext(s.ctx,
		`INSERT INTO media_objects (account_id, key, checksum, size, content_type, created_at) VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT (account_id, key) DO UPDATE SET
			checksum = excluded.checksum, size = excluded.size,
			content_type = excluded.content_type, created_at = excluded.created_at`,
		accountID, key, checksum, size, contentType, rfc3339(time.Now().UTC())); err != nil {
		return fmt.Errorf("写入媒体索引: %w", err)
	}
	return nil
}

func (s *sqliteStore) GetMedia(accountID, key string) (MediaObject, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return MediaObject{}, err
	}
	var m MediaObject
	var created string
	err := s.db.QueryRowContext(s.ctx,
		"SELECT key, checksum, size, content_type, created_at FROM media_objects WHERE account_id = ? AND key = ?", accountID, key).
		Scan(&m.Key, &m.Checksum, &m.Size, &m.ContentType, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return MediaObject{}, ErrNotFound
	}
	if err != nil {
		return MediaObject{}, fmt.Errorf("读取媒体索引: %w", err)
	}
	m.AccountID = accountID
	if m.CreatedAt, err = parseRFC3339(created); err != nil {
		return MediaObject{}, fmt.Errorf("created_at 解析: %w", err)
	}
	return m, nil
}

func (s *sqliteStore) DeleteMedia(accountID, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := requireAccountLocked(s.db, s.ctx, accountID); err != nil {
		return err
	}
	var references int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM change_log, json_each(change_log.snapshot, '$._media') AS media
		WHERE change_log.account_id = ? AND json_extract(media.value, '$.key') = ?`, accountID, key).Scan(&references); err != nil {
		return err
	}
	if references > 0 {
		return ErrConflict
	}
	res, err := s.db.ExecContext(s.ctx, "DELETE FROM media_objects WHERE account_id = ? AND key = ?", accountID, key)
	if err != nil {
		return fmt.Errorf("删除媒体索引: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// MediaPath 返回某对象字节在磁盘上的落点（内容寻址，可被多个 key 共享）。
func (s *sqliteStore) MediaPath(accountID, checksum string) string {
	return filepath.Join(s.mediaDir, accountID, checksum)
}

// Save 为幂等兼容保留：SQLite 每次变更即时提交，无需额外落盘。
func (s *sqliteStore) Save() error { return nil }

func (s *sqliteStore) Close() error { return s.db.Close() }

// ExportSQL 把指定账号的全部状态导出为可移植 SQL（含建表与全部数据）。
// 多账号实例里各账号互不可见，因此导出必须以账号为界。
func (s *sqliteStore) ExportSQL(w io.Writer, accountID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	d := dumpData{}
	if acc, err := s.getAccountLocked(s.db, accountID); err == nil {
		d.Account = &acc
	} else if !errors.Is(err, ErrNoAccount) {
		return err
	}
	if err := s.scanDevices(&d, accountID); err != nil {
		return err
	}
	if err := s.scanRowState(&d, accountID); err != nil {
		return err
	}
	if err := s.scanChangeLog(&d, accountID); err != nil {
		return err
	}
	if err := s.scanMedia(&d, accountID); err != nil {
		return err
	}
	if d.Account != nil {
		state, err := s.syncStateLocked(accountID)
		if err != nil {
			return err
		}
		d.Dataset = &state
	}
	if err := s.scanReceipts(&d, accountID); err != nil {
		return err
	}
	return writeSQLDump(w, d)
}

func (s *sqliteStore) scanDevices(d *dumpData, accountID string) error {
	rows, err := s.db.QueryContext(s.ctx,
		"SELECT "+deviceColumns+" FROM devices WHERE account_id = ? ORDER BY id", accountID)
	if err != nil {
		return fmt.Errorf("导出设备: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		dv, err := scanDevice(rows)
		if err != nil {
			return err
		}
		d.Devices = append(d.Devices, dv)
	}
	return rows.Err()
}

func (s *sqliteStore) scanRowState(d *dumpData, accountID string) error {
	rows, err := s.db.QueryContext(s.ctx,
		`SELECT table_name, id, snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version
		 FROM row_state WHERE account_id = ? ORDER BY table_name, id`, accountID)
	if err != nil {
		return fmt.Errorf("导出行状态: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		r := Row{AccountID: accountID}
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

func (s *sqliteStore) scanChangeLog(d *dumpData, accountID string) error {
	rows, err := s.db.QueryContext(s.ctx,
		`SELECT version, table_name, id, operation, updated_at_ms, device_id, deleted, deleted_at_ms, snapshot, data_version
		 FROM change_log WHERE account_id = ? ORDER BY version`, accountID)
	if err != nil {
		return fmt.Errorf("导出变更日志: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		ch := Change{AccountID: accountID}
		var op string
		var deleted int64
		var snap sql.NullString
		if err := rows.Scan(&ch.Version, &ch.Table, &ch.ID, &op, &ch.UpdatedAtMs, &ch.DeviceID,
			&deleted, &ch.DeletedAtMs, &snap, &ch.DataVersion); err != nil {
			return fmt.Errorf("扫描变更日志导出: %w", err)
		}
		ch.Operation = Operation(op)
		ch.Deleted = deleted != 0
		if snap.Valid && snap.String != "" {
			ch.Snapshot = json.RawMessage(snap.String)
		}
		d.Log = append(d.Log, ch)
	}
	return rows.Err()
}

func (s *sqliteStore) scanMedia(d *dumpData, accountID string) error {
	rows, err := s.db.QueryContext(s.ctx,
		"SELECT key, checksum, size, content_type, created_at FROM media_objects WHERE account_id = ? ORDER BY key", accountID)
	if err != nil {
		return fmt.Errorf("导出媒体索引: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		m := MediaObject{AccountID: accountID}
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
