# Commander Project

A Docker Swarm stack with a Go/Gin project manager, a headless CMS, and a Kong API gateway.

## Services

| Service | Image | Host Port | Container Port | Description |
|---|---|---|---|---|
| `postgres` | `postgres:16` | 5445 | 5432 | PostgreSQL database |
| `marshal` | `TafakoriTech/marshal:latest` | 8090 | 8090 | Go / Gin REST API |
| `herald` | `TafakoriTech/herald:latest` | 8092 | 8092 | Go / Gin Headless CMS |
| `kong` | `kong:3.7` | 8080 / 8001 | 8000 / 8001 | API gateway (DB-less mode) |
| `swagger-ui` | `swaggerapi/swagger-ui` | 8088 | 8080 | Swagger UI |

## URLs

| Endpoint | URL | Notes |
|---|---|---|
| Kong proxy | http://127.0.0.1:8080 | All public traffic goes here |
| Kong admin API | http://127.0.0.1:8001 | Gateway management |
| Marshal API (direct) | http://127.0.0.1:8090 | Direct access, bypasses Kong |
| Marshal API (via Kong) | http://127.0.0.1:8080/marshal/ | Proxied via Kong |
| Herald API (direct) | http://127.0.0.1:8092 | Direct access, bypasses Kong |
| Herald API (via Kong) | http://127.0.0.1:8080/herald/ | Proxied via Kong |
| Swagger UI (direct) | http://127.0.0.1:8088 | Direct access, bypasses Kong |
| Swagger UI (via Kong) | http://127.0.0.1:8080/swagger/ | Proxied via Kong |
| PostgreSQL | 127.0.0.1:5445 | Exposed for local dev access |

## Quick Start

```bash
cd infra
cp .env.example .env          # fill in secrets
docker swarm init             # skip if already in a swarm
docker stack deploy -c docker-stack.yml TafakoriTech
```

## Deploy / Update

```bash
# Re-deploy after image rebuild
cd infra
set -a && source .env && set +a
docker stack deploy -c docker-stack.yml TafakoriTech
```

## Status & Logs

```bash
# Service overview
docker stack services TafakoriTech

# Logs for a specific service
docker service logs -f TafakoriTech_marshal
docker service logs -f TafakoriTech_herald
docker service logs -f TafakoriTech_kong
docker service logs -f TafakoriTech_postgres

# Task history (shows exit codes on failure)
docker service ps TafakoriTech_marshal
```

## Teardown

```bash
docker stack rm TafakoriTech
```

## Environment Variables

Copy `infra/.env.example` to `infra/.env` and set:

| Variable | Description |
|---|---|
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password |
| `POSTGRES_DB` | Database name |
| `DATABASE_URL` | Full connection string for marshal |
| `PM_API_KEY` | API key for marshal |
| `HERALD_DATABASE_URL` | Database connection for herald |
| `HERALD_API_KEY` | API key for herald |
| `MARSHAL_IMAGE` | Docker image for marshal |
| `HERALD_IMAGE` | Docker image for herald |



cd ~/projects/TafakoriTech/infra
set -a && source .env && set +a
envsubst < docker-stack.yml | docker stack deploy -c - TafakoriTech