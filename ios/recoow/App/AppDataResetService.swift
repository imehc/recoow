import Foundation
import UserNotifications

enum AppDataResetService {
    static func resetAllLocalData() throws {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()

        try removeKnownDataDirectories()
        resetUserDefaults()
    }

    private static func removeKnownDataDirectories() throws {
        let fileManager = FileManager.default
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let caches = try fileManager.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let temporaryTransferDirectory = fileManager.temporaryDirectory
            .appending(path: "RecoowDataTransfer", directoryHint: .isDirectory)

        let urls = [
            appSupport.appending(path: "Database", directoryHint: .isDirectory),
            appSupport.appending(path: "MediaObjects", directoryHint: .isDirectory),
            appSupport.appending(path: "DataBackups", directoryHint: .isDirectory),
            temporaryTransferDirectory
        ]

        for url in urls where fileManager.fileExists(atPath: url.path(percentEncoded: false)) {
            try fileManager.removeItem(at: url)
        }

        try removeDirectoryContents(at: caches)
    }

    private static func removeDirectoryContents(at directoryURL: URL) throws {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: directoryURL.path(percentEncoded: false)) else { return }

        let urls = try fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil
        )

        for url in urls {
            try fileManager.removeItem(at: url)
        }
    }

    private static func resetUserDefaults() {
        guard let bundleIdentifier = Bundle.main.bundleIdentifier else { return }
        UserDefaults.standard.removePersistentDomain(forName: bundleIdentifier)
        UserDefaults.standard.synchronize()
    }
}
