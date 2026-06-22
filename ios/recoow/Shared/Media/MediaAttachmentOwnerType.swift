import Foundation

enum MediaAttachmentOwnerType: String, CaseIterable, Identifiable, Codable, Sendable {
    case diary

    var id: String { rawValue }
}
