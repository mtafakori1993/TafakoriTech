package mfa

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
)

var (
	// ErrInvalidKey is returned when the AES key is not 32 bytes.
	ErrInvalidKey = errors.New("mfa: encryption key must be 32 bytes")
	// ErrDecrypt is returned when ciphertext fails to decrypt or authenticate.
	ErrDecrypt = errors.New("mfa: decryption failed")
)

// Crypto encrypts TOTP secrets at rest (AES-256-GCM) and hashes recovery codes
// (peppered SHA-256). It holds key material and must never be logged.
type Crypto struct {
	key    []byte // 32-byte AES-256 key
	pepper []byte // mixed into recovery-code hashes
}

// NewCrypto validates the key length and returns a Crypto helper.
func NewCrypto(key, pepper []byte) (*Crypto, error) {
	if len(key) != 32 {
		return nil, ErrInvalidKey
	}
	return &Crypto{key: key, pepper: pepper}, nil
}

// Encrypt seals plaintext with AES-256-GCM and returns nonce||ciphertext.
// A fresh random nonce is generated per call.
func (c *Crypto) Encrypt(plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(c.key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	// Seal appends ciphertext to nonce, so the result is nonce||ciphertext||tag.
	return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

// Decrypt opens a nonce||ciphertext blob produced by Encrypt.
func (c *Crypto) Decrypt(blob []byte) ([]byte, error) {
	block, err := aes.NewCipher(c.key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	ns := gcm.NonceSize()
	if len(blob) < ns {
		return nil, ErrDecrypt
	}
	nonce, ct := blob[:ns], blob[ns:]
	plaintext, err := gcm.Open(nil, nonce, ct, nil)
	if err != nil {
		return nil, ErrDecrypt
	}
	return plaintext, nil
}

// HashRecoveryCode returns hex(sha256(pepper || code)). Deterministic so the
// hash can be looked up via a unique index for O(1) single-use verification.
func (c *Crypto) HashRecoveryCode(code string) string {
	h := sha256.New()
	h.Write(c.pepper)
	h.Write([]byte(code))
	return hex.EncodeToString(h.Sum(nil))
}

// GenerateRecoveryCodes returns n random, human-readable one-time codes plus
// their hashes. Codes use Crockford-ish base32 (no padding) for ~50 bits each,
// formatted as XXXXX-XXXXX. The plaintext is returned to the caller ONCE; only
// the hashes are persisted.
func (c *Crypto) GenerateRecoveryCodes(n int) (codes []string, hashes []string, err error) {
	codes = make([]string, 0, n)
	hashes = make([]string, 0, n)
	enc := base32.StdEncoding.WithPadding(base32.NoPadding)
	for i := 0; i < n; i++ {
		raw := make([]byte, 8) // 64 bits of entropy
		if _, err = io.ReadFull(rand.Reader, raw); err != nil {
			return nil, nil, err
		}
		s := enc.EncodeToString(raw) // ~13 chars
		code := fmt.Sprintf("%s-%s", s[:5], s[5:10])
		codes = append(codes, code)
		hashes = append(hashes, c.HashRecoveryCode(code))
	}
	return codes, hashes, nil
}

// EqualHash compares two recovery-code hashes in constant time.
func EqualHash(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
