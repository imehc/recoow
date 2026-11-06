.PHONY: generate test build migrate
# 生成后端接口模型和数据库访问代码。
generate:
	$(MAKE) -C .. generate-go-api generate-sql
# 生成代码后运行 Go 测试与静态检查。
test: generate
	go test ./...
	go vet ./...
# 构建 API 和后台任务可执行文件到 dist/。
build: generate
	mkdir -p ../dist
	go build -o ../dist/recoow-api ./cmd/api
	go build -o ../dist/recoow-worker ./cmd/worker
# 执行数据库迁移；需提前配置迁移连接串。
migrate:
	go run ./cmd/api -migrate
