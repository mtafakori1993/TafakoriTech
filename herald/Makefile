.PHONY: run build test tidy swagger docker-build migrate seed lint

# ── Dev ──────────────────────────────────────────────────────────────────────
run:
	go run ./cmd/herald

build:
	CGO_ENABLED=0 go build -o bin/herald ./cmd/herald

tidy:
	go mod tidy

# ── Tests ────────────────────────────────────────────────────────────────────
test:
	go test ./... -v -race -count=1

test-cover:
	go test ./... -coverprofile=coverage.out && go tool cover -html=coverage.out

# ── Swagger (requires: go install github.com/swaggo/swag/cmd/swag@latest) ───
swagger:
	swag init --generalInfo cmd/herald/main.go --output docs --parseInternal

# ── Database ─────────────────────────────────────────────────────────────────
# Usage: make migrate DB_URL="postgres://user:pass@127.0.0.1:5445/herald?sslmode=disable"
migrate:
	@for f in migrations/*.sql; do \
		echo "Applying $$f…"; \
		psql "$(DB_URL)" -f $$f; \
	done

seed:
	psql "$(DB_URL)" -f migrations/007_seed.sql

# ── Docker ───────────────────────────────────────────────────────────────────
docker-build:
	docker build -t commanderproject/herald:latest .

# ── Lint (requires: go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest) ──
lint:
	golangci-lint run ./...
