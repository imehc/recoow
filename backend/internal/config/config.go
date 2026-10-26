// 包 config 从环境变量加载后端配置。
package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ListenAddr     string
	DataDir        string
	SchemaDir      string
	BootstrapToken string // 若已设置，调用 account/init 前必须携带
	MaxAccounts    int    // 0 表示不限制；>0 时 account/init 超过该数量直接拒绝
	PairingTTL     time.Duration
	TLSCertFile    string // 可空；为空则以明文 HTTP 对外（TLS 由上游终止）
	TLSKeyFile     string
	CORSOrigins    []string // 允许跨源访问的后台来源；为空表示不开启 CORS
}

func Load() Config {
	return Config{
		ListenAddr:     envOr("RECOOW_LISTEN", ":8080"),
		DataDir:        envOr("RECOOW_DATA", "./data"),
		SchemaDir:      envOr("RECOOW_SCHEMA", "../schema"),
		BootstrapToken: os.Getenv("RECOOW_BOOTSTRAP_TOKEN"),
		MaxAccounts:    envIntOr("RECOOW_MAX_ACCOUNTS", 0),
		PairingTTL:     envDurOr("RECOOW_PAIRING_TTL", 5*time.Minute),
		TLSCertFile:    os.Getenv("RECOOW_TLS_CERT"),
		TLSKeyFile:     os.Getenv("RECOOW_TLS_KEY"),
		CORSOrigins:    splitList(os.Getenv("RECOOW_CORS_ORIGINS")),
	}
}

func envIntOr(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

// splitList 把逗号分隔的字符串拆成去空白的列表。
func splitList(s string) []string {
	if s == "" {
		return nil
	}
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envDurOr(k string, def time.Duration) time.Duration {
	if v := os.Getenv(k); v != "" {
		if d, err := strconv.ParseInt(v, 10, 64); err == nil {
			return time.Duration(d) * time.Second
		}
	}
	return def
}
