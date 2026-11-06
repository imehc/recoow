package service

import (
	"encoding/json"
	"testing"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestHistoryDifferencesRetainUnknownFieldsNullAndExactNumbers(t *testing.T) {
	detail := domain.ChangeEventDetail{
		Before: &domain.Row{Snapshot: json.RawMessage(`{"amount":9007199254740993,"equivalent":1,"nullable":null,"updated_at":1}`)},
		After:  domain.Row{Snapshot: json.RawMessage(`{"amount":9007199254740994,"equivalent":1e0,"nullable":null,"new_null":null,"updated_at":2}`)},
	}
	changes, err := HistoryChanges(detail)
	if err != nil || len(changes) != 2 || changes[0].Field != "amount" || string(changes[0].BeforeValue) != "9007199254740993" || changes[1].Field != "new_null" || changes[1].BeforePresent || !changes[1].AfterPresent {
		t.Fatalf("differences: %+v %v", changes, err)
	}
	oldItem := domain.PushItem{MutationID: "old", Snapshot: json.RawMessage(`{"name":"unchanged"}`)}
	oldHash, _ := MutationRequestHash(oldItem)
	oldItem.Origin = &domain.ChangeOrigin{ClientEnvironment: domain.ClientEnvironment{Client: "ios", OS: "ios"}}
	newHash, _ := MutationRequestHash(oldItem)
	if oldHash == newHash {
		t.Fatal("frozen origin excluded from new mutation hash")
	}
}
