#!/usr/bin/env python3
"""Build Room models and the Android registry from the existing iOS contract pipeline."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from generate_sync_schema import manifests

# Physical query indexes are Android storage policy, separate from the wire contract.
INDEXES = {
    "bills": [("deleted_at", "occurred_at"), ("stored_value_account_id",)],
    "diary_entries": [("deleted_at", "occurred_at")],
    "food_entries": [("deleted_at", "occurred_at")],
    "food_day_records": [("day_start_at",)],
    "anniversaries": [("deleted_at", "occurred_at")],
    "tracks": [("deleted_at", "started_at")],
    "track_points": [("track_id", "deleted_at", "timestamp_ms")],
    "track_segments": [("track_id", "deleted_at", "started_at")],
    "media_attachments": [("owner_type", "owner_id", "deleted_at", "sort_order")],
    "media_assets": [("storage_key",), ("deleted_at", "sort_order")],
    "reminder_completions": [("reminder_id", "date_key")],
    "decision_choice_records": [("deleted_at", "selected_at")],
}
LOCAL = ["EntitySnapshot", "LocalChange", "SyncTarget", "SyncOutbox", "SyncRow", "SyncInbox",
         "SyncReceivedPage", "SyncConflict", "SyncMediaJob", "NotificationJob", "DatasetState"]


def quoted(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def camel(value):
    parts = value.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def generate():
    contracts = manifests()
    version = int((ROOT / "android/core/database/schema-version.txt").read_text())
    if version < 1:
        raise ValueError("Room schema version must be positive")
    result = {}
    names = []
    for contract in contracts:
        table = contract["table"]
        name = "".join(part.title() for part in table.split("_")) + "Entity"
        names.append(name)
        indexes = list(INDEXES.get(table, []))
        for dependency in contract["dependencies"]:
            if not any(index[0] == dependency["field"] for index in indexes):
                indexes.append((dependency["field"],))
        indexes = ", ".join("Index(value = [" + ", ".join(map(quoted, index)) + "]" +
                            (", unique = true" if table == "reminder_completions" and index == ("reminder_id", "date_key") else "") + ")" for index in indexes)
        foreign_keys = ", ".join("ForeignKey(entity = " + "".join(p.title() for p in dep["table"].split("_")) +
                                 "Entity::class, parentColumns = [\"id\"], childColumns = [" + quoted(dep["field"]) +
                                 "], deferred = true)" for dep in contract["dependencies"])
        lines = ["// Generated from iOS models and sync_contract_rules.py. Do not edit.",
                 "package imehc.recoow.core.database.generated", "", "import androidx.room.*", "",
                 f"@Entity(tableName = {quoted(table)}, indices = [{indexes}], foreignKeys = [{foreign_keys}])", f"data class {name}("]
        for field in contract["columns"]:
            kind = {"text": "String", "int": "Long", "real": "Double", "bool": "Boolean", "blob": "ByteArray"}[field["type"]]
            suffix = "?" if field.get("nullable") else ""
            default = ", defaultValue = \"0\"" if field.get("derived") and kind == "Long" else ""
            annotation = "@PrimaryKey " if field["name"] == "id" else ""
            lines.append(f'    {annotation}@ColumnInfo(name = {quoted(field["name"])}{default}) val {camel(field["name"])}: {kind}{suffix},')
        lines += ["    @ColumnInfo(name = \"sync_status\", defaultValue = \"0\") val syncStatus: Long = 0,", ")", ""]
        result[name + ".kt"] = "\n".join(lines)

    entities = ",\n        ".join(name + "::class" for name in names + LOCAL)
    result["RecoowDatabase.kt"] = f'''// Generated from the shared contract; local entities are maintained separately.
package imehc.recoow.core.database.generated

import androidx.room.Database
import androidx.room.RoomDatabase
import imehc.recoow.core.database.*

const val DATABASE_VERSION = {version}

@Database(entities = [
        {entities}
    ], version = DATABASE_VERSION, exportSchema = true)
abstract class RecoowDatabase : RoomDatabase() {{
    abstract fun records(): RecordDao
}}
'''
    compact = [{key: row[key] for key in ("table", "dataVersion", "minDataVersion", "dependencies", "columns")} for row in contracts]
    # Escaped Kotlin strings preserve JSON numbers/booleans without parsing through Double.
    result["EntityContracts.kt"] = '''// Generated from the existing iOS contract pipeline. Do not edit.
package imehc.recoow.core.database.generated

import imehc.recoow.core.database.contract.ContractRegistry

object EntityContracts {
    val registry = ContractRegistry.parse(
''' + "        " + quoted(json.dumps(compact, ensure_ascii=False, separators=(",", ":"))) + "\n    )\n}\n"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--room-schema", type=Path, help="Export compact migration SQL from a local Room JSON export")
    args = parser.parse_args()
    if args.room_schema:
        schema = json.loads(args.room_schema.read_text())["database"]
        lines = [f'-- Room schema version {schema["version"]}. Keep published versions immutable.',
                 f'PRAGMA user_version = {schema["version"]};']
        for entity in sorted(schema["entities"], key=lambda row: row["tableName"]):
            statements = [entity["createSql"]] + [index["createSql"] for index in sorted(entity.get("indices", []), key=lambda row: row["name"])]
            for statement in statements:
                if "\n" in statement or "\r" in statement:
                    raise ValueError("Migration SQL must use one statement per line")
                lines.append(statement.replace("${TABLE_NAME}", entity["tableName"]) + ";")
        for view in sorted(schema.get("views", []), key=lambda row: row["viewName"]):
            lines.append(view["createSql"].replace("${VIEW_NAME}", view["viewName"]) + ";")
        # Omit Room's identity hash so opening a historical SQL database validates its actual tables.
        content = "\n".join(lines) + "\n"
        path = args.output / f'recoow-database-v{schema["version"]}.sql'
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit("Room schema differs from migration history: " + str(path))
        else:
            args.output.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        return
    outputs = generate()
    directory = args.output / "imehc/recoow/core/database/generated"
    if args.check:
        if set(p.name for p in directory.glob("*.kt")) != set(outputs):
            raise SystemExit("Generated database file set differs")
        for name, content in outputs.items():
            if (directory / name).read_text() != content:
                raise SystemExit("Generated database differs: " + name)
        return
    directory.mkdir(parents=True, exist_ok=True)
    for stale in directory.glob("*.kt"):
        if stale.name not in outputs:
            stale.unlink()
    for name, content in outputs.items():
        path = directory / name
        if not path.exists() or path.read_text() != content:
            path.write_text(content)


if __name__ == "__main__":
    main()
