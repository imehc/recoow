import Foundation
import GRDB

nonisolated struct SyncManifestCandidate: @unchecked Sendable {
    let outboxID: Int64
    let snapshot: [String: Any]
}

extension FullSyncRepository {
    nonisolated func persistManifest(_ data: Data, target: SyncTarget) throws {
        try database.writer.write { db in
            try requireCurrent()
            try db.execute(sql:"INSERT OR REPLACE INTO sync_received_pages (scope,page_key,body) VALUES (?,?,?)",
                           arguments:[target.scope,"manifest:\(target.bootstrapAfter)",data])
        }
    }

    nonisolated func manifestCandidates(_ entries: [[String: Any]], scope: String) throws -> [Int64: SyncManifestCandidate] {
        try database.reader.read { db in
            try requireCurrent()
            var result: [Int64: SyncManifestCandidate] = [:]
            for entry in entries {
                guard entry["deleted"] as? Bool == false,
                      let table = entry["table"] as? String, let id = entry["id"] as? String,
                      let version = entry["version"] as? Int64, let digest = entry["digest"] as? String,
                      !digest.isEmpty, let dataVersion = entry["dataVersion"] as? Int,
                      let row = try Row.fetchOne(db, sql: """
                        SELECT id, payload_json FROM sync_outbox WHERE scope = ? AND table_name = ? AND entity_id = ?
                        AND status = 'pending' AND prepared_json IS NULL AND base_version = 0
                        AND local_change_id IS NULL AND capture_pending = 0 ORDER BY id LIMIT 1
                        """, arguments: [scope, table, id]) else { continue }
                let entity = try SyncEntityCatalog.entity(table)
                guard dataVersion == entity.version else { continue }
                var snapshot = try Self.object(row["payload_json"])
                // 派生值也一并保留以便比较：相等性必须描述完整的云端快照，
                // 而不只是客户端可写的字段。
                for field in entity.fields where field.binary { snapshot.removeValue(forKey: field.name) }
                snapshot.removeValue(forKey: "server_version")
                snapshot.removeValue(forKey: "sync_status")
                snapshot = try entity.upgrade(snapshot, from: entity.version)
                guard (try? SyncSnapshotDigest.digest(snapshot)) == digest else { continue }
                result[version] = SyncManifestCandidate(outboxID: row["id"], snapshot: snapshot)
            }
            return result
        }
    }
}

extension RecoowAPIClient {
    nonisolated static func manifestEntries(_ data: Data, target: SyncTarget) throws -> [[String: Any]] {
        guard let page = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              page["generation"] as? String == target.generation,
              page["watermark"] as? Int64 == target.bootstrapWatermark,
              page["digestVersion"] as? Int == SyncSnapshotDigest.version,
              let entries = page["entries"] as? [[String: Any]], entries.count <= 500,
              let cursor = page["cursor"] as? Int64, let more = page["more"] as? Bool else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("同步清单格式或水位不一致"))
        }
        var previous = target.bootstrapAfter
        for entry in entries {
            guard let version = entry["version"] as? Int64, version > previous, version <= target.bootstrapWatermark,
                  let digest = entry["digest"] as? String,
                  digest.isEmpty || digest.count == 64 && digest.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
                  let metadata = entry["metadata"] as? [String: Any], Set(metadata.keys).isSubset(of: SyncSnapshotDigest.metadataFields),
                  let table = entry["table"] as? String, SyncEntityCatalog.tables.contains(table) else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("同步清单版本、摘要或字段无效"))
            }
            previous = version
        }
        guard cursor == previous, !more || !entries.isEmpty else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("同步清单游标无效"))
        }
        return entries
    }

    func fetchManifest(target: SyncTarget) async throws -> Data {
        try await send(path: "/api/sync/manifests", method: "GET", body: nil, auth: true, query: [
            URLQueryItem(name: "generation", value: target.generation),
            URLQueryItem(name: "watermark", value: "\(target.bootstrapWatermark)"),
            URLQueryItem(name: "after", value: "\(target.bootstrapAfter)"), URLQueryItem(name: "limit", value: "500")
        ])
    }

    func hydrateManifest(_ data: Data, target: SyncTarget, candidates: [Int64: SyncManifestCandidate]) async throws -> Data {
        let entries = try Self.manifestEntries(data, target: target)
        let missing = entries.filter { candidates[$0["version"] as! Int64] == nil }
        var fetched: [Int64: [String: Any]] = [:]
        if !missing.isEmpty {
            let body = try JSONSerialization.data(withJSONObject: ["generation": target.generation,
                "watermark": target.bootstrapWatermark, "versions": missing.map { $0["version"] as! Int64 }])
            let response = try await send(path: "/api/sync/snapshot-queries", method: "POST", body: body, auth: true)
            guard let object = try JSONSerialization.jsonObject(with: response) as? [String: Any],
                  object["generation"] as? String == target.generation, object["watermark"] as? Int64 == target.bootstrapWatermark,
                  let rows = object["entries"] as? [[String: Any]], rows.count == missing.count else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("按需快照响应不一致"))
            }
            for (row, expected) in zip(rows, missing) {
                let update = try CloudSyncResponseDecoder.row(from: row)
                guard update.version == expected["version"] as? Int64, update.table == expected["table"] as? String,
                      update.id == expected["id"] as? String else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("按需快照版本或记录不一致"))
                }
                fetched[update.version] = row
            }
        }
        var page = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        page.removeValue(forKey: "digestVersion")
        page["entries"] = try entries.map { entry -> [String: Any] in
            let version = entry["version"] as! Int64
            if let row = fetched[version] { return row }
            guard let candidate = candidates[version], let metadata = entry["metadata"] as? [String: Any] else {
                throw CloudAPIError.invalidResponse(AppLocalization.string("缺少同步快照"))
            }
            var snapshot = candidate.snapshot.filter { !SyncSnapshotDigest.metadataFields.contains($0.key) }
            snapshot.merge(metadata) { _, remote in remote }
            var row = entry
            row.removeValue(forKey: "metadata"); row.removeValue(forKey: "digest")
            row["snapshot"] = snapshot
            _ = try CloudSyncResponseDecoder.row(from: row)
            return row
        }
        return try JSONSerialization.data(withJSONObject: page, options: [.sortedKeys])
    }
}
