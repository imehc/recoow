import Foundation

@main
struct SyncDigestChecks {
    static func main() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let vectors = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        for vector in vectors {
            let raw = vector["snapshot"] as! String
            let snapshot = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
            let actual = try SyncSnapshotDigest.digest(snapshot)
            let expected = vector["digest"] as! String
            // Swift String dictionaries merge canonically equivalent Unicode keys.
            // Such snapshots must miss the digest and fall back to a full download.
            if vector["swiftComparable"] as? Bool == false {
                guard actual != expected else { fatalError("Lossy dictionary conversion must not match") }
            } else if actual != expected {
                fatalError("Digest mismatch: \(raw) expected \(expected), got \(actual)")
            }
        }
        print("Swift digest: \(vectors.count) shared vectors passed")
    }
}
