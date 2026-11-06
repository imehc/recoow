-- name: CreateUser :one
INSERT INTO users (id, account_id, username, password_hash)
VALUES (sqlc.arg(id), sqlc.arg(account_id), sqlc.arg(username), sqlc.arg(password_hash))
RETURNING id, account_id, username, password_hash, disabled;

-- name: UserByUsername :one
SELECT id, account_id, username, password_hash, disabled FROM users WHERE username = sqlc.arg(username);

-- name: UserByAccount :one
SELECT id, account_id, username, password_hash, disabled FROM users WHERE account_id = sqlc.arg(account_id);

-- name: SetPassword :execrows
UPDATE users SET password_hash = sqlc.arg(password_hash) WHERE id = sqlc.arg(id);

-- name: RevokeUserSessions :exec
-- 删除会话设备会级联删除会话；长期设备凭据不受密码更新影响。
DELETE FROM devices WHERE id IN (SELECT device_id FROM sessions WHERE user_id = sqlc.arg(user_id));

-- name: CreateSession :execrows
-- 和密码变更使用相同的用户行锁，防止旧密码验证后在吊销之后创建新会话。
INSERT INTO sessions (id, user_id, device_id, token_hash, expires_at, access)
SELECT sqlc.arg(id)::text, users.id, sqlc.arg(device_id)::text, sqlc.arg(token_hash)::text, sqlc.arg(expires_at)::timestamptz, sqlc.arg(access)::text
FROM users WHERE users.id = sqlc.arg(user_id) AND users.password_hash = sqlc.arg(password_hash) AND NOT users.disabled
FOR SHARE;

-- name: SessionByToken :one
SELECT sessions.id, sessions.user_id, sessions.device_id, sessions.token_hash, sessions.expires_at, sessions.access
FROM sessions JOIN users ON users.id = sessions.user_id
WHERE sessions.token_hash = sqlc.arg(token_hash) AND sessions.expires_at > now() AND NOT users.disabled;

-- name: DeleteSession :exec
DELETE FROM devices WHERE id IN (SELECT sessions.device_id FROM sessions WHERE sessions.token_hash = sqlc.arg(token_hash));

-- name: CleanupExpiredSessions :execrows
DELETE FROM devices WHERE id IN (
    SELECT device_id FROM sessions WHERE expires_at <= sqlc.arg(expires_at)
    ORDER BY expires_at, id LIMIT sqlc.arg(batch_size)::integer
);
