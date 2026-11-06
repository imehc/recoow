import Foundation

/// HTTP naming is independent of the durable local sync envelope format.
nonisolated enum APIFields {
    private static let legacyNames = Dictionary(uniqueKeysWithValues: APIFieldNames.aliases.map { ($0.value, $0.key) })
    private static let opaque: Set<String> = ["snapshot", "metadata", "before_value", "after_value", "contracts", "record_counts"]

    static func name(_ value: String) -> String { APIFieldNames.aliases[value] ?? value }

    static func rename(_ value: Any, legacy: Bool) throws -> Any {
        if let array = value as? [Any] { return try array.map { try rename($0, legacy: legacy) } }
        guard let object = value as? [String: Any] else { return value }
        var result: [String: Any] = [:]
        for (key, item) in object {
            let canonical = name(key)
            let target = legacy ? legacyNames[canonical] ?? canonical : canonical
            guard result[target] == nil else { throw CloudAPIError.invalidResponse(AppLocalization.string("接口字段重复")) }
            result[target] = opaque.contains(canonical) ? item : try rename(item, legacy: legacy)
        }
        return result
    }

    static func data(_ data: Data, legacy: Bool) throws -> Data {
        let object = try JSONSerialization.jsonObject(with: data)
        return try JSONSerialization.data(withJSONObject: rename(object, legacy: legacy), options: [.sortedKeys])
    }

    static func object(_ object: [String: Any]) throws -> [String: Any] {
        try rename(object, legacy: true) as! [String: Any]
    }
}
