// 包 domain 定义同步状态模型与仓储契约，与具体数据库解耦。
// repository 提供 PostgreSQL 实现（pgx 驱动、结构化业务表）与内存测试实现。
// 数据按账号隔离，同一实例可承载多个互不可见的数据集。
package domain

import (
	"encoding/json"
	"errors"
	"time"
)

// Operation 与 iOS 端 ChangeOperation 取值保持一致。
type Operation string

const (
	OpInsert Operation = "insert"
	OpUpdate Operation = "update"
	OpDelete Operation = "delete"
)

// SyncableTables 是可同步实体表白名单，与 iOS 端 SyncableRecord 模型对应。
// 注：真正“启用的”同步范围由 OpenAPI 契约决定，
// 这里仅作为底层存储的安全兜底，二者应保持一致（启动时自检）。
var SyncableTables = map[string]bool{
	"bills":                    true,
	"reminders":                true,
	"anniversaries":            true,
	"diary_entries":            true,
	"diary_tags":               true,
	"diary_links":              true,
	"food_entries":             true,
	"food_day_records":         true,
	"stored_value_accounts":    true,
	"decision_collections":     true,
	"decision_options":         true,
	"decision_choice_records":  true,
	"item_categories":          true,
	"stored_items":             true,
	"tracks":                   true,
	"track_points":             true,
	"track_segments":           true,
	"media_assets":             true,
	"media_attachments":        true,
	"stored_value_adjustments": true,
	"reminder_completions":     true,
}

// IsSyncableTable 判断某表是否在白名单内。
func IsSyncableTable(t string) bool { return SyncableTables[t] }

// ErrNotFound 表示请求的实体不存在。
var ErrNotFound = errors.New("not found")

// ErrConflict 表示操作违反唯一性约束。
var ErrConflict = errors.New("conflict")

var (
	ErrAccountExists    = errors.New("account already exists")
	ErrRecoveryKeyTaken = errors.New("recovery key already registered")
	ErrNoAccount        = errors.New("no account exists")
	ErrDeviceExists     = errors.New("device already exists")
	ErrAccountQuota     = errors.New("account quota exceeded")
	ErrWebWriteDenied   = errors.New("account web writes disabled")
)

// Account 表示一份由多台设备共享的逻辑数据集。
type Account struct {
	ID              string    `json:"id"`
	RecoveryKeyHash []byte    `json:"recovery_key_hash"`
	CreatedAt       time.Time `json:"created_at"`
	NextVersion     int64     `json:"next_version"` // 全局单调版本号
	WebRecordWrites bool      `json:"-"`
}

// Device 是绑定到某账号的同步参与者。只保存 token 的 SHA-256 哈希；
// 这里的 device_id 由服务端签发且具权威性，客户端上报的 device_id
// （用于 LWW 平票）只出现在每次变更里，绝不用于鉴权。
type Device struct {
	Profile    *DeviceProfile `json:"profile,omitempty"`
	ID         string         `json:"id"`
	AccountID  string         `json:"account_id"`
	Name       string         `json:"name"`
	TokenHash  []byte         `json:"token_hash"`
	IsOwner    bool           `json:"is_owner"`
	CreatedAt  time.Time      `json:"created_at"`
	LastSeenAt time.Time      `json:"last_seen_at"`
}

// PushItem 表示一条基于已确认版本的客户端变更。
type PushItem struct {
	MutationID      string          `json:"mutationId,omitempty"`
	BaseVersion     *int64          `json:"baseVersion,omitempty"`
	Resolution      string          `json:"resolution,omitempty"`
	ExpectedVersion *int64          `json:"expectedVersion,omitempty"`
	DataVersion     int             `json:"dataVersion,omitempty"`
	References      []RowReference  `json:"-"`
	RequestHash     string          `json:"-"`
	DefaultedFields []string        `json:"-"`
	Origin          *ChangeOrigin   `json:"origin,omitempty"`
	Table           string          `json:"table"`
	ID              string          `json:"id"`
	Operation       Operation       `json:"operation"`
	UpdatedAtMs     int64           `json:"updatedAtMs"`
	DeviceID        string          `json:"deviceId"`
	Snapshot        json.RawMessage `json:"snapshot,omitempty"`
}

type PushStatus string

const (
	PushApplied           PushStatus = "applied"
	PushDuplicate         PushStatus = "duplicate"
	PushConflict          PushStatus = "conflict"
	PushUnsupportedTable  PushStatus = "unsupported_table"
	PushInvalidOperation  PushStatus = "invalid_operation"
	PushMutationReused    PushStatus = "mutation_reused"
	PushDependencyMissing PushStatus = "dependency_missing"
	PushInvalidRevision   PushStatus = "invalid_revision"
)

// PushResult 描述一条 push 条目的处理结果。
type PushResult struct {
	MutationID string           `json:"mutationId,omitempty"`
	Replayed   bool             `json:"replayed,omitempty"`
	Table      string           `json:"table"`
	ID         string           `json:"id"`
	Applied    bool             `json:"applied"`
	Operation  Operation        `json:"operation"`
	Version    int64            `json:"version"` // 该行最新的 changelog 版本号
	Status     PushStatus       `json:"status"`
	Current    *Row             `json:"current,omitempty"`
	Events     []ChangeEventRef `json:"events,omitempty"`
}

// Change 是账号级 changelog 中的一条记录（也是 pull 的数据来源）。
// AccountID 只用于服务端内部隔离，不进入客户端契约。
type Change struct {
	AccountID   string          `json:"-"`
	DataVersion int             `json:"dataVersion,omitempty"`
	Version     int64           `json:"version"`
	Table       string          `json:"table"`
	ID          string          `json:"id"`
	Operation   Operation       `json:"operation"`
	UpdatedAtMs int64           `json:"updatedAtMs"`
	DeviceID    string          `json:"deviceId"`
	Deleted     bool            `json:"deleted"`
	DeletedAtMs int64           `json:"deletedAtMs,omitempty"`
	Snapshot    json.RawMessage `json:"snapshot,omitempty"`
}

// Row 表示 (account, table, id) 当前胜出的镜像行状态。
type Row struct {
	AccountID   string          `json:"-"`
	DataVersion int             `json:"dataVersion,omitempty"`
	Table       string          `json:"table"`
	ID          string          `json:"id"`
	Snapshot    json.RawMessage `json:"snapshot,omitempty"`
	UpdatedAtMs int64           `json:"updatedAtMs"`
	DeviceID    string          `json:"deviceId"`
	DeletedAtMs int64           `json:"deletedAtMs,omitempty"` // 0 表示存活
	Version     int64           `json:"version"`               // 最近一次被采纳写入的 changelog 版本
}

// MediaObject 记录某账号下已存储的一段媒体。
type MediaObject struct {
	AccountID   string    `json:"-"`
	Key         string    `json:"key"` // 账号内逻辑 key，如 media/<uuid>.<ext>
	Checksum    string    `json:"checksum"`
	Size        int64     `json:"size"`
	ContentType string    `json:"content_type"`
	CreatedAt   time.Time `json:"created_at"`
}
