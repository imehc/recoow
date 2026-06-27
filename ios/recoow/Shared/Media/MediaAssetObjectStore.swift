import Foundation

nonisolated final class MediaAssetObjectStore: @unchecked Sendable {
    static let shared = MediaAssetObjectStore()

    private let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    func write(_ data: Data, storageKey: String) throws {
        let url = try url(for: storageKey)
        try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
    }

    func data(for storageKey: String) -> Data? {
        guard let url = try? url(for: storageKey) else { return nil }
        return try? Data(contentsOf: url)
    }

    func data(forAssetID assetID: String, mimeType: String = "image/jpeg") -> Data? {
        if let data = data(for: Self.storageKey(for: assetID, mimeType: mimeType)) {
            return data
        }

        for fallbackMimeType in ["image/jpeg", "image/png", "image/heic"] where fallbackMimeType != mimeType {
            if let data = data(for: Self.storageKey(for: assetID, mimeType: fallbackMimeType)) {
                return data
            }
        }

        return nil
    }

    func contains(storageKey: String) -> Bool {
        guard let url = try? url(for: storageKey) else { return false }
        return fileManager.fileExists(atPath: url.path(percentEncoded: false))
    }

    nonisolated static func storageKey(for assetID: String, mimeType: String) -> String {
        "images/\(assetID).\(fileExtension(for: mimeType))"
    }

    nonisolated private static func fileExtension(for mimeType: String) -> String {
        switch mimeType.lowercased() {
        case "image/png":
            "png"
        case "image/heic", "image/heif":
            "heic"
        default:
            "jpg"
        }
    }

    private func url(for storageKey: String) throws -> URL {
        // 对象存储只接受相对 key，避免未来从同步数据恢复时写出沙盒外路径。
        let safeKey = storageKey
            .split(separator: "/")
            .filter { $0 != "." && $0 != ".." }
            .joined(separator: "/")
        let baseURL = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return baseURL
            .appending(path: "MediaObjects", directoryHint: .isDirectory)
            .appending(path: safeKey)
    }
}
