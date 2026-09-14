.PHONY: generate check-generated test backend decision-models-ios ios-build

BLENDER ?= $(if $(wildcard /Applications/Blender.app/Contents/MacOS/Blender),/Applications/Blender.app/Contents/MacOS/Blender,blender)

# 从 iOS 模型生成后端 JSON 契约和 iOS 注册表，生成产物不纳入 Git。
generate:
	python3 scripts/generate_sync_schema.py
	python3 scripts/generate_sync_registry.py --write

# 检查生成物是否最新，以及字段、类型、枚举和全量覆盖是否一致。
check-generated:
	python3 scripts/generate_sync_schema.py --check
	python3 scripts/generate_sync_registry.py --check
	python3 scripts/check_schema_gate.py --require-full-coverage

# 重新生成契约后，运行脚本测试、后端测试和静态检查。
test: generate check-generated
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
	cd backend && go test ./... && go vet ./...

# 打包后端可执行文件与运行时契约到 dist/。
backend: generate
	mkdir -p dist/schema
	python3 scripts/generate_sync_schema.py --output dist/schema
	cd backend && go build -o ../dist/recoow-api ./cmd/api

# 更新共享模型后重新导出 iOS 资源；导出产物与源文件一同纳入 Git。
decision-models-ios:
	@command -v "$(BLENDER)" >/dev/null 2>&1 || { printf '%s\n' '未找到 Blender，请安装 Blender 或指定 BLENDER=/path/to/blender。'; exit 1; }
	"$(BLENDER)" --background --factory-startup assets/decision-scene/decision-scene.blend --python-exit-code 1 --python assets/decision-scene/build_scene.py -- export-ios

# 生成契约并构建 iOS 模拟器版本，使用已提交的模型资源，无需 Blender。
ios-build: generate
	xcodebuild -project ios/recoow.xcodeproj -scheme recoow -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
