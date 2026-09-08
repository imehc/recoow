import Foundation
import CryptoKit

/// 云同步（备份）配置。以 UserDefaults 持久化 baseURL、设备 token 等。
/// UI 与同步引擎各自创建一份（读写同一套 key），避免全局单例。
nonisolated final class RecoowSyncConfig: @unchecked Sendable {
    private let defaults: UserDefaults
    private let keyPrefix = "recoow.sync."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// 本轮参与双向同步的表（上传 + 回拉都能本地正确回写）。
    nonisolated static var enabledUploadTables: Set<String> { SyncEntityCatalog.tables }

    var connectionID: String {
        let address = baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var components = URLComponents(string: address)
        let scheme = components?.scheme?.lowercased()
        let host = components?.host?.lowercased()
        components?.scheme = scheme
        components?.host = host
        let value = (components?.string ?? address) + "\n" + (accountID ?? "")
        return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    var activeScope: String? {
        get { defaults.string(forKey: keyPrefix + "scope." + connectionID) }
        set { setOptional(newValue, key: keyPrefix + "scope." + connectionID) }
    }

    var isSyncEnabled: Bool {
        get { defaults.bool(forKey: keyPrefix + "fullSyncEnabled." + connectionID) }
        set { defaults.set(newValue, forKey: keyPrefix + "fullSyncEnabled." + connectionID) }
    }

    var baseURL: String {
        get { defaults.string(forKey: keyPrefix + "baseURL") ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: keyPrefix + "baseURL") }
    }

    var deviceToken: String? {
        get { defaults.string(forKey: keyPrefix + "deviceToken") }
        set {
            if let newValue, newValue.isEmpty == false {
                defaults.set(newValue, forKey: keyPrefix + "deviceToken")
            } else {
                defaults.removeObject(forKey: keyPrefix + "deviceToken")
            }
        }
    }

    var accountID: String? {
        get { defaults.string(forKey: keyPrefix + "accountID") }
        set { setOptional(newValue, key: keyPrefix + "accountID") }
    }

    /// 服务端为本设备签发的 device_id（用于识别当前设备是否为主设备）。
    var deviceID: String? {
        get { defaults.string(forKey: keyPrefix + "deviceID") }
        set { setOptional(newValue, key: keyPrefix + "deviceID") }
    }

    var syncCursor: Int64 {
        get { defaults.object(forKey: keyPrefix + "cursor") as? Int64 ?? 0 }
        set { defaults.set(newValue, forKey: keyPrefix + "cursor") }
    }

    var isConfigured: Bool {
        guard let accountID, !accountID.isEmpty, let token = deviceToken, token.isEmpty == false,
              baseURL.isEmpty == false else {
            return false
        }
        return true
    }

    /// 最近一次手动同步的结果文案（也用于在设置页展示）。
    var lastSyncMessage: String {
        get { defaults.string(forKey: keyPrefix + "lastMessage") ?? "" }
        set { defaults.set(newValue, forKey: keyPrefix + "lastMessage") }
    }

    var lastSyncedAt: Date? {
        get { defaults.object(forKey: keyPrefix + "lastSyncedAt") as? Date }
        set {
            if let newValue {
                defaults.set(newValue, forKey: keyPrefix + "lastSyncedAt")
            } else {
                defaults.removeObject(forKey: keyPrefix + "lastSyncedAt")
            }
        }
    }

    /// 清除全部同步配置（断连时使用）。
    func reset() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(keyPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    private func setOptional(_ value: String?, key: String) {
        if let value, value.isEmpty == false {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}
