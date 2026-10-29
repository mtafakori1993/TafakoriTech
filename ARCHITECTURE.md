# TafakoriTech — Microservices Architecture

## Overview

```mermaid
graph TB
    Client["🌐 Client Layer\n(Browser / Mobile / API Client)"]

    subgraph GW["Kong API Gateway :8080"]
        direction TB
        Plugins["Global Plugins\n• CORS — Allow-Origin: *, expose X-Request-ID\n• JWT (per svc) — Validate Bearer token issued by Sentinel\n• Request-Transformer — Inject internal X-API-Key headers"]
        Routes["Routes (strip_path: true)\n/sentinel/* → sentinel:8094  (no JWT)\n/persona/*  → persona:8095   (JWT)\n/marshal/*  → marshal:8090   (JWT + X-API-Key)\n/herald/*   → herald:8092    (JWT + X-API-Key)\n/harbor/*   → harbor:8093    (JWT + X-API-Key + X-Admin-Key)\n/swagger/*  → swagger-ui:8088 (public)\n/*/openapi.json → per-service (public)"]
        Plugins --> Routes
    end

    subgraph NET["Docker Swarm Overlay Network (kong-net)"]
        direction TB

        subgraph SVC["Microservices"]
            direction LR
            Sentinel["**SENTINEL** :8094\nAuth Service\n• OTP login\n• Admin login\n• JWT issue\n• Refresh / Logout"]
            Persona["**PERSONA** :8095\nIAM Service\n• Users / Roles\n• Permissions\n• Teams\n• Access resolution"]
            Marshal["**MARSHAL** :8090\nProject Manager\n• Projects CRUD"]
            Herald["**HERALD** :8092\nHeadless CMS\n• Content / Posts\n• Pages / Articles\n• Categories"]
            Harbor["**HARBOR** :8093\nLead CRM\n• Leads / Campaigns\n• Ad sources\n• Contacts"]
        end

        subgraph DB["PostgreSQL :5432 (internal only)"]
            direction LR
            dbSentinel[(sentinel)]
            dbPersona[(persona)]
            dbMarshal[(marshal)]
            dbHerald[(herald)]
            dbHarbor[(harbor)]
            dbAuth[(auth)]
        end

        Sentinel --> dbSentinel
        Persona  --> dbPersona
        Marshal  --> dbMarshal
        Herald   --> dbHerald
        Harbor   --> dbHarbor
    end

    Client -->|HTTPS| GW
    Routes -->|internal| Sentinel
    Routes -->|internal| Persona
    Routes -->|internal| Marshal
    Routes -->|internal| Herald
    Routes -->|internal| Harbor
```

## Service Catalogue

| Service      | Port | Responsibility                                    | DB          |
|--------------|------|---------------------------------------------------|-------------|
| **Sentinel** | 8094 | Authentication — OTP & admin login, JWT issuance  | `sentinel`  |
| **Persona**  | 8095 | IAM — Users, Roles, Permissions, Teams, Access    | `persona`   |
| **Marshal**  | 8090 | Project management — Projects CRUD                | `marshal`   |
| **Herald**   | 8092 | Headless CMS — Content, Posts, Pages, Articles   | `herald`    |
| **Harbor**   | 8093 | Lead CRM — Leads, Campaigns, Ad platforms         | `harbor`    |
| **Kong**     | 8080 | API Gateway — routing, JWT validation, CORS       | —           |
| **Swagger**  | 8088 | API documentation UI                              | —           |
| **Postgres** | 5432 | Database (internal only)                          | —           |

## Authentication & Authorization Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant K as Kong :8080
    participant S as Sentinel :8094
    participant P as Persona :8095

    C->>S: POST /sentinel/admin/login (or /otp/verify)
    S-->>C: { api_key (JWT), refresh_key (JWT) }

    C->>K: GET /persona/api/v1/users\nAuthorization: Bearer <api_key>
    K->>K: JWT plugin validates token\nagainst shared JWT_SECRET
    K->>P: forward request\n+ inject X-API-Key header
    P->>P: resolve roles & permissions
    P-->>C: response
```

## Security Model

```mermaid
graph LR
    subgraph Public["🔓 Public (no JWT)"]
        P1["/sentinel/*\nlogin · OTP · refresh"]
        P2["/*/openapi.json\nAPI specs"]
        P3["/swagger/*\nSwagger UI"]
    end

    subgraph Protected["🔒 Protected (JWT required)"]
        R1["/persona/*\nIAM management"]
        R2["/marshal/*\nProject management"]
        R3["/herald/*\nCMS management"]
        R4["/harbor/*\nCRM management"]
    end

    subgraph Internal["🚫 Internal only (never exposed)"]
        I1["X-API-Key\nper-service key injected by Kong"]
        I2["X-Admin-Key\nharbor admin key injected by Kong"]
        I3["Database passwords\nswarm overlay network only"]
        I4["Postgres :5432\nnot published outside swarm"]
    end
```

## Port Map (external)

| Port   | Service            |
|--------|--------------------|
| `8080` | Kong proxy (API)   |
| `8001` | Kong admin         |
| `8088` | Swagger UI         |

All other service ports are internal to the Docker Swarm overlay network only.

## Tech Stack

| Layer         | Technology                         |
|---------------|------------------------------------|
| Language      | Go 1.22                            |
| Framework     | Gin (HTTP)                         |
| Database      | PostgreSQL 16                      |
| Driver        | pgx/v5                             |
| Auth tokens   | JWT HS256 (golang-jwt/jwt)         |
| Gateway       | Kong 3.7 (DB-less declarative)     |
| Orchestration | Docker Swarm                       |
| Logging       | zap (Persona), stdlib (others)     |
| Docs          | OpenAPI 2.0 (Swagger UI)           |
