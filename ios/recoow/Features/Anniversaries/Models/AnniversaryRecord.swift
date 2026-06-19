import Foundation
import GRDB

/// 一条纪念日。日期按原始发生日保存，重复提醒按下一次发生日期动态计算。
struct AnniversaryRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "anniversaries"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var note: String?
    var categoryRawValue: String
    var occurredAt: Int64
    var dateCalendarRawValue: String
    var isYearly: Bool
    var leadTimeMinutes: Int
    var isEnabled: Bool
    var reminderTimeMinutes: Int

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case note
        case categoryRawValue = "category"
        case occurredAt = "occurred_at"
        case dateCalendarRawValue = "date_calendar"
        case isYearly = "is_yearly"
        case leadTimeMinutes = "lead_time_minutes"
        case isEnabled = "is_enabled"
        case reminderTimeMinutes = "reminder_time_minutes"
    }

    var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    var category: AnniversaryCategory {
        AnniversaryCategory(rawValue: categoryRawValue) ?? .other
    }

    var dateCalendar: AnniversaryDateCalendar {
        AnniversaryDateCalendar(rawValue: dateCalendarRawValue) ?? .gregorian
    }

    var occurrenceCalendar: Calendar {
        dateCalendar.calendar
    }

    var leadTime: ReminderLeadTime {
        ReminderLeadTime(rawValue: leadTimeMinutes) ?? .none
    }

    var nextOccurrenceDate: Date? {
        nextOccurrenceDate(from: Date())
    }

    var isExpired: Bool {
        isYearly == false && nextOccurrenceDate == nil
    }

    var daysSince: Int {
        days(from: occurredDate, to: Date())
    }

    var yearsSince: Int {
        max(0, occurrenceCalendar.dateComponents([.year], from: occurredDate, to: Date()).year ?? 0)
    }

    func nextOccurrenceDate(from referenceDate: Date, calendar: Calendar = .current) -> Date? {
        let occurrenceCalendar = occurrenceCalendar
        let baseOccurrenceDate = dateByApplyingReminderTime(to: occurredDate, calendar: calendar)

        guard isYearly else {
            return baseOccurrenceDate >= referenceDate ? baseOccurrenceDate : nil
        }

        if referenceDate < baseOccurrenceDate {
            return baseOccurrenceDate
        }

        let currentYearOccurrence = occurrenceDate(matchingYearOf: referenceDate, calendar: occurrenceCalendar)

        if currentYearOccurrence >= referenceDate {
            return currentYearOccurrence
        }

        return occurrenceCalendar.date(byAdding: .year, value: 1, to: currentYearOccurrence)
            ?? currentYearOccurrence
    }

    func occurrenceDate(on date: Date, calendar: Calendar = .current) -> Date? {
        let targetDay = calendar.startOfDay(for: date)
        guard targetDay >= calendar.startOfDay(for: occurredDate) else { return nil }

        if isYearly {
            let occurrence = occurrenceDate(matchingYearOf: targetDay, calendar: occurrenceCalendar)
            return calendar.isDate(occurrence, inSameDayAs: targetDay) ? occurrence : nil
        }

        let occurrence = dateByApplyingReminderTime(to: occurredDate, calendar: calendar)
        return calendar.isDate(occurrence, inSameDayAs: targetDay) ? occurrence : nil
    }

    func daysUntilNext(from referenceDate: Date = Date(), calendar: Calendar = .current) -> Int? {
        guard let nextOccurrence = nextOccurrenceDate(from: referenceDate, calendar: calendar) else { return nil }
        return days(from: referenceDate, to: nextOccurrence, calendar: calendar)
    }

    func occurrenceYears(on date: Date = Date(), calendar: Calendar = .current) -> Int {
        let occurrenceCalendar = occurrenceCalendar
        let targetYear = occurrenceCalendar.component(.year, from: date)
        let startYear = occurrenceCalendar.component(.year, from: occurredDate)
        return max(0, targetYear - startYear)
    }

    func formattedDate(_ date: Date, locale: Locale = AppLocalization.currentLocale) -> String {
        let dateText = formattedDate(date, locale: locale, calendar: occurrenceCalendar)
        let primaryText = AppLocalization.format("%@ %@", dateCalendar.localizedTitle, dateText)
        let counterpartCalendar = counterpartDateCalendar

        return formattedDateWithCounterpart(
            primaryText: primaryText,
            counterpartTitle: counterpartCalendar.localizedTitle,
            counterpartValue: formattedDate(date, locale: locale, calendar: counterpartCalendar.calendar)
        )
    }

    func formattedDateTime(_ date: Date, locale: Locale = AppLocalization.currentLocale) -> String {
        let dateText = formattedDateTime(date, locale: locale, calendar: occurrenceCalendar)
        let primaryText = AppLocalization.format("%@ %@", dateCalendar.localizedTitle, dateText)
        let counterpartCalendar = counterpartDateCalendar

        return formattedDateWithCounterpart(
            primaryText: primaryText,
            counterpartTitle: counterpartCalendar.localizedTitle,
            counterpartValue: formattedDateTime(date, locale: locale, calendar: counterpartCalendar.calendar)
        )
    }

    private var counterpartDateCalendar: AnniversaryDateCalendar {
        dateCalendar == .gregorian ? .chinese : .gregorian
    }

    private func formattedDateWithCounterpart(
        primaryText: String,
        counterpartTitle: String,
        counterpartValue: String
    ) -> String {
        AppLocalization.format(
            "%@（%@ %@）",
            primaryText,
            counterpartTitle,
            counterpartValue
        )
    }

    private func formattedDate(_ date: Date, locale: Locale, calendar: Calendar) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    private func formattedDateTime(_ date: Date, locale: Locale, calendar: Calendar) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    static func makeNew(
        title: String,
        note: String?,
        category: AnniversaryCategory,
        occurredAt: Int64,
        dateCalendar: AnniversaryDateCalendar,
        isYearly: Bool,
        leadTime: ReminderLeadTime,
        isEnabled: Bool,
        reminderTimeMinutes: Int,
        deviceID: String
    ) -> AnniversaryRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return AnniversaryRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            note: note,
            categoryRawValue: category.rawValue,
            occurredAt: occurredAt,
            dateCalendarRawValue: dateCalendar.rawValue,
            isYearly: isYearly,
            leadTimeMinutes: leadTime.rawValue,
            isEnabled: isEnabled,
            reminderTimeMinutes: reminderTimeMinutes
        )
    }

    nonisolated static func minutesSinceStartOfDay(for date: Date, calendar: Calendar = .current) -> Int {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    private func occurrenceDate(matchingYearOf date: Date, calendar: Calendar) -> Date {
        let yearComponents = calendar.dateComponents([.era, .year], from: date)
        return occurrenceDate(era: yearComponents.era, year: yearComponents.year ?? 1, calendar: calendar)
    }

    private func occurrenceDate(era: Int?, year: Int, calendar: Calendar) -> Date {
        let components = calendar.dateComponents([.month, .day, .isLeapMonth], from: occurredDate)
        let month = components.month ?? 1
        let day = components.day ?? 1
        let hour = reminderTimeMinutes / 60
        let minute = reminderTimeMinutes % 60

        var dateComponents = DateComponents(era: era, year: year, month: month, day: day, hour: hour, minute: minute)
        dateComponents.calendar = calendar
        dateComponents.isLeapMonth = components.isLeapMonth

        if let date = calendar.date(from: dateComponents) {
            return date
        }

        dateComponents.isLeapMonth = nil
        if let date = calendar.date(from: dateComponents) {
            return date
        }

        return calendar.date(from: DateComponents(calendar: calendar, era: era, year: year, month: month, day: 28, hour: hour, minute: minute))
            ?? dateByApplyingReminderTime(to: occurredDate, calendar: calendar)
    }

    private func dateByApplyingReminderTime(to date: Date, calendar: Calendar) -> Date {
        calendar.date(
            bySettingHour: reminderTimeMinutes / 60,
            minute: reminderTimeMinutes % 60,
            second: 0,
            of: date
        ) ?? date
    }

    private func days(from startDate: Date, to endDate: Date, calendar: Calendar = .current) -> Int {
        let startDay = calendar.startOfDay(for: startDate)
        let endDay = calendar.startOfDay(for: endDate)
        return calendar.dateComponents([.day], from: startDay, to: endDay).day ?? 0
    }
}
