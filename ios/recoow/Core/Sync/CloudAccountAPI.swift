import Foundation
import RecoowAPI

typealias CloudAccountIdentity = RWAPIIdentityResponse
typealias CloudDeviceLogin = RWAPIDeviceLoginResponse

struct CloudAccountMismatch: Error {}

enum CloudAccountValidation {
    static func password(_ value: String) -> Bool {
        (6...18).contains(value.utf8.count) && value.utf8.allSatisfy { (33...126).contains($0) }
    }

    static func passwordError(_ value: String) -> String? {
        if !value.utf8.allSatisfy({ (33...126).contains($0) }) { return "密码仅支持英文字母、数字和常用符号" }
        if !(6...18).contains(value.count) { return "密码须为 6 至 18 个字符" }
        return nil
    }

    static func endpoint(_ value: String) throws -> String {
        let value = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var url = URLComponents(string: value), let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
            throw CloudAPIError.invalidURL
        }
        var allowed = url.scheme?.lowercased() == "https"
        #if DEBUG
        allowed = allowed || (url.scheme?.lowercased() == "http" && ["localhost", "127.0.0.1", "::1"].contains(host))
        #endif
        guard allowed else { throw CloudAPIError.invalidURL }
        url.scheme = url.scheme?.lowercased()
        url.host = host.lowercased()
        while url.path.hasSuffix("/") { url.path.removeLast() }
        guard let normalized = url.string else { throw CloudAPIError.invalidURL }
        return normalized
    }

    static func message(for error: Error) -> String {
        switch error {
        case is CloudAccountMismatch: return AppLocalization.string("本机数据属于其他账号，请先导出备份并重置本机数据")
        case CloudAPIError.invalidURL: return AppLocalization.string("请输入有效的 HTTPS 服务器地址")
        case CloudAPIError.badStatus(400, _): return AppLocalization.string("请检查输入后重试")
        case CloudAPIError.badStatus(401, _): return AppLocalization.string("用户名、密码或恢复密钥无效，请核对后重试")
        case CloudAPIError.badStatus(403, _): return AppLocalization.string("此操作需要主设备权限")
        case CloudAPIError.badStatus(404, _): return AppLocalization.string("服务器尚不支持账号密码，请先升级后端")
        case CloudAPIError.badStatus(409, _): return AppLocalization.string("账号已设置密码，请刷新后使用改密")
        case CloudAPIError.badStatus(429, _): return AppLocalization.string("尝试过于频繁，请 15 分钟后重试")
        default: return AppLocalization.string("无法完成账号操作，请检查连接后重试")
        }
    }
}

extension RecoowAPIClient {
    func accountIdentity() async throws -> CloudAccountIdentity? {
        do {
            let data = try await send(path: "/api/users/me", method: "GET", body: nil, auth: true)
            return try decodeAccount(data)
        } catch CloudAPIError.badStatus(404, _) { return nil }
    }

    func bindAccountPassword(_ password: String) async throws -> CloudAccountIdentity {
        let body = try JSONEncoder().encode(RWAPIBindCredentialsRequest(password: password))
        return try decodeAccount(try await send(path: "/api/users/me/credentials", method: "POST", body: body, auth: true))
    }

    func changeAccountPassword(current: String, next: String) async throws {
        let body = try JSONEncoder().encode(RWAPIPasswordRequest(currentPassword: current, newPassword: next))
        _ = try await send(path: "/api/users/me/password", method: "PUT", body: body, auth: true)
    }

    func resetAccountPassword(recoveryKey: String, next: String) async throws -> CloudAccountIdentity {
        let body = try JSONEncoder().encode(RWAPIPasswordResetRequest(recoveryKey: recoveryKey.trimmingCharacters(in: .whitespacesAndNewlines), newPassword: next))
        return try decodeAccount(try await send(path: "/api/password-resets", method: "POST", body: body, auth: false))
    }

    func loginAccount(username: String, password: String) async throws -> CloudDeviceLogin {
        let body = try JSONEncoder().encode(RWAPIDeviceLoginRequest(username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password, deviceName: Self.currentDeviceName()))
        let response: CloudDeviceLogin = try decodeAccount(try await send(path: "/api/device-logins", method: "POST", body: body, auth: false))
        guard !response.userId.isEmpty, !response.username.isEmpty, !response.accountId.isEmpty,
              !response.deviceId.isEmpty, !response.deviceToken.isEmpty else {
            throw CloudAPIError.invalidResponse("invalid login identity")
        }
        return response
    }

    private func decodeAccount<T: Decodable>(_ data: Data) throws -> T {
        try JSONDecoder().decode(T.self, from: APIFields.data(data, legacy: false))
    }
}
