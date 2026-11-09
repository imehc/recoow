import XCTest
@testable import recoow

/// 真实后端联调测试。
///
/// 默认探测 `http://127.0.0.1:8080`，不可达则自动跳过；可用环境变量
/// `RECOOW_TEST_BASE_URL` 指向其他地址。这样常规单元测试不会因为后端没起而失败。
///
/// 覆盖内容：账号初始化 → 能力协商 → push → pull → 设备列表 → 配对码 → 恢复密钥找回。
/// 这同时验证了三件事：DTO 契约与服务端一致、ATS 允许当前地址、账号归属正确。
@MainActor
final class CloudSyncIntegrationTests: XCTestCase {
    /// 探测后端是否可达。明文 HTTP 被 ATS 拦截时直接判失败（那是配置缺陷，不该被跳过）。
    private func liveBaseURL() async throws -> String {
        let raw = ProcessInfo.processInfo.environment["RECOOW_TEST_BASE_URL"] ?? "http://127.0.0.1:8080"
        let candidate = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let probe = URL(string: candidate + "/api/version") else {
            throw XCTSkip("无效的后端地址：\(candidate)")
        }
        do {
            _ = try await URLSession.shared.data(from: probe)
        } catch let error as URLError {
            if error.code == .appTransportSecurityRequiresSecureConnection {
                XCTFail("""
                    ATS 拦截了对 \(candidate) 的明文 HTTP 请求（错误码 -1022）。
                    局域网/本机调试需要在 Info.plist 里加入 NSAppTransportSecurity → NSAllowsLocalNetworking。
                    """)
                throw error
            }
            throw XCTSkip("后端不可达（\(candidate)）：\(error.localizedDescription)")
        }
        return candidate
    }

    private func makeConfig(baseURL: String) -> RecoowSyncConfig {
        let suite = "recoow.tests.integration." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suite)
        }
        let config = RecoowSyncConfig(defaults: defaults)
        config.baseURL = baseURL
        return config
    }

    func testAccountSyncRoundTrip() async throws {
        let baseURL = try await liveBaseURL()
        let config = makeConfig(baseURL: baseURL)
        let client = RecoowAPIClient(config: config)

        // 1) 注册账号：服务端必须回传 accountId，否则后续 isConfigured 恒为 false。
        let created = try await client.initializeAccount()
        XCTAssertFalse(created.recoveryKey.isEmpty, "应返回恢复密钥")
        XCTAssertFalse(created.deviceToken.isEmpty, "应返回设备令牌")
        XCTAssertFalse(created.accountId.isEmpty, "服务端必须回传 accountId")
        XCTAssertFalse(created.deviceId.isEmpty, "服务端必须回传 deviceId")

        config.deviceToken = created.deviceToken
        config.accountID = created.accountId
        config.deviceID = created.deviceId
        XCTAssertTrue(config.isConfigured, "写入账号信息后应视为已配置")

        // 2) 能力协商：返回的账号 id 必须与当前账号一致。
        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.accountId, created.accountId, "能力协商返回的账号与本地不一致")
        try capabilities.validate(accountID: created.accountId)
        let generation = capabilities.dataset.generation
        XCTAssertFalse(generation.isEmpty, "应返回数据集世代")

        // 3) push 一条日记标签，服务端应采纳。
        let tagID = "tag-" + UUID().uuidString.prefix(8)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let pushResult = try await client.push(
            items: [
                CloudPushItem(
                    table: "diary_tags",
                    id: tagID,
                    operation: "insert",
                    updatedAtMs: nowMs,
                    deviceID: created.deviceId,
                    snapshot: [
                        "id": tagID,
                        "created_at": nowMs,
                        "updated_at": nowMs,
                        "device_id": created.deviceId,
                        "name": "联调标签",
                    ],
                    mutationID: UUID().uuidString,
                    baseVersion: 0,
                    dataVersion: 1
                )
            ],
            generation: generation
        )
        XCTAssertEqual(pushResult.results.count, 1, "应返回一条推送结果")
        XCTAssertTrue(pushResult.results[0].applied, "推送应被采纳：\(pushResult.results[0].status)")

        // 4) pull 应能拉回刚写入的这条。
        let pulled = try await client.pull(cursor: 0, limit: 200)
        XCTAssertTrue(
            pulled.entries.contains { $0.id == tagID },
            "增量拉取未包含刚写入的记录"
        )

        // 5) 设备列表：应有一台主设备。
        let devices = try await client.listDevices()
        XCTAssertTrue(devices.contains { $0.isOwner }, "应存在主设备")

        // 6) 配对码：10 位数字。
        let code = try await client.createPairingCode()
        XCTAssertEqual(code.count, 10, "配对码应为 10 位，实际 \(code)")
        XCTAssertTrue(code.allSatisfy(\.isNumber), "配对码应全为数字")

        // 7) 用恢复密钥找回：返回同一账号，且签发新令牌。
        let recovered = try await client.recover(
            recoveryKey: created.recoveryKey, deviceName: "联调恢复设备"
        )
        XCTAssertEqual(recovered.accountId, created.accountId, "恢复密钥找回的账号不一致")
        XCTAssertNotEqual(recovered.deviceToken, created.deviceToken, "恢复应签发新设备令牌")
    }
}
