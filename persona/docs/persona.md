# Persona — IAM Authorization Service

Persona is the **single source of truth for identity and authorization** in the TafakoriTech microservices platform. It stores users, roles, and permissions, manages RBAC assignments, and exposes a context API consumed by Sentinel during JWT issuance.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     API Gateway (Kong)                   │
└────────────────┬────────────────────────────────────────┘
                 │ /persona/*
     ┌───────────▼───────────┐        ┌──────────────────┐
     │        Persona         │◄──────►│    Sentinel      │
     │  (IAM Authorization)   │        │  (Auth + JWT)    │
     │                        │        │                  │
     │  • Users               │ /internal/users/:id/context
     │  • Roles               │        └──────────────────┘
     │  • Permissions         │
     │  • Teams               │
     │  • Audit log (stdout)  │
     └───────────┬────────────┘
                 │
     ┌───────────▼────────────┐
     │    PostgreSQL (persona) │
     │  users · roles          │
     │  permissions · teams    │
     │  user_roles             │
     │  role_permissions       │
     │  team_users             │
     └────────────────────────┘
```

### Clean Architecture Layout

```
persona/
├── cmd/persona/
│   ├── main.go          # Bootstrap, DI wiring
│   └── swagger.json     # Embedded OpenAPI 2.0 spec
├── internal/
│   ├── user/            # Identity domain
│   │   ├── domain/      # User struct, Status enum
│   │   ├── repository/  # Interface + postgres impl
│   │   ├── service/     # Business logic
│   │   └── handler/     # HTTP handlers + audit emission
│   ├── role/            # RBAC role domain
│   ├── permission/      # Fine-grained permission domain
│   ├── team/            # Team (group) domain
│   └── access/          # Cross-cutting authorization context
│       ├── repository/postgres/  # GetUserContext JOIN query
│       ├── service/              # GetUserContext service
│       └── handler/              # Internal HTTP handler
├── migrations/
│   ├── 001_init.sql          # Schema + indexes + triggers
│   ├── 002_seed.sql          # Default roles, permissions, super-admin
│   └── 003_authz_version.sql # authz_version column
└── shared/
    ├── audit/       # Async IAM audit event emitter
    ├── apperror/    # Sentinel errors + HTTP mapping
    ├── config/      # Env-based config loader
    ├── ctxkey/      # Typed context keys
    ├── db/          # pgxpool factory
    ├── middleware/  # RequestID, TraceID, Logger, Auth, APIKey
    └── pagination/  # Cursor-based pagination helpers
```

---

## API Design

### Public API (`/api/v1/*`) — JWT Bearer required

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/users` | Create user |
| GET | `/api/v1/users` | List users (paginated, filterable) |
| GET | `/api/v1/users/:id` | Get user by ID |
| PUT | `/api/v1/users/:id` | Update user fields |
| DELETE | `/api/v1/users/:id` | Soft-delete user |
| POST | `/api/v1/users/:id/roles` | Assign role to user |
| DELETE | `/api/v1/users/:id/roles/:roleId` | Remove role from user |
| GET | `/api/v1/users/:id/roles` | List user's roles |
| GET | `/api/v1/users/:id/teams` | List user's teams |
| POST | `/api/v1/roles` | Create role |
| GET | `/api/v1/roles` | List roles |
| GET | `/api/v1/roles/:id` | Get role by ID |
| PUT | `/api/v1/roles/:id` | Update role |
| DELETE | `/api/v1/roles/:id` | Soft-delete role |
| POST | `/api/v1/roles/:id/permissions` | Assign permission to role |
| DELETE | `/api/v1/roles/:id/permissions/:permissionId` | Remove permission from role |
| GET | `/api/v1/roles/:id/permissions` | List role's permissions |
| POST | `/api/v1/permissions` | Create permission |
| GET | `/api/v1/permissions` | List permissions |
| GET | `/api/v1/permissions/:id` | Get permission by ID |
| PUT | `/api/v1/permissions/:id` | Update permission |
| DELETE | `/api/v1/permissions/:id` | Soft-delete permission |
| POST | `/api/v1/teams` | Create team |
| GET | `/api/v1/teams` | List teams |
| GET | `/api/v1/teams/:id` | Get team by ID |
| PUT | `/api/v1/teams/:id` | Update team |
| DELETE | `/api/v1/teams/:id` | Soft-delete team |
| POST | `/api/v1/teams/:id/users` | Add user to team |
| DELETE | `/api/v1/teams/:id/users/:userId` | Remove user from team |
| GET | `/api/v1/teams/:id/users` | List team members |

### Internal API (`/internal/*`) — X-API-Key required

| Method | Path | Description |
|--------|------|-------------|
| GET | `/internal/users/by-email?identifier=` | Lookup user by email/phone (Sentinel login) |
| GET | `/internal/users/:id/context` | Full RBAC context for JWT enrichment |

#### User Context Response

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "roles": ["admin", "operator"],
  "permissions": ["users.read", "users.write", "logs.read"],
  "status": "active",
  "authz_version": 14
}
```

---

## RBAC Model

Persona implements **flat RBAC (RBAC Level 1)**:

```
User ──(many-to-many)──► Role ──(many-to-many)──► Permission
```

- A **user** can hold multiple roles.
- A **role** is a named set of permissions with a unique slug.
- A **permission** is a `resource.action` tuple (e.g. `logs.read`, `users.write`).
- Effective permissions for a user = union of permissions across all assigned roles.

### Permission Naming Convention

```
<resource>.<action>

Examples:
  users.read       users.write      users.delete
  roles.manage     permissions.create
  logs.read        content.publish
```

### Authorization Versioning

Every user has an `authz_version` counter (BIGINT, starts at 1). It increments atomically in a database transaction whenever:

- A role is **assigned to** or **revoked from** a user
- A permission is **assigned to** or **revoked from** a role (bumps all users holding that role)

Sentinel embeds `authz_version` in the JWT payload. On each request, services can compare the token's version against the current value from Persona to detect stale authorizations — enabling fine-grained cache invalidation without token revocation.

---

## Integration with Sentinel

Sentinel is the authentication service. It calls Persona at two points:

### 1. Login / User Lookup

```
POST /auth/login  →  GET /internal/users/by-email?identifier=<email>
```

Sentinel verifies the user exists in Persona and is active before issuing any token.

### 2. JWT Issuance

```
Sentinel issues JWT  →  GET /internal/users/:id/context
```

Persona returns the full RBAC snapshot. Sentinel embeds `roles`, `permissions`, and `authz_version` as JWT claims. Downstream services can enforce authorization using the claims directly — no Persona round-trip needed per request.

### Sentinel Persona Client

Located at `sentinel/internal/persona/client.go`:

```go
client := persona.New(personaURL, apiKey)

// During login
err := client.CheckUser(ctx, email)

// During JWT issuance
ctx, err := client.GetUserContext(ctx, userID)
// ctx.Roles, ctx.Permissions, ctx.AuthzVersion
```

---

## Deployment Model

### Docker Swarm Stack (`infra/docker-stack.yml`)

```yaml
persona:
  image: ${PERSONA_IMAGE}
  replicas: 2
  resources:
    limits:   { cpus: "0.5", memory: 256M }
    reservations: { cpus: "0.1", memory: 64M }
  healthcheck:
    test: wget -qO- http://127.0.0.1:8095/health
    interval: 15s
  secrets: [postgres_password, jwt_secret, persona_api_key]
  networks: [kong-net]
```

**Stateless design**: Persona holds no in-process state. Every replica reads from the same PostgreSQL database. Horizontal scaling is safe — just increase `replicas`.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DATABASE_URL` | ✓ | PostgreSQL DSN |
| `JWT_SECRET` | ✓ | Shared secret for JWT validation (enrichment only) |
| `API_KEY` | ✓ | Service-to-service key for `/internal/*` |
| `PORT` | — | HTTP listen port (default: `8095`) |
| `ENV` | — | `production` enables release mode + JSON logs |
| `SWAGGER_HOST` | — | Override for Swagger UI host |
| `SWAGGER_BASE_PATH` | — | Override for Swagger UI base path |

### Migrations

```bash
# Apply schema
make migrate         # runs 001_init.sql + 003_authz_version.sql

# Seed default data
make seed            # runs 002_seed.sql with ADMIN_EMAIL
```

---

## Security Model

| Layer | Control |
|-------|---------|
| Public API | JWT Bearer token (enforced by Kong gateway) |
| Internal API | X-API-Key header — rejected if missing or wrong |
| Database | Parameterized queries only; no string interpolation |
| Soft deletes | Users/roles/permissions are never hard-deleted; `deleted_at` prevents data loss |
| Privilege escalation | Persona has no authorization enforcement on who can assign roles — that is Kong's responsibility via route-level JWT policies. Persona is a data plane only. |
| Secrets | `postgres_password`, `jwt_secret`, `persona_api_key` are Docker secrets — never env vars in plain text |
| Log safety | Audit logs include IDs and actor IDs only; no passwords, tokens, or PII values |

---

## Logging Strategy

All logs are structured JSON to stdout, compatible with Fluent Bit → Elasticsearch → Kibana.

### Request Logs (Zap)

```json
{
  "level": "info",
  "timestamp": "2026-06-15T10:00:00.000Z",
  "service": "persona",
  "message": "request",
  "method": "POST",
  "path": "/api/v1/users",
  "status": 201,
  "latency": 12,
  "request_id": "abc-123",
  "trace_id": "xyz-456"
}
```

### Audit Logs

```json
{
  "timestamp": "2026-06-15T10:00:00Z",
  "log_type": "audit",
  "service": "persona",
  "event": "role.assigned_to_user",
  "result": "success",
  "actor": "admin-user-uuid",
  "user_id": "target-user-uuid",
  "trace_id": "xyz-456",
  "request_id": "abc-123",
  "meta": {
    "role_id": "role-uuid"
  }
}
```

**Audit events emitted:**

| Event | Trigger |
|-------|---------|
| `user.created` | POST /api/v1/users |
| `user.updated` | PUT /api/v1/users/:id |
| `user.deleted` | DELETE /api/v1/users/:id |
| `role.created` | POST /api/v1/roles |
| `role.updated` | PUT /api/v1/roles/:id |
| `role.deleted` | DELETE /api/v1/roles/:id |
| `permission.created` | POST /api/v1/permissions |
| `permission.updated` | PUT /api/v1/permissions/:id |
| `permission.deleted` | DELETE /api/v1/permissions/:id |
| `role.assigned_to_user` | POST /api/v1/users/:id/roles |
| `role.revoked_from_user` | DELETE /api/v1/users/:id/roles/:roleId |
| `permission.assigned_to_role` | POST /api/v1/roles/:id/permissions |
| `permission.revoked_from_role` | DELETE /api/v1/roles/:id/permissions/:permId |

The audit logger is non-blocking (buffered channel, 512 entries). On shutdown, `Shutdown()` drains remaining events before the process exits.

---

## Database Schema Summary

```sql
users            id · email · status · authz_version · deleted_at
roles            id · name · slug · deleted_at
permissions      id · name · slug · resource · action · deleted_at
teams            id · name · deleted_at
user_roles       user_id · role_id                    (many-to-many)
role_permissions role_id · permission_id              (many-to-many)
team_users       team_id · user_id                    (many-to-many)
```

All tables have `updated_at` triggers. All foreign keys cascade on delete. Soft-delete columns (`deleted_at`) are partial-indexed for query performance.
