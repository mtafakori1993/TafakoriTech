package domain

// Page represents a static content page (About Us, Privacy Policy, etc.).
type Page struct {
	ID        uint    `gorm:"primaryKey;autoIncrement"          json:"id"`
	ContentID uint    `gorm:"uniqueIndex;not null"              json:"content_id"`
	Content   Content `gorm:"foreignKey:ContentID"              json:"content"`
	Route     string  `gorm:"not null;size:500;uniqueIndex"     json:"route"`
	Template  string  `gorm:"size:100;default:'default'"        json:"template"`
	IsSystem  bool    `gorm:"not null;default:false"            json:"is_system"`
}

func (Page) TableName() string { return "pages" }
