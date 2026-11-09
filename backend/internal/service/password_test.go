package service

import (
	"errors"
	"strings"
	"testing"
)

func TestNewPasswordsUseCommonCharacters(t *testing.T) {
	for _, password := range []string{strings.Repeat("a", 5), strings.Repeat("a", 19), "password123密", "password 1234", "password123\n", "password123😀"} {
		if _, err := HashPassword(password); !errors.Is(err, ErrInvalidPassword) {
			t.Fatal("invalid new password accepted")
		}
	}
	for _, password := range []string{"Passw0rd!@#$", strings.Repeat("~", 18)} {
		encoded, err := HashPassword(password)
		if err != nil || !VerifyPassword(encoded, password) {
			t.Fatal("valid new password failed to round-trip")
		}
	}
}
