package mfa

import (
	"bytes"
	"encoding/base64"
	"errors"
	"time"

	"github.com/pquerna/otp"
	"github.com/pquerna/otp/totp"
	qrcode "github.com/skip2/go-qrcode"
)

const (
	totpPeriod = 30 // seconds per time-step (Google Authenticator default)
	totpDigits = otp.DigitsSix
)

var (
	ErrInvalidTOTP = errors.New("mfa: invalid TOTP code")
	ErrTOTPReplay  = errors.New("mfa: TOTP code already used (replay)")
)

// Enrollment is returned to the client once during MFA setup.
type Enrollment struct {
	Secret      string // base32 secret (for manual entry)
	OTPAuthURL  string // otpauth:// URL encoded in the QR
	QRPNGBase64 string // base64-encoded PNG of the QR code
}

// GenerateSecret creates a new TOTP key for the given account and renders a QR
// code. The raw base32 secret is returned for encryption + storage by the caller.
func GenerateSecret(issuer, account string) (*Enrollment, error) {
	key, err := totp.Generate(totp.GenerateOpts{
		Issuer:      issuer,
		AccountName: account,
		Period:      totpPeriod,
		Digits:      totpDigits,
		Algorithm:   otp.AlgorithmSHA1, // SHA1 = authenticator-app compatible
	})
	if err != nil {
		return nil, err
	}

	png, err := qrcode.Encode(key.URL(), qrcode.Medium, 256)
	if err != nil {
		return nil, err
	}

	return &Enrollment{
		Secret:      key.Secret(),
		OTPAuthURL:  key.URL(),
		QRPNGBase64: base64.StdEncoding.EncodeToString(png),
	}, nil
}

// validateTOTPWithStep validates a code against the secret at the given time,
// allowing ±skew steps. On success it returns the matched time-step T so the
// caller can enforce single-use-per-step replay protection.
func validateTOTPWithStep(secret, code string, t time.Time, skew uint) (bool, int64, error) {
	opts := totp.ValidateOpts{
		Period:    totpPeriod,
		Skew:      skew,
		Digits:    totpDigits,
		Algorithm: otp.AlgorithmSHA1,
	}
	ok, err := totp.ValidateCustom(code, secret, t, opts)
	if err != nil {
		return false, 0, err
	}
	if !ok {
		return false, 0, nil
	}
	// Find which step within the skew window matched, so we can record it and
	// reject any step <= the last accepted one.
	step := func(ts time.Time) int64 { return ts.Unix() / totpPeriod }
	base := step(t)
	for d := -int64(skew); d <= int64(skew); d++ {
		cand := t.Add(time.Duration(d) * totpPeriod * time.Second)
		if c, err := totp.GenerateCode(secret, cand); err == nil && bytes.Equal([]byte(c), []byte(code)) {
			return true, step(cand), nil
		}
	}
	// Matched per ValidateCustom but we couldn't localize the step; fall back to
	// the current step (still monotonic, so replay protection holds).
	return true, base, nil
}
