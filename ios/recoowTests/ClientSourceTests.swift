import Foundation
import GRDB
import XCTest
@testable import recoow

@MainActor
final class ClientSourceTests: XCTestCase {
    private func fixture(supportsOrigin: Bool, source: String?) throws -> (AppDatabase, FullSyncRepository) {
        let database = try AppDatabase.makeInMemory()
        try database.writer.write { db in
            try db.execute(sql: "INSERT INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark, seeded, supports_origin) VALUES ('scope','connection','account','generation',0,1,?)", arguments: [supportsOrigin])
            try db.execute(sql: "INSERT INTO diary_tags (id,created_at,updated_at,device_id,name,sync_status) VALUES ('tag',1,100,'original-phone','offline',0)")
            let snapshot: [String: Any] = ["updated_at": Int64(100), "device_id": "original-phone"]
            try ClientSource.save(db: db, table: "diary_tags", id: "tag", snapshot: snapshot, originJSON: source)
            try FullSyncRepository.seed(db: db, scope: "scope")
        }
        return (database, FullSyncRepository(database: database))
    }

    func testRestoredSourceAndRetryRemainFrozen() throws {
        let origin = "{\"deviceId\":\"original-phone\",\"client\":\"android\",\"os\":\"android\",\"appVersion\":\"old-version\"}"
        let (database, repository) = try fixture(supportsOrigin: true, source: origin)
        defer { try? database.close() }
        let first = try XCTUnwrap(repository.pending(scope: "scope").first)
        XCTAssertEqual(first.item.origin?["client"] as? String, "android")
        try database.writer.write { db in
            try db.execute(sql: "UPDATE record_origins SET origin_json = NULL")
            try db.execute(sql: "UPDATE sync_outbox SET origin_json = NULL")
        }
        let retry = try XCTUnwrap(repository.pending(scope: "scope").first)
        XCTAssertEqual(try FullSyncRepository.json(RecoowAPIClient.object(for: first.item)), try FullSyncRepository.json(RecoowAPIClient.object(for: retry.item)))
    }

    func testOldServerRequestIsNotChangedAfterUpgrade() throws {
        let source = ClientSource.origin(deviceID: "original-phone", localDeviceID: "original-phone")
        let (database, repository) = try fixture(supportsOrigin: false, source: source)
        defer { try? database.close() }
        let first = try XCTUnwrap(repository.pending(scope: "scope").first)
        XCTAssertNil(first.item.origin)
        try database.writer.write { db in try db.execute(sql: "UPDATE sync_targets SET supports_origin = 1") }
        XCTAssertNil(try XCTUnwrap(repository.pending(scope: "scope").first).item.origin)
    }

    func testMissingOrForeignSourceIsNeverInferredFromUploader() throws {
        XCTAssertNil(ClientSource.origin(deviceID: "original-phone", localDeviceID: "new-phone"))
        let (database, repository) = try fixture(supportsOrigin: true, source: nil)
        defer { try? database.close() }
        XCTAssertNil(try XCTUnwrap(repository.pending(scope: "scope").first).item.origin)
    }

    func testBackupPreservesSourceAndSelectiveImportKeepsExistingLocalSource() throws {
        let origin = "{\"deviceId\":\"original-phone\",\"client\":\"android\",\"os\":\"android\"}"
        let (source, _) = try fixture(supportsOrigin: true, source: origin)
        let target = try AppDatabase.makeInMemory()
        defer { try? source.close(); try? target.close() }
        let preferences = AppDataTransferPreferences(languageRawValue: nil, appearanceRawValue: nil,
            addsPickedPhotosToMediaLibrary: false, savesCameraPhotosToLibrary: false, hiddenToolRouteIDs: [])
        let backup = try AppDataTransferService(database: source).exportBackup(sourceDeviceID: "exporting-phone", preferences: preferences)
        defer { try? FileManager.default.removeItem(at: backup) }
        let service = AppDataTransferService(database: target)
        let first = try service.importBackup(from: backup, mode: .mergeMissing, scopes: [.diary])
        defer { try? FileManager.default.removeItem(at: first.rollbackBackupURL) }
        try target.writer.write { db in
            XCTAssertEqual(try String.fetchOne(db, sql: "SELECT origin_json FROM record_origins WHERE entity_id = 'tag'"), origin)
            try db.execute(sql: "UPDATE record_origins SET origin_json = 'keep-existing-source'")
        }
        let second = try service.importBackup(from: backup, mode: .mergeMissing, scopes: [.diary])
        defer { try? FileManager.default.removeItem(at: second.rollbackBackupURL) }
        try target.reader.read { db in
            XCTAssertEqual(try String.fetchOne(db, sql: "SELECT origin_json FROM record_origins WHERE entity_id = 'tag'"), "keep-existing-source")
        }
        let replaced = try service.importBackup(from: backup, mode: .replaceAll, scopes: Set(AppDataImportScope.allCases))
        defer { try? FileManager.default.removeItem(at: replaced.rollbackBackupURL) }
        try target.reader.read { db in
            XCTAssertEqual(try String.fetchOne(db, sql: "SELECT origin_json FROM record_origins WHERE entity_id = 'tag'"), origin)
        }
    }
}
