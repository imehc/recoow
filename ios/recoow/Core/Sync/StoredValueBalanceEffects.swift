import Foundation

nonisolated enum StoredValueBalanceEffects {
    static func decode(_ rawValue: String?) throws -> [String: Int64] {
        guard let rawValue else { return [:] }
        return try JSONDecoder().decode([String: Int64].self, from: Data(rawValue.utf8))
    }

    static func adding(_ amount: Int64, accountID: String, to effects: inout [String: Int64]) throws {
        let result = (effects[accountID] ?? 0).addingReportingOverflow(amount)
        guard !result.overflow else {
            throw NSError(domain: "StoredValueBalanceEffects", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "储值余额超出范围，未保存修改"])
        }
        effects[accountID] = result.partialValue == 0 ? nil : result.partialValue
    }

    static func encode(_ effects: [String: Int64]) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return String(decoding: try encoder.encode(effects), as: UTF8.self)
    }
}
