package model

import "time"

type Notification struct {
	ID        string
	Channel   string
	Recipient string
	Subject   string
	Body      string
	Status    string
	Attempts  int
	ErrorMsg  string
	SentAt    *time.Time
	CreatedAt time.Time
	UpdatedAt time.Time
}

const (
	ChannelEmail = "EMAIL"
	ChannelSMS   = "SMS"
	ChannelPush  = "PUSH"

	StatusPending = "PENDING"
	StatusSent    = "SENT"
	StatusFailed  = "FAILED"
)
