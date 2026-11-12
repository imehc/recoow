package handler

import (
	"github.com/imehc/recoow/backend/internal/handler/generated"
	"testing"
)

func TestBillStatisticsReadSessionAndFilterValidation(t *testing.T) {
	e := newEnv(t)
	owner, _ := e.initAccount(t)
	credentials := map[string]string{"username": "bill.statistics", "password": "web-password"}
	e.request(t, "POST", "/api/users/me/credentials", owner, credentials)
	_, body := e.request(t, "POST", "/api/sessions", "", credentials)
	token := decode[loginResponse](t, body).AccessToken
	response, body := e.request(t, "GET", "/api/statistics/bills?year=2026&timezone=Asia%2FShanghai", token, nil)
	if response.Code != 200 {
		t.Fatalf("read session statistics rejected: %s", body)
	}
	stats := decode[generated.BillStatistics](t, body)
	if len(stats.Months) != 12 || len(stats.PreviousMonths) != 12 || len(stats.Categories) != 0 {
		t.Fatal("incomplete month frame")
	}
	for _, query := range []string{"year=1970&timezone=UTC", "year=2026&timezone=Local", "year=2026&timezone=Unknown%2FZone", "year=2026", "timezone=UTC"} {
		if response, _ := e.request(t, "GET", "/api/statistics/bills?"+query, owner, nil); response.Code != 400 {
			t.Fatalf("invalid statistics accepted: %s", query)
		}
	}
	for _, path := range []string{"/api/bills?transaction_type=unknown", "/api/bills?exclude_refunded=unknown", "/api/reminders?category=other"} {
		if response, _ := e.request(t, "GET", path, owner, nil); response.Code != 400 {
			t.Fatalf("invalid bill filter accepted: %s", path)
		}
	}
}
