import CryptoKit
import GRDB
import UIKit
import XCTest
@testable import recoow

@MainActor
final class MediaAssetTitleTests: XCTestCase {
    func testReferencesFollowActualKeyAfterBackupImportAndReplacement() throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let bytes = fixture.imageData()
        let asset = try fixture.create(bytes)
        let reference = ImageReference(data: nil, assetID: asset.id)
        let key = "sync/" + asset.checksum
        try MediaAssetObjectStore.shared.write(bytes, storageKey: key)
        try fixture.database.writer.write { db in
            try db.execute(sql: "UPDATE media_assets SET storage_key = ? WHERE id = ?", arguments: [key, asset.id])
        }
        // Remove the old ID-derived path, as on a new device importing a backup.
        try MediaAssetObjectStore.shared.remove(storageKey: asset.storageKey)
        XCTAssertEqual(reference.resolvedData, bytes)
        let backupURL = try AppDataTransferService(database: fixture.database).exportBackup(sourceDeviceID: "test", preferences: AppDataTransferPreferences(languageRawValue: nil, appearanceRawValue: nil, addsPickedPhotosToMediaLibrary: false, savesCameraPhotosToLibrary: false, hiddenToolRouteIDs: []))
        defer { try? FileManager.default.removeItem(at: backupURL) }
        try MediaAssetObjectStore.shared.remove(storageKey: key)
        _ = try AppDataTransferService(database: fixture.database).importBackup(from: backupURL, mode: .replaceAll, scopes: Set(AppDataImportScope.allCases))
        XCTAssertEqual(reference.resolvedData, bytes)
        let attachment = MediaAttachment.makeNew(ownerType: .diary, ownerID: "diary", kind: .photo, title: nil, mimeType: asset.mimeType, assetID: asset.id, deviceID: "test")
        XCTAssertEqual(attachment.resolvedData, bytes)
        let replacement = fixture.imageData()
        fixture.track(replacement)
        _ = try fixture.repository.replaceImageAsset(id: asset.id, data: replacement)
        XCTAssertEqual(reference.resolvedData, replacement)
    }
    func testLegacyMigrationPreservesImageAndOrdering() throws {
        let queue = try DatabaseQueue()
        try AppMigrator.makeMigrator().migrate(queue, upTo: "v35_bill_utility_account_number")
        try queue.write { db in
            try db.execute(sql: """
                INSERT INTO media_assets
                (id, created_at, updated_at, sync_status, device_id, kind, storage_backend,
                 storage_key, mime_type, byte_count, checksum, sort_order, inline_data)
                VALUES ('legacy', 10, 20, 0, 'test', 'image', 'local', 'images/legacy.jpg',
                        'image/jpeg', 3, 'original', -3, X'010203')
                """)
        }
        try AppMigrator.migrate(queue)
        let asset = try queue.read { try XCTUnwrap(MediaAsset.fetchOne($0, key: "legacy")) }
        XCTAssertNil(asset.title)
        XCTAssertEqual(asset.inlineData, Data([1, 2, 3]))
        XCTAssertEqual(asset.sortOrder, -3)
        XCTAssertEqual(asset.updatedAt, 20)
        XCTAssertEqual(asset.storageKey, "images/legacy.jpg")
        try AppMigrator.migrate(queue)
        XCTAssertEqual(try queue.read { try MediaAsset.fetchCount($0) }, 1)
    }

    func testRenameDeduplicationAndReplacementKeepReferencesAndTitle() throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let data = fixture.imageData()
        let original = try fixture.create(data)
        let second = try fixture.create(fixture.imageData())
        let order = try fixture.repository.fetchImageAssets().map(\.id)
        try fixture.repository.updateImageAssetTitle(id: original.id, title: "  家里的猫 🐈  ")
        let renamed = try fixture.asset(original.id)
        XCTAssertEqual(renamed.title, "家里的猫 🐈")
        XCTAssertEqual(renamed.storageKey, original.storageKey)
        XCTAssertEqual(renamed.checksum, original.checksum)
        XCTAssertEqual(renamed.sortOrder, original.sortOrder)
        XCTAssertEqual(fixture.repository.data(for: renamed), data)
        XCTAssertEqual(try fixture.repository.fetchImageAssets().map(\.id), order)
        let duplicate = try fixture.repository.createImageAsset(data: data)
        XCTAssertEqual(duplicate.id, original.id)
        XCTAssertEqual(duplicate.title, renamed.title)
        XCTAssertEqual(try fixture.repository.fetchImageAssets().count, 2)

        let replacement = fixture.imageData()
        fixture.track(replacement)
        let replaced = try fixture.repository.replaceImageAsset(id: original.id, data: replacement)
        XCTAssertEqual(replaced.id, original.id)
        XCTAssertEqual(replaced.title, renamed.title)
        XCTAssertEqual(fixture.repository.data(for: replaced), replacement)
        try fixture.repository.reorderImageAssets(ids: [original.id, second.id])
        XCTAssertEqual(try fixture.repository.fetchImageAssets().map(\.id), [original.id, second.id])
        XCTAssertEqual(try fixture.asset(original.id).title, renamed.title)
    }

    func testTitleValidationClearAndNoOpDoNotDamageAsset() throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let asset = try fixture.create(fixture.imageData())
        try fixture.database.writer.write { db in
            try db.execute(sql: "INSERT INTO sync_targets (scope, connection_id, account_id, generation, bootstrap_watermark, seeded) VALUES ('test', 'test', 'test', 'test', 0, 1)")
        }
        let title = String(repeating: "🐈", count: 50)
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: title)
        let count = try fixture.changeCount()
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: " \(title)\n")
        XCTAssertEqual(try fixture.changeCount(), count)
        XCTAssertThrowsError(try fixture.repository.updateImageAssetTitle(id: asset.id, title: title + "猫"))
        XCTAssertEqual(try fixture.asset(asset.id).title, title)
        XCTAssertEqual(try fixture.changeCount(), count)
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: " \n ")
        XCTAssertNil(try fixture.asset(asset.id).title)
        let payload = try fixture.database.reader.read { db in
            try XCTUnwrap(String.fetchOne(db, sql: "SELECT payload_json FROM change_log ORDER BY id DESC LIMIT 1"))
        }
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any])
        XCTAssertTrue(object["title"] is NSNull, "Clearing must be explicit, unlike an old client's omitted field")
        XCTAssertEqual(object["checksum"] as? String, asset.checksum)

        var legacyJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(asset)) as? [String: Any])
        legacyJSON.removeValue(forKey: "title")
        let decoded = try JSONDecoder().decode(MediaAsset.self, from: JSONSerialization.data(withJSONObject: legacyJSON))
        XCTAssertNil(decoded.title)
        XCTAssertEqual(decoded.id, asset.id)
        let legacyPayload = try SyncMediaTransfer.capturePayload(JSONSerialization.data(withJSONObject: legacyJSON), table: MediaAsset.databaseTableName)
        let legacyObject = try XCTUnwrap(JSONSerialization.jsonObject(with: legacyPayload) as? [String: Any])
        XCTAssertNil(legacyObject["title"], "An update queued before upgrading must not clear a remote title")
    }

    func testReferencedAssetCannotBeDeletedAndAttachmentTitleIsPreserved() throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let asset = try fixture.create(fixture.imageData())
        let attachment = MediaAttachment.makeNew(
            ownerType: .diary, ownerID: UUID().uuidString, kind: .photo, title: "独立附件标题",
            mimeType: asset.mimeType, assetID: asset.id, deviceID: "test"
        )
        try fixture.database.writer.write { try attachment.insert($0) }
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: "素材标题")
        XCTAssertThrowsError(try fixture.repository.deleteImageAsset(id: asset.id))
        let items = try fixture.repository.fetchImageAssetLibraryItems()
        XCTAssertEqual(items.first?.referenceCount, 1)
        XCTAssertEqual(try fixture.database.reader.read { try MediaAttachment.fetchOne($0, key: attachment.id)?.title }, "独立附件标题")
        try fixture.database.writer.write { db in
            try db.execute(sql: "UPDATE media_attachments SET deleted_at = 1 WHERE id = ?", arguments: [attachment.id])
        }
        try fixture.repository.deleteImageAsset(id: asset.id)
        XCTAssertTrue(try fixture.repository.fetchImageAssets().isEmpty)
        XCTAssertThrowsError(try fixture.repository.updateImageAssetTitle(id: asset.id, title: "不能恢复已删素材"))
    }

    func testBackupCarriesTitleAndOriginalBytes() throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let data = fixture.imageData()
        let asset = try fixture.create(data)
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: "旅行照片")
        let backupURL = FileManager.default.temporaryDirectory.appending(path: "asset-title-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: backupURL) }
        try fixture.database.backup(to: backupURL)
        try MediaAssetRepository.prepareBackupCopy(at: backupURL)
        let backup = try DatabaseQueue(path: backupURL.path)
        defer { try? backup.close() }
        let restored = try backup.read { try XCTUnwrap(MediaAsset.fetchOne($0, key: asset.id)) }
        XCTAssertEqual(restored.title, "旅行照片")
        XCTAssertEqual(restored.inlineData, data)
        XCTAssertEqual(restored.id, asset.id)
        XCTAssertNil(try fixture.asset(asset.id).inlineData)
    }

    func testTitleObservationFollowsRenameAndClear() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanUp() }
        let asset = try fixture.create(fixture.imageData())
        var iterator = fixture.repository.observeImageTitles().makeAsyncIterator()
        let initial = await iterator.next()
        XCTAssertEqual(try initial?.get(), [:])
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: "新标题")
        let renamed = await iterator.next()
        XCTAssertEqual(try renamed?.get()[asset.id], "新标题")
        try fixture.repository.updateImageAssetTitle(id: asset.id, title: nil)
        let cleared = await iterator.next()
        XCTAssertNil(try cleared?.get()[asset.id])
    }
}

@MainActor
private final class Fixture {
    let database: AppDatabase
    let repository: MediaAssetRepository
    private var storageKeys = Set<String>()
    private let defaultsName = "recoow-asset-tests-\(UUID().uuidString)"

    init() throws {
        database = try AppDatabase.makeInMemory()
        repository = MediaAssetRepository(
            database: database,
            changeLogRepository: ChangeLogRepository(database: database),
            deviceIdentifier: DeviceIdentifier(defaults: UserDefaults(suiteName: defaultsName)!)
        )
    }

    func imageData() -> Data {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        // 唯一字节避免与真机已有素材去重；只在独立内存数据库中创建测试引用。
        return image.jpegData(compressionQuality: 0.8)! + Data(UUID().uuidString.utf8)
    }

    func create(_ data: Data) throws -> MediaAsset {
        track(data)
        let asset = try repository.createImageAsset(data: data)
        storageKeys.insert(asset.storageKey)
        return asset
    }

    func track(_ data: Data) {
        let checksum = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        storageKeys.insert("sync/" + checksum)
    }

    func asset(_ id: String) throws -> MediaAsset {
        try database.reader.read { try XCTUnwrap(MediaAsset.fetchOne($0, key: id)) }
    }

    func changeCount() throws -> Int {
        try database.reader.read { try ChangeRecord.fetchCount($0) }
    }

    func cleanUp() {
        for key in storageKeys { try? MediaAssetObjectStore.shared.remove(storageKey: key) }
        UserDefaults.standard.removePersistentDomain(forName: defaultsName)
        try? database.close()
    }
}
