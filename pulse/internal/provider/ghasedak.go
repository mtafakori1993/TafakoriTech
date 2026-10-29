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

const ghasedakBaseURL = "https://gateway.ghasedak.me/rest/api/v1/webservice"

type GhasedakConfig struct {
	APIKey       string
	LineNumber   string
	TemplateName string // OTP template name registered in Ghasedak panel
	OTPParam     string // parameter name in the template (default: "Code")
}

type GhasedakProvider struct {
	cfg    GhasedakConfig
	client *http.Client
}

func NewGhasedakProvider(cfg GhasedakConfig) *GhasedakProvider {
	return &GhasedakProvider{
		cfg: cfg,
		client: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// Send delivers an OTP via the /sendotpsms endpoint when TemplateName is set,
// otherwise falls back to a regular single SMS.
func (p *GhasedakProvider) Send(ctx context.Context, n *model.Notification) error {
	if p.cfg.TemplateName != "" {
		paramName := p.cfg.OTPParam
		if paramName == "" {
			paramName = "Code"
		}
		return p.SendOTP(ctx, n.Recipient, p.cfg.TemplateName, []GhasedakInput{
			{Param: paramName, Value: n.Body},
		})
	}

	payload := ghasedakSingleRequest{
		LineNumber: p.cfg.LineNumber,
		Receptor:   n.Recipient,
		Message:    n.Body,
	}

	var resp ghasedakResponse
	if err := p.post(ctx, "/sendsimplesms", payload, &resp); err != nil {
		return err
	}
	if !resp.IsSuccess {
		return fmt.Errorf("ghasedak: %s (status %d)", resp.Message, resp.StatusCode)
	}
	return nil
}

// SendOTP sends a pattern-based OTP message using POST /sendotpsms.
// templateName must be pre-registered in the Ghasedak panel.
func (p *GhasedakProvider) SendOTP(ctx context.Context, mobile, templateName string, inputs []GhasedakInput) error {
	payload := ghasedakOTPRequest{
		Receptors:    []ghasedakReceptor{{Mobile: mobile}},
		TemplateName: templateName,
		Inputs:       inputs,
	}

	var resp ghasedakResponse
	if err := p.post(ctx, "/sendotpsms", payload, &resp); err != nil {
		return err
	}
	if !resp.IsSuccess {
		return fmt.Errorf("ghasedak otp: %s (status %d)", resp.Message, resp.StatusCode)
	}
	return nil
}

func (p *GhasedakProvider) post(ctx context.Context, path string, body any, out any) error {
	b, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("ghasedak marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ghasedakBaseURL+path, bytes.NewReader(b))
	if err != nil {
		return fmt.Errorf("ghasedak request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("ApiKey", p.cfg.APIKey)

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("ghasedak http: %w", err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)

	switch resp.StatusCode {
	case http.StatusOK:
		// fall through to decode
	case http.StatusUnauthorized:
		return fmt.Errorf("ghasedak: authentication failed (check API key)")
	default:
		return fmt.Errorf("ghasedak: unexpected status %d: %s", resp.StatusCode, raw)
	}

	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("ghasedak decode: %w", err)
	}
	return nil
}

// ---- request / response types -----------------------------------------------

type ghasedakSingleRequest struct {
	LineNumber        string `json:"lineNumber"`
	Receptor          string `json:"receptor"`
	Message           string `json:"message"`
	ClientReferenceID string `json:"clientReferenceId,omitempty"`
}

type ghasedakReceptor struct {
	Mobile            string `json:"mobile"`
	ClientReferenceID string `json:"clientReferenceId,omitempty"`
}

// GhasedakInput is a name/value pair used in OTP template messages.
type GhasedakInput struct {
	Param string `json:"param"`
	Value string `json:"value"`
}

type ghasedakOTPRequest struct {
	Receptors    []ghasedakReceptor `json:"receptors"`
	TemplateName string             `json:"templateName"`
	Inputs       []GhasedakInput    `json:"inputs"`
	IsVoice      bool               `json:"isVoice"`
}

type ghasedakResponse struct {
	IsSuccess  bool   `json:"isSuccess"`
	StatusCode int    `json:"statusCode"`
	Message    string `json:"message"`
}
