package domain

import "encoding/json"

// ClientEnvironment 是客户端自报的信息，绝不作为鉴权依据。
type ClientEnvironment struct {
	Client     string `json:"client"`
	AppVersion string `json:"appVersion,omitempty"`
	OS         string `json:"os"`
	OSVersion  string `json:"osVersion,omitempty"`
}

type DeviceProfile struct {
	ClientEnvironment
	LocalDeviceID string `json:"localDeviceId,omitempty"`
}

type ChangeOrigin struct {
	ClientEnvironment
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName,omitempty"`
}

// SubmissionSource 只由通过鉴权的服务端请求上下文提供。
type SubmissionSource struct {
	ClientEnvironment
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	SessionID  string `json:"sessionId,omitempty"`
}

type PushContext struct {
	Generation string
	Submission *SubmissionSource
	DirectEdit bool
	// Web 写入在持有账号事务锁时再次核对授权，避免撤销与提交竞态。
	RequireWebWrite bool
	// SessionID 在写入事务内核对会话设备仍有效，阻止撤销后提交。
	SessionID string
}

type ChangeEvent struct {
	AccountID                  string            `json:"-"`
	EventID                    string            `json:"eventId"`
	Generation                 string            `json:"generation"`
	Version                    int64             `json:"version"`
	PreviousVersion            int64             `json:"previousVersion"`
	Table                      string            `json:"table"`
	ID                         string            `json:"id"`
	Title                      string            `json:"title"`
	Operation                  string            `json:"operation"`
	Method                     string            `json:"method"`
	OperationID                string            `json:"operationId"`
	TriggerEventID             *string           `json:"triggerEventId"`
	MutationID                 string            `json:"mutationId,omitempty"`
	ModifiedAtMs               *int64            `json:"modifiedAtMs"`
	AcceptedAtMs               *int64            `json:"acceptedAtMs"`
	OriginalSource             *ChangeOrigin     `json:"originalSource"`
	OriginalRegisteredDeviceID *string           `json:"originalRegisteredDeviceId"`
	Submission                 *SubmissionSource `json:"submission"`
}

type ChangeEventRef struct {
	EventID      string `json:"eventId"`
	Version      int64  `json:"version"`
	AcceptedAtMs int64  `json:"acceptedAtMs"`
}

type ChangeEventQuery struct {
	Generation string
	Watermark  *int64
	Before     int64
	Limit      int
	Table      string
	ID         string
	Operation  string
	Client     string
	DeviceID   string
	FromMs     int64
	ToMs       int64
}

type ChangeEventPage struct {
	Events     []ChangeEvent `json:"events"`
	Generation string        `json:"generation"`
	Watermark  int64         `json:"watermark"`
	Before     int64         `json:"-"`
	More       bool          `json:"more"`
}

type ChangeEventDetail struct {
	Event   ChangeEvent   `json:"event"`
	Before  *Row          `json:"before"`
	After   Row           `json:"after"`
	Related []ChangeEvent `json:"related"`
	Changes []FieldChange `json:"changes"`
}

type FieldChange struct {
	Field         string          `json:"field"`
	BeforePresent bool            `json:"beforePresent"`
	AfterPresent  bool            `json:"afterPresent"`
	BeforeValue   json.RawMessage `json:"beforeValue"`
	AfterValue    json.RawMessage `json:"afterValue"`
}
