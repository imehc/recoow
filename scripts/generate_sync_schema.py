#!/usr/bin/env python3
"""从 iOS 模型与审查过的版本、依赖、派生规则生成共享数据契约。"""
import argparse
import json
from pathlib import Path

import check_schema_gate as gate
from sync_contract_rules import FIELD_OPTIONS, TABLE_OPTIONS


def manifests():
    result = []
    system = {"id", "created_at", "updated_at", "deleted_at", "device_id", "server_version"}
    models = gate.discover_models()
    stale = set(TABLE_OPTIONS) - set(models)
    if stale:
        raise ValueError("版本规则引用了不存在的实体：" + ", ".join(sorted(stale)))
    for table, model in sorted(models.items()):
        options = TABLE_OPTIONS.get(table, {"manifestVersion": 1, "dataVersion": 1, "minDataVersion": 1, "dependencies": []})
        manifest = {"table": table, **options, "columns": []}
        fields = gate.model_fields(model)
        for name, (swift_type, nullable) in fields.items():
            if name in gate.LOCAL_ONLY:
                continue
            if swift_type not in gate.SWIFT_TYPES:
                raise ValueError(f"未注册的 DTO 类型：{table}.{name}: {swift_type}")
            datatype = gate.SWIFT_TYPES[swift_type]
            column = {"name": name, "type": datatype, "format": datatype}
            if datatype == "blob":
                column.update(format="binary", binary=True)
            elif name.endswith("_json"):
                column["format"] = "json"
            elif name.endswith("_at") or name == "timestamp_ms":
                column["format"] = "ts_ms"
            elif name.endswith("_cents"):
                column["format"] = "amount_cents"
            if nullable:
                column["nullable"] = True
            elif datatype != "blob":
                column["required"] = True
            if name in system:
                column["system"] = True
            column["editable"] = name not in system and datatype != "blob"
            column["labels"] = {"zh": name, "en": name.replace("_", " ")}
            column.update(FIELD_OPTIONS.get((table, name), {}))
            if (table, name) in gate.ENUM_MODELS:
                column["format"] = "enum"
                column["enumValues"] = sorted(gate.enum_values(gate.IOS / gate.ENUM_MODELS[table, name]))
            manifest["columns"].append(column)
        unknown = {name for owner, name in FIELD_OPTIONS if owner == table} - set(fields)
        if unknown:
            raise ValueError(f"字段规则漂移：{table}: {sorted(unknown)}")
        result.append(manifest)
    return result


def input_file_list():
    sources = set(gate.discover_models().values())
    sources.update(gate.IOS / relative for relative in gate.ENUM_MODELS.values())
    model_lines = ["$(SRCROOT)/" + str(source.relative_to(gate.ROOT / "ios")) for source in sorted(sources)]
    script_lines = ["$(SRCROOT)/../scripts/" + name for name in (
        "sync-models.xcfilelist", "generate_sync_registry.py", "generate_sync_schema.py",
        "check_schema_gate.py", "sync_contract_rules.py",
    )]
    return "\n".join(model_lines + script_lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=gate.SCHEMA_DIR)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--patch", action="store_true")
    arguments = parser.parse_args()
    patches = ["*** Begin Patch"]
    artifacts = {
        arguments.output / (manifest["table"] + ".json"): json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        for manifest in manifests()
    }
    if arguments.output.resolve() == gate.SCHEMA_DIR:
        artifacts[gate.ROOT / "scripts" / "sync-models.xcfilelist"] = input_file_list()
    stale = set(arguments.output.glob("*.json")) - set(artifacts)
    if arguments.check and stale:
        raise SystemExit("存在过期生成物：" + ", ".join(str(path) for path in sorted(stale)))
    for path in sorted(stale):
        if arguments.patch:
            patches.append("*** Delete File: " + str(path))
        else:
            path.unlink()
    for path, content in artifacts.items():
        current = path.read_text() if path.exists() else ""
        if arguments.check:
            if current != content:
                raise SystemExit("生成结果过期或缺失：" + str(path))
        elif arguments.patch:
            import difflib
            if current == content:
                continue
            patches.append("*** " + ("Update" if current else "Add") + " File: " + str(path))
            if current:
                patches.extend("@@" if line.startswith("@@") else line.rstrip("\n") for line in list(
                    difflib.unified_diff(current.splitlines(True), content.splitlines(True)))[2:])
            else:
                patches.extend("+" + line for line in content.splitlines())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            if current != content:
                path.write_text(content)
    if arguments.patch:
        print("\n".join(patches + ["*** End Patch"]))


if __name__ == "__main__":
    main()
