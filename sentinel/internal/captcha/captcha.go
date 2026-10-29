// Package captcha provides self-hosted CAPTCHA using Altcha proof-of-work.
// No third-party service or network call is required — challenges are issued
// and verified locally using HMAC-SHA256 (pure stdlib, no external library).
//
// Flow:
//  1. Client calls GET /altcha/challenge to receive a challenge JSON.
//  2. Client solves the proof-of-work (altcha.js handles this in the browser).
//  3. Client base64-encodes the solution JSON and sends it in X-Captcha-Token.
//  4. RequireCaptcha middleware calls Verify, which checks the HMAC and PoW.
package captcha

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Challenge is sent to the client to solve.
type Challenge struct {
	Algorithm string `json:"algorithm"`
	Challenge string `json:"challenge"`
	MaxNumber int64  `json:"maxNumber"`
	Salt      string `json:"salt"`
	Signature string `json:"signature"`
}

// payload is the base64-decoded solution submitted by the client.
type payload struct {
	Algorithm string `json:"algorithm"`
	Challenge string `json:"challenge"`
	Number    int64  `json:"number"`
	Salt      string `json:"salt"`
	Signature string `json:"signature"`
}

// Verifier issues and verifies Altcha challenges.
type Verifier struct {
	hmacKey   string
	maxNumber int64
	expires   time.Duration
}

// New creates a Verifier. Pass an empty hmacKey to disable (all requests pass — local dev only).
func New(hmacKey string, maxNumber int64, expires time.Duration) *Verifier {
	return &Verifier{hmacKey: hmacKey, maxNumber: maxNumber, expires: expires}
}

// Enabled reports whether CAPTCHA verification is active.
func (v *Verifier) Enabled() bool { return v.hmacKey != "" }

// Challenge generates a new Altcha challenge to send to the client.
func (v *Verifier) Challenge() (Challenge, error) {
	// Random 12-byte salt, hex-encoded
	saltBytes := make([]byte, 12)
	if _, err := rand.Read(saltBytes); err != nil {
		return Challenge{}, fmt.Errorf("altcha: rand: %w", err)
	}
	salt := hex.EncodeToString(saltBytes)

	// Append expiry as a query param, then terminate with & (Altcha spec)
	params := url.Values{}
	params.Set("expires", strconv.FormatInt(time.Now().Add(v.expires).Unix(), 10))
	salt = salt + "?" + params.Encode() + "&"

	// Pick a random secret number
	n, err := rand.Int(rand.Reader, big.NewInt(v.maxNumber+1))
	if err != nil {
		return Challenge{}, fmt.Errorf("altcha: rand int: %w", err)
	}
	number := n.Int64()

	challengeHex := sha256Hex(salt + strconv.FormatInt(number, 10))
	sigHex := hmacSHA256Hex(challengeHex, v.hmacKey)

	return Challenge{
		Algorithm: "SHA-256",
		Challenge: challengeHex,
		MaxNumber: v.maxNumber,
		Salt:      salt,
		Signature: sigHex,
	}, nil
}

// Verify checks the base64-encoded Altcha solution payload from the client.
// remoteIP is accepted for interface compatibility but unused (Altcha is local).
func (v *Verifier) Verify(_ context.Context, raw, _ string) error {
	if !v.Enabled() {
		return nil
	}
	if raw == "" {
		return fmt.Errorf("altcha: missing solution payload")
	}

	decoded, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return fmt.Errorf("altcha: base64 decode: %w", err)
	}

	var p payload
	if err := json.Unmarshal(decoded, &p); err != nil {
		return fmt.Errorf("altcha: json decode: %w", err)
	}

	// Check expiry embedded in salt query params
	if idx := strings.Index(p.Salt, "?"); idx >= 0 {
		qs, _ := url.ParseQuery(p.Salt[idx+1:])
		if exp := qs.Get("expires"); exp != "" {
			expUnix, err := strconv.ParseInt(exp, 10, 64)
			if err != nil || time.Now().Unix() > expUnix {
				return fmt.Errorf("altcha: challenge expired")
			}
		}
	}

	// Recompute expected challenge and signature
	expectedChallenge := sha256Hex(p.Salt + strconv.FormatInt(p.Number, 10))
	expectedSig := hmacSHA256Hex(expectedChallenge, v.hmacKey)

	if expectedChallenge != p.Challenge || expectedSig != p.Signature {
		return fmt.Errorf("altcha: invalid solution")
	}
	return nil
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func hmacSHA256Hex(data, key string) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(data))
	return hex.EncodeToString(mac.Sum(nil))
}
