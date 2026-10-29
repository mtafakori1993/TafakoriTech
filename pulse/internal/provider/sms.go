package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/mtafakori/pulse/internal/model"
)

const smsirBaseURL = "https://api.sms.ir/v1"

// SMSIRConfig holds credentials and line configuration for SMS.ir.
type SMSIRConfig struct {
	APIKey     string
	LineNumber string // dedicated line number (optional for verify endpoint)
	TemplateID int    // if non-zero, Send uses /send/verify instead of /send/bulk
	OTPParam   string // parameter name in the template that receives the OTP code (default: "OTP")
}

// SMSIRProvider delivers SMS messages via the SMS.ir REST API.
type SMSIRProvider struct {
	cfg    SMSIRConfig
	client *http.Client
}

func NewSMSIRProvider(cfg SMSIRConfig) *SMSIRProvider {
	return &SMSIRProvider{
		cfg: cfg,
		client: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// Send delivers a single SMS. If TemplateID is set, uses /send/verify with the
// notification body as the OTP code value; otherwise falls back to /send/bulk.
func (p *SMSIRProvider) Send(ctx context.Context, n *model.Notification) error {
	if p.cfg.TemplateID != 0 {
		paramName := p.cfg.OTPParam
		if paramName == "" {
			paramName = "OTP"
		}
		return p.SendVerify(ctx, n.Recipient, p.cfg.TemplateID, []SMSIRParameter{
			{Name: paramName, Value: n.Body},
		})
	}

	payload := smsBulkRequest{
		LineNumber:  p.cfg.LineNumber,
		MessageText: n.Body,
		Mobiles:     []string{n.Recipient},
	}

	var resp smsirResponse
	if err := p.post(ctx, "/send/bulk", "application/json", payload, &resp); err != nil {
		return err
	}
	if resp.Status != 1 {
		return fmt.Errorf("sms.ir: %s", resp.Message)
	}
	return nil
}

// SendVerify sends a pattern-based (OTP) message using POST /send/verify.
// templateID must be pre-registered in the SMS.ir panel.
// The Accept header must be "text/plain" per SMS.ir API docs.
func (p *SMSIRProvider) SendVerify(ctx context.Context, mobile string, templateID int, params []SMSIRParameter) error {
	payload := smsVerifyRequest{
		Mobile:     mobile,
		TemplateID: templateID,
		Parameters: params,
	}

	var resp smsirResponse
	if err := p.post(ctx, "/send/verify", "text/plain", payload, &resp); err != nil {
		return err
	}
	if resp.Status != 1 {
		return fmt.Errorf("sms.ir verify: %s", resp.Message)
	}
	return nil
}

func (p *SMSIRProvider) post(ctx context.Context, path, accept string, body any, out any) error {
	b, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("sms.ir marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, smsirBaseURL+path, bytes.NewReader(b))
	if err != nil {
		return fmt.Errorf("sms.ir request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", accept)
	req.Header.Set("x-api-key", p.cfg.APIKey)

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("sms.ir http: %w", err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)

	switch resp.StatusCode {
	case http.StatusOK:
		// fall through to decode
	case http.StatusUnauthorized:
		return fmt.Errorf("sms.ir: authentication failed (check API key)")
	case http.StatusTooManyRequests:
		return fmt.Errorf("sms.ir: rate limited (429)")
	default:
		return fmt.Errorf("sms.ir: unexpected status %d: %s", resp.StatusCode, raw)
	}

	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("sms.ir decode: %w", err)
	}
	return nil
}

// ---- request / response types -----------------------------------------------

type smsBulkRequest struct {
	LineNumber   string   `json:"lineNumber"`
	MessageText  string   `json:"messageText"`
	Mobiles      []string `json:"mobiles"`
	SendDateTime *int64   `json:"sendDateTime"` // unix epoch, nil = immediate
}

type smsirResponse struct {
	Status  int    `json:"status"`
	Message string `json:"message"`
}

type smsVerifyRequest struct {
	Mobile     string          `json:"mobile"`
	TemplateID int             `json:"templateId"`
	Parameters []SMSIRParameter `json:"parameters"`
}

// SMSIRParameter is a name/value pair used in pattern-based (OTP) messages.
type SMSIRParameter struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}
