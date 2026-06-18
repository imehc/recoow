import Foundation
import UserNotifications

final class LocalNotificationScheduler: NSObject, AppNotificationScheduling, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private let center: UNUserNotificationCenter
    private let fileManager: FileManager

    init(
        center: UNUserNotificationCenter = .current(),
        fileManager: FileManager = .default
    ) {
        self.center = center
        self.fileManager = fileManager
        super.init()
        center.delegate = self
    }

    func schedule(_ request: AppNotificationRequest) async throws {
        guard request.scheduledDate > Date() else {
            throw AppNotificationError.scheduleDateInPast
        }

        try await ensureAuthorization()

        let content = UNMutableNotificationContent()
        content.title = request.title
        if let subtitle = request.subtitle {
            content.subtitle = subtitle
        }
        if let body = request.body {
            content.body = body
        }
        if let badge = request.badge {
            content.badge = NSNumber(value: badge)
        }
        if request.playsSound {
            content.sound = .default
        }
        content.userInfo = request.userInfo

        if let attachment = try makeAttachment(for: request) {
            content.attachments = [attachment]
        }

        let interval = max(1, request.scheduledDate.timeIntervalSinceNow)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        let notificationRequest = UNNotificationRequest(
            identifier: request.identifier,
            content: content,
            trigger: trigger
        )

        try await center.add(notificationRequest)
    }

    func cancel(identifiers: [String]) {
        guard identifiers.isEmpty == false else { return }
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound, .badge]
    }

    private func ensureAuthorization() async throws {
        let settings = await center.notificationSettings()

        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return
        case .notDetermined:
            let isGranted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            if isGranted == false {
                throw AppNotificationError.permissionDenied
            }
        case .denied:
            throw AppNotificationError.permissionDenied
        @unknown default:
            throw AppNotificationError.permissionDenied
        }
    }

    private func makeAttachment(for request: AppNotificationRequest) throws -> UNNotificationAttachment? {
        guard let data = request.attachmentData else { return nil }

        let directory = try attachmentDirectory()
        let fileExtension = request.attachmentFileExtension.trimmingCharacters(in: .punctuationCharacters)
        let filename = "\(request.identifier).\(fileExtension.isEmpty ? "jpg" : fileExtension)"
        let url = directory.appending(path: filename)
        try data.write(to: url, options: [.atomic])
        return try UNNotificationAttachment(identifier: request.identifier, url: url)
    }

    private func attachmentDirectory() throws -> URL {
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = appSupport.appending(path: "NotificationAttachments", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
