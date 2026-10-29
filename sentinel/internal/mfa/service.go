package mfa

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const recoveryCodeCount = 10

var (
	ErrMFAAlreadyEnabled = errors.New("mfa: already enabled")
	ErrMFANotPending     = errors.New("mfa: setup not started")
	ErrMFANotEnabled     = errors.New("mfa: not enabled")
	ErrUserNotFound      = errors.New("mfa: user not found")
)

// State values for users.mfa_state.
const (
	StateDisabled = "disabled"
	StatePending  = "pending_setup"
	StateEnabled  = "enabled"
)

// userMFA is the MFA-relevant projection of a users row.
type userMFA struct {
	UserID       int64
	Identifier   string
	State        string
	SecretEnc    []byte
	LastTimestep *int64
}

// Service owns MFA enrollment, verification, and recovery-code logic.
// It is stateless beyond the shared DB; safe to run on every replica.
type Service struct {
	db     *pgxpool.Pool
	crypto *Crypto
	issuer string
	skew   uint
}

func NewService(db *pgxpool.Pool, crypto *Crypto, issuer string, skew uint) *Service {
	return &Service{db: db, crypto: crypto, issuer: issuer, skew: skew}
}

func (s *Service) load(ctx context.Context, q pgx.Row) (*userMFA, error) {
	var u userMFA
	err := q.Scan(&u.UserID, &u.Identifier, &u.State, &u.SecretEnc, &u.LastTimestep)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

func (s *Service) get(ctx context.Context, userID int64) (*userMFA, error) {
	return s.load(ctx, s.db.QueryRow(ctx,
		`SELECT id, identifier, mfa_state, mfa_secret_enc, mfa_last_timestep
		   FROM users WHERE id=$1`, userID))
}

// State returns the current MFA state for a user.
func (s *Service) State(ctx context.Context, userID int64) (string, error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return "", err
	}
	return u.State, nil
}

// Setup generates a fresh TOTP secret, encrypts it, moves the user to
// pending_setup, and returns the enrollment artifacts (shown once).
func (s *Service) Setup(ctx context.Context, userID int64) (*Enrollment, error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return nil, err
	}
	if u.State == StateEnabled {
		return nil, ErrMFAAlreadyEnabled
	}

	enr, err := GenerateSecret(s.issuer, u.Identifier)
	if err != nil {
		return nil, err
	}
	enc, err := s.crypto.Encrypt([]byte(enr.Secret))
	if err != nil {
		return nil, err
	}

	_, err = s.db.Exec(ctx,
		`UPDATE users
		    SET mfa_state='pending_setup', mfa_secret_enc=$1, mfa_last_timestep=NULL
		  WHERE id=$2`, enc, userID)
	if err != nil {
		return nil, err
	}
	return enr, nil
}

// VerifySetup confirms the first TOTP code, activates MFA, and returns a fresh
// set of one-time recovery codes (plaintext, shown once).
func (s *Service) VerifySetup(ctx context.Context, userID int64, code string) ([]string, error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return nil, err
	}
	if u.State != StatePending {
		return nil, ErrMFANotPending
	}

	ok, step, err := s.checkTOTP(u, code)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, ErrInvalidTOTP
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	_, err = tx.Exec(ctx,
		`UPDATE users
		    SET mfa_state='enabled', mfa_enabled_at=NOW(), mfa_last_timestep=$1
		  WHERE id=$2`, step, userID)
	if err != nil {
		return nil, err
	}

	codes, err := s.replaceRecoveryCodes(ctx, tx, userID)
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return codes, nil
}

// Disable turns MFA off after verifying a current factor (TOTP or recovery code)
// so a hijacked session cannot silently remove the second factor.
// usedRecovery reports whether a recovery code (vs TOTP) was consumed.
func (s *Service) Disable(ctx context.Context, userID int64, code string) (usedRecovery bool, err error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return false, err
	}
	if u.State != StateEnabled {
		return false, ErrMFANotEnabled
	}

	usedRecovery, err = s.verifyFactor(ctx, u, code)
	if err != nil {
		return false, err
	}

	_, err = s.db.Exec(ctx,
		`UPDATE users
		    SET mfa_state='disabled', mfa_secret_enc=NULL,
		        mfa_last_timestep=NULL, mfa_enabled_at=NULL
		  WHERE id=$1`, userID)
	if err != nil {
		return usedRecovery, err
	}
	_, _ = s.db.Exec(ctx, `DELETE FROM mfa_recovery_codes WHERE user_id=$1`, userID)
	return usedRecovery, nil
}

// RegenerateRecoveryCodes invalidates all existing codes and issues a new set.
func (s *Service) RegenerateRecoveryCodes(ctx context.Context, userID int64) ([]string, error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return nil, err
	}
	if u.State != StateEnabled {
		return nil, ErrMFANotEnabled
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	codes, err := s.replaceRecoveryCodes(ctx, tx, userID)
	if err != nil {
		return nil, err
	}
	return codes, tx.Commit(ctx)
}

// VerifyChallenge validates a login-time factor (TOTP or recovery code) for an
// MFA-enabled user. Used by the login flow (Step 4).
// Returns usedRecovery=true if a recovery code was consumed.
func (s *Service) VerifyChallenge(ctx context.Context, userID int64, code string) (usedRecovery bool, err error) {
	u, err := s.get(ctx, userID)
	if err != nil {
		return false, err
	}
	if u.State != StateEnabled {
		return false, ErrMFANotEnabled
	}
	return s.verifyFactor(ctx, u, code)
}

// ── internal ────────────────────────────────────────────────────────────────

// verifyFactor tries the code as a TOTP first, then as a recovery code.
// On a successful TOTP it persists the new last_timestep (replay guard); on a
// successful recovery code it atomically marks that code used.
func (s *Service) verifyFactor(ctx context.Context, u *userMFA, code string) (usedRecovery bool, err error) {
	ok, step, err := s.checkTOTP(u, code)
	if err != nil {
		return false, err
	}
	if ok {
		if u.LastTimestep != nil && step <= *u.LastTimestep {
			return false, ErrTOTPReplay
		}
		_, err = s.db.Exec(ctx, `UPDATE users SET mfa_last_timestep=$1 WHERE id=$2`, step, u.UserID)
		return false, err
	}

	// Try recovery code: single-use, atomic.
	hash := s.crypto.HashRecoveryCode(code)
	ct, err := s.db.Exec(ctx,
		`UPDATE mfa_recovery_codes SET used=TRUE, used_at=NOW()
		  WHERE user_id=$1 AND code_hash=$2 AND used=FALSE`,
		u.UserID, hash)
	if err != nil {
		return false, err
	}
	if ct.RowsAffected() == 1 {
		return true, nil
	}
	return false, ErrInvalidTOTP
}

func (s *Service) checkTOTP(u *userMFA, code string) (ok bool, step int64, err error) {
	if len(u.SecretEnc) == 0 {
		return false, 0, nil
	}
	secret, err := s.crypto.Decrypt(u.SecretEnc)
	if err != nil {
		return false, 0, err
	}
	defer zero(secret)
	return validateTOTPWithStep(string(secret), code, time.Now(), s.skew)
}

func (s *Service) replaceRecoveryCodes(ctx context.Context, tx pgx.Tx, userID int64) ([]string, error) {
	if _, err := tx.Exec(ctx, `DELETE FROM mfa_recovery_codes WHERE user_id=$1`, userID); err != nil {
		return nil, err
	}
	codes, hashes, err := s.crypto.GenerateRecoveryCodes(recoveryCodeCount)
	if err != nil {
		return nil, err
	}
	for _, h := range hashes {
		if _, err := tx.Exec(ctx,
			`INSERT INTO mfa_recovery_codes (user_id, code_hash) VALUES ($1, $2)`,
			userID, h); err != nil {
			return nil, err
		}
	}
	return codes, nil
}

func zero(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
