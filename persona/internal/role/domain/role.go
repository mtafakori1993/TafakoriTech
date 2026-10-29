package domain

import "time"

// Role represents a named set of permissions that can be assigned to users.
type Role struct {
	ID          string
	Name        string
	Slug        string
	Description string
	CreatedAt   time.Time
	UpdatedAt   time.Time
	DeletedAt   *time.Time
}
