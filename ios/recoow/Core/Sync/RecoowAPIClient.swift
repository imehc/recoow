import Foundation
import RecoowAPI
#if canImport(UIKit)
import UIKit
#endif

/// 与 Go 后端 /api 对齐的 DTO 与网络客户端。
/// 所有方法均不持有 UI；并发由 URLSession 处理。

/// 账号初始化（首台设备/主设备）返回体。
typealias CloudInitResponse = RWAPIAccountInitResponse

/// 新设备用配对码加入已有账号的返回体。
typealias CloudPairResponse = RWAPIPairResponse

/// push 中每条变更的结果（服务端是否采纳 + 落库版本号）。
nonisolated enum CloudPushStatus: String, Sendable {
    case applied
    case duplicate
    case conflict
    case unsupportedTable = "unsupported_table"
    case invalidOperation = "invalid_operation"
    case mutationReused = "mutation_reused"
    case dependencyMissing = "dependency_missing"
    case invalidRevision = "invalid_revision"
}

nonisolated struct CloudPushResult: @unchecked Sendable {
    let table: String
    let id: String
    let applied: Bool
    let operation: String
    let version: Int64
    let status: CloudPushStatus
    let current: RemoteRowUpdate?
    var mutationID: String = ""
}

nonisolated struct CloudPushResponse: Sendable {
    let results: [CloudPushResult]
}

/// 已绑定到账号的设备视图。
typealias CloudDevice = RWAPIDevice

typealias CloudDevicesResponse = RWAPIDevicesResponse

typealias CloudPairingCodeResponse = RWAPIPairingCodeResponse

/// 服务端 changelog 里的一条变更（pull 增量拉取的数据来源）。
nonisolated struct CloudChange: @unchecked Sendable {
    let version: Int64
    let table: String
    let id: String
    let operation: ChangeOperation
    let updatedAtMs: Int64
    let deviceID: String
    let deleted: Bool
    let deletedAtMs: Int64?
    let snapshot: [String: Any]?
    var dataVersion: Int = 1
}

nonisolated struct CloudPullResult: Sendable {
    let entries: [CloudChange]
    let cursor: Int64
    let more: Bool
}

/// 组装好的 push 条目（snapshot 为整行 JSON 快照，字段名 snake_case）。
nonisolated struct CloudPushItem: @unchecked Sendable {
    let table: String
    let id: String
    let operation: String
    let updatedAtMs: Int64
    let deviceID: String
    let snapshot: [String: Any]
    var mutationID: String = ""
    var baseVersion: Int64 = 0
    var dataVersion: Int = 1
    var resolution: String?
    var expectedVersion: Int64?
}

nonisolated enum CloudAPIError: LocalizedError {
    case invalidURL
    case unconfigured
    case badStatus(Int, String)
    case transport(String)
    case invalidResponse(String)
    case syncRejected(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return AppLocalization.string("服务器地址无效")
        case .unconfigured:
            return AppLocalization.string("尚未配置服务器或设备令牌")
        case .badStatus(let code, let message):
            return AppLocalization.format("服务器返回 %d（%@）", code, message)
        case .transport(let detail):
            return AppLocalization.format("网络错误：%@", detail)
        case .invalidResponse(let detail):
            return AppLocalization.format("同步响应无效：%@", detail)
        case .syncRejected(let detail):
            return detail
        }
    }
}

/// 云同步后端 REST 客户端。token 从 RecoowSyncConfig 实时读取，
/// 因此配对/初始化后无需重建实例即可使用。
final class RecoowAPIClient: @unchecked Sendable {
    private let config: RecoowSyncConfig
    private let session: URLSession
    private let deviceName: String
    private var fixedBaseURL: String?
    private var fixedToken: String?

    init(config: RecoowSyncConfig, session: URLSession = .shared) {
        self.config = config
        self.session = session
        self.deviceName = Self.currentDeviceName()
    }

    func frozenSession() -> RecoowAPIClient {
        let client = RecoowAPIClient(config: config, session: session)
        client.fixedBaseURL = baseURL()
        client.fixedToken = config.deviceToken
        return client
    }

    // MARK: - 账号/配对

    /// 主设备初始化账号（无账号时可调用）。
    func initializeAccount() async throws -> CloudInitResponse {
        let body = try JSONSerialization.data(withJSONObject: [String: Any]())
        let data = try await send(path: "/api/accounts", method: "POST", body: body, auth: false)
        return try decode(data)
    }

    /// 用恢复密钥把账号重新绑到本机（换机、重装或丢失绑定后找回）。
    /// 服务端只校验密钥哈希，因此这条路径不受 RECOOW_MAX_ACCOUNTS 限制。
    /// 密钥是 base64url 编码，原样透传（不调整大小写、不去除任何字符）。
    func recover(recoveryKey: String, deviceName: String? = nil) async throws -> CloudPairResponse {
        let payload: [String: Any] = [
            "recoveryKey": recoveryKey.trimmingCharacters(in: .whitespacesAndNewlines),
            "deviceName": deviceName ?? self.deviceName,
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await send(path: "/api/account-recoveries", method: "POST", body: body, auth: false)
        return try decode(data)
    }

    /// 新设备输入配对码加入已有账号。
    func pair(code: String, deviceName: String? = nil) async throws -> CloudPairResponse {
        let payload: [String: Any] = [
            "code": code.trimmingCharacters(in: .whitespacesAndNewlines),
            "deviceName": deviceName ?? self.deviceName,
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await send(path: "/api/device-pairings", method: "POST", body: body, auth: false)
        return try decode(data)
    }

    // MARK: - 同步

    /// 上传一批变更，服务端按基础版本进行三方合并并返回每条权威回执。
    func push(items: [CloudPushItem], generation: String = "") async throws -> CloudPushResponse {
        guard config.isConfigured else { throw CloudAPIError.unconfigured }
        let payload: [String: Any] = ["items": items.map { Self.object(for: $0) }, "protocolVersion": 2, "generation": generation]
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await send(path: "/api/sync/mutations", method: "POST", body: body, auth: true)
        return try CloudSyncResponseDecoder.push(data, items: items)
    }

    // MARK: - 同步

    /// 拉取自 cursor 以来的增量变更（服务端按版本升序返回）。
    func pull(cursor: Int64, limit: Int = 500) async throws -> CloudPullResult {
        guard config.isConfigured else { throw CloudAPIError.unconfigured }
        guard var components = URLComponents(string: baseURL()) else { throw CloudAPIError.invalidURL }
        components.path = (components.path.isEmpty ? "" : components.path) + "/api/sync/changes"
        components.queryItems = [
            URLQueryItem(name: "cursor", value: "\(cursor)"),
            URLQueryItem(name: "limit", value: "\(limit)"),
        ]
        guard let url = components.url else { throw CloudAPIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 60
        if let token = config.deviceToken, token.isEmpty == false {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CloudAPIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw CloudAPIError.transport(AppLocalization.string("无效的服务器响应"))
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CloudAPIError.badStatus(http.statusCode, Self.errorMessage(from: data))
        }
        return try CloudSyncResponseDecoder.pull(data, after: cursor)
    }

    // MARK: - 设备管理

    /// 查看本账号已绑定的设备列表（仅主设备可生成配对码）。
    func listDevices() async throws -> [CloudDevice] {
        guard config.isConfigured else { throw CloudAPIError.unconfigured }
        let data = try await send(path: "/api/devices", method: "GET", body: nil, auth: true)
        return try decode(data, as: CloudDevicesResponse.self).devices
    }

    /// 主设备生成一次性配对码（供新设备加入）。
    func createPairingCode() async throws -> String {
        guard config.isConfigured else { throw CloudAPIError.unconfigured }
        let data = try await send(path: "/api/device-pairing-codes", method: "POST", body: nil, auth: true)
        return try decode(data, as: CloudPairingCodeResponse.self).code
    }

    /// 吊销设备。主设备可吊销任意设备；非主设备只能吊销自己。
    func deleteDevice(id: String) async throws {
        guard config.isConfigured else { throw CloudAPIError.unconfigured }
        _ = try await send(path: "/api/devices/\(id)", method: "DELETE", body: nil, auth: true)
    }

    // MARK: - 底层

    func send(path: String, method: String, body: Data?, auth: Bool,
              query: [URLQueryItem] = [], contentType: String = "application/json",
              validateResponse: (@Sendable (HTTPURLResponse) throws -> Void)? = nil) async throws -> Data {
        guard var components = URLComponents(string: baseURL()) else {
            throw CloudAPIError.invalidURL
        }
        components.path = (components.path.isEmpty ? "" : components.path) + path
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw CloudAPIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 60
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        if auth, let token = fixedToken ?? config.deviceToken, token.isEmpty == false {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = body
        }

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw CloudAPIError.transport(AppLocalization.string("无效的服务器响应"))
            }
            guard (200..<300).contains(http.statusCode) else {
                let message = Self.errorMessage(from: data)
                throw CloudAPIError.badStatus(http.statusCode, message)
            }
            try validateResponse?(http)
            return data
        } catch let error as CloudAPIError {
            throw error
        } catch {
            throw CloudAPIError.transport(error.localizedDescription)
        }
    }

    private func baseURL() -> String {
        var url = (fixedBaseURL ?? config.baseURL).trimmingCharacters(in: .whitespacesAndNewlines)
        while url.hasSuffix("/") {
            url.removeLast()
        }
        return url
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        try JSONDecoder().decode(T.self, from: data)
    }

    private func decode<T: Decodable>(_ data: Data, as type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: data)
    }

    nonisolated static func object(for item: CloudPushItem) -> [String: Any] {
        var object: [String: Any] = [
            "table": item.table,
            "id": item.id,
            "operation": item.operation,
            "updatedAtMs": item.updatedAtMs,
            "deviceId": item.deviceID,
            "snapshot": item.snapshot,
            "mutationId": item.mutationID,
            "baseVersion": item.baseVersion,
            "dataVersion": item.dataVersion,
        ]
        object["resolution"] = item.resolution
        object["expectedVersion"] = item.expectedVersion
        return object
    }

    private static func errorMessage(from data: Data) -> String {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let message = object["error"] as? String
        else {
            return AppLocalization.string("未知错误")
        }
        return message
    }

    private static func currentDeviceName() -> String {
        #if os(iOS)
        let name = UIDevice.current.name
        if name.isEmpty == false {
            return name
        }
        #endif
        return AppLocalization.string("iOS 设备")
    }
}
