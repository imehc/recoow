package handler

import (
	"net/http"

	"github.com/imehc/recoow/backend/internal/handler/generated"
)

func (s *Server) handleAccountWebAccess(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	device, _ := s.deviceFrom(r.Context())
	if r.Method == http.MethodPut {
		// 指针区分显式关闭与遗漏/null，避免不完整请求撤销账号授权。
		var input struct {
			Enabled *bool `json:"enabled"`
		}
		if err := decodeJSON(w, r, &input); err != nil || input.Enabled == nil {
			writeError(w, http.StatusBadRequest, "enabled boolean required")
			return
		}
		if err := s.st.SetAccountWebRecordWrites(device.AccountID, *input.Enabled); err != nil {
			writeError(w, http.StatusInternalServerError, "account web access update failed")
			return
		}
		writeJSON(w, http.StatusOK, generated.AccountWebAccess{Enabled: *input.Enabled})
		return
	}
	account, err := s.st.GetAccount(device.AccountID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "account web access unavailable")
		return
	}
	writeJSON(w, http.StatusOK, generated.AccountWebAccess{Enabled: account.WebRecordWrites})
}
