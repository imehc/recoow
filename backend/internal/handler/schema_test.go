package handler

import (
	"net/http"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
)

// TestSchemaEndpointAndUnknownPush 校验字段清单下发与未知实体的宽容跳过。
func TestSchemaEndpointAndUnknownPush(t *testing.T) {
	e := newEnv(t)
	ownerToken, _ := e.initAccount(t)

	if response, _ := e.request(t, http.MethodGet, "/api/schema", ownerToken, nil); response.Code != http.StatusNotFound {
		t.Fatal("旧 schema 接口必须移除")
	}
	resp, data := e.request(t, http.MethodGet, "/api/openapi.yaml", "", nil)
	if resp.Code != http.StatusOK {
		t.Fatal("OpenAPI 文档应公开可读")
	}

	// 混合批次：清单外的表被宽容跳过(applied=false)，清单内的表正常入库。
	resp, data = e.request(t, http.MethodPost, "/api/sync/mutations", ownerToken, map[string]any{
		"items": []domain.PushItem{
			item(t, "bills", "bill-1", domain.OpInsert, 1000, "web"),
			item(t, "food_entries", "f1", domain.OpInsert, 1, "web"),
		},
	})
	if resp.Code != http.StatusOK {
		t.Fatalf("混合 push 应 200, 实际 %d body=%s", resp.Code, data)
	}
	out2 := decode[struct {
		Results []domain.PushResult `json:"results"`
	}](t, data)
	if len(out2.Results) != 2 {
		t.Fatalf("应返回 2 条结果, 实际 %d", len(out2.Results))
	}
	var billsOK, unknownSkipped bool
	for _, r := range out2.Results {
		if r.Table == "bills" && r.Applied {
			billsOK = true
		}
		if r.Table == "food_entries" && !r.Applied {
			unknownSkipped = true
		}
	}
	if !billsOK || !unknownSkipped {
		t.Fatalf("期望 bills 入库、food_entries 跳过, 实际 %+v", out2.Results)
	}

	// 缺少 id / 时间戳的变更仍应 400（结构性错误）。
	resp, _ = e.request(t, http.MethodPost, "/api/sync/mutations", ownerToken, map[string]any{
		"items": []domain.PushItem{{Table: "bills", ID: "", Operation: domain.OpInsert, UpdatedAtMs: 1}},
	})
	if resp.Code != http.StatusBadRequest {
		t.Fatalf("缺 id 应 400, 实际 %d", resp.Code)
	}
}
