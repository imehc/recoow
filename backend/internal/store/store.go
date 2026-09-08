// 包 store 定义持久化状态模型与仓储契约（recoow 云同步后端核心）。
//
// 设计刻意与具体数据库解耦：同步引擎只面向 Store 接口，本包提供单进程
// SQLite 实现（纯 Go 驱动、单文件落库，适合个人自托管部署），内存实现
// 供测试使用；未来如需多租户/中心化部署，可用 Postgres 适配器实现同一接口。
package store

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
// 注：真正“启用的”同步范围由 recoow/schema 字段清单唯一权威决定，
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
	ErrAccountExists = errors.New("account already exists")
	ErrNoAccount     = errors.New("no account exists")
	ErrDeviceExists  = errors.New("device already exists")
)

// Account 表示一份由多台设备共享的逻辑数据集。
type Account struct {
	ID              string    `json:"id"`
	RecoveryKeyHash []byte    `json:"recovery_key_hash"`
	CreatedAt       time.Time `json:"created_at"`
	NextVersion     int64     `json:"next_version"` // 全局单调版本号
}

// Device 是绑定到某账号的同步参与者。只保存 token 的 SHA-256 哈希；
// 这里的 device_id 由服务端签发且具权威性，客户端上报的 device_id
// （用于 LWW 平票）只出现在每次变更里，绝不用于鉴权。
type Device struct {
	ID         string    `json:"id"`
	AccountID  string    `json:"account_id"`
	Name       string    `json:"name"`
	TokenHash  []byte    `json:"token_hash"`
	IsOwner    bool      `json:"is_owner"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}

// PushItem 表示一条由客户端发起、需要按 LWW 合并的变更。
type PushItem struct {
	MutationID      string          `json:"mutationId,omitempty"`
	BaseVersion     *int64          `json:"baseVersion,omitempty"`
	DataVersion     int             `json:"dataVersion,omitempty"`
	References      []RowReference  `json:"-"`
	RequestHash     string          `json:"-"`
	DefaultedFields []string        `json:"-"`
	Table           string          `json:"table"`
	ID              string          `json:"id"`
	Operation       Operation       `json:"operation"`
	UpdatedAtMs     int64           `json:"updatedAtMs"`
	DeviceID        string          `json:"deviceId"` // 仅作平票用
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
	MutationID string     `json:"mutationId,omitempty"`
	Replayed   bool       `json:"replayed,omitempty"`
	Table      string     `json:"table"`
	ID         string     `json:"id"`
	Applied    bool       `json:"applied"`
	Operation  Operation  `json:"operation"`
	Version    int64      `json:"version"` // 该行最新的 changelog 版本号
	Status     PushStatus `json:"status"`
	Current    *Row       `json:"current,omitempty"`
}

// Change 是账号级 changelog 中的一条记录（也是 pull 的数据来源）。
type Change struct {
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

// Row 表示 (table, id) 当前胜出的镜像行状态。
type Row struct {
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
	Key         string    `json:"key"` // 账号内逻辑 key，如 media/<uuid>.<ext>
	Checksum    string    `json:"checksum"`
	Size        int64     `json:"size"`
	ContentType string    `json:"content_type"`
	CreatedAt   time.Time `json:"created_at"`
}
