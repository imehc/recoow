import Foundation

enum MediaAttachmentKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case photo
    case audio

    var id: String { rawValue }

    var title: String {
        switch self {
        case .photo:
            "照片"
        case .audio:
            "语音"
        }
    }

    var systemImage: String {
        switch self {
        case .photo:
            "photo.fill"
        case .audio:
            "waveform"
        }
    }
}
