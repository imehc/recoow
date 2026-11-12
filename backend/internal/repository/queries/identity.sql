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

-- name: SetPasswordIfCurrent :execrows
UPDATE users SET password_hash = sqlc.arg(password_hash)
WHERE id = sqlc.arg(id) AND password_hash = sqlc.arg(expected_hash) AND NOT disabled;

-- name: CreateLoginDevice :execrows
-- 与改密共用用户行锁，旧密码验证结果不能在改密后签发设备凭据。
INSERT INTO devices (id, account_id, name, token_hash, is_owner, created_at, last_seen_at, profile)
SELECT sqlc.arg(id)::text AS id, users.account_id, sqlc.arg(name)::text AS name, sqlc.arg(token_hash)::text AS token_hash, 0::bigint AS is_owner,
    sqlc.arg(created_at)::text AS created_at, sqlc.arg(last_seen_at)::text AS last_seen_at, COALESCE(sqlc.narg(profile), 'null'::jsonb) AS profile
FROM users WHERE users.id = sqlc.arg(user_id) AND users.account_id = sqlc.arg(account_id)
    AND users.password_hash = sqlc.arg(password_hash) AND NOT users.disabled
FOR SHARE;

-- name: RevokeUserSessions :exec
-- 删除会话设备会级联删除会话；长期设备凭据不受密码更新影响。
DELETE FROM devices WHERE id IN (SELECT sessions.device_id FROM sessions JOIN devices ON devices.id = sessions.device_id JOIN users ON users.account_id = devices.account_id WHERE users.id = sqlc.arg(user_id));

-- name: CreateSession :execrows
-- 和密码变更使用相同的用户行锁，防止旧密码验证后在吊销之后创建新会话。
INSERT INTO sessions (id, user_id, device_id, token_hash, expires_at, access)
SELECT sqlc.arg(id)::text, users.id, sqlc.arg(device_id)::text, sqlc.arg(token_hash)::text, sqlc.arg(expires_at)::timestamptz, sqlc.arg(access)::text
FROM users WHERE users.id = sqlc.arg(user_id) AND users.password_hash = sqlc.arg(password_hash) AND NOT users.disabled
FOR SHARE;

-- name: SessionByToken :one
SELECT sessions.id, COALESCE(sessions.user_id, '')::text AS user_id, sessions.device_id, sessions.token_hash, sessions.expires_at, sessions.access
FROM sessions JOIN devices ON devices.id = sessions.device_id LEFT JOIN users ON users.account_id = devices.account_id
WHERE sessions.token_hash = sqlc.arg(token_hash) AND sessions.expires_at > now()
    AND (users.id IS NULL OR NOT users.disabled);


-- name: DeleteSession :exec
DELETE FROM devices WHERE id IN (SELECT sessions.device_id FROM sessions WHERE sessions.token_hash = sqlc.arg(token_hash));

-- name: CleanupExpiredSessions :execrows
DELETE FROM devices WHERE id IN (
    SELECT device_id FROM sessions WHERE expires_at <= sqlc.arg(expires_at)
    ORDER BY expires_at, id LIMIT sqlc.arg(batch_size)::integer
);

-- name: UserByAccountForShare :one
-- 已绑定账号与改密共用用户行锁；未绑定账号无需创建密码身份。
SELECT id, account_id, username, password_hash, disabled FROM users WHERE account_id = sqlc.arg(account_id) FOR SHARE;

-- name: CreatePairingSession :exec
INSERT INTO sessions (id, user_id, device_id, token_hash, expires_at, access, auth_method)
VALUES (sqlc.arg(id), sqlc.narg(user_id), sqlc.arg(device_id), sqlc.arg(token_hash), sqlc.arg(expires_at), sqlc.arg(access), 'pairing');

-- name: RequireSessionDevice :one
-- 锁定设备行，使删除设备与本次提交按事务先后生效。
SELECT devices.id FROM devices
WHERE devices.account_id = sqlc.arg(account_id) AND EXISTS (
    SELECT 1 FROM sessions WHERE sessions.device_id = devices.id AND sessions.id = sqlc.arg(session_id) AND sessions.expires_at > now()
) AND NOT EXISTS (SELECT 1 FROM users WHERE users.account_id = devices.account_id AND users.disabled)
FOR SHARE OF devices;
