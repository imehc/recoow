import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

import check_schema_gate as gate
import generate_sync_registry as registry
import generate_sync_schema as generator


def sql_blocks(path):
    source = path.read_text()
    return [multiline or single for multiline, single in re.findall(
        r'try db\.execute\(sql:\s*(?:"""(.*?)"""|"([^"\n]+)")', source, re.S
    )]


class GeneratedSyncTests(unittest.TestCase):
    def test_deterministic_complete_contracts(self):
        manifests = generator.manifests()
        self.assertEqual(manifests, generator.manifests())
        self.assertEqual({manifest["table"] for manifest in manifests}, set(gate.discover_models()))
        self.assertEqual(registry.render(), (gate.ROOT / registry.DESTINATION).read_text())

    def test_dependency_order_and_build_inputs(self):
        ordered = registry.ordered_manifests()
        positions = {manifest["table"]: index for index, manifest in enumerate(ordered)}
        for manifest in ordered:
            for dependency in manifest.get("dependencies", []):
                self.assertLess(positions[dependency["table"]], positions[manifest["table"]])
        self.assertEqual(generator.input_file_list(), (gate.ROOT / "scripts/sync-models.xcfilelist").read_text())
        paths = {
            Path(line.replace("$(SRCROOT)", str(gate.ROOT / "ios"))).resolve()
            for line in generator.input_file_list().splitlines()
        }
        self.assertTrue(set(gate.discover_models().values()).issubset(paths))

    def test_generation_from_a_clean_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(gate.ROOT / "scripts/generate_sync_schema.py"), "--output", directory]
            subprocess.run(command, check=True, capture_output=True)
            subprocess.run(command + ["--check"], check=True, capture_output=True)
            self.assertEqual(len(list(Path(directory).glob("*.json"))), len(gate.discover_models()))

    @unittest.skipUnless(sys.platform == "darwin" and shutil.which("xcrun"), "requires the macOS Swift compiler")
    def test_swift_balance_effects(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "sync-value-checks"
            result = subprocess.run([
                "xcrun", "swiftc", "-module-cache-path", str(Path(directory) / "modules"),
                "-o", str(binary), str(gate.IOS / "Core/Sync/StoredValueBalanceEffects.swift"),
                str(gate.ROOT / "scripts/SyncValueChecks.swift"),
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            subprocess.run([str(binary)], check=True, capture_output=True)


class IOSSyncDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.database = sqlite3.connect(":memory:")
        self.database.execute("PRAGMA foreign_keys = ON")
        self.manifests = {manifest["table"]: manifest for manifest in generator.manifests()}
        self.original_columns = {}
        for table, manifest in self.manifests.items():
            columns = ['"sync_status" INTEGER NOT NULL DEFAULT 0']
            original = ["sync_status"]
            values = {"sync_status": 1}
            for column in manifest["columns"]:
                name = column["name"]
                if table == "bills" and name == "stored_value_effects":
                    continue
                datatype = {"text": "TEXT", "int": "INTEGER", "real": "REAL", "bool": "INTEGER", "blob": "BLOB"}[column["type"]]
                suffix = " PRIMARY KEY" if name == "id" else ""
                columns.append(f'"{name}" {datatype}{suffix}')
                original.append(name)
                values[name] = None if column.get("nullable") else ("fixture" if datatype == "TEXT" else 0)
                if column.get("binary"):
                    values[name] = b"unchanged-media-bytes"
            self.database.execute(f'CREATE TABLE "{table}" ({", ".join(columns)})')
            if table in {"stored_value_adjustments", "reminder_completions"}:
                continue
            values.update(id=table + "-id", created_at=10, updated_at=20, device_id="old-device")
            if table == "stored_value_accounts":
                values["balance_cents"] = 7300
            elif table == "bills":
                values.update(stored_value_account_id="stored_value_accounts-id", transaction_type="expense",
                              original_amount_cents=1500, final_amount_cents=1000, settlement_status="active",
                              category="groupBuy", group_buy_valid_until=1)
            elif table == "reminders":
                values.update(completion_records='[{"dateKey":"2026-09-07","completedAt":15,"kindRawValue":"checkIn","note":"retain"}]',
                              completed_date_keys="2026-09-07,2026-09-08", schedule_kind="continuousDays", continuous_days=7)
            names = ", ".join(f'"{name}"' for name in values)
            self.database.execute(f'INSERT INTO "{table}" ({names}) VALUES ({", ".join("?" for _ in values)})', tuple(values.values()))
            self.original_columns[table] = original
        self.database.execute("CREATE TABLE change_log (id INTEGER PRIMARY KEY, payload_json TEXT, synced_at_ms INTEGER)")
        self.database.execute("INSERT INTO change_log VALUES (1, '{\"original\":true}', 20)")
        self.before = self.business_rows()
        self.migration = sql_blocks(gate.IOS / "Core/Database/Migrations/V34FullSyncSchema.swift")
        for statement in self.migration:
            if "?" not in statement:
                self.database.executescript(statement)

    def tearDown(self):
        self.database.close()

    def business_rows(self):
        return {
            table: self.database.execute(f'SELECT {", ".join(columns)} FROM "{table}" ORDER BY id').fetchall()
            for table, columns in self.original_columns.items()
        }

    def target(self):
        self.database.execute("INSERT INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark) VALUES ('scope', 'connection', 'account', 'generation', 0)")

    def enqueue(self, mutation, table, identifier, status="pending"):
        self.database.execute("""
            INSERT INTO sync_outbox (scope, mutation_id, table_name, entity_id, operation, payload_json, base_version, status)
            VALUES ('scope', ?, ?, ?, 'update', '{}', 0, ?)
        """, (mutation, table, identifier, status))

    def test_migration_preserves_all_existing_business_columns_and_media(self):
        self.assertEqual(self.before, self.business_rows())
        payload, synced_at, mutation = self.database.execute("SELECT payload_json, synced_at_ms, mutation_id FROM change_log").fetchone()
        self.assertEqual((payload, synced_at), ('{"original":true}', 20))
        self.assertEqual(len(mutation), 32)
        self.assertEqual(self.database.execute("SELECT COUNT(*) FROM sync_targets").fetchone()[0], 0)
        self.assertEqual(self.database.execute("SELECT amount_cents FROM stored_value_adjustments").fetchone()[0], 5800)

    def test_initial_balance_projection_does_not_change_legacy_balance(self):
        statement = sql_blocks(gate.IOS / "Core/Sync/SyncAggregateProjection.swift")[0]
        self.database.execute(statement, (None, None))
        self.assertEqual(self.before, self.business_rows())

    def test_conflicted_balance_stays_local_until_explicit_resolution(self):
        self.target()
        self.enqueue("conflict", "stored_value_accounts", "stored_value_accounts-id", "conflict")
        self.database.execute("UPDATE stored_value_accounts SET balance_cents = 9999")
        statement = sql_blocks(gate.IOS / "Core/Sync/SyncAggregateProjection.swift")[0]
        self.database.execute(statement, ("scope", "scope"))
        self.assertEqual(self.database.execute("SELECT balance_cents FROM stored_value_accounts").fetchone()[0], 9999)
        self.database.execute("UPDATE sync_outbox SET status = 'resolved_remote'")
        self.database.execute(statement, ("scope", "scope"))
        self.assertEqual(self.database.execute("SELECT balance_cents FROM stored_value_accounts").fetchone()[0], 7300)

    def test_outbox_sends_only_one_revision_per_row_and_blocks_conflicts(self):
        self.target()
        self.enqueue("first", "bills", "bill")
        self.enqueue("second", "bills", "bill")
        self.enqueue("conflict", "diary_entries", "entry", "conflict")
        self.enqueue("blocked", "diary_entries", "entry")
        self.enqueue("parent", "stored_value_accounts", "account")
        source = (gate.IOS / "Core/Sync/FullSyncRepository.swift").read_text()
        statement = re.search(r'(SELECT \* FROM sync_outbox AS candidate.*?)"""', source, re.S).group(1)
        ordering = "CASE table_name " + " ".join(f"WHEN '{manifest['table']}' THEN {index}" for index, manifest in enumerate(registry.ordered_manifests())) + " ELSE 999 END"
        statement = statement.replace(r"\(SyncEntityCatalog.sqlOrder)", ordering)
        self.database.row_factory = sqlite3.Row
        rows = self.database.execute(statement, ("scope", 40)).fetchall()
        self.assertEqual([row["mutation_id"] for row in rows], ["parent", "first"])

    def test_unapplied_inbox_blocks_applied_cursor(self):
        self.target()
        self.database.execute("UPDATE sync_targets SET received_cursor = 10")
        self.database.execute("INSERT INTO sync_inbox (scope, version, table_name, entity_id, envelope_json) VALUES ('scope', 4, 'future_entity', 'unknown', '{}')")
        statements = sql_blocks(gate.IOS / "Core/Sync/SyncInbox.swift")
        statement = next(value for value in statements if "SET applied_cursor" in value)
        self.database.execute(statement, ("scope", "scope"))
        self.assertEqual(self.database.execute("SELECT applied_cursor FROM sync_targets").fetchone()[0], 3)
        self.database.execute("UPDATE sync_inbox SET applied = 1")
        self.database.execute(statement, ("scope", "scope"))
        self.assertEqual(self.database.execute("SELECT applied_cursor FROM sync_targets").fetchone()[0], 10)

    def test_media_materialization_does_not_overwrite_files_in_a_transaction(self):
        source = (gate.IOS / "Core/Sync/SyncMediaTransfer.swift").read_text()
        installation = source.split("private static func install(")[1].split("func transfer(")[0]
        self.assertNotIn("MediaAssetObjectStore.shared.write", installation)
        self.assertIn("storage_key = ?", installation)


if __name__ == "__main__":
    unittest.main()
