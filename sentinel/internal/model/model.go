package model

import "time"

type User struct {
	ID          int64
	Identifier  string
	Role        string
	CreatedAt   time.Time
	Locked      bool
	LockedUntil *time.Time
}

type OTPCode struct {
	ID        int64
	UserID    int64
	Code      string
	ExpiresAt time.Time
	Used      bool
}

type Session struct {
	ID          int64
	SessionUUID string
	UserID      int64
	IPAddress   string
	UserAgent   string
	CreatedAt   time.Time
	LastActive  time.Time
	ExpiresAt   time.Time
	Revoked     bool
	RevokedAt   *time.Time
}

type RefreshToken struct {
	ID         string // UUID / jti
	SessionID  int64
	UserID     int64
	IssuedAt   time.Time
	ExpiresAt  time.Time
	Revoked    bool
	RevokedAt  *time.Time
	ReplacedBy *string
}
