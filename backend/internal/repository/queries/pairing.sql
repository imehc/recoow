-- name: DeleteExpiredPairingCodes :exec
DELETE FROM device_pairing_codes WHERE expires_at <= now();

-- name: IssuePairingCode :exec
INSERT INTO device_pairing_codes (code_hash, account_id, expires_at)
VALUES (sqlc.arg(code_hash), sqlc.arg(account_id), sqlc.arg(expires_at));

-- name: ConsumePairingCode :one
-- 有效期检查和一次性消费是同一条语句，多个实例也只能成功一次。
DELETE FROM device_pairing_codes WHERE code_hash = sqlc.arg(code_hash) AND expires_at > now() RETURNING account_id;
