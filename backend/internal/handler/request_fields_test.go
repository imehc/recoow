package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHTTPResponsesUseCanonicalFieldsForAllClients(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("from_ms") != "1000" || r.URL.Query().Get("device_id") != "device" {
			t.Fatal("query not normalized")
		}
		writeJSON(w, 200, map[string]any{"accountId": "account", "origin": map[string]string{"appVersion": "1"}})
	})
	for _, client := range []string{"", "ios", "android", "web"} {
		for _, mode := range []string{"", "snake_case", "camelCase"} {
			request := httptest.NewRequest("GET", "/api/example?fromMs=1000&device_id=device", nil)
			request.Header.Set("X-Recoow-Client", client)
			request.Header.Set("X-Recoow-Field-Case", mode)
			response := httptest.NewRecorder()
			apiQueryParameters(next).ServeHTTP(response, request)
			var object map[string]json.RawMessage
			if err := json.Unmarshal(response.Body.Bytes(), &object); err != nil {
				t.Fatal(err)
			}
			if response.Code != 200 || string(object["account_id"]) != `"account"` || string(object["origin"]) != `{"app_version":"1"}` {
				t.Fatalf("%s %s: %s", client, mode, response.Body.String())
			}
			if _, exists := object["accountId"]; exists {
				t.Fatal("响应仍包含旧版信封字段")
			}
			if response.Header().Get("X-Recoow-Field-Case") != "" || response.Header().Get("Vary") != "" {
				t.Fatal("响应不应再协商字段名")
			}
		}
	}
}

func TestCanonicalAndLegacyRequestAliasesDecodeAndCollisionsFail(t *testing.T) {
	handler := apiQueryParameters(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			DeviceID string `json:"device_id"`
		}
		if err := decodeJSON(w, r, &request); err != nil {
			writeError(w, 400, "invalid fields")
			return
		}
		writeJSON(w, 200, map[string]string{"device_id": request.DeviceID})
	}))
	for _, body := range []string{`{"device_id":"device"}`, `{"deviceId":"device"}`} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("POST", "/api/example", strings.NewReader(body)))
		if response.Code != 200 || !strings.Contains(response.Body.String(), `"device_id":"device"`) {
			t.Fatal(response.Body.String())
		}
	}
	for _, path := range []string{"/api/example", "/api/example?deviceId=a&device_id=b"} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("POST", path, strings.NewReader(`{"device_id":"a","deviceId":"b"}`)))
		if response.Code != 400 {
			t.Fatal("accepted alias collision")
		}
	}
}
