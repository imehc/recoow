import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid

import psycopg
from psycopg import sql
from psycopg.conninfo import make_conninfo

from migrate_backend_sqlite import migrate


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get("RECOOW_TEST_DATABASE_URL"), "requires disposable PostgreSQL test database")
class BackendMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="recoow-migration-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.source_directory = self.directory / "source"
        self.source_directory.mkdir()
        self.source = self.source_directory / "recoow.db"
        self.media_source = self.source_directory / "media"
        self.media_target = self.directory / "target" / "media"
        self.admin = psycopg.connect(os.environ["RECOOW_TEST_DATABASE_URL"], autocommit=True)
        self.addCleanup(self.admin.close)
        self.namespace = "migration_" + uuid.uuid4().hex
        self.admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(self.namespace)))
        self.addCleanup(lambda: self.admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(self.namespace))))
        self.dsn = make_conninfo(os.environ["RECOOW_TEST_DATABASE_URL"], options=f"-c search_path={self.namespace}")
        with psycopg.connect(self.dsn) as target:
            for path in sorted((ROOT / "backend/migrations").glob("*.sql")):
                target.execute(path.read_text())
        content = b"migration-media-preserved"
        checksum = hashlib.sha256(content).hexdigest()
        (self.media_source / "tenant-a").mkdir(parents=True)
        (self.media_source / "tenant-a" / checksum).write_bytes(content)
        with sqlite3.connect(self.source) as source:
            source.executescript("""
                CREATE TABLE account (id TEXT PRIMARY KEY, recovery_key_hash TEXT, created_at TEXT, next_version INTEGER);
                CREATE TABLE devices (id TEXT, account_id TEXT, name TEXT, token_hash TEXT, is_owner INTEGER, created_at TEXT, last_seen_at TEXT);
                CREATE TABLE sync_metadata (account_id TEXT, generation TEXT, min_cursor INTEGER);
                CREATE TABLE row_state (account_id TEXT, table_name TEXT, id TEXT, snapshot TEXT, updated_at_ms INTEGER, device_id TEXT, deleted_at_ms INTEGER, version INTEGER, data_version INTEGER);
                CREATE TABLE change_log (account_id TEXT, version INTEGER, table_name TEXT, id TEXT, operation TEXT, updated_at_ms INTEGER, device_id TEXT, deleted INTEGER, deleted_at_ms INTEGER, snapshot TEXT, data_version INTEGER);
                CREATE TABLE mutation_receipts (account_id TEXT, mutation_id TEXT, request_hash TEXT, result_json TEXT);
                CREATE TABLE media_objects (account_id TEXT, key TEXT, checksum TEXT, size INTEGER, content_type TEXT, created_at TEXT);
            """)
            source.execute("INSERT INTO account VALUES ('tenant-a','recovery-hash','2026-11-05T00:00:00Z',3)")
            source.execute("INSERT INTO devices VALUES ('device-a','tenant-a','phone','token-hash',1,'2026-11-05T00:00:00Z','2026-11-05T00:00:00Z')")
            source.execute("INSERT INTO sync_metadata VALUES ('tenant-a','original-generation',0)")
            snapshot = json.dumps({"id": "tag-a", "name": "O'Reilly", "note": None, "created_at": 1000, "updated_at": 1000, "device_id": "device-a", "deleted_at": None, "future_field": {"number": 9007199254740993}})
            source.execute("INSERT INTO row_state VALUES ('tenant-a','diary_tags','tag-a',?,1000,'device-a',0,1,1)", (snapshot,))
            source.execute("INSERT INTO change_log VALUES ('tenant-a',1,'diary_tags','tag-a','update',1000,'device-a',0,0,?,1)", (snapshot,))
            source.execute("INSERT INTO row_state VALUES ('tenant-a','diary_tags','deleted-tag',NULL,2000,'device-a',2000,2,1)")
            source.execute("INSERT INTO change_log VALUES ('tenant-a',2,'diary_tags','deleted-tag','delete',2000,'device-a',1,2000,NULL,1)")
            source.execute("INSERT INTO mutation_receipts VALUES ('tenant-a','mutation-a','request-hash',?)", (json.dumps({"version": 1, "status": "applied"}),))
            source.execute("INSERT INTO media_objects VALUES ('tenant-a',?,?,?,'image/jpeg','2026-11-05T00:00:00Z')", ("sync/" + checksum, checksum, len(content)))

    def test_preserves_credentials_revisions_tombstones_unknown_fields_and_media(self):
        before = self.source.read_bytes()
        counts = migrate(self.source, self.dsn, self.media_source, self.media_target)
        self.assertEqual(counts["diary_tags"], 2)
        self.assertEqual(self.source.read_bytes(), before)
        with psycopg.connect(self.dsn) as target:
            self.assertEqual(target.execute("SELECT generation FROM sync_metadata").fetchone()[0], "original-generation")
            self.assertEqual(target.execute("SELECT recovery_key_hash,next_version FROM account").fetchone(), ("recovery-hash", 3))
            self.assertEqual(target.execute("SELECT token_hash FROM devices").fetchone()[0], "token-hash")
            self.assertEqual(target.execute("SELECT name,extras FROM diary_tags WHERE id='tag-a'").fetchone(), ("O'Reilly", {"future_field": {"number": 9007199254740993}}))
            self.assertEqual(target.execute("SELECT snapshot,deleted_at_ms,version FROM row_state WHERE id='deleted-tag'").fetchone(), (None, 2000, 2))
            self.assertEqual(target.execute("SELECT COUNT(*) FROM mutation_receipts").fetchone()[0], 1)
        self.assertEqual(next((self.media_target / "tenant-a").iterdir()).read_bytes(), b"migration-media-preserved")
        with self.assertRaises(ValueError):
            migrate(self.source, self.dsn, self.media_source, self.directory / "second-media")
        self.assertFalse((self.directory / "second-media").exists())

    def test_unknown_entity_rolls_back_entire_target(self):
        with sqlite3.connect(self.source) as source:
            source.execute("UPDATE row_state SET table_name='future_table'")
        with self.assertRaises(ValueError):
            migrate(self.source, self.dsn, self.media_source, self.media_target)
        with psycopg.connect(self.dsn) as target:
            self.assertEqual(target.execute("SELECT COUNT(*) FROM account").fetchone()[0], 0)
        self.assertFalse(self.media_target.exists())

    def test_corrupt_media_is_rejected_before_import(self):
        next((self.media_source / "tenant-a").iterdir()).write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            migrate(self.source, self.dsn, self.media_source, self.media_target)
        with psycopg.connect(self.dsn) as target:
            self.assertEqual(target.execute("SELECT COUNT(*) FROM account").fetchone()[0], 0)
