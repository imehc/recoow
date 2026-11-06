import Foundation
import GRDB
import XCTest
@testable import recoow

@MainActor
final class SyncManifestTests: XCTestCase {
    private func fixture() throws -> (AppDatabase, FullSyncRepository, SyncTarget) {
        let database = try AppDatabase.makeInMemory()
        try database.writer.write { db in
            try db.execute(sql: "INSERT INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark, seeded) VALUES ('scope','connection','account','generation',1,1)")
            try db.execute(sql: "INSERT INTO diary_tags (id,created_at,updated_at,device_id,name,note,sync_status) VALUES ('same',1,10,'ios','one',NULL,0)")
            try FullSyncRepository.seed(db: db, scope: "scope")
        }
        let repository = FullSyncRepository(database: database)
        return (database, repository, try repository.target(scope: "scope"))
    }

    private func manifest(_ database: AppDatabase) throws -> Data {
        let payload = try database.reader.read { db in
            try XCTUnwrap(String.fetchOne(db, sql: "SELECT payload_json FROM sync_outbox WHERE entity_id = 'same' ORDER BY id LIMIT 1"))
        }
        let snapshot = try FullSyncRepository.object(payload)
        let entry: [String: Any] = ["table":"diary_tags", "id":"same", "version":Int64(1), "updatedAtMs":Int64(20),
            "deviceId":"remote", "dataVersion":1, "operation":"update", "deleted":false,
            "digest":try SyncSnapshotDigest.digest(snapshot), "metadata":["updated_at":Int64(20),"device_id":"remote"]]
        return try JSONSerialization.data(withJSONObject: ["generation":"generation", "watermark":Int64(1),
            "cursor":Int64(1), "more":false, "digestVersion":1, "entries":[entry]])
    }

    func testIdenticalRestoredRecordEstablishesBaselineWithoutUploading() async throws {
        let (database, repository, target) = try fixture()
        defer { try? database.close() }
        let data = try manifest(database)
        let candidates = try repository.manifestCandidates(RecoowAPIClient.manifestEntries(data, target: target), scope: target.scope)
        XCTAssertEqual(candidates.count,1)
        let client = RecoowAPIClient(config: RecoowSyncConfig())
        let hydrated = try await client.hydrateManifest(data, target: target, candidates: candidates)
        XCTAssertFalse(try repository.receive(hydrated, target: target, matchedOutbox: candidates))
        _ = try repository.applyInbox(scope: target.scope)
        XCTAssertEqual(try repository.pendingCount(scope: target.scope),0)
        XCTAssertEqual(try repository.target(scope: target.scope).appliedCursor,1)
        try await database.reader.read { db in
            let shadow = try XCTUnwrap(FullSyncRepository.shadow(db: db, scope: target.scope, table: "diary_tags", id:"same"))
            XCTAssertEqual(shadow.version,1)
            XCTAssertEqual(shadow.deviceID,"remote")
            XCTAssertEqual(try String.fetchOne(db,sql:"SELECT device_id FROM diary_tags WHERE id='same'"),"remote")
        }
    }

    func testEditDuringInventoryIsPreservedAndUsesMatchedAncestor() async throws {
        let (database, repository, target) = try fixture()
        defer { try? database.close() }
        let data = try manifest(database)
        let candidates = try repository.manifestCandidates(RecoowAPIClient.manifestEntries(data, target: target), scope: target.scope)
        try await database.writer.write { db in
            var next = try XCTUnwrap(candidates[1]).snapshot
            next["name"]="later"; next["updated_at"]=Int64(30)
            try db.execute(sql:"UPDATE diary_tags SET name='later',updated_at=30 WHERE id='same'")
            try FullSyncRepository.enqueue(db:db, scope:target.scope, table:"diary_tags", entityID:"same", operation:.update,
                payloadJSON:FullSyncRepository.json(next), localChangeID:nil)
        }
        let client = RecoowAPIClient(config: RecoowSyncConfig())
        let hydrated = try await client.hydrateManifest(data, target:target, candidates:candidates)
        _ = try repository.receive(hydrated, target:target, matchedOutbox:candidates)
        _ = try repository.applyInbox(scope:target.scope)
        let pending = try XCTUnwrap(repository.pending(scope:target.scope).first)
        XCTAssertEqual(pending.item.snapshot["name"] as? String,"later")
        XCTAssertEqual(pending.item.baseVersion,1)
        let name = try await database.reader.read { try String.fetchOne($0,sql:"SELECT name FROM diary_tags WHERE id='same'") }
        XCTAssertEqual(name,"later")
    }

    func testPreparedMutationCannotBeSkippedByInventory() throws {
        let (database, repository, target) = try fixture()
        defer { try? database.close() }
        let data = try manifest(database)
        _ = try repository.pending(scope:target.scope)
        let candidates = try repository.manifestCandidates(RecoowAPIClient.manifestEntries(data, target:target), scope:target.scope)
        XCTAssertTrue(candidates.isEmpty)
        XCTAssertEqual(try repository.pendingCount(scope:target.scope),1)
    }

    func testInvalidPageDoesNotConfirmSeedOrAdvanceCursor() throws {
        let (database, repository, target) = try fixture()
        defer { try? database.close() }
        let data = try manifest(database)
        let candidates = try repository.manifestCandidates(RecoowAPIClient.manifestEntries(data,target:target),scope:target.scope)
        let invalid = try JSONSerialization.data(withJSONObject:["generation":"generation","watermark":1,"cursor":1,"more":false,"entries":[]])
        XCTAssertThrowsError(try repository.receive(invalid,target:target,matchedOutbox:candidates))
        XCTAssertEqual(try repository.pendingCount(scope:target.scope),1)
        XCTAssertEqual(try repository.target(scope:target.scope).bootstrapAfter,0)
    }

    func testMediaPresenceRequiresChecksumAndSizeAndRecognizesMissingObjects() async throws {
        let suite = "manifest-media-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName:suite))
        defer { defaults.removePersistentDomain(forName:suite) }
        let config = RecoowSyncConfig(defaults:defaults)
        config.baseURL="http://localhost"
        let settings = URLSessionConfiguration.ephemeral
        settings.protocolClasses=[ManifestMediaProtocol.self]
        let session = URLSession(configuration:settings)
        defer { session.invalidateAndCancel() }
        let client = RecoowAPIClient(config:config,session:session)
        let exists = try await client.mediaExists(reference:SyncMediaReference(data:Data("exists".utf8),contentType:"image/jpeg"))
        XCTAssertTrue(exists)
        let missing = try await client.mediaExists(reference:SyncMediaReference(data:Data("missing".utf8),contentType:"image/jpeg"))
        XCTAssertFalse(missing)
        do {
            _ = try await client.mediaExists(reference:SyncMediaReference(data:Data("wrong".utf8),contentType:"image/jpeg"))
            XCTFail("Incorrect media identity accepted")
        } catch CloudAPIError.invalidResponse(_) { }
    }
}

private final class ManifestMediaProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request:URLRequest) -> Bool { true }
    override class func canonicalRequest(for request:URLRequest) -> URLRequest { request }
    override func startLoading() {
        let existing = SyncMediaReference(data:Data("exists".utf8),contentType:"image/jpeg")
        let missing = SyncMediaReference(data:Data("missing".utf8),contentType:"image/jpeg")
        let checksum = request.url!.lastPathComponent
        let status = checksum == missing.checksum ? 404 : 200
        let headers = ["ETag":"\"\(existing.checksum)\"","Content-Length":"\(existing.size)"]
        let response = HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:"HTTP/1.1",headerFields:headers)!
        client?.urlProtocol(self,didReceive:response,cacheStoragePolicy:.notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
