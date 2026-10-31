// Package buildinfo separates executable provenance from sync/schema versions.
package buildinfo

import "runtime/debug"

// Overridden by the package script when building a distribution binary.
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
