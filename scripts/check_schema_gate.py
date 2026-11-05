#!/usr/bin/env python3
"""检查已声明实体的字段、类型、可空性和枚举，并报告全量覆盖缺口。"""
import argparse
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
IOS = ROOT / "ios" / "recoow"

LOCAL_ONLY = {"sync_status"}
SWIFT_TYPES = {"String": "text", "Int": "int", "Int64": "int", "Double": "real", "Bool": "bool", "Data": "blob"}
ENUM_MODELS = {
    ("bills", "transaction_type"): "Features/Bills/Models/BillType.swift",
    ("bills", "payment_method"): "Features/Bills/Models/BillPaymentMethod.swift",
    ("bills", "settlement_status"): "Features/Bills/Models/BillSettlementStatus.swift",
    ("reminders", "schedule_kind"): "Features/Reminders/Models/ReminderScheduleKind.swift",
    ("stored_value_accounts", "kind"): "Features/Bills/Models/StoredValueAccountKind.swift",
}

ALLOWED_TYPES = {"text", "int", "real", "bool", "blob"}
ALLOWED_FORMATS = {
    "text", "multiline", "int", "real", "bool", "ts_ms", "amount_cents",
    "duration_s", "enum", "media_ref", "media_ids", "json", "binary",
}


def discover_models():
    models = {}
    inputs = os.environ.get("RECOOW_SYNC_MODEL_INPUTS")
    paths = IOS.rglob("*.swift") if not inputs else (
        Path(line.replace("$(SRCROOT)", str(ROOT / "ios")))
        for line in Path(inputs).read_text().splitlines() if line.endswith(".swift")
    )
    for path in sorted(paths):
        text = path.read_text(encoding="utf-8")
        if not re.search(r"\bstruct\s+\w+[^\{]*\bSyncableRecord\b[^\{]*\{", text):
            continue
        match = re.search(r'static let databaseTableName = "([^"]+)"', text)
        if match and match.group(1) != "change_log":
            table = match.group(1)
            if table in models:
                raise ValueError("重复的 iOS 实体：%s" % table)
            models[table] = path
    return models


def model_fields(path):
    text = path.read_text(encoding="utf-8")
    block = re.search(r"enum CodingKeys[^\{]*\{(.*?)\}", text, re.S)
    if not block:
        raise ValueError("缺少 CodingKeys：%s" % path)
    properties = {
        name: (swift_type, bool(optional))
        for name, swift_type, optional in re.findall(
            r"^\s*var\s+(\w+)\s*:\s*(\w+)(\?)?\s*(?=$|=)", text[:block.start()], re.M
        )
    }
    fields = {}
    for name, raw_name in re.findall(r'case\s+(\w+)(?:\s*=\s*"([^"]+)")?', block.group(1)):
        if name not in properties:
            raise ValueError("无法识别属性类型 %s：%s" % (name, path))
        fields[raw_name or name] = properties[name]
    return fields


def enum_values(path):
    text = path.read_text(encoding="utf-8")
    return {
        raw_value or name
        for name, raw_value in re.findall(
            r'^\s*case\s+(\w+)(?:\s*=\s*"([^"]+)")?\s*(?://[^\n]*)?$', text, re.M
        )
    }


def coverage_problems(tables, models):
    return ["缺少实体清单：%s" % table for table in sorted(set(models) - set(tables))]


def check(table, manifest, models=None):
    models = discover_models() if models is None else models
    if table not in models:
        return ["未找到对应的 iOS 同步实体：%s" % table]
    fields = model_fields(models[table])
    problems = []
    if manifest.get("table") != table:
        problems.append("清单 table 与文件名不一致：%s" % table)
    version = manifest.get("manifestVersion")
    if type(version) is not int or version < 1:
        problems.append("表 %s manifestVersion 必须是正整数" % table)
    columns = manifest.get("columns")
    if not isinstance(columns, list) or not columns:
        return problems + ["表 %s 缺少 columns" % table]
    seen = set()
    for col in columns:
        if not isinstance(col, dict):
            problems.append("表 %s 存在无效的列定义" % table)
            continue
        name = col.get("name", "")
        ctype = col.get("type")
        cformat = col.get("format")
        if not name:
            problems.append("表 %s 存在未命名字段" % table)
            continue
        if name in seen:
            problems.append("表 %s 字段 %s 重复声明" % (table, name))
        seen.add(name)
        if ctype not in ALLOWED_TYPES:
            problems.append("表 %s 字段 %s type %r 不合法" % (table, name, ctype))
        if cformat not in ALLOWED_FORMATS:
            problems.append("表 %s 字段 %s format %r 不合法" % (table, name, cformat))
        if cformat == "enum":
            values = col.get("enumValues")
            source = ENUM_MODELS.get((table, name))
            if not isinstance(values, list) or not values or not all(isinstance(value, str) for value in values):
                problems.append("表 %s 字段 %s 缺少合法 enumValues" % (table, name))
            elif len(values) != len(set(values)):
                problems.append("表 %s 字段 %s 枚举值重复" % (table, name))
            elif not source:
                problems.append("表 %s 字段 %s 缺少 iOS 枚举来源映射" % (table, name))
            elif set(values) != enum_values(IOS / source):
                problems.append("表 %s 字段 %s 枚举值与 iOS 不一致" % (table, name))
        if col.get("binary") and cformat != "binary":
            problems.append("表 %s 字段 %s 声明 binary 但 format 非 binary" % (table, name))
        if name in LOCAL_ONLY:
            problems.append("本机字段 %s 不应加入共享契约" % name)
            continue
        if col.get("deprecated"):
            continue
        if name not in fields:
            problems.append("表 %s 的字段 %s 未在 iOS CodingKeys 中找到" % (table, name))
            continue
        swift_type, nullable = fields[name]
        if SWIFT_TYPES.get(swift_type) != ctype:
            problems.append("表 %s 字段 %s 类型与 iOS %s 不一致" % (table, name, swift_type))
        if bool(col.get("nullable")) != nullable:
            problems.append("表 %s 字段 %s 可空性与 iOS 不一致" % (table, name))
        if not nullable and not col.get("binary") and not col.get("required"):
            problems.append("表 %s 非可选字段 %s 缺少 required" % (table, name))
    for name in sorted(set(fields) - LOCAL_ONLY - seen):
        problems.append("表 %s 的 iOS 字段 %s 缺少清单声明" % (table, name))
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tables", nargs="*")
    parser.add_argument("--require-full-coverage", action="store_true")
    arguments = parser.parse_args()
    models = discover_models()
    from openapi_contract import manifests
    contracts = {row["table"]: row for row in manifests()}
    declared_tables = set(contracts)
    tables = arguments.tables or sorted(declared_tables)
    failed = False
    for table in sorted(tables):
        if table not in contracts:
            print("[缺失] OpenAPI 缺少实体：%s" % table)
            failed = True
            continue
        try:
            manifest = contracts[table]
            problems = check(table, manifest, models)
        except (OSError, ValueError) as error:
            problems = [str(error)]
        if problems:
            failed = True
            for problem in problems:
                print("[漂移] %s" % problem)
        else:
            print("[OK] %s 字段、类型、可空性和枚举与 iOS 一致" % table)
    missing = coverage_problems(declared_tables, models)
    print("[覆盖] %d/%d 个 iOS 同步实体有清单" % (len(declared_tables & set(models)), len(models)))
    if missing:
        prefix = "[缺失]" if arguments.require_full_coverage else "[待接入]"
        for problem in missing:
            print("%s %s" % (prefix, problem))
        if arguments.require_full_coverage:
            failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
