import Foundation

/// LWW 冲突输入的最小契约。业务实体只要暴露这两个字段即可复用策略。
protocol ConflictComparableRecord {
    var updatedAt: Int64 { get }
    var deviceID: String { get }
}

enum ConflictResolver {
    enum Winner {
        case local
        case remote
    }

    /// Last-Write-Wins：updated_at 大者胜；时间相等时使用 device_id 字典序作为确定性平票规则。
    static func resolve<L: ConflictComparableRecord, R: ConflictComparableRecord>(
        local: L,
        remote: R
    ) -> Winner {
        if local.updatedAt > remote.updatedAt {
            return .local
        }

        if remote.updatedAt > local.updatedAt {
            return .remote
        }

        return local.deviceID >= remote.deviceID ? .local : .remote
    }
}
