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

    func totalByteCount() throws -> Int64 {
        try byteCount(at: storageRootURL(create: false))
    }

    func allStorageKeys() throws -> Set<String> {
        let rootURL = try storageRootURL(create: false)
        guard fileManager.fileExists(atPath: rootURL.path(percentEncoded: false)) else {
            return []
        }

        let rootPath = rootURL.path(percentEncoded: false)
        let fileURLs = fileManager.enumerator(
            at: rootURL,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        )?.compactMap { $0 as? URL } ?? []

        return try fileURLs.reduce(into: Set<String>()) { keys, url in
            let values = try url.resourceValues(forKeys: [.isRegularFileKey])
            guard values.isRegularFile == true else { return }

            let path = url.path(percentEncoded: false)
            guard path.hasPrefix(rootPath) else { return }

            let relativePath = path
                .dropFirst(rootPath.count)
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard relativePath.isEmpty == false else { return }

            keys.insert(relativePath)
        }
    }

    func remove(storageKey: String) throws {
        let url = try url(for: storageKey)
        guard fileManager.fileExists(atPath: url.path(percentEncoded: false)) else { return }
        try fileManager.removeItem(at: url)
    }

    func removeObjects(excluding retainedStorageKeys: Set<String>) throws -> Int {
        let storageKeys = try allStorageKeys()
        var removedCount = 0

        for storageKey in storageKeys where retainedStorageKeys.contains(storageKey) == false {
            try remove(storageKey: storageKey)
            removedCount += 1
        }

        return removedCount
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
        return try storageRootURL(create: true).appending(path: safeKey)
    }

    private func storageRootURL(create: Bool) throws -> URL {
        let baseURL = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let rootURL = baseURL
            .appending(path: "MediaObjects", directoryHint: .isDirectory)
        if create {
            try fileManager.createDirectory(at: rootURL, withIntermediateDirectories: true)
        }

        return rootURL
    }

    private func byteCount(at directoryURL: URL) throws -> Int64 {
        guard fileManager.fileExists(atPath: directoryURL.path(percentEncoded: false)) else {
            return 0
        }

        let fileURLs = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )?.compactMap { $0 as? URL } ?? []

        return try fileURLs.reduce(Int64(0)) { total, url in
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            guard values.isRegularFile == true else { return total }
            return total + Int64(values.fileSize ?? 0)
        }
    }
}
