import Foundation

struct DiaryEntryDetail: Identifiable, Hashable, Sendable {
    let entry: DiaryEntry
    let links: [DiaryLink]
    let attachments: [MediaAttachment]

    var id: String { entry.id }
}
