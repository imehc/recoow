import Foundation

struct AppNotificationRequest: Sendable {
    var identifier: String
    var title: String
    var subtitle: String?
    var body: String?
    var scheduledDate: Date
    var badge: Int?
    var playsSound: Bool
    var attachmentData: Data?
    var attachmentFileExtension: String
    var userInfo: [String: String]

    init(
        identifier: String,
        title: String,
        subtitle: String? = nil,
        body: String? = nil,
        scheduledDate: Date,
        badge: Int? = 1,
        playsSound: Bool = true,
        attachmentData: Data? = nil,
        attachmentFileExtension: String = "jpg",
        userInfo: [String: String] = [:]
    ) {
        self.identifier = identifier
        self.title = title
        self.subtitle = subtitle
        self.body = body
        self.scheduledDate = scheduledDate
        self.badge = badge
        self.playsSound = playsSound
        self.attachmentData = attachmentData
        self.attachmentFileExtension = attachmentFileExtension
        self.userInfo = userInfo
    }
}
