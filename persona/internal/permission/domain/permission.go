package domain

import "time"

// Permission represents a specific action that can be taken on a resource.
type Permission struct {
	ID          string
	Name        string
	Slug        string
	Resource    string
	Action      string
	Description string
	CreatedAt   time.Time
	UpdatedAt   time.Time
	DeletedAt   *time.Time
}
