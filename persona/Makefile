SHELL := /bin/bash
.PHONY: build run migrate seed test

build:
	CGO_ENABLED=0 GOOS=linux go build -o persona ./cmd/persona

run:
	go run ./cmd/persona

ENV_FILE ?= ../infra/.env
POSTGRES_CONTAINER = $(shell docker ps -qf "name=TafakoriTech_postgres")

psql-exec = set -a && source $(ENV_FILE) && set +a && \
  docker exec -i $(POSTGRES_CONTAINER) \
    psql -U $$POSTGRES_USER -d persona

migrate:
	@$(psql-exec) < migrations/001_init.sql
	@$(psql-exec) < migrations/003_authz_version.sql

seed:
	@set -a && source $(ENV_FILE) && set +a && \
	  docker exec -i $(POSTGRES_CONTAINER) \
	    psql -U $$POSTGRES_USER -d persona -v admin_email="$$ADMIN_EMAIL" \
	    < migrations/002_seed.sql && \
	  echo "Seed complete — super-admin seeded as $$ADMIN_EMAIL"

test:
	go test ./...
