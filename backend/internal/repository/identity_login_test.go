package repository

import (
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/imehc/recoow/backend/internal/domain"
)

func TestPasswordVersionProtectsDeviceLoginAndConcurrentChanges(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		user, err := store.BindUser(account, "password-owner", "old-hash")
		if err != nil {
			t.Fatal(err)
		}
		device, err := store.CreateLoginDevice(user, "Phone", []byte("login-token"))
		if err != nil || device.IsOwner || device.AccountID != account {
			t.Fatalf("login device: %+v %v", device, err)
		}
		webDevice, err := store.CreateDevice(account, "Web session", []byte("hidden-web-token"), true)
		if err != nil {
			t.Fatal(err)
		}
		if err := store.CreateSession(domain.Session{ID: "web-session", UserID: user.ID, DeviceID: webDevice.ID, TokenHash: "web-token", PasswordHash: "old-hash", ExpiresAt: time.Now().Add(time.Hour)}); err != nil {
			t.Fatal(err)
		}
		var group sync.WaitGroup
		results := make(chan error, 2)
		for _, hash := range []string{"first-new-hash", "second-new-hash"} {
			group.Add(1)
			go func() {
				defer group.Done()
				results <- store.SetPasswordIfCurrent(user.ID, "old-hash", hash)
			}()
		}
		group.Wait()
		close(results)
		var applied, rejected int
		for err := range results {
			if err == nil {
				applied++
			} else if errors.Is(err, domain.ErrConflict) {
				rejected++
			} else {
				t.Fatal(err)
			}
		}
		if applied != 1 || rejected != 1 {
			t.Fatal("concurrent password change overwrote a newer password")
		}
		if _, err := store.SessionByToken("web-token"); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("password change retained a Web session")
		}
		if _, err := store.GetDevice(device.ID); err != nil {
			t.Fatal("password change revoked a long-lived login device")
		}
		before, _ := store.ListDevices(account)
		if _, err := store.CreateLoginDevice(user, "Stale phone", []byte("stale-login")); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("stale password verification issued a device token")
		}
		after, _ := store.ListDevices(account)
		if len(before) != len(after) {
			t.Fatal("failed login left a device behind")
		}
		current, _ := store.UserByAccount(account)
		if _, err := store.CreateLoginDevice(current, "Current phone", []byte("current-login")); err != nil {
			t.Fatal(err)
		}
		current.AccountID = "another-account"
		if _, err := store.CreateLoginDevice(current, "Wrong account", []byte("cross-account-login")); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("login could create a device in another account")
		}
		if err := store.SetPasswordIfCurrent(user.ID, "", "unchecked-hash"); !errors.Is(err, domain.ErrConflict) {
			t.Fatal("empty password version bypassed comparison")
		}
	})
}
