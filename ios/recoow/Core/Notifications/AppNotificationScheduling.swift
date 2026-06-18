import Foundation

protocol AppNotificationScheduling: Sendable {
    func schedule(_ request: AppNotificationRequest) async throws
    func schedule(_ requests: [AppNotificationRequest]) async throws
    func cancel(identifiers: [String])
    func clearBadge() async
}

extension AppNotificationScheduling {
    func schedule(_ requests: [AppNotificationRequest]) async throws {
        for request in requests {
            try await schedule(request)
        }
    }
}
