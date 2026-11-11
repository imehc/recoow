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
        if let accountID, let owner = defaults.string(forKey: keyPrefix + "datasetConnectionID"),
           backupAccountScope == Self.backupScope(endpoint: baseURL, accountID: accountID) { return owner }
        return Self.connectionID(endpoint: baseURL, accountID: accountID ?? "")
    }

    private static func connectionID(endpoint: String, accountID: String) -> String {
        let address = endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var components = URLComponents(string: address)
        let scheme = components?.scheme?.lowercased()
        let host = components?.host?.lowercased()
        components?.scheme = scheme
        components?.host = host
        let value = (components?.string ?? address) + "\n" + accountID
        return SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    var datasetConnectionID: String? {
        if let saved = defaults.string(forKey: keyPrefix + "datasetConnectionID") { return saved }
        guard isConfigured else { return nil }
        let owner = connectionID
        defaults.set(owner, forKey: keyPrefix + "datasetConnectionID")
        return owner
    }

    var backupAccountScope: String? {
        if let saved = defaults.string(forKey: keyPrefix + "backupAccountScope") {
            // 只有当已存的连接能确认服务器与账户时，才归一化早期的本地实现。
            let originalAccount = saved.split(separator: ":", maxSplits: 1).last.map(String.init) ?? ""
            if let normalized = try? CloudAccountValidation.endpoint(baseURL),
               defaults.string(forKey: keyPrefix + "datasetConnectionID") == Self.connectionID(endpoint: baseURL, accountID: originalAccount) {
                let legacy = SHA256.hash(data: Data(normalized.utf8)).map { String(format: "%02x", $0) }.joined() + ":" + originalAccount
                if saved == legacy {
                    let canonical = Self.backupScope(endpoint: baseURL, accountID: originalAccount)
                    defaults.set(canonical, forKey: keyPrefix + "backupAccountScope")
                    return canonical
                }
            }
            return saved
        }
        guard isConfigured, let accountID else { return nil }
        let scope = Self.backupScope(endpoint: baseURL, accountID: accountID)
        defaults.set(scope, forKey: keyPrefix + "backupAccountScope")
        return scope
    }

    var hasDatasetOwner: Bool { datasetConnectionID != nil || backupAccountScope != nil }

    static func backupScope(endpoint: String, accountID: String) -> String {
        let normalized = (try? CloudAccountValidation.endpoint(endpoint)) ?? endpoint
        var url = URLComponents(string: normalized)
        if url?.scheme == "https" && url?.port == 443 || url?.scheme == "http" && url?.port == 80 { url?.port = nil }
        let canonical = (url?.string ?? normalized).trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/"
        let server = SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined()
        return server + ":" + accountID
    }

    static func validBackupScope(_ scope: String) -> Bool {
        scope.range(of: "^[a-f0-9]{64}:[A-Za-z0-9._-]{1,128}$", options: .regularExpression) != nil
    }

    func bindDataset(endpoint: String, accountID: String) throws {
        let target = Self.connectionID(endpoint: endpoint, accountID: accountID)
        let targetBackup = Self.backupScope(endpoint: endpoint, accountID: accountID)
        let previous = datasetConnectionID
        if let previous, previous != target, backupAccountScope != targetBackup { throw CloudAccountMismatch() }
        if let owner = backupAccountScope, owner != targetBackup { throw CloudAccountMismatch() }
        defaults.set(previous ?? target, forKey: keyPrefix + "datasetConnectionID")
        defaults.set(targetBackup, forKey: keyPrefix + "backupAccountScope")
    }

    func adoptBackupAccountScope(_ scope: String) throws {
        guard Self.validBackupScope(scope) else { throw AppDataTransferError.missingMetadata }
        if let owner = backupAccountScope, owner != scope { throw CloudAccountMismatch() }
        defaults.set(scope, forKey: keyPrefix + "backupAccountScope")
    }

    func discardUnconfirmedDatasetBinding() {
        guard syncCursor == 0, lastSyncedAt == nil, !isSyncEnabled else { return }
        defaults.removeObject(forKey: keyPrefix + "datasetConnectionID")
        defaults.removeObject(forKey: keyPrefix + "backupAccountScope")
    }

    func retainDatasetBinding() { _ = datasetConnectionID; _ = backupAccountScope }

    /// 设备凭据可以替换，数据集归属及其同步检查点不行。
    func installDeviceCredentials(endpoint: String, accountID: String, deviceID: String, token: String, enableSync: Bool) throws {
        try bindDataset(endpoint: endpoint, accountID: accountID)
        isSyncEnabled = false
        baseURL = endpoint
        self.accountID = accountID
        self.deviceID = deviceID
        deviceToken = token
        lastSyncState = "waiting"
        isSyncEnabled = enableSync && !needsCloudResetConfirmation
    }

    func disconnect() {
        retainDatasetBinding()
        isSyncEnabled = false
        deviceToken = nil
        accountID = nil
        deviceID = nil
    }

    var needsCloudResetConfirmation: Bool {
        get { defaults.bool(forKey: keyPrefix + "resetConfirmation." + connectionID) }
        set { defaults.set(newValue, forKey: keyPrefix + "resetConfirmation." + connectionID) }
    }

    var lastSyncState: String {
        get { defaults.string(forKey: keyPrefix + "status." + connectionID) ?? "waiting" }
        set { defaults.set(newValue, forKey: keyPrefix + "status." + connectionID) }
    }

    func confirmCloudReset() {
        activeScope = nil
        syncCursor = 0
        needsCloudResetConfirmation = false
        isSyncEnabled = true
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

    /// 仅随全部本机数据重置使用；断开连接须保留数据归属。
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
