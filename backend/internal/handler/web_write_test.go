package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"github.com/imehc/recoow/backend/internal/schema"
)

func TestWebWriteScopeReceiptsHistoryAndSync(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, state := fullSyncEnvironment(t, registry)
	credentials := map[string]any{"username": "web.editor", "password": "web-password"}
	if response, _ := e.request(t, "POST", "/api/users/me/credentials", owner, credentials); response.Code != 201 {
		t.Fatal("bind")
	}
	_, raw := e.request(t, "POST", "/api/sessions", "", credentials)
	old := decode[loginResponse](t, raw)
	if old.GetAccess() != "read_only" || len(old.WritableTables) != 0 {
		t.Fatal("account must default to read only")
	}
	if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, map[string]any{"enabled": true}); response.Code != 200 {
		t.Fatal("owner enable")
	}
	response, raw := e.request(t, "POST", "/api/sessions", "", credentials)
	login := decode[loginResponse](t, raw)
	if response.Code != 200 || login.GetAccess() != "read_write" || len(login.WritableTables) != 4 {
		t.Fatalf("scope: %s", raw)
	}
	_, raw = e.request(t, "GET", "/api/users/me", login.AccessToken, nil)
	me := decode[generated.IdentityResponse](t, raw)
	if me.GetAccess() != "read_write" || len(me.WritableTables) != 4 {
		t.Fatalf("me: %s", raw)
	}
	_, raw = e.request(t, "GET", "/api/users/me", old.AccessToken, nil)
	me = decode[generated.IdentityResponse](t, raw)
	if me.GetAccess() != "read_write" || len(me.WritableTables) != 4 {
		t.Fatal("existing session did not apply latest account access")
	}
	for _, table := range webWritableTables {
		manifest, _ := registry.Get(table)
		snapshot := contractSnapshot(manifest, "web-"+table)
		snapshot["future_amount"] = json.Number("9007199254740993")
		mutation := map[string]any{"generation": state.Generation, "mutation_id": table + "-create", "base_version": 0, "data_version": manifest.DataVersion, "updated_at_ms": 1000, "snapshot": snapshot}
		path := "/api/" + strings.ReplaceAll(table, "_", "-")
		response, raw = e.request(t, "POST", path, old.AccessToken, mutation)
		created := decode[domain.PushResult](t, raw)
		if response.Code != 201 || created.Status != domain.PushApplied || len(created.Events) != 1 {
			t.Fatalf("create %s: %d %s", table, response.Code, raw)
		}
		response, raw = e.request(t, "POST", path, old.AccessToken, mutation)
		if response.Code != 201 || decode[domain.PushResult](t, raw).Version != created.Version {
			t.Fatalf("retry: %s", raw)
		}
		_, raw = e.request(t, "GET", "/api/change-events/"+created.Events[0].EventID, owner, nil)
		detail := decode[domain.ChangeEventDetail](t, raw)
		if detail.Event.Submission.Client != "web" || detail.Event.Submission.SessionID == "" || !bytes.Contains(raw, []byte("9007199254740993")) {
			t.Fatalf("source/precision: %s", raw)
		}
		mutation["mutation_id"], mutation["base_version"], mutation["updated_at_ms"] = table+"-edit", created.Version, 2000
		if table == "diary_tags" {
			snapshot["name"] = "Edited"
		} else {
			snapshot["title"] = "Edited"
		}
		response, raw = e.request(t, "PUT", path+"/web-"+table, login.AccessToken, mutation)
		edited := decode[domain.PushResult](t, raw)
		if response.Code != 200 || edited.Status != domain.PushApplied {
			t.Fatalf("edit: %d %s", response.Code, raw)
		}
		mutation["mutation_id"], mutation["base_version"], mutation["updated_at_ms"] = table+"-delete", edited.Version, 3000
		delete(mutation, "snapshot")
		response, raw = e.request(t, "DELETE", path+"/web-"+table, login.AccessToken, mutation)
		if response.Code != 200 || decode[domain.PushResult](t, raw).Status != domain.PushApplied {
			t.Fatalf("delete: %d %s", response.Code, raw)
		}
	}
	_, raw = e.request(t, "GET", "/api/sync/changes?generation="+state.Generation, owner, nil)
	if !bytes.Contains(raw, []byte("web-diary_tags")) || !bytes.Contains(raw, []byte("9007199254740993")) {
		t.Fatalf("mobile sync lost writes: %s", raw)
	}
	for _, forbidden := range []struct{ method, path string }{
		{"POST", "/api/sync/mutations"}, {"DELETE", "/api/sync/dataset"}, {"PUT", "/api/media/objects/key"},
		{"DELETE", "/api/media/objects/key"}, {"PUT", "/api/users/me/password"}, {"POST", "/api/users/me/credentials"},
		{"POST", "/api/device-pairing-codes"}, {"DELETE", "/api/devices/other"}, {"GET", "/api/exports"},
		{"GET", "/api/exports/sql"}, {"POST", "/api/item-categories"}, {"PUT", "/api/stored-value-accounts/other"},
		{"GET", "/api/accounts/current/web-access"}, {"PUT", "/api/accounts/current/web-access"},
	} {
		for _, cookie := range []bool{false, true} {
			request := httptest.NewRequest(forbidden.method, forbidden.path, bytes.NewBufferString(`{}`))
			request.Header.Set("X-Recoow-Client", "ios")
			if cookie {
				request.AddCookie(responseCookie(login.AccessToken))
				request.Header.Set("Origin", "https://example.com")
			} else {
				request.Header.Set("Authorization", "Bearer "+login.AccessToken)
			}
			recorder := httptest.NewRecorder()
			e.handler.ServeHTTP(recorder, request)
			if recorder.Code != 403 {
				t.Fatalf("scope escaped: %s %s cookie=%v: %d", forbidden.method, forbidden.path, cookie, recorder.Code)
			}
		}
	}
	if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, map[string]any{"enabled": false}); response.Code != 200 {
		t.Fatal("owner disable")
	}
	_, raw = e.request(t, "GET", "/api/users/me", login.AccessToken, nil)
	me = decode[generated.IdentityResponse](t, raw)
	if me.GetAccess() != "read_only" {
		t.Fatal("disabled account retains permissions")
	}
	if response, _ := e.request(t, "POST", "/api/diary-tags", login.AccessToken, map[string]any{}); response.Code != 403 {
		t.Fatal("disabled account can write")
	}
	if response, _ := e.request(t, "GET", "/api/exports", owner, nil); response.Code != 200 {
		t.Fatal("mobile permissions changed")
	}
}

func responseCookie(token string) *http.Cookie {
	return &http.Cookie{Name: "recoow_session", Value: token}
}

func TestWebWriteGenerationAndCookieOrigin(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, state := fullSyncEnvironment(t, registry)
	credentials := map[string]any{"username": "web.generation", "password": "web-password"}
	e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	if response, _ := e.request(t, "PUT", "/api/accounts/current/web-access", owner, map[string]any{"enabled": true}); response.Code != 200 {
		t.Fatal("owner enable")
	}
	_, raw := e.request(t, "POST", "/api/sessions", "", credentials)
	login := decode[loginResponse](t, raw)
	manifest, _ := registry.Get("diary_tags")
	mutation := map[string]any{"mutation_id": "generation-check", "base_version": 0, "data_version": 1, "updated_at_ms": 1000, "snapshot": contractSnapshot(manifest, "tag")}
	if response, _ := e.request(t, "POST", "/api/diary-tags", login.AccessToken, mutation); response.Code != 400 {
		t.Fatal("missing generation accepted")
	}
	mutation["generation"] = "stale-generation"
	if response, raw := e.request(t, "POST", "/api/diary-tags", login.AccessToken, mutation); response.Code != 409 || !bytes.Contains(raw, []byte("reset_required")) {
		t.Fatalf("stale generation accepted: %s", raw)
	}
	mutation["generation"] = state.Generation
	encoded, _ := json.Marshal(mutation)
	request := httptest.NewRequest("POST", "/api/diary-tags", bytes.NewReader(encoded))
	request.AddCookie(responseCookie(login.AccessToken))
	recorder := httptest.NewRecorder()
	e.handler.ServeHTTP(recorder, request)
	if recorder.Code != 403 {
		t.Fatal("missing origin accepted")
	}
	request = httptest.NewRequest("POST", "/api/diary-tags", bytes.NewReader(encoded))
	request.AddCookie(responseCookie(login.AccessToken))
	request.Header.Set("Origin", "https://example.com")
	recorder = httptest.NewRecorder()
	e.handler.ServeHTTP(recorder, request)
	if recorder.Code != 201 {
		t.Fatalf("cookie write: %d %s", recorder.Code, recorder.Body.String())
	}
}
