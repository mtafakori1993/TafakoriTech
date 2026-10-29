package mfa

import (
	"bytes"
	"strings"
	"testing"
)

func newTestCrypto(t *testing.T) *Crypto {
	t.Helper()
	key := bytes.Repeat([]byte{0x42}, 32)
	c, err := NewCrypto(key, []byte("test-pepper"))
	if err != nil {
		t.Fatalf("NewCrypto: %v", err)
	}
	return c
}

func TestNewCrypto_RejectsBadKeyLen(t *testing.T) {
	if _, err := NewCrypto([]byte("short"), nil); err != ErrInvalidKey {
		t.Fatalf("want ErrInvalidKey, got %v", err)
	}
}

func TestEncryptDecrypt_Roundtrip(t *testing.T) {
	c := newTestCrypto(t)
	secret := []byte("JBSWY3DPEHPK3PXP")

	blob, err := c.Encrypt(secret)
	if err != nil {
		t.Fatalf("Encrypt: %v", err)
	}
	if bytes.Contains(blob, secret) {
		t.Fatal("ciphertext leaks plaintext")
	}

	got, err := c.Decrypt(blob)
	if err != nil {
		t.Fatalf("Decrypt: %v", err)
	}
	if !bytes.Equal(got, secret) {
		t.Fatalf("roundtrip mismatch: got %q want %q", got, secret)
	}
}

func TestEncrypt_UniqueNoncePerCall(t *testing.T) {
	c := newTestCrypto(t)
	a, _ := c.Encrypt([]byte("same"))
	b, _ := c.Encrypt([]byte("same"))
	if bytes.Equal(a, b) {
		t.Fatal("identical ciphertext for two Encrypt calls — nonce not random")
	}
}

func TestDecrypt_TamperDetected(t *testing.T) {
	c := newTestCrypto(t)
	blob, _ := c.Encrypt([]byte("secret"))
	blob[len(blob)-1] ^= 0xFF // flip a tag bit
	if _, err := c.Decrypt(blob); err != ErrDecrypt {
		t.Fatalf("want ErrDecrypt on tamper, got %v", err)
	}
}

func TestDecrypt_WrongKeyFails(t *testing.T) {
	c := newTestCrypto(t)
	blob, _ := c.Encrypt([]byte("secret"))

	other, _ := NewCrypto(bytes.Repeat([]byte{0x01}, 32), nil)
	if _, err := other.Decrypt(blob); err != ErrDecrypt {
		t.Fatalf("want ErrDecrypt with wrong key, got %v", err)
	}
}

func TestDecrypt_ShortInput(t *testing.T) {
	c := newTestCrypto(t)
	if _, err := c.Decrypt([]byte{0x00}); err != ErrDecrypt {
		t.Fatalf("want ErrDecrypt on short input, got %v", err)
	}
}

func TestHashRecoveryCode_DeterministicAndPeppered(t *testing.T) {
	c := newTestCrypto(t)
	h1 := c.HashRecoveryCode("ABCDE-FGHIJ")
	h2 := c.HashRecoveryCode("ABCDE-FGHIJ")
	if h1 != h2 {
		t.Fatal("hash not deterministic")
	}

	other, _ := NewCrypto(c.key, []byte("different-pepper"))
	if other.HashRecoveryCode("ABCDE-FGHIJ") == h1 {
		t.Fatal("pepper does not affect hash")
	}
}

func TestGenerateRecoveryCodes(t *testing.T) {
	c := newTestCrypto(t)
	codes, hashes, err := c.GenerateRecoveryCodes(10)
	if err != nil {
		t.Fatalf("GenerateRecoveryCodes: %v", err)
	}
	if len(codes) != 10 || len(hashes) != 10 {
		t.Fatalf("want 10 codes+hashes, got %d/%d", len(codes), len(hashes))
	}

	seen := map[string]bool{}
	for i, code := range codes {
		if !strings.Contains(code, "-") {
			t.Errorf("code %q not formatted XXXXX-XXXXX", code)
		}
		if seen[code] {
			t.Errorf("duplicate code generated: %q", code)
		}
		seen[code] = true
		if c.HashRecoveryCode(code) != hashes[i] {
			t.Errorf("hash[%d] does not match code", i)
		}
	}
}

func TestEqualHash(t *testing.T) {
	if !EqualHash("abc", "abc") {
		t.Fatal("equal hashes reported unequal")
	}
	if EqualHash("abc", "abd") {
		t.Fatal("unequal hashes reported equal")
	}
}
