package handler

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/schema"
)

type historyPageResponse struct {
	domain.ChangeEventPage
	NextCursor *string `json:"nextCursor"`
}

func TestHistoryHTTPSourceTimeDetailsAndCursors(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, state := fullSyncEnvironment(t, registry)
	manifest, _ := registry.Get("diary_tags")
	snapshot := contractSnapshot(manifest, "offline-tag")
	snapshot["name"] = "Original name"
	snapshot["future_amount"] = json.Number("9007199254740993")
	item := domain.PushItem{Table: "diary_tags", ID: "offline-tag", MutationID: "offline-create", BaseVersion: new(int64), DataVersion: 1,
		Operation: domain.OpUpdate, UpdatedAtMs: 1000, DeviceID: "device-fixture"}
	item.Snapshot, _ = json.Marshal(snapshot)
	item.Origin = &domain.ChangeOrigin{ClientEnvironment: domain.ClientEnvironment{Client: "ios", OS: "ios", OSVersion: "26.1"}, DeviceID: item.DeviceID, DeviceName: "Offline iPhone"}
	body := map[string]any{"protocolVersion": 2, "generation": state.Generation, "items": []domain.PushItem{item}}
	raw, _ := json.Marshal(body)
	request := httptest.NewRequest("POST", "/api/sync/mutations", bytes.NewReader(raw))
	request.Header.Set("Authorization", "Bearer "+owner)
	request.Header.Set("X-Recoow-Client", "android")
	request.Header.Set("X-Recoow-OS", "android")
	request.Header.Set("X-Recoow-App-Version", "0.7.1")
	response := httptest.NewRecorder()
	e.handler.ServeHTTP(response, request)
	if response.Code != 200 {
		t.Fatalf("push: %d %s", response.Code, response.Body.String())
	}
	created := decode[struct {
		Results []domain.PushResult `json:"results"`
	}](t, response.Body.Bytes()).Results[0]
	if len(created.Events) != 1 {
		t.Fatal("no event associated with receipt")
	}
	ref := created.Events[0]
	_, data := e.request(t, "GET", "/api/change-events/"+ref.EventID, owner, nil)
	detail := decode[domain.ChangeEventDetail](t, data)
	if *detail.Event.ModifiedAtMs != 1000 || *detail.Event.AcceptedAtMs != ref.AcceptedAtMs || detail.Event.OriginalSource.Client != "ios" || detail.Event.Submission.Client != "android" {
		t.Fatalf("time/source mismatch: %s", data)
	}
	if !bytes.Contains(data, []byte("9007199254740993")) || bytes.Contains(data, []byte(`"field":"updated_at"`)) {
		t.Fatalf("lossy or technical difference: %s", data)
	}
	for i := 0; i < 2; i++ {
		id := "later-" + string(rune('a'+i))
		snapshot := contractSnapshot(manifest, id)
		_, data := e.request(t, "POST", "/api/diary-tags", owner, map[string]any{"mutationId": id, "baseVersion": 0, "dataVersion": 1, "updatedAtMs": 1000, "snapshot": snapshot})
		if len(decode[domain.PushResult](t, data).Events) != 1 {
			t.Fatal("CRUD did not record event")
		}
	}
	response, data = e.request(t, "GET", "/api/change-events?limit=1", owner, nil)
	page := decode[historyPageResponse](t, data)
	if response.Code != 200 || !page.More || page.NextCursor == nil || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("page: %d %s", response.Code, data)
	}
	originalAccount := e.accountID
	other, _ := e.initAccount(t)
	response, _ = e.request(t, "GET", "/api/change-events?cursor="+url.QueryEscape(*page.NextCursor), other, nil)
	if response.Code != 400 {
		t.Fatal("foreign history cursor accepted")
	}
	response, _ = e.request(t, "GET", "/api/change-events/"+ref.EventID, other, nil)
	if response.Code != 404 {
		t.Fatal("foreign event visible")
	}
	response, _ = e.request(t, "GET", "/api/change-events?client=ios&cursor="+url.QueryEscape(*page.NextCursor), owner, nil)
	if response.Code != 400 {
		t.Fatal("changed cursor filters accepted")
	}
	response, data = e.request(t, "GET", "/api/change-events?cursor="+url.QueryEscape(*page.NextCursor), owner, nil)
	if response.Code != 200 || len(decode[historyPageResponse](t, data).Events) != 2 {
		t.Fatalf("next page: %d %s", response.Code, data)
	}
	if _, err := e.st.ClearDataset(originalAccount, page.Generation); err != nil {
		t.Fatal(err)
	}
	response, _ = e.request(t, "GET", "/api/change-events?cursor="+url.QueryEscape(*page.NextCursor), owner, nil)
	if response.Code != 409 {
		t.Fatal("cleared dataset accepted old history cursor")
	}
}

func TestReadOnlySessionRejectsWritesRegardlessOfTransportAndHeaders(t *testing.T) {
	registry, err := schema.LoadOpenAPI("../../../openapi/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	e, owner, state := fullSyncEnvironment(t, registry)
	credentials := map[string]any{"username": "read.only", "password": "long-enough-password"}
	response, data := e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	if response.Code != 201 {
		t.Fatalf("bind: %s", data)
	}
	response, data = e.request(t, "POST", "/api/sessions", "", credentials)
	if response.Code != 200 {
		t.Fatalf("login: %s", data)
	}
	login := decode[loginResponse](t, data)
	for _, operation := range []struct{ method, path string }{
		{"POST", "/api/diary-tags"}, {"PUT", "/api/diary-tags/id"}, {"DELETE", "/api/diary-tags/id"},
		{"POST", "/api/sync/mutations"}, {"DELETE", "/api/sync/dataset?generation=" + state.Generation},
		{"PUT", "/api/media/objects/key"}, {"DELETE", "/api/media/objects/key"}, {"DELETE", "/api/devices/id"},
		{"POST", "/api/device-pairing-codes"}, {"PUT", "/api/users/me/password"}, {"POST", "/api/users/me/credentials"},
		{"GET", "/api/exports"}, {"GET", "/api/exports/sql"},
	} {
		for _, cookie := range []bool{false, true} {
			request := httptest.NewRequest(operation.method, operation.path, bytes.NewBufferString("{}"))
			if cookie {
				request.AddCookie(response.Result().Cookies()[0])
				request.Header.Set("Origin", "https://example.com")
			} else {
				request.Header.Set("Authorization", "Bearer "+login.AccessToken)
			}
			request.Header.Set("X-Recoow-Client", "ios")
			denied := httptest.NewRecorder()
			e.handler.ServeHTTP(denied, request)
			if denied.Code != 403 {
				t.Fatalf("read-only %s %s cookie=%v: %d", operation.method, operation.path, cookie, denied.Code)
			}
		}
	}
	for _, path := range []string{"/api/users/me", "/api/devices", "/api/statistics", "/api/diary-tags", "/api/change-events"} {
		response, _ := e.request(t, "GET", path, login.AccessToken, nil)
		if response.Code != 200 {
			t.Fatalf("read denied: %s %d", path, response.Code)
		}
	}
}
