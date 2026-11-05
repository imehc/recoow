package handler

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/imehc/recoow/backend/internal/domain"
)

var maxMediaBytes int64 = 200 << 20 // 单文件上限 200 MB

func validKey(k string) bool {
	if k == "" || len(k) > 200 {
		return false
	}
	if strings.Contains(k, "..") || strings.HasPrefix(k, "/") || strings.Contains(k, "\\") {
		return false
	}
	for _, c := range k {
		ok := c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
			c == '.' || c == '_' || c == '-' || c == '/'
		if !ok {
			return false
		}
	}
	return true
}

func (s *Server) handleMediaHead(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	key := r.PathValue("key")
	m, err := s.st.GetMedia(d.AccountID, key)
	if err != nil {
		writeError(w, http.StatusNotFound, "object not found")
		return
	}
	p := s.st.MediaPath(d.AccountID, m.Checksum)
	if _, err := os.Stat(p); err != nil {
		writeError(w, http.StatusNotFound, "object not found")
		return
	}
	w.Header().Set("Content-Type", m.ContentType)
	w.Header().Set("Content-Length", fmt.Sprintf("%d", m.Size))
	w.Header().Set("ETag", `"`+m.Checksum+`"`)
	w.WriteHeader(http.StatusOK)
}

func (s *Server) handleMediaGet(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	key := r.PathValue("key")
	m, err := s.st.GetMedia(d.AccountID, key)
	if err != nil {
		writeError(w, http.StatusNotFound, "object not found")
		return
	}
	p := s.st.MediaPath(d.AccountID, m.Checksum)
	f, err := os.Open(p)
	if err != nil {
		writeError(w, http.StatusNotFound, "object not found")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", m.ContentType)
	w.Header().Set("Content-Length", fmt.Sprintf("%d", st.Size()))
	w.Header().Set("ETag", `"`+m.Checksum+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, f)
}

func (s *Server) handleMediaPut(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	key := r.PathValue("key")
	if !validKey(key) {
		writeError(w, http.StatusBadRequest, "invalid object key")
		return
	}
	expectChecksum := r.URL.Query().Get("checksum")
	contentType := r.URL.Query().Get("contentType")
	if contentType == "" {
		contentType = r.Header.Get("Content-Type")
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	if r.ContentLength > maxMediaBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "media too large")
		return
	}

	// 边写入临时文件边计算 SHA-256 校验和。
	dir := filepath.Dir(s.st.MediaPath(d.AccountID, "x"))
	if dir == "" {
		writeError(w, http.StatusServiceUnavailable, "object storage not configured")
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	tmp, err := os.CreateTemp(dir, ".media-*.tmp")
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	hasher := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, hasher), io.LimitReader(r.Body, maxMediaBytes+1))
	if err != nil {
		tmp.Close()
		writeError(w, http.StatusBadRequest, "read body: "+err.Error())
		return
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := tmp.Close(); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	if n > maxMediaBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "media too large")
		return
	}
	sum := hex.EncodeToString(hasher.Sum(nil))
	if expectChecksum != "" && !strings.EqualFold(expectChecksum, sum) {
		writeError(w, http.StatusBadRequest, "checksum mismatch")
		return
	}
	finalPath := s.st.MediaPath(d.AccountID, sum)
	if err := os.Rename(tmpName, finalPath); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := s.st.PutMedia(d.AccountID, key, sum, contentType, n); err != nil {
		if err == domain.ErrConflict {
			writeError(w, http.StatusConflict, "object key is immutable")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"key":         key,
		"checksum":    sum,
		"size":        n,
		"contentType": contentType,
	})
}

func (s *Server) handleMediaDelete(w http.ResponseWriter, r *http.Request) {
	d, _ := s.deviceFrom(r.Context())
	key := r.PathValue("key")
	if err := s.st.DeleteMedia(d.AccountID, key); err != nil {
		if err == domain.ErrConflict {
			writeError(w, http.StatusConflict, "object is retained by sync history")
			return
		}
		writeError(w, http.StatusNotFound, "object not found")
		return
	}
	// 底层文件按内容寻址且可能被多个 key 共享，删除时只移除映射，
	// 文件留给将来的垃圾回收，而不是急着删。
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
