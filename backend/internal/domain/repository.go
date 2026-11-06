package domain

import (
	"io"
	"time"
)

// Store 是云同步后端的持久化契约。
//
// 所有变更方法都保证并发安全；PostgreSQL 写入在返回前完成事务提交。
type Store interface {
	SetAccountLimit(limit int)
	IssuePairingCode(hash, accountID string, expires time.Time) error
	ConsumePairingCode(hash string) (string, error)
	CleanupExpiredSessions(now time.Time, limit int) (int64, error)
	QueryRecords(accountID string, query RecordQuery) (RecordPage, error)
	Record(accountID, table, id string) (Row, error)
	Statistics(accountID string, from, to int64) (Statistics, error)
	IdentityStore
	ConfigureSyncTables(tables []string) error
	// ---- 账号与设备 ----
	// InitAccount 新建一个全新账号（同一实例可存在多个账号，彼此数据隔离）。
	InitAccount(recoveryKeyHash []byte) (Account, error)
	GetAccount(id string) (Account, error)
	GetAccountByRecoveryKeyHash(recoveryKeyHash []byte) (Account, error)
	CountAccounts() (int, error)
	CreateDevice(accountID, name string, tokenHash []byte, isOwner bool) (Device, error)
	GetDeviceByTokenHash(tokenHash []byte) (Device, error)
	GetDevice(id string) (Device, error)
	ListDevices(accountID string) ([]Device, error)
	DeleteDevice(id string) error
	TouchDeviceLastSeen(id string) error

	// ---- 同步 ----
	Push(accountID string, items []PushItem) ([]PushResult, error)
	Pull(accountID string, cursor int64, limit int) ([]Change, int64, error)
	Rows(accountID, table string) ([]Row, error) // 某张表的存活行（后台浏览/导出用）
	SyncState(accountID string) (DatasetState, error)
	Bootstrap(accountID string, request BootstrapRequest) (BootstrapPage, error)
	SnapshotRevisions(accountID string, request SnapshotQuery) ([]Change, error)
	ClearDataset(accountID, expectedGeneration string) (DatasetState, error)

	// ---- 媒体 ----
	PutMedia(accountID string, key, checksum, contentType string, size int64) error
	GetMedia(accountID, key string) (MediaObject, error)
	DeleteMedia(accountID, key string) error

	// ---- 生命周期 ----
	Save() error
	Close() error

	// MediaPath 返回某对象字节在磁盘上的落点（供对象存储适配层读写）。
	MediaPath(accountID, checksum string) string
}

// SQLDumper 导出当前账号的 PostgreSQL 数据。
type SQLDumper interface {
	ExportSQL(w io.Writer, accountID string) error
}
