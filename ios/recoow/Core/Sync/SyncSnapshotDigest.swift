import CoreFoundation
import CryptoKit
import Foundation

nonisolated enum SyncSnapshotDigest {
    static let version = 1
    static let metadataFields: Set<String> = ["updated_at", "device_id", "server_version", "sync_status"]
    private enum Invalid: Error { case value, number }

    static func digest(_ snapshot: [String: Any]) throws -> String {
        let fields = snapshot.filter { !metadataFields.contains($0.key) }
        let encoded = try canonical(fields)
        return SHA256.hash(data: Data(encoded.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func canonical(_ value: Any) throws -> String {
        if value is NSNull { return "z" }
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue ? "t" : "f" }
            return "n" + (try canonicalNumber(number.stringValue)) + ";"
        }
        if let text = value as? String { return "s\(text.utf8.count):\(text)" }
        if let array = value as? [Any] { return "a\(array.count)[" + (try array.map(canonical).joined()) + "]" }
        if let object = value as? [String: Any] {
            let keys = object.keys.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }
            return "o\(keys.count){" + (try keys.map { try canonical($0) + canonical(object[$0]!) }.joined()) + "}"
        }
        throw Invalid.value
    }

    private static func canonicalNumber(_ raw: String) throws -> String {
        let parts = raw.lowercased().split(separator: "e", omittingEmptySubsequences: false)
        guard parts.count <= 2 else { throw Invalid.number }
        var exponent = 0
        if parts.count == 2 {
            guard let parsed = Int(parts[1]), parsed > -1_000_000, parsed < 1_000_000 else { throw Invalid.number }
            exponent = parsed
        }
        var digits = String(parts[0])
        let negative = digits.hasPrefix("-")
        if negative { digits.removeFirst() }
        if let dot = digits.firstIndex(of: ".") {
            exponent -= digits.distance(from: digits.index(after: dot), to: digits.endIndex)
            digits.remove(at: dot)
        }
        guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw Invalid.number }
        while digits.first == "0" { digits.removeFirst() }
        if digits.isEmpty { return "0e0" }
        while digits.last == "0" { digits.removeLast(); exponent += 1 }
        return (negative ? "-" : "") + digits + "e\(exponent)"
    }
}
