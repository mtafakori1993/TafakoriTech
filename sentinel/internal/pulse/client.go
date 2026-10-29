package pulse

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

type Client struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func New(baseURL, apiKey string) *Client {
	return &Client{
		baseURL: baseURL,
		apiKey:  apiKey,
		http:    &http.Client{},
	}
}

type notifyRequest struct {
	Channel   string `json:"channel"`
	Recipient string `json:"recipient"`
	Subject   string `json:"subject,omitempty"`
	Body      string `json:"body"`
}

// SendSMS submits an SMS notification to Pulse for async delivery.
func (c *Client) SendSMS(ctx context.Context, phone, body string) error {
	return c.submit(ctx, notifyRequest{Channel: "SMS", Recipient: phone, Body: body})
}

// SendEmail submits an email notification to Pulse for async delivery.
func (c *Client) SendEmail(ctx context.Context, to, subject, body string) error {
	return c.submit(ctx, notifyRequest{Channel: "EMAIL", Recipient: to, Subject: subject, Body: body})
}

func (c *Client) submit(ctx context.Context, n notifyRequest) error {
	payload, _ := json.Marshal(n)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.baseURL+"/internal/v1/notifications", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", c.apiKey)

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusAccepted {
		return fmt.Errorf("pulse: unexpected status %d", resp.StatusCode)
	}
	return nil
}
