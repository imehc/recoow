.PHONY: generate check-generated test backend backend-package decision-models-ios ios-build

BLENDER ?= $(if $(wildcard /Applications/Blender.app/Contents/MacOS/Blender),/Applications/Blender.app/Contents/MacOS/Blender,blender)

PYTHON ?= $(if $(wildcard .venv/bin/python),.venv/bin/python,python3)
OPENAPI_GENERATOR ?= openapi-generator
OPENAPI_VERSION := 7.22.0
OPENAPI_SPEC := openapi/openapi.yaml
SQLC_VERSION := v1.30.0
SQLC ?= go run github.com/sqlc-dev/sqlc/cmd/sqlc@$(SQLC_VERSION)

.PHONY: generate-sql check-sql
# 从 SQL 定义生成后端数据库访问代码。
generate-sql:
	cd backend && $(SQLC) generate

# 检查数据库访问代码是否与 SQL 定义一致。
check-sql:
	cd backend && $(SQLC) diff

.PHONY: check-openapi generate-clients generate-ios-api generate-android-api generate-web-api generate-go-api generate-api-field-names
# 生成各端的 snake_case 字段映射及旧字段兼容别名。
generate-api-field-names:
	$(PYTHON) scripts/generate_api_field_names.py --write

# 校验 OpenAPI 格式及生成器版本。
check-openapi:
	@test "$$($(OPENAPI_GENERATOR) version)" = "$(OPENAPI_VERSION)" || { printf '%s\n' '需要 OpenAPI Generator $(OPENAPI_VERSION)'; exit 1; }
	$(OPENAPI_GENERATOR) validate -i $(OPENAPI_SPEC)

# 生成三端 SDK 与后端接口模型；产物不提交。
generate-clients: generate-ios-api generate-android-api generate-web-api generate-go-api

# 生成 iOS 的 Swift API 包。
generate-ios-api: check-openapi generate-api-field-names
	$(OPENAPI_GENERATOR) generate -i $(OPENAPI_SPEC) -g swift5 -o generated/ios-api --type-mappings DateTime=String,date-time=String --model-name-prefix RWAPI --additional-properties projectName=RecoowAPI,library=urlsession,responseAs=AsyncAwait,useSPMFileStructure=true,useBacktickEscapes=true

# 生成 Android 的 Kotlin 模型及 Retrofit 接口。
generate-android-api: check-openapi generate-api-field-names
	rm -rf android/core/network/build/generated/openapi
	$(OPENAPI_GENERATOR) generate -i $(OPENAPI_SPEC) -g kotlin -t android/build-tools/openapi-templates -o android/core/network/build/generated/openapi --type-mappings object=JsonElement,AnyType=JsonElement,ByteArray=kotlin.String --import-mappings JsonElement=kotlinx.serialization.json.JsonElement --model-name-prefix RWAPI --additional-properties packageName=imehc.recoow.core.network.generated,serializationLibrary=kotlinx_serialization,library=jvm-retrofit2,dateLibrary=string,useCoroutines=true --global-property apis,models,apiDocs=false,apiTests=false,modelDocs=false,modelTests=false,supportingFiles=CollectionFormats.kt

# 生成 Web 的 TypeScript 模型及 Fetch 接口。
generate-web-api: check-openapi generate-api-field-names
	$(OPENAPI_GENERATOR) generate -i $(OPENAPI_SPEC) -g typescript-fetch -o generated/web-api --additional-properties supportsES6=true

# 生成后端 Go 请求与响应模型。
generate-go-api: check-openapi generate-api-field-names
	rm -rf backend/internal/handler/generated
	$(OPENAPI_GENERATOR) generate -i $(OPENAPI_SPEC) -g go -o backend/internal/handler/generated --additional-properties packageName=generated,enumClassPrefix=true --global-property models,modelDocs=false,modelTests=false,supportingFiles=utils.go

# 生成全部接口、数据库代码和 iOS 同步实体注册表。
generate: generate-clients generate-sql
	$(PYTHON) scripts/generate_sync_registry.py --write

# 检查 SQL、接口字段映射、实体注册表及各端快照模型。
check-generated: check-sql
	$(PYTHON) scripts/generate_api_field_names.py --check
	$(PYTHON) scripts/generate_sync_registry.py --check
	$(PYTHON) scripts/check_schema_gate.py --require-full-coverage

# 重新生成契约后，运行脚本测试、后端测试和静态检查。
test: generate check-generated
	PYTHONDONTWRITEBYTECODE=1 $(PYTHON) -m unittest discover -s tests -t . -p 'test_*.py'
	cd backend && go test ./... && go vet ./...

# 打包后端可执行文件与运行时契约到 dist/。
backend: generate-go-api generate-sql
	mkdir -p dist/openapi
	cp openapi/openapi.yaml dist/openapi/openapi.yaml
	cd backend && go build -o ../dist/recoow-api ./cmd/api
	cd backend && go build -o ../dist/recoow-worker ./cmd/worker

# 打包后端发布文件。
backend-package:
	bash scripts/package-backend.sh

# 更新共享模型后重新导出 iOS 资源；导出产物与源文件一同纳入 Git。
decision-models-ios:
	@command -v "$(BLENDER)" >/dev/null 2>&1 || { printf '%s\n' '未找到 Blender，请安装 Blender 或指定 BLENDER=/path/to/blender。'; exit 1; }
	"$(BLENDER)" --background --factory-startup assets/decision-scene/decision-scene.blend --python-exit-code 1 --python assets/decision-scene/build_scene.py -- export-ios

# 生成契约并构建 iOS 模拟器版本，使用已提交的模型资源，无需 Blender。
ios-build: generate
	xcodebuild -project ios/recoow.xcodeproj -scheme recoow -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
