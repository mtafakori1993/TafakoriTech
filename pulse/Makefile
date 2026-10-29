SHELL := /bin/bash
.PHONY: build run migrate test lint

build:
	CGO_ENABLED=0 GOOS=linux go build -o bin/pulse ./cmd/pulse

run:
	go run ./cmd/pulse

ENV_FILE ?= ../infra/.env
POSTGRES_CONTAINER = $(shell docker ps -qf "name=TafakoriTech_postgres")

psql-exec = set -a && source $(ENV_FILE) && set +a && \
  docker exec -i $(POSTGRES_CONTAINER) \
    psql -U $$POSTGRES_USER -d pulse

migrate:
	@$(psql-exec) < migrations/001_create_notifications.up.sql

migrate-down:
	@$(psql-exec) < migrations/001_create_notifications.down.sql

test:
	go test ./...

lint:
	golangci-lint run
