package domain

import "time"

// Status represents the lifecycle state of a user account.
type Status string

const (
	StatusActive    Status = "active"
	StatusInactive  Status = "inactive"
	StatusSuspended Status = "suspended"
)

// User is the core domain model representing an identity in the system.
type User struct {
	ID           string
	FirstName    string
	LastName     string
	Email        string
	Phone        *string
	AvatarURL    *string
	Status       Status
	AuthzVersion int64
	CreatedAt    time.Time
	UpdatedAt    time.Time
	DeletedAt    *time.Time
}
