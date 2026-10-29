.PHONY: run build swagger tidy

# Install swag CLI once: go install github.com/swaggo/swag/cmd/swag@latest
swagger:
	swag init --generalInfo main.go --output docs

tidy:
	go mod tidy

build:
	go build -o bin/marshal ./...

run:
	go run ./...

docker-build:
	docker build -t marshal:latest .
