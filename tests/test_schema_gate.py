import copy
import json
import unittest

import check_schema_gate as gate
from openapi_contract import manifests


class SchemaGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.models = gate.discover_models()
        cls.manifests = {row["table"]: row for row in manifests()}

    def check_changes(self, table, change):
        manifest = copy.deepcopy(self.manifests[table])
        change(manifest)
        return gate.check(table, manifest, self.models)

    def test_repository_manifests_match_models(self):
        for table, manifest in self.manifests.items():
            with self.subTest(table=table):
                self.assertEqual(gate.check(table, manifest, self.models), [])

    def test_missing_ios_field_is_reported(self):
        problems = self.check_changes(
            "reminders",
            lambda manifest: manifest.update(columns=[
                column for column in manifest["columns"] if column["name"] != "is_enabled"
            ]),
        )
        self.assertTrue(any("is_enabled" in problem and "缺少清单" in problem for problem in problems))

    def test_enum_drift_is_reported(self):
        def change(manifest):
            for column in manifest["columns"]:
                if column["name"] == "settlement_status":
                    column["enumValues"] = ["pending", "settled"]

        problems = self.check_changes("bills", change)
        self.assertTrue(any("枚举值与 iOS 不一致" in problem for problem in problems))

    def test_type_nullable_and_required_are_checked(self):
        def change(manifest):
            for column in manifest["columns"]:
                if column["name"] == "balance_cents":
                    column.update(type="text", nullable=True, required=False)

        problems = self.check_changes("stored_value_accounts", change)
        self.assertTrue(any("类型与 iOS" in problem for problem in problems))
        self.assertTrue(any("可空性与 iOS" in problem for problem in problems))
        self.assertTrue(any("缺少 required" in problem for problem in problems))

    def test_local_sync_status_is_not_a_shared_field(self):
        problems = self.check_changes(
            "bills",
            lambda manifest: manifest["columns"].append({
                "name": "sync_status", "type": "int", "format": "int", "required": True,
            }),
        )
        self.assertTrue(any("本机字段 sync_status" in problem for problem in problems))

    def test_binary_fields_are_not_skipped(self):
        def change(manifest):
            for column in manifest["columns"]:
                if column["name"] == "image_data":
                    column["type"] = "text"

        self.assertTrue(any("image_data 类型" in problem for problem in self.check_changes("bills", change)))

    def test_table_name_and_version_are_checked(self):
        problems = self.check_changes("bills", lambda manifest: manifest.update(table="other", manifestVersion=0))
        self.assertTrue(any("table 与文件名不一致" in problem for problem in problems))
        self.assertTrue(any("manifestVersion" in problem for problem in problems))

    def test_unregistered_enum_source_is_reported(self):
        def change(manifest):
            for column in manifest["columns"]:
                if column["name"] == "note":
                    column.update(format="enum", enumValues=["example"])

        self.assertTrue(any("枚举来源映射" in problem for problem in self.check_changes("bills", change)))

    def test_full_coverage_does_not_ignore_unregistered_entities(self):
        self.assertIn("diary_entries", self.models)
        self.assertNotIn("change_log", self.models)
        self.assertNotIn("check_in_completion_records", self.models)
        problems = gate.coverage_problems({"bills"}, self.models)
        self.assertIn("缺少实体清单：diary_entries", problems)
        self.assertEqual(gate.coverage_problems(self.models, self.models), [])


if __name__ == "__main__":
    unittest.main()
