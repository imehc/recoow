#!/usr/bin/env python3
"""把已停止服务的 SQLite 数据副本迁入已建表的空 PostgreSQL 库，保留源文件。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

from openapi_contract import manifests


SYSTEM_TABLES = ("account", "devices", "sync_metadata", "change_log", "mutation_receipts", "media_objects")


def copy_table(source, target, table):
    columns = [row[1] for row in source.execute(f'PRAGMA table_info("{table}")')]
    if not columns:
        raise ValueError(f"源库缺少 {table}，请先使用最后一个 SQLite 后端版本完成升级")
    statement = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
        sql.Identifier(table), sql.SQL(",").join(map(sql.Identifier, columns)),
        sql.SQL(",").join(sql.Placeholder() for _ in columns),
    )
    count = 0
    for row in source.execute(f'SELECT * FROM "{table}"'):
        values = list(row)
        if table == "change_log" and "snapshot" in columns:
            index = columns.index("snapshot")
            if values[index] is not None:
                values[index] = Jsonb(json.loads(values[index]))
        target.execute(statement, values)
        count += 1
    return count


def copy_business_rows(source, target, contracts):
    counts = {table: 0 for table in contracts}
    for row in source.execute("SELECT * FROM row_state ORDER BY account_id, table_name, id"):
        table = row["table_name"]
        if table not in contracts:
            raise ValueError(f"源库包含未定义的实体 {table}，不能跳过数据")
        snapshot = json.loads(row["snapshot"]) if row["snapshot"] is not None else None
        if snapshot is not None and not isinstance(snapshot, dict):
            raise ValueError(f"{table}/{row['id']} 的快照不是对象")
        extras = dict(snapshot or {})
        names = ["tenant_id", "id", "extras", "present_fields", "server_updated_at_ms", "sync_device_id", "tombstone_at_ms", "server_version", "data_version"]
        present = [] if snapshot is not None else None
        values = [row["account_id"], row["id"], None, present, row["updated_at_ms"], row["device_id"], row["deleted_at_ms"], row["version"], row["data_version"]]
        for column in contracts[table]["columns"]:
            name = column["name"]
            if column.get("binary") or name == "server_version":
                continue
            if snapshot is not None and name in snapshot:
                present.append(name)
            extras.pop(name, None)
            if name != "id":
                names.append(name)
                values.append((snapshot or {}).get(name))
        values[2] = Jsonb(extras)
        target.execute(sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            sql.Identifier(table), sql.SQL(",").join(map(sql.Identifier, names)),
            sql.SQL(",").join(sql.Placeholder() for _ in names),
        ), values)
        counts[table] += 1
    return counts


def verify_media(source, source_media):
    for row in source.execute("SELECT account_id, checksum, size FROM media_objects"):
        # 旧库的账号与 checksum 也是路径组成部分，验证它们不能逃逸媒体目录。
        path = (source_media / row["account_id"] / row["checksum"]).resolve()
        if not path.is_relative_to(source_media.resolve()) or not path.is_file():
            raise ValueError("媒体文件缺失或路径非法")
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                size += len(block)
                digest.update(block)
        if size != row["size"] or digest.hexdigest() != row["checksum"]:
            raise ValueError("媒体文件长度或 SHA-256 不匹配")


def migrate(source_path, database_url, source_media, target_media):
    source_path, source_media, target_media = map(lambda path: Path(path).resolve(), (source_path, source_media, target_media))
    if target_media.exists():
        raise ValueError("媒体目标必须不存在，不能覆盖已有目录")
    if target_media.is_relative_to(source_path.parent) or source_path.parent.is_relative_to(target_media):
        raise ValueError("目标目录不能与源数据目录重叠")
    target_media.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".recoow-migration-", dir=target_media.parent))
    source = None
    try:
        source = sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True)
        source.row_factory = sqlite3.Row
        source.execute("BEGIN")
        required = set(SYSTEM_TABLES) | {"row_state"}
        existing = {row[0] for row in source.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        if not required <= existing:
            raise ValueError("源库不是完整的多账号同步库，请先升级旧服务并重新导出")
        verify_media(source, source_media)
        if source_media.exists():
            shutil.copytree(source_media, staging, dirs_exist_ok=True)
        contracts = {manifest["table"]: manifest for manifest in manifests()}
        with psycopg.connect(database_url) as target:
            # 在目标库持有注册锁并锁住全部数据表，阻止启动服务时并发写入。
            target.execute("SELECT pg_advisory_xact_lock(72394109)")
            tables = [*SYSTEM_TABLES, *contracts, "users", "sessions", "device_pairing_codes"]
            target.execute(sql.SQL("LOCK TABLE {} IN ACCESS EXCLUSIVE MODE").format(sql.SQL(",").join(map(sql.Identifier, tables))))
            for table in tables:
                if target.execute(sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table))).fetchone()[0]:
                    raise ValueError(f"目标表 {table} 非空，拒绝覆盖")
            counts = {}
            for table in SYSTEM_TABLES:
                counts[table] = copy_table(source, target, table)
            counts.update(copy_business_rows(source, target, contracts))
            target.execute("SET CONSTRAINTS ALL IMMEDIATE")
            for table, count in counts.items():
                actual = target.execute(sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table))).fetchone()[0]
                if count != actual:
                    raise ValueError(f"{table} 迁移数量不一致")
            # 重构后的同步视图必须逐条还原相同快照、墓碑和版本。
            for row in source.execute("SELECT * FROM row_state"):
                actual = target.execute("SELECT snapshot, updated_at_ms, device_id, deleted_at_ms, version, data_version FROM row_state WHERE account_id=%s AND table_name=%s AND id=%s", (row["account_id"], row["table_name"], row["id"])).fetchone()
                expected = tuple(row[key] for key in ("updated_at_ms", "device_id", "deleted_at_ms", "version", "data_version"))
                if actual is None or actual[1:] != expected or (json.loads(actual[0]) if actual[0] is not None else None) != (json.loads(row["snapshot"]) if row["snapshot"] is not None else None):
                    raise ValueError("同步状态或快照还原不一致")
            staging.rename(target_media)
        return counts
    finally:
        if source is not None:
            source.close()
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--media-source", required=True, type=Path)
    parser.add_argument("--media-target", required=True, type=Path)
    args = parser.parse_args()
    database_url = os.environ.get("RECOOW_MIGRATION_DATABASE_URL")
    if not database_url:
        parser.error("需要 RECOOW_MIGRATION_DATABASE_URL（只能使用独立空目标库）")
    try:
        counts = migrate(args.source, database_url, args.media_source, args.media_target)
    except (ValueError, OSError, sqlite3.Error, psycopg.Error) as error:
        raise SystemExit(f"迁移失败，源文件保留；请检查目标库及媒体目录：{type(error).__name__}: {error}") from error
    print(json.dumps({"status": "migrated", "counts": counts}, ensure_ascii=False))


if __name__ == "__main__":
    main()
