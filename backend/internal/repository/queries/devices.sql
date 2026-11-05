-- name: CreateDevice :exec
INSERT INTO devices (id, account_id, name, token_hash, is_owner, created_at, last_seen_at)
VALUES (sqlc.arg(id), sqlc.arg(account_id), sqlc.arg(name), sqlc.arg(token_hash), sqlc.arg(is_owner), sqlc.arg(created_at), sqlc.arg(last_seen_at));

-- name: GetDevice :one
SELECT id, account_id, name, token_hash, is_owner, created_at, last_seen_at FROM devices WHERE id = sqlc.arg(id);

-- name: GetDeviceByTokenHash :one
SELECT id, account_id, name, token_hash, is_owner, created_at, last_seen_at FROM devices WHERE token_hash = sqlc.arg(token_hash);

-- name: ListDevices :many
SELECT id, account_id, name, token_hash, is_owner, created_at, last_seen_at FROM devices
WHERE account_id = sqlc.arg(account_id) ORDER BY created_at, id;

-- name: DeleteDevice :execrows
DELETE FROM devices WHERE id = sqlc.arg(id);

-- name: TouchDevice :execrows
UPDATE devices SET last_seen_at = sqlc.arg(last_seen_at) WHERE id = sqlc.arg(id);
