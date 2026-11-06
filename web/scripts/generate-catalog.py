"""Generate read-only entity metadata from the shared API contract."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from openapi_contract import manifests

catalog = [{"table": item["table"], "titles": item["summary"], "fields": item["columns"]} for item in manifests()]
target = ROOT / "web/src/lib/generated/catalog.ts"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("// Generated from OpenAPI. Do not edit.\nexport const entities = " + json.dumps(catalog, ensure_ascii=False, indent=2) + " as const;\n")
