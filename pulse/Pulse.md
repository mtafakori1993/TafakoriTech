# Pulse

Notification delivery microservice for the TafakoriTech platform.
Handles multi-channel notification dispatch (Email, SMS, Push), delivery tracking, retry logic, and structured ELK-compatible logging.

---

## Table of Contents

1. [Service Overview](#service-overview)
2. [Scope (MVP)](#scope-mvp)
3. [Architecture](#architecture)
4. [API Design](#api-design)
5. [Notification Model](#notification-model)
6. [Delivery Flow](#delivery-flow)
7. [Provider Abstraction](#provider-abstraction)
8. [Storage](#storage)
9. [Failure Handling](#failure-handling)
10. [Logging & ELK Integration](#logging--elk-integration)
11. [Run Instructions](#run-instructions)

---

## Service Overview

Pulse is a Go/Gin service responsible for sending notifications across multiple channels on behalf of other platform services. Callers submit a notification request and receive an ID immediately; delivery is handled asynchronously with status tracked in PostgreSQL.

**Module:** `github.com/mtafakori/pulse`
**Default port:** `9505`
**Dependencies:** PostgreSQL, (optional) Redis for rate limiting

---

## Scope (MVP)

| Capability                        | Included |
|-----------------------------------|----------|
| Send notification via single API  | Yes      |
| Channel selection: EMAIL/SMS/PUSH | Yes      |
| Immediate notification ID return  | Yes      |
| Delivery status tracking          | Yes      |
| Basic retry on failure (3×)       | Yes      |
| ELK-compatible structured logging | Yes      |
| Templating engine                 | No       |
| Batching / fan-out                | No       |
| Scheduling / delayed send         | No       |
| Unsubscribe / preference store    | No       |
| Webhook callbacks                 | No       |

---

## Architecture

```
Caller (Sentinel / Persona / other service)
  │
  ▼  POST /api/v1/notifications
Gin Router
  ├── Middleware (recovery → logger → auth: shared JWT secret)
  └── Handler — validates request, persists PENDING record, returns ID
        │
        ▼
  Dispatcher (goroutine pool, in-process)
        │
        ├── EmailProvider  → SMTP (net/smtp or sendgrid SDK)
        ├── SMSProvider    → Kavenegar REST API
        └── PushProvider   → mock (logs + marks SENT)
              │
              ▼
        Update delivery record → SENT | FAILED
        Retry up to 3× on transient failure
        Emit structured log for every event

Persistence
  └── PostgreSQL — notifications table
```

No message queue is required. The dispatcher runs in-process using a bounded goroutine worker pool (`runtime.NumCPU()` workers). This is sufficient for MVP throughput and eliminates operational overhead.

---

## API Design

### Authentication

All endpoints require a valid JWT issued by Sentinel, passed as `Authorization: Bearer <token>`. Pulse verifies the signature using the shared `JWT_SECRET` environment variable — no round-trip to Sentinel on the hot path.

---

### POST `/api/v1/notifications`

Submit a notification for delivery.

**Request**

```json
{
  "channel":    "EMAIL",
  "recipient":  "user@example.com",
  "subject":    "Your verification code",
  "body":       "Your code is 123456. Valid for 5 minutes."
}
```

| Field       | Type   | Required | Notes                                            |
|-------------|--------|----------|--------------------------------------------------|
| `channel`   | string | Yes      | `EMAIL`, `SMS`, or `PUSH`                        |
| `recipient` | string | Yes      | Email address, phone number, or device token     |
| `subject`   | string | No       | Used by EMAIL only; ignored for SMS/PUSH         |
| `body`      | string | Yes      | Notification content                             |

**Response `202 Accepted`**

```json
{
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "status":          "PENDING"
}
```

**Error responses**

| Code | Condition                         |
|------|-----------------------------------|
| 400  | Missing required field or invalid channel |
| 401  | Missing or invalid JWT            |
| 500  | Failed to persist notification    |

---

### GET `/api/v1/notifications/:id`

Retrieve delivery status for a notification.

**Response `200 OK`**

```json
{
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "channel":         "EMAIL",
  "recipient":       "user@example.com",
  "status":          "SENT",
  "attempts":        1,
  "sent_at":         "2026-06-15T10:22:04Z",
  "created_at":      "2026-06-15T10:22:03Z"
}
```

**Error responses**

| Code | Condition        |
|------|------------------|
| 404  | ID not found     |
| 401  | Invalid JWT      |

---

## Notification Model

```go
type Notification struct {
    ID          string    // ULID
    Channel     string    // EMAIL | SMS | PUSH
    Recipient   string
    Subject     string    // nullable
    Body        string
    Status      string    // PENDING | SENT | FAILED
    Attempts    int
    ErrorMsg    string    // nullable — last error text
    SentAt      *time.Time
    CreatedAt   time.Time
    UpdatedAt   time.Time
}
```

**Status transitions**

```
PENDING → SENT    (delivery succeeded on any attempt)
PENDING → FAILED  (all 3 attempts exhausted)
```

---

## Delivery Flow

```
1. Handler receives POST /api/v1/notifications
2. Validate input (channel enum, required fields)
3. Persist notification with status=PENDING, attempts=0
4. Return 202 with notification_id immediately
5. Enqueue notification to worker pool (non-blocking channel send)
6. Worker picks up notification
7. Call provider.Send(notification)
   ├── Success → update status=SENT, sent_at=now, log delivery_success
   └── Error   → increment attempts
                 ├── attempts < 3 → sleep backoff, retry from step 7
                 └── attempts = 3 → update status=FAILED, log delivery_failed
```

Backoff schedule: attempt 1 → 2s, attempt 2 → 5s, attempt 3 → immediate (already exhausted).

---

## Provider Abstraction

All providers implement a single interface:

```go
type Provider interface {
    Send(ctx context.Context, n *model.Notification) error
}
```

**Directory layout**

```
internal/
  provider/
    email.go    — net/smtp
    sms.go      — SMS.ir REST API
    ghasedak.go — Ghasedak REST API
    push.go     — mock: logs + returns nil
```

### Email

Uses Go's `net/smtp` (zero external dependency).

```
SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_FROM
```

### SMS

The active SMS provider is selected at startup via `SMS_PROVIDER`. Both providers implement the same `Provider` interface — swapping is a config change only.

```
SMS_PROVIDER=smsir    # or ghasedak
```

#### SMS.ir (`SMS_PROVIDER=smsir`, default)

Base URL: `https://api.sms.ir/v1` — Auth: `x-api-key` header.

- **Bulk send** (`POST /send/bulk`) — plain text to a recipient from a dedicated line.
- **Verify/OTP send** (`POST /send/verify`) — pattern-based message via pre-registered template ID; bypasses blacklists with higher priority.

```
SMSIR_API_KEY, SMSIR_LINE_NUMBER
```

#### Ghasedak (`SMS_PROVIDER=ghasedak`)

Base URL: `https://gateway.ghasedak.me/rest/api/v1/WebService` — Auth: `ApiKey` header.

- **Single send** (`POST /SendSingleSMS`) — plain text to one recipient.
- **OTP send** (`POST /SendOtpSMS`) — template-based message with named `inputs` parameters.

```
GHASEDAK_API_KEY, GHASEDAK_LINE_NUMBER
```

### Push (mock)

The push provider writes a log entry at `INFO` level containing the full notification payload and returns `nil`. No external dependency. Replace with FCM or APNs in a future iteration without changing caller code.

---

## Storage

### PostgreSQL — `notifications` table

```sql
CREATE TABLE notifications (
    id          TEXT        PRIMARY KEY,           -- ULID
    channel     TEXT        NOT NULL,
    recipient   TEXT        NOT NULL,
    subject     TEXT,
    body        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    error_msg   TEXT,
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_status    ON notifications(status);
CREATE INDEX idx_notifications_created   ON notifications(created_at DESC);
```

Migration files live under `pulse/migrations/`.

No Redis dependency is required for MVP. Rate limiting can be added later using the same Redis sliding-window pattern used in Sentinel.

---

## Failure Handling

| Scenario                         | Behaviour                                                      |
|----------------------------------|----------------------------------------------------------------|
| Provider timeout / 5xx           | Retry with backoff; mark FAILED after 3 attempts               |
| Invalid recipient format         | Rejected at validation layer (400), never dispatched           |
| Worker pool full at startup      | Goroutine channel is buffered (1 000 slots); backpressure drops to 503 if exceeded |
| DB write fails on status update  | Logged at ERROR; notification left in last known state         |
| Service restart with PENDING rows | On startup, Pulse re-enqueues all PENDING notifications older than 30s |

---

## Logging & ELK Integration

Pulse uses `zerolog` with JSON output to `stdout`. All log lines include a consistent set of base fields so Logstash/Filebeat can parse, route, and index them without custom grok patterns.

### Base fields (every log line)

```json
{
  "time":    "2026-06-15T10:22:03.412Z",
  "level":   "info",
  "service": "pulse",
  "version": "0.1.0"
}
```

### Event-specific log lines

**notification_received** — emitted immediately after persisting the record

```json
{
  "event":           "notification_received",
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "channel":         "EMAIL",
  "recipient_hash":  "sha256:6b86b273",
  "level":           "info"
}
```

> `recipient` is hashed (SHA-256 prefix) to avoid storing PII in logs.

**delivery_attempt** — emitted at the start of each provider call

```json
{
  "event":           "delivery_attempt",
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "channel":         "EMAIL",
  "attempt":         1,
  "level":           "info"
}
```

**delivery_success** — emitted when the provider returns nil

```json
{
  "event":           "delivery_success",
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "channel":         "EMAIL",
  "attempt":         1,
  "duration_ms":     142,
  "level":           "info"
}
```

**delivery_failed** — emitted when all retries are exhausted

```json
{
  "event":           "delivery_failed",
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "channel":         "SMS",
  "attempts":        3,
  "error":           "sms.ir: insufficient credit",
  "level":           "error"
}
```

**retry_scheduled** — emitted before each backoff sleep

```json
{
  "event":           "retry_scheduled",
  "notification_id": "01J3K5R8X2VFWG4P9QDNM0ZBHC",
  "attempt":         2,
  "backoff_ms":      5000,
  "level":           "warn"
}
```

### ELK index pattern

Recommended Logstash index: `pulse-YYYY.MM.DD`
Kibana filter field: `service: pulse`
Key Kibana dashboards:
- Delivery success rate by channel: `event: delivery_success` vs `delivery_failed`, aggregated by `channel`
- Retry volume: `event: retry_scheduled`, aggregated by hour
- Error breakdown: `event: delivery_failed`, aggregated by `error`

---

## Run Instructions

### Prerequisites

- Go 1.22+
- PostgreSQL 14+
- Environment variables set (see below)

### Environment Variables

```env
# Server
PORT=9505
ENV=development

# Database
DATABASE_URL=postgres://user:pass@localhost:5432/pulse?sslmode=disable

# Auth (shared with Sentinel)
JWT_SECRET=your-shared-secret

# Email
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=noreply@tafakoritech.ir
SMTP_PASS=app-password
SMTP_FROM=Pulse <noreply@tafakoritech.ir>

# SMS (SMS.ir)
SMSIR_API_KEY=your-smsir-key
SMSIR_LINE_NUMBER=30007732XXXXXX

# Worker pool
WORKER_COUNT=4
QUEUE_BUFFER=1000
```

### Run locally

```bash
cd pulse
cp .env.example .env
# edit .env with your values

# Apply migrations
migrate -path migrations -database "$DATABASE_URL" up

# Start
go run ./cmd/pulse
```

### Run with Docker

```bash
docker build -t pulse:dev .
docker run --env-file .env -p 9505:9505 pulse:dev
```

### Add to go.work

```
# go.work (project root)
use (
    ./sentinel
    ./persona
    ./pulse      # add this line
)
```

### Makefile targets

```makefile
make run       # go run ./cmd/pulse
make build     # go build -o bin/pulse ./cmd/pulse
make migrate   # run all pending migrations
make test      # go test ./...
make lint      # golangci-lint run
```

---

## Directory Layout

```
pulse/
  cmd/
    pulse/
      main.go
  internal/
    config/
      config.go
    db/
      db.go
    handler/
      notification.go
    model/
      notification.go
    provider/
      email.go
      sms.go
      push.go
      provider.go       (interface)
    dispatcher/
      dispatcher.go     (worker pool + retry loop)
    logger/
      logger.go         (zerolog setup, base fields)
    middleware/
      auth.go           (JWT verify)
      recovery.go
  migrations/
    001_create_notifications.up.sql
    001_create_notifications.down.sql
  Dockerfile
  Makefile
  go.mod
  .env.example
```
