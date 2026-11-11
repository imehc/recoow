// Package buildinfo 把可执行文件的来源信息与同步、schema 版本分开。
package buildinfo

import "runtime/debug"

// 构建发行版二进制时由包脚本覆盖。
var Commit = "unknown"
var Dirty = "unknown"

type Info struct {
	Version string `json:"version"`
	Commit  string `json:"commit"`
	Dirty   string `json:"dirty"`
}

func Current() Info {
	info := Info{Version: Version, Commit: Commit, Dirty: Dirty}
	if build, ok := debug.ReadBuildInfo(); ok && Commit == "unknown" {
		for _, setting := range build.Settings {
			switch setting.Key {
			case "vcs.revision":
				info.Commit = setting.Value
			case "vcs.modified":
				info.Dirty = setting.Value
			}
		}
	}
	return info
}
