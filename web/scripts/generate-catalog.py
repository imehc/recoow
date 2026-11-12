"""从公共接口契约生成实体字段、依赖和数据版本。"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from openapi_contract import manifests

catalog = [{"table": item["table"], "titles": item["summary"], "fields": item["columns"], "dependencies": item.get("dependencies", []), "dataVersion": item["dataVersion"]} for item in manifests()]
target = ROOT / "web/src/lib/generated/catalog.ts"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("// Generated from OpenAPI. Do not edit.\nexport const entities = " + json.dumps(catalog, ensure_ascii=False, indent=2) + " as const;\n")
