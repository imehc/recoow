import Foundation

enum MediaAttachmentOwnerType: String, CaseIterable, Identifiable, Codable, Sendable {
    case diary
    case foodEntry

    var id: String { rawValue }
}
