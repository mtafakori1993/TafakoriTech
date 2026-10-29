package domain

import "time"

// Team represents a group of users that can share resources and permissions.
type Team struct {
	ID          string
	Name        string
	Description string
	CreatedAt   time.Time
	UpdatedAt   time.Time
	DeletedAt   *time.Time
}
