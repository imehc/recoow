package handler

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/service"
	"net/http"
	"testing"
)

func TestClearDatasetRequiresOwnerAndCurrentGeneration(t *testing.T) {
	env := newEnv(t)
	owner, _ := env.initAccount(t)
	_, err := env.st.CreateDevice(env.accountID, "member", service.HashToken("member-token"), false)
	if err != nil {
		t.Fatal(err)
	}
	state, _ := env.st.SyncState(env.accountID)
	path := "/api/sync/dataset?generation=" + state.Generation
	for _, test := range []struct {
		token, path string
		status      int
	}{
		{"", path, http.StatusUnauthorized}, {"member-token", path, http.StatusForbidden},
		{owner, "/api/sync/dataset", http.StatusBadRequest}, {owner, "/api/sync/dataset?generation=stale", http.StatusConflict},
	} {
		if response, _ := env.request(t, http.MethodDelete, test.path, test.token, nil); response.Code != test.status {
			t.Fatalf("expected %d, got %d: %s", test.status, response.Code, response.Body)
		}
	}
	response, data := env.request(t, http.MethodDelete, path, owner, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("clear failed: %d %s", response.Code, data)
	}
	cleared := decode[domain.DatasetState](t, data)
	if cleared.Generation == state.Generation || cleared.Head != 0 {
		t.Fatal("invalid reset state", cleared)
	}
	if response, _ := env.request(t, http.MethodGet, "/api/sync/snapshots?generation="+state.Generation, owner, nil); response.Code != http.StatusConflict {
		t.Fatal("old generation accepted")
	}
	if response, _ := env.request(t, http.MethodGet, "/api/devices", owner, nil); response.Code != http.StatusOK {
		t.Fatal("owner binding lost")
	}
}
