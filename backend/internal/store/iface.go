package store

// Store 是云同步后端的持久化契约。
//
// 所有变更方法都保证并发安全；对 SQLite 实现而言，返回前已完成事务提交。
type Store interface {
	ConfigureSyncTables(tables []string) error
	// ---- 账号与设备 ----
	InitAccount(recoveryKeyHash []byte) (Account, error)
	GetAccount() (Account, error)
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
