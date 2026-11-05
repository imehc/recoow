.PHONY: generate test build migrate
generate:
	$(MAKE) -C .. generate-go-api generate-sql
test: generate
	go test ./...
	go vet ./...
build: generate
	mkdir -p ../dist
	go build -o ../dist/recoow-api ./cmd/api
	go build -o ../dist/recoow-worker ./cmd/worker
migrate:
	go run ./cmd/api -migrate
