import Foundation

nonisolated struct CloudDataset: Decodable, Sendable {
    let generation: String
    let head: Int64
    let minCursor: Int64
}

nonisolated struct CloudCapabilities: Decodable, Sendable {
    let protocolVersion: Int
    let accountId: String
    let dataset: CloudDataset
    let contracts: [String: Int]
    let features: [String]

    func validate(accountID: String) throws {
        guard protocolVersion == 2, accountId == accountID, !dataset.generation.isEmpty,
              dataset.head >= dataset.minCursor, dataset.minCursor >= 0 else {
            throw CloudAPIError.syncRejected(AppLocalization.string("服务端协议或账号不匹配，请先升级后端"))
        }
        for (table, version) in SyncEntityCatalog.contracts {
            guard contracts[table] == version else {
                throw CloudAPIError.syncRejected(AppLocalization.format("%@ 数据契约不匹配，请更新客户端或后端", table))
            }
        }
        guard Set(contracts.keys) == SyncEntityCatalog.tables,
              Set(["revision", "receipts", "bootstrap", "media", "unknown_fields", "projections"]).isSubset(of: Set(features)) else {
            throw CloudAPIError.syncRejected(AppLocalization.string("全量实体或协议能力不匹配，已停止同步以保护数据"))
        }
    }
}

extension RecoowAPIClient {
    func capabilities() async throws -> CloudCapabilities {
        let data = try await send(path: "/api/sync/capabilities", method: "GET", body: nil, auth: true)
        return try JSONDecoder().decode(CloudCapabilities.self, from: data)
    }

    func fetchSyncPage(target: SyncTarget) async throws -> Data {
        var query = [URLQueryItem(name: "generation", value: target.generation), URLQueryItem(name: "limit", value: "500")]
        if target.bootstrapComplete {
            query.append(URLQueryItem(name: "cursor", value: "\(target.receivedCursor)"))
        } else {
            query.append(URLQueryItem(name: "watermark", value: "\(target.bootstrapWatermark)"))
            query.append(URLQueryItem(name: "after", value: "\(target.bootstrapAfter)"))
        }
        return try await send(path: target.bootstrapComplete ? "/api/sync/changes" : "/api/sync/snapshots",
                              method: "GET", body: nil, auth: true, query: query)
    }

    func uploadMedia(_ data: Data, reference: SyncMediaReference) async throws {
        let response = try await send(path: "/api/media/objects/\(reference.key)", method: "PUT", body: data, auth: true,
                                      query: [URLQueryItem(name: "checksum", value: reference.checksum)], contentType: reference.contentType)
        guard let object = try JSONSerialization.jsonObject(with: response) as? [String: Any],
              object["checksum"] as? String == reference.checksum,
              object["size"] as? Int64 == reference.size else {
            throw CloudAPIError.invalidResponse(AppLocalization.string("媒体上传确认不匹配"))
        }
    }

    func downloadMedia(reference: SyncMediaReference) async throws -> Data {
        try await send(path: "/api/media/objects/\(reference.key)", method: "GET", body: nil, auth: true)
    }
}
