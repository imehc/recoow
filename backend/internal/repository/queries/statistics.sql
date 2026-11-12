-- name: CountRecordsByTable :many
SELECT table_name, COUNT(*)::bigint AS record_count FROM row_state
WHERE account_id = sqlc.arg(account_id) AND deleted_at_ms = 0
  AND (sqlc.arg(from_ms)::bigint = 0 OR updated_at_ms >= sqlc.arg(from_ms))
  AND (sqlc.arg(to_ms)::bigint = 0 OR updated_at_ms < sqlc.arg(to_ms))
GROUP BY table_name ORDER BY table_name;

-- name: BillTotals :one
-- 金额使用整数分；时间范围为账单发生时间的左闭右开区间，退款和墓碑排除。
SELECT
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'expense'), 0)::bigint AS expense_cents,
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'income'), 0)::bigint AS income_cents,
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'storedValueUse'), 0)::bigint AS stored_value_use_cents
FROM bills WHERE tenant_id = sqlc.arg(account_id) AND tombstone_at_ms = 0
  AND COALESCE(settlement_status, 'active') <> 'refunded'
  AND (sqlc.arg(from_ms)::bigint = 0 OR occurred_at >= sqlc.arg(from_ms))
  AND (sqlc.arg(to_ms)::bigint = 0 OR occurred_at < sqlc.arg(to_ms));

-- name: BillMonthlyTotals :many
-- 仅将毫秒时间转换到用户时区；金额聚合始终使用整数分。
SELECT EXTRACT(MONTH FROM to_timestamp(occurred_at / 1000.0) AT TIME ZONE sqlc.arg(timezone)::text)::integer AS month,
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'expense'), 0)::bigint AS expense_cents,
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'income'), 0)::bigint AS income_cents,
    COALESCE(SUM(final_amount_cents) FILTER (WHERE transaction_type = 'storedValueUse'), 0)::bigint AS stored_value_use_cents
FROM bills WHERE tenant_id = sqlc.arg(account_id) AND tombstone_at_ms = 0
  AND COALESCE(settlement_status, 'active') <> 'refunded'
  AND occurred_at >= sqlc.arg(from_ms)::bigint AND occurred_at < sqlc.arg(to_ms)::bigint
GROUP BY month ORDER BY month;

-- name: BillCategoryTotals :many
SELECT COALESCE(category, '')::text AS category, COALESCE(SUM(final_amount_cents), 0)::bigint AS expense_cents, COUNT(*)::bigint AS count
FROM bills WHERE tenant_id = sqlc.arg(account_id) AND tombstone_at_ms = 0
  AND COALESCE(settlement_status, 'active') <> 'refunded' AND transaction_type = 'expense'
  AND occurred_at >= sqlc.arg(from_ms)::bigint AND occurred_at < sqlc.arg(to_ms)::bigint
GROUP BY COALESCE(category, '') ORDER BY expense_cents DESC, category;
