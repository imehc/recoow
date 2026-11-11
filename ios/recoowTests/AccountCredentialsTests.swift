import Foundation
import GRDB
import XCTest
import RecoowAPI
@testable import recoow

@MainActor
final class AccountCredentialsTests: XCTestCase {
    func testNewPasswordCharactersAndLength() {
        XCTAssertFalse(CloudAccountValidation.password(String(repeating: "a", count: 5)))
        XCTAssertTrue(CloudAccountValidation.password(String(repeating: "a", count: 6)))
        XCTAssertTrue(CloudAccountValidation.password(String(repeating: "a", count: 18)))
        XCTAssertFalse(CloudAccountValidation.password(String(repeating: "a", count: 19)))
        XCTAssertFalse(CloudAccountValidation.password(String(repeating: "密", count: 4)))
        XCTAssertFalse(CloudAccountValidation.password("password with spaces"))
        XCTAssertFalse(CloudAccountValidation.password("password123\n"))
        XCTAssertTrue(CloudAccountValidation.password("Ab1!@#$%^&*()_+-="))
        XCTAssertEqual(CloudAccountValidation.passwordError("short"), "密码须为 6 至 18 个字符")
        XCTAssertEqual(CloudAccountValidation.passwordError("password123密"), "密码仅支持英文字母、数字和常用符号")
        XCTAssertFalse(CloudAccountValidation.password(String(repeating: "密", count: 86)))
    }

    func testDatasetOwnershipSurvivesDisconnectAndBlocksOtherAccounts() throws {
        let suite = "account-owner-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let config = RecoowSyncConfig(defaults: defaults)
        config.baseURL = "https://example.com"
        config.accountID = "original"
        config.deviceToken = "device-token"
        config.retainDatasetBinding()
        config.deviceToken = nil
        config.accountID = nil
        XCTAssertNoThrow(try config.bindDataset(endpoint: "https://EXAMPLE.com/", accountID: "original"))
        XCTAssertThrowsError(try config.bindDataset(endpoint: "https://example.com", accountID: "other"))
        XCTAssertThrowsError(try config.bindDataset(endpoint: "https://other.example", accountID: "original"))
        config.reset()
        XCTAssertNoThrow(try config.bindDataset(endpoint: "https://example.com", accountID: "other"))
    }

    func testReconnectKeepsCheckpointAndPortableServerScope() throws {
        let suite = "account-checkpoint-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let config = RecoowSyncConfig(defaults: defaults)
        try config.installDeviceCredentials(endpoint: "https://example.com/base", accountID: "original",
            deviceID: "old-device", token: "old-token", enableSync: true)
        let scope = config.connectionID + ":generation"
        config.activeScope = scope
        config.syncCursor = 42
        config.lastSyncedAt = Date(timeIntervalSince1970: 100)
        config.disconnect()
        XCTAssertFalse(config.isConfigured)
        XCTAssertEqual(config.syncCursor, 42)
        try config.installDeviceCredentials(endpoint: "https://EXAMPLE.com:443/base/", accountID: "original",
            deviceID: "new-device", token: "new-token", enableSync: true)
        XCTAssertEqual(config.activeScope, scope)
        XCTAssertEqual(config.syncCursor, 42)
        XCTAssertEqual(config.lastSyncedAt, Date(timeIntervalSince1970: 100))
        XCTAssertTrue(config.isSyncEnabled)
        XCTAssertThrowsError(try config.installDeviceCredentials(endpoint: "https://example.com/base", accountID: "other",
            deviceID: "other-device", token: "other-token", enableSync: true))
        XCTAssertEqual(config.deviceToken, "new-token")
        XCTAssertEqual(RecoowSyncConfig.backupScope(endpoint: "https://EXAMPLE.com:443/base/", accountID: "original"), "982ea881d40b49fa1b0a33a9f937c4d27fd857d9e5c9556221b87391ceea1c30:original")
        XCTAssertEqual(RecoowSyncConfig.backupScope(endpoint: "https://example.com/base", accountID: "original"), "982ea881d40b49fa1b0a33a9f937c4d27fd857d9e5c9556221b87391ceea1c30:original")
        XCTAssertFalse(RecoowSyncConfig.validBackupScope("malformed"))
    }

    func testBackupProvenanceRejectsOtherAccountAndRequiresLegacyConfirmation() throws {
        let preferences = AppDataTransferPreferences(languageRawValue: nil, appearanceRawValue: nil,
            addsPickedPhotosToMediaLibrary: false, savesCameraPhotosToLibrary: false, hiddenToolRouteIDs: [])
        let metadata = AppDataTransferMetadata.current(sourceDeviceID: "phone", preferences: preferences, sourceAccountScope: String(repeating: "a", count: 64) + ":account-a")
        let encoded = try metadata.encodedJSONString()
        let decoded = try AppDataTransferMetadata.decoded(fromJSONString: encoded)
        XCTAssertNoThrow(try decoded.validateAccount(currentScope: String(repeating: "a", count: 64) + ":account-a", allowUnverified: false))
        XCTAssertThrowsError(try decoded.validateAccount(currentScope: String(repeating: "a", count: 64) + ":account-b", allowUnverified: true))
        var legacy = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
        legacy.removeValue(forKey: "sourceAccountScope")
        let old = try AppDataTransferMetadata.decoded(fromJSONString: String(decoding: JSONSerialization.data(withJSONObject: legacy), as: UTF8.self))
        XCTAssertThrowsError(try old.validateAccount(currentScope: String(repeating: "a", count: 64) + ":account-a", allowUnverified: false))
        XCTAssertNoThrow(try old.validateAccount(currentScope: nil, allowUnverified: false))
        XCTAssertNoThrow(try old.validateAccount(currentScope: String(repeating: "a", count: 64) + ":account-a", allowUnverified: true))
    }

    func testLocalBackupConflictCancelsMergeAndReplacementIsExplicit() throws {
        let source = try AppDatabase.makeInMemory()
        let target = try AppDatabase.makeInMemory()
        defer { try? source.close(); try? target.close() }
        try source.writer.write { db in
            try db.execute(sql: "INSERT INTO diary_tags (id,name,device_id,created_at,updated_at,sync_status) VALUES ('same','backup','backup',1,1,0), ('missing','only in backup','backup',1,1,0)")
        }
        try target.writer.write { db in try db.execute(sql: "INSERT INTO diary_tags (id,name,device_id,created_at,updated_at,sync_status) VALUES ('same','local','local',1,2,0)") }
        let preferences = AppDataTransferPreferences(languageRawValue: nil, appearanceRawValue: nil,
            addsPickedPhotosToMediaLibrary: false, savesCameraPhotosToLibrary: false, hiddenToolRouteIDs: [])
        let backup = try AppDataTransferService(database: source).exportBackup(sourceDeviceID: "phone", preferences: preferences)
        defer { try? FileManager.default.removeItem(at: backup) }
        let service = AppDataTransferService(database: target)
        XCTAssertThrowsError(try service.importBackup(from: backup, mode: .mergeMissing, scopes: [.diary]))
        try target.reader.read { db in
            XCTAssertEqual(try String.fetchOne(db, sql: "SELECT name FROM diary_tags WHERE id='same'"), "local")
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM diary_tags"), 1)
        }
        let result = try service.importBackup(from: backup, mode: .replaceAll, scopes: [.diary])
        defer { try? FileManager.default.removeItem(at: result.rollbackBackupURL) }
        try target.reader.read { db in
            XCTAssertEqual(try String.fetchOne(db, sql: "SELECT name FROM diary_tags WHERE id='same'"), "backup")
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM diary_tags"), 2)
        }
    }

    func testServerAddressRejectsCredentialsQueryAndInsecureRemoteHost() throws {
        XCTAssertEqual(try CloudAccountValidation.endpoint(" https://EXAMPLE.com/base/ "), "https://example.com/base")
        for endpoint in ["https://user:password@example.com", "https://example.com?token=secret", "https://example.com#fragment", "http://example.com", "file:///private/tmp"] {
            XCTAssertThrowsError(try CloudAccountValidation.endpoint(endpoint))
        }
    }

    func testAccountOperationsKeepGeneratedFieldsAndSeparateTargetCredentials() async throws {
        let suite = "account-api-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let config = RecoowSyncConfig(defaults: defaults)
        config.baseURL = "https://original.example"
        config.deviceToken = "original-device-token"
        config.accountID = "original-account"
        let settings = URLSessionConfiguration.ephemeral
        settings.protocolClasses = [AccountCredentialsProtocol.self]
        let session = URLSession(configuration: settings)
        defer { session.invalidateAndCancel() }
        let original = RecoowAPIClient(config: config, session: session)
        let identity = try await original.bindAccountPassword("Passw0rd-123")
        XCTAssertEqual(identity.accountId, "original-account")
        try await original.changeAccountPassword(current: "original-password", next: "NewPass-456")
        let target = original.sessionForEndpoint("https://target.example")
        let login = try await target.loginAccount(username: " target ", password: "Passw0rd-123")
        XCTAssertEqual(login.accountId, "target-account")
        let reset = try await target.resetAccountPassword(recoveryKey: " Recovery-Key ", next: "NewPass-456")
        XCTAssertEqual(reset.username, "target")
        XCTAssertEqual(config.baseURL, "https://original.example")
        XCTAssertEqual(config.deviceToken, "original-device-token")
        XCTAssertEqual(config.accountID, "original-account")
    }
}

private final class AccountCredentialsProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            XCTAssertNil(request.value(forHTTPHeaderField: "X-Recoow-Field-Case"))
            let data: Data
            if let body = request.httpBody { data = body }
            else if let stream = request.httpBodyStream {
                stream.open()
                defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 1024)
                var collected = Data()
                while stream.hasBytesAvailable {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    collected.append(buffer, count: count)
                }
                data = collected
            } else { data = Data() }
            let body = try JSONSerialization.jsonObject(with: data) as! [String: String]
            var response = #"{"user_id":"user","username":"target","account_id":"target-account"}"#
            switch request.url!.path {
            case "/api/users/me/credentials":
                XCTAssertEqual(request.url!.host, "original.example")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer original-device-token")
                XCTAssertEqual(Set(body.keys), ["password"])
                XCTAssertEqual(body["password"], "Passw0rd-123")
                response = #"{"user_id":"user","username":"original","account_id":"original-account"}"#
            case "/api/users/me/password":
                XCTAssertEqual(Set(body.keys), ["current_password", "new_password"])
                XCTAssertEqual(body["new_password"], "NewPass-456")
                response = #"{"ok":true}"#
            case "/api/device-logins":
                XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
                XCTAssertEqual(request.url!.host, "target.example")
                XCTAssertEqual(request.value(forHTTPHeaderField: "X-Recoow-Client"), "ios")
                XCTAssertEqual(Set(body.keys), ["username", "password", "device_name"])
                XCTAssertEqual(body["password"], "Passw0rd-123")
                response = #"{"user_id":"user","username":"target","account_id":"target-account","device_id":"device","device_token":"target-token"}"#
            case "/api/password-resets":
                XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
                XCTAssertEqual(Set(body.keys), ["recovery_key", "new_password"])
                XCTAssertEqual(body["recovery_key"], "Recovery-Key")
                XCTAssertEqual(body["new_password"], "NewPass-456")
            default: XCTFail("Unexpected account route")
            }
            let http = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(response.utf8))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
