import GRDB
import XCTest
@testable import recoow

@MainActor
final class SyncConflictTests: XCTestCase {
    private func object(_ json: String) throws -> [String: Any] { try FullSyncRepository.object(json) }

    func testRebaseKeepsCloudEditsAndSubsequentLocalEdits() throws {
        let first = try object(#"{"title":"device","note":"old","updated_at":10,"device_id":"ios"}"#)
        let second = try object(#"{"title":"device","note":"later","updated_at":11,"device_id":"ios"}"#)
        let cloud = try object(#"{"title":"device","note":"old","mood":"cloud","updated_at":12,"device_id":"android"}"#)
        XCTAssertFalse(try SyncSnapshotRebase.conflicts(table: "diary_entries", base: first, local: second, remote: cloud))
        let result = try SyncSnapshotRebase.snapshot(table: "diary_entries", base: first, local: second, remote: cloud)
        XCTAssertEqual(result["mood"] as? String, "cloud")
        XCTAssertEqual(result["note"] as? String, "later")
        XCTAssertEqual((result["updated_at"] as? NSNumber)?.int64Value, 11)
    }

    func testInFlightOverlapAndSettlementGroupNeedConfirmation() throws {
        let base = try object(#"{"title":"device","note":"old"}"#)
        let local = try object(#"{"title":"device","note":"local"}"#)
        let cloud = try object(#"{"title":"device","note":"cloud"}"#)
        XCTAssertTrue(try SyncSnapshotRebase.conflicts(table: "diary_entries", base: base, local: local, remote: cloud))
        XCTAssertFalse(try SyncSnapshotRebase.conflicts(table: "diary_entries", base: base, local: cloud, remote: cloud))
        let bill = try object(#"{"final_amount_cents":100,"payment_method":"cash"}"#)
        let payment = try object(#"{"final_amount_cents":100,"payment_method":"alipay"}"#)
        let amount = try object(#"{"final_amount_cents":120,"payment_method":"cash"}"#)
        XCTAssertTrue(try SyncSnapshotRebase.conflicts(table: "bills", base: bill, local: payment, remote: amount))
    }

    func testRebaseRetainsIntegerPrecisionAndExplicitNull() throws {
        XCTAssertFalse(try SyncSnapshotRebase.equal(Int64(9_007_199_254_740_993), Int64(9_007_199_254_740_992)))
        XCTAssertFalse(try SyncSnapshotRebase.equal(nil, NSNull()))
        let base = try object(#"{"title":"old","note":"old","future":9007199254740993}"#)
        let local = try object(#"{"title":"old","note":null}"#)
        let remote = try object(#"{"title":"cloud","note":"old","future":9007199254740993}"#)
        let result = try SyncSnapshotRebase.snapshot(table: "diary_entries", base: base, local: local, remote: remote)
        XCTAssertEqual((result["future"] as? NSNumber)?.int64Value, 9_007_199_254_740_993)
        XCTAssertTrue(result["note"] is NSNull)
    }

    func testRebaseRecalculatesCumulativeBillEffects() throws {
        let base = try object(#"{"title":"device","final_amount_cents":100,"transaction_type":"storedValueUse","stored_value_account_id":"card","stored_value_effects":"{\"card\":-100}","updated_at":10}"#)
        let local = try object(#"{"title":"next","final_amount_cents":100,"transaction_type":"storedValueUse","stored_value_account_id":"card","stored_value_effects":"{\"card\":-100}","updated_at":11}"#)
        let remote = try object(#"{"title":"device","final_amount_cents":120,"transaction_type":"storedValueUse","stored_value_account_id":"card","stored_value_effects":"{\"card\":-120}","updated_at":12}"#)
        let result = try SyncSnapshotRebase.snapshot(table: "bills", base: base, local: local, remote: remote)
        XCTAssertEqual((result["final_amount_cents"] as? NSNumber)?.int64Value, 120)
        XCTAssertEqual(try StoredValueBalanceEffects.decode(result["stored_value_effects"] as? String), ["card": -120])
    }

    private func fixture() throws -> AppDatabase {
        let database = try AppDatabase.makeInMemory()
        try database.writer.write { db in
            try db.execute(sql: "INSERT INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark) VALUES ('scope','connection','account','generation',0)")
            try db.execute(sql: "INSERT INTO diary_tags (id, created_at, updated_at, device_id, name, note, sync_status) VALUES ('tag',1,10,'ios','device','local',0)")
            let remote = RemoteRowUpdate(table: "diary_tags", id: "tag", version: 1, updatedAtMs: 1, deviceID: "android", snapshot: ["id":"tag", "name":"old", "note":"old", "created_at":Int64(1), "updated_at":Int64(1), "device_id":"android"])
            try db.execute(sql: "INSERT INTO sync_rows VALUES ('scope','diary_tags','tag',1,?)", arguments: [try FullSyncRepository.json(remote.envelope)])
        }
        return database
    }

    func testQueuedOverlapKeepsOriginalAncestor() throws {
        let database = try fixture()
        defer { try? database.close() }
        try database.writer.write { db in
            let first = try object(#"{"id":"tag","created_at":1,"updated_at":9,"device_id":"ios","name":"device","note":"old"}"#)
            let next = try object(#"{"id":"tag","created_at":1,"updated_at":10,"device_id":"ios","name":"device","note":"local"}"#)
            try FullSyncRepository.enqueue(db: db, scope: "scope", table: "diary_tags", entityID: "tag", operation: .update, payloadJSON: FullSyncRepository.json(first), localChangeID: nil)
            let firstID = db.lastInsertedRowID
            try FullSyncRepository.enqueue(db: db, scope: "scope", table: "diary_tags", entityID: "tag", operation: .update, payloadJSON: FullSyncRepository.json(next), localChangeID: nil)
            let nextID = db.lastInsertedRowID
            try db.execute(sql: "UPDATE sync_outbox SET status = 'applied', server_version = 2 WHERE id = ?", arguments: [firstID])
            var cloud = first; cloud["note"] = "cloud"
            let update = RemoteRowUpdate(table: "diary_tags", id: "tag", version: 2, updatedAtMs: 12, deviceID: "ios", snapshot: cloud)
            try SyncSnapshotRebase.pending(db: db, scope: "scope", acknowledgedID: firstID, update: update)
            let queued = try XCTUnwrap(Row.fetchOne(db, sql: "SELECT * FROM sync_outbox WHERE id = ?", arguments: [nextID]))
            XCTAssertEqual(queued["base_version"] as Int64, 1)
            XCTAssertNil(queued["predecessor_id"] as Int64?)
            XCTAssertEqual(try object(queued["payload_json"])["note"] as? String, "local")
        }
        XCTAssertEqual(try FullSyncRepository(database: database).pending(scope: "scope").first?.item.baseVersion, 1)
    }

    func testQueuedIndependentEditsKeepCloudFieldsAndFrozenRequests() throws {
        let database = try fixture()
        defer { try? database.close() }
        try database.writer.write { db in
            let first = try object(#"{"id":"tag","created_at":1,"updated_at":9,"device_id":"ios","name":"device","note":"old"}"#)
            try FullSyncRepository.enqueue(db: db, scope: "scope", table: "diary_tags", entityID: "tag", operation: .update, payloadJSON: FullSyncRepository.json(first), localChangeID: nil)
            let firstID = db.lastInsertedRowID
            for (name, timestamp) in [("second", Int64(10)), ("third", Int64(11))] {
                var next = first; next["name"] = name; next["updated_at"] = timestamp
                try FullSyncRepository.enqueue(db: db, scope: "scope", table: "diary_tags", entityID: "tag", operation: .update, payloadJSON: FullSyncRepository.json(next), localChangeID: nil)
            }
            try db.execute(sql: "UPDATE sync_outbox SET status = 'applied', server_version = 2 WHERE id = ?", arguments: [firstID])
            var cloud = first; cloud["note"] = "cloud"
            let update = RemoteRowUpdate(table: "diary_tags", id: "tag", version: 2, updatedAtMs: 12, deviceID: "ios", snapshot: cloud)
            try SyncSnapshotRebase.pending(db: db, scope: "scope", acknowledgedID: firstID, update: update)
            let local = try XCTUnwrap(Row.fetchOne(db, sql: "SELECT name, note FROM diary_tags WHERE id = 'tag'"))
            XCTAssertEqual(local["name"] as String, "third")
            XCTAssertEqual(local["note"] as String, "cloud")
        }
        let repository = FullSyncRepository(database: database)
        let prepared = try XCTUnwrap(repository.pending(scope: "scope").first)
        XCTAssertEqual(prepared.item.baseVersion, 2)
        XCTAssertEqual(prepared.item.snapshot["note"] as? String, "cloud")
        let retry = try XCTUnwrap(repository.pending(scope: "scope").first)
        XCTAssertEqual(prepared.item.mutationID, retry.item.mutationID)
        XCTAssertEqual(try FullSyncRepository.json(RecoowAPIClient.object(for: prepared.item)), try FullSyncRepository.json(RecoowAPIClient.object(for: retry.item)))
    }

    func testConfirmationRejectsChangedCloudAndLocalState() throws {
        let database = try fixture()
        defer { try? database.close() }
        try database.writer.write { db in
            let row = try XCTUnwrap(Row.fetchOne(db, sql: "SELECT * FROM diary_tags WHERE id = 'tag'"))
            let snapshot = SyncEntityCatalog.snapshot(row: row, entity: try SyncEntityCatalog.entity("diary_tags"))
            try FullSyncRepository.enqueue(db: db, scope: "scope", table: "diary_tags", entityID: "tag", operation: .update, payloadJSON: FullSyncRepository.json(snapshot), localChangeID: nil)
            try db.execute(sql: "UPDATE sync_outbox SET status = 'conflict'")
        }
        let repository = FullSyncRepository(database: database)
        let shown = try XCTUnwrap(repository.conflicts(scope: "scope").first)
        try database.writer.write { try $0.execute(sql: "UPDATE sync_rows SET version = 2, envelope_json = json_set(envelope_json, '$.version', 2)") }
        XCTAssertThrowsError(try repository.resolveConflict(scope: "scope", conflict: shown, keepLocal: true))
        let refreshed = try XCTUnwrap(repository.conflicts(scope: "scope").first)
        try database.writer.write { try $0.execute(sql: "UPDATE diary_tags SET note = 'edited again'") }
        XCTAssertThrowsError(try repository.resolveConflict(scope: "scope", conflict: refreshed, keepLocal: false))
        XCTAssertEqual(try database.reader.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM sync_outbox WHERE status = 'conflict'") }, 1)
    }
}
