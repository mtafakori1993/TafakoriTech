package provider

import (
	"context"
	"crypto/tls"
	"fmt"
	"net/mail"
	"net/smtp"
	"strings"

	"github.com/mtafakori/pulse/internal/model"
)

type EmailConfig struct {
	Host string
	Port int
	User string
	Pass string
	From string
}

type EmailProvider struct {
	cfg EmailConfig
}

func NewEmailProvider(cfg EmailConfig) *EmailProvider {
	return &EmailProvider{cfg: cfg}
}

func (p *EmailProvider) Send(_ context.Context, n *model.Notification) error {
	addr := fmt.Sprintf("%s:%d", p.cfg.Host, p.cfg.Port)

	// Envelope sender must be a bare address; display-name format is only for headers.
	envelopeFrom := p.cfg.From
	if parsed, err := mail.ParseAddress(p.cfg.From); err == nil {
		envelopeFrom = parsed.Address
	}

	subject := n.Subject
	if subject == "" {
		subject = "Notification"
	}

	msg := strings.Join([]string{
		"From: " + p.cfg.From,
		"To: " + n.Recipient,
		"Subject: " + subject,
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		n.Body,
	}, "\r\n")

	// Port 465 = SMTPS (implicit TLS). smtp.SendMail only supports STARTTLS,
	// so we dial TLS directly and authenticate manually.
	if p.cfg.Port == 465 {
		return p.sendSMTPS(addr, envelopeFrom, n.Recipient, []byte(msg))
	}

	// Port 587 (or any other port) — STARTTLS via the standard library.
	auth := smtp.PlainAuth("", p.cfg.User, p.cfg.Pass, p.cfg.Host)
	return smtp.SendMail(addr, auth, envelopeFrom, []string{n.Recipient}, []byte(msg))
}

// sendSMTPS dials an implicit-TLS (SMTPS) connection and sends mail.
// smtp.PlainAuth refuses to authenticate over connections that weren't
// upgraded via STARTTLS, so we implement the AUTH PLAIN exchange directly.
func (p *EmailProvider) sendSMTPS(addr, from, to string, msg []byte) error {
	conn, err := tls.Dial("tcp", addr, &tls.Config{ServerName: p.cfg.Host})
	if err != nil {
		return fmt.Errorf("smtp tls dial: %w", err)
	}
	defer conn.Close()

	c, err := smtp.NewClient(conn, p.cfg.Host)
	if err != nil {
		return fmt.Errorf("smtp new client: %w", err)
	}
	defer c.Close()

	if err = c.Auth(implicitTLSPlainAuth(p.cfg.User, p.cfg.Pass)); err != nil {
		return fmt.Errorf("smtp auth: %w", err)
	}
	if err = c.Mail(from); err != nil {
		return fmt.Errorf("smtp mail from: %w", err)
	}
	if err = c.Rcpt(to); err != nil {
		return fmt.Errorf("smtp rcpt to: %w", err)
	}
	wc, err := c.Data()
	if err != nil {
		return fmt.Errorf("smtp data: %w", err)
	}
	if _, err = wc.Write(msg); err != nil {
		return fmt.Errorf("smtp write body: %w", err)
	}
	return wc.Close()
}

// implicitTLSPlainAuth returns an smtp.Auth that sends AUTH PLAIN without
// checking for STARTTLS — safe because the connection is already TLS via tls.Dial.
func implicitTLSPlainAuth(user, pass string) smtp.Auth {
	return &smtpsPlainAuth{user: user, pass: pass}
}

type smtpsPlainAuth struct{ user, pass string }

func (a *smtpsPlainAuth) Start(_ *smtp.ServerInfo) (string, []byte, error) {
	return "PLAIN", []byte("\x00" + a.user + "\x00" + a.pass), nil
}

func (a *smtpsPlainAuth) Next(_ []byte, more bool) ([]byte, error) {
	if more {
		return nil, fmt.Errorf("unexpected server challenge")
	}
	return nil, nil
}
