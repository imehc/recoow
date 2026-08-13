import Foundation
import GRDB

/// V32：历史储值充值余额回填——充值按原价（面值）入账，而非实付金额。
/// 仅对未作废的储值充值账单，将余额补上「原价 - 实付」的差额。
enum V32StoredValueRechargeBalanceBackfillSchema {
    nonisolated static func register(in migrator: inout DatabaseMigrator) {
        migrator.registerMigration("v32_stored_value_recharge_original_balance_backfill") { db in
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            var encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]

            let accounts = try StoredValueAccount
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)

            for var account in accounts {
                let correctionCents = try Int64.fetchOne(
                    db,
                    sql: """
                        SELECT COALESCE(SUM(original_amount_cents - final_amount_cents), 0)
                        FROM bills
                        WHERE stored_value_account_id = ?
                          AND deleted_at IS NULL
                          AND transaction_type = 'expense'
                          AND settlement_status != 'refunded'
                          AND NOT (
                              settlement_status = 'active'
                              AND category = 'groupBuy'
                              AND group_buy_valid_until IS NOT NULL
                              AND group_buy_valid_until < ?
                          )
                        """,
                    arguments: [account.id, now]
                ) ?? 0

                guard correctionCents > 0 else { continue }

                account.balanceCents += correctionCents
                account.updatedAt = now
                account.syncStatus = .pending
                try account.update(db)

                let payload = try encoder.encode(account)
                try ChangeRecord(
                    id: nil,
                    entityTable: StoredValueAccount.databaseTableName,
                    entityID: account.id,
                    operation: .update,
                    payloadJSON: String(decoding: payload, as: UTF8.self),
                    clientTimestampMilliseconds: now,
                    attemptCount: 0,
                    lastError: nil,
                    syncedAtMilliseconds: nil
                ).insert(db)
            }
        }
    }
}
