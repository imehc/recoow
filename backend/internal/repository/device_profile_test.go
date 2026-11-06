package repository

import (
	"github.com/imehc/recoow/backend/internal/domain"
	"testing"
)

func TestSourceRegistrationIsAccountScopedUniqueAndHistorical(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		profile := domain.DeviceProfile{ClientEnvironment: domain.ClientEnvironment{Client: "ios", OS: "ios", AppVersion: "original"}, LocalDeviceID: "local-phone"}
		original, err := store.CreateDevice(account, "iPhone", []byte("original-phone-token"), false, profile)
		if err != nil {
			t.Fatal(err)
		}
		uploader, err := store.CreateDevice(account, "Android", []byte("uploader-token"), true)
		if err != nil {
			t.Fatal(err)
		}
		other, err := store.InitAccount([]byte("foreign-source-account"))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.CreateDevice(other.ID, "foreign", []byte("foreign-source-token"), true, profile); err != nil {
			t.Fatal(err)
		}
		push := func(id string) domain.ChangeEvent {
			item := revisionItem("diary_tags", id, id, 0, map[string]any{"name": id})
			item.DeviceID = profile.LocalDeviceID
			item.Origin = &domain.ChangeOrigin{ClientEnvironment: profile.ClientEnvironment, DeviceID: item.DeviceID, DeviceName: "original iPhone"}
			results, err := store.PushWithContext(account, []domain.PushItem{item}, domain.PushContext{Submission: &domain.SubmissionSource{ClientEnvironment: domain.ClientEnvironment{Client: "android", OS: "android"}, DeviceID: uploader.ID, DeviceName: uploader.Name}})
			if err != nil {
				t.Fatal(err)
			}
			detail, err := store.ChangeEvent(account, results[0].Events[0].EventID)
			if err != nil {
				t.Fatal(err)
			}
			return detail.Event
		}
		event := push("restored-offline-edit")
		if event.OriginalRegisteredDeviceID == nil || *event.OriginalRegisteredDeviceID != original.ID || event.Submission.DeviceID != uploader.ID {
			t.Fatalf("source association: %+v", event)
		}
		if err := store.UpdateDeviceProfile(other.ID, original.ID, "forged", profile); err != domain.ErrNotFound {
			t.Fatal("foreign profile update accepted")
		}
		if _, err := store.CreateDevice(account, "ambiguous copy", []byte("ambiguous-token"), false, profile); err != nil {
			t.Fatal(err)
		}
		if push("ambiguous-edit").OriginalRegisteredDeviceID != nil {
			t.Fatal("ambiguous source was guessed")
		}
		if err := store.DeleteDevice(original.ID); err != nil {
			t.Fatal(err)
		}
		detail, err := store.ChangeEvent(account, event.EventID)
		if err != nil || detail.Event.OriginalRegisteredDeviceID == nil || *detail.Event.OriginalRegisteredDeviceID != original.ID || detail.Event.OriginalSource.AppVersion != "original" {
			t.Fatal("historical source changed after revoke")
		}
	})
}
