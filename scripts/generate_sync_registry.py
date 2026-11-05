#!/usr/bin/env python3
"""从共享 schema 生成 iOS 同步注册表；--patch 输出 apply_patch 输入。"""
import argparse
import difflib
import json
from pathlib import Path
from openapi_contract import manifests as generate_manifests, input_file_list

ROOT = Path(__file__).resolve().parent.parent
DESTINATION = Path("ios/recoow/Core/Sync/SyncEntityCatalog.generated.swift")


def ordered_manifests():
    manifests = {manifest["table"]: manifest for manifest in generate_manifests()}
    ordered = []
    visited = set()
    visiting = set()

    def visit(table):
        if table in visiting:
            raise ValueError("循环依赖：" + table)
        if table in visited:
            return
        if table not in manifests:
            raise ValueError("依赖实体缺少清单：" + table)
        visiting.add(table)
        for dependency in manifests[table].get("dependencies", []):
            visit(dependency["table"])
        visiting.remove(table)
        visited.add(table)
        ordered.append(manifests[table])

    for table in sorted(manifests):
        visit(table)
    return ordered


def render():
    lines = ["import Foundation", "", "nonisolated enum SyncEntityCatalog {", "    static let entities: [SyncEntityDefinition] = ["]
    string = lambda value: json.dumps(value, ensure_ascii=False)
    for manifest in ordered_manifests():
        lines += ["        SyncEntityDefinition(",
                  f'            table: {string(manifest["table"])}, version: {manifest["dataVersion"]},',
                  "            fields: ["]
        for column in manifest["columns"]:
            arguments = [string(column["name"]), f'.{column["type"]}']
            for flag in ("required", "nullable", "binary", "derived"):
                if column.get(flag):
                    arguments.append(flag + ": true")
            if "default" in column:
                arguments.append("defaultJSON: " + string(json.dumps(column["default"], ensure_ascii=False)))
            if column.get("enumValues"):
                arguments.append("enumValues: " + string(column["enumValues"]))
            if column.get("format") == "json":
                arguments.append("jsonText: true")
            lines.append("                SyncField(" + ", ".join(arguments) + "),")
        lines += ["            ],", "            dependencies: ["]
        for dependency in manifest.get("dependencies", []):
            lines.append("                SyncDependency(field: " + string(dependency["field"]) + ", table: " + string(dependency["table"]) + "),")
        lines += ["            ]", "        ),"]
    lines += ["    ]", "}", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--patch", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write", action="store_true")
    arguments = parser.parse_args()
    generated = render()
    target = ROOT / DESTINATION
    current = target.read_text() if target.exists() else ""
    inputs = ROOT / "scripts/sync-models.xcfilelist"
    if arguments.check:
        if not inputs.exists() or inputs.read_text() != input_file_list():
            raise SystemExit("构建输入清单过期：运行 make generate")
        if current != generated:
            raise SystemExit("注册表与 OpenAPI 不一致：运行 make generate")
        print("[OK] iOS 注册表与全部共享契约一致")
    elif arguments.write:
        inputs.write_text(input_file_list())
        if current != generated:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(generated)
    elif arguments.patch:
        print("*** Begin Patch")
        if current != generated:
            print("*** " + ("Update" if current else "Add") + " File: " + str(DESTINATION))
            if current:
                for line in list(difflib.unified_diff(current.splitlines(True), generated.splitlines(True)))[2:]:
                    print("@@\n" if line.startswith("@@") else line, end="")
            else:
                for line in generated.splitlines():
                    print("+" + line)
        print("*** End Patch")
    else:
        print(generated, end="")


if __name__ == "__main__":
    main()
