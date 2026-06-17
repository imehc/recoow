import Foundation

/// 当前阶段用 UserDefaults 持久化匿名 device_id；该值不是秘密，只用于同步冲突平票。
final class DeviceIdentifier: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    init(defaults: UserDefaults = .standard, key: String = "recoow_device_id") {
        self.defaults = defaults
        self.key = key
    }

    var value: String {
        if let existing = defaults.string(forKey: key), existing.isEmpty == false {
            return existing
        }

        let generated = UUID().uuidString
        defaults.set(generated, forKey: key)
        return generated
    }
}
