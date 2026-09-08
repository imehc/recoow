import Foundation
import GRDB

nonisolated enum SyncAggregateProjection {
    static func refresh(db: Database, scope: String? = nil) throws {
        try refreshBalances(db: db, scope: scope)
        try refreshCompletions(db: db, scope: scope)
    }

    static func refreshBalances(db: Database, scope: String? = nil) throws {
        try db.execute(sql: """
            UPDATE stored_value_accounts SET balance_cents = COALESCE((
                SELECT SUM(amount_cents) FROM stored_value_adjustments
                WHERE account_id = stored_value_accounts.id AND deleted_at IS NULL
            ), 0) + COALESCE((
                SELECT SUM(effect.value) FROM bills, json_each(COALESCE(bills.stored_value_effects, '{}')) AS effect
                WHERE effect.key = stored_value_accounts.id
            ), 0)
            WHERE ? IS NULL OR NOT EXISTS (
                SELECT 1 FROM sync_outbox WHERE scope = ? AND table_name = 'stored_value_accounts'
                    AND entity_id = stored_value_accounts.id AND status IN ('pending', 'conflict')
            )
            """, arguments: [scope, scope])
        if try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM stored_value_accounts WHERE typeof(balance_cents) != 'integer'") != 0 {
            throw DatabaseError(message: "储值余额超出整数范围，未保存修改")
        }
    }

    static func refreshCompletions(db: Database, scope: String? = nil) throws {
        for reminder in try Row.fetchAll(db, sql: """
            SELECT * FROM reminders WHERE ? IS NULL OR NOT EXISTS (
                SELECT 1 FROM sync_outbox WHERE scope = ? AND table_name = 'reminders'
                    AND entity_id = reminders.id AND status IN ('pending', 'conflict')
            )
            """, arguments: [scope, scope]) {
            let identifier: String = reminder["id"]
            guard try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM reminder_completions WHERE reminder_id = ?)", arguments: [identifier]) == true else { continue }
            let rows = try Row.fetchAll(db, sql: """
                SELECT date_key, completed_at, kind, note FROM reminder_completions
                WHERE reminder_id = ? AND deleted_at IS NULL ORDER BY date_key
                """, arguments: [identifier])
            let details: [[String: Any]] = rows.map { row in
                var detail: [String: Any] = ["dateKey": row["date_key"] as String, "completedAt": row["completed_at"] as Int64,
                                             "kindRawValue": row["kind"] as String]
                detail["note"] = row["note"] as String?
                return detail
            }
            let json = String(decoding: try JSONSerialization.data(withJSONObject: details, options: [.sortedKeys]), as: UTF8.self)
            let keys = rows.map { $0["date_key"] as String }.joined(separator: ",")
            let schedule: String = reminder["schedule_kind"]
            let targetDays: Int?
            if schedule == "single" {
                targetDays = 1
            } else if schedule == "dailyGoal" {
                targetDays = nil
            } else if schedule == "dateRange" {
                if let end: Int64 = reminder["end_at"] {
                    let start: Int64 = reminder["scheduled_at"]
                    let calendar = Calendar.current
                    let startDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(start) / 1000))
                    let endDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(end) / 1000))
                    targetDays = max(1, (calendar.dateComponents([.day], from: startDay, to: endDay).day ?? 0) + 1)
                } else {
                    targetDays = nil
                }
            } else {
                targetDays = max(1, reminder["continuous_days"] as Int)
            }
            let importedDays: Int = reminder["imported_completed_days"] ?? 0
            let endedEarly = schedule == "continuousDays" && (reminder["ended_early_at"] as Int64?) != nil
            let complete = targetDays.map { rows.count + importedDays >= $0 } ?? false
            let completedAt = complete && !endedEarly ? ((reminder["completed_at"] as Int64?) ?? rows.map { $0["completed_at"] as Int64 }.max()) : nil
            if (reminder["completion_records"] as String?) != json || (reminder["completed_date_keys"] as String?) != keys || (reminder["completed_at"] as Int64?) != completedAt {
                try db.execute(sql: "UPDATE reminders SET completion_records = ?, completed_date_keys = ?, completed_at = ? WHERE id = ?", arguments: [json, keys, completedAt, identifier])
                try SyncRowApplier.queueNotification(db: db, table: "reminders", id: identifier, version: SyncableTimestamp.nowMilliseconds())
            }
        }
    }
}
