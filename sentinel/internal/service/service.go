package service

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/mtafakori/sentinel/internal/blacklist"
	"github.com/mtafakori/sentinel/internal/config"
	"github.com/mtafakori/sentinel/internal/logger"
	"github.com/mtafakori/sentinel/internal/model"
	"github.com/mtafakori/sentinel/internal/persona"
	"github.com/mtafakori/sentinel/internal/pulse"
)

// ── Sentinel errors ───────────────────────────────────────────────────────────

var (
	ErrInvalidOTP      = errors.New("invalid or expired OTP")
	ErrInvalidToken    = errors.New("invalid or expired token")
	ErrUserNotFound    = errors.New("user not registered")
	ErrUserInactive    = errors.New("user account is inactive")
	ErrAccountLocked   = errors.New("account is locked")
	ErrSessionNotFound = errors.New("session not found")
	ErrForbidden       = errors.New("forbidden")
)

// TokenReuseError is returned when a refresh token that was already rotated
// (revoked) is presented again — a strong signal of token theft.
// The handler must treat this as a security event and force-revoke the session.
type TokenReuseError struct {
	UserID    int64
	SessionID int64
	TokenID   string
}

func (e *TokenReuseError) Error() string {
	return fmt.Sprintf("refresh token reuse: token=%s session=%d user=%d", e.TokenID, e.SessionID, e.UserID)
}

// AutoLockedError is returned when VerifyOTP auto-locks the account after
// too many consecutive failures.
type AutoLockedError struct {
	UserID    int64
	FailCount int64
}

func (e *AutoLockedError) Error() string {
	return fmt.Sprintf("account auto-locked after %d failed OTP attempts", e.FailCount)
}

// MFARequiredError is returned by VerifyOTP when the OTP is valid but the user
// has MFA enabled. No session is created; the handler issues a short-lived
// mfa_challenge token and the caller must complete login via /mfa/login.
type MFARequiredError struct {
	UserID int64
}

func (e *MFARequiredError) Error() string {
	return fmt.Sprintf("mfa required for user %d", e.UserID)
}

// ── Wire types ────────────────────────────────────────────────────────────────

type TokenPair struct {
	APIKey     string `json:"api_key"`
	RefreshKey string `json:"refresh_key"`
}

// AuthResult carries the token pair plus internal metadata used for audit
// logging. Fields tagged json:"-" are never sent to the caller.
type AuthResult struct {
	TokenPair
	UserID      int64  `json:"-"`
	SessionUUID string `json:"-"`
	OldTokenID  string `json:"-"` // set only on token rotation (Refresh)
	NewTokenID  string `json:"-"`
}

type Claims struct {
	UserID      int64  `json:"uid"`
	Role        string `json:"role"`
	SessionUUID string `json:"sid,omitempty"`
	TokenType   string `json:"typ,omitempty"` // "access" | "refresh" | "mfa_challenge"
	jwt.RegisteredClaims
}

// ── Service ───────────────────────────────────────────────────────────────────

type Service struct {
	db        *pgxpool.Pool
	rdb       *redis.Client
	cfg       *config.Config
	persona   *persona.Client
	pulse     *pulse.Client
	blacklist *blacklist.Blacklist
}

func New(db *pgxpool.Pool, rdb *redis.Client, cfg *config.Config, personaClient *persona.Client, pulseClient *pulse.Client, bl *blacklist.Blacklist) *Service {
	return &Service{
		db:        db,
		rdb:       rdb,
		cfg:       cfg,
		persona:   personaClient,
		pulse:     pulseClient,
		blacklist: bl,
	}
}

// ── OTP flow ──────────────────────────────────────────────────────────────────

func (s *Service) RequestOTP(ctx context.Context, identifier string) error {
	if err := s.checkPersona(ctx, identifier); err != nil {
		return err
	}

	user, err := s.upsertUser(ctx, identifier)
	if err != nil {
		return err
	}

	if user.Locked {
		if user.LockedUntil == nil || user.LockedUntil.After(time.Now()) {
			return ErrAccountLocked
		}
		// Timed DB lock expired — auto-unlock
		_, _ = s.db.Exec(ctx, `UPDATE users SET locked=false, locked_until=NULL WHERE id=$1`, user.ID)
	}

	// Check Redis auto-lock
	lockKey := fmt.Sprintf("auth:lock:%d", user.ID)
	if exists, _ := s.rdb.Exists(ctx, lockKey).Result(); exists > 0 {
		return ErrAccountLocked
	}

	code, err := generateOTP()
	if err != nil {
		return err
	}

	_, err = s.db.Exec(ctx,
		`INSERT INTO otp_codes (user_id, code, expires_at) VALUES ($1, $2, $3)`,
		user.ID, code, time.Now().Add(5*time.Minute),
	)
	if err != nil {
		return err
	}

	if s.pulse != nil {
		if isEmail(identifier) {
			body := fmt.Sprintf("Your one-time code is: %s\n\nThis code expires in 5 minutes.", code)
			if err := s.pulse.SendEmail(ctx, identifier, "Your OTP code", body); err != nil {
				logger.Log.Error().Err(err).Str("user_id", fmt.Sprintf("%d", user.ID)).Msg("pulse: failed to dispatch OTP email")
			}
		} else {
			if err := s.pulse.SendSMS(ctx, identifier, code); err != nil {
				logger.Log.Error().Err(err).Str("user_id", fmt.Sprintf("%d", user.ID)).Msg("pulse: failed to dispatch OTP SMS")
			}
		}
	}
	return nil
}

func isEmail(identifier string) bool {
	for _, ch := range identifier {
		if ch == '@' {
			return true
		}
	}
	return false
}

func (s *Service) VerifyOTP(ctx context.Context, identifier, code, ip, ua string) (*AuthResult, error) {
	user, err := s.upsertUser(ctx, identifier)
	if err != nil {
		return nil, err
	}

	// Check DB lock
	if user.Locked {
		if user.LockedUntil == nil || user.LockedUntil.After(time.Now()) {
			return nil, ErrAccountLocked
		}
		_, _ = s.db.Exec(ctx, `UPDATE users SET locked=false, locked_until=NULL WHERE id=$1`, user.ID)
	}

	// Check Redis auto-lock
	lockKey := fmt.Sprintf("auth:lock:%d", user.ID)
	if exists, _ := s.rdb.Exists(ctx, lockKey).Result(); exists > 0 {
		return nil, ErrAccountLocked
	}

	var otpID int64
	err = s.db.QueryRow(ctx,
		`SELECT id FROM otp_codes
		 WHERE user_id=$1 AND code=$2 AND used=false AND expires_at > NOW()
		 ORDER BY created_at DESC LIMIT 1`,
		user.ID, code,
	).Scan(&otpID)
	if errors.Is(err, pgx.ErrNoRows) {
		count, _ := s.incrementFailCount(ctx, identifier)
		if count >= int64(s.cfg.FailLockAt) {
			_ = s.rdb.Set(ctx, lockKey, "1", s.cfg.LockDuration).Err()
			return nil, &AutoLockedError{UserID: user.ID, FailCount: count}
		}
		return nil, ErrInvalidOTP
	}
	if err != nil {
		return nil, err
	}

	_, err = s.db.Exec(ctx, `UPDATE otp_codes SET used=true WHERE id=$1`, otpID)
	if err != nil {
		return nil, err
	}

	s.resetFailCount(ctx, identifier)

	// OTP is valid. If the user has MFA enabled, do NOT create a session yet —
	// signal the handler to issue an mfa_challenge token and require a second
	// factor via /mfa/login.
	var mfaState string
	if err := s.db.QueryRow(ctx,
		`SELECT COALESCE(mfa_state, 'disabled') FROM users WHERE id=$1`, user.ID,
	).Scan(&mfaState); err != nil {
		return nil, err
	}
	if mfaState == "enabled" {
		return nil, &MFARequiredError{UserID: user.ID}
	}

	return s.issueTokens(ctx, user, ip, ua)
}

// ── Refresh token rotation ────────────────────────────────────────────────────

// Refresh performs single-use refresh token rotation with SELECT FOR UPDATE
// to prevent concurrent rotation races.
//
// If the presented token is already revoked, the entire session is
// force-revoked and a *TokenReuseError is returned so the handler can
// emit a security audit event.
func (s *Service) Refresh(ctx context.Context, rawToken string) (*AuthResult, error) {
	claims, err := s.parseTokenInsecure(rawToken)
	if err != nil {
		return nil, ErrInvalidToken
	}
	if claims.TokenType != "refresh" {
		return nil, ErrInvalidToken
	}
	jti := claims.ID
	if jti == "" {
		return nil, ErrInvalidToken
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	// Lock the token row so concurrent callers with the same token block here.
	var rt model.RefreshToken
	var replacedBy *string
	err = tx.QueryRow(ctx,
		`SELECT id, session_id, user_id, expires_at, revoked, replaced_by
		 FROM refresh_tokens WHERE id=$1 FOR UPDATE`,
		jti,
	).Scan(&rt.ID, &rt.SessionID, &rt.UserID, &rt.ExpiresAt, &rt.Revoked, &replacedBy)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrInvalidToken
	}
	if err != nil {
		return nil, err
	}
	rt.ReplacedBy = replacedBy

	// Reuse detection: token already rotated or explicitly revoked.
	// Force-revoke the entire session chain, then surface the error.
	if rt.Revoked {
		_, _ = tx.Exec(ctx,
			`UPDATE sessions SET revoked=true, revoked_at=NOW() WHERE id=$1 AND revoked=false`,
			rt.SessionID,
		)
		_, _ = tx.Exec(ctx,
			`UPDATE refresh_tokens SET revoked=true, revoked_at=NOW()
			 WHERE session_id=$1 AND revoked=false`,
			rt.SessionID,
		)
		_ = tx.Commit(ctx)
		return nil, &TokenReuseError{UserID: rt.UserID, SessionID: rt.SessionID, TokenID: rt.ID}
	}

	if rt.ExpiresAt.Before(time.Now()) {
		return nil, ErrInvalidToken
	}

	// Lock session row too, to prevent concurrent logout racing this rotation.
	var sessionUUID string
	var sessionRevoked bool
	var sessionExpiresAt time.Time
	err = tx.QueryRow(ctx,
		`SELECT session_uuid, revoked, expires_at FROM sessions WHERE id=$1 FOR UPDATE`,
		rt.SessionID,
	).Scan(&sessionUUID, &sessionRevoked, &sessionExpiresAt)
	if err != nil {
		return nil, err
	}
	if sessionRevoked || sessionExpiresAt.Before(time.Now()) {
		return nil, ErrInvalidToken
	}

	// Fetch user for role; do NOT lock — read-only within this tx.
	var user model.User
	err = tx.QueryRow(ctx,
		`SELECT id, identifier, role, locked, locked_until FROM users WHERE id=$1`,
		rt.UserID,
	).Scan(&user.ID, &user.Identifier, &user.Role, &user.Locked, &user.LockedUntil)
	if err != nil {
		return nil, err
	}
	if user.Locked {
		if user.LockedUntil == nil || user.LockedUntil.After(time.Now()) {
			return nil, ErrAccountLocked
		}
		_, _ = tx.Exec(ctx, `UPDATE users SET locked=false, locked_until=NULL WHERE id=$1`, user.ID)
		user.Locked = false
	}
	lockKey := fmt.Sprintf("auth:lock:%d", user.ID)
	if exists, _ := s.rdb.Exists(ctx, lockKey).Result(); exists > 0 {
		return nil, ErrAccountLocked
	}

	// Issue replacement token
	newJTI := uuid.New().String()
	newExpiresAt := time.Now().Add(s.cfg.RefreshTokenTTL)

	_, err = tx.Exec(ctx,
		`INSERT INTO refresh_tokens (id, session_id, user_id, expires_at) VALUES ($1, $2, $3, $4)`,
		newJTI, rt.SessionID, rt.UserID, newExpiresAt,
	)
	if err != nil {
		return nil, err
	}

	_, err = tx.Exec(ctx,
		`UPDATE refresh_tokens SET revoked=true, revoked_at=NOW(), replaced_by=$1 WHERE id=$2`,
		newJTI, jti,
	)
	if err != nil {
		return nil, err
	}

	_, err = tx.Exec(ctx, `UPDATE sessions SET last_active=NOW() WHERE id=$1`, rt.SessionID)
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	accessToken, err := s.signAccessToken(&user, sessionUUID)
	if err != nil {
		return nil, err
	}
	refreshToken, err := s.signRefreshToken(newJTI, &user, sessionUUID, newExpiresAt)
	if err != nil {
		return nil, err
	}

	return &AuthResult{
		TokenPair:   TokenPair{APIKey: accessToken, RefreshKey: refreshToken},
		UserID:      user.ID,
		SessionUUID: sessionUUID,
		OldTokenID:  jti,
		NewTokenID:  newJTI,
	}, nil
}

// ── Logout ────────────────────────────────────────────────────────────────────

// Logout revokes the session identified by the refresh token's sid claim.
// It accepts expired tokens so users can always log out.
// Returns (userID, sessionUUID, error) for audit logging.
func (s *Service) Logout(ctx context.Context, rawToken string) (int64, string, error) {
	claims, err := s.parseTokenInsecure(rawToken)
	if err != nil {
		return 0, "", ErrInvalidToken
	}
	sessionUUID := claims.SessionUUID
	if sessionUUID == "" {
		return 0, "", ErrInvalidToken
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return 0, "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	var sessionID int64
	err = tx.QueryRow(ctx,
		`UPDATE sessions SET revoked=true, revoked_at=NOW()
		 WHERE session_uuid=$1 AND revoked=false RETURNING id`,
		sessionUUID,
	).Scan(&sessionID)
	if errors.Is(err, pgx.ErrNoRows) {
		// Already revoked — idempotent success
		return claims.UserID, sessionUUID, nil
	}
	if err != nil {
		return 0, "", err
	}

	_, err = tx.Exec(ctx,
		`UPDATE refresh_tokens SET revoked=true, revoked_at=NOW()
		 WHERE session_id=$1 AND revoked=false`,
		sessionID,
	)
	if err != nil {
		return 0, "", err
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, "", err
	}
	if s.blacklist != nil {
		_ = s.blacklist.RevokeSession(ctx, sessionUUID)
	}
	return claims.UserID, sessionUUID, nil
}

// ── Session management ────────────────────────────────────────────────────────

// ListSessions returns active (non-revoked, non-expired) sessions for a user.
func (s *Service) ListSessions(ctx context.Context, userID int64, page, pageSize int) ([]model.Session, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	offset := (page - 1) * pageSize

	var total int64
	err := s.db.QueryRow(ctx,
		`SELECT COUNT(*) FROM sessions
		 WHERE user_id=$1 AND revoked=false AND expires_at > NOW()`,
		userID,
	).Scan(&total)
	if err != nil {
		return nil, 0, err
	}

	rows, err := s.db.Query(ctx,
		`SELECT id, session_uuid, user_id, ip_address, user_agent, created_at,
		        last_active, expires_at, revoked, revoked_at
		 FROM sessions
		 WHERE user_id=$1 AND revoked=false AND expires_at > NOW()
		 ORDER BY last_active DESC
		 LIMIT $2 OFFSET $3`,
		userID, pageSize, offset,
	)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var sessions []model.Session
	for rows.Next() {
		var sess model.Session
		if err := rows.Scan(
			&sess.ID, &sess.SessionUUID, &sess.UserID,
			&sess.IPAddress, &sess.UserAgent, &sess.CreatedAt,
			&sess.LastActive, &sess.ExpiresAt, &sess.Revoked, &sess.RevokedAt,
		); err != nil {
			return nil, 0, err
		}
		sessions = append(sessions, sess)
	}
	return sessions, total, rows.Err()
}

// RevokeSession revokes a single session owned by requesterUserID.
// Returns ErrForbidden if the session belongs to a different user.
func (s *Service) RevokeSession(ctx context.Context, requesterUserID int64, sessionUUID string) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	var sessionID int64
	err = tx.QueryRow(ctx,
		`UPDATE sessions SET revoked=true, revoked_at=NOW()
		 WHERE session_uuid=$1 AND user_id=$2 AND revoked=false
		 RETURNING id`,
		sessionUUID, requesterUserID,
	).Scan(&sessionID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrSessionNotFound
	}
	if err != nil {
		return err
	}

	_, err = tx.Exec(ctx,
		`UPDATE refresh_tokens SET revoked=true, revoked_at=NOW()
		 WHERE session_id=$1 AND revoked=false`,
		sessionID,
	)
	if err != nil {
		return err
	}

	if err := tx.Commit(ctx); err != nil {
		return err
	}
	if s.blacklist != nil {
		_ = s.blacklist.RevokeSession(ctx, sessionUUID)
	}
	return nil
}

// LogoutAll revokes all active sessions for a user and returns the count.
func (s *Service) LogoutAll(ctx context.Context, userID int64) (int, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	rows, err := tx.Query(ctx,
		`UPDATE sessions SET revoked=true, revoked_at=NOW()
		 WHERE user_id=$1 AND revoked=false
		 RETURNING id, session_uuid`,
		userID,
	)
	if err != nil {
		return 0, err
	}
	type sessionRef struct {
		id   int64
		uuid string
	}
	var sessions []sessionRef
	for rows.Next() {
		var ref sessionRef
		if err := rows.Scan(&ref.id, &ref.uuid); err != nil {
			rows.Close()
			return 0, err
		}
		sessions = append(sessions, ref)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	for _, ref := range sessions {
		_, err = tx.Exec(ctx,
			`UPDATE refresh_tokens SET revoked=true, revoked_at=NOW()
			 WHERE session_id=$1 AND revoked=false`,
			ref.id,
		)
		if err != nil {
			return 0, err
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	if s.blacklist != nil {
		for _, ref := range sessions {
			_ = s.blacklist.RevokeSession(ctx, ref.uuid)
		}
	}
	return len(sessions), nil
}

// ── Account locking ───────────────────────────────────────────────────────────

// LockAccount sets the DB admin lock on a user account and immediately
// blacklists all active sessions so in-flight access tokens are rejected.
// lockedUntil == nil means permanent lock (until explicitly unlocked).
func (s *Service) LockAccount(ctx context.Context, targetUserID int64, reason string, lockedUntil *time.Time) error {
	_, err := s.db.Exec(ctx,
		`UPDATE users
		 SET locked=true, locked_at=NOW(), locked_until=$1, lock_reason=$2
		 WHERE id=$3`,
		lockedUntil, reason, targetUserID,
	)
	if err != nil {
		return err
	}
	if s.blacklist != nil {
		_ = s.blacklistUserSessions(ctx, targetUserID)
	}
	return nil
}

// blacklistUserSessions fetches all active session UUIDs for a user and
// adds them to the blacklist so their access tokens are rejected immediately.
func (s *Service) blacklistUserSessions(ctx context.Context, userID int64) error {
	rows, err := s.db.Query(ctx,
		`SELECT session_uuid FROM sessions
		 WHERE user_id=$1 AND revoked=false AND expires_at > NOW()`,
		userID,
	)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var uuid string
		if err := rows.Scan(&uuid); err != nil {
			return err
		}
		_ = s.blacklist.RevokeSession(ctx, uuid)
	}
	return rows.Err()
}

// UnlockAccount clears both the DB lock and Redis auto-lock for a user.
func (s *Service) UnlockAccount(ctx context.Context, targetUserID int64) error {
	_, err := s.db.Exec(ctx,
		`UPDATE users SET locked=false, locked_at=NULL, locked_until=NULL, lock_reason=NULL
		 WHERE id=$1`,
		targetUserID,
	)
	if err != nil {
		return err
	}
	// Clear Redis auto-lock and fail counter if present
	lockKey := fmt.Sprintf("auth:lock:%d", targetUserID)
	s.rdb.Del(ctx, lockKey) //nolint:errcheck
	return nil
}

// ── Internal helpers ──────────────────────────────────────────────────────────

func (s *Service) issueTokens(ctx context.Context, user *model.User, ip, ua string) (*AuthResult, error) {
	now := time.Now()
	sessionUUID := uuid.New().String()
	jti := uuid.New().String()

	sessionExpiresAt := now.Add(s.cfg.SessionMaxAge)
	rtExpiresAt := now.Add(s.cfg.RefreshTokenTTL)

	var sessionID int64
	err := s.db.QueryRow(ctx,
		`INSERT INTO sessions (user_id, session_uuid, ip_address, user_agent, expires_at)
		 VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		user.ID, sessionUUID, ip, ua, sessionExpiresAt,
	).Scan(&sessionID)
	if err != nil {
		return nil, err
	}

	_, err = s.db.Exec(ctx,
		`INSERT INTO refresh_tokens (id, session_id, user_id, expires_at)
		 VALUES ($1, $2, $3, $4)`,
		jti, sessionID, user.ID, rtExpiresAt,
	)
	if err != nil {
		return nil, err
	}

	accessToken, err := s.signAccessToken(user, sessionUUID)
	if err != nil {
		return nil, err
	}
	refreshToken, err := s.signRefreshToken(jti, user, sessionUUID, rtExpiresAt)
	if err != nil {
		return nil, err
	}

	return &AuthResult{
		TokenPair:   TokenPair{APIKey: accessToken, RefreshKey: refreshToken},
		UserID:      user.ID,
		SessionUUID: sessionUUID,
		NewTokenID:  jti,
	}, nil
}

func (s *Service) signAccessToken(user *model.User, sessionUUID string) (string, error) {
	now := time.Now()
	claims := Claims{
		UserID:      user.ID,
		Role:        user.Role,
		SessionUUID: sessionUUID,
		TokenType:   "access",
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.New().String(),
			Issuer:    "tafakori-auth",
			Subject:   fmt.Sprintf("%d", user.ID),
			ExpiresAt: jwt.NewNumericDate(now.Add(s.cfg.AccessTokenTTL)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(s.cfg.JWTSecret))
}

func (s *Service) signRefreshToken(jti string, user *model.User, sessionUUID string, expiresAt time.Time) (string, error) {
	now := time.Now()
	claims := Claims{
		UserID:      user.ID,
		Role:        user.Role,
		SessionUUID: sessionUUID,
		TokenType:   "refresh",
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        jti,
			Issuer:    "tafakori-auth",
			Subject:   fmt.Sprintf("%d", user.ID),
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(s.cfg.JWTSecret))
}

// SignMFAChallenge mints a short-lived token that proves the OTP step passed
// for the given user. It carries no session and grants no access — it is only
// accepted by ParseMFAChallenge to complete login via /mfa/login.
func (s *Service) SignMFAChallenge(userID int64) (string, error) {
	now := time.Now()
	claims := Claims{
		UserID:    userID,
		TokenType: "mfa_challenge",
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.New().String(),
			Issuer:    "tafakori-auth",
			Subject:   fmt.Sprintf("%d", userID),
			ExpiresAt: jwt.NewNumericDate(now.Add(s.cfg.MFAChallengeTTL)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(s.cfg.JWTSecret))
}

// ParseMFAChallenge validates an mfa_challenge token (signature + expiry + type)
// and returns the user ID it was issued for.
func (s *Service) ParseMFAChallenge(raw string) (int64, error) {
	token, err := jwt.ParseWithClaims(raw, &Claims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method")
		}
		return []byte(s.cfg.JWTSecret), nil
	})
	if err != nil || !token.Valid {
		return 0, ErrInvalidToken
	}
	claims, ok := token.Claims.(*Claims)
	if !ok || claims.TokenType != "mfa_challenge" || claims.UserID == 0 {
		return 0, ErrInvalidToken
	}
	return claims.UserID, nil
}

// IssueTokensByUserID creates a session and token pair for an already-verified
// user (used after a successful MFA challenge). It re-checks account locks.
func (s *Service) IssueTokensByUserID(ctx context.Context, userID int64, ip, ua string) (*AuthResult, error) {
	var user model.User
	err := s.db.QueryRow(ctx,
		`SELECT id, identifier, role, locked, locked_until FROM users WHERE id=$1`,
		userID,
	).Scan(&user.ID, &user.Identifier, &user.Role, &user.Locked, &user.LockedUntil)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	if user.Locked {
		if user.LockedUntil == nil || user.LockedUntil.After(time.Now()) {
			return nil, ErrAccountLocked
		}
		_, _ = s.db.Exec(ctx, `UPDATE users SET locked=false, locked_until=NULL WHERE id=$1`, user.ID)
	}
	lockKey := fmt.Sprintf("auth:lock:%d", user.ID)
	if exists, _ := s.rdb.Exists(ctx, lockKey).Result(); exists > 0 {
		return nil, ErrAccountLocked
	}
	return s.issueTokens(ctx, &user, ip, ua)
}

// parseTokenInsecure validates the signature but skips exp/nbf/iss checks.
// Use for Logout and as first-pass in Refresh (DB record is the expiry source of truth).
func (s *Service) parseTokenInsecure(raw string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(raw, &Claims{},
		func(t *jwt.Token) (any, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method")
			}
			return []byte(s.cfg.JWTSecret), nil
		},
		jwt.WithoutClaimsValidation(),
	)
	if err != nil || !token.Valid {
		return nil, ErrInvalidToken
	}
	cl, ok := token.Claims.(*Claims)
	if !ok {
		return nil, ErrInvalidToken
	}
	return cl, nil
}

func (s *Service) incrementFailCount(ctx context.Context, identifier string) (int64, error) {
	key := fmt.Sprintf("auth:fails:%s", identifier)
	count, err := s.rdb.Incr(ctx, key).Result()
	if err != nil {
		return 0, err
	}
	if count == 1 {
		s.rdb.Expire(ctx, key, s.cfg.FailWindow) //nolint:errcheck
	}
	return count, nil
}

func (s *Service) resetFailCount(ctx context.Context, identifier string) {
	key := fmt.Sprintf("auth:fails:%s", identifier)
	s.rdb.Del(ctx, key) //nolint:errcheck
}

func (s *Service) checkPersona(ctx context.Context, identifier string) error {
	if s.persona == nil {
		return nil
	}
	err := s.persona.CheckUser(ctx, identifier)
	if errors.Is(err, persona.ErrUserNotFound) {
		return ErrUserNotFound
	}
	if errors.Is(err, persona.ErrUserInactive) {
		return ErrUserInactive
	}
	return err
}

func (s *Service) upsertUser(ctx context.Context, identifier string) (*model.User, error) {
	var user model.User
	err := s.db.QueryRow(ctx,
		`INSERT INTO users (identifier) VALUES ($1)
		 ON CONFLICT (identifier) DO UPDATE SET identifier=EXCLUDED.identifier
		 RETURNING id, identifier, role, created_at, locked, locked_until`,
		identifier,
	).Scan(&user.ID, &user.Identifier, &user.Role, &user.CreatedAt, &user.Locked, &user.LockedUntil)
	return &user, err
}

func generateOTP() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}
