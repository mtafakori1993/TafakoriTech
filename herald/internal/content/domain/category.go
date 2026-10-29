package domain

import "time"

// Category groups blog posts.
type Category struct {
	ID          uint      `gorm:"primaryKey;autoIncrement"      json:"id"`
	Name        string    `gorm:"not null;size:200"             json:"name"`
	Slug        string    `gorm:"uniqueIndex;not null;size:200" json:"slug"`
	Description string    `gorm:"type:text"                     json:"description"`
	CreatedAt   time.Time `                                     json:"created_at"`
	UpdatedAt   time.Time `                                     json:"updated_at"`
}

func (Category) TableName() string { return "categories" }
