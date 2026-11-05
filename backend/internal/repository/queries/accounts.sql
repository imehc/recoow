-- name: SetAccountContext :exec
-- 账号上下文只在当前事务有效，提交或回滚后连接可安全复用。
SELECT set_config('recoow.tenant_id', sqlc.arg(account_id)::text, true);

-- name: LockRegistration :exec
SELECT pg_advisory_xact_lock(72394109);

-- name: CountAccounts :one
SELECT COUNT(*) FROM account;

-- name: CreateAccount :exec
INSERT INTO account (id, recovery_key_hash, created_at, next_version)
VALUES (sqlc.arg(id), sqlc.arg(recovery_key_hash), sqlc.arg(created_at), sqlc.arg(next_version));

-- name: InitializeSyncMetadata :exec
INSERT INTO sync_metadata (account_id, generation)
VALUES (sqlc.arg(account_id), sqlc.arg(generation)) ON CONFLICT (account_id) DO NOTHING;

-- name: GetAccount :one
SELECT id, recovery_key_hash, created_at, next_version FROM account WHERE id = sqlc.arg(id);

-- name: GetAccountByRecoveryHash :one
SELECT id, recovery_key_hash, created_at, next_version FROM account WHERE recovery_key_hash = sqlc.arg(recovery_key_hash);

-- name: RequireAccount :one
SELECT id FROM account WHERE id = sqlc.arg(id);

-- name: LockAccount :one
SELECT id FROM account WHERE id = sqlc.arg(id) FOR UPDATE;

-- name: GetSyncState :one
SELECT sync_metadata.generation, sync_metadata.min_cursor, (account.next_version - 1)::bigint AS head
FROM sync_metadata JOIN account ON account.id = sync_metadata.account_id
WHERE sync_metadata.account_id = sqlc.arg(account_id);
