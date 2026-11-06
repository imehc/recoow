"""从唯一 OpenAPI 契约读取同步实体及构建输入。"""
import yaml
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi/openapi.yaml"


def document(path=SPEC):
    result = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if result.get("openapi") != "3.0.3":
        raise ValueError("需要 OpenAPI 3.0.3 契约")
    return result


def manifests(path=SPEC):
    result = []
    for name, model in document(path)["components"]["schemas"].items():
        options = model.get("x-recoow-sync")
        if options is None:
            continue
        required = set(model.get("required", []))
        columns = []
        for field, definition in model["properties"].items():
            metadata = dict(definition.get("x-recoow", {}))
            kind = {"string": "text", "integer": "int", "number": "real", "boolean": "bool"}.get(definition.get("type"))
            if metadata.get("binary"):
                kind = "blob"
            if kind is None:
                raise ValueError(f"不支持的同步字段：{name}.{field}")
            column = {"name": field, "type": kind, "format": metadata.pop("format", kind)}
            if field in required:
                column["required"] = True
            if definition.get("nullable"):
                column["nullable"] = True
            if "enum" in definition:
                column["enumValues"] = definition["enum"]
            if "default" in definition:
                column["default"] = definition["default"]
            column.update(metadata)
            columns.append(column)
        result.append({**options, "columns": columns})
    tables = [row["table"] for row in result]
    if len(tables) != len(set(tables)) or not tables:
        raise ValueError("同步实体缺失或重复")
    return sorted(result, key=lambda row: row["table"])


def input_file_list():
    return "\n".join("$(SRCROOT)/../" + path for path in (
        "openapi/openapi.yaml", "scripts/sync-models.xcfilelist",
        "scripts/generate_sync_registry.py",
        "scripts/generate_api_field_names.py",
        "scripts/openapi_contract.py",
    )) + "\n"
