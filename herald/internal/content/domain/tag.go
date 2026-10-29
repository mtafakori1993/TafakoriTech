package domain

import "time"

// Tag is a label applied to posts and articles.
type Tag struct {
	ID        uint      `gorm:"primaryKey;autoIncrement"       json:"id"`
	Name      string    `gorm:"not null;size:100"              json:"name"`
	Slug      string    `gorm:"uniqueIndex;not null;size:100"  json:"slug"`
	CreatedAt time.Time `                                      json:"created_at"`
	UpdatedAt time.Time `                                      json:"updated_at"`
}

func (Tag) TableName() string { return "tags" }
