package repository

import (
	"errors"
	"fmt"
	"github.com/imehc/recoow/backend/internal/domain"
	"sync"
	"testing"
	"time"
)

func TestPairingSessionAtomicConsumptionAndWriteRevocation(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		if err := store.IssuePairingCode("code", account, time.Now().Add(time.Hour)); err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		results := make(chan error, 2)
		for i := 0; i < 2; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				_, _, err := store.CreatePairingSession("code", domain.Session{ID: fmt.Sprint(i), TokenHash: fmt.Sprint(i), ExpiresAt: time.Now().Add(time.Hour)}, []byte(fmt.Sprint(i)))
				results <- err
			}(i)
		}
		wg.Wait()
		close(results)
		success, denied := 0, 0
		for err := range results {
			if err == nil {
				success++
			} else if errors.Is(err, domain.ErrNotFound) {
				denied++
			} else {
				t.Fatal(err)
			}
		}
		if success != 1 || denied != 1 {
			t.Fatalf("multiple sessions from one code: %d/%d", success, denied)
		}
		if err := store.IssuePairingCode("fresh", account, time.Now().Add(time.Hour)); err != nil {
			t.Fatal(err)
		}
		session := domain.Session{ID: "write", TokenHash: "write", ExpiresAt: time.Now().Add(time.Hour)}
		_, device, err := store.CreatePairingSession("fresh", session, []byte("device"))
		if err != nil {
			t.Fatal(err)
		}
		if err := store.SetAccountWebRecordWrites(account, true); err != nil {
			t.Fatal(err)
		}
		context := domain.PushContext{SessionID: session.ID, RequireWebWrite: true, DirectEdit: true}
		if _, err := store.PushWithContext(account, []domain.PushItem{revisionItem("diary_tags", "before", "before", 0, map[string]any{"name": "before"})}, context); err != nil {
			t.Fatal(err)
		}
		if err := store.DeleteDevice(device.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := store.PushWithContext(account, []domain.PushItem{revisionItem("diary_tags", "after", "after", 0, map[string]any{"name": "after"})}, context); !errors.Is(err, domain.ErrSessionRevoked) {
			t.Fatalf("revoked session committed: %v", err)
		}
		if _, err := store.SessionByToken(session.TokenHash); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("removed device retained a session")
		}
	})
}

func TestPairingSessionFailurePreservesCodeAndPasswordResetRevokesUnboundSession(t *testing.T) {
	withSyncStores(t, func(t *testing.T, store domain.Store, account string) {
		session := domain.Session{ID: "first", TokenHash: "first", ExpiresAt: time.Now().Add(time.Hour)}
		store.IssuePairingCode("first-code", account, time.Now().Add(time.Hour))
		_, _, err := store.CreatePairingSession("first-code", session, []byte("first-device"))
		if err != nil {
			t.Fatal(err)
		}
		store.IssuePairingCode("retry-code", account, time.Now().Add(time.Hour))
		if _, _, err := store.CreatePairingSession("retry-code", session, []byte("duplicate-device")); err == nil {
			t.Fatal("duplicate session accepted")
		}
		session.ID, session.TokenHash = "retry", "retry"
		if _, _, err := store.CreatePairingSession("retry-code", session, []byte("retry-device")); err != nil {
			t.Fatalf("failed login consumed code: %v", err)
		}
		user, err := store.BindUser(account, "paired-owner", "old-hash")
		if err != nil {
			t.Fatal(err)
		}
		if err := store.SetPassword(user.ID, "new-hash"); err != nil {
			t.Fatal(err)
		}
		if _, err := store.SessionByToken("first"); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("password change retained previously unbound session")
		}
	})
}
