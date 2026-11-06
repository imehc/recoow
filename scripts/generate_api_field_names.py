"""从 OpenAPI 生成各端共用的接口字段兼容别名。"""
import argparse
import json
import re
from pathlib import Path
from openapi_contract import document

ROOT = Path(__file__).resolve().parents[1]


def aliases():
    result = {}

    def add(legacy, canonical):
        if legacy in result and result[legacy] != canonical:
            raise ValueError("接口兼容别名重复：" + legacy)
        result[legacy] = canonical

    def walk(value):
        if isinstance(value, dict):
            for name, definition in value.get("properties", {}).items():
                if legacy := definition.get("x-recoow-legacy-name"):
                    add(legacy, name)
            if value.get("in") in ("query", "path"):
                if legacy := value.get("x-recoow-legacy-name"):
                    add(legacy, value["name"])
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(document())
    return dict(sorted(result.items()))


def outputs():
    pairs = aliases().items()
    quote = json.dumps
    notice = "// Generated from openapi/openapi.yaml; do not edit.\n"
    go = ["package wire", "", "var FieldNames = map[string]string{"]
    swift = ["import Foundation", "", "nonisolated enum APIFieldNames {",
             "    static let aliases: [String: String] = ["]
    kotlin = ["package imehc.recoow.core.network", "", "internal val apiFieldNames = mapOf("]
    typescript = ["export const apiFieldNames: Record<string, string> = {"]
    for legacy, canonical in pairs:
        go.append(f"\t{quote(legacy)}: {quote(canonical)},")
        swift.append(f"        {quote(legacy)}: {quote(canonical)},")
        kotlin.append(f"    {quote(legacy)} to {quote(canonical)},")
        typescript.append(f"  {legacy}: '{canonical}',")
    go += ["}", ""]
    swift += ["    ]", "}", ""]
    kotlin += [")", ""]
    typescript += ["};", ""]
    files = {
        "backend/internal/wire/field_names.generated.go": go,
        "ios/recoow/Core/Sync/APIFieldNames.generated.swift": swift,
        "android/core/network/src/main/kotlin/imehc/recoow/core/network/APIFieldNames.generated.kt": kotlin,
        "web/src/lib/api-field-names.generated.ts": typescript,
    }
    return {path: notice + "\n".join(lines) for path, lines in files.items()}


def main(platform=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="检查各端别名是否与契约一致")
    mode.add_argument("--write", action="store_true", help="生成各端字段别名文件")
    parser.add_argument("--platform", choices=("backend", "ios", "android", "web"),
                        help="只处理指定平台；默认处理全部平台")
    args = parser.parse_args()
    selected = platform or args.platform
    expected = aliases()
    for relative, text in outputs().items():
        if selected and not relative.startswith(selected + "/"):
            continue
        path = ROOT / relative
        current = path.read_text() if path.exists() else None
        if args.check:
            # Parse pairs so formatter alignment and quote style are irrelevant.
            pairs = dict(re.findall(
                r"['\"]?([A-Za-z_][A-Za-z_0-9]*)['\"]?\s*(?::|to)\s*['\"]([a-z_][a-z_0-9]*)['\"]",
                current or ""))
            if pairs != expected:
                raise SystemExit("接口字段别名过期：运行 make generate-api-field-names")
        elif current != text:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    if args.check:
        print("[OK] 各端接口字段别名与 OpenAPI 一致")


if __name__ == "__main__":
    main()
