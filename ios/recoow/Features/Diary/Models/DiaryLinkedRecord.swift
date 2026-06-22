import Foundation

struct DiaryLinkedRecord: Identifiable, Hashable, Sendable {
    let sourceType: DiaryLinkSourceType
    let sourceID: String
    let title: String
    let subtitle: String?
    let systemImage: String
    let occurredAt: Int64?
    let snapshotJSON: String?

    var id: String {
        "\(sourceType.rawValue):\(sourceID)"
    }

    func makeLink(diaryID: String, deviceID: String) -> DiaryLink {
        DiaryLink.makeNew(diaryID: diaryID, record: self, deviceID: deviceID)
    }

    static func track(_ track: Track) -> DiaryLinkedRecord {
        DiaryLinkedRecord(
            sourceType: .track,
            sourceID: track.id,
            title: track.name,
            subtitle: [
                AppFormatters.distance(track.distanceMeters),
                AppFormatters.duration(track.durationSeconds)
            ].joined(separator: " · "),
            systemImage: DiaryLinkSourceType.track.systemImage,
            occurredAt: track.startedAt,
            snapshotJSON: encodeSnapshot(DiaryTrackLinkSnapshot(track: track))
        )
    }

    static func bill(_ bill: BillRecord) -> DiaryLinkedRecord {
        DiaryLinkedRecord(
            sourceType: .bill,
            sourceID: bill.id,
            title: bill.title,
            subtitle: [
                bill.displayAmount,
                bill.billType.localizedTitle,
                AppFormatters.date(milliseconds: bill.occurredAt)
            ].joined(separator: " · "),
            systemImage: DiaryLinkSourceType.bill.systemImage,
            occurredAt: bill.occurredAt,
            snapshotJSON: encodeSnapshot(DiaryBillLinkSnapshot(bill: bill))
        )
    }

    static func reminder(_ reminder: ReminderRecord) -> DiaryLinkedRecord {
        let subtitle = [
            AppLocalization.string(reminder.scheduleKind.title),
            reminder.progressText ?? reminder.goalSummaryText,
            AppLocalization.string(reminder.checkInStatus().title)
        ]
        .compactMap { value in
            value?.isEmpty == false ? value : nil
        }
        .joined(separator: " · ")

        return DiaryLinkedRecord(
            sourceType: .reminder,
            sourceID: reminder.id,
            title: reminder.title,
            subtitle: subtitle,
            systemImage: reminder.scheduleKind.systemImage,
            occurredAt: reminder.scheduledAt,
            snapshotJSON: encodeSnapshot(DiaryReminderLinkSnapshot(reminder: reminder))
        )
    }

    static func anniversary(_ anniversary: AnniversaryRecord) -> DiaryLinkedRecord {
        let nextText: String? = {
            guard let days = anniversary.daysUntilNext() else { return nil }
            return days == 0 ? AppLocalization.string("今天") : AppLocalization.format("%d 天后", days)
        }()

        return DiaryLinkedRecord(
            sourceType: .anniversary,
            sourceID: anniversary.id,
            title: anniversary.title,
            subtitle: [
                anniversary.category.localizedTitle,
                AppFormatters.date(milliseconds: anniversary.occurredAt),
                nextText
            ]
            .compactMap { $0 }
            .joined(separator: " · "),
            systemImage: anniversary.category.systemImage,
            occurredAt: anniversary.occurredAt,
            snapshotJSON: encodeSnapshot(DiaryAnniversaryLinkSnapshot(anniversary: anniversary))
        )
    }

    static func storedItem(_ item: StoredItem, categoryName: String?) -> DiaryLinkedRecord {
        DiaryLinkedRecord(
            sourceType: .storedItem,
            sourceID: item.id,
            title: item.title,
            subtitle: [
                item.location,
                categoryName
            ]
            .compactMap { value in
                value?.isEmpty == false ? value : nil
            }
            .joined(separator: " · "),
            systemImage: DiaryLinkSourceType.storedItem.systemImage,
            occurredAt: item.updatedAt,
            snapshotJSON: encodeSnapshot(DiaryStoredItemLinkSnapshot(item: item, categoryName: categoryName))
        )
    }

    static func decisionChoice(_ record: DecisionChoiceRecord) -> DiaryLinkedRecord {
        DiaryLinkedRecord(
            sourceType: .decisionChoice,
            sourceID: record.id,
            title: record.optionTitle,
            subtitle: [
                record.collectionTitle,
                AppFormatters.date(milliseconds: record.selectedAt)
            ].joined(separator: " · "),
            systemImage: DiaryLinkSourceType.decisionChoice.systemImage,
            occurredAt: record.selectedAt,
            snapshotJSON: encodeSnapshot(DiaryDecisionChoiceLinkSnapshot(record: record))
        )
    }

    private static func encodeSnapshot<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }
}

private struct DiaryTrackLinkSnapshot: Encodable {
    let id: String
    let name: String
    let startedAt: Int64
    let endedAt: Int64?
    let distanceMeters: Double
    let durationSeconds: Int64
    let note: String?

    init(track: Track) {
        id = track.id
        name = track.name
        startedAt = track.startedAt
        endedAt = track.endedAt
        distanceMeters = track.distanceMeters
        durationSeconds = track.durationSeconds
        note = track.note
    }
}

private struct DiaryBillLinkSnapshot: Encodable {
    let id: String
    let title: String
    let displayAmount: String
    let finalAmountCents: Int64
    let transactionType: String
    let category: String
    let paymentMethod: String
    let occurredAt: Int64
    let note: String?

    init(bill: BillRecord) {
        id = bill.id
        title = bill.title
        displayAmount = bill.displayAmount
        finalAmountCents = bill.finalAmountCents
        transactionType = bill.transactionType
        category = bill.category
        paymentMethod = bill.paymentMethod
        occurredAt = bill.occurredAt
        note = bill.note
    }
}

private struct DiaryReminderLinkSnapshot: Encodable {
    let id: String
    let title: String
    let note: String?
    let scheduleKind: String
    let scheduleTitle: String
    let scheduledAt: Int64
    let totalCheckInDays: Int
    let progressTotalDays: Int?
    let status: String

    init(reminder: ReminderRecord) {
        id = reminder.id
        title = reminder.title
        note = reminder.note
        scheduleKind = reminder.scheduleKind.rawValue
        scheduleTitle = reminder.scheduleTitle
        scheduledAt = reminder.scheduledAt
        totalCheckInDays = reminder.totalCheckInDays
        progressTotalDays = reminder.progressTotalDays
        status = reminder.checkInStatus().title
    }
}

private struct DiaryAnniversaryLinkSnapshot: Encodable {
    let id: String
    let title: String
    let note: String?
    let category: String
    let occurredAt: Int64
    let dateCalendar: String
    let isYearly: Bool

    init(anniversary: AnniversaryRecord) {
        id = anniversary.id
        title = anniversary.title
        note = anniversary.note
        category = anniversary.categoryRawValue
        occurredAt = anniversary.occurredAt
        dateCalendar = anniversary.dateCalendarRawValue
        isYearly = anniversary.isYearly
    }
}

private struct DiaryStoredItemLinkSnapshot: Encodable {
    let id: String
    let title: String
    let location: String
    let categoryName: String?
    let note: String?
    let tags: String?
    let updatedAt: Int64

    init(item: StoredItem, categoryName: String?) {
        id = item.id
        title = item.title
        location = item.location
        self.categoryName = categoryName
        note = item.note
        tags = item.tags
        updatedAt = item.updatedAt
    }
}

private struct DiaryDecisionChoiceLinkSnapshot: Encodable {
    let id: String
    let collectionID: String
    let collectionTitle: String
    let optionID: String
    let optionTitle: String
    let optionDetail: String?
    let optionCustomInfo: String?
    let selectedAt: Int64

    init(record: DecisionChoiceRecord) {
        id = record.id
        collectionID = record.collectionID
        collectionTitle = record.collectionTitle
        optionID = record.optionID
        optionTitle = record.optionTitle
        optionDetail = record.optionDetail
        optionCustomInfo = record.optionCustomInfo
        selectedAt = record.selectedAt
    }
}
